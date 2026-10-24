package io.floci.az.services.acr;

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
import io.floci.az.services.acr.AcrModels.Registry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
 *   GET  {name}.azurecr.io/acr/v1/_catalog          repository catalog (scoped challenge first)
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
public class AcrHandler implements AzureServiceHandler, Resettable, ResourceIndexContributor, GlobalNameSource {

    private static final Logger LOG = Logger.getLogger(AcrHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String TYPE = "Microsoft.ContainerRegistry/registries";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALNUM =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String NAMESPACE = "Microsoft.ContainerRegistry";

    /** The Azure host suffix the registry data plane is served on. */
    public static final String REGISTRY_HOST_SUFFIX = ".azurecr.io";

    private static final String ACR_V1_PREFIX = "acr/v1/";
    private static final String ACR_V1_CATALOG = ACR_V1_PREFIX + "_catalog";

    private final EmulatorConfig config;
    private final AcrRegistryManager registryManager;
    private final AcrTokenService tokenService;
    private final AcrRegistryProxy registryProxy;
    private final GlobalNameRegistry names;
    /**
     * Every registry, keyed by its canonical id. Before the store, registries were kept under raw-case
     * {@code sub/rg/name}; those entries are read as they are and move to their canonical key on their next write.
     */
    private final ArmResourceStore<Registry> store;
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
                      StorageFactory storageFactory,
                      GlobalNameRegistry names) {
        this.config = config;
        this.registryManager = registryManager;
        this.tokenService = tokenService;
        this.registryProxy = registryProxy;
        this.names = names;
        this.store = ArmResourceStore.create(storageFactory, "acr", Registry.class, Registry::armId, key -> true);
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
        if (path.startsWith(ACR_V1_PREFIX)) {
            return handleAcrMetadata(req, registryName, loginServer, path);
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
        if (path.equals("v2") && !isAuthenticated(req)) {
            return AcrTokenService.challengeResponse(RequestUrls.resolveScheme(req), loginServer);
        }
        return proxyToSharedRegistry(req, registryName);
    }

    private Response proxyToSharedRegistry(AzureRequest req, String registryName) {
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

    /**
     * The ACR metadata API under {@code /acr/v1/}, which the Azure SDK container-registry clients
     * call without credentials first and authenticate only when challenged. An unauthenticated
     * request is therefore answered with a challenge naming the scope it needs, since the clients
     * start the token exchange only when the challenge carries one. Of the metadata API only the
     * repository catalog is served, from the same storage as {@code /v2/_catalog}.
     */
    private Response handleAcrMetadata(AzureRequest req, String registryName, String loginServer, String path) {
        if (!isAuthenticated(req)) {
            String scope = AcrTokenService.scopeFor(req.method(), path);
            if (scope != null) {
                return AcrTokenService.challengeResponse(RequestUrls.resolveScheme(req), loginServer, scope);
            }
        }
        if (!path.equals(ACR_V1_CATALOG)) {
            return AcrErrors.error(404, AcrErrors.NAME_UNKNOWN,
                    "unknown registry path '" + path + "' on " + loginServer);
        }
        return proxyToSharedRegistry(req, registryName);
    }

    private static boolean isAuthenticated(AzureRequest req) {
        return req.headers() != null && req.headers().getHeaderString("Authorization") != null;
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

            // The name is reserved in the GlobalNameRegistry by the gate's GlobalNameRule before this runs, so a
            // name another subscription owns never reaches here. The read and the first write are one store
            // operation, so two concurrent creates of one registry keep the first one's instance and passwords.
            boolean mocked = config.services().acr().mocked();
            ArmResourceStore.Written<Registry> written = store.createOrUpdate(registryId(sub, rg, registryName),
                    existing -> {
                        Registry current = existing.orElseGet(() -> newRegistry(sub, rg, registryName));
                        current.setLocation(location);
                        current.setSkuName(sku.path("name").asText("Basic"));
                        current.setAdminUserEnabled(props.path("adminUserEnabled").asBoolean(false));
                        current.setTags(parseStringMap(body.path("tags")));
                        current.setLoginServer(loginServer(registryName));
                        if (mocked) {
                            current.setProvisioningState("Succeeded");
                        } else if (existing.isEmpty()) {
                            current.setProvisioningState("Creating");
                        }
                        return current;
                    });
            boolean isNew = written.created();
            Registry registry = written.stored().value();

            if (!config.services().acr().mocked() && isNew) {
                String provisioningState;
                try {
                    registryManager.ensureStarted();
                    provisioningState = registryManager.isReady() ? "Succeeded" : "Creating";
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to start shared ACR registry for %s", registryName);
                    provisioningState = "Failed";
                }
                // A DELETE may have removed the registry while it started; writing the record
                // back would resurrect a name another subscription may have claimed since.
                String startedState = provisioningState;
                registry.setProvisioningState(startedState);
                Optional<Registry> stored = updateIfSameInstance(registry,
                        current -> current.setProvisioningState(startedState));
                if (stored.isPresent()) {
                    registry = stored.get();
                }
            }

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
        return getRegistry(registryId(sub, rg, registryName))
                .map(r -> Response.ok(toArmResponse(r)).type("application/json").build())
                .orElseGet(() -> notFound("Registry '" + registryName + "' not found."));
    }

    private Response handlePatch(String sub, String rg, String registryName, AzureRequest req) {
        Optional<Registry> found = getRegistry(registryId(sub, rg, registryName));
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
            store.put(registry);
            return Response.ok(toArmResponse(registry)).type("application/json").build();
        } catch (Exception e) {
            return badRequest("Invalid request: " + e.getMessage());
        }
    }

    private Response handleDelete(String sub, String rg, String registryName) {
        ArmResourceId id = registryId(sub, rg, registryName);
        if (getRegistry(id).isEmpty()) {
            return notFound("Registry '" + registryName + "' not found.");
        }
        // The backing registry is shared across all registries, so deleting one only removes its
        // metadata; its repositories remain in the shared registry until garbage collection.
        store.delete(id);
        return Response.status(202).build();
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

    // ── Credential operations ──────────────────────────────────────────────────────

    private Response handleListCredentials(String sub, String rg, String registryName) {
        return getRegistry(registryId(sub, rg, registryName))
                .map(r -> Response.ok(credentials(r)).type("application/json").build())
                .orElseGet(() -> notFound("Registry '" + registryName + "' not found."));
    }

    private Response handleRegenerateCredential(String sub, String rg, String registryName, AzureRequest req) {
        Optional<Registry> found = getRegistry(registryId(sub, rg, registryName));
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
        store.put(registry);
        return Response.ok(credentials(registry)).type("application/json").build();
    }

    private Response handleListUsages(String sub, String rg, String registryName) {
        Optional<Registry> found = getRegistry(registryId(sub, rg, registryName));
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

    /** {@code Registries_CheckNameAvailability}: whether the name is owned in the caller's cloud. */
    private Response handleCheckNameAvailability(AzureRequest req) {
        JsonNode body = readBody(req.bodyStream());
        String name = body.path("name").asText("");
        boolean taken = names.owner(cloud(req), CloudSuffix.ACR, name).isPresent();
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
                store.all().stream().map(ArmResourceStore.Stored::value).forEach(reg -> {
                    if ("Creating".equals(reg.getProvisioningState())) {
                        // Recovers the shared registry if it died after its initial start,
                        // so a pending registry cannot stay Creating forever.
                        registryManager.ensureStarted();
                        if (registryManager.isReady()) {
                            updateIfSameInstance(reg, current -> current.setProvisioningState("Succeeded"))
                                    .ifPresent(ready -> LOG.infov("ACR registry {0} is now ready", ready.getName()));
                        }
                    }
                });
            } catch (Exception e) {
                LOG.error("Error in ACR readiness poller", e);
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    // ── Storage helpers ──────────────────────────────────────────────────────────

    private static Registry newRegistry(String sub, String rg, String registryName) {
        Registry registry = new Registry();
        registry.setInstanceId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        registry.setSubscriptionId(sub);
        registry.setResourceGroup(rg);
        registry.setName(registryName);
        registry.setCreatedAt(Instant.now());
        registry.setUsername(registryName);
        registry.setPassword(generatePassword());
        registry.setPassword2(generatePassword());
        return registry;
    }

    private Optional<Registry> getRegistry(ArmResourceId id) {
        return store.get(id).map(ArmResourceStore.Stored::value);
    }

    /**
     * Applies {@code change} to the stored copy of {@code registry} while it is still the same instance, and
     * returns the stored result. Changing the stored copy, rather than writing {@code registry} back, keeps any
     * update made since {@code registry} was read. Empty once the registry was deleted, or deleted and created
     * again.
     */
    private Optional<Registry> updateIfSameInstance(Registry registry, Consumer<Registry> change) {
        AtomicBoolean same = new AtomicBoolean();
        Optional<ArmResourceStore.Stored<Registry>> stored = store.update(registryId(registry), current -> {
            if (registry.getInstanceId().equals(current.getInstanceId())) {
                same.set(true);
                change.accept(current);
            }
            return current;
        });
        return same.get() ? stored.map(ArmResourceStore.Stored::value) : Optional.empty();
    }

    /**
     * Whether a registry of this name was created, in any subscription or resource group. The data
     * plane knows only the name: {@code {name}.azurecr.io} carries no ARM scope.
     */
    private boolean registryExists(String registryName) {
        return store.all().stream().anyMatch(stored -> registryName.equalsIgnoreCase(stored.value().getName()));
    }

    private static ArmResourceId registryId(String sub, String rg, String registryName) {
        return ArmResourceId.parse(String.format(
                "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.ContainerRegistry/registries/%s",
                sub, rg, registryName)).orElseThrow();
    }

    private static ArmResourceId registryId(Registry registry) {
        return ArmResourceId.parse(registry.armId()).orElseThrow();
    }

    private static CloudProfile cloud(AzureRequest req) {
        return req.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : req.scope().cloud();
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
        out.put("type", TYPE);
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
        return ArmPaths.afterProvider(fullPath, NAMESPACE, fullPath);
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

    /** Wipes all ACR data — used by {@code POST /_admin/reset}. */
    public void clear() {
        store.clear();
    }

    // ── ResourceIndexContributor ────────────────────────────────────────────────

    @Override
    public boolean indexEnabled() {
        return config.services().acr().enabled();
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
                .map(stored -> ArmResources.indexEntry(stored.value().armId(), stored.value().getName(), TYPE,
                        stored.value().getLocation(), stored.value().getTags()))
                .toList();
    }

    // ── GlobalNameSource ────────────────────────────────────────────────────────

    @Override
    public List<Claim> globalNames() {
        return store.all().stream()
                .map(stored -> new Claim(CloudSuffix.ACR, stored.value().getName(), stored.id()))
                .toList();
    }
}
