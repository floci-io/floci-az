package io.floci.az.services.arm;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmProviderService;
import io.floci.az.core.arm.ArmRequestGate;
import io.floci.az.core.arm.ArmResourceFilter;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.ResourceIndexContributor;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.DirectoryOrigin;
import io.floci.az.core.directory.Subscription;
import io.floci.az.core.directory.Tenant;
import io.floci.az.services.apim.ApiManagementHandler;
import io.floci.az.services.functions.FunctionRuntime;
import io.floci.az.services.functions.FunctionsServiceHandler;
import io.floci.az.services.managedidentity.ManagedIdentityHandler;
import io.floci.az.services.monitor.activitylog.ActivityLogEndpoint;
import io.floci.az.services.network.NetworkHandler;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * ARM management-plane handler for Azure Resource Manager paths that are not
 * served by more-specific handlers (AKS, SQL).
 *
 * <p>Handles the minimal ARM surface required by the {@code hashicorp/azurerm}
 * Terraform provider v4+:
 *
 * <ul>
 *   <li>Subscription — {@code GET /subscriptions/{sub}}</li>
 *   <li>Resource Groups: {@code PUT/GET/HEAD/PATCH/DELETE /subscriptions/{sub}/resourceGroups/{rg}}, served by
 *       {@link ResourceGroupService}</li>
 *   <li>Key Vaults — ARM shell whose vault URI points at the existing Key Vault handler</li>
 * </ul>
 */
@ApplicationScoped
public class ArmHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(ArmHandler.class);

    private static final String DEFAULT_FUNCTIONS_ACCOUNT = "devstoreaccount1";

    // In-memory ARM resource state — no StorageBackend needed, these are ephemeral
    private final Map<String, Map<String, Object>> keyVaults        = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> managedHsms      = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> webApps          = new ConcurrentHashMap<>();
    private final EmulatorConfig config;
    private final FunctionsServiceHandler functionsHandler;
    private final ApiManagementHandler apiManagementHandler;
    private final NetworkHandler networkHandler;
    private final ManagedIdentityHandler managedIdentityHandler;

    /** provider namespace → owning ArmProviderService, assembled from CDI at startup. */
    private final Instance<ArmProviderService> providerServices;
    private final Map<String, ArmProviderService> providerLane = new HashMap<>();

    /** Services contributing their resources to the resource-group /resources index. */
    private final Instance<ResourceIndexContributor> resourceIndexContributors;

    /** Wraps this handler, its inline providers and its provider lane, for callers that bypass the router. */
    private final ArmRequestGate gate;

    /** The tenants and subscriptions {@code GET /tenants} and {@code GET /subscriptions} report. */
    private final AzureDirectory directory;

    /** Resource groups: their own operations and the cascade a delete runs. */
    private final ResourceGroupService resourceGroups;

    /** The Providers API: the namespaces floci-az serves and the subscription's registration with each. */
    private final ProviderService providerService;

    @Inject
    public ArmHandler(EmulatorConfig config, FunctionsServiceHandler functionsHandler, ApiManagementHandler apiManagementHandler,
                      NetworkHandler networkHandler, ManagedIdentityHandler managedIdentityHandler,
                      Instance<ArmProviderService> providerServices,
                      Instance<ResourceIndexContributor> resourceIndexContributors, ArmRequestGate gate,
                      AzureDirectory directory, ResourceGroupService resourceGroups,
                      ProviderService providerService) {
        this.config               = config;
        this.functionsHandler     = functionsHandler;
        this.apiManagementHandler = apiManagementHandler;
        this.networkHandler       = networkHandler;
        this.managedIdentityHandler = managedIdentityHandler;
        this.providerServices     = providerServices;
        this.resourceIndexContributors = resourceIndexContributors;
        this.gate                 = gate;
        this.directory            = directory;
        this.resourceGroups       = resourceGroups;
        this.providerService      = providerService;
    }

    /**
     * Builds the provider-namespace → service map. Two services claiming the same namespace is a wiring
     * bug that would otherwise resolve by arbitrary CDI order — fail fast at startup.
     */
    @PostConstruct
    void buildProviderLane() {
        for (ArmProviderService service : providerServices) {
            for (String namespace : service.providerNamespaces()) {
                ArmProviderService previous = providerLane.putIfAbsent(namespace, service);
                if (previous != null) {
                    throw new IllegalStateException("Two ArmProviderService beans claim " + namespace
                        + ": " + previous.getClass().getSimpleName() + " and " + service.getClass().getSimpleName());
                }
            }
        }
        LOG.infof("ARM provider lane: %d namespaces → %s", providerLane.size(), providerLane.keySet());
    }

    @Override
    public String getServiceType() { return "arm"; }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().arm().enabled();
    }

    /**
     * Serves the request inside the ARM request gate. A request the router dispatched is already inside it
     * and passes straight through; one that reaches this handler another way enters here, so the inline
     * providers and the {@link ArmProviderService} lane are never served outside the gate.
     */
    @Override
    public Response handle(AzureRequest req) {
        return gate.serve(req, this::handleInsideGate);
    }

    private Response handleInsideGate(AzureRequest req) {
        // ARM-plane handlers (Network/APIM/Monitor/EventGrid) parse the body strictly; a malformed
        // body surfaces here as the real ARM 400 InvalidRequestContent rather than a lenient no-op.
        try {
            return dispatch(req);
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The request content was invalid and could not be deserialized.");
        }
    }

    private Response dispatch(AzureRequest req) {
        String path   = req.resourcePath();
        String method = req.method().toUpperCase();

        LOG.debugf("ArmHandler %s %s", method, path);

        // ── Providers API ─────────────────────────────────────────────────────
        // First, because register and unregister carry the namespace's /providers/Microsoft.X/ segment
        // (Microsoft.Network's included), and the provider list and a single namespace have no type segment.
        Response providers = providers(req, path, method);
        if (providers != null) {
            return providers;
        }

        // ── Network Provider (subscription & resource-group levels) ───────────
        // Network is dispatched here, NOT via the providerLane map: subscription-scoped Network list
        // paths (no /resourceGroups/) must be caught before the resource-group branch below, and the
        // map lane is reached only from inside that branch. This block owns Network's enabled-guard.
        if (ArmPaths.hasProvider(path, "Microsoft.Network")) {
            if (!config.services().network().enabled()) {
                return armNotFound(path);
            }
            return networkHandler.handleArm(req, path, method, extractSub(path));
        }

        // ── Tenant list ───────────────────────────────────────────────────────
        // GET /tenants — the az CLI enumerates tenants during `az login`.
        if (path.matches("tenants([?].*)?")) {
            return Response.ok(Map.of("value", directory.tenants().stream().map(ArmHandler::tenantBody).toList()))
                    .build();
        }

        // ── Subscription list ─────────────────────────────────────────────────
        // GET /subscriptions — the az CLI enumerates subscriptions during `az login`.
        if (path.matches("subscriptions([?].*)?")) {
            return Response.ok(Map.of("value", callerSubscriptions(req).stream()
                    .map(subscription -> subscriptionBody(subscription, subscription.id())).toList())).build();
        }

        // ── Subscription ──────────────────────────────────────────────────────
        // The gate's SubscriptionRule has already registered an unknown id on first use, or refused it.
        if (path.matches("subscriptions/[^/?]+") ||
            path.matches("subscriptions/[^/?]+\\?.*")) {
            String sub = extractSub(path);
            // compat echoes the id as the path spells it, as floci-az always has; subscription-listing echoes
            // the directory's spelling, as Azure echoes its canonical id.
            return directory.subscription(sub)
                    .map(subscription -> Response.ok(subscriptionBody(subscription,
                            config.subscriptionListing() ? subscription.id() : sub)).build())
                    .orElseGet(() -> ArmErrors.subscriptionNotFound(sub));
        }

        // ── Location list ─────────────────────────────────────────────────────
        // GET /subscriptions/{sub}/locations — `az account list-locations`, the CLI's display-name
        // location translation, and azurerm's data.azurerm_location all read this.
        if (path.matches("subscriptions/[^/?]+/locations([?].*)?")) {
            return Response.ok(Map.of("value", AzureLocations.forSubscription(cloud(req), extractSub(path)))).build();
        }

        // ── Subscription-level paths of a provider-lane namespace that serves them ──
        // Its subscription-wide lists and provider actions (Key Vault's checkNameAvailability among them), before
        // the generic checkNameAvailability below answers for the namespaces nothing serves.
        ArmProviderService subscriptionLevel = subscriptionLevelProvider(path);
        if (subscriptionLevel != null) {
            return subscriptionLevel.armEnabled()
                    ? subscriptionLevel.handleArm(req, path, method, extractSub(path))
                    : armNotFound(path);
        }

        // ── checkNameAvailability ──────────────────────────────────────────────
        // az CLI probes this before creating storage accounts / key vaults / etc. ARM paths are
        // case-insensitive and clients disagree on the casing: Storage and KeyVault send
        // checkNameAvailability, the Web provider (azurerm web and function apps) checknameavailability.
        if (path.toLowerCase(Locale.ROOT)
                .matches("subscriptions/[^/]+/providers/microsoft\\.[^/]+/checknameavailability([?].*)?")) {
            return checkNameAvailability(req, path);
        }

        // ── Cross-subscription key vault listing ───────────────────────────────
        if (path.matches("subscriptions/[^/?]+/providers/(?i:Microsoft\\.KeyVault)/vaults([?].*)?")) {
            String sub = extractSub(path);
            List<Map<String, Object>> vaults = keyVaults.values().stream()
                    .filter(v -> sub.equals(v.get("_sub")))
                    .map(ArmHandler::stripInternal)
                    .toList();
            return Response.ok(Map.of("value", vaults)).build();
        }

        // ── Cross-subscription managed HSM listing ─────────────────────────────
        if (path.matches("subscriptions/[^/?]+/providers/(?i:Microsoft\\.KeyVault)/managedHSMs([?].*)?")) {
            String sub = extractSub(path);
            List<Map<String, Object>> hsms = managedHsms.values().stream()
                    .filter(h -> sub.equals(h.get("_sub")))
                    .map(ArmHandler::stripInternal)
                    .toList();
            return Response.ok(Map.of("value", hsms)).build();
        }

        // ── Subscription Activity Log (Microsoft.Insights/eventtypes/management/values) ──
        if (ActivityLogEndpoint.matches(path)) {
            return handleProviders(req, path, method, extractSub(path));
        }

        // ── Subscription-level resource listing ──────────────────────────────
        if (path.matches("subscriptions/[^/?]+/resources([?].*)?")) {
            return listIndex(req, extractSub(path), null);
        }

        // ── Long-running operation results (resource-group delete) ───────────
        if (path.matches("subscriptions/[^/?]+/operationresults/[^/?]+([?].*)?")) {
            return resourceGroups.operationResult(extractSub(path), extractAfter(path, "/operationresults/"));
        }

        // ── Resource Groups ───────────────────────────────────────────────────
        // Azure spec uses lowercase "resourcegroups" but convention uses "resourceGroups"; accept both.
        if (path.startsWith("subscriptions/") && path.toLowerCase().contains("/resourcegroups")) {
            return handleResourceGroupBranch(req, path, method);
        }

        return armNotFound(path);
    }

    // ── Resource Group routing ────────────────────────────────────────────────

    private Response handleResourceGroupBranch(AzureRequest req, String path, String method) {
        String sub = extractSub(path);
        String lc  = path.toLowerCase();

        // GET subscriptions/{sub}/resourceGroups  (list)
        if (lc.matches("subscriptions/[^/]+/resourcegroups([?].*)?")) {
            return resourceGroups.list(sub);
        }

        // subscriptions/{sub}/resourceGroups/{rg}  (no trailing segments)
        if (lc.matches("subscriptions/[^/]+/resourcegroups/[^/]+([?].*)?")) {
            return resourceGroups.handle(req, method, sub, extractRg(path), () -> resourceIds(sub));
        }

        // subscriptions/{sub}/resourceGroups/{rg}/resources  (list resources in RG)
        // azurerm provider calls this before deleting a resource group to verify it is empty.
        if (lc.matches("subscriptions/[^/]+/resourcegroups/[^/]+/resources([?].*)?")) {
            return listIndex(req, sub, extractRg(path));
        }

        // subscriptions/{sub}/resourceGroups/{rg}/providers/...
        if (path.contains("/providers/")) {
            return handleProviders(req, path, method, sub);
        }

        return armNotFound(path);
    }

    /**
     * Answers either generic listing, honouring {@code $filter}. The azurerm provider fills its
     * Key Vault cache from {@code $filter=resourceType eq 'Microsoft.KeyVault/vaults'} and parses
     * every returned id as a vault id, so a filter must narrow the listing — or be refused — never
     * be ignored.
     */
    private Response listIndex(AzureRequest req, String sub, String rg) {
        String filter = req.queryParams() == null ? null : req.queryParams().get("$filter");
        Predicate<Map<String, Object>> matches;
        try {
            matches = ArmResourceFilter.parse(filter);
        } catch (ArmResourceFilter.InvalidFilterException e) {
            return ArmErrors.error(400, "InvalidFilter", e.getMessage());
        }
        return Response.ok(Map.of("value", indexedResources(sub, rg, config.resourceGroupDelete()).stream()
                .filter(matches).toList())).build();
    }

    /** The id of every resource in the subscription, contributors listed only under parity included. */
    private List<String> resourceIds(String sub) {
        return indexedResources(sub, null, true).stream()
                .map(resource -> resource.get("id"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    /**
     * The generic resource index for a scope: the whole subscription when {@code rg} is null, one
     * resource group otherwise. Both ARM listings answer from this one assembly so they cannot
     * disagree about what the estate holds.
     *
     * <p>ArmHandler's own Key Vault / Web state (and Storage's contributor) contributes the full stored body rather
     * than the trimmed {@code ArmResources.indexEntry} shape: the azurerm provider reads this
     * listing to populate its Key Vault cache and looks vaults up by {@code properties.vaultUri},
     * which a properties-free entry would not carry. A deliberate deviation from Azure, whose
     * generic listing never carries {@code properties} — its {@code $expand} adds only
     * {@code createdTime}, {@code changedTime} and {@code provisioningState}.</p>
     *
     * @param everyContributor whether contributors outside the default listing
     *                         ({@link ResourceIndexContributor#inDefaultListing()}) contribute too
     */
    public List<Map<String, Object>> indexedResources(String sub, String rg, boolean everyContributor) {
        List<Map<String, Object>> resources = new ArrayList<>();
        collectOwn(keyVaults, sub, rg, resources);
        collectOwn(managedHsms, sub, rg, resources);
        collectOwn(webApps, sub, rg, resources);
        if (config.services().network().enabled()) {
            resources.addAll(rg == null
                    ? networkHandler.listResources(sub)
                    : networkHandler.listResources(sub, rg));
        }
        resources.addAll(rg == null
                ? apiManagementHandler.listSubscriptionServices(sub)
                : apiManagementHandler.listServices(sub, rg));
        if (config.services().managedIdentity().enabled()) {
            resources.addAll(rg == null
                    ? managedIdentityHandler.listResources(sub)
                    : managedIdentityHandler.listResources(sub, rg));
        }
        for (ResourceIndexContributor contributor : resourceIndexContributors) {
            if (contributor.indexEnabled() && (everyContributor || contributor.inDefaultListing())) {
                resources.addAll(rg == null
                        ? contributor.listSubscriptionResources(sub)
                        : contributor.listRgResources(sub, rg));
            }
        }
        return resources;
    }

    /** ArmHandler's own ARM state for a scope, stripped of the internal routing keys. */
    private static void collectOwn(Map<String, Map<String, Object>> state, String sub, String rg,
                                   List<Map<String, Object>> out) {
        state.values().stream()
                .filter(r -> sub.equals(r.get(ArmResources.SUB_KEY))
                        && (rg == null || rg.equals(r.get(ArmResources.RG_KEY))))
                .map(ArmHandler::stripInternal)
                .forEach(out::add);
    }

    // ── Provider-level routing ────────────────────────────────────────────────

    private Response handleProviders(AzureRequest req, String path, String method, String sub) {
        // Inline ARM shells that bridge to data-plane handlers (KeyVault) or functions
        // provisioning (Web) — coupled to those subsystems, so they stay here rather than in the lane.
        if (ArmPaths.hasProvider(path, "Microsoft.KeyVault")) {
            return handleKeyVault(req, path, method, sub);
        }
        if (ArmPaths.hasProvider(path, "Microsoft.Web")) {
            return handleWeb(req, path, method, sub);
        }
        // Delegating providers (Storage/APIM/Monitor/EventGrid), via the ArmProviderService lane.
        ArmProviderService provider = providerLane(providerNamespace(path));
        if (provider != null) {
            return provider.armEnabled() ? provider.handleArm(req, path, method, sub) : armNotFound(path);
        }
        return armNotFound(path);
    }

    /** The lane service for a namespace, matched ignoring case: ARM ids and links spell namespaces either way. */
    private ArmProviderService providerLane(String namespace) {
        ArmProviderService exact = providerLane.get(namespace);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, ArmProviderService> entry : providerLane.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(namespace)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * The provider-lane service that serves {@code path} when it is a subscription-level path of its namespace
     * ({@code subscriptions/{sub}/providers/Microsoft.X/...}, no resource group) and it serves those
     * ({@link ArmProviderService#servesSubscriptionPaths()}); null otherwise. The namespace compares
     * case-insensitively, as ARM's provider actions are sent in either casing.
     */
    private ArmProviderService subscriptionLevelProvider(String path) {
        if (!path.matches("subscriptions/[^/?]+/providers/[^/?]+/.+")) {
            return null;
        }
        String namespace = providerNamespace(path);
        for (Map.Entry<String, ArmProviderService> lane : providerLane.entrySet()) {
            if (lane.getKey().equalsIgnoreCase(namespace) && lane.getValue().servesSubscriptionPaths()) {
                return lane.getValue();
            }
        }
        return null;
    }

    /** The {@code Microsoft.X} namespace segment immediately after {@code /providers/}, or "" if none. */
    private static String providerNamespace(String path) {
        int marker = path.indexOf("/providers/");
        if (marker < 0) {
            return "";
        }
        String rest = path.substring(marker + "/providers/".length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    // ── Function Apps ────────────────────────────────────────────────────────

    private Response handleWeb(AzureRequest req, String path, String method, String sub) {
        String rg = extractRg(path);

        if (path.matches(".*/providers/(?i:Microsoft\\.Web)/sites([?].*)?")) {
            List<Map<String, Object>> apps = webApps.values().stream()
                    .filter(a -> sub.equals(a.get("_sub")) && rg.equals(a.get("_rg")))
                    .map(ArmHandler::stripInternal)
                    .toList();
            return Response.ok(Map.of("value", apps)).build();
        }

        if (path.contains("/sites/")) {
            String appName = extractResourceName(path, "sites");
            if (path.contains("/config/web")) {
                return handleWebConfig(req, path, method, sub, rg, appName);
            }
            return switch (method) {
                case "PUT"    -> createOrUpdateWebApp(req, sub, rg, appName);
                case "GET"    -> getWebApp(sub, rg, appName);
                case "DELETE" -> { webApps.remove(webAppKey(sub, rg, appName)); yield Response.ok().build(); }
                default       -> Response.status(405).build();
            };
        }

        return armNotFound(path);
    }

    private synchronized Response createOrUpdateWebApp(AzureRequest req, String sub, String rg, String appName) {
        if (ownedElsewhere(webApps, sub, rg, appName).isPresent()) {
            return webAppNameTaken(appName);
        }
        Map<String, Object> body = parseBody(req);
        Map<String, Object> properties = cast(body.get("properties"));
        Map<String, Object> siteConfig = cast(properties.get("siteConfig"));
        String linuxFxVersion = bodyString(siteConfig, "linuxFxVersion", null);
        try {
            Map<String, Object> resource = webAppResource(sub, rg, appName,
                    bodyString(body, "location", "eastus"), linuxFxVersion);
            syncFunctionApp(appName, linuxFxVersion);
            webApps.put(webAppKey(sub, rg, appName), resource);
            return Response.ok(stripInternal(resource)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", Map.of(
                    "code", "InvalidLinuxFxVersion", "message", e.getMessage()))).build();
        }
    }

    private Response getWebApp(String sub, String rg, String appName) {
        Map<String, Object> resource = webApps.get(webAppKey(sub, rg, appName));
        return resource == null ? armNotFound("sites/" + appName)
                : Response.ok(stripInternal(resource)).build();
    }

    private Response handleWebConfig(AzureRequest req, String path, String method, String sub,
                                     String rg, String appName) {
        Map<String, Object> resource = webApps.get(webAppKey(sub, rg, appName));
        if (resource == null) {
            return armNotFound("sites/" + appName);
        }
        if ("GET".equals(method)) {
            return Response.ok(Map.of("id", path, "name", "web",
                    "type", "Microsoft.Web/sites/config",
                    "properties", cast(resource.get("properties")).get("siteConfig"))).build();
        }
        if (!"PUT".equals(method)) {
            return Response.status(405).build();
        }

        Map<String, Object> body = parseBody(req);
        Map<String, Object> config = body.containsKey("properties") ? cast(body.get("properties")) : body;
        String linuxFxVersion = bodyString(config, "linuxFxVersion", null);
        try {
            Map<String, Object> updated = webAppResource(sub, rg, appName,
                    bodyString(resource, "location", "eastus"), linuxFxVersion);
            syncFunctionApp(appName, linuxFxVersion);
            webApps.put(webAppKey(sub, rg, appName), updated);
            return Response.ok(Map.of("id", path, "name", "web",
                    "type", "Microsoft.Web/sites/config",
                    "properties", cast(updated.get("properties")).get("siteConfig"))).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", Map.of(
                    "code", "InvalidLinuxFxVersion", "message", e.getMessage()))).build();
        }
    }

    private void syncFunctionApp(String appName, String linuxFxVersion) {
        if (linuxFxVersion == null || linuxFxVersion.isBlank()) {
            return;
        }
        String runtime = FunctionRuntime.runtimeFromLinuxFxVersion(linuxFxVersion);
        functionsHandler.upsertApp(DEFAULT_FUNCTIONS_ACCOUNT, appName, runtime, linuxFxVersion, null);
    }

    private static Map<String, Object> webAppResource(String sub, String rg, String appName,
                                                       String location, String linuxFxVersion) {
        Map<String, Object> siteConfig = new LinkedHashMap<>();
        if (linuxFxVersion != null) {
            siteConfig.put("linuxFxVersion", linuxFxVersion);
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("state", "Running");
        properties.put("siteConfig", siteConfig);

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("_sub", sub);
        resource.put("_rg", rg);
        resource.put("id", "/subscriptions/" + sub + "/resourceGroups/" + rg
                + "/providers/Microsoft.Web/sites/" + appName);
        resource.put("name", appName);
        resource.put("type", "Microsoft.Web/sites");
        resource.put("location", location);
        resource.put("kind", "functionapp,linux");
        resource.put("properties", properties);
        return resource;
    }

    // ── Key Vault ─────────────────────────────────────────────────────────────

    private Response handleKeyVault(AzureRequest req, String path, String method, String sub) {
        String rg = extractRg(path);

        // GET list
        if (path.matches(".*/providers/(?i:Microsoft\\.KeyVault)/vaults([?].*)?")) {
            List<Map<String, Object>> vaults = keyVaults.values().stream()
                    .filter(v -> sub.equals(v.get("_sub")) && rg.equals(v.get("_rg")))
                    .map(ArmHandler::stripInternal)
                    .toList();
            return Response.ok(Map.of("value", vaults)).build();
        }

        // Single vault
        if (path.contains("/vaults/")) {
            String vaultName = extractResourceName(path, "vaults");
            return switch (method) {
                case "PUT"    -> createOrUpdateKeyVault(req, sub, rg, vaultName);
                case "GET"    -> getKeyVault(sub, rg, vaultName);
                case "DELETE" -> { keyVaults.remove(kvKey(sub, rg, vaultName)); yield Response.ok().build(); }
                default       -> Response.status(405).build();
            };
        }

        // Managed HSM list
        if (path.matches(".*/providers/(?i:Microsoft\\.KeyVault)/managedHSMs([?].*)?")) {
            List<Map<String, Object>> hsms = managedHsms.values().stream()
                    .filter(h -> sub.equals(h.get("_sub")) && rg.equals(h.get("_rg")))
                    .map(ArmHandler::stripInternal)
                    .toList();
            return Response.ok(Map.of("value", hsms)).build();
        }

        // Single managed HSM
        if (path.contains("/managedHSMs/")) {
            String hsmName = extractResourceName(path, "managedHSMs");
            return switch (method) {
                case "PUT"    -> createOrUpdateManagedHsm(req, sub, rg, hsmName);
                case "GET"    -> getManagedHsm(sub, rg, hsmName);
                case "DELETE" -> { managedHsms.remove(hsmKey(sub, rg, hsmName)); yield Response.ok().build(); }
                default       -> Response.status(405).build();
            };
        }

        return armNotFound(path);
    }

    private synchronized Response createOrUpdateKeyVault(AzureRequest req, String sub, String rg, String vaultName) {
        if (ownedElsewhere(keyVaults, sub, rg, vaultName).isPresent()) {
            return ArmErrors.error(409, "VaultAlreadyExists", "The vault name '" + vaultName
                    + "' is already in use. Vault names are globally unique so it is possible that the name is already taken.");
        }
        Map<String, Object> body     = parseBody(req);
        String location              = bodyString(body, "location", "eastus");
        // Use vault.azure.net domain format so the azurerm provider accepts the URI.
        // Port is derived from the configured base URL so data-plane calls reach our emulator.
        String vaultUri              = vaultUri(vaultName);
        Map<String, Object> bodyProps = body.containsKey("properties")
                ? cast(body.get("properties")) : Map.of();
        String tenantId = bodyString(bodyProps, "tenantId", tenantId());

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("_sub",  sub);
        resource.put("_rg",   rg);
        resource.put("id",    "/subscriptions/" + sub + "/resourceGroups/" + rg
                + "/providers/Microsoft.KeyVault/vaults/" + vaultName);
        resource.put("name",     vaultName);
        resource.put("type",     "Microsoft.KeyVault/vaults");
        resource.put("location", location);
        resource.put("properties", Map.of(
                "vaultUri",          vaultUri,
                "tenantId",          tenantId,
                "sku",               Map.of("family", "A", "name", "standard"),
                "provisioningState", "Succeeded",
                "enableSoftDelete",  false,
                "accessPolicies",    List.of()
        ));

        keyVaults.put(kvKey(sub, rg, vaultName), resource);
        LOG.infof("ARM: created key vault %s (vaultUri=%s)", vaultName, vaultUri);
        return Response.ok(stripInternal(resource)).build();
    }

    private Response getKeyVault(String sub, String rg, String vaultName) {
        Map<String, Object> resource = keyVaults.get(kvKey(sub, rg, vaultName));
        if (resource == null) {
            return armNotFound("vaults/" + vaultName);
        }
        return Response.ok(stripInternal(resource)).build();
    }

    private synchronized Response createOrUpdateManagedHsm(AzureRequest req, String sub, String rg, String hsmName) {
        if (ownedElsewhere(managedHsms, sub, rg, hsmName).isPresent()) {
            // No public source gives the Managed HSM create-conflict code; ARM's generic Conflict stands in.
            return ArmErrors.error(409, "Conflict", "The managed HSM name '" + hsmName + "' is already in use.");
        }
        Map<String, Object> body     = parseBody(req);
        String location              = bodyString(body, "location", "eastus");
        String hsmUri                = "https://" + hsmName + ".managedhsm.azure.net/";
        Map<String, Object> bodyProps = body.containsKey("properties")
                ? cast(body.get("properties")) : Map.of();
        String tenantId = bodyString(bodyProps, "tenantId", tenantId());
        @SuppressWarnings("unchecked")
        List<String> initialAdminObjectIds = bodyProps.get("initialAdminObjectIds") instanceof List<?> l
                ? (List<String>) l : List.of();

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tenantId", tenantId);
        properties.put("initialAdminObjectIds", initialAdminObjectIds);
        properties.put("enableSoftDelete", true);
        properties.put("softDeleteRetentionInDays", 90);
        properties.put("enablePurgeProtection", true);
        properties.put("hsmUri", hsmUri);
        properties.put("provisioningState", "Succeeded");

        Map<String, Object> sku = body.get("sku") instanceof Map<?, ?> m
                ? cast(m) : Map.of();
        Map<String, Object> skuOut = Map.of(
                "family", bodyString(sku, "family", "B"),
                "name",   bodyString(sku, "name", "Standard_B1"));

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("_sub",  sub);
        resource.put("_rg",   rg);
        resource.put("id",    "/subscriptions/" + sub + "/resourceGroups/" + rg
                + "/providers/Microsoft.KeyVault/managedHSMs/" + hsmName);
        resource.put("name",     hsmName);
        resource.put("type",     "Microsoft.KeyVault/managedHSMs");
        resource.put("location", location);
        resource.put("sku",       skuOut);
        resource.put("properties", properties);

        managedHsms.put(hsmKey(sub, rg, hsmName), resource);
        LOG.infof("ARM: created managed HSM %s (hsmUri=%s)", hsmName, hsmUri);
        return Response.ok(stripInternal(resource)).build();
    }

    private Response getManagedHsm(String sub, String rg, String hsmName) {
        Map<String, Object> resource = managedHsms.get(hsmKey(sub, rg, hsmName));
        if (resource == null) {
            return armNotFound("managedHSMs/" + hsmName);
        }
        return Response.ok(stripInternal(resource)).build();
    }

    // ── Global resource names ────────────────────────────────────────────────
    // Vaults, managed HSMs and web apps are DNS names, unique across every
    // subscription. A create under a second scope is a name conflict, never a second resource.
    // Their create methods are synchronized so the ownership check and the write are one step.

    /** The resource named {@code name} in {@code store} when it belongs to a scope other than {@code sub}/{@code rg}. */
    private static Optional<Map<String, Object>> ownedElsewhere(Map<String, Map<String, Object>> store,
                                                               String sub, String rg, String name) {
        ArmScope scope = new ArmScope(sub, rg);
        return store.values().stream()
                .filter(r -> name.equalsIgnoreCase((String) r.get("name")))
                .filter(r -> !scope.owns((String) r.get("_sub"), (String) r.get("_rg")))
                .findFirst();
    }

    /** App Service answers a taken site name in its own error shape, not the ARM CloudError envelope. */
    private static Response webAppNameTaken(String appName) {
        Map<String, Object> errorEntity = new LinkedHashMap<>();
        errorEntity.put("ExtendedCode", "54001");
        errorEntity.put("MessageTemplate", "Website with given name {0} already exists.");
        errorEntity.put("Parameters", List.of(appName));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Code", "Conflict");
        body.put("Message", "Website with given name " + appName + " already exists.");
        body.put("ErrorEntity", errorEntity);
        return Response.status(409).entity(body).build();
    }

    /**
     * {@code POST .../providers/{namespace}/checkNameAvailability}. Vaults are answered from this handler's own
     * store; any other namespace routed here has no store to consult and reports the name as available. A
     * provider-lane namespace that serves its subscription-level paths (Storage) answers its own.
     */
    private Response checkNameAvailability(AzureRequest req, String path) {
        String name = bodyString(parseBody(req), "name", "");
        String namespace = ArmPaths.segmentAfter(path, "providers", "");
        boolean taken = "Microsoft.KeyVault".equalsIgnoreCase(namespace) && keyVaults.values().stream()
                .anyMatch(r -> name.equalsIgnoreCase((String) r.get("name")));
        if (!taken) {
            return Response.ok(Map.of("nameAvailable", true)).build();
        }
        return Response.ok(Map.of("nameAvailable", false, "reason", "AlreadyExists",
                "message", "The vault name '" + name + "' is already in use.")).build();
    }

    // ── Subscription ─────────────────────────────────────────────────────────

    /** {@code subscriptionId} is the spelling the body reports; the rest comes from the directory. */
    private static Map<String, Object> subscriptionBody(Subscription subscription, String subscriptionId) {
        return Map.of(
                "id",                   "/subscriptions/" + subscriptionId,
                "subscriptionId",       subscriptionId,
                "displayName",          subscription.displayName(),
                "state",                subscription.state().wireName(),
                "tenantId",             subscription.tenantId(),
                "authorizationSource",  "RoleBased",
                "managedByTenants",     List.of(),
                "subscriptionPolicies", Map.of(
                        "locationPlacementId", "Internal_2014-09-01",
                        "quotaId",             "Internal_2014-09-01",
                        "spendingLimit",       "Off")
        );
    }

    /**
     * Every tenant reports {@code Home}: Azure lists every tenant the identity can reach, every tenant is reachable
     * in the emulator, and no local source says which category a guest tenant gets.
     */
    private static Map<String, Object> tenantBody(Tenant tenant) {
        return Map.of(
                "id",                "/tenants/" + tenant.id(),
                "tenantId",          tenant.id(),
                "displayName",       tenant.displayName(),
                "tenantCategory",    "Home",
                "defaultDomain",     tenant.defaultDomain(),
                "domains",           List.of(tenant.defaultDomain()),
                "tenantType",        "AAD"
        );
    }

    private String tenantId() {
        return directory.defaultTenantId();
    }

    /**
     * The subscriptions {@code GET /subscriptions} lists: the caller's tenant's, first-use ones only under
     * subscription-listing. Under tenant-scoping a caller whose tenant owns none sees the default subscription, so a
     * client logged in to that tenant ({@code az login --tenant}) still finds one to use; without tenant-scoping the
     * caller is always the default tenant, which owns it.
     */
    private List<Subscription> callerSubscriptions(AzureRequest req) {
        boolean listFirstUse = config.subscriptionListing();
        List<Subscription> owned = directory.subscriptionsOfTenant(callerTenantId(req)).stream()
                .filter(subscription -> listFirstUse || subscription.origin() != DirectoryOrigin.FIRST_USE)
                .toList();
        if (!owned.isEmpty() || !config.tenantScoping()) {
            return owned;
        }
        return directory.subscription(directory.defaultSubscriptionId()).stream().toList();
    }

    /** The caller's tenant: its token's, when the request carries one the directory holds, else the default. */
    private String callerTenantId(AzureRequest req) {
        String tokenTenantId = req.scope() == null ? null : req.scope().tokenTenantId();
        return directory.tenant(tokenTenantId).map(Tenant::id).orElse(directory.defaultTenantId());
    }

    @Override
    public void clear() {
        keyVaults.clear();
        managedHsms.clear();
        webApps.clear();
    }

    /**
     * {@code subscriptions/{sub}/providers}, {@code .../providers/{namespace}} and its {@code register} and
     * {@code unregister} actions, served by {@link ProviderService}; null for any other path.
     */
    private Response providers(AzureRequest req, String path, String method) {
        boolean registration = ArmPaths.isProviderRegistration(path);
        if (!registration && !path.matches("subscriptions/[^/?]+/providers(/[^/?]+)?([?].*)?")) {
            return null;
        }
        ArmResourceId id = ArmResourceId.parse(path).orElseThrow();
        String sub = id.subscriptionId();
        if (registration) {
            if (!"POST".equals(method)) {
                return Response.status(405).build();
            }
            return id.types().getFirst().equalsIgnoreCase("register")
                ? providerService.register(cloud(req), sub, id.providerNamespace())
                : providerService.unregister(cloud(req), sub, id.providerNamespace());
        }
        if (!"GET".equals(method)) {
            return Response.status(405).build();
        }
        return id.providerNamespace() == null ? providerService.list(cloud(req), sub)
            : providerService.get(cloud(req), sub, id.providerNamespace());
    }

    /** The cloud the request's subscription belongs to, as the routing filter resolved it; public without a scope. */
    private static CloudProfile cloud(AzureRequest req) {
        return req.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : req.scope().cloud();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String extractSub(String path) {
        return ArmPaths.subscription(path, "unknown");
    }

    private static String extractRg(String path) {
        return ArmPaths.resourceGroup(path, "unknown");
    }

    private static String extractResourceName(String path, String resourceType) {
        return ArmPaths.resourceName(path, resourceType, "unknown");
    }

    private static String extractAfter(String path, String marker) {
        return ArmPaths.afterSegment(path, marker, "unknown");
    }

    private static String kvKey(String sub, String rg, String name)  { return sub + "/" + rg + "/kv/" + name; }
    private static String hsmKey(String sub, String rg, String name) { return sub + "/" + rg + "/hsm/" + name; }
    private static String webAppKey(String sub, String rg, String name) { return sub + "/" + rg + "/web/" + name; }

    private static Map<String, Object> cast(Object o) {
        return ArmJson.cast(o);
    }

    private static String bodyString(Map<String, Object> map, String key, String defaultValue) {
        return ArmJson.string(map, key, defaultValue);
    }

    private Map<String, Object> parseBody(AzureRequest req) {
        return ArmJson.parseBodyLenient(req);
    }

    private static Map<String, Object> stripInternal(Map<String, Object> resource) {
        return ArmResources.stripInternal(resource);
    }

    /**
     * Returns the account name to use for ARM-base-URL key vault data-plane fallback.
     * When there is exactly one vault registered, returns its name so that KV data-plane
     * responses carry the correct vault.azure.net URI and the provider's vault cache lookup
     * succeeds. Falls back to "kv-default" when zero or multiple vaults are present.
     */
    public String getDefaultKvAccount() {
        if (keyVaults.size() == 1) {
            Object name = keyVaults.values().iterator().next().get("name");
            if (name instanceof String s) return s;
        }
        return "kv-default";
    }

    private String vaultUri(String vaultName) {
        // Use vault.azure.net domain format on standard port 443 (no explicit port).
        // azurerm validates the URI matches *.vault.* pattern. In the test container a
        // socat listener on 443 forwards to the emulator so the azurerm provider can
        // reach the vault; host-based routing then dispatches to KeyVaultHandler.
        return "https://" + vaultName + ".vault.azure.net/";
    }

    private Response armNotFound(String path) {
        return ArmErrors.notFound("Resource not found: " + path);
    }
}
