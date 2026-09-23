package io.floci.az.services.aci;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ResourceIndexContributor;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerStateInfo;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.aci.AciModels.ContainerGroup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * HTTP handler for Azure Container Instances (Microsoft.ContainerInstance/containerGroups)
 * management-plane requests.
 *
 * <h2>Routing</h2>
 * <pre>
 *   GET    subscriptions/{sub}/providers/Microsoft.ContainerInstance/containerGroups                    (list all)
 *   GET    subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerInstance/containerGroups (list in rg)
 *   PUT    .../containerGroups/{name}            create/update
 *   GET    .../containerGroups/{name}
 *   PATCH  .../containerGroups/{name}            update tags
 *   DELETE .../containerGroups/{name}
 *   POST   .../containerGroups/{name}/{start|stop|restart}
 *   GET    .../containerGroups/{name}/containers/{container}/logs
 *   POST   .../containerGroups/{name}/containers/{container}/{exec|attach}   (501)
 *   GET    .../containerGroups/{name}/outboundNetworkDependenciesEndpoints
 *   GET    subscriptions/{sub}/providers/Microsoft.ContainerInstance/locations/{loc}/operations/{opId}
 *   GET    subscriptions/{sub}/providers/Microsoft.ContainerInstance/locations/{loc}/{cachedImages|capabilities|usages}
 * </pre>
 *
 * <h2>LRO shapes</h2>
 * <p>The containerGroups surface polls via the {@code Location} header only (never
 * {@code Azure-AsyncOperation}). PUT returns the full body with {@code provisioningState} and no
 * LRO header, so azurerm's provisioningState poller re-GETs the resource; DELETE is a synchronous
 * 204 (a 202 with no operation endpoint makes azurerm poll forever); {@code start} is 202 and
 * {@code restart} 204, both with a {@code Location} operation URL; {@code stop} is a bare 204,
 * the only synchronous action in the spec.</p>
 *
 * <h2>Mocked mode</h2>
 * <p>When {@code floci-az.services.aci.mocked=true} (default), no Docker container is started.
 * Groups transition immediately to {@code provisioningState=Succeeded} with a synthetic IP and a
 * {@code Running} instance view. This keeps the service usable in CI without Docker.</p>
 */
@ApplicationScoped
public class AciHandler implements AzureServiceHandler, Resettable, ResourceIndexContributor {

    private static final Logger LOG = Logger.getLogger(AciHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String ACI_MARKER = "/providers/Microsoft.ContainerInstance/";
    private static final String API_VERSION = "2023-05-01";
    private static final String TYPE = "Microsoft.ContainerInstance/containerGroups";

    /** Server-side defaults applied when the client omits resource requests (the az CLI does). */
    private static final double DEFAULT_CPU = 1.0;
    private static final double DEFAULT_MEMORY_GB = 1.5;
    private static final String MOCKED_IP = "10.0.0.4";
    private static final int MAX_TRACKED_OPERATIONS = 1024;

    private final Map<String, String> operationOutcomes = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_TRACKED_OPERATIONS;
        }
    };

    private final EmulatorConfig config;
    private final AciContainerGroupManager containerManager;
    private final StorageBackend<String, StoredObject> storage;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aci-readiness-poller");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public AciHandler(EmulatorConfig config, AciContainerGroupManager containerManager,
                      StorageFactory storageFactory) {
        this.config = config;
        this.containerManager = containerManager;
        this.storage = storageFactory.create("aci");
    }

    @PostConstruct
    public void init() {
        if (!mocked()) {
            startReadinessPoller();
        }
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (!mocked()) {
            scanAll().forEach(group -> {
                try {
                    containerManager.removeGroup(group);
                } catch (Exception e) {
                    LOG.warnv("Error removing containers for group {0}: {1}", group.getName(), e.getMessage());
                }
            });
        }
    }

    private boolean mocked() {
        return config.services().aci().mocked();
    }

    @Override
    public String getServiceType() { return "aci"; }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().aci().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .provider("Microsoft.ContainerInstance")
                .build();
    }

    @Override
    public boolean canHandle(AzureRequest req) { return "aci".equals(req.serviceType()); }

    @Override
    public Response handle(AzureRequest req) {
        String fullPath = req.resourcePath();
        String method = req.method().toUpperCase();
        String tail = extractAciPath(fullPath);

        LOG.debugf("AciHandler: %s %s (tail=%s)", method, fullPath, tail);

        // ── LRO operation status ───────────────────────────────────────────────
        // Terminal immediately: every action this handler issues has already run by the time the
        // Location header is returned, so its outcome is known. handleAction is the only thing that
        // issues an operation URL here (PUT returns a body with no LRO header, DELETE is
        // synchronous), so an id this handler does not know is one it never issued: 404, not a
        // guess. Answering an unknown id Succeeded would turn an evicted failure back into a
        // success, which is the whole point of recording outcomes.
        if (tail.matches("locations/[^/]+/operations/[^/?]+.*")) {
            return operationStatusResponse(segment(tail, 3));
        }

        // ── Location-scoped catalogs probed by the CLI/portal ───────────────────
        if (tail.matches("locations/[^/]+/(cachedImages|capabilities|usages)(?:[?].*)?") && "GET".equals(method)) {
            return Response.ok(Map.of("value", List.of())).type("application/json").build();
        }

        // ── List in subscription ───────────────────────────────────────────────
        if (tail.matches("containerGroups(?:[?].*)?") && !fullPath.contains("/resourceGroups/")) {
            return handleList(extractSubscriptionId(fullPath), null);
        }

        // ── List in resource group ─────────────────────────────────────────────
        if (tail.matches("containerGroups(?:[?].*)?") && "GET".equals(method)) {
            return handleList(extractSubscriptionId(fullPath), extractResourceGroup(fullPath));
        }

        String sub = extractSubscriptionId(fullPath);
        String rg = extractResourceGroup(fullPath);

        // ── Container sub-resources: logs / exec / attach ──────────────────────
        if (tail.matches("containerGroups/[^/]+/containers/[^/]+/logs(?:[?].*)?") && "GET".equals(method)) {
            return handleLogs(sub, rg, segment(tail, 1), segment(tail, 3), req);
        }
        if (tail.matches("containerGroups/[^/]+/containers/[^/]+/(exec|attach)(?:[?].*)?") && "POST".equals(method)) {
            return ArmErrors.error(501, "NotImplemented",
                    "Container " + segment(tail, 4) + " is not supported by the floci-az emulator.");
        }

        // ── outboundNetworkDependenciesEndpoints — always an empty array per spec ──
        if (tail.matches("containerGroups/[^/]+/outboundNetworkDependenciesEndpoints(?:[?].*)?") && "GET".equals(method)) {
            return getGroup(storageKey(sub, rg, segment(tail, 1)))
                    .map(group -> Response.ok(List.of()).type("application/json").build())
                    .orElseGet(() -> groupNotFound(rg, segment(tail, 1)));
        }

        // ── Actions ────────────────────────────────────────────────────────────
        if (tail.matches("containerGroups/[^/]+/(start|stop|restart)(?:[?].*)?") && "POST".equals(method)) {
            return handleAction(req, sub, rg, segment(tail, 1), segment(tail, 2));
        }

        // ── Single group CRUD ──────────────────────────────────────────────────
        if (tail.matches("containerGroups/[^/]+(?:[?].*)?")) {
            String name = segment(tail, 1);
            return switch (method) {
                case "GET"    -> handleGet(sub, rg, name);
                case "PUT"    -> handleCreateOrUpdate(sub, rg, name, req);
                case "PATCH"  -> handleUpdateTags(sub, rg, name, req);
                case "DELETE" -> handleDelete(sub, rg, name);
                default       -> ArmErrors.error(405, "MethodNotAllowed", "Method not allowed");
            };
        }

        return ArmErrors.notFound("Unsupported Microsoft.ContainerInstance path: " + tail);
    }

    // ── CRUD ───────────────────────────────────────────────────────────────────

    private Response handleCreateOrUpdate(String sub, String rg, String name, AzureRequest req) {
        try {
            JsonNode body = readBody(req.bodyStream());
            Map<String, Object> properties = objectToMap(body.path("properties"));

            Response validationError = validateProperties(properties);
            if (validationError != null) {
                return validationError;
            }
            normalizeProperties(properties);

            String key = storageKey(sub, rg, name);
            Optional<ContainerGroup> existing = getGroup(key);
            boolean isNew = existing.isEmpty();

            ContainerGroup group = existing.orElseGet(ContainerGroup::new);
            if (isNew) {
                group.setSubscriptionId(sub);
                group.setResourceGroup(rg);
                group.setName(name);
                group.setTimeCreated(Instant.now());
            }
            group.setLocation(body.path("location").asText(group.getLocation() == null ? "eastus" : group.getLocation()));
            Map<String, String> tags = parseTags(body.path("tags"));
            group.setTags(tags.isEmpty() ? null : tags);
            group.setProperties(properties);

            if (mocked()) {
                // Mocked mode: groups are provisioned immediately, no Docker.
                group.setProvisioningState("Succeeded");
            } else if (isNew) {
                // Real mode: launch the backing containers; the readiness poller flips the group
                // to Succeeded once every container runs. A Docker failure is an honest Failed —
                // the azurerm provisioningState poller treats it as terminal (deliberately NOT
                // VM's degrade-to-Succeeded).
                group.setProvisioningState("Creating");
                try {
                    containerManager.startGroup(group);
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to start containers for group %s; provisioningState=Failed", name);
                    group.setProvisioningState("Failed");
                }
            } else {
                // Real-mode update: keep the existing containers (azurerm forces replacement for
                // anything but tags/identity anyway).
                group.setProvisioningState("Succeeded");
            }

            putGroup(key, group);
            return Response.status(isNew ? 201 : 200)
                    .entity(toArmResponse(group, true))
                    .type("application/json")
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Error creating/updating container group %s", name);
            return ArmErrors.error(400, "InvalidRequestContent", "Invalid request: " + e.getMessage());
        }
    }

    private Response handleGet(String sub, String rg, String name) {
        return getGroup(storageKey(sub, rg, name))
                .map(group -> Response.ok(toArmResponse(group, true)).type("application/json").build())
                .orElseGet(() -> groupNotFound(rg, name));
    }

    private Response handleUpdateTags(String sub, String rg, String name, AzureRequest req) {
        String key = storageKey(sub, rg, name);
        Optional<ContainerGroup> found = getGroup(key);
        if (found.isEmpty()) {
            return groupNotFound(rg, name);
        }
        try {
            JsonNode body = readBody(req.bodyStream());
            ContainerGroup group = found.get();
            Map<String, String> tags = parseTags(body.path("tags"));
            group.setTags(tags.isEmpty() ? null : tags);
            putGroup(key, group);
            return Response.ok(toArmResponse(group, true)).type("application/json").build();
        } catch (Exception e) {
            return ArmErrors.error(400, "InvalidRequestContent", "Invalid request: " + e.getMessage());
        }
    }

    private Response handleDelete(String sub, String rg, String name) {
        // Spec-declared codes: 200 with the resource body when the group exists, 204 when it
        // does not ("Resource does not exist"). Synchronous in both cases: azurerm's delete
        // poller GETs until 404, and a 202 with no operation endpoint would poll forever
        // (same rationale as VmHandler). Idempotent when absent.
        String key = storageKey(sub, rg, name);
        Optional<ContainerGroup> existing = getGroup(key);
        if (existing.isEmpty()) {
            return Response.status(204).build();
        }
        // Built before the containers go away: toArmResponse reads live Docker state, so a body
        // rendered after teardown would report the group it just deleted as Stopped/Waiting.
        Object body = toArmResponse(existing.get(), true);
        if (!mocked()) {
            try {
                containerManager.removeGroup(existing.get());
            } catch (Exception e) {
                LOG.warnv("Error removing containers for group {0}: {1}", name, e.getMessage());
            }
        }
        storage.delete(key);
        return Response.ok(body).type("application/json").build();
    }

    private Response handleList(String sub, String rg) {
        String prefix = rg == null ? (sub + "/").toLowerCase() : (sub + "/" + rg + "/").toLowerCase();
        List<Map<String, Object>> items = new ArrayList<>();
        scanAll().stream()
                .filter(group -> group.storageKey().toLowerCase().startsWith(prefix))
                // List responses use the spec's ListResultContainerGroup shape: the full
                // resource minus instanceView.
                .forEach(group -> items.add(toArmResponse(group, false)));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    private Response handleAction(AzureRequest req, String sub, String rg, String name, String action) {
        Optional<ContainerGroup> found = getGroup(storageKey(sub, rg, name));
        if (found.isEmpty()) {
            return groupNotFound(rg, name);
        }
        ContainerGroup group = found.get();
        boolean succeeded = true;
        if (!mocked()) {
            // Map the action onto the backing containers. The shared lifecycle helpers log and
            // swallow Docker errors, so each call reports the state the containers actually
            // reached; that outcome is what the operation status below must tell the client.
            succeeded = switch (action) {
                case "stop"    -> containerManager.stopGroupContainers(group);
                case "restart" -> containerManager.restartGroupContainers(group);
                default        -> containerManager.startGroupContainers(group);
            };
            if (!succeeded) {
                LOG.warnv("Action {0} on container group {1} did not reach its expected state",
                        action, group.getName());
            }
        }
        return switch (action) {
            // The spec's only synchronous action: bare 204, no LRO headers.
            case "stop"    -> Response.status(204).build();
            // Async per spec, final-state-via: location. start → 202, restart → 204 (the spec
            // signals restart's LRO on a 204 — unusual but per ContainerGroup.tsp).
            case "start"   -> asyncActionResponse(req, 202, sub, group.getLocation(), succeeded);
            default        -> asyncActionResponse(req, 204, sub, group.getLocation(), succeeded);
        };
    }

    private Response handleLogs(String sub, String rg, String name, String containerName, AzureRequest req) {
        Optional<ContainerGroup> found = getGroup(storageKey(sub, rg, name));
        if (found.isEmpty()) {
            return groupNotFound(rg, name);
        }
        if (findContainer(found.get(), containerName).isEmpty()) {
            return ArmErrors.notFound("The container '" + containerName
                    + "' was not found in container group '" + name + "'.");
        }
        if (mocked()) {
            // No backing container in mocked mode, so there is no log stream.
            return Response.ok(Map.of("content", "")).type("application/json").build();
        }
        Integer tail = null;
        String tailParam = req.queryParams() == null ? null : req.queryParams().get("tail");
        if (tailParam != null && tailParam.matches("\\d+")) {
            tail = Integer.parseInt(tailParam);
        }
        boolean timestamps = req.queryParams() != null
                && "true".equalsIgnoreCase(req.queryParams().get("timestamps"));
        String content = containerManager.logs(found.get(), containerName, tail, timestamps);
        return Response.ok(Map.of("content", content)).type("application/json").build();
    }

    // ── Validation & normalization ─────────────────────────────────────────────

    /** Rejects requests the emulator cannot honor; returns null when the body is acceptable. */
    private Response validateProperties(Map<String, Object> properties) {
        List<Map<String, Object>> containers = listOfMaps(properties.get("containers"));
        if (containers.isEmpty()) {
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The 'properties.containers' of container group is invalid: at least one container is required.");
        }
        for (Map<String, Object> container : containers) {
            if (!(container.get("properties") instanceof Map<?, ?> props) || asString(props.get("image")) == null) {
                return ArmErrors.error(400, "InvalidRequestContent",
                        "Each container requires 'properties.image'.");
            }
        }
        for (Map<String, Object> volume : listOfMaps(properties.get("volumes"))) {
            if (volume.containsKey("azureFile") || volume.containsKey("gitRepo")) {
                return ArmErrors.error(400, "InvalidRequestContent",
                        "Volume '" + asString(volume.get("name"))
                                + "': azureFile and gitRepo volumes are not supported by the floci-az emulator; "
                                + "use emptyDir or secret volumes.");
            }
        }
        return null;
    }

    /**
     * Applies server-side defaults and canonical enum casing in place, so every read-back
     * satisfies the azurerm provider's flatten code (which panics on missing container ports or
     * resource requests, and diffs on non-canonical casing).
     */
    @SuppressWarnings("unchecked")
    private void normalizeProperties(Map<String, Object> properties) {
        properties.put("osType", canonical(asString(properties.get("osType")), "Linux", "Windows"));
        properties.put("restartPolicy", canonical(asString(properties.get("restartPolicy")), "Always", "OnFailure", "Never"));
        if (properties.get("sku") == null) {
            properties.put("sku", "Standard");
        }

        for (Map<String, Object> container : listOfMaps(properties.get("containers"))) {
            Map<String, Object> props = (Map<String, Object>) container.get("properties");
            if (props.get("ports") == null) {
                props.put("ports", new ArrayList<>());
            }
            for (Map<String, Object> port : listOfMaps(props.get("ports"))) {
                port.put("protocol", canonical(asString(port.get("protocol")), "TCP", "UDP"));
            }
            Map<String, Object> resources = props.get("resources") instanceof Map<?, ?> r
                    ? (Map<String, Object>) r : new LinkedHashMap<>();
            Map<String, Object> requests = resources.get("requests") instanceof Map<?, ?> q
                    ? (Map<String, Object>) q : new LinkedHashMap<>();
            requests.putIfAbsent("cpu", DEFAULT_CPU);
            requests.putIfAbsent("memoryInGB", DEFAULT_MEMORY_GB);
            resources.put("requests", requests);
            props.put("resources", resources);
        }

        if (properties.get("ipAddress") instanceof Map<?, ?> ip) {
            Map<String, Object> ipAddress = (Map<String, Object>) ip;
            ipAddress.put("type", canonical(asString(ipAddress.get("type")), "Public", "Private"));
            if (ipAddress.get("ports") == null) {
                ipAddress.put("ports", new ArrayList<>());
            }
            for (Map<String, Object> port : listOfMaps(ipAddress.get("ports"))) {
                port.put("protocol", canonical(asString(port.get("protocol")), "TCP", "UDP"));
            }
        }
    }

    /** Case-insensitive match against the canonical values; first value is the default. */
    private static String canonical(String value, String... allowed) {
        if (value != null) {
            for (String candidate : allowed) {
                if (candidate.equalsIgnoreCase(value)) {
                    return candidate;
                }
            }
        }
        return allowed[0];
    }

    // ── ARM response builders ──────────────────────────────────────────────────

    private Map<String, Object> toArmResponse(ContainerGroup group, boolean includeInstanceView) {
        Map<String, Object> props = sanitizedProperties(group);
        props.put("provisioningState", group.getProvisioningState());

        if (props.get("ipAddress") instanceof Map<?, ?> ip) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ipAddress = (Map<String, Object>) ip;
            ipAddress.put("ip", mocked() ? MOCKED_IP : containerManager.groupIp(group));
            String dnsNameLabel = asString(ipAddress.get("dnsNameLabel"));
            if (dnsNameLabel != null) {
                ipAddress.put("fqdn", dnsNameLabel + "." + group.getLocation() + ".azurecontainer.io");
            }
        }

        if (includeInstanceView) {
            props.put("instanceView", Map.of(
                    "events", List.of(),
                    "state", groupState(group)));
            for (Map<String, Object> container : listOfMaps(props.get("containers"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> containerProps = (Map<String, Object>) container.get("properties");
                containerProps.put("instanceView",
                        containerInstanceView(group, asString(container.get("name"))));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", group.armId());
        out.put("name", group.getName());
        out.put("type", TYPE);
        out.put("location", group.getLocation());
        if (group.getTags() != null && !group.getTags().isEmpty()) {
            out.put("tags", group.getTags());
        }
        out.put("properties", props);
        return out;
    }

    /** Group-level instanceView state: real container states in unmocked mode. */
    private String groupState(ContainerGroup group) {
        if (mocked()) {
            return "Running";
        }
        if ("Failed".equals(group.getProvisioningState())) {
            return "Failed";
        }
        if (containerManager.isRunning(group)) {
            return "Running";
        }
        // Containers that have all terminated under Never or OnFailure ended the way the policy
        // intends, so the group succeeded or failed on their exit codes rather than being "Stopped".
        // Under Always an exit is a restart the emulator does not perform, so Stopped stays honest.
        List<ContainerStateInfo> states = containerManager.containerStates(group);
        boolean allTerminated = !states.isEmpty()
                && states.stream().allMatch(info -> !info.isRunning() && info.exitCode() != null);
        if (allTerminated && !"Always".equals(restartPolicy(group))) {
            return states.stream().allMatch(info -> info.exitCode() == 0) ? "Succeeded" : "Failed";
        }
        return "Stopped";
    }

    /** The group's restartPolicy, defaulting to the Azure default of Always when unset. */
    private String restartPolicy(ContainerGroup group) {
        Object policy = group.getProperties() == null ? null : group.getProperties().get("restartPolicy");
        return policy == null || String.valueOf(policy).isBlank() ? "Always" : String.valueOf(policy);
    }

    private Map<String, Object> containerInstanceView(ContainerGroup group, String containerName) {
        Map<String, Object> currentState = new LinkedHashMap<>();
        if (!mocked()) {
            Optional<ContainerStateInfo> state = containerManager.containerState(group, containerName);
            if (state.isPresent()) {
                ContainerStateInfo info = state.get();
                currentState.put("state", mapDockerState(info));
                if (info.startedAt() != null && !info.startedAt().isBlank()) {
                    currentState.put("startTime", info.startedAt());
                }
                if (!info.isRunning() && info.exitCode() != null) {
                    currentState.put("exitCode", info.exitCode());
                    if (info.finishedAt() != null && !info.finishedAt().isBlank()) {
                        currentState.put("finishTime", info.finishedAt());
                    }
                }
                currentState.put("detailStatus", "");
                return Map.of("restartCount", 0, "currentState", currentState, "events", List.of());
            }
            // No backing container (e.g. Failed group): report an honest Waiting state.
            currentState.put("state", "Waiting");
            currentState.put("detailStatus", "");
            return Map.of("restartCount", 0, "currentState", currentState, "events", List.of());
        }
        currentState.put("state", "Running");
        if (group.getTimeCreated() != null) {
            currentState.put("startTime",
                    OffsetDateTime.ofInstant(group.getTimeCreated(), ZoneOffset.UTC).toString());
        }
        currentState.put("detailStatus", "");
        return Map.of(
                "restartCount", 0,
                "currentState", currentState,
                "events", List.of());
    }

    /** Docker inspect status → ACI container state vocabulary. */
    private static String mapDockerState(ContainerStateInfo info) {
        return switch (info.status() == null ? "" : info.status().toLowerCase()) {
            case "running" -> "Running";
            case "exited", "dead" -> "Terminated";
            default -> "Waiting";  // created, paused, restarting
        };
    }

    // ── Readiness poller (non-mocked mode) ─────────────────────────────────────

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                scanAll().forEach(group -> {
                    // Every container has started, whether or not they are all running right now:
                    // the deployment is what provisioningState describes. Requiring simultaneous
                    // running left short-lived commands, and Never/OnFailure policies, in Creating
                    // for ever while ARM clients polled a state that could never change.
                    if ("Creating".equals(group.getProvisioningState())
                            && containerManager.allContainersStarted(group)) {
                        group.setProvisioningState("Succeeded");
                        putGroup(group.storageKey(), group);
                        LOG.infov("Container group {0} has started; provisioningState=Succeeded", group.getName());
                    }
                });
            } catch (Exception e) {
                LOG.error("Error in ACI readiness poller", e);
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    /**
     * Deep-copies the stored properties with secrets removed: secure environment variables come
     * back name-only and imageRegistryCredentials lose their password. Returning either value
     * pushes the azurerm provider's state handling into a perpetual diff — and leaks the secret.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizedProperties(ContainerGroup group) {
        Map<String, Object> props = deepCopy(group.getProperties());

        for (Map<String, Object> container : listOfMaps(props.get("containers"))) {
            Map<String, Object> containerProps = (Map<String, Object>) container.get("properties");
            List<Map<String, Object>> envVars = listOfMaps(containerProps.get("environmentVariables"));
            for (Map<String, Object> envVar : envVars) {
                if (envVar.containsKey("secureValue")) {
                    envVar.remove("secureValue");
                    envVar.remove("value");
                }
            }
        }
        for (Map<String, Object> credential : listOfMaps(props.get("imageRegistryCredentials"))) {
            credential.remove("password");
        }
        for (Map<String, Object> volume : listOfMaps(props.get("volumes"))) {
            // Secret volume contents are write-only, like the spec's x-ms-secret marking.
            if (volume.get("secret") instanceof Map<?, ?>) {
                volume.put("secret", Map.of());
            }
        }
        return props;
    }

    private Map<String, Object> deepCopy(Map<String, Object> source) {
        if (source == null) {
            return new LinkedHashMap<>();
        }
        return MAPPER.convertValue(MAPPER.valueToTree(source), Map.class);
    }

    private Response asyncActionResponse(AzureRequest req, int status, String sub, String location,
                                         boolean succeeded) {
        String loc = (location == null || location.isBlank()) ? "eastus" : location;
        String operationId = UUID.randomUUID().toString();
        recordOperation(operationId, succeeded);
        // Built from the caller's own scheme and host, not the configured base URL: the az CLI
        // refuses to send credentials to a non-HTTPS URL, so an http:// operation URL handed to a
        // client that reached us over TLS fails the poll with an authentication error.
        String url = RequestUrls.resolveBaseUrl(req, config) + "/subscriptions/" + sub
                + "/providers/Microsoft.ContainerInstance/locations/" + loc
                + "/operations/" + operationId + "?api-version=" + API_VERSION;
        return Response.status(status)
                .header("Location", url)
                .header("Retry-After", "1")
                .build();
    }

    /**
     * Records the outcome of an action LRO, keeping the newest {@value #MAX_TRACKED_OPERATIONS}.
     * The cap bounds memory in a long-running emulator; it is not a correctness knob, because an
     * evicted id is answered 404 rather than assumed to have succeeded.
     */
    private void recordOperation(String operationId, boolean succeeded) {
        synchronized (operationOutcomes) {
            operationOutcomes.put(operationId, succeeded ? "Succeeded" : "Failed");
        }
    }

    /** 200 with the recorded status, or 404 for an id this handler never issued or has evicted. */
    private Response operationStatusResponse(String operationId) {
        String status;
        synchronized (operationOutcomes) {
            status = operationOutcomes.get(operationId);
        }
        if (status == null) {
            return ArmErrors.notFound("The operation '" + operationId + "' was not found.");
        }
        return Response.ok(Map.of("status", status)).type("application/json").build();
    }

    private Response groupNotFound(String rg, String name) {
        return ArmErrors.notFound("The Resource '" + TYPE + "/" + name
                + "' under resource group '" + rg + "' was not found.");
    }

    private Optional<Map<String, Object>> findContainer(ContainerGroup group, String containerName) {
        return listOfMaps(group.getProperties() == null ? null : group.getProperties().get("containers")).stream()
                .filter(container -> containerName.equals(asString(container.get("name"))))
                .findFirst();
    }

    // ── ResourceIndexContributor ────────────────────────────────────────────────

    @Override
    public boolean indexEnabled() {
        return config.services().aci().enabled();
    }

    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        return indexEntries((sub + "/" + rg + "/").toLowerCase());
    }

    @Override
    public List<Map<String, Object>> listSubscriptionResources(String sub) {
        return indexEntries((sub + "/").toLowerCase());
    }

    private List<Map<String, Object>> indexEntries(String storageKeyPrefix) {
        return scanAll().stream()
                .filter(group -> group.storageKey().toLowerCase().startsWith(storageKeyPrefix))
                .map(ContainerGroup::indexEntry)
                .toList();
    }

    // ── Storage helpers ────────────────────────────────────────────────────────

    private Optional<ContainerGroup> getGroup(String key) {
        return storage.get(key).map(so -> {
            try {
                return MAPPER.readValue(so.data(), ContainerGroup.class);
            } catch (Exception e) {
                LOG.warnv("Failed to deserialize container group {0}: {1}", key, e.getMessage());
                return null;
            }
        });
    }

    private void putGroup(String key, ContainerGroup group) {
        try {
            byte[] data = MAPPER.writeValueAsBytes(group);
            storage.put(key, new StoredObject(key, data, Map.of(), Instant.now(), key));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize container group: " + key, e);
        }
    }

    private List<ContainerGroup> scanAll() {
        List<ContainerGroup> result = new ArrayList<>();
        storage.scan(k -> true).forEach(so -> {
            try {
                ContainerGroup group = MAPPER.readValue(so.data(), ContainerGroup.class);
                if (group != null) { result.add(group); }
            } catch (Exception e) {
                LOG.debugv("Skipping unreadable container group entry: {0}", e.getMessage());
            }
        });
        return result;
    }

    // ── Path / body parsing helpers ────────────────────────────────────────────

    private static String extractAciPath(String fullPath) {
        if (fullPath == null) { return ""; }
        int idx = fullPath.indexOf(ACI_MARKER);
        return idx >= 0 ? fullPath.substring(idx + ACI_MARKER.length()) : fullPath;
    }

    private static String extractSubscriptionId(String fullPath) {
        return ArmPaths.segmentAfter(fullPath, "subscriptions", "default");
    }

    private static String extractResourceGroup(String fullPath) {
        return ArmPaths.segmentAfter(fullPath, "resourcegroups", "default");
    }

    private static String segment(String path, int index) {
        String[] parts = path.split("[/?]");
        return index < parts.length ? parts[index] : "";
    }

    private static String storageKey(String sub, String rg, String name) {
        return sub + "/" + rg + "/" + name;
    }

    private static Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectToMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.convertValue(node, Map.class);
    }

    private JsonNode readBody(java.io.InputStream stream) {
        try {
            if (stream == null || stream.available() == 0) { return MAPPER.createObjectNode(); }
            return MAPPER.readTree(stream);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /** Wipes all container-group data — used by {@code POST /_admin/reset}. */
    @Override
    public void clear() {
        // Container-backed groups own real containers, volumes and host ports. Clearing the ARM
        // records alone would strand them: the ids and port allocations needed to reclaim them live
        // in the records being deleted, so teardown has to happen first.
        if (!mocked()) {
            scanAll().forEach(group -> {
                try {
                    containerManager.removeGroup(group);
                } catch (Exception e) {
                    LOG.warnv("Error removing containers for group {0} during reset: {1}",
                            group.getName(), e.getMessage());
                }
            });
        }
        storage.clear();
    }
}
