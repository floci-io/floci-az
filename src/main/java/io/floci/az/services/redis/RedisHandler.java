package io.floci.az.services.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.arm.GlobalNameSource;
import io.floci.az.core.arm.ResourceIndexContributor;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.redis.RedisModels.RedisCache;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * HTTP handler for Azure Cache for Redis ({@code Microsoft.Cache/redis}) management-plane requests.
 *
 * <h2>Routing</h2>
 * <pre>
 *   GET    subscriptions/{sub}/providers/Microsoft.Cache/redis
 *   GET    subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis
 *   GET    subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis/{name}
 *   PUT    subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis/{name}
 *   PATCH  subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis/{name}
 *   DELETE subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis/{name}
 *   POST   subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis/{name}/listKeys
 *   POST   subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Cache/redis/{name}/regenerateKey
 * </pre>
 *
 * <h2>Mocked mode</h2>
 * <p>When {@code floci-az.services.redis.mocked=true} (default), no Redis container is started.
 * Caches transition immediately to "Succeeded" with {@code hostName=localhost}.</p>
 *
 * <h2>Endpoint resolution</h2>
 * <p>{@code hostName} returns the actually-reachable host (localhost, or the container name when
 * floci-az itself runs in Docker), not the real {@code {name}.redis.cache.windows.net} FQDN, so
 * standard Redis clients can connect to the sidecar.</p>
 */
@ApplicationScoped
public class RedisHandler implements AzureServiceHandler, Resettable, ResourceIndexContributor, GlobalNameSource {

    private static final Logger LOG = Logger.getLogger(RedisHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String TYPE = "Microsoft.Cache/Redis";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SSL_PORT = 6380;

    private final EmulatorConfig config;
    private final RedisCacheManager cacheManager;
    private final GlobalNameRegistry names;
    /**
     * Every cache, keyed by its canonical id. Before the store, caches were kept under raw-case
     * {@code sub/rg/name}; those entries are read as they are and move to their canonical key on their next write.
     */
    private final ArmResourceStore<RedisCache> store;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "redis-readiness-poller");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public RedisHandler(EmulatorConfig config,
                        RedisCacheManager cacheManager,
                        StorageFactory storageFactory,
                        GlobalNameRegistry names) {
        this.config = config;
        this.cacheManager = cacheManager;
        this.names = names;
        this.store = ArmResourceStore.create(storageFactory, "redis", RedisCache.class, RedisCache::armId,
                key -> true);
    }

    @PostConstruct
    public void init() {
        dropShadowedCaches();
        if (!config.services().redis().mocked()) {
            startReadinessPoller();
        }
    }

    /**
     * Old raw-case keys could hold one cache under several casings, each with a container of its own (named from the
     * record's {@code instanceId}). The store serves one of them; the others are removed here, with their containers,
     * before anything could drop them and leave the containers running.
     */
    void dropShadowedCaches() {
        for (ArmResourceStore.Stored<RedisCache> shadowed : store.removeShadowedLegacy()) {
            RedisCache cache = shadowed.value();
            LOG.infov("Redis cache {0}: dropping the duplicate stored under {1}/{2}", cache.getName(),
                    cache.getSubscriptionId(), cache.getResourceGroup());
            if (!config.services().redis().mocked()) {
                try {
                    cacheManager.stopCache(cache);
                } catch (Exception e) {
                    LOG.warnv("Could not remove the container of duplicate Redis cache {0}: {1}. Remove container {2}"
                            + " (id {3}) by hand", cache.getName(), e.getMessage(), cacheManager.containerName(cache),
                            cache.getContainerId());
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (!config.services().redis().mocked()) {
            scanAll().forEach(cache -> {
                try {
                    cacheManager.stopCache(cache);
                } catch (Exception e) {
                    LOG.warnv("Error stopping Redis cache {0}: {1}", cache.getName(), e.getMessage());
                }
            });
        }
    }

    @Override
    public String getServiceType() { return "redis"; }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().redis().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .provider("Microsoft.Cache")
                .build();
    }

    @Override
    public Response handle(AzureRequest req) {
        String fullPath = req.resourcePath();
        String method = req.method();

        LOG.debugf("RedisHandler: %s %s", method, fullPath);

        String tail = extractRedisPath(fullPath);

        // ── Name availability (Redis_CheckNameAvailability) ──────────────────
        if (tail.equalsIgnoreCase("checkNameAvailability") && "POST".equals(method)) {
            return handleCheckNameAvailability(req);
        }

        // ── LIST all caches in subscription ────────────────────────────────
        if (tail.equalsIgnoreCase("redis") && !fullPath.contains("/resourceGroups/")) {
            return handleListSubscription(fullPath);
        }

        // ── LIST caches in resource group ──────────────────────────────────
        if (tail.equalsIgnoreCase("redis") && "GET".equals(method)) {
            return handleListByResourceGroup(fullPath);
        }

        // ── Key actions ────────────────────────────────────────────────────
        if (tail.matches("(?i)redis/[^/]+/listKeys") && "POST".equals(method)) {
            return handleListKeys(extractSubscriptionId(fullPath), extractResourceGroup(fullPath),
                    segment(tail, 1));
        }
        if (tail.matches("(?i)redis/[^/]+/regenerateKey") && "POST".equals(method)) {
            return handleRegenerateKey(extractSubscriptionId(fullPath), extractResourceGroup(fullPath),
                    segment(tail, 1), req);
        }

        // ── Single cache CRUD ──────────────────────────────────────────────
        // ARM resource types are case-insensitive, and the id this handler echoes spells the type "Redis".
        if (tail.matches("(?i)redis/[^/]+")) {
            String cacheName = segment(tail, 1);
            String sub = extractSubscriptionId(fullPath);
            String rg = extractResourceGroup(fullPath);
            return switch (method) {
                case "GET"    -> handleGet(sub, rg, cacheName);
                case "PUT"    -> handleCreateOrUpdate(sub, rg, cacheName, req);
                case "PATCH"  -> handlePatch(sub, rg, cacheName, req);
                case "DELETE" -> handleDelete(sub, rg, cacheName);
                default       -> methodNotAllowed();
            };
        }

        return notFound("Unknown Redis path: " + tail);
    }

    // ── CRUD operations ──────────────────────────────────────────────────────────

    private Response handleCreateOrUpdate(String sub, String rg, String cacheName, AzureRequest req) {
        try {
            JsonNode body = readBody(req.bodyStream());
            String location = body.path("location").asText("eastus");
            JsonNode props = body.path("properties");
            JsonNode sku = props.path("sku");

            // The name is reserved in the GlobalNameRegistry by the gate's GlobalNameRule before this runs, so a
            // name another subscription owns never reaches here. The read and the first write are one store
            // operation, so two concurrent creates of one cache keep the first one's instance and keys.
            ArmResourceId id = cacheId(sub, rg, cacheName);
            boolean mocked = config.services().redis().mocked();
            ArmResourceStore.Written<RedisCache> written = store.createOrUpdate(id, existing -> {
                RedisCache current = existing.orElseGet(() -> newCache(sub, rg, cacheName));
                current.setLocation(location);
                current.setSkuName(sku.path("name").asText("Basic"));
                current.setSkuFamily(sku.path("family").asText("C"));
                current.setSkuCapacity(sku.path("capacity").asInt(0));
                current.setRedisVersion("7.0");
                // With TLS served, absent means false, the spec default; without it, true, because
                // the plain port is then the only one served. The plain port is served either way.
                current.setEnableNonSslPort(props.path("enableNonSslPort")
                        .asBoolean(!config.services().redis().tlsEnabled()));
                current.setMinimumTlsVersion(props.path("minimumTlsVersion").asText("1.2"));
                current.setRedisConfiguration(parseStringMap(props.path("redisConfiguration")));
                current.setTags(parseStringMap(body.path("tags")));
                if (mocked) {
                    current.setProvisioningState("Succeeded");
                    current.setHostName("localhost");
                    current.setPort(6379);
                } else if (existing.isEmpty()) {
                    current.setProvisioningState("Creating");
                }
                return current;
            });
            boolean isNew = written.created();
            RedisCache cache = written.stored().value();

            if (!mocked && isNew) {
                try {
                    cacheManager.startCache(cache);
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to start Redis container for cache %s", cacheName);
                    cache.setProvisioningState("Failed");
                }
                // A DELETE may have removed the cache while the container started; writing the record
                // back would resurrect a name another subscription may have claimed since.
                RedisCache started = cache;
                Optional<RedisCache> stored = updateIfSameInstance(started, current -> {
                    current.setContainerId(started.getContainerId());
                    current.setHostName(started.getHostName());
                    current.setPort(started.getPort());
                    current.setSslPort(started.getSslPort());
                    current.setInternalEndpoint(started.getInternalEndpoint());
                    if ("Failed".equals(started.getProvisioningState())) {
                        current.setProvisioningState("Failed");
                    }
                });
                if (stored.isPresent()) {
                    cache = stored.get();
                } else {
                    stopQuietly(started);
                }
            }

            int status = isNew ? 201 : 200;
            return Response.status(status)
                    .entity(toArmResponse(cache))
                    .type("application/json")
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Error creating/updating Redis cache %s", cacheName);
            return badRequest("Invalid request: " + e.getMessage());
        }
    }

    private Response handleGet(String sub, String rg, String cacheName) {
        return getCache(cacheId(sub, rg, cacheName))
                .map(c -> Response.ok(toArmResponse(c)).type("application/json").build())
                .orElseGet(() -> notFound("Redis cache '" + cacheName + "' not found."));
    }

    private Response handlePatch(String sub, String rg, String cacheName, AzureRequest req) {
        Optional<RedisCache> found = getCache(cacheId(sub, rg, cacheName));
        if (found.isEmpty()) {
            return notFound("Redis cache '" + cacheName + "' not found.");
        }
        try {
            JsonNode body = readBody(req.bodyStream());
            RedisCache cache = found.get();
            if (body.has("tags")) {
                cache.setTags(parseStringMap(body.path("tags")));
            }
            JsonNode props = body.path("properties");
            if (props.has("redisConfiguration")) {
                cache.setRedisConfiguration(parseStringMap(props.path("redisConfiguration")));
            }
            if (props.has("minimumTlsVersion")) {
                cache.setMinimumTlsVersion(props.path("minimumTlsVersion").asText());
            }
            if (props.has("enableNonSslPort")) {
                cache.setEnableNonSslPort(props.path("enableNonSslPort").asBoolean());
            }
            store.put(cache);
            return Response.ok(toArmResponse(cache)).type("application/json").build();
        } catch (Exception e) {
            return badRequest("Invalid request: " + e.getMessage());
        }
    }

    private Response handleDelete(String sub, String rg, String cacheName) {
        ArmResourceId id = cacheId(sub, rg, cacheName);
        Optional<RedisCache> found = getCache(id);
        if (found.isEmpty()) {
            return notFound("Redis cache '" + cacheName + "' not found.");
        }
        if (!config.services().redis().mocked()) {
            stopQuietly(found.get());
        }
        store.delete(id);
        return Response.status(202).build();
    }

    /**
     * {@code Redis_CheckNameAvailability}: {@code 200} with no body when the name is free in the caller's cloud. A
     * taken name is answered with the create conflict, an emulator choice: Azure's answer for it is not recorded.
     */
    private Response handleCheckNameAvailability(AzureRequest req) {
        String name = readBody(req.bodyStream()).path("name").asText("");
        if (names.owner(cloud(req), CloudSuffix.REDIS, name).isEmpty()) {
            return Response.ok().build();
        }
        return ArmErrors.error(RedisArmSurface.CACHE_NAME.conflictStatus(),
                RedisArmSurface.CACHE_NAME.conflictCode(), RedisArmSurface.CACHE_NAME.conflictMessage());
    }

    private Response handleListByResourceGroup(String fullPath) {
        return list(new ArmScope(extractSubscriptionId(fullPath), extractResourceGroup(fullPath)));
    }

    private Response handleListSubscription(String fullPath) {
        return list(new ArmScope(extractSubscriptionId(fullPath), null));
    }

    private Response list(ArmScope scope) {
        List<Map<String, Object>> items = new ArrayList<>();
        store.list(scope).forEach(stored -> items.add(toArmResponse(stored.value())));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    private Response handleListKeys(String sub, String rg, String cacheName) {
        return getCache(cacheId(sub, rg, cacheName))
                .map(c -> Response.ok(accessKeys(c)).type("application/json").build())
                .orElseGet(() -> notFound("Redis cache '" + cacheName + "' not found."));
    }

    private Response handleRegenerateKey(String sub, String rg, String cacheName, AzureRequest req) {
        Optional<RedisCache> found = getCache(cacheId(sub, rg, cacheName));
        if (found.isEmpty()) {
            return notFound("Redis cache '" + cacheName + "' not found.");
        }
        RedisCache cache = found.get();
        // The old primary key still authenticates on the running container, so use it to apply
        // the rotated keys before they take effect.
        String authKey = cache.getPrimaryKey();
        String keyType = readBody(req.bodyStream()).path("keyType").asText("Primary");
        if ("Secondary".equalsIgnoreCase(keyType)) {
            cache.setSecondaryKey(generateAccessKey());
        } else {
            cache.setPrimaryKey(generateAccessKey());
        }
        if (!config.services().redis().mocked()) {
            cacheManager.applyAccessKeys(cache, authKey);
        }
        store.put(cache);
        return Response.ok(accessKeys(cache)).type("application/json").build();
    }

    // ── Readiness poller ─────────────────────────────────────────────────────────

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                scanAll().forEach(cache -> {
                    if ("Creating".equals(cache.getProvisioningState()) && cacheManager.isReady(cache)) {
                        cacheManager.applyAccessKeys(cache, cache.getPrimaryKey());
                        updateIfSameInstance(cache, current -> current.setProvisioningState("Succeeded"))
                                .ifPresent(ready -> LOG.infov("Redis cache {0} is now ready", ready.getName()));
                    }
                });
            } catch (Exception e) {
                LOG.error("Error in Redis readiness poller", e);
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    // ── Storage helpers ──────────────────────────────────────────────────────────

    private static RedisCache newCache(String sub, String rg, String cacheName) {
        RedisCache cache = new RedisCache();
        cache.setInstanceId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        cache.setSubscriptionId(sub);
        cache.setResourceGroup(rg);
        cache.setName(cacheName);
        cache.setCreatedAt(Instant.now());
        cache.setPrimaryKey(generateAccessKey());
        cache.setSecondaryKey(generateAccessKey());
        cache.setSslPort(SSL_PORT);
        return cache;
    }

    private Optional<RedisCache> getCache(ArmResourceId id) {
        return store.get(id).map(ArmResourceStore.Stored::value);
    }

    private static boolean isSameCache(RedisCache stored, RedisCache cache) {
        return cache.getInstanceId().equals(stored.getInstanceId());
    }

    /**
     * Applies {@code change} to the stored copy of {@code cache} while it is still the same instance, and
     * returns the stored result. Changing the stored copy, rather than writing {@code cache} back, keeps any
     * update made since {@code cache} was read. Empty once the cache was deleted, or deleted and created again.
     */
    private Optional<RedisCache> updateIfSameInstance(RedisCache cache, Consumer<RedisCache> change) {
        AtomicBoolean same = new AtomicBoolean();
        Optional<ArmResourceStore.Stored<RedisCache>> stored = store.update(cacheId(cache), current -> {
            if (isSameCache(current, cache)) {
                same.set(true);
                change.accept(current);
            }
            return current;
        });
        return same.get() ? stored.map(ArmResourceStore.Stored::value) : Optional.empty();
    }

    private void stopQuietly(RedisCache cache) {
        try {
            cacheManager.stopCache(cache);
        } catch (Exception e) {
            LOG.warnv("Error stopping Redis container for cache {0}: {1}", cache.getName(), e.getMessage());
        }
    }

    private List<RedisCache> scanAll() {
        return store.all().stream().map(ArmResourceStore.Stored::value).toList();
    }

    private static ArmResourceId cacheId(String sub, String rg, String cacheName) {
        return ArmResourceId.parse(String.format("/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Cache/Redis/%s",
                sub, rg, cacheName)).orElseThrow();
    }

    private static ArmResourceId cacheId(RedisCache cache) {
        return ArmResourceId.parse(cache.armId()).orElseThrow();
    }

    private static CloudProfile cloud(AzureRequest req) {
        return req.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : req.scope().cloud();
    }

    // ── ARM response builders ──────────────────────────────────────────────────────

    private Map<String, Object> toArmResponse(RedisCache cache) {
        Map<String, Object> sku = new LinkedHashMap<>();
        sku.put("name", cache.getSkuName());
        sku.put("family", cache.getSkuFamily());
        sku.put("capacity", cache.getSkuCapacity());

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("provisioningState", cache.getProvisioningState());
        props.put("redisVersion", cache.getRedisVersion());
        props.put("sku", sku);
        props.put("enableNonSslPort", cache.isEnableNonSslPort());
        props.put("minimumTlsVersion", cache.getMinimumTlsVersion());
        props.put("hostName", cache.getHostName());
        props.put("port", cache.getPort());
        props.put("sslPort", cache.getSslPort());
        props.put("publicNetworkAccess", "Enabled");
        if (cache.getRedisConfiguration() != null) {
            props.put("redisConfiguration", cache.getRedisConfiguration());
        }
        props.put("accessKeys", accessKeys(cache));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", cache.armId());
        out.put("name", cache.getName());
        out.put("type", TYPE);
        out.put("location", cache.getLocation());
        if (cache.getTags() != null && !cache.getTags().isEmpty()) {
            out.put("tags", cache.getTags());
        }
        out.put("properties", props);
        return out;
    }

    private static Map<String, Object> accessKeys(RedisCache cache) {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("primaryKey", cache.getPrimaryKey());
        keys.put("secondaryKey", cache.getSecondaryKey());
        return keys;
    }

    private static String generateAccessKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    // ── Path parsing helpers ───────────────────────────────────────────────────────

    private static String extractRedisPath(String fullPath) {
        if (fullPath == null) { return ""; }
        return ArmPaths.afterProvider(fullPath, "Microsoft.Cache", fullPath);
    }

    private static String extractSubscriptionId(String fullPath) {
        return ArmPaths.segmentAfter(fullPath, "subscriptions", "default");
    }

    private static String extractResourceGroup(String fullPath) {
        return ArmPaths.resourceGroup(fullPath, "default");
    }

    private static String segment(String path, int index) {
        String[] parts = path.split("/");
        return index < parts.length ? parts[index] : "";
    }

    private static Map<String, String> parseStringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        }
        return map;
    }

    private JsonNode readBody(InputStream stream) {
        try {
            if (stream == null || stream.available() == 0) { return MAPPER.createObjectNode(); }
            return MAPPER.readTree(stream);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    // ── Standard error responses ─────────────────────────────────────────────────

    private static Response notFound(String message) {
        return ArmErrors.notFound(message);
    }

    private static Response badRequest(String message) {
        return ArmErrors.error(400, "InvalidRequest", message);
    }

    private static Response methodNotAllowed() {
        return Response.status(405).entity(Map.of("error", "Method not allowed"))
                .type("application/json").build();
    }

    /** Wipes all Redis data — used by {@code POST /_admin/reset}. */
    public void clear() {
        store.clear();
    }

    // ── ResourceIndexContributor ────────────────────────────────────────────────

    @Override
    public boolean indexEnabled() {
        return config.services().redis().enabled();
    }

    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        return indexEntries(new ArmScope(sub, rg));
    }

    @Override
    public List<Map<String, Object>> listSubscriptionResources(String sub) {
        return indexEntries(new ArmScope(sub, null));
    }

    private List<Map<String, Object>> indexEntries(ArmScope scope) {
        return store.list(scope).stream()
                .map(ArmResourceStore.Stored::value)
                .map(cache -> ArmResources.indexEntry(cache.armId(), cache.getName(), TYPE,
                        cache.getLocation(), cache.getTags()))
                .toList();
    }

    // ── GlobalNameSource ───────────────────────────────────────────────────────

    @Override
    public List<Claim> globalNames() {
        return store.all().stream()
                .map(stored -> new Claim(CloudSuffix.REDIS, stored.value().getName(), stored.id()))
                .toList();
    }
}
