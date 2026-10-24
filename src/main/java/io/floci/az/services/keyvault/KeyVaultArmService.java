package io.floci.az.services.keyvault;

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
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Microsoft.KeyVault's management plane: vaults and managed HSMs, ARM shells whose {@code vaultUri} and
 * {@code hsmUri} point at {@link KeyVaultHandler}'s data plane. Reached through {@code ArmHandler}'s provider lane,
 * inside the ARM request gate, which owns each name ({@link KeyVaultArmSurface}) before a PUT gets here.
 *
 * <p>Each type is one {@link ArmResourceStore} of the ARM documents responses return, both over the
 * {@value #STORE} backend, keyed by canonical id, so a path that differs only in case addresses the one resource and
 * the casing of its first PUT is what responses echo. The store follows the global storage mode. These resources
 * were kept in memory before, so there are no older entries to read.
 *
 * <p>The data plane does not consult these records: it serves any vault name it is addressed by. The one reader is
 * the Key Vault fallback at the ARM base URL ({@link #defaultDataPlaneAccount()}).
 */
@ApplicationScoped
public class KeyVaultArmService implements ArmProviderService, ResourceIndexContributor, GlobalNameSource, Resettable {

    private static final Logger LOG = Logger.getLogger(KeyVaultArmService.class);

    /** The {@link StorageFactory} backend both types live in. */
    static final String STORE = "arm-keyvault";

    private static final String NAMESPACE = "Microsoft.KeyVault";
    static final String VAULT_TYPE = NAMESPACE + "/vaults";
    static final String HSM_TYPE = NAMESPACE + "/managedHSMs";

    /** The data-plane account the ARM-base fallback uses when no single vault names one. */
    private static final String DEFAULT_DATA_PLANE_ACCOUNT = "kv-default";

    /** A vault's or managed HSM's own path, namespace and type in any case: group 1 the type, group 2 the name. */
    private static final Pattern RESOURCE_PATH = Pattern.compile(
        ".*/providers/Microsoft\\.KeyVault/(vaults|managedHSMs)/([^/?]+)/?([?].*)?", Pattern.CASE_INSENSITIVE);

    private final ArmResourceStore<Map<String, Object>> vaults;
    private final ArmResourceStore<Map<String, Object>> hsms;
    private final GlobalNameRegistry names;
    private final AzureDirectory directory;

    @Inject
    public KeyVaultArmService(StorageFactory storageFactory, GlobalNameRegistry names, AzureDirectory directory) {
        this.vaults = ArmResourceStore.create(storageFactory, STORE, VAULT_TYPE, documentType(),
            KeyVaultArmService::idOf, key -> false);
        this.hsms = ArmResourceStore.create(storageFactory, STORE, HSM_TYPE, documentType(),
            KeyVaultArmService::idOf, key -> false);
        this.names = names;
        this.directory = directory;
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
        if (path.matches("subscriptions/[^/?]+/providers/(?i:Microsoft\\.KeyVault)/vaults([?].*)?")) {
            return list(vaults, new ArmScope(sub, null));
        }
        if (path.matches("subscriptions/[^/?]+/providers/(?i:Microsoft\\.KeyVault)/managedHSMs([?].*)?")) {
            return list(hsms, new ArmScope(sub, null));
        }
        // ARM paths are case-insensitive, and clients disagree on the casing of provider actions.
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.matches("subscriptions/[^/]+/providers/microsoft\\.keyvault/checknameavailability([?].*)?")) {
            return checkNameAvailability(req, CloudSuffix.KEYVAULT, "The vault name '%s' is already in use.");
        }
        if (lower.matches("subscriptions/[^/]+/providers/microsoft\\.keyvault/checkmhsmnameavailability([?].*)?")) {
            return checkNameAvailability(req, CloudSuffix.MHSM, "The managed HSM name '%s' is already in use.");
        }
        return armNotFound(path);
    }

    /**
     * {@code Vaults_CheckNameAvailability} and {@code ManagedHsms_CheckMhsmNameAvailability}: whether the name is
     * owned, answered from the name registry, in the {@code CheckNameAvailabilityResult} shape.
     */
    private Response checkNameAvailability(AzureRequest req, CloudSuffix suffix, String takenMessage) {
        String name = ArmJson.string(ArmJson.parseBodyLenient(req), "name", "");
        if (names.owner(cloud(req), suffix, name).isEmpty()) {
            return Response.ok(Map.of("nameAvailable", true)).build();
        }
        return Response.ok(Map.of("nameAvailable", false, "reason", "AlreadyExists",
            "message", String.format(takenMessage, name))).build();
    }

    // ── Resource group level ────────────────────────────────────────────────

    /**
     * Only a vault's or managed HSM's own path reaches the resource handlers. A path under one
     * ({@code .../vaults/{name}/accessPolicies/add} and the like) is a child operation floci-az does not serve, so it
     * answers ARM 404 rather than writing, reading or deleting the parent: the gate owns names for the declared types
     * only, so a child PUT treated as the parent's create would write a vault whose name another subscription owns.
     */
    private Response handleResourceGroupPath(AzureRequest req, String path, String method, String sub, String rg) {
        if (path.matches(".*/providers/(?i:Microsoft\\.KeyVault)/vaults([?].*)?")) {
            return list(vaults, new ArmScope(sub, rg));
        }
        if (path.matches(".*/providers/(?i:Microsoft\\.KeyVault)/managedHSMs([?].*)?")) {
            return list(hsms, new ArmScope(sub, rg));
        }
        Matcher resource = RESOURCE_PATH.matcher(path);
        if (!resource.matches()) {
            return armNotFound(path);
        }
        String name = resource.group(2);
        return resource.group(1).equalsIgnoreCase("vaults")
            ? vault(req, method, resourceId(sub, rg, "vaults", name), name)
            : managedHsm(req, method, resourceId(sub, rg, "managedHSMs", name), name);
    }

    private Response vault(AzureRequest req, String method, ArmResourceId id, String vaultName) {
        return switch (method) {
            case "PUT"    -> createOrUpdateVault(req, id);
            case "GET"    -> get(vaults, id, "vaults/" + vaultName);
            case "DELETE" -> {
                vaults.delete(id);
                yield Response.ok().build();
            }
            default       -> Response.status(405).build();
        };
    }

    private Response managedHsm(AzureRequest req, String method, ArmResourceId id, String hsmName) {
        return switch (method) {
            case "PUT"    -> createOrUpdateManagedHsm(req, id);
            case "GET"    -> get(hsms, id, "managedHSMs/" + hsmName);
            case "DELETE" -> {
                hsms.delete(id);
                yield Response.ok().build();
            }
            default       -> Response.status(405).build();
        };
    }

    private static ArmResourceId resourceId(String sub, String rg, String type, String name) {
        String id = "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/" + NAMESPACE + "/" + type + "/"
            + name;
        return ArmResourceId.parse(id).orElseThrow();
    }

    private static Response list(ArmResourceStore<Map<String, Object>> store, ArmScope scope) {
        return Response.ok(Map.of("value", documents(store, scope))).build();
    }

    private static List<Map<String, Object>> documents(ArmResourceStore<Map<String, Object>> store, ArmScope scope) {
        return store.list(scope).stream().map(Stored::value).toList();
    }

    private Response get(ArmResourceStore<Map<String, Object>> store, ArmResourceId id, String notFoundName) {
        return store.get(id)
            .map(stored -> Response.ok(stored.value()).build())
            .orElseGet(() -> armNotFound(notFoundName));
    }

    /**
     * The name is reserved in the {@link GlobalNameRegistry} by the gate before this runs, so a name another resource
     * owns never gets here. A re-PUT keeps the id and name the vault was created with.
     */
    private Response createOrUpdateVault(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        Map<String, Object> written = vaults.createOrUpdate(id, existing -> vaultDocument(body,
            existing.map(KeyVaultArmService::idOf).orElse(id.id()),
            existing.map(document -> String.valueOf(document.get("name"))).orElse(id.name()))).stored().value();
        LOG.infov("ARM: created key vault {0} (vaultUri={1})", written.get("name"),
            ArmJson.cast(written.get("properties")).get("vaultUri"));
        return Response.ok(written).build();
    }

    private Map<String, Object> vaultDocument(Map<String, Object> body, String id, String vaultName) {
        String location = ArmJson.string(body, "location", "eastus");
        Map<String, Object> bodyProps = body.containsKey("properties")
            ? ArmJson.cast(body.get("properties")) : Map.of();
        String tenantId = ArmJson.string(bodyProps, "tenantId", directory.defaultTenantId());

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", id);
        resource.put("name", vaultName);
        resource.put("type", VAULT_TYPE);
        resource.put("location", location);
        resource.put("properties", Map.of(
            "vaultUri", vaultUri(vaultName),
            "tenantId", tenantId,
            "sku", Map.of("family", "A", "name", "standard"),
            "provisioningState", "Succeeded",
            "enableSoftDelete", false,
            "accessPolicies", List.of()));
        return resource;
    }

    /** As {@link #createOrUpdateVault}: the gate owns the name, and a re-PUT keeps the original id and name. */
    private Response createOrUpdateManagedHsm(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        Map<String, Object> written = hsms.createOrUpdate(id, existing -> hsmDocument(body,
            existing.map(KeyVaultArmService::idOf).orElse(id.id()),
            existing.map(document -> String.valueOf(document.get("name"))).orElse(id.name()))).stored().value();
        LOG.infov("ARM: created managed HSM {0} (hsmUri={1})", written.get("name"),
            ArmJson.cast(written.get("properties")).get("hsmUri"));
        return Response.ok(written).build();
    }

    private Map<String, Object> hsmDocument(Map<String, Object> body, String id, String hsmName) {
        String location = ArmJson.string(body, "location", "eastus");
        Map<String, Object> bodyProps = body.containsKey("properties")
            ? ArmJson.cast(body.get("properties")) : Map.of();
        String tenantId = ArmJson.string(bodyProps, "tenantId", directory.defaultTenantId());
        List<Object> initialAdminObjectIds = bodyProps.get("initialAdminObjectIds") instanceof List<?> list
            ? new ArrayList<>(list) : List.of();

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tenantId", tenantId);
        properties.put("initialAdminObjectIds", initialAdminObjectIds);
        properties.put("enableSoftDelete", true);
        properties.put("softDeleteRetentionInDays", 90);
        properties.put("enablePurgeProtection", true);
        properties.put("hsmUri", "https://" + hsmName + ".managedhsm.azure.net/");
        properties.put("provisioningState", "Succeeded");

        Map<String, Object> sku = body.get("sku") instanceof Map<?, ?> map ? ArmJson.cast(map) : Map.of();
        Map<String, Object> skuOut = Map.of(
            "family", ArmJson.string(sku, "family", "B"),
            "name", ArmJson.string(sku, "name", "Standard_B1"));

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", id);
        resource.put("name", hsmName);
        resource.put("type", HSM_TYPE);
        resource.put("location", location);
        resource.put("sku", skuOut);
        resource.put("properties", properties);
        return resource;
    }

    /**
     * Uses the vault.azure.net domain on the standard port 443 (no explicit port): azurerm validates that the URI
     * matches {@code *.vault.*}. In the test container a socat listener on 443 forwards to the emulator, and host-based
     * routing then dispatches to {@link KeyVaultHandler}.
     */
    private static String vaultUri(String vaultName) {
        return "https://" + vaultName + ".vault.azure.net/";
    }

    // ── The Key Vault data plane at the ARM base URL ────────────────────────

    /**
     * The account the Key Vault data plane serves at the ARM base URL: the one vault's name when exactly one exists,
     * so data-plane responses carry its {@code vault.azure.net} URI and azurerm's vault cache lookup succeeds;
     * {@code kv-default} when there are none or several.
     */
    public String defaultDataPlaneAccount() {
        List<Stored<Map<String, Object>>> all = vaults.all();
        if (all.size() == 1 && all.getFirst().value().get("name") instanceof String name) {
            return name;
        }
        return DEFAULT_DATA_PLANE_ACCOUNT;
    }

    // ── ResourceIndexContributor ────────────────────────────────────────────

    /**
     * The full documents rather than the trimmed index entry: azurerm reads the generic listing to fill its Key Vault
     * cache and looks vaults up by {@code properties.vaultUri}.
     */
    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        return indexed(new ArmScope(sub, rg));
    }

    @Override
    public List<Map<String, Object>> listSubscriptionResources(String sub) {
        return indexed(new ArmScope(sub, null));
    }

    private List<Map<String, Object>> indexed(ArmScope scope) {
        List<Map<String, Object>> resources = new ArrayList<>(documents(vaults, scope));
        resources.addAll(documents(hsms, scope));
        return resources;
    }

    // ── GlobalNameSource ────────────────────────────────────────────────────

    @Override
    public List<Claim> globalNames() {
        return Stream.concat(
                vaults.all().stream().map(stored -> claim(CloudSuffix.KEYVAULT, stored)),
                hsms.all().stream().map(stored -> claim(CloudSuffix.MHSM, stored)))
            .toList();
    }

    private static Claim claim(CloudSuffix suffix, Stored<Map<String, Object>> stored) {
        return new Claim(suffix, String.valueOf(stored.value().get("name")), stored.id());
    }

    @Override
    public void clear() {
        vaults.clear();
        hsms.clear();
    }

    /** The cloud the request's subscription belongs to, as the routing filter resolved it; public without a scope. */
    private static CloudProfile cloud(AzureRequest req) {
        return req.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : req.scope().cloud();
    }

    private static Response armNotFound(String path) {
        return ArmErrors.notFound("Resource not found: " + path);
    }
}
