package io.floci.az.services.apim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.arm.GlobalNameSource;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudDefaults;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.apim.ApiManagementModels.ApimDeletedService;
import io.floci.az.services.apim.ApiManagementModels.ApimService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ApiManagementService implements GlobalNameSource {

    private static final Logger LOG = Logger.getLogger(ApiManagementService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Every service, keyed by its canonical id. Services were kept in memory before this store, so no entry was ever
     * written under another key shape and there are no legacy keys to read.
     */
    private final ArmResourceStore<ApimService> services;

    /**
     * {@code DeletedServices_GetByName} and {@code _Purge}: a deleted service stays recoverable for
     * {@link #SOFT_DELETE_RETENTION}. The children of a deleted service stay in their maps until it is purged, restored
     * over, or replaced by a plain create.
     */
    private final ArmResourceStore<ApimDeletedService> deletedServices;

    /** How long Azure keeps a deleted service: the az CLI recording schedules the purge 48 hours after the delete. */
    static final Duration SOFT_DELETE_RETENTION = Duration.ofHours(48);
    private final Map<String, Map<String, Object>> apis;
    private final Map<String, Map<String, Object>> operations;
    private final Map<String, Map<String, Object>> policies;
    private final Map<String, Map<String, Object>> products;
    private final Map<String, Map<String, Object>> subscriptions;
    private final Map<String, Map<String, Object>> namedValues;
    private final Map<String, Map<String, Object>> backends;
    private final Map<String, String> productApis;
    private final EmulatorConfig config;
    private final GlobalNameRegistry names;
    private final CloudDefaults defaults;

    @Inject
    public ApiManagementService(EmulatorConfig config, ApiManagementStore store, StorageFactory storageFactory,
                                GlobalNameRegistry names, CloudDefaults defaults) {
        this.config = config;
        this.names = names;
        this.defaults = defaults;
        this.services = ArmResourceStore.create(storageFactory, "apim", ApiManagementModels.SERVICE_TYPE,
                ApimService.class, ApimService::id, key -> false);
        this.deletedServices = ArmResourceStore.create(storageFactory, "apim",
                ApiManagementModels.DELETED_SERVICE_ID_TYPE, ApimDeletedService.class, ApimDeletedService::id,
                key -> false);
        this.apis = store.apis();
        this.operations = store.operations();
        this.policies = store.policies();
        this.products = store.products();
        this.subscriptions = store.subscriptions();
        this.namedValues = store.namedValues();
        this.backends = store.backends();
        this.productApis = store.productApis();
    }

    public Response handleArm(AzureRequest request, String path, String method, String sub) {
        String tail = ArmPaths.afterProvider(path, "Microsoft.ApiManagement", "unknown");
        String[] parts = split(tail);
        Optional<String> group = ArmPaths.resourceGroup(path);
        if (group.isEmpty()) {
            return handleSubscriptionPath(request, path, method, sub, parts);
        }
        String rg = group.get();

        if (parts.length == 1 && "service".equals(parts[0])) {
            return Response.ok(Map.of("value", listServices(sub, rg))).build();
        }
        if (parts.length < 2 || !"service".equals(parts[0])) {
            return notFound(path);
        }

        ArmResourceId requested = serviceId(sub, rg, parts[1]);
        if (parts.length == 2) {
            return switch (method) {
                case "PUT" -> createOrUpdateService(request, requested);
                case "GET" -> services.get(requested)
                        .map(stored -> Response.ok(stored.value().toArm()).build())
                        .orElseGet(() -> notFound("service/" + requested.name()));
                case "DELETE" -> {
                    deleteService(request, requested);
                    yield Response.ok().build();
                }
                default -> Response.status(405).build();
            };
        }
        Optional<ArmResourceId> live = services.get(requested).map(ArmResourceStore.Stored::id);
        if (live.isEmpty() && !deletedServicesNamed(cloud(request), sub, requested.name()).isEmpty()) {
            // The children of a soft-deleted service wait for its restore; until then the service is not there.
            return notFound("service/" + requested.name());
        }
        return handleChild(request, path, method, live.orElse(requested), parts);
    }

    /**
     * A subscription-level path: {@code ApiManagementService_List}, every service in the subscription, and
     * {@code ApiManagementService_CheckNameAvailability}. Any other path is ARM's 404.
     */
    private Response handleSubscriptionPath(AzureRequest request, String path, String method, String sub,
                                            String[] parts) {
        if (parts.length == 1 && "service".equalsIgnoreCase(parts[0])) {
            return "GET".equals(method)
                    ? Response.ok(Map.of("value", listSubscriptionServices(sub))).build()
                    : Response.status(405).build();
        }
        if (parts.length == 1 && "checkNameAvailability".equalsIgnoreCase(parts[0])) {
            return "POST".equals(method) ? checkNameAvailability(request) : Response.status(405).build();
        }
        if (parts.length == 1 && "deletedservices".equalsIgnoreCase(parts[0])) {
            return "GET".equals(method) ? listDeletedServices(cloud(request), sub) : Response.status(405).build();
        }
        if (parts.length == 4 && "locations".equalsIgnoreCase(parts[0])
                && "deletedservices".equalsIgnoreCase(parts[2])) {
            // A client builds this path from the service's location, which Azure answers as a display name
            // ("West US"), so the segment arrives escaped.
            ArmResourceId id = deletedServiceId(request, sub, URLDecoder.decode(parts[1], StandardCharsets.UTF_8),
                    parts[3]);
            return switch (method) {
                case "GET" -> liveDeletedService(cloud(request), id)
                        .map(deleted -> Response.ok(deleted.toArm()).build())
                        .orElseGet(() -> notFound("deletedservices/" + id.name()));
                case "DELETE" -> purgeDeletedService(cloud(request), id);
                default -> Response.status(405).build();
            };
        }
        return notFound(path);
    }

    // ── Deleted services ────────────────────────────────────────────────────

    /** {@code DeletedServices_ListBySubscription}: one page holds them all, so there is no {@code nextLink}. */
    private Response listDeletedServices(CloudProfile cloud, String sub) {
        dropExpiredDeletedServices(cloud);
        return Response.ok(Map.of("value", deletedServices.list(new ArmScope(sub, null)).stream()
                .map(stored -> stored.value().toArm()).toList())).build();
    }

    /**
     * {@code DeletedServices_Purge}. The spec declares 200 for a purged service and 204 for one that does not exist;
     * both are terminal, so a client's poller finishes at once. Azure itself answers 202 and polls.
     */
    private synchronized Response purgeDeletedService(CloudProfile cloud, ArmResourceId id) {
        Optional<ApimDeletedService> deleted = liveDeletedService(cloud, id);
        if (deleted.isEmpty()) {
            return Response.noContent().build();
        }
        forget(cloud, id, deleted.get());
        LOG.infov("ARM: purged deleted API Management service {0}", id.name());
        return Response.ok().build();
    }

    /**
     * Drops a deleted service for good: its record, its children, and the {@code azure-api.net} name it kept while it
     * could still be restored.
     */
    private void forget(CloudProfile cloud, ArmResourceId deletedId, ApimDeletedService deleted) {
        deletedServices.delete(deletedId);
        ArmResourceId service = serviceId(deleted.service().id());
        dropChildren(service);
        names.release(cloud, CloudSuffix.APIM, deleted.name(), service);
    }

    /** The deleted service {@code id} names, unless its purge date has passed. */
    private Optional<ApimDeletedService> liveDeletedService(CloudProfile cloud, ArmResourceId id) {
        dropExpiredDeletedServices(cloud);
        return deletedServices.get(id).map(ArmResourceStore.Stored::value);
    }

    /** Records past their purge date go, with their children, the next time anything reads the deleted services. */
    private synchronized void dropExpiredDeletedServices(CloudProfile cloud) {
        Instant now = Instant.now();
        for (ArmResourceStore.Stored<ApimDeletedService> expired
                : deletedServices.deleteIf(deleted -> deleted.scheduledPurgeDate().isBefore(now))) {
            ArmResourceId service = serviceId(expired.value().service().id());
            dropChildren(service);
            names.release(cloud, CloudSuffix.APIM, expired.value().name(), service);
        }
    }

    /** The subscription's deleted services of this name: a location is part of their ids, but a name is one service. */
    private List<ArmResourceStore.Stored<ApimDeletedService>> deletedServicesNamed(CloudProfile cloud, String sub,
                                                                                   String name) {
        dropExpiredDeletedServices(cloud);
        return deletedServices.list(new ArmScope(sub, null)).stream()
                .filter(stored -> name.equalsIgnoreCase(stored.id().name()))
                .toList();
    }

    private static ArmResourceId deletedServiceId(AzureRequest request, String sub, String location, String name) {
        return ArmResourceId.parse("/subscriptions/" + sub + "/providers/Microsoft.ApiManagement/locations/"
                + CloudCatalog.normalizeLocation(cloud(request), location) + "/deletedservices/" + name)
                .orElseThrow(() -> new IllegalArgumentException("not a deleted service: " + name));
    }

    /** The display name of {@code location} in the request's cloud ({@code West US}), or the value itself. */
    private static String locationDisplayName(AzureRequest request, String location) {
        String name = CloudCatalog.normalizeLocation(cloud(request), location);
        return cloud(request).locations().stream()
                .filter(candidate -> candidate.name().equals(name))
                .map(CloudProfile.Location::displayName)
                .findFirst()
                .orElse(location);
    }

    /**
     * A request below a service. The children are keyed by the service's own casing, so a path that differs from it
     * only in case reaches the same children.
     */
    private Response handleChild(AzureRequest request, String path, String method, ArmResourceId service,
                                 String[] parts) {
        String sub = service.subscriptionId();
        String rg = service.resourceGroup();
        String serviceName = service.name();

        if (parts.length >= 3 && "policies".equals(parts[2])) {
            return handlePolicy(request, method, serviceKey(sub, rg, serviceName),
                    "/subscriptions/" + sub + "/resourceGroups/" + rg
                            + "/providers/Microsoft.ApiManagement/service/" + serviceName,
                    "Microsoft.ApiManagement/service/policies", parts);
        }

        if (parts.length >= 3 && "products".equals(parts[2])) {
            return handleProducts(request, method, sub, rg, serviceName, parts);
        }

        if (parts.length >= 3 && "subscriptions".equals(parts[2])) {
            return handleSubscriptions(request, method, sub, rg, serviceName, parts);
        }

        if (parts.length >= 3 && "namedValues".equals(parts[2])) {
            return handleNamedValues(request, method, sub, rg, serviceName, parts);
        }

        if (parts.length >= 3 && "backends".equals(parts[2])) {
            return handleBackends(request, method, sub, rg, serviceName, parts);
        }

        if (parts.length >= 3 && "apis".equals(parts[2])) {
            if (parts.length == 3) {
                return Response.ok(Map.of("value", listApis(sub, rg, serviceName))).build();
            }
            String apiId = parts[3];
            if (parts.length == 4) {
                return switch (method) {
                    case "PUT" -> createOrUpdateApi(request, sub, rg, serviceName, apiId);
                    case "GET" -> getResource(apiKey(sub, rg, serviceName, apiId), apis, "apis/" + apiId);
                    case "DELETE" -> {
                        deleteApi(sub, rg, serviceName, apiId);
                        yield Response.ok().build();
                    }
                    default -> Response.status(405).build();
                };
            }
            if (parts.length >= 5 && "policies".equals(parts[4])) {
                return handlePolicy(request, method, apiKey(sub, rg, serviceName, apiId),
                        "/subscriptions/" + sub + "/resourceGroups/" + rg
                                + "/providers/Microsoft.ApiManagement/service/" + serviceName + "/apis/" + apiId,
                        "Microsoft.ApiManagement/service/apis/policies", parts);
            }
            if (parts.length >= 5 && "operations".equals(parts[4])) {
                if (parts.length == 5) {
                    return Response.ok(Map.of("value", listOperations(sub, rg, serviceName, apiId))).build();
                }
                String operationId = parts[5];
                if (parts.length == 6) {
                    return switch (method) {
                        case "PUT" -> createOrUpdateOperation(request, sub, rg, serviceName, apiId, operationId);
                        case "GET" -> getResource(operationKey(sub, rg, serviceName, apiId, operationId),
                                operations, "operations/" + operationId);
                        case "DELETE" -> {
                            operations.remove(operationKey(sub, rg, serviceName, apiId, operationId));
                            yield Response.ok().build();
                        }
                        default -> Response.status(405).build();
                    };
                }
                if (parts.length >= 7 && "policies".equals(parts[6])) {
                    return handlePolicy(request, method, operationKey(sub, rg, serviceName, apiId, operationId),
                            "/subscriptions/" + sub + "/resourceGroups/" + rg
                                    + "/providers/Microsoft.ApiManagement/service/" + serviceName
                                    + "/apis/" + apiId + "/operations/" + operationId,
                            "Microsoft.ApiManagement/service/apis/operations/policies", parts);
                }
            }
        }

        return notFound(path);
    }

    /**
     * {@code ApiManagementService_CheckNameAvailability}: a name outside the service name rule is {@code Invalid}, one
     * the name registry holds in any subscription is {@code AlreadyExists}, in the
     * {@code ApiManagementServiceNameAvailabilityResult} shape of the spec's example.
     */
    private Response checkNameAvailability(AzureRequest request) {
        String name = ArmJson.string(ArmJson.parseBodyLenient(request), "name", "");
        if (!ApiManagementArmSurface.SERVICE_NAME.accepts(name)) {
            return nameAvailability(false, "Invalid", ApiManagementArmSurface.SERVICE_NAME.message(name));
        }
        if (names.owner(cloud(request), CloudSuffix.APIM, name).isPresent()) {
            return nameAvailability(false, "AlreadyExists", "The API Management service name '" + name
                    + "' is already in use.");
        }
        return nameAvailability(true, "Valid", "");
    }

    private static Response nameAvailability(boolean available, String reason, String message) {
        return Response.ok(Map.of("nameAvailable", available, "reason", reason, "message", message)).build();
    }

    private static CloudProfile cloud(AzureRequest request) {
        return request.scope() == null
                ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : request.scope().cloud();
    }

    public List<Map<String, Object>> listServices(String sub, String rg) {
        return list(new ArmScope(sub, rg));
    }

    public List<Map<String, Object>> listSubscriptionServices(String sub) {
        return list(new ArmScope(sub, null));
    }

    private List<Map<String, Object>> list(ArmScope scope) {
        return services.list(scope).stream().map(stored -> stored.value().toArm()).toList();
    }

    /** Whether a service of exactly this name exists, in any scope: the gateway knows the name alone. */
    public boolean hasServiceNamed(String serviceName) {
        return services.all().stream().anyMatch(stored -> serviceName.equals(stored.value().name()));
    }

    private synchronized Response createOrUpdateService(AzureRequest request, ArmResourceId requested) {
        Map<String, Object> body = parseBody(request);
        if (services.get(requested).isEmpty()) {
            // Only this service's own record: the deleted service keeps its global name, so the name gate answers
            // a create of the name in any other resource group or subscription with a conflict before it gets here.
            String key = requested.canonicalKey();
            List<ArmResourceStore.Stored<ApimDeletedService>> deleted =
                    deletedServicesNamed(cloud(request), requested.subscriptionId(), requested.name()).stream()
                            .filter(stored -> serviceId(stored.value().service().id()).canonicalKey().equals(key))
                            .toList();
            if (isRestore(body) && !deleted.isEmpty()) {
                return restoreService(deleted.getFirst());
            }
            // No local source says what Azure answers to a plain create of a soft-deleted service, so it is not
            // refused: the deleted service and its children go, and an ordinary create runs (as for Key Vault).
            for (ArmResourceStore.Stored<ApimDeletedService> stale : deleted) {
                deletedServices.delete(stale.id());
                dropChildren(serviceId(stale.value().service().id()));
            }
        }
        ArmResourceStore.Written<ApimService> written = services.createOrUpdate(requested, existing -> {
            // A service keeps the casing of the PUT that created it, as ARM does.
            ArmResourceId id = existing.map(service -> serviceId(service.id())).orElse(requested);
            return newService(id, body, defaults.defaultLocation(request.scope()));
        });
        LOG.infov("ARM: {0} API Management service {1}", written.created() ? "created" : "updated",
                written.stored().value().name());
        return Response.ok(written.stored().value().toArm()).build();
    }

    /** {@code properties.restore: true}: "all other properties will be ignored", so the service comes back as it was. */
    private static boolean isRestore(Map<String, Object> body) {
        return Boolean.TRUE.equals(cast(body.get("properties")).get("restore"));
    }

    private Response restoreService(ArmResourceStore.Stored<ApimDeletedService> deleted) {
        deletedServices.delete(deleted.id());
        ApimService service = services.put(deleted.value().service()).value();
        LOG.infov("ARM: restored API Management service {0}", service.name());
        return Response.ok(service.toArm()).build();
    }

    /**
     * The names live services own, and those deleted services keep until they are purged or expire: azurerm answers
     * a soft-deleted name by recovering it or asking for "a different name/location", and the gateway finds a
     * service's APIs and policies by name alone, so another service must not take it while its children are kept.
     */
    @Override
    public List<Claim> globalNames() {
        List<Claim> claims = new ArrayList<>();
        for (ArmResourceStore.Stored<ApimService> stored : services.all()) {
            claims.add(new Claim(CloudSuffix.APIM, stored.value().name(), stored.id()));
        }
        for (ArmResourceStore.Stored<ApimDeletedService> stored : deletedServices.all()) {
            claims.add(new Claim(CloudSuffix.APIM, stored.value().name(), serviceId(stored.value().service().id())));
        }
        return claims;
    }

    @Override
    public boolean holds(CloudSuffix suffix, String name, ArmResourceId owner) {
        if (suffix != CloudSuffix.APIM) {
            return false;
        }
        if (services.get(owner).filter(stored -> name.equalsIgnoreCase(stored.value().name())).isPresent()) {
            return true;
        }
        String key = owner.canonicalKey();
        return deletedServices.list(new ArmScope(owner.subscriptionId(), null)).stream()
                .anyMatch(stored -> name.equalsIgnoreCase(stored.value().name())
                        && serviceId(stored.value().service().id()).canonicalKey().equals(key));
    }

    private ApimService newService(ArmResourceId id, Map<String, Object> body, String defaultLocation) {
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.put("provisioningState", "Succeeded");
        properties.putIfAbsent("gatewayUrl", gatewayUrl(id.name()));
        properties.putIfAbsent("managementApiUrl", config.effectiveBaseUrl() + id.id());
        properties.putIfAbsent("publisherEmail", "admin@example.com");
        properties.putIfAbsent("publisherName", "floci-az");
        Object sku = body.get("sku");
        Map<String, Object> skuValue = sku instanceof Map<?, ?> ? cast(sku) : Map.of("name", "Developer", "capacity", 1);
        return new ApimService(id.id(), id.name(), bodyString(body, "location", defaultLocation),
                "\"" + UUID.randomUUID() + "\"", properties, skuValue);
    }

    private Response createOrUpdateApi(AzureRequest request, String sub, String rg, String serviceName, String apiId) {
        if (services.get(serviceId(sub, rg, serviceName)).isEmpty()) {
            return notFound("service/" + serviceName);
        }
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.putIfAbsent("displayName", apiId);
        properties.putIfAbsent("path", apiId);
        properties.putIfAbsent("protocols", List.of("https"));
        properties.put("provisioningState", "Succeeded");

        Map<String, Object> resource = resource(sub, rg, "Microsoft.ApiManagement/service/apis", apiId,
                "/subscriptions/" + sub + "/resourceGroups/" + rg
                        + "/providers/Microsoft.ApiManagement/service/" + serviceName + "/apis/" + apiId,
                null, properties);
        resource.put("_service", serviceName);
        apis.put(apiKey(sub, rg, serviceName, apiId), resource);
        importOpenApiOperations(sub, rg, serviceName, apiId, properties);
        return Response.ok(stripInternal(resource)).build();
    }

    private Response createOrUpdateOperation(AzureRequest request, String sub, String rg, String serviceName,
                                             String apiId, String operationId) {
        if (!apis.containsKey(apiKey(sub, rg, serviceName, apiId))) {
            return notFound("apis/" + apiId);
        }
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.putIfAbsent("displayName", operationId);
        properties.putIfAbsent("method", "GET");
        properties.putIfAbsent("urlTemplate", "/");

        Map<String, Object> resource = resource(sub, rg, "Microsoft.ApiManagement/service/apis/operations", operationId,
                "/subscriptions/" + sub + "/resourceGroups/" + rg
                        + "/providers/Microsoft.ApiManagement/service/" + serviceName
                        + "/apis/" + apiId + "/operations/" + operationId,
                null, properties);
        resource.put("_service", serviceName);
        resource.put("_api", apiId);
        operations.put(operationKey(sub, rg, serviceName, apiId, operationId), resource);
        return Response.ok(stripInternal(resource)).build();
    }

    private void importOpenApiOperations(String sub, String rg, String serviceName, String apiId,
                                         Map<String, Object> apiProperties) {
        String format = stringValue(apiProperties.get("format"));
        String value = stringValue(apiProperties.get("value"));
        if (format == null || value == null || !format.toLowerCase().contains("openapi")) {
            return;
        }
        try {
            JsonNode document = MAPPER.readTree(value);
            JsonNode info = document.path("info");
            if (!apiProperties.containsKey("displayName") && info.path("title").isTextual()) {
                apiProperties.put("displayName", info.path("title").asText());
            }
            JsonNode paths = document.path("paths");
            if (!paths.isObject()) {
                return;
            }
            operations.keySet().removeIf(k -> k.startsWith(apiKey(sub, rg, serviceName, apiId) + "/operations/"));
            paths.fields().forEachRemaining(pathEntry -> importOpenApiPath(sub, rg, serviceName, apiId,
                    pathEntry.getKey(), pathEntry.getValue()));
        } catch (Exception e) {
            LOG.warnf("Ignoring unsupported OpenAPI import for API %s: %s", apiId, e.getMessage());
        }
    }

    private void importOpenApiPath(String sub, String rg, String serviceName, String apiId,
                                   String path, JsonNode pathItem) {
        if (!pathItem.isObject()) {
            return;
        }
        pathItem.fields().forEachRemaining(methodEntry -> {
            String method = methodEntry.getKey().toUpperCase();
            if (!isOpenApiHttpMethod(method)) {
                return;
            }
            JsonNode operation = methodEntry.getValue();
            String operationId = operation.path("operationId").isTextual()
                    ? operation.path("operationId").asText()
                    : generatedOperationId(method, path);
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("displayName", firstNonBlank(textValue(operation.path("summary")), operationId));
            properties.put("method", method);
            properties.put("urlTemplate", path == null || path.isBlank() ? "/" : path);

            Map<String, Object> resource = resource(sub, rg,
                    "Microsoft.ApiManagement/service/apis/operations", operationId,
                    "/subscriptions/" + sub + "/resourceGroups/" + rg
                            + "/providers/Microsoft.ApiManagement/service/" + serviceName
                            + "/apis/" + apiId + "/operations/" + operationId,
                    null, properties);
            resource.put("_service", serviceName);
            resource.put("_api", apiId);
            operations.put(operationKey(sub, rg, serviceName, apiId, operationId), resource);
        });
    }

    private List<Map<String, Object>> listApis(String sub, String rg, String serviceName) {
        return apis.values().stream()
                .filter(a -> same(sub, a.get("_sub")) && same(rg, a.get("_rg")) && same(serviceName, a.get("_service")))
                .map(ApiManagementService::stripInternal)
                .toList();
    }

    private List<Map<String, Object>> listOperations(String sub, String rg, String serviceName, String apiId) {
        return operations.values().stream()
                .filter(o -> same(sub, o.get("_sub")) && same(rg, o.get("_rg"))
                        && same(serviceName, o.get("_service")) && same(apiId, o.get("_api")))
                .map(ApiManagementService::stripInternal)
                .toList();
    }

    private List<Map<String, Object>> listProducts(String sub, String rg, String serviceName) {
        return products.values().stream()
                .filter(p -> same(sub, p.get("_sub")) && same(rg, p.get("_rg")) && same(serviceName, p.get("_service")))
                .map(ApiManagementService::stripInternal)
                .toList();
    }

    private List<Map<String, Object>> listSubscriptions(String sub, String rg, String serviceName) {
        return subscriptions.values().stream()
                .filter(s -> same(sub, s.get("_sub")) && same(rg, s.get("_rg")) && same(serviceName, s.get("_service")))
                .map(ApiManagementService::stripInternal)
                .toList();
    }

    private List<Map<String, Object>> listNamedValues(String sub, String rg, String serviceName) {
        return namedValues.values().stream()
                .filter(n -> same(sub, n.get("_sub")) && same(rg, n.get("_rg")) && same(serviceName, n.get("_service")))
                .map(ApiManagementService::stripInternal)
                .toList();
    }

    private List<Map<String, Object>> listBackends(String sub, String rg, String serviceName) {
        return backends.values().stream()
                .filter(b -> same(sub, b.get("_sub")) && same(rg, b.get("_rg")) && same(serviceName, b.get("_service")))
                .map(ApiManagementService::stripInternal)
                .toList();
    }

    private Response getResource(String key, Map<String, Map<String, Object>> store, String path) {
        Map<String, Object> resource = store.get(key);
        return resource == null ? notFound(path) : Response.ok(stripInternal(resource)).build();
    }

    /**
     * {@code ApiManagementService_Delete}: the service moves to the deleted services, with its children left in place
     * for a restore. A resource-group delete arrives here too, and Azure soft-deletes the service then as well.
     */
    private synchronized void deleteService(AzureRequest request, ArmResourceId requested) {
        services.delete(requested).ifPresent(stored -> {
            ApimService service = stored.value();
            Instant deletedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            String location = Objects.requireNonNullElse(service.location(),
                    defaults.defaultLocation(request.scope()));
            ArmResourceId id = deletedServiceId(request, stored.id().subscriptionId(), location, service.name());
            deletedServices.put(new ApimDeletedService(id.id(), service.name(), locationDisplayName(request, location),
                    deletedAt, deletedAt.plus(SOFT_DELETE_RETENTION), service));
            LOG.infov("ARM: API Management service {0} is soft-deleted", service.name());
        });
    }

    /** Drops every child of {@code service}: its APIs, operations, policies, products and the rest. */
    private void dropChildren(ArmResourceId service) {
        String sub = service.subscriptionId();
        String rg = service.resourceGroup();
        String serviceName = service.name();
        // Each map holds one kind, so the service's own key prefix selects all of its children; spelling a kind's
        // segment here would have to match the lowercased keys exactly.
        String children = serviceKey(sub, rg, serviceName) + "/";
        for (Map<String, ?> kind : List.of(apis, operations, policies, products, subscriptions, namedValues, backends,
                productApis)) {
            kind.keySet().removeIf(k -> k.startsWith(children));
        }
    }

    private void deleteApi(String sub, String rg, String serviceName, String apiId) {
        apis.remove(apiKey(sub, rg, serviceName, apiId));
        operations.keySet().removeIf(k -> k.startsWith(apiKey(sub, rg, serviceName, apiId) + "/operations/"));
        policies.keySet().removeIf(k -> k.startsWith(apiKey(sub, rg, serviceName, apiId) + "/"));
        productApis.entrySet().removeIf(e -> e.getValue().equals(apiKey(sub, rg, serviceName, apiId)));
    }

    private Response handlePolicy(AzureRequest request, String method, String parentKey, String parentId,
                                  String type, String[] parts) {
        if (parts.length < 1 || !"policies".equals(parts[parts.length - 1]) && parts.length < 2) {
            return notFound(parentId + "/policies");
        }
        if ("policies".equals(parts[parts.length - 1])) {
            List<Map<String, Object>> items = policies.values().stream()
                    .filter(p -> parentKey.equals(p.get("_parent")))
                    .map(ApiManagementService::stripInternal)
                    .toList();
            return Response.ok(Map.of("value", items)).build();
        }

        String policyId = parts[parts.length - 1];
        String key = policyKey(parentKey, policyId);
        return switch (method) {
            case "PUT" -> createOrUpdatePolicy(request, parentKey, parentId, type, policyId, key);
            case "GET" -> getResource(key, policies, "policies/" + policyId);
            case "DELETE" -> {
                policies.remove(key);
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    private Response createOrUpdatePolicy(AzureRequest request, String parentKey, String parentId,
                                          String type, String policyId, String key) {
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.putIfAbsent("format", "rawxml");
        properties.putIfAbsent("value", "");

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("_parent", parentKey);
        resource.put("id", parentId + "/policies/" + policyId);
        resource.put("name", policyId);
        resource.put("type", type);
        resource.put("properties", properties);
        policies.put(key, resource);
        return Response.ok(stripInternal(resource)).build();
    }

    private Response handleProducts(AzureRequest request, String method, String sub, String rg,
                                    String serviceName, String[] parts) {
        if (parts.length == 3) {
            return Response.ok(Map.of("value", listProducts(sub, rg, serviceName))).build();
        }
        String productId = parts[3];
        if (parts.length == 4) {
            return switch (method) {
                case "PUT" -> createOrUpdateProduct(request, sub, rg, serviceName, productId);
                case "GET" -> getResource(productKey(sub, rg, serviceName, productId), products, "products/" + productId);
                case "DELETE" -> {
                    products.remove(productKey(sub, rg, serviceName, productId));
                    productApis.keySet().removeIf(k -> k.startsWith(productKey(sub, rg, serviceName, productId) + "/apis/"));
                    yield Response.ok().build();
                }
                default -> Response.status(405).build();
            };
        }
        if (parts.length >= 5 && "apis".equals(parts[4])) {
            if (parts.length == 5) {
                List<Map<String, Object>> items = productApis.entrySet().stream()
                        .filter(e -> e.getKey().startsWith(productKey(sub, rg, serviceName, productId) + "/apis/"))
                        .map(Map.Entry::getValue)
                        .map(apis::get)
                        .filter(Objects::nonNull)
                        .map(ApiManagementService::stripInternal)
                        .toList();
                return Response.ok(Map.of("value", items)).build();
            }
            String apiId = parts[5];
            return switch (method) {
                case "PUT" -> linkProductApi(sub, rg, serviceName, productId, apiId);
                case "GET" -> getProductApi(sub, rg, serviceName, productId, apiId);
                case "DELETE" -> {
                    productApis.remove(productApiKey(sub, rg, serviceName, productId, apiId));
                    yield Response.ok().build();
                }
                default -> Response.status(405).build();
            };
        }
        return notFound("products/" + productId);
    }

    private Response handleSubscriptions(AzureRequest request, String method, String sub, String rg,
                                         String serviceName, String[] parts) {
        if (parts.length == 3) {
            return Response.ok(Map.of("value", listSubscriptions(sub, rg, serviceName))).build();
        }
        String subscriptionId = parts[3];
        return switch (method) {
            case "PUT" -> createOrUpdateSubscription(request, sub, rg, serviceName, subscriptionId);
            case "GET" -> getResource(subscriptionKey(sub, rg, serviceName, subscriptionId),
                    subscriptions, "subscriptions/" + subscriptionId);
            case "DELETE" -> {
                subscriptions.remove(subscriptionKey(sub, rg, serviceName, subscriptionId));
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    private Response createOrUpdateProduct(AzureRequest request, String sub, String rg,
                                           String serviceName, String productId) {
        if (services.get(serviceId(sub, rg, serviceName)).isEmpty()) {
            return notFound("service/" + serviceName);
        }
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.putIfAbsent("displayName", productId);
        properties.putIfAbsent("subscriptionRequired", true);
        properties.putIfAbsent("approvalRequired", false);
        properties.putIfAbsent("state", "published");

        Map<String, Object> resource = resource(sub, rg, "Microsoft.ApiManagement/service/products", productId,
                "/subscriptions/" + sub + "/resourceGroups/" + rg
                        + "/providers/Microsoft.ApiManagement/service/" + serviceName + "/products/" + productId,
                null, properties);
        resource.put("_service", serviceName);
        products.put(productKey(sub, rg, serviceName, productId), resource);
        return Response.ok(stripInternal(resource)).build();
    }

    private Response linkProductApi(String sub, String rg, String serviceName, String productId, String apiId) {
        String productKey = productKey(sub, rg, serviceName, productId);
        String apiKey = apiKey(sub, rg, serviceName, apiId);
        if (!products.containsKey(productKey)) {
            return notFound("products/" + productId);
        }
        Map<String, Object> api = apis.get(apiKey);
        if (api == null) {
            return notFound("apis/" + apiId);
        }
        productApis.put(productApiKey(sub, rg, serviceName, productId, apiId), apiKey);
        return Response.ok(stripInternal(api)).build();
    }

    private Response getProductApi(String sub, String rg, String serviceName, String productId, String apiId) {
        String apiKey = productApis.get(productApiKey(sub, rg, serviceName, productId, apiId));
        Map<String, Object> api = apiKey == null ? null : apis.get(apiKey);
        return api == null ? notFound("products/" + productId + "/apis/" + apiId)
                : Response.ok(stripInternal(api)).build();
    }

    private Response createOrUpdateSubscription(AzureRequest request, String sub, String rg,
                                                String serviceName, String subscriptionId) {
        if (services.get(serviceId(sub, rg, serviceName)).isEmpty()) {
            return notFound("service/" + serviceName);
        }
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.putIfAbsent("displayName", subscriptionId);
        properties.putIfAbsent("state", "active");
        properties.putIfAbsent("scope", "/subscriptions/" + sub + "/resourceGroups/" + rg
                + "/providers/Microsoft.ApiManagement/service/" + serviceName);
        properties.putIfAbsent("primaryKey", generatedSubscriptionKey(subscriptionId, "primary"));
        properties.putIfAbsent("secondaryKey", generatedSubscriptionKey(subscriptionId, "secondary"));

        Map<String, Object> resource = resource(sub, rg, "Microsoft.ApiManagement/service/subscriptions", subscriptionId,
                "/subscriptions/" + sub + "/resourceGroups/" + rg
                        + "/providers/Microsoft.ApiManagement/service/" + serviceName + "/subscriptions/" + subscriptionId,
                null, properties);
        resource.put("_service", serviceName);
        subscriptions.put(subscriptionKey(sub, rg, serviceName, subscriptionId), resource);
        return Response.ok(stripInternal(resource)).build();
    }

    private Response handleNamedValues(AzureRequest request, String method, String sub, String rg,
                                       String serviceName, String[] parts) {
        if (parts.length == 3) {
            return Response.ok(Map.of("value", listNamedValues(sub, rg, serviceName))).build();
        }
        String namedValueId = parts[3];
        return switch (method) {
            case "PUT" -> createOrUpdateNamedValue(request, sub, rg, serviceName, namedValueId);
            case "GET" -> getResource(namedValueKey(sub, rg, serviceName, namedValueId),
                    namedValues, "namedValues/" + namedValueId);
            case "DELETE" -> {
                namedValues.remove(namedValueKey(sub, rg, serviceName, namedValueId));
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    private Response createOrUpdateNamedValue(AzureRequest request, String sub, String rg,
                                              String serviceName, String namedValueId) {
        if (services.get(serviceId(sub, rg, serviceName)).isEmpty()) {
            return notFound("service/" + serviceName);
        }
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.putIfAbsent("displayName", namedValueId);
        properties.putIfAbsent("value", "");
        properties.putIfAbsent("secret", false);

        Map<String, Object> resource = resource(sub, rg, "Microsoft.ApiManagement/service/namedValues", namedValueId,
                "/subscriptions/" + sub + "/resourceGroups/" + rg
                        + "/providers/Microsoft.ApiManagement/service/" + serviceName + "/namedValues/" + namedValueId,
                null, properties);
        resource.put("_service", serviceName);
        namedValues.put(namedValueKey(sub, rg, serviceName, namedValueId), resource);
        return Response.ok(stripInternal(resource)).build();
    }

    private Response handleBackends(AzureRequest request, String method, String sub, String rg,
                                    String serviceName, String[] parts) {
        if (parts.length == 3) {
            return Response.ok(Map.of("value", listBackends(sub, rg, serviceName))).build();
        }
        String backendId = parts[3];
        return switch (method) {
            case "PUT" -> createOrUpdateBackend(request, sub, rg, serviceName, backendId);
            case "GET" -> getResource(backendKey(sub, rg, serviceName, backendId), backends, "backends/" + backendId);
            case "DELETE" -> {
                backends.remove(backendKey(sub, rg, serviceName, backendId));
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    private Response createOrUpdateBackend(AzureRequest request, String sub, String rg,
                                           String serviceName, String backendId) {
        if (services.get(serviceId(sub, rg, serviceName)).isEmpty()) {
            return notFound("service/" + serviceName);
        }
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.putIfAbsent("title", backendId);
        properties.putIfAbsent("protocol", "http");
        properties.putIfAbsent("url", "");

        Map<String, Object> resource = resource(sub, rg, "Microsoft.ApiManagement/service/backends", backendId,
                "/subscriptions/" + sub + "/resourceGroups/" + rg
                        + "/providers/Microsoft.ApiManagement/service/" + serviceName + "/backends/" + backendId,
                null, properties);
        resource.put("_service", serviceName);
        backends.put(backendKey(sub, rg, serviceName, backendId), resource);
        return Response.ok(stripInternal(resource)).build();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second != null && !second.isBlank() ? second : null);
    }

    private String gatewayUrl(String serviceName) {
        return config.effectiveBaseUrl().replaceAll("/+$", "") + "/devstoreaccount1-apim/" + serviceName;
    }

    private static Map<String, Object> resource(String sub, String rg, String type, String name, String id,
                                                String location, Map<String, Object> properties) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("_sub", sub);
        resource.put("_rg", rg);
        resource.put("id", id);
        resource.put("name", name);
        resource.put("type", type);
        if (location != null) {
            resource.put("location", location);
        }
        resource.put("etag", "\"" + UUID.randomUUID() + "\"");
        resource.put("properties", properties);
        return resource;
    }

    private static String trimSlashes(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private static String[] split(String path) {
        String clean = path == null ? "" : path;
        int q = clean.indexOf('?');
        if (q >= 0) {
            clean = clean.substring(0, q);
        }
        clean = clean.replaceAll("^/+", "").replaceAll("/+$", "");
        if (clean.isBlank()) {
            return new String[0];
        }
        return clean.split("/", -1);
    }

    private static Map<String, Object> cast(Object value) {
        return ArmJson.cast(value);
    }

    private static String bodyString(Map<String, Object> map, String key, String defaultValue) {
        return ArmJson.string(map, key, defaultValue);
    }

    private static String stringValue(Object value) {
        return value instanceof String s ? s : null;
    }

    private static String textValue(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static boolean isOpenApiHttpMethod(String method) {
        return switch (method) {
            case "GET", "PUT", "POST", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE" -> true;
            default -> false;
        };
    }

    private static String generatedOperationId(String method, String path) {
        String cleanPath = trimSlashes(path).replaceAll("\\{([^/]+)}", "$1")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return method.toLowerCase() + (cleanPath.isBlank() ? "" : "-" + cleanPath);
    }

    private Map<String, Object> parseBody(AzureRequest request) {
        return ArmJson.parseBodyStrict(request);
    }

    private static Map<String, Object> stripInternal(Map<String, Object> resource) {
        Map<String, Object> copy = new LinkedHashMap<>(resource);
        copy.remove("_sub");
        copy.remove("_rg");
        copy.remove("_service");
        copy.remove("_api");
        copy.remove("_parent");
        if ("Microsoft.ApiManagement/service/namedValues".equals(copy.get("type"))) {
            Map<String, Object> properties = new LinkedHashMap<>(cast(copy.get("properties")));
            if (Boolean.parseBoolean(String.valueOf(properties.getOrDefault("secret", false)))) {
                properties.remove("value");
                copy.put("properties", properties);
            }
        }
        return copy;
    }

    private Response notFound(String message) {
        return ArmErrors.notFound(message);
    }

    private static ArmResourceId serviceId(String sub, String rg, String serviceName) {
        return serviceId("/subscriptions/" + sub + "/resourceGroups/" + rg
                + "/providers/Microsoft.ApiManagement/service/" + serviceName);
    }

    private static ArmResourceId serviceId(String id) {
        return ArmResourceId.parse(id).orElseThrow(() -> new IllegalArgumentException("not a service id: " + id));
    }

    /** ARM paths are case-insensitive, so the casing a child was written with never decides whether it matches. */
    static boolean same(String requested, Object stored) {
        return stored instanceof String text && requested.equalsIgnoreCase(text);
    }

    private static String serviceKey(String sub, String rg, String serviceName) {
        return (sub + "/" + rg + "/apim/" + serviceName).toLowerCase(Locale.ROOT);
    }

    private static String apiKey(String sub, String rg, String serviceName, String apiId) {
        return (serviceKey(sub, rg, serviceName) + "/apis/" + apiId).toLowerCase(Locale.ROOT);
    }

    private static String operationKey(String sub, String rg, String serviceName, String apiId, String operationId) {
        return (apiKey(sub, rg, serviceName, apiId) + "/operations/" + operationId).toLowerCase(Locale.ROOT);
    }

    private static String policyKey(String parentKey, String policyId) {
        return (parentKey + "/policies/" + policyId).toLowerCase(Locale.ROOT);
    }

    private static String productKey(String sub, String rg, String serviceName, String productId) {
        return (serviceKey(sub, rg, serviceName) + "/products/" + productId).toLowerCase(Locale.ROOT);
    }

    private static String subscriptionKey(String sub, String rg, String serviceName, String subscriptionId) {
        return (serviceKey(sub, rg, serviceName) + "/subscriptions/" + subscriptionId).toLowerCase(Locale.ROOT);
    }

    private static String productApiKey(String sub, String rg, String serviceName, String productId, String apiId) {
        return (productKey(sub, rg, serviceName, productId) + "/apis/" + apiId).toLowerCase(Locale.ROOT);
    }

    private static String namedValueKey(String sub, String rg, String serviceName, String namedValueId) {
        return (serviceKey(sub, rg, serviceName) + "/namedValues/" + namedValueId).toLowerCase(Locale.ROOT);
    }

    private static String backendKey(String sub, String rg, String serviceName, String backendId) {
        return (serviceKey(sub, rg, serviceName) + "/backends/" + backendId).toLowerCase(Locale.ROOT);
    }

    private static String generatedSubscriptionKey(String subscriptionId, String suffix) {
        return UUID.nameUUIDFromBytes((subscriptionId + ":" + suffix).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    public void clear() {
        services.clear();
        deletedServices.clear();
        apis.clear();
        operations.clear();
        policies.clear();
        products.clear();
        subscriptions.clear();
        namedValues.clear();
        backends.clear();
        productApis.clear();
    }
}
