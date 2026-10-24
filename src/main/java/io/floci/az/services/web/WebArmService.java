package io.floci.az.services.web;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.Resettable;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmProviderService;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmResourceStore.Stored;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.arm.GlobalNameSource;
import io.floci.az.core.arm.ResourceIndexContributor;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.functions.FunctionRuntime;
import io.floci.az.services.functions.FunctionsServiceHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Microsoft.Web's management plane: sites and their {@code config/web}, ARM shells that provision a Function App in
 * {@link FunctionsServiceHandler} when the site carries a {@code linuxFxVersion}. Reached through {@code ArmHandler}'s
 * provider lane, inside the ARM request gate, which owns each site name ({@link WebArmSurface}) before a PUT gets here.
 *
 * <p>Sites are one {@link ArmResourceStore} of the ARM documents responses return, over the {@value #STORE} backend,
 * keyed by canonical id, so a path that differs only in case addresses the one site and the casing of its first PUT
 * is what responses echo. The store follows the global storage mode. Sites were kept in memory before, so there are
 * no older entries to read.
 */
@ApplicationScoped
public class WebArmService implements ArmProviderService, ResourceIndexContributor, GlobalNameSource, Resettable {

    private static final Logger LOG = Logger.getLogger(WebArmService.class);

    /** The {@link StorageFactory} backend sites live in. */
    static final String STORE = "arm-web";

    private static final String NAMESPACE = "Microsoft.Web";
    static final String SITE_TYPE = NAMESPACE + "/sites";
    private static final String CONFIG_TYPE = NAMESPACE + "/sites/config";

    /** The Functions data-plane account a site's Function App is provisioned under. */
    private static final String DEFAULT_FUNCTIONS_ACCOUNT = "devstoreaccount1";

    /** The {@code CheckNameResourceTypes} values that name a site; the others name nothing floci-az stores. */
    private static final Set<String> SITE_NAME_TYPES = Set.of("site", "microsoft.web/sites");

    private final ArmResourceStore<Map<String, Object>> sites;
    private final GlobalNameRegistry names;
    private final FunctionsServiceHandler functionsHandler;

    @Inject
    public WebArmService(StorageFactory storageFactory, GlobalNameRegistry names,
                         FunctionsServiceHandler functionsHandler) {
        this.sites = ArmResourceStore.create(storageFactory, STORE, SITE_TYPE, documentType(), WebArmService::idOf,
            key -> false);
        this.names = names;
        this.functionsHandler = functionsHandler;
    }

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> documentType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private static String idOf(Map<String, Object> document) {
        return String.valueOf(document.get("id"));
    }

    @Override
    public Set<String> providerNamespaces() {
        return Set.of(NAMESPACE);
    }

    @Override
    public boolean servesSubscriptionPaths() {
        return true;
    }

    @Override
    public Response handleArm(AzureRequest req, String path, String method, String sub) {
        Optional<String> rg = ArmPaths.resourceGroup(path);
        return rg.isPresent()
            ? handleResourceGroupPath(req, path, method, sub, rg.get())
            : handleSubscriptionPath(req, path, sub);
    }

    // ── Subscription level ──────────────────────────────────────────────────

    private Response handleSubscriptionPath(AzureRequest req, String path, String sub) {
        if (path.matches("subscriptions/[^/?]+/providers/(?i:Microsoft\\.Web)/sites([?].*)?")) {
            return list(new ArmScope(sub, null));
        }
        // ARM paths are case-insensitive; azurerm sends checknameavailability in lower case.
        if (path.toLowerCase(Locale.ROOT)
                .matches("subscriptions/[^/]+/providers/microsoft\\.web/checknameavailability([?].*)?")) {
            return checkNameAvailability(req);
        }
        return armNotFound(path);
    }

    /**
     * {@code CheckNameAvailability}: whether a site name is owned, answered from the name registry, in the
     * {@code ResourceNameAvailability} shape. Only a site's name ({@code Site}, {@code Microsoft.Web/sites}) is
     * looked up; any other type names nothing floci-az stores and is reported available, as before.
     */
    private Response checkNameAvailability(AzureRequest req) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        String name = ArmJson.string(body, "name", "");
        String type = ArmJson.string(body, "type", SITE_TYPE);
        boolean site = SITE_NAME_TYPES.contains(type.toLowerCase(Locale.ROOT));
        if (!site || names.owner(cloud(req), CloudSuffix.WEB, name).isEmpty()) {
            return Response.ok(Map.of("nameAvailable", true)).build();
        }
        return Response.ok(Map.of("nameAvailable", false, "reason", "AlreadyExists",
            "message", String.format(WebArmSurface.SITE_NAME_TAKEN, name))).build();
    }

    // ── Resource group level ────────────────────────────────────────────────

    private Response handleResourceGroupPath(AzureRequest req, String path, String method, String sub, String rg) {
        if (path.matches(".*/providers/(?i:Microsoft\\.Web)/sites([?].*)?")) {
            return list(new ArmScope(sub, rg));
        }
        if (path.contains("/sites/")) {
            String siteName = ArmPaths.resourceName(path, "sites", "unknown");
            ArmResourceId id = siteId(sub, rg, siteName);
            if (path.contains("/config/web")) {
                return handleConfig(req, path, method, id);
            }
            return switch (method) {
                case "PUT"    -> createOrUpdateSite(req, id);
                case "GET"    -> getSite(id);
                case "DELETE" -> {
                    sites.delete(id);
                    yield Response.ok().build();
                }
                default       -> Response.status(405).build();
            };
        }
        return armNotFound(path);
    }

    private static ArmResourceId siteId(String sub, String rg, String siteName) {
        return ArmResourceId.parse("/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/" + SITE_TYPE + "/"
            + siteName).orElseThrow();
    }

    private Response list(ArmScope scope) {
        return Response.ok(Map.of("value", documents(scope))).build();
    }

    private List<Map<String, Object>> documents(ArmScope scope) {
        return sites.list(scope).stream().map(Stored::value).toList();
    }

    private Response getSite(ArmResourceId id) {
        return sites.get(id)
            .map(stored -> Response.ok(stored.value()).build())
            .orElseGet(() -> armNotFound("sites/" + id.name()));
    }

    /**
     * The name is reserved in the {@link GlobalNameRegistry} by the gate before this runs, so a name another resource
     * owns never gets here. A re-PUT keeps the id and name the site was created with, and provisions the Function
     * App under that name. A {@code linuxFxVersion} the
     * Functions runtime does not know is refused before anything is written.
     */
    private Response createOrUpdateSite(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        Map<String, Object> properties = ArmJson.cast(body.get("properties"));
        Map<String, Object> siteConfig = ArmJson.cast(properties.get("siteConfig"));
        String linuxFxVersion = ArmJson.string(siteConfig, "linuxFxVersion", null);
        String location = ArmJson.string(body, "location", "eastus");
        String siteName = sites.get(id).map(stored -> nameOf(stored.value())).orElse(id.name());
        try {
            syncFunctionApp(siteName, linuxFxVersion);
        } catch (IllegalArgumentException e) {
            return invalidLinuxFxVersion(e);
        }
        Map<String, Object> written = sites.createOrUpdate(id, existing -> siteDocument(
            existing.map(WebArmService::idOf).orElse(id.id()),
            existing.map(WebArmService::nameOf).orElse(id.name()), location, linuxFxVersion)).stored().value();
        LOG.infov("ARM: created site {0}", written.get("name"));
        return Response.ok(written).build();
    }

    /** {@code sites/{name}/config/web}: the site's {@code siteConfig}, read or replaced. */
    private Response handleConfig(AzureRequest req, String path, String method, ArmResourceId id) {
        Optional<Stored<Map<String, Object>>> site = sites.get(id);
        if (site.isEmpty()) {
            return armNotFound("sites/" + id.name());
        }
        if ("GET".equals(method)) {
            return configResponse(path, site.get().value());
        }
        if (!"PUT".equals(method)) {
            return Response.status(405).build();
        }

        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        Map<String, Object> config = body.containsKey("properties") ? ArmJson.cast(body.get("properties")) : body;
        String linuxFxVersion = ArmJson.string(config, "linuxFxVersion", null);
        try {
            syncFunctionApp(nameOf(site.get().value()), linuxFxVersion);
        } catch (IllegalArgumentException e) {
            return invalidLinuxFxVersion(e);
        }
        Optional<Stored<Map<String, Object>>> updated = sites.update(id, existing -> siteDocument(idOf(existing),
            nameOf(existing), String.valueOf(existing.get("location")), linuxFxVersion));
        return updated
            .map(stored -> configResponse(path, stored.value()))
            .orElseGet(() -> armNotFound("sites/" + id.name()));
    }

    private static Response configResponse(String path, Map<String, Object> site) {
        return Response.ok(Map.of("id", path, "name", "web",
            "type", CONFIG_TYPE,
            "properties", ArmJson.cast(site.get("properties")).get("siteConfig"))).build();
    }

    private static Response invalidLinuxFxVersion(IllegalArgumentException e) {
        return Response.status(400).entity(Map.of("error", Map.of(
            "code", "InvalidLinuxFxVersion", "message", e.getMessage()))).build();
    }

    private void syncFunctionApp(String siteName, String linuxFxVersion) {
        if (linuxFxVersion == null || linuxFxVersion.isBlank()) {
            return;
        }
        String runtime = FunctionRuntime.runtimeFromLinuxFxVersion(linuxFxVersion);
        functionsHandler.upsertApp(DEFAULT_FUNCTIONS_ACCOUNT, siteName, runtime, linuxFxVersion, null);
    }

    private static Map<String, Object> siteDocument(String id, String siteName, String location,
                                                    String linuxFxVersion) {
        Map<String, Object> siteConfig = new LinkedHashMap<>();
        if (linuxFxVersion != null) {
            siteConfig.put("linuxFxVersion", linuxFxVersion);
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("state", "Running");
        properties.put("siteConfig", siteConfig);

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", id);
        resource.put("name", siteName);
        resource.put("type", SITE_TYPE);
        resource.put("location", location);
        resource.put("kind", "functionapp,linux");
        resource.put("properties", properties);
        return resource;
    }

    private static String nameOf(Map<String, Object> document) {
        return String.valueOf(document.get("name"));
    }

    // ── ResourceIndexContributor ────────────────────────────────────────────

    /** The full documents, as ArmHandler listed its own sites before. */
    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        return documents(new ArmScope(sub, rg));
    }

    @Override
    public List<Map<String, Object>> listSubscriptionResources(String sub) {
        return documents(new ArmScope(sub, null));
    }

    // ── GlobalNameSource ────────────────────────────────────────────────────

    @Override
    public List<Claim> globalNames() {
        return sites.all().stream()
            .map(stored -> new Claim(CloudSuffix.WEB, nameOf(stored.value()), stored.id()))
            .toList();
    }

    @Override
    public void clear() {
        sites.clear();
    }

    /** The cloud the request's subscription belongs to, as the routing filter resolved it; public without a scope. */
    private static CloudProfile cloud(AzureRequest req) {
        return req.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : req.scope().cloud();
    }

    private static Response armNotFound(String path) {
        return ArmErrors.notFound("Resource not found: " + path);
    }
}
