package io.floci.az.services.acr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.Resettable;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.acr.AcrModels.Registry;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * HTTP handler for Azure Container Registry ({@code Microsoft.ContainerRegistry/registries})
 * management-plane requests.
 *
 * <h2>Routing</h2>
 * <pre>
 *   GET    subscriptions/{sub}/providers/Microsoft.ContainerRegistry/registries
 *   GET    subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerRegistry/registries
 *   GET    .../registries/{name}
 *   PUT    .../registries/{name}
 *   PATCH  .../registries/{name}
 *   DELETE .../registries/{name}
 *   POST   .../registries/{name}/listCredentials
 *   POST   .../registries/{name}/regenerateCredential
 *   GET    .../registries/{name}/listUsages
 *   POST   .../registries/{name}/importImage              (stub, 202)
 *   POST   subscriptions/{sub}/providers/Microsoft.ContainerRegistry/checkNameAvailability
 * </pre>
 *
 * <h2>Data plane</h2>
 * <pre>
 *   GET  {name}.azurecr.io/v2/                      challenge
 *   POST {name}.azurecr.io/oauth2/exchange          Entra access token  -> ACR refresh token
 *   *    {name}.azurecr.io/oauth2/token             ACR refresh token   -> scoped access token
 *   *    {name}.azurecr.io/v2/**                    Docker Registry HTTP API V2
 * </pre>
 *
 * <p>{@code loginServer} is {@code {name}.azurecr.io}, exactly as Azure reports it, and the host
 * suffix routes the data plane here. One shared {@code registry:2} sidecar backs every registry, so
 * the registry name becomes an internal repository prefix when {@link AcrRegistryProxy} forwards
 * {@code /v2/} requests to it; the container's own published port keeps serving the same storage
 * anonymously over plain HTTP.</p>
 *
 * <p>The auth surface works in either mode. In {@code mocked} mode no sidecar is started, so
 * repository operations answer with the registry's {@code UNSUPPORTED} error. A host naming a
 * registry that was never created answers {@code NAME_UNKNOWN} at every endpoint: the caller's
 * resolver points the whole {@code .azurecr.io} space here, but only created registries exist.</p>
 */
@ApplicationScoped
public class AcrHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(AcrHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALNUM =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String PROVIDER = "/providers/Microsoft.ContainerRegistry/";

    /** The Azure host suffix the registry data plane is served on. */
    public static final String REGISTRY_HOST_SUFFIX = ".azurecr.io";

    private final EmulatorConfig config;
    private final AcrRegistryManager registryManager;
    private final AcrTokenService tokenService;
    private final AcrRegistryProxy registryProxy;
    private final StorageBackend<String, StoredObject> storage;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "acr-readiness-poller");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public AcrHandler(EmulatorConfig config,
                      AcrRegistryManager registryManager,
                      AcrTokenService tokenService,
                      AcrRegistryProxy registryProxy,
                      StorageFactory storageFactory) {
        this.config = config;
        this.registryManager = registryManager;
        this.tokenService = tokenService;
        this.registryProxy = registryProxy;
        this.storage = storageFactory.create("acr");
    }

    @PostConstruct
    public void init() {
        if (!config.services().acr().mocked()) {
            startReadinessPoller();
        }
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (!config.services().acr().mocked()) {
            registryManager.shutdown();
        }
    }

    @Override
    public String getServiceType() { return "acr"; }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().acr().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .host(REGISTRY_HOST_SUFFIX)
                .provider("Microsoft.ContainerRegistry")
                .build();
    }

    @Override
    public boolean canHandle(AzureRequest req) { return "acr".equals(req.serviceType()); }

    @Override
    public Response handle(AzureRequest req) {
        String fullPath = req.resourcePath();
        String method = req.method();

        LOG.debugf("AcrHandler: %s %s", method, fullPath);

        String registryHost = registryFromHost(req.host());
        if (registryHost != null) {
            return handleDataPlane(req, registryHost);
        }

        String tail = extractAcrPath(fullPath);

        // ── checkNameAvailability (provider root) ──────────────────────────
        if (tail.equalsIgnoreCase("checkNameAvailability") && "POST".equals(method)) {
            return handleCheckNameAvailability(req);
        }

        // ── LIST all registries in subscription ────────────────────────────
        if (tail.equalsIgnoreCase("registries") && !fullPath.contains("/resourceGroups/")) {
            return handleListSubscription(fullPath);
        }

        // ── LIST registries in resource group ──────────────────────────────
        if (tail.equalsIgnoreCase("registries") && "GET".equals(method)) {
            return handleListByResourceGroup(fullPath);
        }

        // ── Credential actions ─────────────────────────────────────────────
        if (tail.matches("registries/[^/]+/listCredentials") && "POST".equals(method)) {
            return handleListCredentials(extractSubscriptionId(fullPath), extractResourceGroup(fullPath),
                    segment(tail, 1));
        }
        if (tail.matches("registries/[^/]+/regenerateCredential") && "POST".equals(method)) {
            return handleRegenerateCredential(extractSubscriptionId(fullPath), extractResourceGroup(fullPath),
                    segment(tail, 1), req);
        }
        if (tail.matches("registries/[^/]+/listUsages")) {
            return handleListUsages(extractSubscriptionId(fullPath), extractResourceGroup(fullPath),
                    segment(tail, 1));
        }
        if (tail.matches("registries/[^/]+/importImage") && "POST".equals(method)) {
            // Image import is a no-op stub: accept and report success.
            return Response.status(202).build();
        }
        if (tail.matches("registries/[^/]+/replications")) {
            // The azurerm provider lists geo-replications during Read; the emulator has none.
            return Response.ok(Map.of("value", List.of())).type("application/json").build();
        }

        // ── Single registry CRUD ───────────────────────────────────────────
        if (tail.matches("registries/[^/]+")) {
            String registryName = segment(tail, 1);
            String sub = extractSubscriptionId(fullPath);
            String rg = extractResourceGroup(fullPath);
            return switch (method) {
                case "GET"    -> handleGet(sub, rg, registryName);
                case "PUT"    -> handleCreateOrUpdate(sub, rg, registryName, req);
                case "PATCH"  -> handlePatch(sub, rg, registryName, req);
                case "DELETE" -> handleDelete(sub, rg, registryName);
                default       -> methodNotAllowed();
            };
        }

        return notFound("Unknown ACR path: " + tail);
    }

    // ── Data plane ({name}.azurecr.io) ───────────────────────────────────────────

    /**
     * The registry a request is addressed to, taken from the {@code {name}.azurecr.io} host, or
     * {@code null} when the request did not arrive on the registry host. The challenge and token
     * endpoints exist only there, never on the shared container's published port.
     */
    static String registryFromHost(String host) {
        if (host == null) {
            return null;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(REGISTRY_HOST_SUFFIX)) {
            return null;
        }
        String name = lower.substring(0, lower.length() - REGISTRY_HOST_SUFFIX.length());
        return name.isEmpty() ? null : name;
    }

    /** {@code {name}.azurecr.io}, the login server Azure itself reports. */
    static String loginServer(String registryName) {
        return registryName.toLowerCase(Locale.ROOT) + REGISTRY_HOST_SUFFIX;
    }

    private Response handleDataPlane(AzureRequest req, String registryName) {
        String path = stripTrailingSlash(req.resourcePath());
        String loginServer = loginServer(registryName);

        // Azure resolves {name}.azurecr.io only for a registry that exists. Here the host is
        // whatever the caller's resolver points at the emulator, so an unknown name would
        // otherwise be handed tokens and a repository prefix of its own.
        if (!registryExists(registryName)) {
            return AcrErrors.error(404, AcrErrors.NAME_UNKNOWN,
                    "the registry '" + loginServer + "' does not exist");
        }

        if (path.equals("oauth2/exchange")) {
            return tokenService.handleExchange(req, loginServer);
        }
        if (path.equals("oauth2/token")) {
            return tokenService.handleToken(req, loginServer);
        }
        if (path.equals("v2") || path.startsWith("v2/")) {
            return handleRepository(req, registryName, loginServer, path);
        }
        return AcrErrors.error(404, AcrErrors.NAME_UNKNOWN,
                "unknown registry path '" + path + "' on " + loginServer);
    }

    /**
     * {@code GET /v2/} without credentials answers the bearer challenge that starts the token
     * exchange; everything else is proxied to the shared container. Tokens are issued, not verified,
     * so any {@code Authorization} header satisfies this check.
     */
    private Response handleRepository(AzureRequest req, String registryName, String loginServer, String path) {
        boolean authenticated = req.headers() != null
                && req.headers().getHeaderString("Authorization") != null;
        if (path.equals("v2") && !authenticated) {
            return AcrTokenService.challengeResponse(RequestUrls.resolveScheme(req), loginServer);
        }
        if (config.services().acr().mocked()) {
            return AcrErrors.error(405, AcrErrors.UNSUPPORTED,
                    "the registry data plane is not available in mocked mode; "
                            + "set floci-az.services.acr.mocked=false to start the shared registry");
        }
        String endpoint = dataPlaneEndpoint();
        if (endpoint == null) {
            return AcrErrors.error(502, AcrErrors.UNAVAILABLE,
                    "the shared registry container is not running");
        }
        return registryProxy.proxy(req, registryName, endpoint);
    }

    /** The shared container's endpoint, starting it on first use as the management plane does. */
    private String dataPlaneEndpoint() {
        try {
            registryManager.ensureStarted();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to start the shared ACR registry for a data-plane request");
            return null;
        }
        return registryManager.dataPlaneEndpoint();
    }

    private static String stripTrailingSlash(String path) {
        String trimmed = path == null ? "" : path;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    // ── CRUD operations ──────────────────────────────────────────────────────────

    private Response handleCreateOrUpdate(String sub, String rg, String registryName, AzureRequest req) {
        try {
            JsonNode body = readBody(req.bodyStream());
            String location = body.path("location").asText("eastus");
            JsonNode sku = body.path("sku");
            JsonNode props = body.path("properties");

            String storageKey = storageKey(sub, rg, registryName);
            Optional<Registry> existing = getRegistry(storageKey);
            boolean isNew = existing.isEmpty();

            Registry registry;
            if (isNew) {
                registry = new Registry();
                registry.setInstanceId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
                registry.setSubscriptionId(sub);
                registry.setResourceGroup(rg);
                registry.setName(registryName);
                registry.setCreatedAt(Instant.now());
                registry.setUsername(registryName);
                registry.setPassword(generatePassword());
                registry.setPassword2(generatePassword());
            } else {
                registry = existing.get();
            }

            registry.setLocation(location);
            registry.setSkuName(sku.path("name").asText("Basic"));
            registry.setAdminUserEnabled(props.path("adminUserEnabled").asBoolean(false));
            registry.setTags(parseStringMap(body.path("tags")));

            registry.setLoginServer(loginServer(registryName));
            if (config.services().acr().mocked()) {
                registry.setProvisioningState("Succeeded");
            } else if (isNew) {
                try {
                    registryManager.ensureStarted();
                    registry.setProvisioningState(registryManager.isReady() ? "Succeeded" : "Creating");
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to start shared ACR registry for %s", registryName);
                    registry.setProvisioningState("Failed");
                }
            }

            putRegistry(storageKey, registry);

            int status = isNew ? 201 : 200;
            return Response.status(status)
                    .entity(toArmResponse(registry))
                    .type("application/json")
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Error creating/updating ACR registry %s", registryName);
            return badRequest("Invalid request: " + e.getMessage());
        }
    }

    private Response handleGet(String sub, String rg, String registryName) {
        return getRegistry(storageKey(sub, rg, registryName))
                .map(r -> Response.ok(toArmResponse(r)).type("application/json").build())
                .orElseGet(() -> notFound("Registry '" + registryName + "' not found."));
    }

    private Response handlePatch(String sub, String rg, String registryName, AzureRequest req) {
        String key = storageKey(sub, rg, registryName);
        Optional<Registry> found = getRegistry(key);
        if (found.isEmpty()) {
            return notFound("Registry '" + registryName + "' not found.");
        }
        try {
            JsonNode body = readBody(req.bodyStream());
            Registry registry = found.get();
            if (body.has("tags")) {
                registry.setTags(parseStringMap(body.path("tags")));
            }
            if (body.path("sku").has("name")) {
                registry.setSkuName(body.path("sku").path("name").asText());
            }
            JsonNode props = body.path("properties");
            if (props.has("adminUserEnabled")) {
                registry.setAdminUserEnabled(props.path("adminUserEnabled").asBoolean());
            }
            putRegistry(key, registry);
            return Response.ok(toArmResponse(registry)).type("application/json").build();
        } catch (Exception e) {
            return badRequest("Invalid request: " + e.getMessage());
        }
    }

    private Response handleDelete(String sub, String rg, String registryName) {
        String key = storageKey(sub, rg, registryName);
        Optional<Registry> found = getRegistry(key);
        if (found.isEmpty()) {
            return notFound("Registry '" + registryName + "' not found.");
        }
        // The backing registry is shared across all registries, so deleting one only removes its
        // metadata; its repositories remain in the shared registry until garbage collection.
        storage.delete(key);
        return Response.status(202).build();
    }

    private Response handleListByResourceGroup(String fullPath) {
        String sub = extractSubscriptionId(fullPath);
        String rg = extractResourceGroup(fullPath);
        String prefix = sub + "/" + rg.toLowerCase() + "/";
        List<Map<String, Object>> items = new ArrayList<>();
        scanAll().stream()
                .filter(r -> r.storageKey().toLowerCase().startsWith(prefix))
                .forEach(r -> items.add(toArmResponse(r)));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    private Response handleListSubscription(String fullPath) {
        String sub = extractSubscriptionId(fullPath);
        String prefix = sub + "/";
        List<Map<String, Object>> items = new ArrayList<>();
        scanAll().stream()
                .filter(r -> r.storageKey().startsWith(prefix))
                .forEach(r -> items.add(toArmResponse(r)));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    // ── Credential operations ──────────────────────────────────────────────────────

    private Response handleListCredentials(String sub, String rg, String registryName) {
        return getRegistry(storageKey(sub, rg, registryName))
                .map(r -> Response.ok(credentials(r)).type("application/json").build())
                .orElseGet(() -> notFound("Registry '" + registryName + "' not found."));
    }

    private Response handleRegenerateCredential(String sub, String rg, String registryName, AzureRequest req) {
        String key = storageKey(sub, rg, registryName);
        Optional<Registry> found = getRegistry(key);
        if (found.isEmpty()) {
            return notFound("Registry '" + registryName + "' not found.");
        }
        Registry registry = found.get();
        String name = readBody(req.bodyStream()).path("name").asText("password");
        if ("password2".equalsIgnoreCase(name)) {
            registry.setPassword2(generatePassword());
        } else {
            registry.setPassword(generatePassword());
        }
        putRegistry(key, registry);
        return Response.ok(credentials(registry)).type("application/json").build();
    }

    private Response handleListUsages(String sub, String rg, String registryName) {
        Optional<Registry> found = getRegistry(storageKey(sub, rg, registryName));
        if (found.isEmpty()) {
            return notFound("Registry '" + registryName + "' not found.");
        }
        Map<String, Object> storageUsage = new LinkedHashMap<>();
        storageUsage.put("name", "Size");
        storageUsage.put("limit", 10_737_418_240L);
        storageUsage.put("currentValue", 0);
        storageUsage.put("unit", "Bytes");
        return Response.ok(Map.of("value", List.of(storageUsage))).type("application/json").build();
    }

    private Response handleCheckNameAvailability(AzureRequest req) {
        JsonNode body = readBody(req.bodyStream());
        String name = body.path("name").asText("");
        boolean taken = scanAll().stream().anyMatch(r -> r.getName().equalsIgnoreCase(name));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nameAvailable", !taken);
        if (taken) {
            result.put("reason", "AlreadyExists");
            result.put("message", "The registry " + name + " is already in use.");
        }
        return Response.ok(result).type("application/json").build();
    }

    // ── Readiness poller ─────────────────────────────────────────────────────────

    private void startReadinessPoller() {
        poller.scheduleAtFixedRate(() -> {
            try {
                scanAll().forEach(reg -> {
                    if ("Creating".equals(reg.getProvisioningState())) {
                        // Recovers the shared registry if it died after its initial start,
                        // so a pending registry cannot stay Creating forever.
                        registryManager.ensureStarted();
                        if (registryManager.isReady()) {
                            LOG.infov("ACR registry {0} is now ready", reg.getName());
                            reg.setProvisioningState("Succeeded");
                            putRegistry(reg.storageKey(), reg);
                        }
                    }
                });
            } catch (Exception e) {
                LOG.error("Error in ACR readiness poller", e);
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    // ── Storage helpers ──────────────────────────────────────────────────────────

    private Optional<Registry> getRegistry(String key) {
        return storage.get(key).map(so -> {
            try {
                return MAPPER.readValue(so.data(), Registry.class);
            } catch (Exception e) {
                LOG.warnv("Failed to deserialize ACR registry {0}: {1}", key, e.getMessage());
                return null;
            }
        });
    }

    private void putRegistry(String key, Registry registry) {
        try {
            byte[] data = MAPPER.writeValueAsBytes(registry);
            storage.put(key, new StoredObject(key, data, Map.of(), Instant.now(), key));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ACR registry: " + key, e);
        }
    }

    /**
     * Whether a registry of this name was created, in any subscription or resource group. The data
     * plane knows only the name: {@code {name}.azurecr.io} carries no ARM scope.
     */
    private boolean registryExists(String registryName) {
        return scanAll().stream().anyMatch(r -> registryName.equalsIgnoreCase(r.getName()));
    }

    private List<Registry> scanAll() {
        List<Registry> result = new ArrayList<>();
        storage.scan(k -> true).forEach(so -> {
            try {
                Registry r = MAPPER.readValue(so.data(), Registry.class);
                if (r != null) { result.add(r); }
            } catch (Exception e) {
                LOG.debugv("Skipping unreadable ACR registry entry: {0}", e.getMessage());
            }
        });
        return result;
    }

    // ── ARM response builders ──────────────────────────────────────────────────────

    private Map<String, Object> toArmResponse(Registry registry) {
        Map<String, Object> sku = new LinkedHashMap<>();
        sku.put("name", registry.getSkuName());
        sku.put("tier", registry.getSkuName());

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("loginServer", registry.getLoginServer());
        if (registry.getCreatedAt() != null) {
            props.put("creationDate", DateTimeFormatter.ISO_INSTANT.format(registry.getCreatedAt()));
        }
        props.put("provisioningState", registry.getProvisioningState());
        props.put("adminUserEnabled", registry.isAdminUserEnabled());
        props.put("publicNetworkAccess", "Enabled");
        props.put("anonymousPullEnabled", false);
        // The azurerm provider dereferences these without nil checks (e.g. *props.ZoneRedundancy),
        // so they must always be present in the response or the provider plugin panics.
        props.put("zoneRedundancy", "Disabled");
        props.put("dataEndpointEnabled", false);
        props.put("networkRuleBypassOptions", "AzureServices");
        // floci-az convenience, not in the real spec: the host port the shared registry container
        // publishes, which serves the same storage anonymously over plain HTTP. loginServer names
        // the Azure host, so nothing else reports it. Same field PostgreSQL and MySQL servers carry.
        int localPort = registryManager.publishedPort();
        if (localPort > 0) {
            props.put("localPort", localPort);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", registry.armId());
        out.put("name", registry.getName());
        out.put("type", "Microsoft.ContainerRegistry/registries");
        out.put("location", registry.getLocation());
        if (registry.getTags() != null && !registry.getTags().isEmpty()) {
            out.put("tags", registry.getTags());
        }
        out.put("sku", sku);
        out.put("properties", props);
        return out;
    }

    private static Map<String, Object> credentials(Registry registry) {
        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("name", "password");
        p1.put("value", registry.getPassword());
        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("name", "password2");
        p2.put("value", registry.getPassword2());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", registry.getUsername());
        out.put("passwords", List.of(p1, p2));
        return out;
    }

    private static String generatePassword() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    // ── Path parsing helpers ───────────────────────────────────────────────────────

    private static String extractAcrPath(String fullPath) {
        if (fullPath == null) { return ""; }
        int idx = fullPath.indexOf(PROVIDER);
        if (idx >= 0) {
            return fullPath.substring(idx + PROVIDER.length());
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

    private static String storageKey(String sub, String rg, String registryName) {
        return sub + "/" + rg + "/" + registryName;
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

    /** Wipes all ACR data — used by {@code POST /_admin/reset}. */
    public void clear() {
        storage.clear();
    }
}
