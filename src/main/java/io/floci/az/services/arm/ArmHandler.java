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
import io.floci.az.services.blob.BlobServiceHandler;
import io.floci.az.services.managedidentity.ManagedIdentityHandler;
import io.floci.az.services.monitor.activitylog.ActivityLogEndpoint;
import io.floci.az.services.network.NetworkHandler;
import io.floci.az.services.queue.QueueServiceHandler;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static io.floci.az.core.auth.StorageSasAuthorization.DEFAULT_STORAGE_ACCOUNT_KEY;

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
 *   <li>Storage Accounts — ARM shell that bridges container/queue creation to the
 *       existing Blob and Queue storage backends</li>
 * </ul>
 */
@ApplicationScoped
public class ArmHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(ArmHandler.class);

    // In-memory ARM resource state — no StorageBackend needed, these are ephemeral
    private final Map<String, Map<String, Object>> storageAccounts  = new ConcurrentHashMap<>();
    private final EmulatorConfig config;
    private final BlobServiceHandler blobHandler;
    private final QueueServiceHandler queueHandler;
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
    public ArmHandler(EmulatorConfig config, BlobServiceHandler blobHandler, QueueServiceHandler queueHandler,
                      ApiManagementHandler apiManagementHandler,
                      NetworkHandler networkHandler, ManagedIdentityHandler managedIdentityHandler,
                      Instance<ArmProviderService> providerServices,
                      Instance<ResourceIndexContributor> resourceIndexContributors, ArmRequestGate gate,
                      AzureDirectory directory, ResourceGroupService resourceGroups,
                      ProviderService providerService) {
        this.config               = config;
        this.blobHandler          = blobHandler;
        this.queueHandler         = queueHandler;
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
        // az CLI probes this before creating storage accounts. ARM paths are case-insensitive, so the
        // operation matches in any casing.
        if (path.toLowerCase(Locale.ROOT)
                .matches("subscriptions/[^/]+/providers/microsoft\\.[^/]+/checknameavailability([?].*)?")) {
            return checkNameAvailability(req, path);
        }

        // ── Cross-subscription storage account listing ─────────────────────────
        // azurerm provider calls this during state refresh/destroy to verify that
        // storage accounts still exist. Return real data so destroy plans correctly.
        if (path.matches("subscriptions/[^/?]+/providers/(?i:Microsoft\\.Storage)/storageAccounts([?].*)?")) {
            String sub = extractSub(path);
            List<Map<String, Object>> accounts = storageAccounts.values().stream()
                    .filter(a -> sub.equals(a.get("_sub")))
                    .map(ArmHandler::stripInternal)
                    .toList();
            return Response.ok(Map.of("value", accounts)).build();
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
     * <p>ArmHandler's own Storage state (and the Key Vault and Web contributors) contributes the full stored
     * body rather than the trimmed {@code ArmResources.indexEntry} shape: the azurerm provider reads this
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
        collectOwn(storageAccounts, sub, rg, resources);
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
        // The inline ARM shell that bridges to the Storage data plane, coupled to it, so it stays here.
        if (ArmPaths.hasProvider(path, "Microsoft.Storage")) {
            return handleStorage(req, path, method, sub);
        }
        // Delegating providers (Key Vault/Web/APIM/Monitor/EventGrid), via the ArmProviderService lane.
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

    // ── Storage Accounts ─────────────────────────────────────────────────────

    private Response handleStorage(AzureRequest req, String path, String method, String sub) {
        String rg = extractRg(path);

        // Containers, queues and keys reach the shared data plane, which knows only the account name.
        // Under a scope that does not own the account they must stop here, before touching the owner's data.
        if (path.contains("/storageAccounts/") && !path.matches(".*/storageAccounts/[^/?]+([?].*)?")) {
            String account = extractResourceName(path, "storageAccounts");
            if (ownedElsewhere(storageAccounts, sub, rg, account).isPresent()) {
                return armNotFound("storageAccounts/" + account);
            }
        }

        // POST .../storageAccounts/{name}/listKeys
        if (path.contains("/storageAccounts/") && path.endsWith("/listKeys")) {
            String account = extractResourceName(path, "storageAccounts");
            return listKeys(account);
        }

        // .../storageAccounts/{name}/blobServices/default/containers/{container}
        if (path.contains("/blobServices/default/containers/")) {
            return handleBlobContainer(req, path, method, sub, rg);
        }

        // .../storageAccounts/{name}/blobServices/default  (blobServices properties)
        if (path.contains("/blobServices/default") && !path.contains("/containers/")) {
            return Response.ok(Map.of("id", path, "name", "default",
                    "type", "Microsoft.Storage/storageAccounts/blobServices",
                    "properties", Map.of())).build();
        }

        // .../storageAccounts/{name}/queueServices/default/queues/{queue}
        if (path.contains("/queueServices/default/queues/")) {
            return handleQueueResource(req, path, method, sub, rg);
        }

        // .../storageAccounts/{name}/queueServices/default  (queueServices properties)
        if (path.contains("/queueServices/default") && !path.contains("/queues/")) {
            return Response.ok(Map.of("id", path, "name", "default",
                    "type", "Microsoft.Storage/storageAccounts/queueServices",
                    "properties", Map.of())).build();
        }

        // GET .../storageAccounts  (list in RG)
        if (path.matches(".*/providers/(?i:Microsoft\\.Storage)/storageAccounts([?].*)?")) {
            List<Map<String, Object>> accounts = storageAccounts.values().stream()
                    .filter(a -> sub.equals(a.get("_sub")) && rg.equals(a.get("_rg")))
                    .map(ArmHandler::stripInternal)
                    .toList();
            return Response.ok(Map.of("value", accounts)).build();
        }

        // PUT/GET/DELETE .../storageAccounts/{name}
        if (path.contains("/storageAccounts/")) {
            String account = extractResourceName(path, "storageAccounts");
            return switch (method) {
                case "PUT"    -> createOrUpdateStorageAccount(req, sub, rg, account);
                case "PATCH"  -> updateStorageAccount(req, sub, rg, account);
                case "GET"    -> getStorageAccount(sub, rg, account);
                case "DELETE" -> {
                    synchronized (this) {
                        if (storageAccounts.remove(saKey(sub, rg, account)) != null) {
                            blobHandler.removeHierarchicalNamespaceOverride(account);
                        }
                    }
                    yield Response.ok().build();
                }
                default       -> Response.status(405).build();
            };
        }

        return armNotFound(path);
    }

    private synchronized Response createOrUpdateStorageAccount(AzureRequest req, String sub, String rg, String account) {
        if (!account.matches("[a-z0-9]{3,24}")) {
            return ArmErrors.error(400, "AccountNameInvalid", account
                    + " is not a valid storage account name. Storage account name must be between 3 and 24 "
                    + "characters in length and use numbers and lower-case letters only.");
        }
        Optional<Map<String, Object>> owner = ownedElsewhere(storageAccounts, sub, rg, account);
        if (owner.isPresent()) {
            return sub.equalsIgnoreCase((String) owner.get().get("_sub"))
                    ? ArmErrors.error(409, "StorageAccountInAnotherResourceGroup",
                            "The account " + account + " is already in another resource group in this subscription.")
                    : ArmErrors.error(409, "StorageAccountAlreadyTaken",
                            "The storage account named " + account + " is already taken.");
        }
        Map<String, Object> body = parseBody(req);
        String location = bodyString(body, "location", "eastus");
        Map<String, Object> requestedProperties = cast(body.get("properties"));
        Map<String, Object> existing = storageAccounts.get(saKey(sub, rg, account));
        Map<String, Object> existingProperties = cast(existing == null ? null : existing.get("properties"));
        boolean existingHnsEnabled = Boolean.TRUE.equals(existingProperties.get("isHnsEnabled"));
        if (existing != null && requestedProperties.containsKey("isHnsEnabled")
                && existingHnsEnabled != Boolean.TRUE.equals(requestedProperties.get("isHnsEnabled"))) {
            return ArmErrors.error(400, "AccountPropertyCannotBeUpdated",
                    "The property 'isHnsEnabled' was specified in the input, but it cannot be updated as it is "
                            + "read-only. For more information, see - https://aka.ms/storageaccountupdate");
        }
        boolean hnsEnabled = requestedProperties.containsKey("isHnsEnabled")
                ? Boolean.TRUE.equals(requestedProperties.get("isHnsEnabled"))
                : existingProperties.containsKey("isHnsEnabled")
                        ? existingHnsEnabled
                        : config.services().blob().hierarchicalNamespaceAccounts().contains(account);
        // Return domain-based storage endpoints so the azurerm provider can parse the account name.
        // The port is taken from the configured base URL so data-plane requests reach our emulator.
        String portStr = storagePortString();

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("_sub", sub);
        resource.put("_rg", rg);
        resource.put("id", "/subscriptions/" + sub + "/resourceGroups/" + rg
                + "/providers/Microsoft.Storage/storageAccounts/" + account);
        resource.put("name", account);
        resource.put("type", "Microsoft.Storage/storageAccounts");
        resource.put("location", location);
        Map<String, Object> tags = tags(body.get("tags"));
        if (tags != null) {
            resource.put("tags", tags);
        }
        resource.put("sku", Map.of("name", "Standard_LRS", "tier", "Standard"));
        resource.put("kind", "StorageV2");
        resource.put("properties", Map.of(
                "provisioningState", "Succeeded",
                "isHnsEnabled", hnsEnabled,
                "primaryEndpoints", Map.of(
                        "blob",  "http://" + account + ".blob.core.windows.net" + portStr + "/",
                        "dfs",   "http://" + account + ".dfs.core.windows.net" + portStr + "/",
                        "queue", "http://" + account + ".queue.core.windows.net" + portStr + "/",
                        "table", "http://" + account + ".table.core.windows.net" + portStr + "/"),
                "primaryLocation", location,
                "statusOfPrimary", "available",
                "supportsHttpsTrafficOnly", false,
                "accessTier", "Hot",
                "encryption", Map.of(
                        "services", Map.of(
                                "blob",  Map.of("enabled", true, "keyType", "Account"),
                                "file",  Map.of("enabled", true, "keyType", "Account"),
                                "queue", Map.of("enabled", true, "keyType", "Account"),
                                "table", Map.of("enabled", true, "keyType", "Account")),
                        "keySource", "Microsoft.Storage")
        ));

        storageAccounts.put(saKey(sub, rg, account), resource);
        blobHandler.setHierarchicalNamespaceEnabled(account, hnsEnabled);
        LOG.infof("ARM: created storage account %s", account);
        return Response.ok(stripInternal(resource)).build();
    }

    /**
     * {@code StorageAccounts_Update}: the {@code tags} the body names replace the account's, as azurerm updates them;
     * nothing else in the body is applied yet.
     */
    private synchronized Response updateStorageAccount(AzureRequest req, String sub, String rg, String account) {
        Map<String, Object> resource = storageAccounts.get(saKey(sub, rg, account));
        if (resource == null) {
            return armNotFound("storageAccounts/" + account);
        }
        Map<String, Object> tags = tags(parseBody(req).get("tags"));
        if (tags != null) {
            Map<String, Object> updated = new LinkedHashMap<>();
            resource.forEach((key, value) -> {
                if (!key.equals("tags")) {
                    updated.put(key, value);
                }
                if (key.equals("location")) {
                    updated.put("tags", tags);
                }
            });
            storageAccounts.put(saKey(sub, rg, account), updated);
            resource = updated;
        }
        return Response.ok(stripInternal(resource)).build();
    }

    /** A {@code tags} object as sent, or null when the body names none, so an account without tags omits them. */
    private static Map<String, Object> tags(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> tags = new LinkedHashMap<>();
        map.forEach((key, value) -> tags.put(String.valueOf(key), value));
        return tags;
    }

    private Response getStorageAccount(String sub, String rg, String account) {
        Map<String, Object> resource = storageAccounts.get(saKey(sub, rg, account));
        if (resource == null) {
            return armNotFound("storageAccounts/" + account);
        }
        return Response.ok(stripInternal(resource)).build();
    }

    private Response listKeys(String account) {
        // The key the Storage data plane verifies this account's Shared Key SAS with, so a SAS
        // built from either listed key is accepted.
        String key = config.auth().storageAccountKeys().getOrDefault(account, DEFAULT_STORAGE_ACCOUNT_KEY);
        return Response.ok(Map.of("keys", List.of(
                Map.of("keyName", "key1", "value", key, "permissions", "Full"),
                Map.of("keyName", "key2", "value", key, "permissions", "Full")
        ))).build();
    }

    // ── Blob Container (via ARM) ──────────────────────────────────────────────

    private Response handleBlobContainer(AzureRequest req, String path, String method, String sub, String rg) {
        String account   = extractResourceName(path, "storageAccounts");
        String container = extractAfter(path, "/containers/");

        return switch (method) {
            case "PUT" -> {
                blobHandler.ensureContainer(account, container);
                Map<String, Object> body = parseBody(req);
                Map<String, Object> props = Map.of(
                        "publicAccess",      "None",
                        "leaseStatus",       "Unlocked",
                        "leaseState",        "Available",
                        "lastModifiedTime",  Instant.now().toString(),
                        "etag",              "\"" + UUID.randomUUID() + "\"");
                yield Response.status(201).entity(Map.of(
                        "id",         path,
                        "name",       container,
                        "type",       "Microsoft.Storage/storageAccounts/blobServices/containers",
                        "properties", props
                )).build();
            }
            case "GET" -> blobHandler.containerExists(account, container)
                    ? Response.ok(Map.of(
                            "id",         path,
                            "name",       container,
                            "type",       "Microsoft.Storage/storageAccounts/blobServices/containers",
                            "properties", Map.of("publicAccess", "None")
                    )).build()
                    : ArmErrors.error(404, "ContainerNotFound", "The specified container does not exist.");
            // BlobContainers_Delete: 200 when it deleted the container, 204 when there was none.
            case "DELETE" -> Response.status(blobHandler.deleteContainer(account, container) ? 200 : 204).build();
            default -> Response.status(405).build();
        };
    }

    // ── Queue Resource (via ARM) ──────────────────────────────────────────────

    private Response handleQueueResource(AzureRequest req, String path, String method, String sub, String rg) {
        String account = extractResourceName(path, "storageAccounts");
        String queue   = extractAfter(path, "/queues/");

        return switch (method) {
            case "PUT" -> {
                queueHandler.ensureQueue(account, queue);
                // Queue_Create declares 200 only, for a new queue and an existing one alike.
                yield Response.ok(Map.of(
                        "id",         path,
                        "name",       queue,
                        "type",       "Microsoft.Storage/storageAccounts/queueServices/queues",
                        "properties", Map.of()
                )).build();
            }
            case "GET" -> queueHandler.queueExists(account, queue)
                    ? Response.ok(Map.of(
                            "id",         path,
                            "name",       queue,
                            "type",       "Microsoft.Storage/storageAccounts/queueServices/queues",
                            "properties", Map.of()
                    )).build()
                    : ArmErrors.error(404, "QueueNotFound", "The specified queue does not exist.");
            // Queue_Delete declares 204 only, whether or not the queue existed.
            case "DELETE" -> {
                queueHandler.deleteQueue(account, queue);
                yield Response.noContent().build();
            }
            default -> Response.status(405).build();
        };
    }

    // ── Global resource names ────────────────────────────────────────────────
    // Storage accounts are DNS names, unique across every subscription. A create under a second scope is a name conflict, never a second resource.
    // Its create method is synchronized so the ownership check and the write are one step.

    /** The resource named {@code name} in {@code store} when it belongs to a scope other than {@code sub}/{@code rg}. */
    private static Optional<Map<String, Object>> ownedElsewhere(Map<String, Map<String, Object>> store,
                                                               String sub, String rg, String name) {
        ArmScope scope = new ArmScope(sub, rg);
        return store.values().stream()
                .filter(r -> name.equalsIgnoreCase((String) r.get("name")))
                .filter(r -> !scope.owns((String) r.get("_sub"), (String) r.get("_rg")))
                .findFirst();
    }

    /**
     * {@code POST .../providers/{namespace}/checkNameAvailability}. Storage accounts are answered from this
     * handler's own store; any other namespace routed here has no store to consult and reports the name as
     * available. A provider-lane namespace that serves its subscription-level paths (Key Vault, Web) answers its
     * own.
     */
    private Response checkNameAvailability(AzureRequest req, String path) {
        String name = bodyString(parseBody(req), "name", "");
        String namespace = ArmPaths.segmentAfter(path, "providers", "");
        boolean storage = "Microsoft.Storage".equalsIgnoreCase(namespace);
        if (storage && !name.matches("[a-z0-9]{3,24}")) {
            return Response.ok(Map.of(
                    "nameAvailable", false,
                    "reason", "AccountNameInvalid",
                    "message", name + " is not a valid storage account name. Storage account name must be between "
                            + "3 and 24 characters in length and use numbers and lower-case letters only.")).build();
        }
        Map<String, Map<String, Object>> store = storage ? storageAccounts : null;
        boolean taken = store != null && store.values().stream()
                .anyMatch(r -> name.equalsIgnoreCase((String) r.get("name")));
        if (!taken) {
            return Response.ok(Map.of("nameAvailable", true)).build();
        }
        return Response.ok(Map.of("nameAvailable", false, "reason", "AlreadyExists",
                "message", "The storage account named " + name + " is already taken.")).build();
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

    @Override
    public void clear() {
        storageAccounts.clear();
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

    private static String saKey(String sub, String rg, String name)  { return sub + "/" + rg + "/" + name; }

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

    private String storagePortString() {
        // Return no port so storage URLs use standard HTTP port 80.
        // Test containers forward port 80 → emulator via socat.
        return "";
    }

    private Response armNotFound(String path) {
        return ArmErrors.notFound("Resource not found: " + path);
    }
}
