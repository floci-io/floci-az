package io.floci.az.services.storageaccounts;

import io.floci.az.config.EmulatorConfig;
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
import io.floci.az.services.blob.BlobServiceHandler;
import io.floci.az.services.queue.QueueServiceHandler;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.floci.az.core.auth.StorageSasAuthorization.DEFAULT_STORAGE_ACCOUNT_KEY;

/**
 * Microsoft.Storage's management plane: storage accounts, ARM shells over the Blob, Queue and Table data planes, which
 * know an account by its name alone. Their containers and queues are answered from those data planes, and
 * {@code blobServices/default} and {@code queueServices/default} are synthesized. Reached through {@code ArmHandler}'s
 * provider lane, inside the ARM request gate, which checks the account name and owns it ({@link StorageArmSurface})
 * before a PUT gets here.
 *
 * <p>Accounts are one {@link ArmResourceStore} of the ARM documents responses return, over the {@value #STORE}
 * backend, keyed by canonical id, so a path that differs only in case addresses the one account and the casing of its
 * first PUT is what responses echo. The store follows the global storage mode. Accounts were kept in memory before, so
 * there are no older entries to read.
 *
 * <p>The package sits beside {@code blob} and {@code queue} rather than inside either: an account spans both data
 * planes (and Table's), so neither owns it.
 */
@ApplicationScoped
public class StorageAccountArmService
        implements ArmProviderService, ResourceIndexContributor, GlobalNameSource, Resettable {

    private static final Logger LOG = Logger.getLogger(StorageAccountArmService.class);

    /** The {@link StorageFactory} backend accounts live in. */
    static final String STORE = "arm-storage";

    private static final String NAMESPACE = "Microsoft.Storage";
    private static final String ACCOUNT_TYPE = NAMESPACE + "/storageAccounts";

    private final ArmResourceStore<Map<String, Object>> accounts;
    private final GlobalNameRegistry names;
    private final EmulatorConfig config;
    private final BlobServiceHandler blobHandler;
    private final QueueServiceHandler queueHandler;

    @Inject
    public StorageAccountArmService(StorageFactory storageFactory, GlobalNameRegistry names, EmulatorConfig config,
                                    BlobServiceHandler blobHandler, QueueServiceHandler queueHandler) {
        this.accounts = ArmResourceStore.create(storageFactory, STORE, documentType(),
            StorageAccountArmService::idOf, key -> false);
        this.names = names;
        this.config = config;
        this.blobHandler = blobHandler;
        this.queueHandler = queueHandler;
    }

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> documentType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private static String idOf(Map<String, Object> document) {
        return String.valueOf(document.get("id"));
    }

    /**
     * The Blob data plane keeps each account's hierarchical-namespace switch in memory; an account that survived a
     * restart sets it again, so its DFS endpoint answers as its {@code isHnsEnabled} says.
     */
    void restoreHierarchicalNamespaces(@Observes StartupEvent event) {
        for (Stored<Map<String, Object>> stored : accounts.all()) {
            Map<String, Object> properties = ArmJson.cast(stored.value().get("properties"));
            blobHandler.setHierarchicalNamespaceEnabled(stored.id().name(),
                Boolean.TRUE.equals(properties.get("isHnsEnabled")));
        }
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
        // The resource group the routing filter resolved; none for a subscription-level path.
        Optional<String> rg = req.resourceGroup();
        return rg.isPresent()
            ? handleResourceGroupPath(req, path, method, sub, rg.get())
            : handleSubscriptionPath(req, path, sub);
    }

    // ── Subscription level ──────────────────────────────────────────────────

    private Response handleSubscriptionPath(AzureRequest req, String path, String sub) {
        // azurerm lists every account of the subscription during refresh and destroy.
        if (path.matches("subscriptions/[^/?]+/providers/(?i:Microsoft\\.Storage)/storageAccounts([?].*)?")) {
            return list(new ArmScope(sub, null));
        }
        // ARM paths are case-insensitive, and clients disagree on the casing of provider actions.
        if (path.toLowerCase(Locale.ROOT)
                .matches("subscriptions/[^/]+/providers/microsoft\\.storage/checknameavailability([?].*)?")) {
            return checkNameAvailability(req);
        }
        return armNotFound(path);
    }

    /**
     * {@code StorageAccounts_CheckNameAvailability}: a name outside the account name rule is {@code AccountNameInvalid},
     * an owned one {@code AlreadyExists}, answered from the name registry.
     */
    private Response checkNameAvailability(AzureRequest req) {
        String name = ArmJson.string(ArmJson.parseBodyLenient(req), "name", "");
        if (!StorageArmSurface.ACCOUNT_NAME.accepts(name)) {
            return Response.ok(Map.of(
                    "nameAvailable", false,
                    "reason", "AccountNameInvalid",
                    "message", StorageArmSurface.ACCOUNT_NAME.message(name))).build();
        }
        if (names.owner(cloud(req), CloudSuffix.BLOB, name).isEmpty()) {
            return Response.ok(Map.of("nameAvailable", true)).build();
        }
        return Response.ok(Map.of("nameAvailable", false, "reason", "AlreadyExists",
                "message", String.format(StorageArmSurface.ACCOUNT_DNS_NAME.conflictMessage(), name))).build();
    }

    // ── Resource group level ────────────────────────────────────────────────

    private Response handleResourceGroupPath(AzureRequest req, String path, String method, String sub, String rg) {
        // Containers, queues and keys reach the shared data plane, which knows only the account name.
        // Under a scope that does not own the account they must stop here, before touching the owner's data.
        if (path.contains("/storageAccounts/") && !path.matches(".*/storageAccounts/[^/?]+([?].*)?")) {
            String account = accountName(path);
            if (ownedByAnother(req, accountId(sub, rg, account))) {
                return armNotFound("storageAccounts/" + account);
            }
        }

        // POST .../storageAccounts/{name}/listKeys
        if (path.contains("/storageAccounts/") && path.endsWith("/listKeys")) {
            return listKeys(accountName(path));
        }

        // .../storageAccounts/{name}/blobServices/default/containers/{container}
        if (path.contains("/blobServices/default/containers/")) {
            return handleBlobContainer(req, path, method);
        }

        // .../storageAccounts/{name}/blobServices/default  (blobServices properties)
        if (path.contains("/blobServices/default") && !path.contains("/containers/")) {
            return Response.ok(Map.of("id", path, "name", "default",
                    "type", "Microsoft.Storage/storageAccounts/blobServices",
                    "properties", Map.of())).build();
        }

        // .../storageAccounts/{name}/queueServices/default/queues/{queue}
        if (path.contains("/queueServices/default/queues/")) {
            return handleQueueResource(path, method);
        }

        // .../storageAccounts/{name}/queueServices/default  (queueServices properties)
        if (path.contains("/queueServices/default") && !path.contains("/queues/")) {
            return Response.ok(Map.of("id", path, "name", "default",
                    "type", "Microsoft.Storage/storageAccounts/queueServices",
                    "properties", Map.of())).build();
        }

        // GET .../storageAccounts  (list in RG)
        if (path.matches(".*/providers/(?i:Microsoft\\.Storage)/storageAccounts([?].*)?")) {
            return list(new ArmScope(sub, rg));
        }

        // PUT/GET/DELETE .../storageAccounts/{name}
        if (path.contains("/storageAccounts/")) {
            String account = accountName(path);
            ArmResourceId id = accountId(sub, rg, account);
            return switch (method) {
                case "PUT"    -> createOrUpdate(req, id);
                case "PATCH"  -> update(req, id);
                case "GET"    -> accounts.get(id)
                        .map(stored -> Response.ok(stored.value()).build())
                        .orElseGet(() -> armNotFound("storageAccounts/" + account));
                case "DELETE" -> delete(id);
                default       -> Response.status(405).build();
            };
        }

        return armNotFound(path);
    }

    /** Whether the account's name belongs to a resource other than {@code id}, in another scope. */
    private boolean ownedByAnother(AzureRequest req, ArmResourceId id) {
        return names.owner(cloud(req), CloudSuffix.BLOB, id.name())
                .filter(owner -> !owner.canonicalKey().equals(id.canonicalKey()))
                .isPresent();
    }

    private static ArmResourceId accountId(String sub, String rg, String account) {
        String id = "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/" + ACCOUNT_TYPE + "/" + account;
        return ArmResourceId.parse(id).orElseThrow();
    }

    private static String accountName(String path) {
        return ArmPaths.resourceName(path, "storageAccounts", "unknown");
    }

    private Response list(ArmScope scope) {
        return Response.ok(Map.of("value", documents(scope))).build();
    }

    /**
     * The accounts in {@code scope}, ordered by canonical id: a stable order whatever the backend's key order, which
     * for the memory backend is a hash order.
     */
    private List<Map<String, Object>> documents(ArmScope scope) {
        return accounts.list(scope).stream()
                .sorted(Comparator.comparing(stored -> stored.id().canonicalKey()))
                .map(Stored::value)
                .toList();
    }

    /**
     * {@code StorageAccounts_Create}. The gate has checked the name and reserved it before this runs, so a name
     * another account owns never gets here. A re-PUT keeps the id and name the account was created with, and
     * refuses to change {@code isHnsEnabled}. Synchronized so the check and the write are one step.
     */
    private synchronized Response createOrUpdate(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        Map<String, Object> requestedProperties = ArmJson.cast(body.get("properties"));
        Optional<Map<String, Object>> existing = accounts.get(id).map(Stored::value);
        Map<String, Object> existingProperties = ArmJson.cast(existing.map(e -> e.get("properties")).orElse(null));
        boolean existingHnsEnabled = Boolean.TRUE.equals(existingProperties.get("isHnsEnabled"));
        if (existing.isPresent() && requestedProperties.containsKey("isHnsEnabled")
                && existingHnsEnabled != Boolean.TRUE.equals(requestedProperties.get("isHnsEnabled"))) {
            return ArmErrors.error(400, "AccountPropertyCannotBeUpdated",
                    "The property 'isHnsEnabled' was specified in the input, but it cannot be updated as it is "
                            + "read-only. For more information, see - https://aka.ms/storageaccountupdate");
        }
        String account = existing.map(e -> String.valueOf(e.get("name"))).orElse(id.name());
        boolean hnsEnabled = requestedProperties.containsKey("isHnsEnabled")
                ? Boolean.TRUE.equals(requestedProperties.get("isHnsEnabled"))
                : existingProperties.containsKey("isHnsEnabled")
                        ? existingHnsEnabled
                        : config.services().blob().hierarchicalNamespaceAccounts().contains(account);
        String accountId = existing.map(StorageAccountArmService::idOf).orElse(id.id());
        Map<String, Object> written = accounts.createOrUpdate(id,
                ignored -> document(accountId, account, body, hnsEnabled)).stored().value();
        blobHandler.setHierarchicalNamespaceEnabled(account, hnsEnabled);
        LOG.infov("ARM: created storage account {0}", account);
        return Response.ok(written).build();
    }

    private static Map<String, Object> document(String id, String account, Map<String, Object> body,
                                                boolean hnsEnabled) {
        String location = ArmJson.string(body, "location", "eastus");
        // Return domain-based storage endpoints so the azurerm provider can parse the account name. No port, so
        // storage URLs use the standard HTTP port 80: test containers forward port 80 to the emulator via socat.
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", id);
        resource.put("name", account);
        resource.put("type", ACCOUNT_TYPE);
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
                        "blob",  "http://" + account + ".blob.core.windows.net/",
                        "dfs",   "http://" + account + ".dfs.core.windows.net/",
                        "queue", "http://" + account + ".queue.core.windows.net/",
                        "table", "http://" + account + ".table.core.windows.net/"),
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
        return resource;
    }

    /**
     * {@code StorageAccounts_Update}: the {@code tags} the body names replace the account's, as azurerm updates them;
     * nothing else in the body is applied yet.
     */
    private Response update(AzureRequest req, ArmResourceId id) {
        Map<String, Object> tags = tags(ArmJson.parseBodyLenient(req).get("tags"));
        Optional<Stored<Map<String, Object>>> updated = tags == null
                ? accounts.get(id)
                : accounts.update(id, resource -> withTags(resource, tags));
        return updated
                .map(stored -> Response.ok(stored.value()).build())
                .orElseGet(() -> armNotFound("storageAccounts/" + id.name()));
    }

    /** {@code resource} with {@code tags} in place of its own, right after its location. */
    private static Map<String, Object> withTags(Map<String, Object> resource, Map<String, Object> tags) {
        Map<String, Object> updated = new LinkedHashMap<>();
        resource.forEach((key, value) -> {
            if (!key.equals("tags")) {
                updated.put(key, value);
            }
            if (key.equals("location")) {
                updated.put("tags", tags);
            }
        });
        return updated;
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

    /** {@code StorageAccounts_Delete}: 200 whether or not there was an account. */
    private synchronized Response delete(ArmResourceId id) {
        accounts.delete(id).ifPresent(stored ->
                blobHandler.removeHierarchicalNamespaceOverride(String.valueOf(stored.value().get("name"))));
        return Response.ok().build();
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

    private Response handleBlobContainer(AzureRequest req, String path, String method) {
        String account   = accountName(path);
        String container = ArmPaths.afterSegment(path, "/containers/", "unknown");

        return switch (method) {
            case "PUT" -> {
                blobHandler.ensureContainer(account, container);
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

    private Response handleQueueResource(String path, String method) {
        String account = accountName(path);
        String queue   = ArmPaths.afterSegment(path, "/queues/", "unknown");

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

    // ── Resource index, global names, reset ─────────────────────────────────

    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        return documents(new ArmScope(sub, rg));
    }

    @Override
    public List<Map<String, Object>> listSubscriptionResources(String sub) {
        return documents(new ArmScope(sub, null));
    }

    @Override
    public List<Claim> globalNames() {
        return accounts.all().stream()
                .map(stored -> new Claim(CloudSuffix.BLOB, stored.id().name(), stored.id()))
                .toList();
    }

    @Override
    public void clear() {
        accounts.clear();
    }

    private static CloudProfile cloud(AzureRequest req) {
        return req.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : req.scope().cloud();
    }

    private static Response armNotFound(String path) {
        return ArmErrors.notFound("Resource not found: " + path);
    }
}
