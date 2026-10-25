package io.floci.az.services.arm;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmProviderService;
import io.floci.az.core.arm.ArmRequestGate;
import io.floci.az.core.arm.ArmResourceFilter;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ResourceIndexContributor;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.DirectoryOrigin;
import io.floci.az.core.directory.Subscription;
import io.floci.az.core.directory.Tenant;
import io.floci.az.services.apim.ApiManagementHandler;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * </ul>
 */
@ApplicationScoped
public class ArmHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(ArmHandler.class);

    private final EmulatorConfig config;
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
    public ArmHandler(EmulatorConfig config, ApiManagementHandler apiManagementHandler,
                      NetworkHandler networkHandler, ManagedIdentityHandler managedIdentityHandler,
                      Instance<ArmProviderService> providerServices,
                      Instance<ResourceIndexContributor> resourceIndexContributors, ArmRequestGate gate,
                      AzureDirectory directory, ResourceGroupService resourceGroups,
                      ProviderService providerService) {
        this.config               = config;
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
        // For a namespace no provider service answers itself. ARM paths are case-insensitive, so the
        // operation matches in any casing.
        if (path.toLowerCase(Locale.ROOT)
                .matches("subscriptions/[^/]+/providers/microsoft\\.[^/]+/checknameavailability([?].*)?")) {
            return checkNameAvailability();
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
     * <p>The Key Vault, Web and Storage contributors contribute the full stored body rather than the trimmed {@code ArmResources.indexEntry} shape: the azurerm provider reads this
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

    // ── Provider-level routing ────────────────────────────────────────────────

    private Response handleProviders(AzureRequest req, String path, String method, String sub) {
        // Delegating providers (Key Vault/Web/Storage/APIM/Monitor/EventGrid), via the ArmProviderService lane.
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
        ArmProviderService provider = providerLane(providerNamespace(path));
        return provider != null && provider.servesSubscriptionPaths() ? provider : null;
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

    /**
     * {@code POST .../providers/{namespace}/checkNameAvailability} for a namespace no provider service answers
     * itself: there is no store to consult, so the name is reported as available. A provider-lane namespace that
     * serves its subscription-level paths (Key Vault, Web, Storage) answers its own.
     */
    private static Response checkNameAvailability() {
        return Response.ok(Map.of("nameAvailable", true)).build();
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

    private static String extractAfter(String path, String marker) {
        return ArmPaths.afterSegment(path, marker, "unknown");
    }

    private static Map<String, Object> stripInternal(Map<String, Object> resource) {
        return ArmResources.stripInternal(resource);
    }

    private Response armNotFound(String path) {
        return ArmErrors.notFound("Resource not found: " + path);
    }
}
