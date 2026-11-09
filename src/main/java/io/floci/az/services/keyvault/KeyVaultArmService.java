package io.floci.az.services.keyvault;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.Resettable;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmProviderService;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore.Stored;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.arm.GlobalNameSource;
import io.floci.az.core.arm.StoreIndexSource;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudDefaults;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * <p>Each type is one {@link ArmResourceStore} of the ARM documents responses return, all over the
 * {@value #STORE} backend, keyed by canonical id, so a path that differs only in case addresses the one resource and
 * the casing of its first PUT is what responses echo. The store follows the global storage mode. These resources
 * were kept in memory before, so there are no older entries to read.
 *
 * <p>The data plane does not consult these records: it serves any vault name it is addressed by. The one reader is
 * the Key Vault fallback at the ARM base URL ({@link #defaultDataPlaneAccount()}).
 *
 * <p>Deleting a vault with {@code enableSoftDelete} leaves a record in a third store, which {@code Vaults_GetDeleted},
 * {@code Vaults_ListDeleted} and {@code Vaults_PurgeDeleted} serve. azurerm purges that record on destroy with default
 * settings, and looks it up before every create to decide whether to recover.
 */
@ApplicationScoped
public class KeyVaultArmService implements ArmProviderService, StoreIndexSource, GlobalNameSource, Resettable {

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

    /** {@code Vaults_UpdateAccessPolicy}'s path, in any case: group 1 the vault name, group 2 the operation kind. */
    private static final Pattern ACCESS_POLICY_PATH = Pattern.compile(
        ".*/providers/Microsoft\\.KeyVault/vaults/([^/?]+)/accessPolicies/([^/?]+)/?([?].*)?",
        Pattern.CASE_INSENSITIVE);

    private static final String ACCESS_POLICY_TYPE = VAULT_TYPE + "/accessPolicies";

    /** A deleted vault's id type, which {@link #deletedVaults} is keyed by. */
    private static final String DELETED_VAULT_ID_TYPE = NAMESPACE + "/locations/deletedVaults";

    /** The {@code type} a {@code DeletedVault} answers with, which the spec's examples spell without the location. */
    private static final String DELETED_VAULT_TYPE = NAMESPACE + "/deletedVaults";

    /** A deleted vault's path, in any case: group 1 the location, group 2 the name, group 3 the purge action if any. */
    private static final Pattern DELETED_VAULT_PATH = Pattern.compile(
        ".*/providers/Microsoft\\.KeyVault/locations/([^/?]+)/deletedVaults/([^/?]+)(/purge)?/?([?].*)?",
        Pattern.CASE_INSENSITIVE);

    /** Where a deleted vault's document keeps the vault it replaced, for a recover. Never returned to a client. */
    private static final String ORIGINAL_VAULT = "vault";

    /** The spec's default for {@code softDeleteRetentionInDays}. */
    private static final int DEFAULT_RETENTION_DAYS = 90;

    /**
     * The {@code VaultProperties} a client sets and reads back as they are. Stored only: the data plane enforces none
     * of them. {@code createMode} is left out because the spec's example responses do not return it.
     */
    private static final List<String> STORED_VAULT_SETTINGS = List.of(
        "enabledForDeployment", "enabledForDiskEncryption", "enabledForTemplateDeployment", "enableSoftDelete",
        "softDeleteRetentionInDays", "enableRbacAuthorization", "enablePurgeProtection", "networkAcls",
        "publicNetworkAccess");

    private final ArmResourceStore<Map<String, Object>> vaults;
    private final ArmResourceStore<Map<String, Object>> hsms;
    private final ArmResourceStore<Map<String, Object>> deletedVaults;
    private final GlobalNameRegistry names;
    private final AzureDirectory directory;

    private final CloudDefaults defaults;

    @Inject
    public KeyVaultArmService(StorageFactory storageFactory, GlobalNameRegistry names, AzureDirectory directory,
                              CloudDefaults defaults) {
        this.defaults = defaults;
        this.vaults = ArmResourceStore.create(storageFactory, STORE, VAULT_TYPE, documentType(),
            KeyVaultArmService::idOf, key -> false);
        this.hsms = ArmResourceStore.create(storageFactory, STORE, HSM_TYPE, documentType(),
            KeyVaultArmService::idOf, key -> false);
        this.deletedVaults = ArmResourceStore.create(storageFactory, STORE, DELETED_VAULT_ID_TYPE, documentType(),
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
            : handleSubscriptionPath(req, path, method, sub);
    }

    // ── Subscription level ──────────────────────────────────────────────────

    private Response handleSubscriptionPath(AzureRequest req, String path, String method, String sub) {
        if (path.matches("subscriptions/[^/?]+/providers/(?i:Microsoft\\.KeyVault/deletedVaults)([?].*)?")) {
            return "GET".equals(method) ? listDeletedVaults(sub) : Response.status(405).build();
        }
        // A deleted managed HSM is not kept, so there are none to list. `az keyvault list-deleted` lists both kinds.
        if (path.matches("subscriptions/[^/?]+/providers/(?i:Microsoft\\.KeyVault/deletedManagedHSMs)([?].*)?")) {
            return "GET".equals(method) ? Response.ok(Map.of("value", List.of())).build() : Response.status(405).build();
        }
        Matcher deleted = DELETED_VAULT_PATH.matcher(path);
        if (deleted.matches()) {
            return deletedVault(req, method, sub, deleted.group(1), deleted.group(2), deleted.group(3) != null);
        }
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
     * Only a vault's or managed HSM's own path reaches the resource handlers, plus a vault's
     * {@code accessPolicies/{operationKind}}, which updates a vault that exists in the request's own scope. Any other
     * path under one is a child operation floci-az does not serve, so it answers ARM 404 rather than writing, reading
     * or deleting the parent: the gate owns names for the declared types only, so a child PUT treated as the parent's
     * create would write a vault whose name another subscription owns.
     */
    private Response handleResourceGroupPath(AzureRequest req, String path, String method, String sub, String rg) {
        if (path.matches(".*/providers/(?i:Microsoft\\.KeyVault)/vaults([?].*)?")) {
            return list(vaults, new ArmScope(sub, rg));
        }
        if (path.matches(".*/providers/(?i:Microsoft\\.KeyVault)/managedHSMs([?].*)?")) {
            return list(hsms, new ArmScope(sub, rg));
        }
        Matcher accessPolicy = ACCESS_POLICY_PATH.matcher(path);
        if (accessPolicy.matches()) {
            Optional<VaultAccessPolicies.Kind> kind = VaultAccessPolicies.Kind.of(accessPolicy.group(2));
            if (kind.isEmpty()) {
                return armNotFound(path);
            }
            return "PUT".equals(method)
                ? updateAccessPolicy(req, resourceId(sub, rg, "vaults", accessPolicy.group(1)), kind.get())
                : Response.status(405).build();
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
            case "PATCH"  -> updateVault(req, id);
            case "GET"    -> get(vaults, id, "vaults/" + vaultName);
            case "DELETE" -> {
                vaults.delete(id).ifPresent(deleted -> softDelete(req, deleted));
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

    // ── Deleted vaults ──────────────────────────────────────────────────────

    /** Records a vault just deleted, unless it was created with {@code enableSoftDelete: false}. */
    private void softDelete(AzureRequest req, Stored<Map<String, Object>> deleted) {
        Map<String, Object> vault = deleted.value();
        Map<String, Object> properties = ArmJson.cast(vault.get("properties"));
        if (Boolean.FALSE.equals(properties.get("enableSoftDelete"))) {
            return;
        }
        String location = String.valueOf(vault.get("location"));
        String name = String.valueOf(vault.get("name"));
        int retentionDays = properties.get("softDeleteRetentionInDays") instanceof Number days
            ? days.intValue() : DEFAULT_RETENTION_DAYS;
        Instant deletedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("vaultId", idOf(vault));
        details.put("location", location);
        details.put("tags", vault.get("tags") instanceof Map<?, ?> tags ? ArmJson.cast(tags) : Map.of());
        details.put("deletionDate", deletedAt.toString());
        details.put("scheduledPurgeDate", deletedAt.plus(retentionDays, ChronoUnit.DAYS).toString());
        if (properties.get("enablePurgeProtection") != null) {
            details.put("purgeProtectionEnabled", properties.get("enablePurgeProtection"));
        }

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", deletedVaultId(req, deleted.id().subscriptionId(), location, name).id());
        document.put("name", name);
        document.put("type", DELETED_VAULT_TYPE);
        document.put("properties", details);
        document.put(ORIGINAL_VAULT, vault);
        deletedVaults.put(document);
        LOG.infov("ARM: key vault {0} is soft-deleted", name);
    }

    private ArmResourceId deletedVaultId(AzureRequest req, String sub, String location, String name) {
        String id = "/subscriptions/" + sub + "/providers/" + NAMESPACE + "/locations/"
            + CloudCatalog.normalizeLocation(cloud(req), location) + "/deletedVaults/" + name;
        return ArmResourceId.parse(id).orElseThrow();
    }

    private Response listDeletedVaults(String sub) {
        return Response.ok(Map.of("value", deletedVaults.list(new ArmScope(sub, null)).stream()
            .map(stored -> deletedVaultResponse(stored.value())).toList())).build();
    }

    private Response deletedVault(AzureRequest req, String method, String sub, String location, String name,
                                  boolean purge) {
        ArmResourceId id = deletedVaultId(req, sub, location, name);
        if (purge) {
            return "POST".equals(method) ? purgeDeletedVault(id) : Response.status(405).build();
        }
        if (!"GET".equals(method)) {
            return Response.status(405).build();
        }
        return deletedVaults.get(id)
            .map(stored -> Response.ok(deletedVaultResponse(stored.value())).build())
            .orElseGet(() -> armNotFound("deletedVaults/" + name));
    }

    /**
     * {@code Vaults_PurgeDeleted}, answering the spec's 200. The spec lets only the service purge a vault with purge
     * protection, but no public source gives the refusal's code; ARM's generic {@code Conflict} stands in.
     */
    private Response purgeDeletedVault(ArmResourceId id) {
        Optional<Stored<Map<String, Object>>> found = deletedVaults.get(id);
        if (found.isEmpty()) {
            return armNotFound("deletedVaults/" + id.name());
        }
        Map<String, Object> details = ArmJson.cast(found.get().value().get("properties"));
        if (Boolean.TRUE.equals(details.get("purgeProtectionEnabled"))) {
            return ArmErrors.error(409, "Conflict", "The vault '" + id.name()
                + "' has purge protection enabled, so only the Key Vault service can purge it.");
        }
        deletedVaults.delete(id);
        LOG.infov("ARM: purged deleted key vault {0}", id.name());
        return Response.ok().build();
    }

    private static Map<String, Object> deletedVaultResponse(Map<String, Object> document) {
        Map<String, Object> response = new LinkedHashMap<>(document);
        response.remove(ORIGINAL_VAULT);
        return response;
    }

    /** The subscription's deleted vaults of this name: a location is part of their ids, but a name is one vault. */
    private List<Stored<Map<String, Object>>> deletedVaultsNamed(String sub, String name) {
        return deletedVaults.list(new ArmScope(sub, null)).stream()
            .filter(stored -> name.equalsIgnoreCase(stored.id().name()))
            .toList();
    }

    /** The PUT a recover amounts to: the deleted vault, with what the request sets over it. */
    private static Map<String, Object> recovering(Map<String, Object> vault, Map<String, Object> body) {
        Map<String, Object> merged = new LinkedHashMap<>(vault);
        if (body.get("location") != null) {
            merged.put("location", body.get("location"));
        }
        if (body.get("tags") != null) {
            merged.put("tags", body.get("tags"));
        }
        Map<String, Object> properties = new LinkedHashMap<>(ArmJson.cast(vault.get("properties")));
        if (body.get("properties") instanceof Map<?, ?> requested) {
            properties.putAll(ArmJson.cast(requested));
        }
        merged.put("properties", properties);
        return merged;
    }

    private static boolean isRecover(Map<String, Object> body) {
        return body.get("properties") instanceof Map<?, ?> properties
            && "recover".equalsIgnoreCase(String.valueOf(properties.get("createMode")));
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
     *
     * <p>{@code createMode: recover} restores a deleted vault of this name, keeping what the request does not set.
     * Either way the deleted record goes: no local source says what Azure answers to a plain create over a soft-deleted
     * name, so it is not refused, and a {@code createMode: recover} with nothing to recover is an ordinary create.
     */
    private Response createOrUpdateVault(AzureRequest req, ArmResourceId id) {
        Map<String, Object> request = ArmJson.parseBodyLenient(req);
        List<Stored<Map<String, Object>>> deleted = deletedVaultsNamed(id.subscriptionId(), id.name());
        Map<String, Object> body = isRecover(request) && !deleted.isEmpty()
            ? recovering(ArmJson.cast(deleted.getFirst().value().get(ORIGINAL_VAULT)), request)
            : request;
        Optional<List<Map<String, Object>>> listed = VaultAccessPolicies.listed(body);
        Map<String, Object> written = vaults.createOrUpdate(id, existing -> vaultDocument(body,
            existing.map(KeyVaultArmService::idOf).orElse(id.id()),
            existing.map(document -> String.valueOf(document.get("name"))).orElse(id.name()),
            listed.orElseGet(() -> existing.map(document -> accessPolicies(ArmJson.cast(document.get("properties"))))
                .orElse(List.of())), defaults.defaultLocation(req.scope()))).stored().value();
        deleted.forEach(stored -> deletedVaults.delete(stored.id()));
        LOG.infov("ARM: created key vault {0} (vaultUri={1})", written.get("name"),
            ArmJson.cast(written.get("properties")).get("vaultUri"));
        return Response.ok(written).build();
    }

    /**
     * {@code Vaults_UpdateAccessPolicy}: applies the listed entries to the vault's {@code properties.accessPolicies}
     * as {@code kind} says ({@link VaultAccessPolicies}), in one store update, and answers 200 with the
     * {@code VaultAccessPolicyParameters} the spec's example returns: the request's entries under
     * {@code .../accessPolicies/}. The vault must exist in the request's own scope; the name another subscription owns
     * is not found here and nothing is written.
     */
    private Response updateAccessPolicy(AzureRequest req, ArmResourceId vaultId, VaultAccessPolicies.Kind kind) {
        List<Map<String, Object>> requested = VaultAccessPolicies.requested(ArmJson.parseBodyLenient(req));
        Optional<Stored<Map<String, Object>>> updated = vaults.update(vaultId, vault -> {
            Map<String, Object> properties = new LinkedHashMap<>(ArmJson.cast(vault.get("properties")));
            properties.put("accessPolicies",
                VaultAccessPolicies.apply(kind, accessPolicies(properties), requested));
            Map<String, Object> document = new LinkedHashMap<>(vault);
            document.put("properties", properties);
            return document;
        });
        if (updated.isEmpty()) {
            return armNotFound("vaults/" + vaultId.name());
        }
        LOG.infov("ARM: {0} access policies on key vault {1}", kind, updated.get().value().get("name"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", ACCESS_POLICY_TYPE);
        response.put("id", idOf(updated.get().value()) + "/accessPolicies/");
        response.put("properties", Map.of("accessPolicies", requested));
        return Response.ok(response).build();
    }

    private static List<Map<String, Object>> accessPolicies(Map<String, Object> properties) {
        if (!(properties.get("accessPolicies") instanceof List<?> entries)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> map) {
                result.add(ArmJson.cast(map));
            }
        }
        return result;
    }

    /**
     * The vault document a PUT writes. {@code accessPolicies} is the body's list when it carries one, the stored
     * vault's when a re-PUT omits it, and empty for a new vault without one: the 2026-02-01 schema does not mark it
     * required, though its description says it is unless {@code createMode} is {@code recover}, so an omitted list is
     * accepted rather than refused.
     */
    private Map<String, Object> vaultDocument(Map<String, Object> body, String id, String vaultName,
                                              List<Map<String, Object>> accessPolicies, String defaultLocation) {
        String location = ArmJson.string(body, "location", defaultLocation);
        Map<String, Object> bodyProps = body.containsKey("properties")
            ? ArmJson.cast(body.get("properties")) : Map.of();
        String tenantId = ArmJson.string(bodyProps, "tenantId", directory.defaultTenantId());

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("vaultUri", vaultUri(vaultName));
        properties.put("tenantId", tenantId);
        properties.put("sku", bodyProps.get("sku") instanceof Map<?, ?> sku
            ? ArmJson.cast(sku) : Map.of("family", "A", "name", "standard"));
        properties.put("provisioningState", "Succeeded");
        properties.put("enableSoftDelete", true);
        properties.put("accessPolicies", accessPolicies);
        copyStoredSettings(bodyProps, properties);

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", id);
        resource.put("name", vaultName);
        resource.put("type", VAULT_TYPE);
        resource.put("location", location);
        if (body.get("tags") instanceof Map<?, ?> tags) {
            resource.put("tags", ArmJson.cast(tags));
        }
        resource.put("properties", properties);
        return resource;
    }

    private static void copyStoredSettings(Map<String, Object> from, Map<String, Object> to) {
        for (String setting : STORED_VAULT_SETTINGS) {
            if (from.get(setting) != null) {
                to.put(setting, from.get(setting));
            }
        }
    }

    /**
     * {@code Vaults_Update}: merges the {@code VaultPatchParameters} into the stored vault and answers 200 with it.
     * What the body omits stays as it is, {@code tags} replace the stored tags whole, and the properties the service
     * generates are never taken from the body. The vault must exist in the request's own scope.
     */
    private Response updateVault(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        Map<String, Object> patch = body.get("properties") instanceof Map<?, ?> map ? ArmJson.cast(map) : Map.of();
        Optional<List<Map<String, Object>>> listed = VaultAccessPolicies.listed(body);
        Optional<Stored<Map<String, Object>>> updated = vaults.update(id, vault -> {
            Map<String, Object> properties = new LinkedHashMap<>(ArmJson.cast(vault.get("properties")));
            copyStoredSettings(patch, properties);
            if (patch.get("tenantId") instanceof String tenantId) {
                properties.put("tenantId", tenantId);
            }
            if (patch.get("sku") instanceof Map<?, ?> sku) {
                properties.put("sku", ArmJson.cast(sku));
            }
            listed.ifPresent(accessPolicies -> properties.put("accessPolicies", accessPolicies));
            Map<String, Object> document = new LinkedHashMap<>(vault);
            document.put("properties", properties);
            if (body.get("tags") instanceof Map<?, ?> tags) {
                document.put("tags", ArmJson.cast(tags));
            }
            return document;
        });
        if (updated.isEmpty()) {
            return armNotFound("vaults/" + id.name());
        }
        LOG.infov("ARM: updated key vault {0}", updated.get().value().get("name"));
        return Response.ok(updated.get().value()).build();
    }

    /** As {@link #createOrUpdateVault}: the gate owns the name, and a re-PUT keeps the original id and name. */
    private Response createOrUpdateManagedHsm(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        Map<String, Object> written = hsms.createOrUpdate(id, existing -> hsmDocument(body,
            existing.map(KeyVaultArmService::idOf).orElse(id.id()),
            existing.map(document -> String.valueOf(document.get("name"))).orElse(id.name()),
            defaults.defaultLocation(req.scope()))).stored().value();
        LOG.infov("ARM: created managed HSM {0} (hsmUri={1})", written.get("name"),
            ArmJson.cast(written.get("properties")).get("hsmUri"));
        return Response.ok(written).build();
    }

    private Map<String, Object> hsmDocument(Map<String, Object> body, String id, String hsmName,
                                            String defaultLocation) {
        String location = ArmJson.string(body, "location", defaultLocation);
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

    // ── StoreIndexSource ────────────────────────────────────────────

    /**
     * The full documents rather than the trimmed index entry: azurerm reads the generic listing to fill its Key Vault
     * cache and looks vaults up by {@code properties.vaultUri}.
     */
    @Override
    public List<Map<String, Object>> indexEntries(ArmScope scope) {
        return indexed(scope);
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

    @Override
    public boolean holds(CloudSuffix suffix, String name, ArmResourceId owner) {
        ArmResourceStore<Map<String, Object>> store = switch (suffix) {
            case KEYVAULT -> vaults;
            case MHSM -> hsms;
            default -> null;
        };
        return store != null
            && store.get(owner).filter(stored -> name.equalsIgnoreCase(String.valueOf(stored.value().get("name")))).isPresent();
    }

    private static Claim claim(CloudSuffix suffix, Stored<Map<String, Object>> stored) {
        return new Claim(suffix, String.valueOf(stored.value().get("name")), stored.id());
    }

    @Override
    public void clear() {
        vaults.clear();
        hsms.clear();
        deletedVaults.clear();
    }

    /** The cloud the request's subscription belongs to, as the routing filter resolved it; public without a scope. */
    private static CloudProfile cloud(AzureRequest req) {
        return req.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : req.scope().cloud();
    }

    private static Response armNotFound(String path) {
        return ArmErrors.notFound("Resource not found: " + path);
    }
}
