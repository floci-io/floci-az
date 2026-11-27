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
import io.floci.az.core.arm.StoreIndexSource;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudDefaults;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.functions.FunctionRuntime;
import io.floci.az.services.functions.FunctionsServiceHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Microsoft.Web's management plane: sites and their {@code config/web}, ARM shells that provision a Function App in
 * {@link FunctionsServiceHandler} when the site carries a {@code linuxFxVersion}, the site's other
 * {@code config/{name}} documents (app settings, storage mounts, logs and the rest), and App Service plans
 * ({@code serverFarms}), the last two stored ARM documents with no runtime behind them. Reached through
 * {@code ArmHandler}'s provider lane, inside the ARM request gate, which owns each site name ({@link WebArmSurface})
 * before a PUT gets here.
 *
 * <p>Sites are one {@link ArmResourceStore} of the ARM documents responses return, over the {@value #STORE} backend,
 * keyed by canonical id, so a path that differs only in case addresses the one site and the casing of its first PUT
 * is what responses echo. The store follows the global storage mode. Sites were kept in memory before, so there are
 * no older entries to read.
 */
@ApplicationScoped
public class WebArmService implements ArmProviderService, StoreIndexSource, GlobalNameSource, Resettable {

    private static final Logger LOG = Logger.getLogger(WebArmService.class);

    /** The {@link StorageFactory} backend sites live in. */
    static final String STORE = "arm-web";

    private static final String NAMESPACE = "Microsoft.Web";
    static final String SITE_TYPE = NAMESPACE + "/sites";
    private static final String CONFIG_TYPE = NAMESPACE + "/sites/config";
    static final String SERVER_FARM_TYPE = NAMESPACE + "/serverFarms";

    /** The Functions data-plane account a site's Function App is provisioned under. */
    private static final String DEFAULT_FUNCTIONS_ACCOUNT = "devstoreaccount1";

    /** The {@code CheckNameResourceTypes} values that name a site; the others name nothing floci-az stores. */
    private static final Set<String> SITE_NAME_TYPES = Set.of("site", "microsoft.web/sites");

    /**
     * A site's own path, or its {@code config/web}, namespace and segments in any case: group 1 the site name, group 2
     * set for {@code config/web}.
     */
    private static final Pattern SITE_PATH = Pattern.compile(
        ".*/providers/Microsoft\\.Web/sites/([^/?]+)(/config/web)?/?([?].*)?", Pattern.CASE_INSENSITIVE);

    /**
     * A site's {@code config/{name}} other than {@code web}, or its {@code config/{name}/list} action, namespace and
     * segments in any case: group 1 the site name, group 2 the config name, group 3 set for {@code /list}.
     */
    private static final Pattern SITE_CONFIG_PATH = Pattern.compile(
        ".*/providers/Microsoft\\.Web/sites/([^/?]+)/config/([^/?]+)(/list)?/?([?].*)?", Pattern.CASE_INSENSITIVE);

    /**
     * How the App Service REST API (WebApps.json, 2024-11-01) serves each {@code sites/{name}/config/{configName}}
     * other than {@code web}: whether the document itself answers GET and DELETE besides PUT, and the method of its
     * {@code .../list} action, null when it has none. {@code authsettingsV2/list} is a GET in the spec, the others a
     * POST, because they return secrets. {@code backup} is the one config Azure answers 404 for until one is set,
     * which is how azurerm tells a site without a backup ({@code notFoundUntilSet}).
     */
    private record SiteConfigOps(String name, boolean get, boolean delete, String listMethod,
                                 boolean notFoundUntilSet) {
    }

    /** The {@link SiteConfigOps} by config name lowercased. Any other name answers ARM 404, as in Azure. */
    private static final Map<String, SiteConfigOps> SITE_CONFIGS = Map.ofEntries(
        siteConfig("appsettings", false, false, "POST", false),
        siteConfig("authsettings", false, false, "POST", false),
        siteConfig("authsettingsV2", true, false, "GET", false),
        siteConfig("azurestorageaccounts", false, false, "POST", false),
        siteConfig("backup", false, true, "POST", true),
        siteConfig("connectionstrings", false, false, "POST", false),
        siteConfig("logs", true, false, null, false),
        siteConfig("metadata", false, false, "POST", false),
        siteConfig("pushsettings", false, false, "POST", false),
        siteConfig("slotConfigNames", true, false, null, false));

    /**
     * A site's {@code basicPublishingCredentialsPolicies} collection or one of its two policies, namespace and segments
     * in any case: group 1 the site name, group 2 the policy name, null for the collection.
     */
    private static final Pattern PUBLISHING_POLICY_PATH = Pattern.compile(
        ".*/providers/Microsoft\\.Web/sites/([^/?]+)/basicPublishingCredentialsPolicies(?:/([^/?]+))?/?([?].*)?",
        Pattern.CASE_INSENSITIVE);
    private static final String PUBLISHING_POLICY_TYPE = NAMESPACE + "/sites/basicPublishingCredentialsPolicies";
    /** The two policies the API defines, FTP and SCM (Kudu) basic auth, keyed lowercased. */
    private static final Map<String, String> PUBLISHING_POLICIES = Map.of("ftp", "ftp", "scm", "scm");

    /** Only an action, {@code POST .../config/publishingcredentials/list}: the API defines no GET or PUT for it. */
    private static final String PUBLISHING_CREDENTIALS = "publishingcredentials";
    private static final String PUBLISHING_CREDENTIALS_TYPE = NAMESPACE + "/sites/publishingcredentials";
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * An App Service plan's own path, namespace and type in any case: group 1 the plan name. Any path under a plan
     * ({@code .../serverFarms/{name}/virtualNetworkConnections} and the like) does not match, so it answers ARM 404.
     */
    private static final Pattern SERVER_FARM_PATH = Pattern.compile(
        ".*/providers/Microsoft\\.Web/serverFarms/([^/?]+)/?([?].*)?", Pattern.CASE_INSENSITIVE);

    private final ArmResourceStore<Map<String, Object>> sites;
    private final ArmResourceStore<Map<String, Object>> serverFarms;
    private final ArmResourceStore<Map<String, Object>> siteConfigs;
    private final ArmResourceStore<Map<String, Object>> publishingPolicies;
    /**
     * Held across a site write and its Function App sync, so concurrent PUTs to one site cannot leave the site's
     * {@code linuxFxVersion} and its Function App's runtime from different requests. The store locks each call on its
     * own, and the sync runs outside it. A site DELETE takes it too, so it cannot remove a site, and let the gate
     * release its name, while a PUT to it is between its name check and its write.
     */
    private final Object siteWrites = new Object();
    private final GlobalNameRegistry names;
    private final FunctionsServiceHandler functionsHandler;

    private final CloudDefaults defaults;

    @Inject
    public WebArmService(StorageFactory storageFactory, GlobalNameRegistry names,
                         FunctionsServiceHandler functionsHandler,
                         CloudDefaults defaults) {
        this.defaults = defaults;
        this.sites = ArmResourceStore.create(storageFactory, STORE, SITE_TYPE, documentType(), WebArmService::idOf,
            key -> false);
        this.serverFarms = ArmResourceStore.create(storageFactory, STORE, SERVER_FARM_TYPE, documentType(),
            WebArmService::idOf, key -> false);
        this.siteConfigs = ArmResourceStore.create(storageFactory, STORE, CONFIG_TYPE, documentType(),
            WebArmService::idOf, key -> false);
        this.publishingPolicies = ArmResourceStore.create(storageFactory, STORE, PUBLISHING_POLICY_TYPE,
            documentType(), WebArmService::idOf, key -> false);
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
            : handleSubscriptionPath(req, path, method, sub);
    }

    // ── Subscription level ──────────────────────────────────────────────────

    private Response handleSubscriptionPath(AzureRequest req, String path, String method, String sub) {
        if (path.matches("subscriptions/[^/?]+/providers/(?i:Microsoft\\.Web)/sites([?].*)?")) {
            return list(new ArmScope(sub, null));
        }
        if (path.matches("subscriptions/[^/?]+/providers/(?i:Microsoft\\.Web/serverFarms)([?].*)?")) {
            return listServerFarms(method, new ArmScope(sub, null));
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

    /**
     * Only a site's own path and its {@code config/web} reach the site handlers. Any other path under a site
     * ({@code .../sites/{name}/slots/{slot}} and the like) is a child floci-az does not serve, so it answers ARM 404
     * rather than writing, reading or deleting the parent: the gate owns names for the declared types only, so a child
     * PUT treated as the site's create would write a site whose name another subscription owns, and provision its
     * Function App over the owner's.
     */
    private Response handleResourceGroupPath(AzureRequest req, String path, String method, String sub, String rg) {
        if (path.matches(".*/providers/(?i:Microsoft\\.Web)/sites([?].*)?")) {
            return list(new ArmScope(sub, rg));
        }
        if (path.matches(".*/providers/(?i:Microsoft\\.Web/serverFarms)([?].*)?")) {
            return listServerFarms(method, new ArmScope(sub, rg));
        }
        Matcher serverFarm = SERVER_FARM_PATH.matcher(path);
        if (serverFarm.matches()) {
            return handleServerFarm(req, method, serverFarmId(sub, rg, serverFarm.group(1)));
        }
        Matcher publishingPolicy = PUBLISHING_POLICY_PATH.matcher(path);
        if (publishingPolicy.matches()) {
            return handlePublishingPolicy(req, method, siteId(sub, rg, publishingPolicy.group(1)),
                publishingPolicy.group(2));
        }
        Matcher siteConfig = SITE_CONFIG_PATH.matcher(path);
        if (siteConfig.matches() && !isWebConfig(siteConfig)) {
            return handleSiteConfig(req, method, siteId(sub, rg, siteConfig.group(1)), siteConfig.group(2),
                siteConfig.group(3) != null);
        }
        Matcher site = SITE_PATH.matcher(path);
        if (!site.matches()) {
            return armNotFound(path);
        }
        ArmResourceId id = siteId(sub, rg, site.group(1));
        if (site.group(2) != null) {
            return handleConfig(req, path, method, id);
        }
        return switch (method) {
            case "PUT"    -> createOrUpdateSite(req, id);
            case "GET"    -> getSite(id);
            case "DELETE" -> {
                synchronized (siteWrites) {
                    sites.delete(id);
                    siteConfigs.under(id).forEach(config -> siteConfigs.delete(config.id()));
                    publishingPolicies.under(id).forEach(policy -> publishingPolicies.delete(policy.id()));
                }
                yield Response.ok().build();
            }
            default       -> Response.status(405).build();
        };
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
        synchronized (siteWrites) {
            return writeSite(req, id, body, linuxFxVersion);
        }
    }

    /**
     * Holds the name again, inside {@link #siteWrites}, before the Function App sync and the write. The gate reserved
     * it before the lock, so a DELETE of this site may have released it since and another subscription's PUT may
     * hold it now: that PUT is then the one that owns the name and this one answers the gate's conflict, writing
     * nothing. A reservation made here is freed again when the sync refuses the {@code linuxFxVersion}.
     */
    private Response writeSite(AzureRequest req, ArmResourceId id, Map<String, Object> body, String linuxFxVersion) {
        String siteName = sites.get(id).map(stored -> nameOf(stored.value())).orElse(id.name());
        GlobalNameRegistry.Reservation reservation = names.reserve(cloud(req), CloudSuffix.WEB, siteName, id);
        if (reservation.outcome() == GlobalNameRegistry.Outcome.TAKEN) {
            return WebArmSurface.siteNameTaken(siteName);
        }
        try {
            syncFunctionApp(siteName, linuxFxVersion);
        } catch (IllegalArgumentException e) {
            names.releasePending(cloud(req), CloudSuffix.WEB, siteName, reservation.token());
            return invalidLinuxFxVersion(e);
        }
        String location = ArmJson.string(body, "location", defaults.defaultLocation(req.scope()));
        Map<String, Object> properties = ArmJson.cast(body.get("properties"));
        Map<String, Object> siteConfig = new LinkedHashMap<>(ArmJson.cast(properties.get("siteConfig")));
        Object appSettings = siteConfig.remove("appSettings");
        Object connectionStrings = siteConfig.remove("connectionStrings");
        Map<String, Object> written = sites.createOrUpdate(id, existing -> siteDocument(
            existing.map(WebArmService::idOf).orElse(id.id()),
            existing.map(WebArmService::nameOf).orElse(id.name()), location,
            body.get("tags") instanceof Map<?, ?> tags ? ArmJson.cast(tags) : null,
            ArmJson.string(body, "kind", null),
            siteIdentity(req, body.get("identity"), existing.map(WebArmService::identityOf).orElse(null)),
            properties, siteConfig, webSuffix(req))).stored().value();
        storeSettingLists(ArmResourceId.parse(idOf(written)).orElseThrow(), appSettings, connectionStrings);
        LOG.infov("ARM: created site {0}", written.get("name"));
        return Response.ok(written).build();
    }

    /** {@code sites/{name}/config/web}: the site's {@code siteConfig}, read or replaced. */
    private Response handleConfig(AzureRequest req, String path, String method, ArmResourceId id) {
        if ("GET".equals(method)) {
            return sites.get(id)
                .map(site -> configResponse(path, site.value()))
                .orElseGet(() -> armNotFound("sites/" + id.name()));
        }
        if (!"PUT".equals(method)) {
            return sites.get(id).isPresent() ? Response.status(405).build() : armNotFound("sites/" + id.name());
        }
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        Map<String, Object> config = body.containsKey("properties") ? ArmJson.cast(body.get("properties")) : body;
        synchronized (siteWrites) {
            return writeConfig(path, id, config);
        }
    }

    /** A PUT of {@code config/web} replaces the site's {@code siteConfig} whole, as in Azure, and keeps the rest. */
    private Response writeConfig(String path, ArmResourceId id, Map<String, Object> config) {
        String linuxFxVersion = ArmJson.string(config, "linuxFxVersion", null);
        Optional<Stored<Map<String, Object>>> site = sites.get(id);
        if (site.isEmpty()) {
            return armNotFound("sites/" + id.name());
        }
        try {
            syncFunctionApp(nameOf(site.get().value()), linuxFxVersion);
        } catch (IllegalArgumentException e) {
            return invalidLinuxFxVersion(e);
        }
        Map<String, Object> siteConfig = new LinkedHashMap<>(config);
        Object appSettings = siteConfig.remove("appSettings");
        Object connectionStrings = siteConfig.remove("connectionStrings");
        Optional<Stored<Map<String, Object>>> updated = sites.update(id, existing -> withSiteConfig(existing, siteConfig));
        updated.ifPresent(stored ->
            storeSettingLists(ArmResourceId.parse(idOf(stored.value())).orElseThrow(), appSettings, connectionStrings));
        return updated
            .map(stored -> configResponse(path, stored.value()))
            .orElseGet(() -> armNotFound("sites/" + id.name()));
    }

    /**
     * {@code appSettings} and {@code connectionStrings} sent inside a {@code siteConfig} (a site PUT or a
     * {@code config/web} PUT) are stored as the site's {@code config/appsettings} and {@code config/connectionstrings},
     * which is where Azure reads them back from; a list the request leaves out keeps what is stored.
     */
    private void storeSettingLists(ArmResourceId siteId, Object appSettings, Object connectionStrings) {
        if (appSettings instanceof List<?> settings) {
            putSiteConfig(siteId, "appsettings", appSettingsProperties(settings));
        }
        if (connectionStrings instanceof List<?> strings) {
            putSiteConfig(siteId, "connectionstrings", connectionStringProperties(strings));
        }
    }

    private static Response configResponse(String path, Map<String, Object> site) {
        return Response.ok(Map.of("id", path, "name", "web",
            "type", CONFIG_TYPE,
            "properties", ArmJson.cast(site.get("properties")).get("siteConfig"))).build();
    }

    // ── Site config other than web (appsettings, azurestorageaccounts, logs, ...) ─────

    /** {@code config/web} keeps its own handler, which syncs the Function App; {@code config/web/list} is unserved. */
    private static boolean isWebConfig(Matcher siteConfig) {
        return "web".equalsIgnoreCase(siteConfig.group(2)) && siteConfig.group(3) == null;
    }

    /**
     * {@code sites/{name}/config/{configName}}: stored ARM documents with no runtime behind them, served with the
     * methods the API defines for each ({@link #SITE_CONFIGS}); another method answers 405. A PUT replaces the
     * config's {@code properties} whole, as in Azure; a GET where the API has one, or the {@code .../list} action,
     * reads it back. A config never written reads back with empty {@code properties}, as Azure answers its defaults.
     * A config name or a {@code /list} the API does not define, or a site that does not exist, answers ARM 404.
     */
    private Response handleSiteConfig(AzureRequest req, String method, ArmResourceId siteId, String requestedName,
                                      boolean listAction) {
        if (PUBLISHING_CREDENTIALS.equalsIgnoreCase(requestedName)) {
            return listPublishingCredentials(method, siteId, listAction, webSuffix(req));
        }
        SiteConfigOps ops = SITE_CONFIGS.get(requestedName.toLowerCase(Locale.ROOT));
        if (ops == null || (listAction && ops.listMethod() == null)) {
            return armNotFound("sites/" + siteId.name() + "/config/" + requestedName + (listAction ? "/list" : ""));
        }
        if (sites.get(siteId).isEmpty()) {
            return armNotFound("sites/" + siteId.name());
        }
        ArmResourceId id = siteConfigId(siteId, ops.name());
        if (listAction) {
            if (!ops.listMethod().equals(method)) {
                return Response.status(405).build();
            }
            if (ops.notFoundUntilSet() && siteConfigs.get(id).isEmpty()) {
                return armNotFound("sites/" + siteId.name() + "/config/" + ops.name());
            }
            return Response.ok(readSiteConfig(id, ops.name())).build();
        }
        return switch (method) {
            case "PUT" -> writeSiteConfig(req, siteId, id, ops.name());
            case "GET" -> ops.get() ? Response.ok(readSiteConfig(id, ops.name())).build()
                : Response.status(405).build();
            case "DELETE" -> {
                if (!ops.delete()) {
                    yield Response.status(405).build();
                }
                siteConfigs.delete(id);
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    /** Holds {@link #siteWrites}, so a site DELETE cannot remove the site between the check and the write. */
    private Response writeSiteConfig(AzureRequest req, ArmResourceId siteId, ArmResourceId id, String configName) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        Map<String, Object> properties = body.get("properties") instanceof Map<?, ?> requested
            ? ArmJson.cast(requested) : Map.of();
        synchronized (siteWrites) {
            if (sites.get(siteId).isEmpty()) {
                return armNotFound("sites/" + siteId.name());
            }
            Map<String, Object> written = siteConfigs.createOrUpdate(id,
                existing -> siteConfigDocument(id.id(), configName, properties)).stored().value();
            return Response.ok(written).build();
        }
    }

    /**
     * The site's deployment credentials, the {@code $sitename} user azurerm reads into {@code site_credential}. They
     * are generated on the first list and kept, so every read returns the same pair until the site is deleted.
     */
    private Response listPublishingCredentials(String method, ArmResourceId siteId, boolean listAction,
                                               String webSuffix) {
        if (!listAction) {
            return armNotFound("sites/" + siteId.name() + "/config/" + PUBLISHING_CREDENTIALS);
        }
        if (!"POST".equals(method)) {
            return sites.get(siteId).isPresent() ? Response.status(405).build() : armNotFound("sites/" + siteId.name());
        }
        synchronized (siteWrites) {
            Optional<Stored<Map<String, Object>>> site = sites.get(siteId);
            if (site.isEmpty()) {
                return armNotFound("sites/" + siteId.name());
            }
            String siteName = nameOf(site.get().value());
            ArmResourceId id = siteConfigId(siteId, PUBLISHING_CREDENTIALS);
            Map<String, Object> credentials = siteConfigs.createOrUpdate(id,
                existing -> existing.orElseGet(() -> publishingCredentialsDocument(id.id(), siteName, webSuffix)))
                .stored().value();
            return Response.ok(credentials).build();
        }
    }

    private static Map<String, Object> publishingCredentialsDocument(String id, String siteName, String webSuffix) {
        String userName = "$" + siteName;
        String password = generatePassword();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", userName);
        properties.put("publishingUserName", userName);
        properties.put("publishingPassword", password);
        properties.put("scmUri", "https://" + userName + ":" + password + "@" + siteName + ".scm." + webSuffix);

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", id);
        resource.put("name", PUBLISHING_CREDENTIALS);
        resource.put("type", PUBLISHING_CREDENTIALS_TYPE);
        resource.put("properties", properties);
        return resource;
    }

    private static String generatePassword() {
        // A SecureRandom per call, not a static field: a native image would otherwise build it into the image heap.
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(60);
        for (int i = 0; i < 60; i++) {
            password.append(ALNUM.charAt(random.nextInt(ALNUM.length())));
        }
        return password.toString();
    }

    private static Map.Entry<String, SiteConfigOps> siteConfig(String name, boolean get, boolean delete,
                                                               String listMethod, boolean notFoundUntilSet) {
        return Map.entry(name.toLowerCase(Locale.ROOT),
            new SiteConfigOps(name, get, delete, listMethod, notFoundUntilSet));
    }

    // ── Basic publishing credentials policies (ftp, scm) ────────────────────

    /**
     * {@code sites/{name}/basicPublishingCredentialsPolicies[/{ftp|scm}]}: whether FTP and SCM basic auth are allowed,
     * which azurerm reads into {@code ftp_publish_basic_authentication_enabled} and
     * {@code webdeploy_publish_basic_authentication_enabled}. A policy never written reads back {@code allow: true},
     * Azure's default for a new site; a PUT sets it. The collection only lists; another policy name answers ARM 404.
     */
    private Response handlePublishingPolicy(AzureRequest req, String method, ArmResourceId siteId,
                                            String requestedPolicy) {
        if (sites.get(siteId).isEmpty()) {
            return armNotFound("sites/" + siteId.name());
        }
        if (requestedPolicy == null) {
            return "GET".equals(method)
                ? Response.ok(Map.of("value", List.of(readPublishingPolicy(siteId, "ftp"),
                    readPublishingPolicy(siteId, "scm")))).build()
                : Response.status(405).build();
        }
        String policy = PUBLISHING_POLICIES.get(requestedPolicy.toLowerCase(Locale.ROOT));
        if (policy == null) {
            return armNotFound("sites/" + siteId.name() + "/basicPublishingCredentialsPolicies/" + requestedPolicy);
        }
        return switch (method) {
            case "GET" -> Response.ok(readPublishingPolicy(siteId, policy)).build();
            case "PUT" -> writePublishingPolicy(req, siteId, policy);
            default -> Response.status(405).build();
        };
    }

    private Response writePublishingPolicy(AzureRequest req, ArmResourceId siteId, String policy) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        Map<String, Object> properties = body.get("properties") instanceof Map<?, ?> requested
            ? ArmJson.cast(requested) : Map.of();
        boolean allow = !Boolean.FALSE.equals(properties.get("allow"));
        ArmResourceId id = publishingPolicyId(siteId, policy);
        synchronized (siteWrites) {
            if (sites.get(siteId).isEmpty()) {
                return armNotFound("sites/" + siteId.name());
            }
            Map<String, Object> written = publishingPolicies.createOrUpdate(id,
                existing -> publishingPolicyDocument(id.id(), policy, allow)).stored().value();
            return Response.ok(written).build();
        }
    }

    private Map<String, Object> readPublishingPolicy(ArmResourceId siteId, String policy) {
        ArmResourceId id = publishingPolicyId(siteId, policy);
        return publishingPolicies.get(id).map(Stored::value)
            .orElseGet(() -> publishingPolicyDocument(id.id(), policy, true));
    }

    private static ArmResourceId publishingPolicyId(ArmResourceId siteId, String policy) {
        return ArmResourceId.parse(siteId.id() + "/basicPublishingCredentialsPolicies/" + policy).orElseThrow();
    }

    private static Map<String, Object> publishingPolicyDocument(String id, String policy, boolean allow) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("allow", allow);
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", id);
        resource.put("name", policy);
        resource.put("type", PUBLISHING_POLICY_TYPE);
        resource.put("properties", properties);
        return resource;
    }

    /**
     * A site PUT carries its app settings and connection strings in {@code siteConfig}, as name/value lists; Azure
     * keeps them as the site's {@code config/appsettings} and {@code config/connectionstrings}, which is where
     * azurerm reads them back, and does not return them in {@code siteConfig}. A PUT that sends no list leaves them as
     * they were.
     */
    private void putSiteConfig(ArmResourceId siteId, String configName, Map<String, Object> properties) {
        ArmResourceId id = siteConfigId(siteId, configName);
        siteConfigs.createOrUpdate(id, existing -> siteConfigDocument(id.id(), configName, properties));
    }

    private static Map<String, Object> appSettingsProperties(List<?> settings) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Object setting : settings) {
            Map<String, Object> pair = ArmJson.cast(setting);
            if (pair.get("name") instanceof String name) {
                properties.put(name, pair.get("value"));
            }
        }
        return properties;
    }

    private static Map<String, Object> connectionStringProperties(List<?> strings) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Object entry : strings) {
            Map<String, Object> connection = ArmJson.cast(entry);
            if (connection.get("name") instanceof String name) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("value", connection.get("connectionString"));
                value.put("type", connection.getOrDefault("type", "Custom"));
                properties.put(name, value);
            }
        }
        return properties;
    }

    private static ArmResourceId siteConfigId(ArmResourceId siteId, String configName) {
        return ArmResourceId.parse(siteId.id() + "/config/" + configName).orElseThrow();
    }

    private Map<String, Object> readSiteConfig(ArmResourceId id, String configName) {
        return siteConfigs.get(id).map(Stored::value)
            .orElseGet(() -> siteConfigDocument(id.id(), configName, Map.of()));
    }

    private static Map<String, Object> siteConfigDocument(String id, String configName,
                                                          Map<String, Object> properties) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", id);
        resource.put("name", configName);
        resource.put("type", CONFIG_TYPE);
        resource.put("properties", new LinkedHashMap<>(properties));
        return resource;
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

    /**
     * A PUT replaces the site whole, as in Azure: its tags (none when the PUT sends none), {@code kind} and
     * {@code identity}, and the {@code properties} it sends, such as {@code serverFarmId}, {@code httpsOnly} or
     * {@code virtualNetworkSubnetId}, which azurerm reads back. {@code state} and {@code defaultHostName} are Azure's
     * own; {@code siteConfig} is kept as sent, since {@code config/web} reads it back.
     */
    private static Map<String, Object> siteDocument(String id, String siteName, String location,
                                                    Map<String, Object> tags, String kind,
                                                    Map<String, Object> identity,
                                                    Map<String, Object> requestedProperties,
                                                    Map<String, Object> siteConfig, String webSuffix) {
        Map<String, Object> properties = new LinkedHashMap<>(requestedProperties);
        properties.put("state", "Running");
        properties.putIfAbsent("enabled", true);
        properties.put("defaultHostName", siteName + "." + webSuffix);
        properties.put("siteConfig", new LinkedHashMap<>(siteConfig));

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", id);
        resource.put("name", siteName);
        resource.put("type", SITE_TYPE);
        resource.put("location", location);
        resource.put("kind", kind != null ? kind : "functionapp,linux");
        if (identity != null) {
            resource.put("identity", identity);
        }
        if (tags != null) {
            resource.put("tags", tags);
        }
        resource.put("properties", properties);
        return resource;
    }

    /** The site as stored, with {@code config} as its whole {@code siteConfig}. */
    private static Map<String, Object> withSiteConfig(Map<String, Object> site, Map<String, Object> config) {
        Map<String, Object> properties = new LinkedHashMap<>(ArmJson.cast(site.get("properties")));
        properties.put("siteConfig", new LinkedHashMap<>(config));
        Map<String, Object> resource = new LinkedHashMap<>(site);
        resource.put("properties", properties);
        return resource;
    }

    /**
     * The identity to store: null for none, else the sent one, with a {@code principalId} and {@code tenantId} for a
     * system-assigned identity, as Azure creates a service principal for it. A re-PUT keeps the principal the site was
     * given, so role assignments made to it stay valid.
     */
    private static Map<String, Object> siteIdentity(AzureRequest req, Object sent, Map<String, Object> existing) {
        if (!(sent instanceof Map<?, ?> requested)) {
            return null;
        }
        Map<String, Object> body = ArmJson.cast(requested);
        String type = ArmJson.string(body, "type", "None");
        if ("None".equalsIgnoreCase(type)) {
            return null;
        }
        Map<String, Object> identity = new LinkedHashMap<>(body);
        if (type.toLowerCase(Locale.ROOT).contains("systemassigned")) {
            identity.put("principalId", existing != null && existing.get("principalId") instanceof String principal
                ? principal : UUID.randomUUID().toString());
            if (req.scope() != null && req.scope().tenantId() != null) {
                identity.put("tenantId", req.scope().tenantId());
            }
        }
        return identity;
    }

    private static Map<String, Object> identityOf(Map<String, Object> document) {
        return document.get("identity") instanceof Map<?, ?> identity ? ArmJson.cast(identity) : null;
    }

    private static String nameOf(Map<String, Object> document) {
        return String.valueOf(document.get("name"));
    }

    // ── App Service plans (serverFarms) ─────────────────────────────────────

    private Response handleServerFarm(AzureRequest req, String method, ArmResourceId id) {
        return switch (method) {
            case "PUT"    -> createOrUpdateServerFarm(req, id);
            case "PATCH"  -> patchServerFarm(req, id);
            case "GET"    -> serverFarms.get(id)
                .map(stored -> Response.ok(stored.value()).build())
                .orElseGet(() -> armNotFound("serverFarms/" + id.name()));
            case "DELETE" -> {
                serverFarms.delete(id);
                yield Response.ok().build();
            }
            default       -> Response.status(405).build();
        };
    }

    private static ArmResourceId serverFarmId(String sub, String rg, String planName) {
        return ArmResourceId.parse("/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/" + SERVER_FARM_TYPE
            + "/" + planName).orElseThrow();
    }

    /** A collection path only lists: a write to it answers 405 rather than a list that looks like success. */
    private Response listServerFarms(String method, ArmScope scope) {
        if (!"GET".equals(method)) {
            return Response.status(405).build();
        }
        return Response.ok(Map.of("value", serverFarmDocuments(scope))).build();
    }

    private List<Map<String, Object>> serverFarmDocuments(ArmScope scope) {
        return serverFarms.list(scope).stream().map(Stored::value).toList();
    }

    /**
     * A PUT replaces the plan whole, as in Azure; a re-PUT keeps the id and name the plan was created with. The
     * request's {@code sku} and {@code properties} are echoed, so a client reads back the tier, worker count and
     * {@code reserved} (Linux) flag it sent.
     */
    private Response createOrUpdateServerFarm(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        String location = ArmJson.string(body, "location", defaults.defaultLocation(req.scope()));
        Map<String, Object> written = serverFarms.createOrUpdate(id, existing -> serverFarmDocument(
            existing.map(WebArmService::idOf).orElse(id.id()),
            existing.map(WebArmService::nameOf).orElse(id.name()), location, body)).stored().value();
        LOG.infov("ARM: created App Service plan {0}", written.get("name"));
        return Response.ok(written).build();
    }

    /**
     * A PATCH changes only what it sends, as Azure's {@code App Service Plans - Update} does: {@code sku},
     * {@code kind} and {@code tags} are replaced when present, {@code properties} are merged key by key. A PATCH of a
     * plan that does not exist answers ARM 404 rather than creating it.
     */
    private Response patchServerFarm(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        return serverFarms.update(id, existing -> patchedServerFarm(existing, body))
            .map(stored -> Response.ok(stored.value()).build())
            .orElseGet(() -> armNotFound("serverFarms/" + id.name()));
    }

    private static Map<String, Object> patchedServerFarm(Map<String, Object> existing, Map<String, Object> body) {
        Map<String, Object> resource = new LinkedHashMap<>(existing);
        for (String key : List.of("sku", "tags")) {
            if (body.get(key) instanceof Map<?, ?> value) {
                resource.put(key, ArmJson.cast(value));
            }
        }
        if (body.get("kind") instanceof String kind) {
            resource.put("kind", kind);
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        if (existing.get("properties") instanceof Map<?, ?> current) {
            properties.putAll(ArmJson.cast(current));
        }
        if (body.get("properties") instanceof Map<?, ?> requested) {
            properties.putAll(ArmJson.cast(requested));
        }
        properties.put("provisioningState", "Succeeded");
        properties.put("status", "Ready");
        resource.put("properties", properties);
        return resource;
    }

    private static Map<String, Object> serverFarmDocument(String id, String planName, String location,
                                                          Map<String, Object> body) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (body.get("properties") instanceof Map<?, ?> requested) {
            properties.putAll(ArmJson.cast(requested));
        }
        properties.put("provisioningState", "Succeeded");
        properties.put("status", "Ready");
        properties.putIfAbsent("reserved", false);
        properties.putIfAbsent("perSiteScaling", false);
        properties.putIfAbsent("zoneRedundant", false);
        properties.putIfAbsent("maximumElasticWorkerCount", 1);
        // Not tracked: a plan does not count the sites whose serverFarmId points at it.
        properties.putIfAbsent("numberOfSites", 0);

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", id);
        resource.put("name", planName);
        resource.put("type", SERVER_FARM_TYPE);
        resource.put("location", location);
        resource.put("kind", ArmJson.string(body, "kind",
            Boolean.TRUE.equals(properties.get("reserved")) ? "linux" : "app"));
        if (body.get("sku") instanceof Map<?, ?> sku) {
            resource.put("sku", ArmJson.cast(sku));
        }
        if (body.get("tags") instanceof Map<?, ?> tags) {
            resource.put("tags", ArmJson.cast(tags));
        }
        resource.put("properties", properties);
        return resource;
    }

    // ── StoreIndexSource ────────────────────────────────────────────

    /** The full documents, as ArmHandler listed its own sites before. */
    @Override
    public List<Map<String, Object>> indexEntries(ArmScope scope) {
        List<Map<String, Object>> entries = new ArrayList<>(documents(scope));
        entries.addAll(serverFarmDocuments(scope));
        return entries;
    }

    // ── GlobalNameSource ────────────────────────────────────────────────────

    @Override
    public List<Claim> globalNames() {
        return sites.all().stream()
            .map(stored -> new Claim(CloudSuffix.WEB, nameOf(stored.value()), stored.id()))
            .toList();
    }

    @Override
    public boolean holds(CloudSuffix suffix, String name, ArmResourceId owner) {
        return suffix == CloudSuffix.WEB
            && sites.get(owner).filter(stored -> name.equalsIgnoreCase(nameOf(stored.value()))).isPresent();
    }

    @Override
    public void clear() {
        sites.clear();
        serverFarms.clear();
        siteConfigs.clear();
        publishingPolicies.clear();
    }

    /** The cloud the request's subscription belongs to, as the routing filter resolved it; public without a scope. */
    private static CloudProfile cloud(AzureRequest req) {
        return req.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : req.scope().cloud();
    }

    /** The App Service DNS suffix of the request's cloud ({@code azurewebsites.net} in the public cloud). */
    private static String webSuffix(AzureRequest req) {
        return cloud(req).suffix(CloudSuffix.WEB)
            .or(() -> CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD).suffix(CloudSuffix.WEB))
            .orElseThrow();
    }

    private static Response armNotFound(String path) {
        return ArmErrors.notFound("Resource not found: " + path);
    }
}
