package io.floci.az.services.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.redis.RedisModels.RedisCache;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmPaths;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

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
public class RedisHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(RedisHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SSL_PORT = 6380;

    private final EmulatorConfig config;
    private final RedisCacheManager cacheManager;
    private final StorageBackend<String, StoredObject> storage;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "redis-readiness-poller");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public RedisHandler(EmulatorConfig config,
                        RedisCacheManager cacheManager,
                        StorageFactory storageFactory) {
        this.config = config;
        this.cacheManager = cacheManager;
        this.storage = storageFactory.create("redis");
    }

    @PostConstruct
    public void init() {
        if (!config.services().redis().mocked()) {
            startReadinessPoller();
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
    public boolean canHandle(AzureRequest req) { return "redis".equals(req.serviceType()); }

    @Override
    public Response handle(AzureRequest req) {
        String fullPath = req.resourcePath();
        String method = req.method();

        LOG.debugf("RedisHandler: %s %s", method, fullPath);

        String tail = extractRedisPath(fullPath);

        // ── LIST all caches in subscription ────────────────────────────────
        if (tail.equalsIgnoreCase("redis") && !fullPath.contains("/resourceGroups/")) {
            return handleListSubscription(fullPath);
        }

        // ── LIST caches in resource group ──────────────────────────────────
        if (tail.equalsIgnoreCase("redis") && "GET".equals(method)) {
            return handleListByResourceGroup(fullPath);
        }

        // ── Key actions ────────────────────────────────────────────────────
        if (tail.matches("redis/[^/]+/listKeys") && "POST".equals(method)) {
            return handleListKeys(extractSubscriptionId(fullPath), extractResourceGroup(fullPath),
                    segment(tail, 1));
        }
        if (tail.matches("redis/[^/]+/regenerateKey") && "POST".equals(method)) {
            return handleRegenerateKey(extractSubscriptionId(fullPath), extractResourceGroup(fullPath),
                    segment(tail, 1), req);
        }

        // ── Single cache CRUD ──────────────────────────────────────────────
        if (tail.matches("redis/[^/]+")) {
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

            String storageKey = storageKey(sub, rg, cacheName);
            Optional<RedisCache> existing = getCache(storageKey);
            boolean isNew = existing.isEmpty();

            RedisCache cache;
            if (isNew) {
                cache = new RedisCache();
                cache.setInstanceId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
                cache.setSubscriptionId(sub);
                cache.setResourceGroup(rg);
                cache.setName(cacheName);
                cache.setCreatedAt(Instant.now());
                cache.setPrimaryKey(generateAccessKey());
                cache.setSecondaryKey(generateAccessKey());
                cache.setSslPort(SSL_PORT);
            } else {
                cache = existing.get();
            }

            cache.setLocation(location);
            cache.setSkuName(sku.path("name").asText("Basic"));
            cache.setSkuFamily(sku.path("family").asText("C"));
            cache.setSkuCapacity(sku.path("capacity").asInt(0));
            cache.setRedisVersion("7.0");
            cache.setEnableNonSslPort(props.path("enableNonSslPort").asBoolean(true));
            cache.setMinimumTlsVersion(props.path("minimumTlsVersion").asText("1.2"));
            cache.setRedisConfiguration(parseStringMap(props.path("redisConfiguration")));
            cache.setTags(parseStringMap(body.path("tags")));

            if (config.services().redis().mocked()) {
                cache.setProvisioningState("Succeeded");
                cache.setHostName("localhost");
                cache.setPort(6379);
            } else if (isNew) {
                cache.setProvisioningState("Creating");
                try {
                    cacheManager.startCache(cache);
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to start Redis container for cache %s", cacheName);
                    cache.setProvisioningState("Failed");
                }
            }

            putCache(storageKey, cache);

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
        return getCache(storageKey(sub, rg, cacheName))
                .map(c -> Response.ok(toArmResponse(c)).type("application/json").build())
                .orElseGet(() -> notFound("Redis cache '" + cacheName + "' not found."));
    }

    private Response handlePatch(String sub, String rg, String cacheName, AzureRequest req) {
        String key = storageKey(sub, rg, cacheName);
        Optional<RedisCache> found = getCache(key);
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
            putCache(key, cache);
            return Response.ok(toArmResponse(cache)).type("application/json").build();
        } catch (Exception e) {
            return badRequest("Invalid request: " + e.getMessage());
        }
    }

    private Response handleDelete(String sub, String rg, String cacheName) {
        String key = storageKey(sub, rg, cacheName);
        Optional<RedisCache> found = getCache(key);
        if (found.isEmpty()) {
            return notFound("Redis cache '" + cacheName + "' not found.");
        }
        if (!config.services().redis().mocked()) {
            try {
                cacheManager.stopCache(found.get());
            } catch (Exception e) {
                LOG.warnv("Error stopping Redis container for cache {0}: {1}", cacheName, e.getMessage());
            }
        }
        storage.delete(key);
        return Response.status(202).build();
    }

    private Response handleListByResourceGroup(String fullPath) {
        String sub = extractSubscriptionId(fullPath);
        String rg = extractResourceGroup(fullPath);
        String prefix = sub + "/" + rg.toLowerCase() + "/";
        List<Map<String, Object>> items = new ArrayList<>();
        scanAll().stream()
                .filter(c -> c.storageKey().toLowerCase().startsWith(prefix))
                .forEach(c -> items.add(toArmResponse(c)));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    private Response handleListSubscription(String fullPath) {
        String sub = extractSubscriptionId(fullPath);
        String prefix = sub + "/";
        List<Map<String, Object>> items = new ArrayList<>();
        scanAll().stream()
                .filter(c -> c.storageKey().startsWith(prefix))
                .forEach(c -> items.add(toArmResponse(c)));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    private Response handleListKeys(String sub, String rg, String cacheName) {
        return getCache(storageKey(sub, rg, cacheName))
                .map(c -> Response.ok(accessKeys(c)).type("application/json").build())
                .orElseGet(() -> notFound("Redis cache '" + cacheName + "' not found."));
    }

    private Response handleRegenerateKey(String sub, String rg, String cacheName, AzureRequest req) {
        String key = storageKey(sub, rg, cacheName);
        Optional<RedisCache> found = getCache(key);
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
        putCache(key, cache);
        return Response.ok(accessKeys(cache)).type("application/json").build();
    }

    // ── Readiness poller ─────────────────────────────────────────────────────────

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                scanAll().forEach(cache -> {
                    if ("Creating".equals(cache.getProvisioningState()) && cacheManager.isReady(cache)) {
                        LOG.infov("Redis cache {0} is now ready", cache.getName());
                        cacheManager.applyAccessKeys(cache, cache.getPrimaryKey());
                        cache.setProvisioningState("Succeeded");
                        putCache(cache.storageKey(), cache);
                    }
                });
            } catch (Exception e) {
                LOG.error("Error in Redis readiness poller", e);
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    // ── Storage helpers ──────────────────────────────────────────────────────────

    private Optional<RedisCache> getCache(String key) {
        return storage.get(key).map(so -> {
            try {
                return MAPPER.readValue(so.data(), RedisCache.class);
            } catch (Exception e) {
                LOG.warnv("Failed to deserialize Redis cache {0}: {1}", key, e.getMessage());
                return null;
            }
        });
    }

    private void putCache(String key, RedisCache cache) {
        try {
            byte[] data = MAPPER.writeValueAsBytes(cache);
            storage.put(key, new StoredObject(key, data, Map.of(), Instant.now(), key));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Redis cache: " + key, e);
        }
    }

    private List<RedisCache> scanAll() {
        List<RedisCache> result = new ArrayList<>();
        storage.scan(k -> true).forEach(so -> {
            try {
                RedisCache c = MAPPER.readValue(so.data(), RedisCache.class);
                if (c != null) { result.add(c); }
            } catch (Exception e) {
                LOG.debugv("Skipping unreadable Redis cache entry: {0}", e.getMessage());
            }
        });
        return result;
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
        out.put("type", "Microsoft.Cache/Redis");
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
        int idx = fullPath.indexOf("/providers/Microsoft.Cache/");
        if (idx >= 0) {
            return fullPath.substring(idx + "/providers/Microsoft.Cache/".length());
        }
        return fullPath;
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

    private static String storageKey(String sub, String rg, String cacheName) {
        return sub + "/" + rg + "/" + cacheName;
    }

    private static Map<String, String> parseStringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        }
        return map;
    }

    private JsonNode readBody(java.io.InputStream stream) {
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
    public void clearAll() {
        storage.clear();
    }
}
