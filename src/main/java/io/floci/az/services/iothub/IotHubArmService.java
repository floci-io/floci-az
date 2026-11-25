package io.floci.az.services.iothub;

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
import io.floci.az.services.iothub.IotHubModels.AccessPolicy;
import io.floci.az.services.iothub.IotHubModels.ConsumerGroup;
import io.floci.az.services.iothub.IotHubModels.IotHubResource;
import io.floci.az.services.managedidentity.ManagedIdentityModels.UserAssignedIdentity;
import io.floci.az.services.managedidentity.ManagedIdentityStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code Microsoft.Devices} management plane: IoT hubs and their shared access policies.
 *
 * <pre>
 *   PUT/GET/PATCH/DELETE .../providers/Microsoft.Devices/IotHubs/{name}
 *   GET    .../providers/Microsoft.Devices/IotHubs                             (resource group or subscription)
 *   POST   .../IotHubs/{name}/listkeys
 *   POST   .../IotHubs/{name}/IotHubKeys/{keyName}/listkeys
 *   POST   /subscriptions/{sub}/providers/Microsoft.Devices/checkNameAvailability
 * </pre>
 *
 * <p>Shapes follow the resource-manager spec (2023-06-30) and the responses Azure gave the az CLI's {@code iot}
 * test recordings (2023-06-30-preview):
 * <ul>
 *   <li>A PUT replaces the hub: a property section it leaves out falls back to Azure's default, except
 *       {@code authorizationPolicies}, which keep their keys when left out. A policy sent without a key gets a new
 *       one, which is how {@code az iot hub policy renew-key} regenerates it.</li>
 *   <li>Azure answers every PUT with {@code 201}, an update too. It answers with {@code Accepted} and an
 *       {@code Azure-AsyncOperation} header; floci-az completes the write before answering, so the body already reads
 *       {@code Succeeded} and no header is sent, which ends the clients' polling at once.</li>
 *   <li>A GET never carries the policies: "Security-related properties are set to null". Keys come only from the
 *       {@code listkeys} operations.</li>
 *   <li>{@code If-Match}, when sent with anything but {@code *}, must name the hub's current ETag.</li>
 *   <li>A PATCH updates the tags only, as {@code IotHubResource_Update} is specified.</li>
 *   <li>Errors the hub itself reports use the IoT Hub {@code ErrorDetails} shape
 *       ({@code {"code": 400016, "httpStatusCode": 400, "message": ...}}), with the codes Azure gave.</li>
 * </ul>
 */
@ApplicationScoped
public class IotHubArmService {

    private static final Logger LOG = Logger.getLogger(IotHubArmService.class);

    private static final String STORE = "iothub";
    private static final String HUBS_SEGMENT = "IotHubs";

    /** {@code IotHubSku} (2023-06-30) with the {@code IotHubSkuTier} each one is billed at. */
    private static final Map<String, String> SKU_TIERS = Map.of(
            "F1", "Free",
            "S1", "Standard", "S2", "Standard", "S3", "Standard",
            "B1", "Basic", "B2", "Basic", "B3", "Basic");
    private static final String FREE_SKU = "F1";

    /** {@code AccessRights} (2023-06-30), every combination the spec enumerates. */
    private static final List<String> ACCESS_RIGHTS = List.of("RegistryRead", "RegistryWrite", "ServiceConnect",
            "DeviceConnect", "RegistryRead, RegistryWrite", "RegistryRead, ServiceConnect",
            "RegistryRead, DeviceConnect", "RegistryWrite, ServiceConnect", "RegistryWrite, DeviceConnect",
            "ServiceConnect, DeviceConnect", "RegistryRead, RegistryWrite, ServiceConnect",
            "RegistryRead, RegistryWrite, DeviceConnect", "RegistryRead, ServiceConnect, DeviceConnect",
            "RegistryWrite, ServiceConnect, DeviceConnect", "RegistryRead, RegistryWrite, ServiceConnect, DeviceConnect");

    /** The policies a new hub has, with the rights Azure lists for them. */
    private static final List<Map.Entry<String, String>> DEFAULT_POLICIES = List.of(
            Map.entry("iothubowner", "RegistryWrite, ServiceConnect, DeviceConnect"),
            Map.entry("service", "ServiceConnect"),
            Map.entry("device", "DeviceConnect"),
            Map.entry("registryRead", "RegistryRead"),
            Map.entry("registryReadWrite", "RegistryWrite"));

    /** The TLS versions a hub accepts as {@code minTlsVersion}. */
    private static final List<String> TLS_VERSIONS = List.of("1.0", "1.2");

    /** The read-only properties of {@code IotHubProperties}, which a request cannot set. */
    private static final Set<String> READ_ONLY_PROPERTIES = Set.of("provisioningState", "state", "hostName",
            "locations");
    /** The read-only properties of {@code EventHubProperties}. */
    private static final Set<String> READ_ONLY_EVENT_HUB = Set.of("partitionIds", "path", "endpoint");

    private static final String EVENTS_ENDPOINT = IotHubModels.EVENTS_ENDPOINT;
    private static final int MIN_PARTITIONS = 2;
    /**
     * "The maximum limit of device-to-cloud partitions for basic tier and standard tier IoT hubs is 32" (Learn, IoT
     * Hub scaling). azurerm's own check allows up to 128, but Azure's limit is the one that answers.
     */
    private static final int MAX_PARTITIONS = 32;
    private static final int DEFAULT_PARTITIONS = 4;
    private static final int DEFAULT_RETENTION_DAYS = 1;
    /** Device-to-cloud retention Azure allows, in days; the free tier keeps one day only. */
    private static final int MAX_RETENTION_DAYS = 7;
    /** The units each SKU allows, from Azure's IoT Hub limits; the rest allow {@link #DEFAULT_MAX_UNITS}. */
    private static final Map<String, Integer> MAX_UNITS = Map.of("F1", 1, "S3", 10, "B3", 10);
    private static final int DEFAULT_MAX_UNITS = 200;
    /**
     * "You can have up to 20 consumer groups for each IoT hub" (Learn, the built-in endpoint), for every tier. Learn
     * does not say whether {@code $Default} counts; it is counted here.
     */
    private static final int MAX_CONSUMER_GROUPS = 20;

    /** The read-only views of a hub, lower-cased: {@code skus}, {@code quotaMetrics} and {@code IotHubStats}. */
    private static final Set<String> HUB_VIEWS = Set.of("skus", "quotametrics", "iothubstats");

    private static final String IDENTITY_NONE = "None";
    private static final String IDENTITY_SYSTEM = "SystemAssigned";
    private static final String IDENTITY_USER = "UserAssigned";
    private static final List<String> IDENTITY_TYPES = List.of(IDENTITY_NONE, IDENTITY_SYSTEM, IDENTITY_USER,
            IDENTITY_SYSTEM + ", " + IDENTITY_USER);

    private static final String LOWER_ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String HEX = "0123456789abcdef";

    private final ArmResourceStore<IotHubResource> hubs;
    /** Per instance, not static: a static one would be built into the native image with a fixed seed. */
    private final SecureRandom random = new SecureRandom();
    private final GlobalNameRegistry names;
    private final CloudDefaults defaults;
    private final ManagedIdentityStore identities;
    private final IotHubDevices devices;

    @Inject
    public IotHubArmService(StorageFactory storageFactory, GlobalNameRegistry names, CloudDefaults defaults,
                            ManagedIdentityStore identities, IotHubDevices devices) {
        this.hubs = ArmResourceStore.create(storageFactory, STORE, IotHubModels.IOT_HUBS, IotHubResource.class,
                IotHubResource::id, key -> false);
        this.names = names;
        this.defaults = defaults;
        this.identities = identities;
        this.devices = devices;
    }

    /** Serves one request under {@code /providers/Microsoft.Devices/}. */
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
        // A tenant-rooted path (providers/Microsoft.Devices/operations) has no slash before its provider marker.
        String markerPath = path.startsWith("/") ? path : "/" + path;
        List<String> tail = segments(ArmPaths.afterProvider(markerPath, IotHubArmSurface.NAMESPACE, ""));

        if (tail.size() == 1 && tail.getFirst().equalsIgnoreCase("checkNameAvailability")) {
            return "POST".equals(method) ? checkNameAvailability(req) : methodNotAllowed();
        }
        if (tail.size() == 1 && tail.getFirst().equalsIgnoreCase("operations")) {
            return "GET".equals(method) ? ok(Map.of("value", IotHubCatalog.OPERATIONS)) : methodNotAllowed();
        }
        if (tail.isEmpty() || !tail.getFirst().equalsIgnoreCase(HUBS_SEGMENT)) {
            return unknownPath(tail);
        }
        if (tail.size() == 1) {
            return "GET".equals(method) ? list(req) : methodNotAllowed();
        }

        String name = tail.get(1);
        Optional<ArmResourceId> id = hubId(req, name);
        if (id.isEmpty()) {
            // Every hub lives in a resource group, so a path that names none addresses none.
            return ArmErrors.notFound("The Resource '" + IotHubModels.IOT_HUBS + "/" + name + "' was not found.");
        }
        if (tail.size() == 2) {
            return switch (method) {
                case "PUT" -> createOrUpdate(req, id.get());
                case "GET" -> hubs.get(id.get()).map(stored -> ok(toArm(req, stored.value())))
                        .orElseGet(() -> hubNotFound(id.get()));
                case "PATCH" -> updateTags(req, id.get());
                case "DELETE" -> delete(req, id.get());
                default -> methodNotAllowed();
            };
        }
        if (tail.size() == 3 && tail.get(2).equalsIgnoreCase("listkeys")) {
            return "POST".equals(method) ? listKeys(id.get()) : methodNotAllowed();
        }
        if (tail.size() == 3 && HUB_VIEWS.contains(tail.get(2).toLowerCase(Locale.ROOT))) {
            if (!"GET".equals(method)) {
                return methodNotAllowed();
            }
            return hubs.get(id.get())
                    .map(stored -> ok(hubView(req, tail.get(2).toLowerCase(Locale.ROOT), stored.value())))
                    .orElseGet(() -> hubNotFound(id.get()));
        }
        if (tail.size() == 5 && tail.get(2).equalsIgnoreCase("IotHubKeys") && tail.get(4).equalsIgnoreCase("listkeys")) {
            return "POST".equals(method) ? keysForKeyName(id.get(), tail.get(3)) : methodNotAllowed();
        }
        // Clients spell these segments differently: the az CLI sends ConsumerGroups, azurerm consumerGroups.
        if (tail.size() >= 5 && tail.size() <= 6 && tail.get(2).equalsIgnoreCase("eventHubEndpoints")
                && tail.get(4).equalsIgnoreCase("ConsumerGroups")) {
            String endpoint = tail.get(3);
            if (tail.size() == 5) {
                return "GET".equals(method) ? listConsumerGroups(id.get(), endpoint) : methodNotAllowed();
            }
            // The path is already percent-decoded: %24Default arrives as $Default.
            String group = tail.get(5);
            return switch (method) {
                case "PUT" -> createConsumerGroup(req, id.get(), endpoint, group);
                case "GET" -> getConsumerGroup(id.get(), endpoint, group);
                case "DELETE" -> deleteConsumerGroup(id.get(), endpoint, group);
                default -> methodNotAllowed();
            };
        }
        return unknownPath(tail);
    }

    // ── Hub views ────────────────────────────────────────────────────────────

    /**
     * {@code IotHubResource_GetValidSkus}, {@code _GetQuotaMetrics} and {@code _GetStats}, answered as Azure answered
     * the az CLI. The two lists come in one page, without a {@code nextLink}.
     */
    private Object hubView(AzureRequest req, String view, IotHubResource hub) {
        IotHubDevices.Counts counts = devices.counts(cloudName(req), hub.name());
        return switch (view) {
            case "skus" -> Map.of("value", validSkus(hub.sku()));
            case "quotametrics" -> Map.of("value",
                    IotHubCatalog.quotaMetrics(hub.sku(), hub.capacity(), counts.total()));
            default -> IotHubCatalog.registryStatistics(counts);
        };
    }

    /**
     * The SKUs a hub can move to ({@link #canMoveTo}), each with the units it allows: an S1 hub lists S1, S2 and S3,
     * as Azure answered, and a Basic hub its own tier's SKUs and then Standard's.
     */
    private static List<Map<String, Object>> validSkus(String current) {
        List<Map<String, Object>> skus = new ArrayList<>();
        SKU_TIERS.keySet().stream()
                .filter(name -> canMoveTo(current, name))
                .sorted()
                .forEach(name -> {
                    Map<String, Object> capacity = new LinkedHashMap<>();
                    capacity.put("minimum", 1);
                    capacity.put("maximum", MAX_UNITS.getOrDefault(name, DEFAULT_MAX_UNITS));
                    capacity.put("default", 1);
                    capacity.put("scaleType", FREE_SKU.equals(name) ? "None" : "Manual");
                    Map<String, Object> sku = new LinkedHashMap<>();
                    sku.put("resourceType", IotHubModels.IOT_HUBS);
                    Map<String, Object> skuInfo = new LinkedHashMap<>();
                    skuInfo.put("name", name);
                    skuInfo.put("tier", SKU_TIERS.get(name));
                    sku.put("sku", skuInfo);
                    sku.put("capacity", capacity);
                    skus.add(sku);
                });
        return skus;
    }

    /**
     * Whether a hub on SKU {@code from} can move to {@code to}: within its tier, or from Basic to Standard. Learn's
     * "Upgrade an IoT hub": "You can only upgrade from the basic tier to the standard tier", "you can't downgrade to a
     * lower tier", and "You can't change the tier of a free or standard hub".
     */
    private static boolean canMoveTo(String from, String to) {
        String fromTier = SKU_TIERS.get(from);
        String toTier = SKU_TIERS.get(to);
        return fromTier.equals(toTier) || ("Basic".equals(fromTier) && "Standard".equals(toTier));
    }

    private static void refuseSkuChange(IotHubResource hub, String sku) {
        if (!canMoveTo(hub.sku(), sku)) {
            throw new Refused(invalid("IoT hub '" + hub.name() + "' cannot move from " + hub.sku() + " ("
                    + SKU_TIERS.get(hub.sku()) + ") to " + sku + " (" + SKU_TIERS.get(sku) + "): a hub changes SKU"
                    + " within its tier, or from Basic to Standard."));
        }
    }

    // ── Hubs ─────────────────────────────────────────────────────────────────

    /**
     * {@code IotHubResource_CreateOrUpdate}: the body replaces the hub, and Azure's defaults fill the property sections
     * it leaves out. The keys, the creation time and the built-in endpoint's names stay.
     */
    private Response createOrUpdate(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Map<String, Object> sent = ArmJson.cast(body.get("properties"));
        Optional<String> sku = skuName(body);
        if (sku.isEmpty()) {
            return invalid(body.get("sku") == null ? "The 'sku' of an IoT hub is required."
                    : "The SKU '" + ArmJson.cast(body.get("sku")).get("name") + "' is invalid: it must be one of "
                    + String.join(", ", SKU_TIERS.keySet().stream().sorted().toList()) + ".");
        }
        Optional<Integer> capacity = capacity(body);
        if (capacity.isEmpty()) {
            return invalid("The SKU capacity of an IoT hub must be a whole number of at least 1.");
        }
        int maxUnits = MAX_UNITS.getOrDefault(sku.get(), DEFAULT_MAX_UNITS);
        if (capacity.get() > maxUnits) {
            return invalid("The SKU " + sku.get() + " allows at most " + maxUnits + " IoT hub unit"
                    + (maxUnits == 1 ? "" : "s") + "; the request asks for " + capacity.get() + ".");
        }
        Optional<Response> badIdentity = malformedIdentity(body.get("identity"));
        if (badIdentity.isPresent()) {
            return badIdentity.get();
        }
        Optional<Response> refused = validateProperties(sent, sku.get());
        if (refused.isPresent()) {
            return refused.get();
        }
        Optional<Response> malformed = malformedPolicies(sent.get("authorizationPolicies"));
        if (malformed.isPresent()) {
            return malformed.get();
        }
        Optional<List<Map<String, Object>>> sentPolicies = sentPolicies(sent);
        if (sentPolicies.isPresent()) {
            Optional<Response> badPolicy = validatePolicies(sentPolicies.get());
            if (badPolicy.isPresent()) {
                return badPolicy.get();
            }
        }
        CloudProfile cloud = cloud(req);
        String location = location(req, cloud, body);
        String ifMatch = ifMatch(req);

        ArmResourceStore.Written<IotHubResource> written;
        try {
            // Every check that reads the stored hub runs inside the store's lock, so a concurrent DELETE cannot
            // change the answer between the check and the write.
            written = hubs.createOrUpdate(id, existing -> {
                existing.ifPresentOrElse(hub -> {
                    refuseStaleEtag(hub, ifMatch);
                    refuseLocationChange(hub, location);
                    refuseSkuChange(hub, sku.get());
                }, () -> refuseETagOfMissingHub(id, ifMatch));
                refusePartitionCount(sent, sku.get(), existing);
                // The id keeps the casing of the PUT that created the hub.
                ArmResourceId stored = existing.map(hub -> ArmResourceId.parse(hub.id()).orElseThrow()).orElse(id);
                List<AccessPolicy> policies = sentPolicies
                        .map(this::policiesFrom)
                        .orElseGet(() -> existing.map(IotHubResource::policies).orElseGet(this::defaultPolicies));
                Map<String, Object> restored = Secrets.restore(sent,
                        existing.map(IotHubResource::properties).orElse(Map.of()));
                if (Secrets.containsMask(restored)) {
                    throw new Refused(invalid("The request sends a masked secret ('" + Secrets.MASK + "') that"
                            + " matches no stored one: send the endpoint's real connection string or key, and keep"
                            + " the endpoint's name when sending back the masked value."));
                }
                Map<String, Object> properties = properties(restored, sku.get());
                existing.ifPresent(hub -> keepPartitionCount(hub, sent, properties));
                return new IotHubResource(stored.id(), stored.name(), location, tags(body.get("tags")), sku.get(),
                        capacity.get(),
                        identity(body.get("identity"), existing.map(IotHubResource::identity).orElse(null), req),
                        properties,
                        policies,
                        existing.map(IotHubResource::incarnation).orElseGet(random::nextInt),
                        existing.map(hub -> hub.version() + 1).orElse(1L),
                        existing.map(IotHubResource::createdAt).orElseGet(IotHubArmService::now),
                        existing.map(IotHubResource::eventHubPath).orElseGet(() -> newEventHubPath(stored.name())),
                        existing.map(IotHubResource::eventHubNamespace)
                                .orElseGet(() -> newEventHubNamespace(stored.name())),
                        // A hub PUT replaces the hub's body, which never lists its groups: they stay. A new hub
                        // gets $Default, created with it.
                        existing.map(IotHubResource::consumerGroups).orElse(null));
            });
        } catch (Refused refusal) {
            return refusal.response();
        }
        LOG.infov("ARM: {0} IoT hub {1}", written.created() ? "created" : "updated", written.stored().value().id());
        return Response.status(201).entity(toArm(req, written.stored().value())).type("application/json").build();
    }

    /** {@code IotHubResource_Update}: the tags the body carries replace the hub's; nothing else changes. */
    private Response updateTags(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        String ifMatch = ifMatch(req);
        try {
            return hubs.update(id, hub -> {
                        refuseStaleEtag(hub, ifMatch);
                        return hub.withTags(body.containsKey("tags") ? tags(body.get("tags")) : hub.tags());
                    })
                    .map(stored -> ok(toArm(req, stored.value())))
                    .orElseGet(() -> hubNotFound(id));
        } catch (Refused refusal) {
            return refusal.response();
        }
    }

    /** {@code IotHubResource_Delete}: {@code 200} for a hub that existed, {@code 204} for one that did not. */
    private Response delete(AzureRequest req, ArmResourceId id) {
        String ifMatch = ifMatch(req);
        Optional<ArmResourceStore.Stored<IotHubResource>> removed;
        // The registry's lock is taken first, as the registry's writes take it before they read the hub: no device
        // can be written between the hub's removal and its registry's, and a hub created again under the name, once
        // the name is free, starts with no devices. The store's writes lock the store itself, so holding it too makes
        // the check and the delete one step.
        synchronized (devices.writeLock()) {
            synchronized (hubs) {
                Optional<ArmResourceStore.Stored<IotHubResource>> found = hubs.get(id);
                if (found.isPresent()) {
                    try {
                        refuseStaleEtag(found.get().value(), ifMatch);
                    } catch (Refused refusal) {
                        return refusal.response();
                    }
                }
                removed = hubs.delete(id);
            }
            if (removed.isEmpty()) {
                return Response.noContent().build();
            }
            devices.deleteAll(cloudName(req), removed.get().value().name());
        }
        LOG.infov("ARM: deleted IoT hub {0}", removed.get().value().id());
        return Response.ok().build();
    }

    /** The hubs of the request's subscription, or of its resource group when it names one. */
    private Response list(AzureRequest req) {
        List<Map<String, Object>> value = scope(req)
                .map(scope -> hubs.list(scope).stream().map(stored -> toArm(req, stored.value())).toList())
                .orElse(List.of());
        return ok(Map.of("value", value));
    }

    /**
     * {@code IotHubResource_CheckNameAvailability}, answered as Azure answered the az CLI: an available name reads
     * {@code "reason": "Invalid"} with a null message, a taken one {@code AlreadyExists} with the hub's message.
     */
    private Response checkNameAvailability(AzureRequest req) {
        String name = ArmJson.string(ArmJson.parseBodyStrict(req), "name", "");
        Map<String, Object> answer = new LinkedHashMap<>();
        if (!IotHubArmSurface.IOT_HUB_NAME.accepts(name)) {
            answer.put("nameAvailable", false);
            answer.put("reason", "Invalid");
            answer.put("message", IotHubArmSurface.IOT_HUB_NAME.message(name));
        } else if (names.owner(cloud(req), CloudSuffix.IOTHUB, name).isPresent()) {
            answer.put("nameAvailable", false);
            answer.put("reason", "AlreadyExists");
            answer.put("message", "IotHub name '" + name + "' is not available");
        } else {
            answer.put("nameAvailable", true);
            answer.put("reason", "Invalid");
            answer.put("message", null);
        }
        return ok(answer);
    }

    // ── Keys ─────────────────────────────────────────────────────────────────

    /** {@code IotHubResource_ListKeys}: every policy with its keys, in one page. */
    private Response listKeys(ArmResourceId id) {
        return hubs.get(id)
                .map(stored -> ok(Map.of("value", stored.value().policies().stream().map(AccessPolicy::toArm).toList())))
                .orElseGet(() -> hubNotFound(id));
    }

    /** {@code IotHubResource_GetKeysForKeyName}: one policy with its keys. */
    private Response keysForKeyName(ArmResourceId id, String keyName) {
        Optional<ArmResourceStore.Stored<IotHubResource>> hub = hubs.get(id);
        if (hub.isEmpty()) {
            return hubNotFound(id);
        }
        return hub.get().value().policies().stream()
                .filter(policy -> policy.keyName().equalsIgnoreCase(keyName))
                .findFirst()
                .map(policy -> ok(policy.toArm()))
                .orElseGet(() -> ArmErrors.notFound("The shared access policy '" + keyName + "' was not found on IoT"
                        + " hub '" + id.name() + "'."));
    }

    // ── Consumer groups ──────────────────────────────────────────────────────

    /** {@code IotHubResource_ListEventHubConsumerGroups}: {@code $Default} first, then in creation order. */
    private Response listConsumerGroups(ArmResourceId id, String endpoint) {
        Optional<ArmResourceStore.Stored<IotHubResource>> hub = hubs.get(id);
        if (hub.isEmpty()) {
            return hubNotFound(id);
        }
        if (!EVENTS_ENDPOINT.equalsIgnoreCase(endpoint)) {
            return endpointNotFound(id, endpoint);
        }
        IotHubResource stored = hub.get().value();
        return ok(Map.of("value", groupsOf(stored, endpoint).stream()
                .map(group -> consumerGroupToArm(stored, group, null))
                .toList()));
    }

    /** {@code IotHubResource_GetEventHubConsumerGroup}. */
    private Response getConsumerGroup(ArmResourceId id, String endpoint, String name) {
        Optional<ArmResourceStore.Stored<IotHubResource>> hub = hubs.get(id);
        if (hub.isEmpty()) {
            return hubNotFound(id);
        }
        if (!EVENTS_ENDPOINT.equalsIgnoreCase(endpoint)) {
            return endpointNotFound(id, endpoint);
        }
        IotHubResource stored = hub.get().value();
        return findGroup(stored, endpoint, name)
                .map(group -> ok(consumerGroupToArm(stored, group, null)))
                .orElseGet(() -> consumerGroupNotFound(id, name));
    }

    /**
     * {@code IotHubResource_CreateEventHubConsumerGroup}: {@code 200} with the group, which echoes the name the body
     * sent ({@code properties.name}, required), as Azure answered the az CLI. A PUT of a group that exists answers it
     * as it is, keeping its creation time.
     */
    private Response createConsumerGroup(AzureRequest req, ArmResourceId id, String endpoint, String name) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        // The name's syntax is the gate's (IotHubArmSurface.CONSUMER_GROUP_NAME, checked in every mode). A missing
        // hub answers before anything about the request's body, as the other consumer group verbs answer it.
        Optional<ArmResourceStore.Stored<IotHubResource>> current = hubs.get(id);
        if (current.isEmpty()) {
            return hubNotFound(id);
        }
        // The body the spec requires (EventHubConsumerGroupBodyDescription): {"properties": {"name": "<name>"}}.
        if (!(body.get("properties") instanceof Map<?, ?> properties)) {
            return invalid("The request body has no 'properties' object: a consumer group is created with"
                    + " {\"properties\": {\"name\": \"" + name + "\"}}.");
        }
        if (!(properties.get("name") instanceof String sentName)) {
            return invalid("The request body's 'properties.name' must be the consumer group's name, as a string.");
        }
        if (!name.equalsIgnoreCase(sentName)) {
            return invalid("The consumer group name in the body ('" + sentName + "') is not the one in the path ('"
                    + name + "').");
        }
        // A repeated PUT answers the group it has without writing the hub again. A group created or deleted after
        // this read is the update's to see, under the store's lock.
        Optional<ConsumerGroup> had = findGroup(current.get().value(), endpoint, name);
        if (had.isPresent()) {
            return ok(consumerGroupToArm(current.get().value(), had.get(), sentName));
        }
        ArmResourceStore.Stored<IotHubResource> written;
        try {
            Optional<ArmResourceStore.Stored<IotHubResource>> updated = hubs.update(id, hub -> {
                if (!EVENTS_ENDPOINT.equalsIgnoreCase(endpoint)) {
                    throw new Refused(endpointNotFound(id, endpoint));
                }
                // A group created since the read above: answered as it is, and the write is aborted, not repeated.
                Optional<ConsumerGroup> created = findGroup(hub, endpoint, name);
                if (created.isPresent()) {
                    throw new Refused(ok(consumerGroupToArm(hub, created.get(), sentName)));
                }
                if (groupsOf(hub, endpoint).size() >= MAX_CONSUMER_GROUPS) {
                    throw new Refused(invalid("IoT hub '" + hub.name() + "' already has " + MAX_CONSUMER_GROUPS
                            + " consumer groups on endpoint '" + EVENTS_ENDPOINT + "', the most a hub can have."));
                }
                List<ConsumerGroup> groups = new ArrayList<>(hub.consumerGroups());
                groups.add(new ConsumerGroup(EVENTS_ENDPOINT, name, now()));
                return hub.withConsumerGroups(groups);
            });
            if (updated.isEmpty()) {
                return hubNotFound(id);
            }
            written = updated.get();
        } catch (Refused refusal) {
            return refusal.response();
        }
        IotHubResource hub = written.value();
        ConsumerGroup group = findGroup(hub, endpoint, name).orElseThrow();
        LOG.infov("ARM: consumer group {0} on IoT hub {1}", group.name(), hub.id());
        return ok(consumerGroupToArm(hub, group, sentName));
    }

    /** {@code IotHubResource_DeleteEventHubConsumerGroup}: {@code 200} with no body. {@code $Default} stays. */
    private Response deleteConsumerGroup(ArmResourceId id, String endpoint, String name) {
        try {
            Optional<ArmResourceStore.Stored<IotHubResource>> updated = hubs.update(id, hub -> {
                if (!EVENTS_ENDPOINT.equalsIgnoreCase(endpoint)) {
                    throw new Refused(endpointNotFound(id, endpoint));
                }
                ConsumerGroup group = findGroup(hub, endpoint, name)
                        .orElseThrow(() -> new Refused(consumerGroupNotFound(id, name)));
                if (IotHubModels.DEFAULT_CONSUMER_GROUP.equalsIgnoreCase(group.name())) {
                    throw new Refused(invalid("The consumer group '" + IotHubModels.DEFAULT_CONSUMER_GROUP
                            + "' cannot be deleted."));
                }
                return hub.withConsumerGroups(hub.consumerGroups().stream()
                        .filter(other -> other != group)
                        .toList());
            });
            if (updated.isEmpty()) {
                return hubNotFound(id);
            }
        } catch (Refused refusal) {
            return refusal.response();
        }
        LOG.infov("ARM: deleted consumer group {0} on IoT hub {1}", name, id.id());
        return Response.ok().build();
    }

    private static List<ConsumerGroup> groupsOf(IotHubResource hub, String endpoint) {
        return hub.consumerGroups().stream().filter(group -> group.endpoint().equalsIgnoreCase(endpoint)).toList();
    }

    /** The group named {@code name}, matched ignoring case as Event Hubs matches consumer group names. */
    private static Optional<ConsumerGroup> findGroup(IotHubResource hub, String endpoint, String name) {
        return groupsOf(hub, endpoint).stream().filter(group -> group.name().equalsIgnoreCase(name)).findFirst();
    }

    /**
     * The group as Azure answered it: {@code created} in RFC 1123, {@code etag} null, and the name URL-encoded in the
     * id ({@code %24Default}). Only a PUT's answer echoes the name it sent, under {@code properties.properties}, in
     * the request's spelling even when it matched a stored group spelt otherwise; {@code sentName} is null otherwise.
     */
    private static Map<String, Object> consumerGroupToArm(IotHubResource hub, ConsumerGroup group, String sentName) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("created", DateTimeFormatter.RFC_1123_DATE_TIME
                .format(Instant.parse(group.createdAt()).atZone(ZoneOffset.UTC)));
        if (sentName != null) {
            properties.put("properties", Map.of("name", sentName));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("properties", properties);
        body.put("id", hub.id() + "/eventHubEndpoints/" + group.endpoint() + "/ConsumerGroups/"
                + URLEncoder.encode(group.name(), StandardCharsets.UTF_8));
        body.put("name", group.name());
        body.put("type", IotHubModels.CONSUMER_GROUPS);
        body.put("etag", null);
        return body;
    }

    private static Response endpointNotFound(ArmResourceId id, String endpoint) {
        return ArmErrors.notFound("IoT hub '" + id.name() + "' has no Event Hub-compatible endpoint '" + endpoint
                + "': its only one is '" + EVENTS_ENDPOINT + "'.");
    }

    private static Response consumerGroupNotFound(ArmResourceId id, String name) {
        return ArmErrors.notFound("The consumer group '" + name + "' was not found on IoT hub '" + id.name() + "'.");
    }

    // ── Resource index, global names, reset ──────────────────────────────────

    /** The index entries of the hubs in {@code scope}. */
    public List<Map<String, Object>> indexEntries(ArmScope scope) {
        return hubs.list(scope).stream().map(stored -> stored.value())
                .map(hub -> ArmResources.indexEntry(hub.id(), hub.name(), IotHubModels.IOT_HUBS, hub.location(),
                        stringTags(hub.tags())))
                .toList();
    }

    /** Every stored hub owns {@code {name}.azure-devices.net}. */
    public List<Claim> globalNames() {
        return hubs.all().stream()
                .map(stored -> new Claim(CloudSuffix.IOTHUB, stored.id().name(), stored.id()))
                .toList();
    }

    /** Whether {@code owner} is a stored hub named {@code name}. */
    public boolean holds(String name, ArmResourceId owner) {
        return hubs.get(owner).filter(stored -> name.equalsIgnoreCase(stored.id().name())).isPresent();
    }

    /** Forgets every hub and its devices: {@code POST /_admin/reset}. */
    public void clear() {
        hubs.clear();
        devices.clear();
    }

    /** The hub that owns {@code name} in the request's cloud: hub names are global, so there is at most one. */
    public Optional<IotHubResource> findByName(AzureRequest req, String name) {
        return names.owner(cloud(req), CloudSuffix.IOTHUB, name)
                .flatMap(hubs::get)
                .map(ArmResourceStore.Stored::value);
    }

    /** The name of the cloud a request addresses, which the device registries are kept by. */
    static String cloudName(AzureRequest req) {
        return cloud(req).name();
    }

    /** The host name of a hub, {@code {name}.azure-devices.net} in the public cloud, from the request's cloud. */
    static String hostName(AzureRequest req, String hubName) {
        return hubName + "." + suffix(cloud(req), CloudSuffix.IOTHUB);
    }

    // ── Response ─────────────────────────────────────────────────────────────

    /**
     * The hub as Azure answers it: the stored properties with the read-only ones added, and without the policies.
     * The host name and the endpoint suffix are read from the request's cloud, so they follow its configuration.
     */
    Map<String, Object> toArm(AzureRequest req, IotHubResource hub) {
        CloudProfile cloud = cloud(req);
        ArmResourceId id = ArmResourceId.parse(hub.id()).orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", hub.id());
        body.put("name", hub.name());
        body.put("type", IotHubModels.IOT_HUBS);
        body.put("location", hub.location());
        body.put("tags", hub.tags() == null ? Map.of() : hub.tags());
        body.put("subscriptionid", id.subscriptionId());
        body.put("resourcegroup", id.resourceGroup());
        body.put("etag", hub.etag());

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("locations", List.of(Map.of("location", displayLocation(cloud, hub.location()),
                "role", "primary")));
        properties.put("state", "Active");
        properties.put("provisioningState", "Succeeded");
        properties.put("hostName", hub.name() + "." + suffix(cloud, CloudSuffix.IOTHUB));
        hub.properties().forEach((key, value) -> properties.put(key,
                "eventHubEndpoints".equals(key) ? eventHubEndpoints(cloud, hub, ArmJson.cast(value))
                        : Secrets.mask(value)));
        body.put("properties", properties);

        Map<String, Object> sku = new LinkedHashMap<>();
        sku.put("name", hub.sku());
        sku.put("tier", SKU_TIERS.get(hub.sku()));
        sku.put("capacity", hub.capacity());
        body.put("sku", sku);
        body.put("identity", hub.identity() == null ? Map.of("type", IDENTITY_NONE) : hub.identity());
        body.put("systemData", Map.of("createdAt", hub.createdAt()));
        return body;
    }

    /** The endpoints with the read-only fields of the built-in {@code events} one: partition ids, path, endpoint. */
    private static Map<String, Object> eventHubEndpoints(CloudProfile cloud, IotHubResource hub,
                                                         Map<String, Object> stored) {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        stored.forEach((name, value) -> {
            Map<String, Object> endpoint = new LinkedHashMap<>(ArmJson.cast(value));
            if (EVENTS_ENDPOINT.equals(name)) {
                int partitions = endpoint.get("partitionCount") instanceof Number count ? count.intValue()
                        : DEFAULT_PARTITIONS;
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < partitions; i++) {
                    ids.add(String.valueOf(i));
                }
                endpoint.put("partitionIds", ids);
                endpoint.put("path", hub.eventHubPath());
                endpoint.put("endpoint", "sb://" + hub.eventHubNamespace() + "."
                        + suffix(cloud, CloudSuffix.SERVICEBUS) + "/");
            }
            endpoints.put(name, endpoint);
        });
        return endpoints;
    }

    // ── Properties ───────────────────────────────────────────────────────────

    /**
     * The properties to store: those sent, without the read-only ones and the policies, with Azure's default for
     * every top-level section the request leaves out. A section that is sent replaces its default whole, even when it
     * is sent empty: {@code azurerm_iothub} sends {@code "storageEndpoints": {}} for a hub without file upload, and
     * reads a {@code $default} storage endpoint back as a {@code file_upload} block, so filling it in would show drift.
     */
    private static Map<String, Object> properties(Map<String, Object> sent, String sku) {
        Map<String, Object> properties = defaultProperties(sku);
        sent.forEach((key, value) -> {
            if (READ_ONLY_PROPERTIES.contains(key) || "authorizationPolicies".equals(key) || value == null) {
                return;
            }
            properties.put(key, "eventHubEndpoints".equals(key) ? writableEventHubEndpoints(ArmJson.cast(value), sku)
                    : value);
        });
        return properties;
    }

    /** The properties of a hub created with none, as the az CLI's recordings show Azure answering them. */
    private static Map<String, Object> defaultProperties(String sku) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("ipFilterRules", List.of());
        properties.put("eventHubEndpoints", Map.of(EVENTS_ENDPOINT, defaultEventHubEndpoint(sku)));
        properties.put("routing", defaultRouting());
        properties.put("storageEndpoints", Map.of("$default", Map.of(
                "sasTtlAsIso8601", "PT1H", "connectionString", "", "containerName", "")));
        properties.put("messagingEndpoints", Map.of("fileNotifications", Map.of(
                "lockDurationAsIso8601", "PT1M", "ttlAsIso8601", "PT1H", "maxDeliveryCount", 10)));
        properties.put("enableFileUploadNotifications", false);
        properties.put("cloudToDevice", Map.of("maxDeliveryCount", 10, "defaultTtlAsIso8601", "PT1H",
                "feedback", Map.of("lockDurationAsIso8601", "PT1M", "ttlAsIso8601", "PT1H", "maxDeliveryCount", 10)));
        properties.put("features", "None");
        properties.put("allowedFqdnList", List.of());
        return properties;
    }

    /** The sent endpoints without their read-only fields, each with the defaults it leaves out. */
    private static Map<String, Object> writableEventHubEndpoints(Map<String, Object> sent, String sku) {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        sent.forEach((name, value) -> {
            Map<String, Object> endpoint = defaultEventHubEndpoint(sku);
            ArmJson.cast(value).forEach((key, field) -> {
                if (!READ_ONLY_EVENT_HUB.contains(key) && field != null) {
                    endpoint.put(key, field);
                }
            });
            endpoints.put(name, endpoint);
        });
        endpoints.putIfAbsent(EVENTS_ENDPOINT, defaultEventHubEndpoint(sku));
        return endpoints;
    }

    private static Map<String, Object> defaultEventHubEndpoint(String sku) {
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("retentionTimeInDays", DEFAULT_RETENTION_DAYS);
        endpoint.put("partitionCount", FREE_SKU.equals(sku) ? MIN_PARTITIONS : DEFAULT_PARTITIONS);
        return endpoint;
    }

    /** The routing a hub has before any is configured: no custom endpoints or routes, and the fallback route. */
    private static Map<String, Object> defaultRouting() {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("serviceBusQueues", List.of());
        endpoints.put("serviceBusTopics", List.of());
        endpoints.put("eventHubs", List.of());
        endpoints.put("storageContainers", List.of());
        endpoints.put("cosmosDBSqlContainers", List.of());
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("name", "$fallback");
        fallback.put("source", "DeviceMessages");
        fallback.put("condition", "true");
        fallback.put("endpointNames", List.of(EVENTS_ENDPOINT));
        fallback.put("isEnabled", true);
        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("endpoints", endpoints);
        routing.put("routes", List.of());
        routing.put("fallbackRoute", fallback);
        return routing;
    }

    /**
     * Refuses what Azure refused in the recordings, with its codes: a partition count outside what the SKU allows
     * ({@code 400016}) and a TLS version it does not know ({@code 400089}).
     */
    private static Optional<Response> validateProperties(Map<String, Object> sent, String sku) {
        Object tls = sent.get("minTlsVersion");
        if (tls != null && !TLS_VERSIONS.contains(String.valueOf(tls))) {
            return Optional.of(iotError(400, 400089, tls + " is not a valid MinTlsVersion value."));
        }
        Map<String, Object> events = ArmJson.cast(ArmJson.cast(sent.get("eventHubEndpoints")).get(EVENTS_ENDPOINT));
        // The partition count's range is checked under the store's lock (refusePartitionCount), against the hub.
        Object retention = events.get("retentionTimeInDays");
        if (retention != null) {
            long maxDays = FREE_SKU.equals(sku) ? 1 : MAX_RETENTION_DAYS;
            if (!(retention instanceof Integer || retention instanceof Long)
                    || ((Number) retention).longValue() < 1 || ((Number) retention).longValue() > maxDays) {
                return Optional.of(invalid("The 'retentionTimeInDays' of the events endpoint must be a whole number"
                        + " of days from 1 to " + maxDays + " on the " + sku + " SKU; the request sends "
                        + retention + "."));
            }
        }
        return Optional.empty();
    }

    /**
     * Keeps the stored hub's partition count: Azure fixes it at creation. A request that leaves it out gets the
     * stored one rather than the default, and one that sends another is refused.
     */
    /**
     * Refuses a partition count outside the SKU's range, with Azure's error. An existing hub's own count passes
     * whatever the range: the count cannot change after creation, and a release of floci-az before the limit of
     * {@value #MAX_PARTITIONS} stored hubs with up to 128, which a read-modify-write update sends back.
     */
    private static void refusePartitionCount(Map<String, Object> sent, String sku, Optional<IotHubResource> existing) {
        Object partitions = ArmJson.cast(ArmJson.cast(sent.get("eventHubEndpoints")).get(EVENTS_ENDPOINT))
                .get("partitionCount");
        if (partitions == null) {
            return;
        }
        boolean storedCount = partitions instanceof Integer count
                && existing.flatMap(IotHubArmService::storedPartitionCount).filter(count::equals).isPresent();
        int max = FREE_SKU.equals(sku) ? MIN_PARTITIONS : MAX_PARTITIONS;
        if (!storedCount
                && (!(partitions instanceof Integer count) || count < MIN_PARTITIONS || count > max)) {
            throw new Refused(iotError(400, 400016, "Invalid PartitionCount " + partitions
                    + " - value must be between " + MIN_PARTITIONS + " and " + max + "."));
        }
    }

    private static Optional<Integer> storedPartitionCount(IotHubResource hub) {
        Object stored = ArmJson.cast(ArmJson.cast(hub.properties().get("eventHubEndpoints")).get(EVENTS_ENDPOINT))
                .get("partitionCount");
        return stored instanceof Number count ? Optional.of(count.intValue()) : Optional.empty();
    }

    private static void keepPartitionCount(IotHubResource hub, Map<String, Object> sent,
                                           Map<String, Object> properties) {
        Optional<Integer> stored = storedPartitionCount(hub);
        if (stored.isEmpty()) {
            return;
        }
        int count = stored.get();
        Object requested = ArmJson.cast(ArmJson.cast(sent.get("eventHubEndpoints")).get(EVENTS_ENDPOINT))
                .get("partitionCount");
        if (requested instanceof Number asked && asked.intValue() != count) {
            throw new Refused(invalid("The partition count of IoT hub '" + hub.name() + "' is " + count
                    + " and cannot be changed after the hub is created; the request sends " + asked + "."));
        }
        Map<String, Object> endpoints = new LinkedHashMap<>(ArmJson.cast(properties.get("eventHubEndpoints")));
        Map<String, Object> events = new LinkedHashMap<>(ArmJson.cast(endpoints.get(EVENTS_ENDPOINT)));
        events.put("partitionCount", count);
        endpoints.put(EVENTS_ENDPOINT, events);
        properties.put("eventHubEndpoints", endpoints);
    }

    // ── Policies ─────────────────────────────────────────────────────────────

    private List<AccessPolicy> defaultPolicies() {
        return DEFAULT_POLICIES.stream()
                .map(policy -> new AccessPolicy(policy.getKey(), newKey(), newKey(), policy.getValue()))
                .toList();
    }

    /**
     * Refuses an {@code authorizationPolicies} that is present but not a list of objects. Only a missing or null one
     * means "keep the policies"; anything else would otherwise be read as that and leave the old keys in place.
     */
    private static Optional<Response> malformedPolicies(Object sent) {
        if (sent == null) {
            return Optional.empty();
        }
        if (!(sent instanceof List<?> list)) {
            return Optional.of(invalid("The 'authorizationPolicies' of an IoT hub must be a list of shared access"
                    + " policies."));
        }
        if (list.stream().anyMatch(entry -> !(entry instanceof Map<?, ?>))) {
            return Optional.of(invalid("Every entry of 'authorizationPolicies' must be a shared access policy"
                    + " object."));
        }
        return Optional.empty();
    }

    /** The policies the request sends, when it sends any: empty when it leaves {@code authorizationPolicies} out. */
    private static Optional<List<Map<String, Object>>> sentPolicies(Map<String, Object> sent) {
        if (!(sent.get("authorizationPolicies") instanceof List<?> list)) {
            return Optional.empty();
        }
        List<Map<String, Object>> policies = new ArrayList<>();
        for (Object entry : list) {
            policies.add(ArmJson.cast(entry));
        }
        return Optional.of(policies);
    }

    /** Refuses a policy without a name, with a repeated name, or with rights outside {@code AccessRights}. */
    private static Optional<Response> validatePolicies(List<Map<String, Object>> policies) {
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> policy : policies) {
            String keyName = ArmJson.string(policy, "keyName", "");
            if (keyName.isBlank()) {
                return Optional.of(invalid("Every shared access policy needs a 'keyName'."));
            }
            if (!seen.add(keyName.toLowerCase(Locale.ROOT))) {
                return Optional.of(invalid("The shared access policy '" + keyName + "' is listed twice."));
            }
            if (accessRights(policy.get("rights")).isEmpty()) {
                return Optional.of(invalid("The rights '" + policy.get("rights") + "' of shared access policy '"
                        + keyName + "' are invalid: they must be one of " + String.join("; ", ACCESS_RIGHTS) + "."));
            }
        }
        return Optional.empty();
    }

    /** The sent policies, each keeping a key it carries and getting a new one for each key it leaves out. */
    private List<AccessPolicy> policiesFrom(List<Map<String, Object>> sent) {
        return sent.stream()
                .map(policy -> new AccessPolicy(ArmJson.string(policy, "keyName", ""),
                        keyOrNew(policy.get("primaryKey")), keyOrNew(policy.get("secondaryKey")),
                        accessRights(policy.get("rights")).orElseThrow()))
                .toList();
    }

    private String keyOrNew(Object sent) {
        return sent instanceof String key && !key.isBlank() ? key : newKey();
    }

    /** The spec's spelling of an {@code AccessRights} value, matched ignoring case and spacing. */
    private static Optional<String> accessRights(Object sent) {
        if (!(sent instanceof String text)) {
            return Optional.empty();
        }
        String compact = text.replace(" ", "");
        return ACCESS_RIGHTS.stream().filter(known -> known.replace(" ", "").equalsIgnoreCase(compact)).findFirst();
    }

    /** A shared access key as Azure issues one. */
    private String newKey() {
        return IotHubKeys.newKey(random);
    }

    // ── Built-in endpoint names ──────────────────────────────────────────────

    /**
     * The built-in endpoint's Event Hub name, shaped as Azure names it: the start of the hub name and six random
     * characters ({@code iot-hub-for-test-11ko7dsb} for {@code iot-hub-for-test-11000003}).
     */
    private String newEventHubPath(String hubName) {
        String lower = hubName.toLowerCase(Locale.ROOT);
        return lower.substring(0, Math.min(lower.length(), 19)) + randomChars(LOWER_ALNUM, 6);
    }

    /**
     * The built-in endpoint's namespace label, shaped as Azure names it:
     * {@code iothub-ns-iot-hub-fo-60086083-d7251237e7}.
     */
    private String newEventHubNamespace(String hubName) {
        String lower = hubName.toLowerCase(Locale.ROOT);
        String start = lower.substring(0, Math.min(lower.length(), 10));
        return "iothub-ns-" + start + "-" + randomChars("0123456789", 8) + "-" + randomChars(HEX, 10);
    }

    private String randomChars(String alphabet, int count) {
        StringBuilder chars = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            chars.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return chars.toString();
    }

    // ── Request reading ──────────────────────────────────────────────────────

    /** The SKU name in the spec's casing, empty when the body has none or one outside {@code IotHubSku}. */
    private static Optional<String> skuName(Map<String, Object> body) {
        Object name = ArmJson.cast(body.get("sku")).get("name");
        if (!(name instanceof String text)) {
            return Optional.empty();
        }
        String upper = text.toUpperCase(Locale.ROOT);
        return SKU_TIERS.containsKey(upper) ? Optional.of(upper) : Optional.empty();
    }

    /** The SKU capacity, 1 when the body leaves it out, empty when it is not a whole number of at least 1. */
    private static Optional<Integer> capacity(Map<String, Object> body) {
        Object capacity = ArmJson.cast(body.get("sku")).get("capacity");
        if (capacity == null) {
            return Optional.of(1);
        }
        if (capacity instanceof Integer units && units >= 1) {
            return Optional.of(units);
        }
        if (capacity instanceof Long units && units >= 1 && units <= Integer.MAX_VALUE) {
            return Optional.of(units.intValue());
        }
        return Optional.empty();
    }

    /**
     * The identity as Azure answers it, built from the request's {@code type} and identity ids alone: every id in it
     * is read-only. A system-assigned identity keeps its principal across updates; a user-assigned one gets its ids
     * from the Managed Identity store, or deterministic ones when that service is disabled. {@code None} is stored as
     * no identity and answered as {@code {"type": "None"}}.
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
                .orElseGet(() -> TokenIssuer.deterministicGuid("iothub-uai-principal:" + seed)));
        ids.put("clientId", stored.map(UserAssignedIdentity::clientId)
                .orElseGet(() -> TokenIssuer.deterministicGuid("iothub-uai-client:" + seed)));
        return ids;
    }

    /**
     * Refuses an {@code identity} that is not an object, or whose {@code type} is not a {@code ResourceIdentityType}.
     * Only a missing identity or type means {@code None}: a misspelt type would otherwise remove the hub's identity
     * and its principal with it.
     */
    private static Optional<Response> malformedIdentity(Object sent) {
        if (sent == null) {
            return Optional.empty();
        }
        if (!(sent instanceof Map<?, ?>)) {
            return Optional.of(invalid("The 'identity' of an IoT hub must be an object."));
        }
        Object type = ArmJson.cast(sent).get("type");
        if (type != null && identityType(type).isEmpty()) {
            return Optional.of(invalid("The identity type '" + type + "' is invalid: it must be one of "
                    + String.join(", ", IDENTITY_TYPES) + "."));
        }
        return Optional.empty();
    }

    /** The spec's spelling of a {@code ResourceIdentityType}, matched ignoring case and the space after the comma. */
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

    /** The canonical location of the PUT: the one the gate's location rule read, else the body's, else the default. */
    private String location(AzureRequest req, CloudProfile cloud, Map<String, Object> body) {
        if (req.armCall() != null && req.armCall().normalizedLocation() != null) {
            return req.armCall().normalizedLocation();
        }
        String sent = ArmJson.string(body, "location", null);
        return sent == null ? defaults.defaultLocation(req.scope()) : CloudCatalog.normalizeLocation(cloud, sent);
    }

    private static String displayLocation(CloudProfile cloud, String location) {
        return cloud.location(location).map(Location::displayName).orElse(location);
    }

    /** The cloud's DNS suffix for {@code suffix}, or the public cloud's where this cloud has none settled. */
    private static String suffix(CloudProfile cloud, CloudSuffix suffix) {
        return cloud.suffix(suffix)
                .or(() -> CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD).suffix(suffix))
                .orElseThrow();
    }

    private static Optional<ArmScope> scope(AzureRequest req) {
        if (req.scope() == null || req.scope().subscriptionId() == null) {
            return Optional.empty();
        }
        return Optional.of(new ArmScope(req.scope().subscriptionId(), req.scope().resourceGroup()));
    }

    private static CloudProfile cloud(AzureRequest req) {
        return req.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : req.scope().cloud();
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    // ── Refusals made under the store lock ───────────────────────────────────

    /** A write refused by a check that reads the stored hub; thrown from inside a store change, which aborts it. */
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

    private static String ifMatch(AzureRequest req) {
        return req.headers() == null ? null : req.headers().getHeaderString("If-Match");
    }

    /** Refuses a write whose {@code If-Match} names another version of the hub than the stored one. */
    private static void refuseStaleEtag(IotHubResource hub, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
            return;
        }
        String sent = ifMatch.trim();
        if (sent.length() > 1 && sent.startsWith("\"") && sent.endsWith("\"")) {
            sent = sent.substring(1, sent.length() - 1);
        }
        if (!sent.equals(hub.etag())) {
            throw new Refused(ArmErrors.error(412, "PreconditionFailed", "The IoT hub '" + hub.name()
                    + "' was changed since ETag '" + sent + "' was read; its current ETag is '" + hub.etag() + "'."));
        }
    }

    /**
     * Refuses a PUT whose {@code If-Match} names an ETag when there is no hub: the version it was read at is gone, so
     * the write is an update of a deleted hub, not a create. Without this, a delayed update would bring the hub back
     * with new keys. {@code *} and no {@code If-Match} still create, as the az CLI and Terraform create without one.
     */
    private static void refuseETagOfMissingHub(ArmResourceId id, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
            return;
        }
        throw new Refused(ArmErrors.error(412, "PreconditionFailed", "The IoT hub '" + id.name()
                + "' does not exist, so it cannot match ETag '" + ifMatch.trim() + "'."));
    }

    /** Refuses a PUT that moves an existing hub: ARM fixes a resource's location at creation. */
    private static void refuseLocationChange(IotHubResource hub, String location) {
        if (!hub.location().equalsIgnoreCase(location)) {
            throw new Refused(ArmErrors.error(400, "InvalidResourceLocation", "The resource '" + hub.name()
                    + "' already exists in location '" + hub.location() + "' in resource group '"
                    + ArmResourceId.parse(hub.id()).map(ArmResourceId::resourceGroup).orElse("") + "'. A resource"
                    + " with the same name cannot be created in location '" + location + "'. Please select a new"
                    + " resource name."));
        }
    }

    // ── Paths ────────────────────────────────────────────────────────────────

    /** The id of the hub {@code name} the request addresses; empty for a request that names no resource group. */
    private static Optional<ArmResourceId> hubId(AzureRequest req, String name) {
        return scope(req)
                .filter(scope -> scope.resourceGroup() != null)
                .flatMap(scope -> ArmResourceId.parse("/subscriptions/" + scope.subscription() + "/resourceGroups/"
                        + scope.resourceGroup() + "/providers/" + IotHubModels.IOT_HUBS + "/" + name));
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

    /** ARM's own not-found answer for a resource in a resource group. */
    private static Response hubNotFound(ArmResourceId id) {
        return ArmErrors.notFound("The Resource '" + IotHubModels.IOT_HUBS + "/" + id.name()
                + "' under resource group '" + id.resourceGroup()
                + "' was not found. For more details please go to https://aka.ms/ARMResourceNotFoundFix");
    }

    private static Response unknownPath(List<String> tail) {
        return ArmErrors.notFound("Unknown Microsoft.Devices path: " + String.join("/", tail));
    }

    private static Response invalid(String message) {
        return ArmErrors.error(400, "InvalidRequestContent", message);
    }

    /** An error in the IoT Hub {@code ErrorDetails} shape, as the hub itself answers it. */
    private static Response iotError(int status, int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("httpStatusCode", status);
        body.put("message", message);
        return Response.status(status).entity(body).type("application/json").build();
    }

    private static Response methodNotAllowed() {
        return Response.status(405).build();
    }
}
