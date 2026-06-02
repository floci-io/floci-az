package io.floci.az.services.arm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.services.blob.BlobServiceHandler;
import io.floci.az.services.functions.FunctionRuntime;
import io.floci.az.services.functions.FunctionsServiceHandler;
import io.floci.az.services.queue.QueueServiceHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ARM management-plane handler for Azure Resource Manager paths that are not
 * served by more-specific handlers (AKS, SQL).
 *
 * <p>Handles the minimal ARM surface required by the {@code hashicorp/azurerm}
 * Terraform provider v4+:
 *
 * <ul>
 *   <li>Subscription — {@code GET /subscriptions/{sub}}</li>
 *   <li>Resource Groups — {@code PUT/GET/DELETE /subscriptions/{sub}/resourceGroups/{rg}}</li>
 *   <li>Storage Accounts — ARM shell that bridges container/queue creation to the
 *       existing Blob and Queue storage backends</li>
 *   <li>Key Vaults — ARM shell whose vault URI points at the existing Key Vault handler</li>
 * </ul>
 */
@ApplicationScoped
public class ArmHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(ArmHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String FAKE_STORAGE_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==";

    private static final String SUBSCRIPTION_ID = "00000000-0000-0000-0000-000000000001";
    private static final String TENANT_ID       = "00000000-0000-0000-0000-000000000002";
    private static final String DEFAULT_FUNCTIONS_ACCOUNT = "devstoreaccount1";

    // In-memory ARM resource state — no StorageBackend needed, these are ephemeral
    private final Map<String, Map<String, Object>> resourceGroups   = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> storageAccounts  = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> keyVaults        = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> webApps          = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> networkResources = new ConcurrentHashMap<>();

    private final EmulatorConfig config;
    private final BlobServiceHandler blobHandler;
    private final QueueServiceHandler queueHandler;
    private final FunctionsServiceHandler functionsHandler;

    @Inject
    public ArmHandler(EmulatorConfig config, BlobServiceHandler blobHandler, QueueServiceHandler queueHandler,
                      FunctionsServiceHandler functionsHandler) {
        this.config           = config;
        this.blobHandler      = blobHandler;
        this.queueHandler     = queueHandler;
        this.functionsHandler = functionsHandler;
    }

    @Override
    public String getServiceType() { return "arm"; }

    @Override
    public boolean canHandle(AzureRequest req) { return "arm".equals(req.serviceType()); }

    @Override
    public Response handle(AzureRequest req) {
        String path   = req.resourcePath();
        String method = req.method().toUpperCase();

        LOG.debugf("ArmHandler %s %s", method, path);

        // ── Subscription ──────────────────────────────────────────────────────
        if (path.matches("subscriptions/[^/?]+") ||
            path.matches("subscriptions/[^/?]+\\?.*")) {
            return subscriptionResponse(extractSub(path));
        }

        // ── Cross-subscription storage account listing ─────────────────────────
        // azurerm provider calls this during state refresh/destroy to verify that
        // storage accounts still exist. Return real data so destroy plans correctly.
        if (path.matches("subscriptions/[^/?]+/providers/Microsoft\\.Storage/storageAccounts([?].*)?")) {
            String sub = extractSub(path);
            List<Map<String, Object>> accounts = storageAccounts.values().stream()
                    .filter(a -> sub.equals(a.get("_sub")))
                    .map(ArmHandler::stripInternal)
                    .toList();
            return Response.ok(Map.of("value", accounts)).build();
        }

        // ── Cross-subscription key vault listing ───────────────────────────────
        if (path.matches("subscriptions/[^/?]+/providers/Microsoft\\.KeyVault/vaults([?].*)?")) {
            String sub = extractSub(path);
            List<Map<String, Object>> vaults = keyVaults.values().stream()
                    .filter(v -> sub.equals(v.get("_sub")))
                    .map(ArmHandler::stripInternal)
                    .toList();
            return Response.ok(Map.of("value", vaults)).build();
        }

        // ── Provider registration check (skip_provider_registration=true still calls this) ──
        // Only matches /subscriptions/{sub}/providers or /subscriptions/{sub}/providers/{namespace}
        // (no resource type segment), so the more-specific handlers above take precedence.
        if (path.matches("subscriptions/[^/]+/providers(/[^/]+)?([?].*)?")) {
            return Response.ok(Map.of("value", List.of())).build();
        }

        // ── Subscription-level resource listing ──────────────────────────────
        // azurerm provider calls this to populate its Key Vault cache so it can
        // look up a vault by its vaultUri. Return all key vaults for the subscription.
        if (path.matches("subscriptions/[^/?]+/resources([?].*)?")) {
            String sub = extractSub(path);
            List<Map<String, Object>> resources = keyVaults.values().stream()
                    .filter(v -> sub.equals(v.get("_sub")))
                    .map(ArmHandler::stripInternal)
                    .toList();
            return Response.ok(Map.of("value", resources)).build();
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
            return Response.ok(Map.of("value", new ArrayList<>(resourceGroups.values()))).build();
        }

        // subscriptions/{sub}/resourceGroups/{rg}  (no trailing segments)
        if (lc.matches("subscriptions/[^/]+/resourcegroups/[^/]+([?].*)?")) {
            String rg = extractRg(path);
            return switch (method) {
                case "PUT"    -> createOrUpdateResourceGroup(sub, rg, req);
                case "GET"    -> getResourceGroup(sub, rg);
                case "DELETE" -> { resourceGroups.remove(rgKey(sub, rg)); yield Response.ok().build(); }
                default       -> Response.status(405).build();
            };
        }

        // subscriptions/{sub}/resourceGroups/{rg}/resources  (list resources in RG)
        // azurerm provider calls this before deleting a resource group to verify it is empty.
        if (lc.matches("subscriptions/[^/]+/resourcegroups/[^/]+/resources([?].*)?")) {
            String rg = extractRg(path);
            List<Map<String, Object>> resources = new ArrayList<>();
            storageAccounts.values().stream()
                    .filter(a -> sub.equals(a.get("_sub")) && rg.equals(a.get("_rg")))
                    .map(ArmHandler::stripInternal)
                    .forEach(resources::add);
            keyVaults.values().stream()
                    .filter(v -> sub.equals(v.get("_sub")) && rg.equals(v.get("_rg")))
                    .map(ArmHandler::stripInternal)
                    .forEach(resources::add);
            webApps.values().stream()
                    .filter(v -> sub.equals(v.get("_sub")) && rg.equals(v.get("_rg")))
                    .map(ArmHandler::stripInternal)
                    .forEach(resources::add);
            networkResources.values().stream()
                    .filter(v -> sub.equals(v.get("_sub")) && rg.equals(v.get("_rg")))
                    .map(ArmHandler::stripInternal)
                    .forEach(resources::add);
            return Response.ok(Map.of("value", resources)).build();
        }

        // subscriptions/{sub}/resourceGroups/{rg}/providers/...
        if (path.contains("/providers/")) {
            return handleProviders(req, path, method, sub);
        }

        return armNotFound(path);
    }

    // ── Provider-level routing ────────────────────────────────────────────────

    private Response handleProviders(AzureRequest req, String path, String method, String sub) {
        if (path.contains("/providers/Microsoft.Storage/")) {
            return handleStorage(req, path, method, sub);
        }
        if (path.contains("/providers/Microsoft.KeyVault/")) {
            return handleKeyVault(req, path, method, sub);
        }
        if (path.contains("/providers/Microsoft.Web/")) {
            return handleWeb(req, path, method, sub);
        }
        if (path.contains("/providers/Microsoft.Network/")) {
            return handleNetwork(req, path, method, sub);
        }
        return armNotFound(path);
    }

    // ── Function Apps ────────────────────────────────────────────────────────

    private Response handleWeb(AzureRequest req, String path, String method, String sub) {
        String rg = extractRg(path);

        if (path.matches(".*/providers/Microsoft\\.Web/sites([?].*)?")) {
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

    private Response createOrUpdateWebApp(AzureRequest req, String sub, String rg, String appName) {
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

    // ── Network resources (VM dependencies) ───────────────────────────────────
    //
    // Lightweight ARM shells for the resources an azurerm_linux_virtual_machine needs:
    // virtualNetworks (+ subnets), networkInterfaces, publicIPAddresses,
    // networkSecurityGroups. Submitted properties are echoed back with
    // provisioningState=Succeeded; NIC private IPs and public IPs are synthesised so the
    // provider can read them back after apply.

    private Response handleNetwork(AzureRequest req, String path, String method, String sub) {
        String rg   = extractRg(path);
        String tail = extractAfter(path, "/providers/Microsoft.Network/");

        // LIST: tail is a single resource-type segment (e.g. "virtualNetworks").
        if (tail.matches("[^/]+")) {
            return listNetworkResources(sub, rg, "Microsoft.Network/" + tail);
        }
        // LIST subnets of a vnet: "virtualNetworks/{vnet}/subnets".
        if (tail.matches("virtualNetworks/[^/]+/subnets")) {
            return listNetworkResources(sub, rg, "Microsoft.Network/virtualNetworks/subnets");
        }

        String resourceType = networkResourceType(tail);
        String name         = networkResourceName(tail);
        String key          = netKey(sub, rg, tail);

        return switch (method) {
            case "PUT"    -> createOrUpdateNetworkResource(req, sub, rg, tail, resourceType, name, key);
            case "GET"    -> {
                Map<String, Object> resource = networkResources.get(key);
                yield resource == null ? armNotFound(tail) : Response.ok(stripInternal(resource)).build();
            }
            case "DELETE" -> { networkResources.remove(key); yield Response.ok().build(); }
            default       -> Response.status(405).build();
        };
    }

    private Response listNetworkResources(String sub, String rg, String type) {
        List<Map<String, Object>> items = networkResources.values().stream()
                .filter(r -> sub.equals(r.get("_sub")) && rg.equals(r.get("_rg")) && type.equals(r.get("type")))
                .map(ArmHandler::stripInternal)
                .toList();
        return Response.ok(Map.of("value", items)).build();
    }

    private Response createOrUpdateNetworkResource(AzureRequest req, String sub, String rg, String tail,
                                                   String resourceType, String name, String key) {
        Map<String, Object> body       = parseBody(req);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        synthesizeNetworkProperties(resourceType, properties);
        properties.put("provisioningState", "Succeeded");

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("_sub", sub);
        resource.put("_rg", rg);
        resource.put("id", "/subscriptions/" + sub + "/resourceGroups/" + rg
                + "/providers/Microsoft.Network/" + tail);
        resource.put("name", name);
        resource.put("type", resourceType);
        String location = bodyString(body, "location", null);
        if (location != null) {
            resource.put("location", location);
        }
        if (body.get("tags") instanceof Map<?, ?> tags && !tags.isEmpty()) {
            resource.put("tags", tags);
        }
        resource.put("properties", properties);
        networkResources.put(key, resource);
        return Response.ok(stripInternal(resource)).build();
    }

    @SuppressWarnings("unchecked")
    private static void synthesizeNetworkProperties(String resourceType, Map<String, Object> properties) {
        switch (resourceType) {
            case "Microsoft.Network/networkInterfaces" -> {
                Object cfgs = properties.get("ipConfigurations");
                List<Object> configs = cfgs instanceof List<?> l && !l.isEmpty()
                        ? new ArrayList<>((List<Object>) l)
                        : new ArrayList<>(List.of(new LinkedHashMap<String, Object>(Map.of("name", "ipconfig1"))));
                boolean[] first = {true};
                configs.replaceAll(c -> {
                    Map<String, Object> cfg = new LinkedHashMap<>(cast(c));
                    Map<String, Object> cp = new LinkedHashMap<>(cast(cfg.get("properties")));
                    cp.putIfAbsent("privateIPAddress", "10.0.0.4");
                    cp.putIfAbsent("privateIPAllocationMethod", "Dynamic");
                    cp.put("primary", first[0]);
                    cp.put("provisioningState", "Succeeded");
                    cfg.put("properties", cp);
                    cfg.putIfAbsent("name", "ipconfig1");
                    first[0] = false;
                    return cfg;
                });
                properties.put("ipConfigurations", configs);
            }
            case "Microsoft.Network/publicIPAddresses" -> {
                properties.putIfAbsent("ipAddress", "20.0.0.4");
                properties.putIfAbsent("publicIPAllocationMethod", "Dynamic");
            }
            default -> { /* virtualNetworks, subnets, networkSecurityGroups: echo as-is */ }
        }
    }

    private static String networkResourceType(String tail) {
        // "networkInterfaces/{name}" -> "Microsoft.Network/networkInterfaces"
        // "virtualNetworks/{v}/subnets/{s}" -> "Microsoft.Network/virtualNetworks/subnets"
        String[] parts = tail.split("/");
        if (parts.length >= 4 && "subnets".equals(parts[2])) {
            return "Microsoft.Network/virtualNetworks/subnets";
        }
        return "Microsoft.Network/" + parts[0];
    }

    private static String networkResourceName(String tail) {
        String[] parts = tail.split("[/?]");
        return parts.length > 0 ? parts[parts.length - 1] : tail;
    }

    private static String netKey(String sub, String rg, String tail) {
        int q = tail.indexOf('?');
        String clean = q >= 0 ? tail.substring(0, q) : tail;
        return sub + "/" + rg + "/net/" + clean;
    }

    // ── Storage Accounts ─────────────────────────────────────────────────────

    private Response handleStorage(AzureRequest req, String path, String method, String sub) {
        String rg = extractRg(path);

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
        if (path.matches(".*/providers/Microsoft\\.Storage/storageAccounts([?].*)?")) {
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
                case "GET"    -> getStorageAccount(sub, rg, account);
                case "DELETE" -> { storageAccounts.remove(saKey(sub, rg, account)); yield Response.ok().build(); }
                default       -> Response.status(405).build();
            };
        }

        return armNotFound(path);
    }

    private Response createOrUpdateStorageAccount(AzureRequest req, String sub, String rg, String account) {
        Map<String, Object> body = parseBody(req);
        String location = bodyString(body, "location", "eastus");
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
        resource.put("sku", Map.of("name", "Standard_LRS", "tier", "Standard"));
        resource.put("kind", "StorageV2");
        resource.put("properties", Map.of(
                "provisioningState", "Succeeded",
                "primaryEndpoints", Map.of(
                        "blob",  "http://" + account + ".blob.core.windows.net" + portStr + "/",
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
        LOG.infof("ARM: created storage account %s", account);
        return Response.ok(stripInternal(resource)).build();
    }

    private Response getStorageAccount(String sub, String rg, String account) {
        Map<String, Object> resource = storageAccounts.get(saKey(sub, rg, account));
        if (resource == null) {
            return armNotFound("storageAccounts/" + account);
        }
        return Response.ok(stripInternal(resource)).build();
    }

    private Response listKeys(String account) {
        return Response.ok(Map.of("keys", List.of(
                Map.of("keyName", "key1", "value", FAKE_STORAGE_KEY, "permissions", "FULL"),
                Map.of("keyName", "key2", "value", FAKE_STORAGE_KEY, "permissions", "FULL")
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
                        "lastModifiedTime",  java.time.Instant.now().toString(),
                        "etag",              "\"" + UUID.randomUUID() + "\"");
                yield Response.status(201).entity(Map.of(
                        "id",         path,
                        "name",       container,
                        "type",       "Microsoft.Storage/storageAccounts/blobServices/containers",
                        "properties", props
                )).build();
            }
            case "GET" -> Response.ok(Map.of(
                    "id",         path,
                    "name",       container,
                    "type",       "Microsoft.Storage/storageAccounts/blobServices/containers",
                    "properties", Map.of("publicAccess", "None")
            )).build();
            case "DELETE" -> Response.ok().build();
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
                yield Response.status(201).entity(Map.of(
                        "id",         path,
                        "name",       queue,
                        "type",       "Microsoft.Storage/storageAccounts/queueServices/queues",
                        "properties", Map.of()
                )).build();
            }
            case "GET" -> Response.ok(Map.of(
                    "id",         path,
                    "name",       queue,
                    "type",       "Microsoft.Storage/storageAccounts/queueServices/queues",
                    "properties", Map.of()
            )).build();
            case "DELETE" -> Response.ok().build();
            default -> Response.status(405).build();
        };
    }

    // ── Key Vault ─────────────────────────────────────────────────────────────

    private Response handleKeyVault(AzureRequest req, String path, String method, String sub) {
        String rg = extractRg(path);

        // GET list
        if (path.matches(".*/providers/Microsoft\\.KeyVault/vaults([?].*)?")) {
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

        return armNotFound(path);
    }

    private Response createOrUpdateKeyVault(AzureRequest req, String sub, String rg, String vaultName) {
        Map<String, Object> body     = parseBody(req);
        String location              = bodyString(body, "location", "eastus");
        // Use vault.azure.net domain format so the azurerm provider accepts the URI.
        // Port is derived from the configured base URL so data-plane calls reach our emulator.
        String vaultUri              = vaultUri(vaultName);
        Map<String, Object> bodyProps = body.containsKey("properties")
                ? cast(body.get("properties")) : Map.of();
        String tenantId = bodyString(bodyProps, "tenantId", TENANT_ID);

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

    // ── Resource Groups ───────────────────────────────────────────────────────

    private Response createOrUpdateResourceGroup(String sub, String rg, AzureRequest req) {
        Map<String, Object> body     = parseBody(req);
        String location              = bodyString(body, "location", "eastus");

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id",       "/subscriptions/" + sub + "/resourceGroups/" + rg);
        resource.put("name",     rg);
        resource.put("type",     "Microsoft.Resources/resourceGroups");
        resource.put("location", location);
        resource.put("properties", Map.of("provisioningState", "Succeeded"));

        resourceGroups.put(rgKey(sub, rg), resource);
        LOG.infof("ARM: created resource group %s", rg);
        return Response.status(201).entity(resource).build();
    }

    private Response getResourceGroup(String sub, String rg) {
        Map<String, Object> resource = resourceGroups.get(rgKey(sub, rg));
        if (resource == null) {
            return armNotFound("resourceGroups/" + rg);
        }
        return Response.ok(resource).build();
    }

    // ── Subscription ─────────────────────────────────────────────────────────

    private Response subscriptionResponse(String sub) {
        return Response.ok(Map.of(
                "id",             "/subscriptions/" + sub,
                "subscriptionId", sub,
                "displayName",    "floci-az local",
                "state",          "Enabled",
                "tenantId",       TENANT_ID
        )).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String extractSub(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("subscriptions".equals(parts[i])) return parts[i + 1];
        }
        return "unknown";
    }

    private static String extractRg(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("resourcegroups".equalsIgnoreCase(parts[i])) return parts[i + 1];
        }
        return "unknown";
    }

    private static String extractResourceName(String path, String resourceType) {
        Pattern p = Pattern.compile("/" + Pattern.quote(resourceType) + "/([^/?]+)");
        Matcher m = p.matcher(path);
        return m.find() ? m.group(1) : "unknown";
    }

    private static String extractAfter(String path, String marker) {
        int idx = path.lastIndexOf(marker);
        if (idx < 0) return "unknown";
        String rest = path.substring(idx + marker.length());
        int q = rest.indexOf('?');
        return q >= 0 ? rest.substring(0, q) : rest;
    }

    private static String rgKey(String sub, String rg)               { return sub + "/" + rg; }
    private static String saKey(String sub, String rg, String name)  { return sub + "/" + rg + "/" + name; }
    private static String kvKey(String sub, String rg, String name)  { return sub + "/" + rg + "/kv/" + name; }
    private static String webAppKey(String sub, String rg, String name) { return sub + "/" + rg + "/web/" + name; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String bodyString(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v instanceof String s ? s : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(AzureRequest req) {
        try {
            if (req.bodyStream() == null || req.bodyStream().available() == 0) {
                return Map.of();
            }
            return MAPPER.readValue(req.bodyStream(), Map.class);
        } catch (IOException e) {
            return Map.of();
        }
    }

    private static Map<String, Object> stripInternal(Map<String, Object> resource) {
        Map<String, Object> copy = new LinkedHashMap<>(resource);
        copy.remove("_sub");
        copy.remove("_rg");
        return copy;
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

    private String storagePortString() {
        // Return no port so storage URLs use standard HTTP port 80.
        // Test containers forward port 80 → emulator via socat.
        return "";
    }

    private String vaultUri(String vaultName) {
        // Use vault.azure.net domain format on standard port 443 (no explicit port).
        // azurerm validates the URI matches *.vault.* pattern. In the test container a
        // socat listener on 443 forwards to the emulator so the azurerm provider can
        // reach the vault; host-based routing then dispatches to KeyVaultHandler.
        return "https://" + vaultName + ".vault.azure.net/";
    }

    private Response armNotFound(String path) {
        return Response.status(404).entity(Map.of("error", Map.of(
                "code",    "ResourceNotFound",
                "message", "Resource not found: " + path
        ))).build();
    }
}
