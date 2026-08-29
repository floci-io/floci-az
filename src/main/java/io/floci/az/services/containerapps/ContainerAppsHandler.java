package io.floci.az.services.containerapps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ResourceIndexContributor;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.containerapps.ContainerAppsModels.ContainerAppState;
import io.floci.az.services.containerapps.ContainerAppsModels.ManagedEnvironmentState;
import io.floci.az.services.containerapps.ContainerAppsModels.RevisionState;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Azure Container Apps management plane and HTTP ingress. */
@ApplicationScoped
public class ContainerAppsHandler implements AzureServiceHandler, Resettable, ResourceIndexContributor {

    private static final Logger LOG = Logger.getLogger(ContainerAppsHandler.class);
    private static final String PROVIDER = "/providers/Microsoft.App/";
    private static final String ENV_PREFIX = "environment/";
    private static final String APP_PREFIX = "app/";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final EmulatorConfig config;
    private final ContainerAppRuntimeManager runtimeManager;
    private final ContainerAppIngressProxy ingressProxy;
    private final StorageBackend<String, StoredObject> storage;
    private final Map<String, AtomicInteger> trafficCounters = new ConcurrentHashMap<>();

    @Inject
    public ContainerAppsHandler(EmulatorConfig config,
                                ContainerAppRuntimeManager runtimeManager,
                                ContainerAppIngressProxy ingressProxy,
                                StorageFactory storageFactory) {
        this.config = config;
        this.runtimeManager = runtimeManager;
        this.ingressProxy = ingressProxy;
        this.storage = storageFactory.create("containerapps");
    }

    @Override
    public String getServiceType() {
        return "containerapps";
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().containerApps().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .provider("Microsoft.App")
                .host("." + config.services().containerApps().dnsSuffix())
                .build();
    }

    @Override
    public boolean canHandle(AzureRequest request) {
        return "containerapps".equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest request) {
        try {
            if (request.resourcePath().contains(PROVIDER)) {
                return handleArm(request);
            }
            return handleIngress(request);
        } catch (InvalidRequestException e) {
            return ArmErrors.error(400, e.code(), e.getMessage());
        } catch (IOException e) {
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The request content was invalid and could not be deserialized.");
        }
    }

    private Response handleArm(AzureRequest request) throws IOException {
        String path = request.resourcePath();
        String tail = providerTail(path);
        String method = request.method().toUpperCase(Locale.ROOT);
        String subscription = ArmPaths.subscription(path, "default");
        String resourceGroup = ArmPaths.resourceGroup(path, "default");

        LOG.debugv("Container Apps ARM request: {0} {1}", method, path);

        if (tail.matches("managedEnvironments/[^/]+/checkNameAvailability")) {
            return checkNameAvailability(method, subscription, resourceGroup, segment(tail, 1), request);
        }
        if ("managedEnvironments".equalsIgnoreCase(tail)) {
            return handleEnvironmentCollection(method, subscription, resourceGroup,
                    path.contains("/resourceGroups/"));
        }
        if (tail.matches("managedEnvironments/[^/]+/storages")) {
            return Response.ok(Map.of("value", List.of())).build();
        }
        if (tail.matches("managedEnvironments/[^/]+")) {
            return handleEnvironment(method, subscription, resourceGroup, segment(tail, 1), request);
        }
        if ("containerApps".equalsIgnoreCase(tail)) {
            return handleAppCollection(method, subscription, resourceGroup,
                    path.contains("/resourceGroups/"));
        }
        if (tail.matches("containerApps/[^/]+/listSecrets") && "POST".equals(method)) {
            return listSecrets(subscription, resourceGroup, segment(tail, 1));
        }
        if (tail.matches("containerApps/[^/]+/revisions")) {
            return listRevisions(method, subscription, resourceGroup, segment(tail, 1));
        }
        if (tail.matches("containerApps/[^/]+/revisions/[^/]+/(activate|deactivate|restart)")) {
            return revisionAction(method, subscription, resourceGroup, segment(tail, 1),
                    segment(tail, 3), segment(tail, 4));
        }
        if (tail.matches("containerApps/[^/]+/revisions/[^/]+")) {
            return getRevision(method, subscription, resourceGroup,
                    segment(tail, 1), segment(tail, 3));
        }
        if (tail.matches("containerApps/[^/]+")) {
            return handleApp(method, subscription, resourceGroup, segment(tail, 1), request);
        }
        return ArmErrors.notFound("Unknown Microsoft.App path: " + tail);
    }

    private Response handleEnvironmentCollection(String method, String subscription,
                                                 String resourceGroup, boolean resourceGroupScoped) {
        if (!"GET".equals(method)) {
            return methodNotAllowed();
        }
        List<ObjectNode> environments = environments().stream()
                .filter(environment -> subscription.equalsIgnoreCase(environment.getSubscriptionId()))
                .filter(environment -> !resourceGroupScoped
                        || resourceGroup.equalsIgnoreCase(environment.getResourceGroup()))
                .map(this::environmentResponse)
                .toList();
        return Response.ok(Map.of("value", environments)).build();
    }

    private Response checkNameAvailability(String method, String subscription, String resourceGroup,
                                           String environmentName, AzureRequest request) throws IOException {
        if (!"POST".equals(method)) {
            return methodNotAllowed();
        }
        if (read(environmentKey(subscription, resourceGroup, environmentName),
                ManagedEnvironmentState.class).isEmpty()) {
            return ArmErrors.notFound("Managed Environment '" + environmentName + "' was not found.");
        }
        ObjectNode body = readObject(request);
        String name = body.path("name").asText();
        String type = body.path("type").asText();
        boolean available = !"Microsoft.App/containerApps".equalsIgnoreCase(type)
                || apps().stream().noneMatch(app -> app.getName().equalsIgnoreCase(name)
                        && environmentId(app).equalsIgnoreCase(
                                environmentId(subscription, resourceGroup, environmentName)));
        return Response.ok(Map.of(
                "nameAvailable", available,
                "reason", available ? "None" : "AlreadyExists",
                "message", available ? "" : "Container App '" + name + "' already exists."))
                .build();
    }

    private Response handleEnvironment(String method, String subscription, String resourceGroup,
                                       String name, AzureRequest request) throws IOException {
        String key = environmentKey(subscription, resourceGroup, name);
        return switch (method) {
            case "GET" -> getEnvironment(key, name);
            case "PUT", "PATCH" -> putEnvironment(key, subscription, resourceGroup, name, request, method);
            case "DELETE" -> deleteEnvironment(key, subscription, resourceGroup, name);
            default -> methodNotAllowed();
        };
    }

    private Response getEnvironment(String key, String name) {
        return read(key, ManagedEnvironmentState.class)
                .map(environment -> Response.ok(environmentResponse(environment)).build())
                .orElseGet(() -> ArmErrors.notFound("Managed Environment '" + name + "' was not found."));
    }

    private Response putEnvironment(String key, String subscription, String resourceGroup,
                                    String name, AzureRequest request, String method) throws IOException {
        Optional<ManagedEnvironmentState> existing = read(key, ManagedEnvironmentState.class);
        ObjectNode incoming = readObject(request);
        ObjectNode document = "PATCH".equals(method) && existing.isPresent()
                ? deepMerge((ObjectNode) existing.get().getDocument().deepCopy(), incoming)
                : incoming;
        if (!document.hasNonNull("location")) {
            document.put("location", existing.map(value -> value.getDocument().path("location").asText("eastus"))
                    .orElse("eastus"));
        }

        ManagedEnvironmentState environment = existing.orElseGet(() ->
                new ManagedEnvironmentState(subscription, resourceGroup, name, document, Instant.now()));
        environment.setDocument(document);
        if (environment.getDefaultDomain() == null || environment.getDefaultDomain().isBlank()) {
            environment.setDefaultDomain(generateDefaultDomain(subscription, resourceGroup, name));
        }
        write(key, environment);
        return Response.status(existing.isPresent() ? 200 : 201)
                .entity(environmentResponse(environment)).build();
    }

    private Response deleteEnvironment(String key, String subscription, String resourceGroup, String name) {
        String environmentId = environmentId(subscription, resourceGroup, name);
        boolean inUse = apps().stream().anyMatch(app -> environmentId.equalsIgnoreCase(environmentId(app)));
        if (inUse) {
            return ArmErrors.error(409, "ManagedEnvironmentInUse",
                    "Managed Environment '" + name + "' still contains Container Apps.");
        }
        storage.delete(key);
        return Response.noContent().build();
    }

    private Response handleAppCollection(String method, String subscription,
                                         String resourceGroup, boolean resourceGroupScoped) {
        if (!"GET".equals(method)) {
            return methodNotAllowed();
        }
        List<ObjectNode> containerApps = apps().stream()
                .filter(app -> subscription.equalsIgnoreCase(app.getSubscriptionId()))
                .filter(app -> !resourceGroupScoped || resourceGroup.equalsIgnoreCase(app.getResourceGroup()))
                .map(this::appResponse)
                .toList();
        return Response.ok(Map.of("value", containerApps)).build();
    }

    private Response handleApp(String method, String subscription, String resourceGroup,
                               String name, AzureRequest request) throws IOException {
        String key = appKey(subscription, resourceGroup, name);
        return switch (method) {
            case "GET" -> getApp(key, name);
            case "PUT", "PATCH" -> putApp(key, subscription, resourceGroup, name, request, method);
            case "DELETE" -> deleteApp(key, name);
            default -> methodNotAllowed();
        };
    }

    private Response getApp(String key, String name) {
        return read(key, ContainerAppState.class)
                .map(app -> Response.ok(appResponse(app)).build())
                .orElseGet(() -> ArmErrors.notFound("Container App '" + name + "' was not found."));
    }

    private synchronized Response putApp(String key, String subscription, String resourceGroup, String name,
                                         AzureRequest request, String method) throws IOException {
        Optional<ContainerAppState> existing = read(key, ContainerAppState.class);
        ObjectNode incoming = readObject(request);
        ObjectNode document = "PATCH".equals(method) && existing.isPresent()
                ? deepMerge((ObjectNode) existing.get().getDocument().deepCopy(), incoming)
                : incoming;
        applyAppDefaults(document, existing);
        validateApp(document);

        ContainerAppState app = existing.orElseGet(() ->
                new ContainerAppState(subscription, resourceGroup, name, document, Instant.now()));
        String previousMode = app.getDocument() == null ? "Single"
                : activeRevisionsMode(app.getDocument());
        JsonNode previousTemplate = app.getDocument() == null
                ? null : app.getDocument().path("properties").path("template");
        JsonNode newTemplate = document.path("properties").path("template");
        boolean templateChanged = existing.isEmpty() || !newTemplate.equals(previousTemplate);
        String newMode = activeRevisionsMode(document);

        app.setDocument(document);
        if (templateChanged) {
            createRevision(app);
        } else if (!previousMode.equalsIgnoreCase(newMode) && "Single".equalsIgnoreCase(newMode)) {
            enforceSingleRevisionMode(app);
        }
        write(key, app);
        return Response.status(existing.isPresent() ? 200 : 201).entity(appResponse(app)).build();
    }

    private void applyAppDefaults(ObjectNode document, Optional<ContainerAppState> existing) {
        if (!document.hasNonNull("location")) {
            document.put("location", existing.map(app -> app.getDocument().path("location").asText("eastus"))
                    .orElse("eastus"));
        }
        ObjectNode properties = document.withObject("/properties");
        if (!properties.hasNonNull("environmentId") && properties.hasNonNull("managedEnvironmentId")) {
            properties.set("environmentId", properties.get("managedEnvironmentId"));
        }
        ObjectNode configuration = properties.withObject("/configuration");
        if (!configuration.hasNonNull("activeRevisionsMode")) {
            configuration.put("activeRevisionsMode", "Single");
        }
    }

    private void validateApp(ObjectNode document) {
        JsonNode properties = document.path("properties");
        String environmentId = properties.path("environmentId").asText();
        if (environmentId.isBlank()) {
            throw new InvalidRequestException("InvalidParameter", "properties.environmentId is required");
        }
        if (environmentById(environmentId).isEmpty()) {
            throw new InvalidRequestException("ManagedEnvironmentNotFound",
                    "Managed Environment '" + environmentId + "' was not found.");
        }
        JsonNode containers = properties.path("template").path("containers");
        if (!containers.isArray() || containers.isEmpty()) {
            throw new InvalidRequestException("InvalidParameter",
                    "properties.template.containers must contain at least one container");
        }
        containers.forEach(container -> {
            if (container.path("name").asText().isBlank() || container.path("image").asText().isBlank()) {
                throw new InvalidRequestException("InvalidParameter", "Container name and image are required");
            }
        });

        JsonNode scale = properties.path("template").path("scale");
        int minReplicas = scale.path("minReplicas").asInt(1);
        int maxReplicas = scale.path("maxReplicas").asInt(Math.max(10, minReplicas));
        if (minReplicas < 0 || maxReplicas < 1 || minReplicas > maxReplicas) {
            throw new InvalidRequestException("InvalidScaleRule",
                    "Scale requires 0 <= minReplicas <= maxReplicas and maxReplicas >= 1");
        }

        JsonNode ingress = properties.path("configuration").path("ingress");
        if (!ingress.isMissingNode() && !ingress.isNull()) {
            int targetPort = ingress.path("targetPort").asInt(0);
            if (targetPort < 1 || targetPort > 65535) {
                throw new InvalidRequestException("InvalidParameter",
                        "properties.configuration.ingress.targetPort must be between 1 and 65535");
            }
            validateTrafficWeights(ingress.path("traffic"));
        }
    }

    private static void validateTrafficWeights(JsonNode traffic) {
        if (!traffic.isArray() || traffic.isEmpty()) {
            return;
        }
        int totalWeight = 0;
        for (JsonNode target : traffic) {
            JsonNode weightNode = target.path("weight");
            int weight = weightNode.asInt(-1);
            if (!weightNode.canConvertToInt() || weight < 0 || weight > 100) {
                throw new InvalidRequestException("InvalidParameter",
                        "Ingress traffic weights must be integers between 0 and 100");
            }
            totalWeight += weight;
        }
        if (totalWeight != 100) {
            throw new InvalidRequestException("InvalidParameter", "Ingress traffic weights must total 100");
        }
    }

    private void createRevision(ContainerAppState app) {
        JsonNode properties = app.getDocument().path("properties");
        JsonNode template = properties.path("template").deepCopy();
        String suffix = template.path("revisionSuffix").asText();
        if (suffix.isBlank()) {
            suffix = String.format("%06d", app.getNextRevision());
            app.setNextRevision(app.getNextRevision() + 1);
        }
        String revisionName = app.getName() + "--" + suffix;
        if (findRevision(app, revisionName).isPresent()) {
            throw new InvalidRequestException("ContainerAppRevisionAlreadyExists",
                    "Revision '" + revisionName + "' already exists.");
        }

        ManagedEnvironmentState environment = environmentById(environmentId(app)).orElseThrow();
        String fqdn = revisionName + "." + defaultDomain(environment);
        int replicaCount = desiredReplicas(template);
        RevisionState revision = new RevisionState(revisionName, template, false, 0, fqdn);
        revision.setProvisioningState("Provisioning");
        app.getRevisions().add(revision);

        try {
            if (!config.services().containerApps().mocked()) {
                runtimeManager.startRevision(app, revision, properties.path("configuration"),
                        replicaCount, targetPort(properties));
            }
            markRevisionReady(revision, replicaCount);
            if ("Single".equalsIgnoreCase(activeRevisionsMode(app.getDocument()))) {
                deactivateOtherRevisions(app, revision);
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to start Container App revision %s", revisionName);
            markRevisionFailed(revision);
        }
    }

    private Response deleteApp(String key, String name) {
        Optional<ContainerAppState> existing = read(key, ContainerAppState.class);
        existing.ifPresent(runtimeManager::stopApp);
        storage.delete(key);
        trafficCounters.remove(key);
        LOG.infov("Deleted Container App {0}", name);
        return Response.noContent().build();
    }

    private Response listSecrets(String subscription, String resourceGroup, String appName) {
        return read(appKey(subscription, resourceGroup, appName), ContainerAppState.class)
                .map(app -> {
                    ArrayNode secrets = MAPPER.createArrayNode();
                    app.getDocument().path("properties").path("configuration").path("secrets")
                            .forEach(secret -> secrets.add(secret.deepCopy()));
                    ObjectNode response = MAPPER.createObjectNode();
                    response.set("value", secrets);
                    return Response.ok(response).build();
                })
                .orElseGet(() -> ArmErrors.notFound("Container App '" + appName + "' was not found."));
    }

    private Response listRevisions(String method, String subscription, String resourceGroup, String appName) {
        if (!"GET".equals(method)) {
            return methodNotAllowed();
        }
        return read(appKey(subscription, resourceGroup, appName), ContainerAppState.class)
                .map(app -> Response.ok(Map.of("value", app.getRevisions().stream()
                        .map(revision -> revisionResponse(app, revision)).toList())).build())
                .orElseGet(() -> ArmErrors.notFound("Container App '" + appName + "' was not found."));
    }

    private Response getRevision(String method, String subscription, String resourceGroup,
                                 String appName, String revisionName) {
        if (!"GET".equals(method)) {
            return methodNotAllowed();
        }
        Optional<ContainerAppState> app = read(appKey(subscription, resourceGroup, appName), ContainerAppState.class);
        if (app.isEmpty()) {
            return ArmErrors.notFound("Container App '" + appName + "' was not found.");
        }
        return findRevision(app.get(), revisionName)
                .map(revision -> Response.ok(revisionResponse(app.get(), revision)).build())
                .orElseGet(() -> ArmErrors.notFound("Revision '" + revisionName + "' was not found."));
    }

    private Response revisionAction(String method, String subscription, String resourceGroup,
                                    String appName, String revisionName, String action) {
        if (!"POST".equals(method)) {
            return methodNotAllowed();
        }
        String key = appKey(subscription, resourceGroup, appName);
        Optional<ContainerAppState> appResult = read(key, ContainerAppState.class);
        if (appResult.isEmpty()) {
            return ArmErrors.notFound("Container App '" + appName + "' was not found.");
        }
        ContainerAppState app = appResult.get();
        Optional<RevisionState> revisionResult = findRevision(app, revisionName);
        if (revisionResult.isEmpty()) {
            return ArmErrors.notFound("Revision '" + revisionName + "' was not found.");
        }
        RevisionState revision = revisionResult.get();

        if ("deactivate".equals(action)) {
            deactivate(app, revision);
        } else if ("activate".equals(action)) {
            activate(app, revision);
        } else {
            deactivate(app, revision);
            activate(app, revision);
        }
        write(key, app);
        return Response.ok().build();
    }

    private void deactivate(ContainerAppState app, RevisionState revision) {
        runtimeManager.stopRevision(app, revision.getName());
        revision.setActive(false);
        revision.setReplicas(0);
        revision.setRunningState("Stopped");
        revision.setHealthState("None");
        revision.setLastActiveTime(Instant.now());
    }

    private void deactivateOtherRevisions(ContainerAppState app, RevisionState revisionToKeep) {
        app.getRevisions().stream()
                .filter(revision -> revision != revisionToKeep && revision.isActive())
                .forEach(revision -> deactivate(app, revision));
    }

    private void enforceSingleRevisionMode(ContainerAppState app) {
        latestActiveRevision(app).ifPresent(revision -> deactivateOtherRevisions(app, revision));
    }

    private static void markRevisionReady(RevisionState revision, int replicaCount) {
        revision.setActive(true);
        revision.setLastActiveTime(null);
        revision.setReplicas(replicaCount);
        revision.setProvisioningState("Provisioned");
        revision.setRunningState("Running");
        revision.setHealthState("Healthy");
    }

    private static void markRevisionFailed(RevisionState revision) {
        revision.setActive(false);
        revision.setProvisioningState("Failed");
        revision.setRunningState("Failed");
        revision.setHealthState("Unhealthy");
        revision.setReplicas(0);
        revision.setLastActiveTime(Instant.now());
    }

    private void activate(ContainerAppState app, RevisionState revision) {
        JsonNode properties = app.getDocument().path("properties");
        boolean singleMode = "Single".equalsIgnoreCase(
                properties.path("configuration").path("activeRevisionsMode").asText("Single"));
        int replicaCount = desiredReplicas(revision.getTemplate());
        revision.setProvisioningState("Provisioning");
        try {
            if (!config.services().containerApps().mocked()) {
                runtimeManager.startRevision(app, revision, properties.path("configuration"),
                        replicaCount, targetPort(properties));
            }
            markRevisionReady(revision, replicaCount);
            if (singleMode) {
                deactivateOtherRevisions(app, revision);
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to activate Container App revision %s", revision.getName());
            markRevisionFailed(revision);
        }
    }

    private Response handleIngress(AzureRequest request) {
        Optional<ContainerAppState> appResult = apps().stream()
                .filter(app -> appFqdn(app).equalsIgnoreCase(
                        request.accountName() + "." + config.services().containerApps().dnsSuffix()))
                .findFirst();
        if (appResult.isEmpty()) {
            return ArmErrors.notFound("Container App ingress host was not found.");
        }

        ContainerAppState app = appResult.get();
        JsonNode properties = app.getDocument().path("properties");
        JsonNode ingress = properties.path("configuration").path("ingress");
        if (ingress.isMissingNode() || ingress.isNull()) {
            return ArmErrors.notFound("Container App has no ingress configured.");
        }
        RevisionState revision = routeRevision(app, ingress).orElse(null);
        if (revision == null) {
            return ArmErrors.error(503, "ContainerAppUnavailable", "No active revision is available.");
        }
        if (config.services().containerApps().mocked()) {
            if (!ingress.path("external").asBoolean(false)
                    && !runtimeManager.isInternalCaller(request.remoteAddress())) {
                return ArmErrors.notFound("Container App ingress host was not found.");
            }
            return ArmErrors.error(503, "ContainerAppMocked",
                    "Container App is configured in mocked mode; ingress data plane is unavailable.");
        }
        if (!isIngressCallerAllowed(ingress, request)) {
            return ArmErrors.notFound("Container App ingress host was not found.");
        }

        Optional<ContainerLifecycleManager.EndpointInfo> endpoint =
                runtimeManager.endpoint(app, revision.getName());
        if (endpoint.isEmpty() && revision.getTemplate().path("scale").path("maxReplicas").asInt(10) > 0) {
            try {
                int replicaCount = Math.max(1, desiredReplicas(revision.getTemplate()));
                runtimeManager.startRevision(app, revision, properties.path("configuration"),
                        replicaCount, targetPort(properties));
                revision.setReplicas(replicaCount);
                write(appKey(app.getSubscriptionId(), app.getResourceGroup(), app.getName()), app);
                endpoint = runtimeManager.endpoint(app, revision.getName());
            } catch (RuntimeException e) {
                LOG.errorf(e, "Failed to scale Container App revision %s from zero", revision.getName());
            }
        }
        return endpoint.map(value -> ingressProxy.proxy(request, value))
                .orElseGet(() -> ArmErrors.error(503, "ContainerAppUnavailable",
                        "Active revision has no running ingress replica."));
    }

    private boolean isIngressCallerAllowed(JsonNode ingress, AzureRequest request) {
        return ingress.path("external").asBoolean(false)
                || runtimeManager.isInternalCaller(request.remoteAddress());
    }

    Optional<RevisionState> routeRevision(ContainerAppState app, JsonNode ingress) {
        JsonNode traffic = ingress.path("traffic");
        if (traffic.isArray() && !traffic.isEmpty()) {
            Map<RevisionState, Integer> weighted = new LinkedHashMap<>();
            for (JsonNode target : traffic) {
                Optional<RevisionState> candidate = target.path("latestRevision").asBoolean(false)
                        ? latestActiveRevision(app)
                        : findRevision(app, target.path("revisionName").asText());
                int weight = target.path("weight").asInt(0);
                if (candidate.isPresent() && candidate.get().isActive() && weight > 0) {
                    weighted.merge(candidate.get(), weight, Integer::sum);
                }
            }
            int totalWeight = weighted.values().stream().mapToInt(Integer::intValue).sum();
            if (totalWeight > 0) {
                String counterKey = appKey(app.getSubscriptionId(), app.getResourceGroup(), app.getName());
                AtomicInteger counter = trafficCounters.computeIfAbsent(counterKey, ignored -> new AtomicInteger());
                int selectedWeight = Math.floorMod(counter.getAndIncrement(), totalWeight);
                int cumulativeWeight = 0;
                for (Map.Entry<RevisionState, Integer> target : weighted.entrySet()) {
                    cumulativeWeight += target.getValue();
                    if (selectedWeight < cumulativeWeight) {
                        return Optional.of(target.getKey());
                    }
                }
            }
        }
        return latestActiveRevision(app);
    }

    private ObjectNode environmentResponse(ManagedEnvironmentState environment) {
        ObjectNode response = (ObjectNode) environment.getDocument().deepCopy();
        response.put("id", environmentId(environment.getSubscriptionId(), environment.getResourceGroup(),
                environment.getName()));
        response.put("name", environment.getName());
        response.put("type", "Microsoft.App/managedEnvironments");
        ObjectNode properties = response.withObject("/properties");
        hideEnvironmentSecretValues(properties);
        properties.put("provisioningState", "Succeeded");
        properties.put("defaultDomain", defaultDomain(environment));
        properties.put("staticIp", "127.0.0.1");
        if (!properties.has("zoneRedundant")) {
            properties.put("zoneRedundant", false);
        }
        return response;
    }

    private static void hideEnvironmentSecretValues(ObjectNode properties) {
        properties.remove(List.of("daprAIConnectionString", "daprAIInstrumentationKey"));
        JsonNode logAnalytics = properties.path("appLogsConfiguration").path("logAnalyticsConfiguration");
        if (logAnalytics instanceof ObjectNode configuration) {
            configuration.remove("sharedKey");
        }
    }

    private ObjectNode appResponse(ContainerAppState app) {
        ObjectNode response = (ObjectNode) app.getDocument().deepCopy();
        response.put("id", appId(app));
        response.put("name", app.getName());
        response.put("type", "Microsoft.App/containerApps");
        ObjectNode properties = response.withObject("/properties");
        Optional<RevisionState> latest = latestRevision(app);
        Optional<RevisionState> latestReady = latestReadyRevision(app);
        String provisioningState = latest.map(RevisionState::getProvisioningState)
                .filter("Failed"::equals).isPresent() ? "Failed" : "Succeeded";
        properties.put("provisioningState", provisioningState);
        properties.put("runningStatus", app.getRevisions().stream().anyMatch(revision -> revision.isActive()
                && "Running".equals(revision.getRunningState())) ? "Running" : "Stopped");
        properties.put("customDomainVerificationId", Integer.toHexString(appId(app).hashCode()));
        latest.ifPresent(revision -> {
            properties.put("latestRevisionName", revision.getName());
            properties.put("latestRevisionFqdn", revision.getFqdn());
        });
        if (latestReady.isPresent()) {
            properties.put("latestReadyRevisionName", latestReady.get().getName());
        } else {
            properties.remove("latestReadyRevisionName");
        }
        properties.putArray("outboundIpAddresses").add("127.0.0.1");

        ObjectNode configuration = properties.withObject("/configuration");
        hideSecretValues(configuration);
        JsonNode ingressNode = configuration.get("ingress");
        if (ingressNode instanceof ObjectNode ingress) {
            ingress.put("fqdn", appFqdn(app));
        }
        return response;
    }

    private ObjectNode revisionResponse(ContainerAppState app, RevisionState revision) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("id", appId(app) + "/revisions/" + revision.getName());
        response.put("name", revision.getName());
        response.put("type", "Microsoft.App/containerApps/revisions");
        ObjectNode properties = response.putObject("properties");
        properties.put("active", revision.isActive());
        properties.put("createdTime", revision.getCreatedTime().toString());
        if (revision.getLastActiveTime() != null) {
            properties.put("lastActiveTime", revision.getLastActiveTime().toString());
        }
        properties.put("fqdn", revision.getFqdn());
        properties.put("healthState", revision.getHealthState());
        properties.put("provisioningState", revision.getProvisioningState());
        properties.put("runningState", revision.getRunningState());
        properties.put("replicas", revision.getReplicas());
        properties.put("trafficWeight", trafficWeight(app, revision));
        properties.set("template", revision.getTemplate().deepCopy());
        return response;
    }

    private int trafficWeight(ContainerAppState app, RevisionState revision) {
        JsonNode traffic = app.getDocument().path("properties").path("configuration")
                .path("ingress").path("traffic");
        for (JsonNode target : traffic) {
            if (revision.getName().equals(target.path("revisionName").asText())) {
                return target.path("weight").asInt(0);
            }
            if (target.path("latestRevision").asBoolean(false)
                    && latestActiveRevision(app).filter(value -> value == revision).isPresent()) {
                return target.path("weight").asInt(0);
            }
        }
        return latestActiveRevision(app).filter(value -> value == revision).isPresent() ? 100 : 0;
    }

    private void hideSecretValues(ObjectNode configuration) {
        JsonNode secrets = configuration.path("secrets");
        if (secrets.isArray()) {
            secrets.forEach(secret -> {
                if (secret instanceof ObjectNode object) {
                    object.remove("value");
                }
            });
        }
    }

    private String appFqdn(ContainerAppState app) {
        return environmentById(environmentId(app))
                .map(environment -> app.getName() + "." + defaultDomain(environment))
                .orElse(app.getName() + "." + config.services().containerApps().dnsSuffix());
    }

    private String defaultDomain(ManagedEnvironmentState environment) {
        String persisted = environment.getDefaultDomain();
        return persisted == null || persisted.isBlank()
                ? generateDefaultDomain(environment.getSubscriptionId(), environment.getResourceGroup(),
                        environment.getName())
                : persisted;
    }

    private Optional<ManagedEnvironmentState> environmentById(String id) {
        return environments().stream()
                .filter(environment -> environmentId(environment.getSubscriptionId(),
                        environment.getResourceGroup(), environment.getName()).equalsIgnoreCase(id))
                .findFirst();
    }

    private static Optional<RevisionState> findRevision(ContainerAppState app, String name) {
        return app.getRevisions().stream().filter(revision -> revision.getName().equalsIgnoreCase(name)).findFirst();
    }

    private static Optional<RevisionState> latestRevision(ContainerAppState app) {
        return app.getRevisions().stream().max(Comparator.comparing(RevisionState::getCreatedTime));
    }

    private static Optional<RevisionState> latestActiveRevision(ContainerAppState app) {
        return app.getRevisions().stream().filter(RevisionState::isActive)
                .max(Comparator.comparing(RevisionState::getCreatedTime));
    }

    private static Optional<RevisionState> latestReadyRevision(ContainerAppState app) {
        return app.getRevisions().stream()
                .filter(revision -> "Provisioned".equals(revision.getProvisioningState()))
                .filter(revision -> "Healthy".equals(revision.getHealthState()))
                .max(Comparator.comparing(RevisionState::getCreatedTime));
    }

    private static String activeRevisionsMode(JsonNode document) {
        return document.path("properties").path("configuration")
                .path("activeRevisionsMode").asText("Single");
    }

    private String generateDefaultDomain(String subscription, String resourceGroup, String name) {
        String environmentId = environmentId(subscription, resourceGroup, name).toLowerCase(Locale.ROOT);
        String uniqueLabel = UUID.nameUUIDFromBytes(environmentId.getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
        return name + "." + uniqueLabel + "." + config.services().containerApps().dnsSuffix();
    }

    private static int desiredReplicas(JsonNode template) {
        JsonNode scale = template.path("scale");
        int min = scale.path("minReplicas").asInt(1);
        int max = scale.path("maxReplicas").asInt(Math.max(10, min));
        return Math.min(min, max);
    }

    private static int targetPort(JsonNode properties) {
        return properties.path("configuration").path("ingress").path("targetPort").asInt(0);
    }

    private static String environmentId(ContainerAppState app) {
        return app.getDocument().path("properties").path("environmentId").asText();
    }

    private static String environmentId(String subscription, String resourceGroup, String name) {
        return "/subscriptions/" + subscription + "/resourceGroups/" + resourceGroup
                + "/providers/Microsoft.App/managedEnvironments/" + name;
    }

    private static String appId(ContainerAppState app) {
        return "/subscriptions/" + app.getSubscriptionId() + "/resourceGroups/" + app.getResourceGroup()
                + "/providers/Microsoft.App/containerApps/" + app.getName();
    }

    private static String environmentKey(String subscription, String resourceGroup, String name) {
        return ENV_PREFIX + subscription.toLowerCase(Locale.ROOT) + "/"
                + resourceGroup.toLowerCase(Locale.ROOT) + "/" + name.toLowerCase(Locale.ROOT);
    }

    private static String appKey(String subscription, String resourceGroup, String name) {
        return APP_PREFIX + subscription.toLowerCase(Locale.ROOT) + "/"
                + resourceGroup.toLowerCase(Locale.ROOT) + "/" + name.toLowerCase(Locale.ROOT);
    }

    private List<ManagedEnvironmentState> environments() {
        return scan(ENV_PREFIX, ManagedEnvironmentState.class);
    }

    private List<ContainerAppState> apps() {
        return scan(APP_PREFIX, ContainerAppState.class);
    }

    @Override
    public boolean indexEnabled() {
        return config.services().containerApps().enabled();
    }

    @Override
    public List<Map<String, Object>> listRgResources(String subscription, String resourceGroup) {
        List<Map<String, Object>> resources = new ArrayList<>();
        environments().stream()
                .filter(environment -> subscription.equalsIgnoreCase(environment.getSubscriptionId()))
                .filter(environment -> resourceGroup.equalsIgnoreCase(environment.getResourceGroup()))
                .map(environment -> indexEntry(
                        environmentId(subscription, resourceGroup, environment.getName()),
                        environment.getName(), "Microsoft.App/managedEnvironments", environment.getDocument()))
                .forEach(resources::add);
        apps().stream()
                .filter(app -> subscription.equalsIgnoreCase(app.getSubscriptionId()))
                .filter(app -> resourceGroup.equalsIgnoreCase(app.getResourceGroup()))
                .map(app -> indexEntry(appId(app), app.getName(), "Microsoft.App/containerApps", app.getDocument()))
                .forEach(resources::add);
        return resources;
    }

    private static Map<String, Object> indexEntry(String id, String name, String type, JsonNode document) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("name", name);
        entry.put("type", type);
        entry.put("location", document.path("location").asText());
        JsonNode tags = document.get("tags");
        if (tags != null && tags.isObject()) {
            entry.put("tags", MAPPER.convertValue(tags, Map.class));
        }
        return entry;
    }

    private <T> List<T> scan(String prefix, Class<T> type) {
        List<T> values = new ArrayList<>();
        storage.scan(key -> key.startsWith(prefix)).forEach(stored -> {
            try {
                values.add(MAPPER.readValue(stored.data(), type));
            } catch (IOException e) {
                LOG.warnv("Skipping unreadable Container Apps state {0}: {1}", stored.key(), e.getMessage());
            }
        });
        return values;
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        return storage.get(key).flatMap(stored -> {
            try {
                return Optional.of(MAPPER.readValue(stored.data(), type));
            } catch (IOException e) {
                LOG.warnv("Failed to deserialize Container Apps state {0}: {1}", key, e.getMessage());
                return Optional.empty();
            }
        });
    }

    private void write(String key, Object value) {
        try {
            storage.put(key, new StoredObject(key, MAPPER.writeValueAsBytes(value), Map.of(),
                    Instant.now(), Integer.toHexString(value.hashCode())));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize Container Apps state " + key, e);
        }
    }

    private static ObjectNode readObject(AzureRequest request) throws IOException {
        JsonNode body = request.bodyStream() == null ? null : MAPPER.readTree(request.bodyStream());
        if (!(body instanceof ObjectNode object)) {
            throw new IOException("Expected JSON object");
        }
        return object;
    }

    private static ObjectNode deepMerge(ObjectNode target, ObjectNode update) {
        update.fields().forEachRemaining(entry -> {
            JsonNode existing = target.get(entry.getKey());
            if (existing instanceof ObjectNode existingObject && entry.getValue() instanceof ObjectNode updateObject) {
                deepMerge(existingObject, updateObject);
            } else {
                target.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        return target;
    }

    private static String providerTail(String path) {
        String tail = ArmPaths.afterSegment(path, PROVIDER, "");
        return tail.replaceAll("/+$", "");
    }

    private static String segment(String path, int index) {
        String[] segments = path.split("/");
        return index < segments.length ? segments[index] : "";
    }

    private static Response methodNotAllowed() {
        return ArmErrors.error(405, "MethodNotAllowed", "The requested method is not allowed.");
    }

    @PreDestroy
    void shutdown() {
        runtimeManager.stopAll();
    }

    @Override
    public void clear() {
        runtimeManager.stopAll();
        trafficCounters.clear();
        storage.clear();
    }

    private static final class InvalidRequestException extends RuntimeException {
        private final String code;

        private InvalidRequestException(String code, String message) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
