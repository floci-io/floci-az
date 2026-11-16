package io.floci.az.services.search;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.arm.GlobalNameSource.Claim;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudDefaults;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudProfile.Location;
import io.floci.az.core.cloud.CloudSuffix;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.entra.TokenIssuer;
import io.floci.az.services.managedidentity.ManagedIdentityModels.UserAssignedIdentity;
import io.floci.az.services.managedidentity.ManagedIdentityStore;
import io.floci.az.services.search.SearchModels.QueryKey;
import io.floci.az.services.search.SearchModels.SearchServiceResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code Microsoft.Search} management plane: search services and their admin and query keys.
 *
 * <pre>
 *   PUT/GET/PATCH/DELETE .../providers/Microsoft.Search/searchServices/{name}
 *   GET    .../providers/Microsoft.Search/searchServices                      (resource group or subscription)
 *   POST   .../searchServices/{name}/listAdminKeys
 *   POST   .../searchServices/{name}/regenerateAdminKey/{primary|secondary}
 *   POST   .../searchServices/{name}/listQueryKeys                             (GET before 2020-08-01)
 *   POST   .../searchServices/{name}/createQueryKey/{keyName}
 *   DELETE .../searchServices/{name}/deleteQueryKey/{key}
 *   POST   /subscriptions/{sub}/providers/Microsoft.Search/checkNameAvailability
 * </pre>
 *
 * <p>Response shapes follow the resource-manager spec (2025-05-01) and the responses Azure gave the az CLI's
 * {@code search} test recordings. A service is {@code succeeded} and {@code running} in the answer to the PUT that
 * creates it: Azure answers {@code 201} with {@code provisioning} and no polling header, and clients poll the
 * resource until it reads {@code succeeded}, so the first read already ends the wait.
 */
@ApplicationScoped
public class SearchArmService {

    private static final Logger LOG = Logger.getLogger(SearchArmService.class);

    private static final String STORE = "search";
    private static final String SERVICES_SEGMENT = "searchServices";

    /** {@code SkuName} (2025-05-01). */
    private static final List<String> SKU_NAMES = List.of("free", "basic", "standard", "standard2", "standard3",
            "storage_optimized_l1", "storage_optimized_l2");
    /** {@code PublicNetworkAccess} (2025-05-01), in the casing Azure answers whatever casing it was sent. */
    private static final List<String> PUBLIC_NETWORK_ACCESS = List.of("Enabled", "Disabled", "SecuredByPerimeter");
    /**
     * The enum properties a request may set, each with the union of its values over every api-version the
     * provider lists (2026-09-01-preview adds {@code AzurePortal} to {@code bypass}), since every api-version is
     * served the same way.
     */
    private static final Map<String, List<String>> ENUMS = Map.of(
            "hostingMode", List.of("Default", "HighDensity"),
            "computeType", List.of("Default", "Confidential"),
            "publicNetworkAccess", PUBLIC_NETWORK_ACCESS,
            "semanticSearch", List.of("disabled", "free", "standard"),
            "knowledgeRetrieval", List.of("free", "standard"),
            "networkRuleSet.bypass", List.of("None", "AzureServices", "AzurePortal"),
            "encryptionWithCmk.enforcement", List.of("Disabled", "Enabled", "Unspecified"));
    private static final List<String> DATA_EXFILTRATION_PROTECTIONS = List.of("BlockAll");
    /** {@code IdentityType} (2025-05-01). */
    private static final String IDENTITY_NONE = "None";
    private static final String IDENTITY_SYSTEM = "SystemAssigned";
    private static final String IDENTITY_USER = "UserAssigned";
    private static final List<String> IDENTITY_TYPES = List.of(IDENTITY_NONE, IDENTITY_SYSTEM, IDENTITY_USER,
            IDENTITY_SYSTEM + ", " + IDENTITY_USER);

    /** A request field whose JSON type the spec fixes, as {@link #wrongType} checks it. */
    private record Shape(String name, Object value, Class<?> type, String description) {
    }
    /** The bounds of {@code replicaCount} and {@code partitionCount} (2025-05-01). */
    private static final int MIN_UNITS = 1;
    private static final int MAX_UNITS = 12;

    /** The most replicas and partitions a SKU scales to. */
    private record Capacity(int replicas, int partitions) {
    }

    /**
     * Each SKU's ceiling, from Microsoft's service limits page ({@code search-limits-quotas-capacity}): a free
     * service is shared and does not scale, and basic is given the three partitions of a service created after
     * April 2024, which every service created here is.
     */
    private static final Map<String, Capacity> CAPACITY = Map.of(
            "free", new Capacity(1, 1),
            "basic", new Capacity(3, 3),
            "standard", new Capacity(12, 12),
            "standard2", new Capacity(12, 12),
            "standard3", new Capacity(12, 12),
            "storage_optimized_l1", new Capacity(12, 12),
            "storage_optimized_l2", new Capacity(12, 12));
    /** {@code partitionCount} "can be 1, 2, 3, 4, 6, or 12" (2025-05-01). */
    private static final List<Integer> PARTITION_COUNTS = List.of(1, 2, 3, 4, 6, 12);
    /** A dedicated service has at most 36 search units, replicas times partitions. */
    private static final int MAX_SEARCH_UNITS = 36;
    private static final String HIGH_DENSITY = "HighDensity";
    private static final String HIGH_DENSITY_SKU = "standard3";
    /** A {@code standard3} service in {@code HighDensity} hosting mode has at most three partitions (2025-05-01). */
    private static final int MAX_HIGH_DENSITY_PARTITIONS = 3;

    private static final String KEY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int KEY_LENGTH = 52;

    private final ArmResourceStore<SearchServiceResource> services;
    /** Per instance, not static: a static one would be built into the native image with a fixed seed. */
    private final SecureRandom random = new SecureRandom();
    private final GlobalNameRegistry names;
    private final CloudDefaults defaults;
    private final ManagedIdentityStore identities;

    @Inject
    public SearchArmService(StorageFactory storageFactory, GlobalNameRegistry names, CloudDefaults defaults,
                            ManagedIdentityStore identities) {
        this.services = ArmResourceStore.create(storageFactory, STORE, SearchModels.SEARCH_SERVICES,
                SearchServiceResource.class, SearchServiceResource::id, key -> false);
        this.names = names;
        this.defaults = defaults;
        this.identities = identities;
    }

    /** Serves one request under {@code /providers/Microsoft.Search/}. */
    public Response handle(AzureRequest req) {
        try {
            return route(req);
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The request content was invalid and could not be deserialized.");
        }
    }

    private Response route(AzureRequest req) {
        String path = withoutQuery(req.resourcePath());
        String method = req.method().toUpperCase(Locale.ROOT);
        List<String> tail = segments(ArmPaths.afterProvider(path, SearchArmSurface.NAMESPACE, ""));

        if (tail.size() == 1 && tail.getFirst().equalsIgnoreCase("checkNameAvailability")) {
            return "POST".equals(method) ? checkNameAvailability(req) : methodNotAllowed();
        }
        if (tail.isEmpty() || !tail.getFirst().equalsIgnoreCase(SERVICES_SEGMENT)) {
            return ArmErrors.notFound("Unknown Microsoft.Search path: " + String.join("/", tail));
        }
        if (tail.size() == 1) {
            return "GET".equals(method) ? list(req) : methodNotAllowed();
        }

        String name = tail.get(1);
        Optional<ArmResourceId> id = serviceId(req, name);
        if (id.isEmpty()) {
            // Every search service lives in a resource group, so a path that names none addresses none.
            return ArmErrors.notFound("The Resource '" + SearchModels.SEARCH_SERVICES + "/" + name + "' was not found.");
        }
        if (tail.size() == 2) {
            return switch (method) {
                case "PUT" -> createOrUpdate(req, id.get());
                case "GET" -> services.get(id.get()).map(stored -> ok(stored.value().toArm()))
                        .orElseGet(() -> serviceNotFound(id.get()));
                case "PATCH" -> update(req, id.get());
                case "DELETE" -> delete(id.get());
                default -> methodNotAllowed();
            };
        }

        String action = tail.get(2).toLowerCase(Locale.ROOT);
        if (tail.size() == 3) {
            return switch (action) {
                case "listadminkeys" -> "POST".equals(method) ? listAdminKeys(id.get()) : methodNotAllowed();
                // QueryKeys_ListBySearchService was a GET until 2020-08-01 made it a POST.
                case "listquerykeys" -> "POST".equals(method) || "GET".equals(method)
                        ? listQueryKeys(id.get()) : methodNotAllowed();
                default -> unknownChild(tail);
            };
        }
        if (tail.size() == 4) {
            return switch (action) {
                case "regenerateadminkey" -> "POST".equals(method)
                        ? regenerateAdminKey(id.get(), tail.get(3)) : methodNotAllowed();
                case "createquerykey" -> "POST".equals(method)
                        ? createQueryKey(id.get(), tail.get(3)) : methodNotAllowed();
                case "deletequerykey" -> "DELETE".equals(method)
                        ? deleteQueryKey(id.get(), tail.get(3)) : methodNotAllowed();
                default -> unknownChild(tail);
            };
        }
        return unknownChild(tail);
    }

    // ── Services ─────────────────────────────────────────────────────────────

    /**
     * {@code Services_CreateOrUpdate}: {@code 201} for a new service and {@code 200} for one that exists, whose
     * properties are all replaced by the ones sent while its keys, identity principal and creation time stay.
     */
    private Response createOrUpdate(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Map<String, Object> properties = ArmJson.cast(body.get("properties"));
        Optional<Response> invalid = validate(body, properties);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        CloudProfile cloud = cloud(req);
        String location = location(req, cloud, body);
        ArmResourceStore.Written<SearchServiceResource> written;
        try {
            // Every check that reads the stored service runs inside the store's lock, so a concurrent DELETE
            // cannot change the answer between the check and the write.
            written = services.createOrUpdate(id, existing -> {
                String sku = skuName(body).or(() -> existing.map(SearchServiceResource::sku))
                        .orElseThrow(() -> new Refused(
                                invalid("The 'sku' of a search service is required when creating it.")));
                existing.ifPresent(service -> {
                    refuseLocationChange(service, location);
                    refuseHostingModeChange(service, properties);
                });
                // A PUT replaces the properties, except the hosting mode, which is fixed at creation.
                Map<String, Object> base = existing.map(SearchArmService::fixedProperties).orElse(Map.of());
                capacityBreach(sku, properties, base).ifPresent(refusal -> {
                    throw new Refused(refusal);
                });
                // The id keeps the casing of the PUT that created the service.
                ArmResourceId stored = existing.map(service -> ArmResourceId.parse(service.id()).orElseThrow())
                        .orElse(id);
                return new SearchServiceResource(stored.id(), stored.name(), location,
                        displayLocation(cloud, location), tags(body.get("tags")), sku,
                        identity(body.get("identity"), existing.map(SearchServiceResource::identity).orElse(null), req),
                        properties(properties, base, endpoint(cloud, stored.name())),
                        existing.map(SearchServiceResource::createdAt).orElseGet(SearchArmService::now),
                        existing.map(SearchServiceResource::primaryKey).orElseGet(this::newKey),
                        existing.map(SearchServiceResource::secondaryKey).orElseGet(this::newKey),
                        existing.map(SearchServiceResource::queryKeys)
                                .orElseGet(() -> List.of(new QueryKey(null, newKey()))));
            });
        } catch (Refused refused) {
            return refused.response();
        }
        LOG.infov("ARM: {0} search service {1}", written.created() ? "created" : "updated",
                written.stored().value().id());
        return Response.status(written.created() ? 201 : 200).entity(written.stored().value().toArm())
                .type("application/json").build();
    }

    /**
     * {@code Services_Update}: the properties, SKU, tags and identity the body carries replace the stored ones, and
     * everything it leaves out stays.
     */
    private Response update(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Map<String, Object> properties = ArmJson.cast(body.get("properties"));
        Optional<Response> invalid = validate(body, properties);
        if (invalid.isPresent()) {
            return invalid.get();
        }
        try {
            return services.update(id, service -> {
                        refuseHostingModeChange(service, properties);
                        String sku = skuName(body).orElse(service.sku());
                        capacityBreach(sku, properties, service.properties()).ifPresent(refusal -> {
                            throw new Refused(refusal);
                        });
                        return new SearchServiceResource(service.id(), service.name(),
                                service.location(), service.displayLocation(),
                                body.containsKey("tags") ? tags(body.get("tags")) : service.tags(), sku,
                                body.containsKey("identity") ? identity(body.get("identity"), service.identity(), req)
                                        : service.identity(),
                                properties(properties, service.properties(),
                                        (String) service.properties().get("endpoint")),
                                service.createdAt(), service.primaryKey(), service.secondaryKey(),
                                service.queryKeys());
                    })
                    .map(stored -> ok(stored.value().toArm()))
                    .orElseGet(() -> serviceNotFound(id));
        } catch (Refused refused) {
            return refused.response();
        }
    }

    /** {@code Services_Delete}: {@code 200} for a service that existed, {@code 204} for one that did not. */
    private Response delete(ArmResourceId id) {
        Optional<ArmResourceStore.Stored<SearchServiceResource>> removed = services.delete(id);
        if (removed.isEmpty()) {
            return Response.noContent().build();
        }
        LOG.infov("ARM: deleted search service {0}", removed.get().value().id());
        return Response.ok().build();
    }

    /** The services of the request's subscription, or of its resource group when it names one. */
    private Response list(AzureRequest req) {
        List<Map<String, Object>> value = scope(req)
                .map(scope -> services.list(scope).stream().map(stored -> stored.value().toArm()).toList())
                .orElse(List.of());
        return ok(Map.of("value", value));
    }

    /**
     * {@code Services_CheckNameAvailability}: a name outside the {@code searchServiceName} rule is {@code Invalid}
     * and one the name registry holds, in any subscription, is {@code AlreadyExists}; the reasons are the spec's
     * {@code UnavailableNameReason}. The spec's example answers an empty message, and gives none for the other two
     * cases, so the invalid name carries the rule's own text and an available one the empty message.
     */
    private Response checkNameAvailability(AzureRequest req) {
        String name = ArmJson.string(ArmJson.parseBodyStrict(req), "name", "");
        Map<String, Object> answer = new LinkedHashMap<>();
        if (!SearchArmSurface.SEARCH_SERVICE_NAME.accepts(name)) {
            answer.put("nameAvailable", false);
            answer.put("reason", "Invalid");
            answer.put("message", SearchArmSurface.SEARCH_SERVICE_NAME.message(name));
        } else if (names.owner(cloud(req), CloudSuffix.SEARCH, name).isPresent()) {
            answer.put("nameAvailable", false);
            answer.put("reason", "AlreadyExists");
            answer.put("message", "");
        } else {
            answer.put("nameAvailable", true);
            answer.put("message", "");
        }
        return ok(answer);
    }

    // ── Keys ─────────────────────────────────────────────────────────────────

    private Response listAdminKeys(ArmResourceId id) {
        return services.get(id).map(stored -> adminKeys(stored.value())).orElseGet(() -> serviceNotFound(id));
    }

    /** {@code AdminKeys_Regenerate}: replaces the named key and answers both, as {@code AdminKeys_Get} does. */
    private Response regenerateAdminKey(ArmResourceId id, String keyKind) {
        boolean primary = "primary".equals(keyKind);
        if (!primary && !"secondary".equals(keyKind)) {
            // The spec closes keyKind to these two values; the error is ARM's generic one, the spec names none.
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The key kind '" + keyKind + "' is invalid: it must be 'primary' or 'secondary'.");
        }
        return services.update(id, service -> primary
                        ? service.withAdminKeys(newKey(), service.secondaryKey())
                        : service.withAdminKeys(service.primaryKey(), newKey()))
                .map(stored -> adminKeys(stored.value()))
                .orElseGet(() -> serviceNotFound(id));
    }

    private static Response adminKeys(SearchServiceResource service) {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("primaryKey", service.primaryKey());
        keys.put("secondaryKey", service.secondaryKey());
        return ok(keys);
    }

    private Response listQueryKeys(ArmResourceId id) {
        return services.get(id).map(stored -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("value", stored.value().queryKeys().stream().map(QueryKey::toArm).toList());
            body.put("nextLink", null);
            return ok(body);
        }).orElseGet(() -> serviceNotFound(id));
    }

    /** {@code QueryKeys_Create}: a new key under {@code keyName}; names are labels, so two keys may share one. */
    private Response createQueryKey(ArmResourceId id, String keyName) {
        QueryKey created = new QueryKey(keyName, newKey());
        return services.update(id, service -> {
            List<QueryKey> keys = new ArrayList<>(service.queryKeys());
            keys.add(created);
            return service.withQueryKeys(keys);
        }).map(stored -> ok(created.toArm())).orElseGet(() -> serviceNotFound(id));
    }

    /** {@code QueryKeys_Delete}: {@code 204} once the key is gone, {@code 404} for a key the service does not have. */
    private Response deleteQueryKey(ArmResourceId id, String key) {
        try {
            return services.update(id, service -> {
                        if (service.queryKeys().stream().noneMatch(queryKey -> queryKey.key().equals(key))) {
                            throw new Refused(ArmErrors.notFound(
                                    "The query key was not found on search service '" + id.name() + "'."));
                        }
                        return service.withQueryKeys(service.queryKeys().stream()
                                .filter(queryKey -> !queryKey.key().equals(key)).toList());
                    })
                    .map(stored -> Response.noContent().build())
                    .orElseGet(() -> serviceNotFound(id));
        } catch (Refused refused) {
            return refused.response();
        }
    }

    // ── Resource index, global names, reset ──────────────────────────────────

    /** The index entries of the services in {@code scope}, under their canonical location. */
    public List<Map<String, Object>> indexEntries(ArmScope scope) {
        return services.list(scope).stream().map(stored -> stored.value())
                .map(service -> ArmResources.indexEntry(service.id(), service.name(), SearchModels.SEARCH_SERVICES,
                        service.location(), stringTags(service.tags())))
                .toList();
    }

    /** Every stored service owns {@code {name}.search.windows.net}. */
    public List<Claim> globalNames() {
        return services.all().stream()
                .map(stored -> new Claim(CloudSuffix.SEARCH, stored.id().name(), stored.id()))
                .toList();
    }

    /** Whether {@code owner} is a stored service named {@code name}. */
    public boolean holds(String name, ArmResourceId owner) {
        return services.get(owner).filter(stored -> name.equalsIgnoreCase(stored.id().name())).isPresent();
    }

    /** Forgets every search service: {@code POST /_admin/reset}. */
    public void clear() {
        services.clear();
    }

    // ── Request validation ───────────────────────────────────────────────────

    /**
     * A write refused by a check that reads the stored service. It is thrown from inside a store change, which
     * aborts the write, and carries the answer to the request.
     */
    private static final class Refused extends RuntimeException {
        private final transient Response response;

        Refused(Response response) {
            super(null, null, false, false);
            this.response = response;
        }

        Response response() {
            return response;
        }
    }

    /**
     * Refuses a PUT that moves an existing service: ARM fixes a resource's location at creation. The answer is
     * the one ARM gives for any resource type, not one the Microsoft.Search spec states.
     */
    private static void refuseLocationChange(SearchServiceResource service, String location) {
        if (!service.location().equalsIgnoreCase(location)) {
            throw new Refused(ArmErrors.error(400, "InvalidResourceLocation", "The resource '" + service.name()
                    + "' already exists in location '" + service.location() + "' in resource group '"
                    + ArmResourceId.parse(service.id()).map(ArmResourceId::resourceGroup).orElse("") + "'. A resource"
                    + " with the same name cannot be created in location '" + location + "'. Please select a new"
                    + " resource name."));
        }
    }

    /**
     * Refuses a write that sends a hosting mode other than the service's: the hosting mode is fixed when the
     * service is created. The spec names no error, so the answer is ARM's generic {@code 400 InvalidRequestContent}.
     */
    private static void refuseHostingModeChange(SearchServiceResource service, Map<String, Object> sent) {
        Object stored = service.properties().get("hostingMode");
        Object requested = sent.get("hostingMode");
        if (requested != null && stored != null && !String.valueOf(stored).equalsIgnoreCase(String.valueOf(requested))) {
            throw new Refused(invalid("The 'hostingMode' of search service '" + service.name() + "' is '" + stored
                    + "' and cannot be changed after the service is created."));
        }
    }

    /** The stored properties a PUT keeps although it replaces the rest: the hosting mode. */
    private static Map<String, Object> fixedProperties(SearchServiceResource service) {
        Map<String, Object> fixed = new LinkedHashMap<>();
        Object hostingMode = service.properties().get("hostingMode");
        if (hostingMode != null) {
            fixed.put("hostingMode", hostingMode);
        }
        return fixed;
    }

    /**
     * Refuses what the spec rules out without reading the stored service: a SKU name outside {@code SkuName}, a
     * replica or partition count outside 1 to 12, an enum property outside its values, and a firewall rule that is
     * not an IPv4 address or CIDR range. The spec states the constraints and not the error a breach is answered
     * with, so the answer is ARM's generic {@code 400 InvalidRequestContent}.
     */
    private static Optional<Response> validate(Map<String, Object> body, Map<String, Object> properties) {
        Optional<Response> wrongType = wrongType(body, properties);
        if (wrongType.isPresent()) {
            return wrongType;
        }
        if (body.get("sku") != null && skuName(body).isEmpty()) {
            return Optional.of(invalid("The SKU name is invalid: it must be one of " + SKU_NAMES + "."));
        }
        // The spec: authOptions "cannot be set if 'disableLocalAuth' is set to true". Only what this request sends is
        // judged, since every service stores a default authOptions that a later PATCH disabling local auth replaces.
        if (Boolean.TRUE.equals(properties.get("disableLocalAuth")) && properties.get("authOptions") != null) {
            return Optional.of(invalid("The 'authOptions' cannot be set when 'disableLocalAuth' is true."));
        }
        for (String count : List.of("replicaCount", "partitionCount")) {
            Object value = properties.get(count);
            if (value != null && unitCount(value).isEmpty()) {
                return Optional.of(invalid("The '" + count + "' is invalid: it must be an integer from "
                        + MIN_UNITS + " to " + MAX_UNITS + "."));
            }
        }
        return outsideEnum(properties).or(() -> invalidNetworkRules(properties));
    }

    /**
     * The first field sent with a JSON type other than the spec's, checked before any default is applied, so a
     * malformed value is refused rather than replaced by its default ({@code disableLocalAuth: "true"} read as
     * {@code false}) or stored in the wrong shape. A {@code null} is no value and passes. An identity type outside
     * {@code IdentityType} is refused here too, since a type nothing recognises would otherwise mean no identity.
     */
    private static Optional<Response> wrongType(Map<String, Object> body, Map<String, Object> properties) {
        Object identity = body.get("identity");
        Object networkRuleSet = properties.get("networkRuleSet");
        List<Shape> shapes = List.of(
                new Shape("properties", body.get("properties"), Map.class, "an object"),
                new Shape("sku", body.get("sku"), Map.class, "an object"),
                new Shape("tags", body.get("tags"), Map.class, "an object"),
                new Shape("identity", identity, Map.class, "an object"),
                new Shape("identity.userAssignedIdentities", ArmJson.cast(identity).get("userAssignedIdentities"),
                        Map.class, "an object"),
                new Shape("disableLocalAuth", properties.get("disableLocalAuth"), Boolean.class, "a boolean"),
                new Shape("authOptions", properties.get("authOptions"), Map.class, "an object"),
                new Shape("networkRuleSet", networkRuleSet, Map.class, "an object"),
                new Shape("networkRuleSet.ipRules", ArmJson.cast(networkRuleSet).get("ipRules"), List.class,
                        "an array"),
                new Shape("encryptionWithCmk", properties.get("encryptionWithCmk"), Map.class, "an object"),
                new Shape("dataExfiltrationProtections", properties.get("dataExfiltrationProtections"), List.class,
                        "an array"));
        for (Shape shape : shapes) {
            if (shape.value() != null && !shape.type().isInstance(shape.value())) {
                return Optional.of(invalid("The '" + shape.name() + "' must be " + shape.description() + "."));
            }
        }
        Object identityType = ArmJson.cast(identity).get("type");
        if (identityType != null && identityType(identityType).isEmpty()) {
            return Optional.of(invalid("The identity type '" + identityType + "' is invalid: it must be one of "
                    + IDENTITY_TYPES + "."));
        }
        return Optional.empty();
    }

    /**
     * The first enum property the request sets to a value no api-version's spec lists. Values are matched ignoring
     * case, as the az CLI sends some in lowercase ({@code publicNetworkAccess: enabled}).
     */
    private static Optional<Response> outsideEnum(Map<String, Object> properties) {
        Map<String, Object> networkRuleSet = ArmJson.cast(properties.get("networkRuleSet"));
        Map<String, Object> encryption = ArmJson.cast(properties.get("encryptionWithCmk"));
        Map<String, Object> checked = new LinkedHashMap<>();
        checked.put("hostingMode", properties.get("hostingMode"));
        checked.put("computeType", properties.get("computeType"));
        checked.put("publicNetworkAccess", properties.get("publicNetworkAccess"));
        checked.put("semanticSearch", properties.get("semanticSearch"));
        checked.put("knowledgeRetrieval", properties.get("knowledgeRetrieval"));
        checked.put("networkRuleSet.bypass", networkRuleSet.get("bypass"));
        checked.put("encryptionWithCmk.enforcement", encryption.get("enforcement"));
        for (Map.Entry<String, Object> property : checked.entrySet()) {
            if (property.getValue() != null && !isOneOf(property.getValue(), ENUMS.get(property.getKey()))) {
                return Optional.of(invalid("The '" + property.getKey() + "' value '" + property.getValue()
                        + "' is invalid: it must be one of " + ENUMS.get(property.getKey()) + "."));
            }
        }
        if (properties.get("dataExfiltrationProtections") instanceof List<?> protections) {
            for (Object protection : protections) {
                if (!isOneOf(protection, DATA_EXFILTRATION_PROTECTIONS)) {
                    return Optional.of(invalid("The 'dataExfiltrationProtections' value '" + protection
                            + "' is invalid: it must be one of " + DATA_EXFILTRATION_PROTECTIONS + "."));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isOneOf(Object value, List<String> allowed) {
        return value instanceof String text && allowed.stream().anyMatch(text::equalsIgnoreCase);
    }

    /** The first firewall rule whose {@code value} is not "a single IPv4 address ... or an IP range in CIDR format". */
    private static Optional<Response> invalidNetworkRules(Map<String, Object> properties) {
        if (!(ArmJson.cast(properties.get("networkRuleSet")).get("ipRules") instanceof List<?> rules)) {
            return Optional.empty();
        }
        for (Object rule : rules) {
            Object value = ArmJson.cast(rule).get("value");
            if (!(value instanceof String text) || !isIpv4OrCidr(text)) {
                return Optional.of(invalid("The IP rule value '" + value + "' is invalid: it must be an IPv4 "
                        + "address such as 123.1.2.3 or a CIDR range such as 123.1.2.0/24."));
            }
        }
        return Optional.empty();
    }

    private static boolean isIpv4OrCidr(String value) {
        String[] parts = value.split("/", -1);
        if (parts.length > 2 || (parts.length == 2 && !isNumberIn(parts[1], 0, 32))) {
            return false;
        }
        String[] octets = parts[0].split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (!isNumberIn(octet, 0, 255)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNumberIn(String text, int min, int max) {
        if (text.isEmpty() || text.length() > 3 || !text.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int number = Integer.parseInt(text);
        return number >= min && number <= max;
    }

    /**
     * Refuses a scale the SKU does not reach, judged on the service as the write would leave it: what the body
     * sends over {@code base}, the stored properties for a PATCH and none for a PUT. The limits are the spec's
     * (the partition counts, {@code HighDensity} on {@code standard3} alone and its three partitions) and those of
     * Microsoft's service limits page (each SKU's ceiling and the 36 search units). Neither gives the error, so the
     * answer is ARM's generic {@code 400 InvalidRequestContent}.
     */
    private static Optional<Response> capacityBreach(String sku, Map<String, Object> sent, Map<String, Object> base) {
        int replicas = unitCount(choose(sent, base, "replicaCount", MIN_UNITS)).orElse(MIN_UNITS);
        int partitions = unitCount(choose(sent, base, "partitionCount", MIN_UNITS)).orElse(MIN_UNITS);
        boolean highDensity = HIGH_DENSITY.equalsIgnoreCase(String.valueOf(choose(sent, base, "hostingMode", "")));
        Capacity capacity = CAPACITY.get(sku);

        if (highDensity && !HIGH_DENSITY_SKU.equals(sku)) {
            return Optional.of(invalid("The hosting mode 'HighDensity' is only available on the '" + HIGH_DENSITY_SKU
                    + "' SKU; the '" + sku + "' SKU takes 'Default'."));
        }
        if (!PARTITION_COUNTS.contains(partitions)) {
            return Optional.of(invalid("The 'partitionCount' is invalid: it must be one of " + PARTITION_COUNTS + "."));
        }
        if (replicas > capacity.replicas()) {
            return Optional.of(invalid("The '" + sku + "' SKU supports at most " + capacity.replicas()
                    + " replica(s); " + replicas + " were requested."));
        }
        int maxPartitions = highDensity ? MAX_HIGH_DENSITY_PARTITIONS : capacity.partitions();
        if (partitions > maxPartitions) {
            return Optional.of(invalid("The '" + sku + "' SKU" + (highDensity ? " in 'HighDensity' hosting mode" : "")
                    + " supports at most " + maxPartitions + " partition(s); " + partitions + " were requested."));
        }
        if (replicas * partitions > MAX_SEARCH_UNITS) {
            return Optional.of(invalid("A search service has at most " + MAX_SEARCH_UNITS + " search units (replicas "
                    + "times partitions); " + replicas * partitions + " were requested."));
        }
        return Optional.empty();
    }

    /** The body's SKU name in the spec's lowercase spelling, or empty when it names none the spec lists. */
    private static Optional<String> skuName(Map<String, Object> body) {
        String name = ArmJson.string(ArmJson.cast(body.get("sku")), "name", "").toLowerCase(Locale.ROOT);
        return SKU_NAMES.contains(name) ? Optional.of(name) : Optional.empty();
    }

    private static Optional<Integer> unitCount(Object value) {
        if (value instanceof Integer || value instanceof Long) {
            long count = ((Number) value).longValue();
            if (count >= MIN_UNITS && count <= MAX_UNITS) {
                return Optional.of((int) count);
            }
        }
        return Optional.empty();
    }

    // ── Response properties ──────────────────────────────────────────────────

    /**
     * The response {@code properties}: each writable one from {@code sent}, else from {@code base}, else its
     * default, and the read-only ones as a provisioned service reports them. A PUT passes an empty {@code base}, so
     * what it leaves out goes back to the default; a PATCH passes the stored properties, so it stays.
     */
    private static Map<String, Object> properties(Map<String, Object> sent, Map<String, Object> base,
                                                  String endpoint) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("replicaCount", unitCount(choose(sent, base, "replicaCount", MIN_UNITS)).orElse(MIN_UNITS));
        properties.put("partitionCount", unitCount(choose(sent, base, "partitionCount", MIN_UNITS)).orElse(MIN_UNITS));
        properties.put("endpoint", endpoint);
        properties.put("status", "running");
        properties.put("statusDetails", "");
        properties.put("provisioningState", "succeeded");
        // Azure answers the hosting mode in the casing it was sent, and the public network access in the spec's.
        properties.put("hostingMode", choose(sent, base, "hostingMode", "Default"));
        properties.put("computeType", choose(sent, base, "computeType", "Default"));
        properties.put("publicNetworkAccess", publicNetworkAccess(choose(sent, base, "publicNetworkAccess", "Enabled")));
        properties.put("networkRuleSet", networkRuleSet(ArmJson.cast(choose(sent, base, "networkRuleSet", Map.of()))));
        properties.put("privateEndpointConnections", List.of());
        properties.put("sharedPrivateLinkResources", List.of());
        Map<String, Object> encryption = new LinkedHashMap<>();
        encryption.put("enforcement", ArmJson.string(
                ArmJson.cast(choose(sent, base, "encryptionWithCmk", Map.of())), "enforcement", "Unspecified"));
        encryption.put("encryptionComplianceStatus", "Compliant");
        properties.put("encryptionWithCmk", encryption);
        boolean disableLocalAuth = Boolean.TRUE.equals(choose(sent, base, "disableLocalAuth", false));
        properties.put("disableLocalAuth", disableLocalAuth);
        // A service with local authentication disabled has no data-plane auth options: Azure answers null.
        Object authOptions = choose(sent, base, "authOptions", null);
        properties.put("authOptions", disableLocalAuth ? null
                : authOptions != null ? authOptions : Map.of("apiKeyOnly", Map.of()));
        properties.put("dataExfiltrationProtections", choose(sent, base, "dataExfiltrationProtections", List.of()));
        properties.put("semanticSearch", choose(sent, base, "semanticSearch", "free"));
        // A preview property (2026-03-01-preview on): answered once a client has set it, so the stable
        // api-versions' answer is unchanged.
        Object knowledgeRetrieval = choose(sent, base, "knowledgeRetrieval", null);
        if (knowledgeRetrieval != null) {
            properties.put("knowledgeRetrieval", knowledgeRetrieval);
        }
        properties.put("upgradeAvailable", "notAvailable");
        properties.put("serviceUpgradedAt", null);
        return properties;
    }

    private static Object choose(Map<String, Object> sent, Map<String, Object> base, String key, Object fallback) {
        Object value = sent.get(key);
        if (value == null) {
            value = base.get(key);
        }
        return value == null ? fallback : value;
    }

    private static Object publicNetworkAccess(Object value) {
        for (String known : PUBLIC_NETWORK_ACCESS) {
            if (known.equalsIgnoreCase(String.valueOf(value))) {
                return known;
            }
        }
        return value;
    }

    private static Map<String, Object> networkRuleSet(Map<String, Object> sent) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("ipRules", sent.get("ipRules") instanceof List<?> ipRules ? ipRules : List.of());
        rules.put("bypass", ArmJson.string(sent, "bypass", "None"));
        return rules;
    }

    /**
     * The identity as Azure answers it, built from the request's {@code type} and identity ids alone: every id in
     * it is read-only, so none is taken from the caller. A system-assigned identity gets a principal, kept across
     * updates, and the tenant. Each user-assigned identity gets its principal and client ids from the Managed
     * Identity store. {@code UserAssignedIdentityRule} refuses an identity the store does not hold before the request
     * gets here, so the deterministic ids, as {@code PolicyHandler.buildIdentity()} gives them, serve only when the
     * Managed Identity service is disabled. An identity of type {@code None} is no identity.
     */
    private Map<String, Object> identity(Object sent, Map<String, Object> existing, AzureRequest req) {
        Map<String, Object> body = ArmJson.cast(sent);
        String type = identityType(body.get("type")).orElse(IDENTITY_NONE);
        if (IDENTITY_NONE.equals(type)) {
            return null;
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("type", type);
        if (type.startsWith(IDENTITY_SYSTEM)) {
            identity.put("principalId", existing != null && existing.get("principalId") instanceof String principal
                    ? principal : UUID.randomUUID().toString());
            if (req.scope() != null && req.scope().tenantId() != null) {
                identity.put("tenantId", req.scope().tenantId());
            }
        }
        if (type.endsWith(IDENTITY_USER)) {
            Map<String, Object> userAssigned = new LinkedHashMap<>();
            // A null entry removes that identity, as UserAssignedIdentityRule reads it.
            ArmJson.cast(body.get("userAssignedIdentities")).forEach((resourceId, value) -> {
                if (value != null) {
                    userAssigned.put(resourceId, userAssignedIds(resourceId));
                }
            });
            identity.put("userAssignedIdentities", userAssigned);
        }
        return identity;
    }

    private Map<String, Object> userAssignedIds(String resourceId) {
        Map<String, Object> ids = new LinkedHashMap<>();
        Optional<UserAssignedIdentity> stored = identities.findByResourceId(resourceId);
        String seed = resourceId.toLowerCase(Locale.ROOT);
        ids.put("principalId", stored.map(UserAssignedIdentity::principalId)
                .orElseGet(() -> TokenIssuer.deterministicGuid("search-uai-principal:" + seed)));
        ids.put("clientId", stored.map(UserAssignedIdentity::clientId)
                .orElseGet(() -> TokenIssuer.deterministicGuid("search-uai-client:" + seed)));
        return ids;
    }

    /** The spec's spelling of an {@code IdentityType}, matched ignoring case and the space after the comma. */
    private static Optional<String> identityType(Object sent) {
        if (!(sent instanceof String text)) {
            return Optional.empty();
        }
        String compact = text.replace(" ", "");
        return IDENTITY_TYPES.stream().filter(known -> known.replace(" ", "").equalsIgnoreCase(compact)).findFirst();
    }

    private static Map<String, Object> tags(Object sent) {
        return new LinkedHashMap<>(ArmJson.cast(sent));
    }

    private static Map<String, String> stringTags(Map<String, Object> tags) {
        Map<String, String> strings = new LinkedHashMap<>();
        if (tags != null) {
            tags.forEach((key, value) -> strings.put(key, String.valueOf(value)));
        }
        return strings;
    }

    // ── Location, endpoint, keys ─────────────────────────────────────────────

    /** The canonical location of the PUT: the one the gate's location rule read, else the body's, else the default. */
    private String location(AzureRequest req, CloudProfile cloud, Map<String, Object> body) {
        if (req.armCall() != null && req.armCall().normalizedLocation() != null) {
            return req.armCall().normalizedLocation();
        }
        String sent = ArmJson.string(body, "location", null);
        return sent == null ? defaults.defaultLocation(req.scope()) : CloudCatalog.normalizeLocation(cloud, sent);
    }

    /** A search service answers its location as a display name ({@code West US}), whichever form it was sent. */
    private static String displayLocation(CloudProfile cloud, String location) {
        return cloud.location(location).map(Location::displayName).orElse(location);
    }

    private static String endpoint(CloudProfile cloud, String name) {
        String suffix = cloud.suffix(CloudSuffix.SEARCH)
                .or(() -> CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD).suffix(CloudSuffix.SEARCH))
                .orElseThrow();
        return "https://" + name + "." + suffix;
    }

    /** The subscription and resource group the request gate resolved; empty for a request that names neither. */
    private static Optional<ArmScope> scope(AzureRequest req) {
        if (req.scope() == null || req.scope().subscriptionId() == null) {
            return Optional.empty();
        }
        return Optional.of(new ArmScope(req.scope().subscriptionId(), req.scope().resourceGroup()));
    }

    private static CloudProfile cloud(AzureRequest req) {
        return req.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : req.scope().cloud();
    }

    /** An API key in the shape of Azure's: 52 letters and digits. */
    private String newKey() {
        StringBuilder key = new StringBuilder(KEY_LENGTH);
        for (int i = 0; i < KEY_LENGTH; i++) {
            key.append(KEY_ALPHABET.charAt(random.nextInt(KEY_ALPHABET.length())));
        }
        return key.toString();
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    // ── Paths ────────────────────────────────────────────────────────────────

    /**
     * The id of the service {@code name} the request addresses, spelled as Azure spells the type; empty for a request
     * that names no resource group.
     */
    private static Optional<ArmResourceId> serviceId(AzureRequest req, String name) {
        return scope(req)
                .filter(scope -> scope.resourceGroup() != null)
                .flatMap(scope -> ArmResourceId.parse("/subscriptions/" + scope.subscription() + "/resourceGroups/"
                        + scope.resourceGroup() + "/providers/" + SearchModels.SEARCH_SERVICES + "/" + name));
    }

    private static List<String> segments(String tail) {
        List<String> segments = new ArrayList<>();
        for (String segment : tail.split("/")) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private static String withoutQuery(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    // ── Responses ────────────────────────────────────────────────────────────

    private static Response ok(Object body) {
        return Response.ok(body).type("application/json").build();
    }

    /** ARM's own not-found answer, as Azure gave it for a deleted search service. */
    private static Response serviceNotFound(ArmResourceId id) {
        return ArmErrors.notFound("The Resource '" + SearchModels.SEARCH_SERVICES + "/" + id.name()
                + "' under resource group '" + id.resourceGroup()
                + "' was not found. For more details please go to https://aka.ms/ARMResourceNotFoundFix");
    }

    private static Response unknownChild(List<String> tail) {
        return ArmErrors.notFound("Unknown Microsoft.Search path: " + String.join("/", tail));
    }

    private static Response invalid(String message) {
        return ArmErrors.error(400, "InvalidRequestContent", message);
    }

    private static Response methodNotAllowed() {
        return Response.status(405).build();
    }
}
