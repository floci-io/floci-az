package io.floci.az.services.keyvault;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class KeyVaultHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(KeyVaultHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RECOVERY_LEVEL = "Purgeable";
    // Soft-delete retention (7 days in seconds)
    private static final long PURGE_RETENTION_SECONDS = 7L * 24 * 3600;

    private final StorageBackend<String, StoredObject> store;
    private final EmulatorConfig config;

    @Inject
    public KeyVaultHandler(StorageFactory factory, EmulatorConfig config) {
        this.store = factory.create("keyvault");
        this.config = config;
    }

    @Override
    public String getServiceType() {
        return "keyvault";
    }

    @Override
    public boolean canHandle(AzureRequest req) {
        return "keyvault".equals(req.serviceType());
    }

    @Override
    public Response handle(AzureRequest req) {
        String path = req.resourcePath();
        String method = req.method().toUpperCase();
        String account = req.accountName();

        LOG.debugf("KeyVault %s /%s", method, path);

        // The Azure SDK challenge_auth_policy sends a bodiless probe to elicit a challenge, then
        // retries with the real body. Return 401 with a Bearer challenge so the SDK caches it and
        // sends subsequent requests with the Authorization header and their original bodies.
        String auth = req.headers().getHeaderString("Authorization");
        if (auth == null || auth.isEmpty()) {
            return Response.status(401)
                    .header("WWW-Authenticate",
                            "Bearer authorization=\"https://login.microsoftonline.com/common\", "
                            + "resource=\"https://vault.azure.net\"")
                    .build();
        }

        // Root probe — azurerm provider polls this to confirm the vault is reachable.
        if (path.isEmpty() || path.equals("/")) {
            return Response.ok(java.util.Map.of(
                "type", "Microsoft.KeyVault/vaults",
                "id",   "https://" + account + ".vault.azure.net/"
            )).build();
        }

        if ("secrets".equals(path)) {
            return "GET".equals(method) ? listSecrets(account) : methodNotAllowed();
        }
        if (path.startsWith("secrets/")) {
            return handleSecrets(req, method, account, path.substring("secrets/".length()));
        }
        if ("deletedsecrets".equals(path)) {
            return "GET".equals(method) ? listDeletedSecrets(account) : methodNotAllowed();
        }
        if (path.startsWith("deletedsecrets/")) {
            return handleDeletedSecrets(req, method, account, path.substring("deletedsecrets/".length()));
        }

        // Certificate contacts — azurerm provider reads this after key vault creation.
        // Return an empty contacts list so the provider sees no contacts configured.
        if ("certificates/contacts".equals(path)) {
            if ("GET".equals(method)) {
                return Response.ok(java.util.Map.of(
                    "id", "https://" + account + ".vault.azure.net/certificates/contacts",
                    "contacts", java.util.List.of()
                )).build();
            }
            return methodNotAllowed();
        }

        return kvError(404, "BadRequest", "Resource not found: " + path);
    }

    // -------------------------------------------------------------------------
    // Sub-routers
    // -------------------------------------------------------------------------

    private Response handleSecrets(AzureRequest req, String method, String account, String rest) {
        // /secrets/{name}/backup
        int backupIdx = rest.indexOf("/backup");
        if (backupIdx != -1) {
            String name = rest.substring(0, backupIdx);
            return "POST".equals(method) ? backupSecret(account, name) : methodNotAllowed();
        }

        // /secrets/{name}/versions[/{version}]
        int versionsIdx = rest.indexOf("/versions");
        if (versionsIdx != -1) {
            String name = rest.substring(0, versionsIdx);
            String afterVersions = rest.substring(versionsIdx + "/versions".length());
            if (afterVersions.isEmpty() || "/".equals(afterVersions)) {
                return "GET".equals(method) ? listSecretVersions(account, name) : methodNotAllowed();
            }
            // /secrets/{name}/versions/{version} — same as /secrets/{name}/{version}
            String version = afterVersions.startsWith("/") ? afterVersions.substring(1) : afterVersions;
            return switch (method) {
                case "GET"   -> getSecretVersion(account, name, version);
                case "PATCH" -> updateSecretProperties(req, account, name, version);
                default      -> methodNotAllowed();
            };
        }

        // /secrets/{name}/{version} or /secrets/{name}/ (trailing slash → treat as latest)
        int slash = rest.indexOf('/');
        if (slash != -1) {
            String name    = rest.substring(0, slash);
            String version = rest.substring(slash + 1);
            if (version.isEmpty()) {
                return switch (method) {
                    case "GET"    -> getSecret(account, name);
                    case "PUT"    -> setSecret(req, account, name);
                    case "DELETE" -> deleteSecret(account, name);
                    default       -> methodNotAllowed();
                };
            }
            return switch (method) {
                case "GET"   -> getSecretVersion(account, name, version);
                case "PATCH" -> updateSecretProperties(req, account, name, version);
                default      -> methodNotAllowed();
            };
        }

        // /secrets/{name} (latest)
        return switch (method) {
            case "GET"    -> getSecret(account, rest);
            case "PUT"    -> setSecret(req, account, rest);
            case "DELETE" -> deleteSecret(account, rest);
            default       -> methodNotAllowed();
        };
    }

    private Response handleDeletedSecrets(AzureRequest req, String method, String account, String rest) {
        int recoverIdx = rest.indexOf("/recover");
        if (recoverIdx != -1) {
            String name = rest.substring(0, recoverIdx);
            return "POST".equals(method) ? recoverDeletedSecret(account, name) : methodNotAllowed();
        }
        return switch (method) {
            case "GET"    -> getDeletedSecret(account, rest);
            case "DELETE" -> purgeDeletedSecret(account, rest);
            default       -> methodNotAllowed();
        };
    }

    // -------------------------------------------------------------------------
    // Secret CRUD
    // -------------------------------------------------------------------------

    private Response setSecret(AzureRequest req, String account, String name) {
        Map<String, Object> body = parseBody(req);

        String value = (String) body.getOrDefault("value", "");
        String contentType = (String) body.getOrDefault("contentType", "");
        @SuppressWarnings("unchecked")
        Map<String, String> tags = body.containsKey("tags")
                ? (Map<String, String>) body.get("tags")
                : new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = body.containsKey("attributes")
                ? (Map<String, Object>) body.get("attributes")
                : new LinkedHashMap<>();

        boolean enabled = attrs.containsKey("enabled")
                ? Boolean.parseBoolean(String.valueOf(attrs.get("enabled")))
                : true;
        Long nbf = toEpochSeconds(attrs.get("nbf"));
        Long exp = toEpochSeconds(attrs.get("exp"));

        long now = Instant.now().getEpochSecond();
        // Preserve original created time if updating an existing secret
        long created = store.get(secretLatestKey(account, name))
                .map(o -> parseLong(o.metadata().get("created"), now))
                .orElse(now);

        String versionId = newVersionId();
        StoredObject versionObj = buildSecretObject(secretVersionKey(account, name, versionId),
                value, contentType, tags, enabled, nbf, exp, created, now, versionId);
        store.put(versionObj.key(), versionObj);

        // Update the latest pointer
        Map<String, String> latestMeta = new HashMap<>(versionObj.metadata());
        latestMeta.put("latestVersion", versionId);
        String latestKey = secretLatestKey(account, name);
        store.put(latestKey, new StoredObject(latestKey, versionObj.data(), latestMeta,
                versionObj.lastModified(), versionObj.etag()));

        return Response.ok(toJson(secretBundle(account, name, versionId, versionObj)), "application/json").build();
    }

    private Response getSecret(String account, String name) {
        Optional<StoredObject> opt = store.get(secretLatestKey(account, name));
        if (opt.isEmpty()) return secretNotFound(name);

        StoredObject obj = opt.get();
        if (!"true".equals(obj.metadata().get("enabled"))) {
            return kvError(403, "Forbidden", "The secret " + name + " is disabled.");
        }
        String versionId = obj.metadata().getOrDefault("latestVersion", obj.metadata().get("version"));
        return Response.ok(toJson(secretBundle(account, name, versionId, obj)), "application/json").build();
    }

    private Response getSecretVersion(String account, String name, String version) {
        Optional<StoredObject> opt = store.get(secretVersionKey(account, name, version));
        if (opt.isEmpty()) return secretNotFound(name + "/" + version);
        return Response.ok(toJson(secretBundle(account, name, version, opt.get())), "application/json").build();
    }

    private Response deleteSecret(String account, String name) {
        String latestKey = secretLatestKey(account, name);
        Optional<StoredObject> opt = store.get(latestKey);
        if (opt.isEmpty()) return secretNotFound(name);

        StoredObject obj = opt.get();
        long now  = Instant.now().getEpochSecond();
        long purge = now + PURGE_RETENTION_SECONDS;

        // Move to deleted namespace
        Map<String, String> deletedMeta = new HashMap<>(obj.metadata());
        deletedMeta.put("deletedDate", String.valueOf(now));
        deletedMeta.put("scheduledPurgeDate", String.valueOf(purge));
        String deletedKey = deletedSecretKey(account, name);
        store.put(deletedKey, new StoredObject(deletedKey, obj.data(), deletedMeta,
                obj.lastModified(), obj.etag()));

        store.delete(latestKey);

        String versionId = obj.metadata().getOrDefault("latestVersion", obj.metadata().get("version"));
        return Response.ok(toJson(deletedSecretBundle(account, name, versionId,
                store.get(deletedKey).get(), now, purge)), "application/json").build();
    }

    private Response updateSecretProperties(AzureRequest req, String account, String name, String version) {
        String versionKey = secretVersionKey(account, name, version);
        Optional<StoredObject> opt = store.get(versionKey);
        if (opt.isEmpty()) return secretNotFound(name + "/" + version);

        StoredObject existing = opt.get();
        Map<String, Object> body = parseBody(req);
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = body.containsKey("attributes")
                ? (Map<String, Object>) body.get("attributes")
                : new LinkedHashMap<>();

        Map<String, Object> data = parseStoredData(existing);
        if (body.containsKey("contentType")) data.put("contentType", body.get("contentType"));
        if (body.containsKey("tags"))        data.put("tags", body.get("tags"));

        Map<String, String> meta = new HashMap<>(existing.metadata());
        if (attrs.containsKey("enabled")) meta.put("enabled", String.valueOf(attrs.get("enabled")));
        if (attrs.containsKey("nbf"))     meta.put("nbf", String.valueOf(toEpochSeconds(attrs.get("nbf"))));
        if (attrs.containsKey("exp"))     meta.put("exp", String.valueOf(toEpochSeconds(attrs.get("exp"))));
        long now = Instant.now().getEpochSecond();
        meta.put("updated", String.valueOf(now));

        Instant updatedAt = Instant.now();
        String newEtag = newVersionId().substring(0, 16);
        StoredObject updated = new StoredObject(versionKey, toBytes(data), meta, updatedAt, newEtag);
        store.put(versionKey, updated);

        // Sync to latest pointer if this is still the current version
        String latestKey = secretLatestKey(account, name);
        store.get(latestKey).ifPresent(latest -> {
            if (version.equals(latest.metadata().get("latestVersion"))) {
                Map<String, String> latestMeta = new HashMap<>(meta);
                latestMeta.put("latestVersion", version);
                store.put(latestKey, new StoredObject(latestKey, updated.data(), latestMeta, updatedAt, newEtag));
            }
        });

        return Response.ok(toJson(secretBundle(account, name, version, updated)), "application/json").build();
    }

    // -------------------------------------------------------------------------
    // List operations
    // -------------------------------------------------------------------------

    private Response listSecrets(String account) {
        String prefix = account + "/secrets/";
        List<Map<String, Object>> items = store.scan(k -> k.startsWith(prefix))
                .stream()
                // Only top-level keys — the latest pointers (no slash after prefix)
                .filter(obj -> !obj.key().substring(prefix.length()).contains("/"))
                .map(obj -> {
                    String name = obj.key().substring(prefix.length());
                    return secretItem(account, name, obj, false);
                })
                .collect(Collectors.toCollection(ArrayList::new));

        return jsonList(items);
    }

    private Response listSecretVersions(String account, String name) {
        String prefix = account + "/secrets/" + name + "/versions/";
        List<Map<String, Object>> items = store.scan(k -> k.startsWith(prefix))
                .stream()
                .map(obj -> {
                    String version = obj.key().substring(prefix.length());
                    return secretItem(account, name + "/" + version, obj, true);
                })
                .collect(Collectors.toCollection(ArrayList::new));

        return jsonList(items);
    }

    // -------------------------------------------------------------------------
    // Deleted secret operations
    // -------------------------------------------------------------------------

    private Response getDeletedSecret(String account, String name) {
        Optional<StoredObject> opt = store.get(deletedSecretKey(account, name));
        if (opt.isEmpty()) return deletedSecretNotFound(name);

        StoredObject obj = opt.get();
        String versionId = obj.metadata().getOrDefault("latestVersion", obj.metadata().get("version"));
        long deletedDate = parseLong(obj.metadata().get("deletedDate"), 0L);
        long purgeDate   = parseLong(obj.metadata().get("scheduledPurgeDate"), 0L);
        return Response.ok(toJson(deletedSecretBundle(account, name, versionId, obj, deletedDate, purgeDate)),
                "application/json").build();
    }

    private Response listDeletedSecrets(String account) {
        String prefix = account + "/deletedsecrets/";
        List<Map<String, Object>> items = store.scan(k -> k.startsWith(prefix))
                .stream()
                .map(obj -> {
                    String name      = obj.key().substring(prefix.length());
                    String versionId = obj.metadata().getOrDefault("latestVersion", obj.metadata().get("version"));
                    long deleted     = parseLong(obj.metadata().get("deletedDate"), 0L);
                    long purge       = parseLong(obj.metadata().get("scheduledPurgeDate"), 0L);
                    return deletedSecretItem(account, name, versionId, obj, deleted, purge);
                })
                .collect(Collectors.toCollection(ArrayList::new));

        return jsonList(items);
    }

    private Response recoverDeletedSecret(String account, String name) {
        String deletedKey = deletedSecretKey(account, name);
        Optional<StoredObject> opt = store.get(deletedKey);
        if (opt.isEmpty()) return deletedSecretNotFound(name);

        StoredObject obj = opt.get();

        // Restore latest pointer (strip deleted-specific metadata)
        Map<String, String> restoredMeta = new HashMap<>(obj.metadata());
        restoredMeta.remove("deletedDate");
        restoredMeta.remove("scheduledPurgeDate");

        String latestKey = secretLatestKey(account, name);
        store.put(latestKey, new StoredObject(latestKey, obj.data(), restoredMeta,
                obj.lastModified(), obj.etag()));

        // Restore version if it was purged
        String versionId = restoredMeta.getOrDefault("latestVersion", restoredMeta.get("version"));
        String versionKey = secretVersionKey(account, name, versionId);
        if (store.get(versionKey).isEmpty()) {
            Map<String, String> versionMeta = new HashMap<>(restoredMeta);
            versionMeta.remove("latestVersion");
            store.put(versionKey, new StoredObject(versionKey, obj.data(), versionMeta,
                    obj.lastModified(), obj.etag()));
        }

        store.delete(deletedKey);

        return Response.ok(toJson(secretBundle(account, name, versionId,
                store.get(latestKey).get())), "application/json").build();
    }

    private Response purgeDeletedSecret(String account, String name) {
        String deletedKey = deletedSecretKey(account, name);
        if (store.get(deletedKey).isEmpty()) return deletedSecretNotFound(name);

        store.delete(deletedKey);
        // Purge all versions too
        String versionPrefix = account + "/secrets/" + name + "/versions/";
        store.scan(k -> k.startsWith(versionPrefix)).forEach(obj -> store.delete(obj.key()));

        return Response.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Backup (stub — no restore in v1)
    // -------------------------------------------------------------------------

    private Response backupSecret(String account, String name) {
        Optional<StoredObject> opt = store.get(secretLatestKey(account, name));
        if (opt.isEmpty()) return secretNotFound(name);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("value", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(opt.get().data()));
        return Response.ok(toJson(response), "application/json").build();
    }

    // -------------------------------------------------------------------------
    // Response builders
    // -------------------------------------------------------------------------

    private Map<String, Object> secretBundle(String account, String name, String version, StoredObject obj) {
        Map<String, Object> data = parseStoredData(obj);
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("value", data.get("value"));
        bundle.put("id", secretVersionId(account, name, version));
        bundle.put("attributes", buildAttributes(obj.metadata()));
        bundle.put("contentType", data.getOrDefault("contentType", ""));
        bundle.put("tags", data.getOrDefault("tags", new LinkedHashMap<>()));
        return bundle;
    }

    private Map<String, Object> deletedSecretBundle(String account, String name, String version,
            StoredObject obj, long deletedDate, long purgeDate) {
        Map<String, Object> bundle = secretBundle(account, name, version, obj);
        bundle.put("recoveryId", recoveryId(account, name));
        bundle.put("deletedDate", deletedDate);
        bundle.put("scheduledPurgeDate", purgeDate);
        return bundle;
    }

    /** Secret list item — no value field. */
    private Map<String, Object> secretItem(String account, String idPath, StoredObject obj, boolean includeVersion) {
        Map<String, Object> data = parseStoredData(obj);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", includeVersion ? secretVersionIdFromPath(account, idPath) : secretBaseId(account, idPath));
        item.put("attributes", buildAttributes(obj.metadata()));
        item.put("contentType", data.getOrDefault("contentType", ""));
        item.put("tags", data.getOrDefault("tags", new LinkedHashMap<>()));
        return item;
    }

    private Map<String, Object> deletedSecretItem(String account, String name, String version,
            StoredObject obj, long deletedDate, long purgeDate) {
        Map<String, Object> item = secretItem(account, name, obj, false);
        item.put("recoveryId", recoveryId(account, name));
        item.put("deletedDate", deletedDate);
        item.put("scheduledPurgeDate", purgeDate);
        return item;
    }

    private Map<String, Object> buildAttributes(Map<String, String> meta) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("enabled", Boolean.parseBoolean(meta.getOrDefault("enabled", "true")));
        String nbf = meta.get("nbf");
        attrs.put("nbf", (nbf != null && !nbf.isEmpty() && !"null".equals(nbf)) ? parseLong(nbf, 0L) : null);
        String exp = meta.get("exp");
        attrs.put("exp", (exp != null && !exp.isEmpty() && !"null".equals(exp)) ? parseLong(exp, 0L) : null);
        attrs.put("created", parseLong(meta.get("created"), 0L));
        attrs.put("updated", parseLong(meta.get("updated"), 0L));
        attrs.put("recoveryLevel", RECOVERY_LEVEL);
        attrs.put("recoverableDays", 7);
        return attrs;
    }

    private Response jsonList(List<Map<String, Object>> items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", items);
        result.put("nextLink", null);
        return Response.ok(toJson(result), "application/json").build();
    }

    // -------------------------------------------------------------------------
    // Storage helpers
    // -------------------------------------------------------------------------

    private StoredObject buildSecretObject(String key, String value, String contentType,
            Map<String, String> tags, boolean enabled, Long nbf, Long exp,
            long created, long updated, String versionId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("value", value);
        data.put("contentType", contentType != null ? contentType : "");
        data.put("tags", tags);

        Map<String, String> meta = new HashMap<>();
        meta.put("version", versionId);
        meta.put("enabled", String.valueOf(enabled));
        meta.put("nbf", nbf != null ? String.valueOf(nbf) : "");
        meta.put("exp", exp != null ? String.valueOf(exp) : "");
        meta.put("created", String.valueOf(created));
        meta.put("updated", String.valueOf(updated));

        return new StoredObject(key, toBytes(data), meta, Instant.now(),
                newVersionId().substring(0, 16));
    }

    private String secretLatestKey(String account, String name) {
        return account + "/secrets/" + name;
    }

    private String secretVersionKey(String account, String name, String version) {
        return account + "/secrets/" + name + "/versions/" + version;
    }

    private String deletedSecretKey(String account, String name) {
        return account + "/deletedsecrets/" + name;
    }

    // -------------------------------------------------------------------------
    // URL builders
    //
    // The Key Vault SDK's parse_key_vault_id() requires the path to have
    // exactly 2–3 segments ("secrets/{name}" or "secrets/{name}/{version}").
    // Our path-based routing embeds the account in the URL path which would
    // add an extra segment.  We therefore encode the vault identity in the
    // hostname ("https://{account}-keyvault/…") so the path stays at 3
    // segments and the SDK's URL parser succeeds.
    // -------------------------------------------------------------------------

    private String vaultHost(String account) {
        return "https://" + account + ".vault.azure.net";
    }

    private String secretVersionId(String account, String name, String version) {
        return vaultHost(account) + "/secrets/" + name + "/" + version;
    }

    private String secretVersionIdFromPath(String account, String path) {
        return vaultHost(account) + "/secrets/" + path;
    }

    private String secretBaseId(String account, String name) {
        return vaultHost(account) + "/secrets/" + name;
    }

    private String recoveryId(String account, String name) {
        return vaultHost(account) + "/deletedsecrets/" + name;
    }

    // -------------------------------------------------------------------------
    // Error responses
    // -------------------------------------------------------------------------

    private Response secretNotFound(String name) {
        return kvError(404, "SecretNotFound",
                "A secret with (name/id) " + name + " was not found in this key vault. "
                + "If you recently deleted this secret, it may still be recoverable. "
                + "For more information, see https://docs.microsoft.com/en-us/rest/api/keyvault/getsecret.");
    }

    private Response deletedSecretNotFound(String name) {
        return kvError(404, "SecretNotFound",
                "A deleted secret with (name/id) " + name + " was not found in this key vault.");
    }

    private Response methodNotAllowed() {
        return Response.status(405).build();
    }

    private Response kvError(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("message", message);
        body.put("error", detail);
        return Response.status(status).entity(toJson(body)).type("application/json").build();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private String newVersionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseStoredData(StoredObject obj) {
        try {
            return MAPPER.readValue(obj.data(), new TypeReference<>() {});
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> parseBody(AzureRequest req) {
        try {
            return MAPPER.readValue(req.bodyStream(), new TypeReference<>() {});
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    private byte[] toBytes(Object obj) {
        try {
            return MAPPER.writeValueAsBytes(obj);
        } catch (JsonProcessingException e) {
            return new byte[0];
        }
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private long parseLong(String val, long fallback) {
        if (val == null || val.isEmpty()) return fallback;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Long toEpochSeconds(Object value) {
        if (value == null) return null;
        try {
            return ((Number) value).longValue();
        } catch (ClassCastException e) {
            return null;
        }
    }
}
