package io.floci.az.services.keyvault;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.storage.StorageBackend;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Key-object business logic for the Key Vault data plane: CRUD, versions, soft-delete / recover /
 * purge, backup / restore, rotation policy, and crypto dispatch.
 *
 * <p>Plain object (not a CDI bean) built by {@link KeyVaultHandler} with the shared
 * {@code "keyvault"} {@link StorageBackend}; keys are stored under {@code {account}/keys/...} to
 * avoid colliding with secrets. Crypto delegates to the pure-JDK {@link KeyVaultCrypto} engine.
 */
final class KeyVaultKeys {

    private static final Logger LOG = Logger.getLogger(KeyVaultKeys.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final String RECOVERY_LEVEL = "Purgeable";
    private static final long PURGE_RETENTION_SECONDS = 7L * 24 * 3600;

    private final StorageBackend<String, StoredObject> store;

    KeyVaultKeys(StorageBackend<String, StoredObject> store) {
        this.store = store;
    }

    // ── Create / import ────────────────────────────────────────────────────────

    Response createKey(AzureRequest req, String account, String name, boolean hsm) {
        Response invalid = validateKeyName(name);
        if (invalid != null) {
            return invalid;
        }
        Map<String, Object> body = parseBody(req);
        String kty = bodyString(body, "kty", "RSA");
        int keySize = bodyInt(body, "key_size", 0);
        String curve = body.containsKey("curve") ? bodyString(body, "curve", null)
                : bodyString(body, "crv", null);
        @SuppressWarnings("unchecked")
        List<String> keyOps = body.containsKey("key_ops")
                ? (body.get("key_ops") instanceof List<?> l ? (List<String>) l : List.of())
                : KeyVaultCrypto.defaultKeyOps(KeyVaultCrypto.baseKty(kty));

        Map<String, Object> jwk;
        try {
            jwk = KeyVaultCrypto.generateJwk(kty, keySize, curve, keyOps);
        } catch (KeyVaultCrypto.CryptoException e) {
            return kvError(400, "BadParameter", e.getMessage());
        }
        jwk.put("hsm", kty.endsWith("-HSM"));
        jwk.put("tags", stringMap(body.get("tags")));

        return storeNewKey(req, account, name, jwk, body, hsm);
    }

    Response importKey(AzureRequest req, String account, String name, boolean hsm) {
        Response invalid = validateKeyName(name);
        if (invalid != null) {
            return invalid;
        }
        Map<String, Object> body = parseBody(req);
        @SuppressWarnings("unchecked")
        Map<String, Object> supplied = body.get("key") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : new LinkedHashMap<>();

        Map<String, Object> jwk;
        try {
            jwk = KeyVaultCrypto.importJwk(supplied);
        } catch (KeyVaultCrypto.CryptoException e) {
            return kvError(400, "BadParameter", e.getMessage());
        }
        jwk.put("hsm", Boolean.parseBoolean(String.valueOf(body.getOrDefault("HSM", false))));
        jwk.put("tags", stringMap(body.get("tags")));

        return storeNewKey(req, account, name, jwk, body, hsm);
    }

    private Response storeNewKey(AzureRequest req, String account, String name,
            Map<String, Object> jwk, Map<String, Object> body, boolean hsm) {
        // Recreating a name that is soft-deleted (not purged) is a 409 until it is recovered or purged.
        if (store.get(deletedKeyKey(account, name, hsm)).isPresent()) {
            return kvError(409, "Conflict",
                    "A key with (name/id) " + name + " was recently deleted and must be recovered or purged first.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = body.get("attributes") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : new LinkedHashMap<>();

        long now = Instant.now().getEpochSecond();
        String versionId = newVersionId();

        Map<String, String> meta = new HashMap<>();
        meta.put("version", versionId);
        meta.put("created", String.valueOf(now));
        meta.put("updated", String.valueOf(now));
        try {
            normalizeAttrMeta(meta, attrs);
        } catch (IllegalArgumentException e) {
            return kvError(400, "BadParameter", e.getMessage());
        }

        StoredObject versionObj = new StoredObject(keyVersionKey(account, name, versionId, hsm),
                toBytes(jwk), meta, Instant.now(), newVersionId().substring(0, 16));
        store.put(versionObj.key(), versionObj);

        Map<String, String> latestMeta = new HashMap<>(versionObj.metadata());
        latestMeta.put("latestVersion", versionId);
        String latestKey = keyLatestKey(account, name, hsm);
        store.put(latestKey, new StoredObject(latestKey, versionObj.data(), latestMeta,
                versionObj.lastModified(), versionObj.etag()));

        return Response.ok(toJson(keyBundle(account, name, versionId, versionObj, hsm)),
                "application/json").build();
    }

    // ── Read ───────────────────────────────────────────────────────────────────

    Response getKey(String account, String name, boolean hsm) {
        Optional<StoredObject> opt = store.get(keyLatestKey(account, name, hsm));
        if (opt.isEmpty()) {
            return keyNotFound(name);
        }
        StoredObject obj = opt.get();
        String versionId = obj.metadata().getOrDefault("latestVersion", obj.metadata().get("version"));
        return Response.ok(toJson(keyBundle(account, name, versionId, obj, hsm)), "application/json").build();
    }

    Response getKeyVersion(String account, String name, String version, boolean hsm) {
        if (store.get(deletedKeyKey(account, name, hsm)).isPresent()) {
            return keyNotFound(name + "/" + version);
        }
        Optional<StoredObject> opt = store.get(keyVersionKey(account, name, version, hsm));
        if (opt.isEmpty()) {
            return keyNotFound(name + "/" + version);
        }
        return Response.ok(toJson(keyBundle(account, name, version, opt.get(), hsm)), "application/json").build();
    }

    Response listKeys(String account, boolean hsm) {
        String prefix = keysPrefix(account, hsm);
        List<Map<String, Object>> items = store.scan(k -> k.startsWith(prefix))
                .stream()
                .filter(obj -> !obj.key().substring(prefix.length()).contains("/"))
                .map(obj -> {
                    String name = obj.key().substring(prefix.length());
                    return keyItem(account, name, obj, hsm, false);
                })
                .collect(Collectors.toCollection(ArrayList::new));
        return jsonList(items);
    }

    Response listKeyVersions(String account, String name, boolean hsm) {
        if (store.get(deletedKeyKey(account, name, hsm)).isPresent()) {
            return keyNotFound(name);
        }
        String prefix = keyVersionsPrefix(account, name, hsm);
        List<Map<String, Object>> items = store.scan(k -> k.startsWith(prefix))
                .stream()
                .map(obj -> {
                    String version = obj.key().substring(prefix.length());
                    return keyItem(account, name + "/" + version, obj, hsm, true);
                })
                .collect(Collectors.toCollection(ArrayList::new));
        return jsonList(items);
    }

    // ── Update (PATCH) ─────────────────────────────────────────────────────────

    Response updateKeyProperties(AzureRequest req, String account, String name, String version, boolean hsm) {
        if (store.get(deletedKeyKey(account, name, hsm)).isPresent()) {
            return keyNotFound(name + "/" + version);
        }
        String versionKey = keyVersionKey(account, name, version, hsm);
        Optional<StoredObject> opt = store.get(versionKey);
        if (opt.isEmpty()) {
            return keyNotFound(name + "/" + version);
        }
        StoredObject existing = opt.get();
        Map<String, Object> body = parseBody(req);
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = body.get("attributes") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : new LinkedHashMap<>();

        Map<String, Object> data = parseStoredData(existing);
        if (body.containsKey("tags")) {
            data.put("tags", body.get("tags"));
        }

        Map<String, String> meta = new HashMap<>(existing.metadata());
        try {
            normalizeAttrMeta(meta, attrs);
        } catch (IllegalArgumentException e) {
            return kvError(400, "BadParameter", e.getMessage());
        }
        long now = Instant.now().getEpochSecond();
        meta.put("updated", String.valueOf(now));

        Instant updatedAt = Instant.now();
        String newEtag = newVersionId().substring(0, 16);
        StoredObject updated = new StoredObject(versionKey, toBytes(data), meta, updatedAt, newEtag);
        store.put(versionKey, updated);

        String latestKey = keyLatestKey(account, name, hsm);
        store.get(latestKey).ifPresent(latest -> {
            if (version.equals(latest.metadata().get("latestVersion"))) {
                Map<String, String> latestMeta = new HashMap<>(meta);
                latestMeta.put("latestVersion", version);
                store.put(latestKey, new StoredObject(latestKey, updated.data(), latestMeta, updatedAt, newEtag));
            }
        });

        return Response.ok(toJson(keyBundle(account, name, version, updated, hsm)), "application/json").build();
    }

    /**
     * {@code PATCH /keys/{name}} with no version: the Azure CLI's {@code key set-attributes} PATCHes
     * the latest version with an empty version segment. Resolves the latest version and delegates.
     */
    Response updateKeyPropertiesLatest(AzureRequest req, String account, String name, boolean hsm) {
        Optional<StoredObject> opt = store.get(keyLatestKey(account, name, hsm));
        if (opt.isEmpty()) {
            return keyNotFound(name);
        }
        String version = opt.get().metadata().getOrDefault("latestVersion", opt.get().metadata().get("version"));
        return updateKeyProperties(req, account, name, version, hsm);
    }

    // ── Delete / deleted / recover / purge ─────────────────────────────────────

    Response deleteKey(String account, String name, boolean hsm) {
        String latestKey = keyLatestKey(account, name, hsm);
        Optional<StoredObject> opt = store.get(latestKey);
        if (opt.isEmpty()) {
            return keyNotFound(name);
        }
        StoredObject obj = opt.get();
        long now = Instant.now().getEpochSecond();
        long purge = now + PURGE_RETENTION_SECONDS;

        Map<String, String> deletedMeta = new HashMap<>(obj.metadata());
        deletedMeta.put("deletedDate", String.valueOf(now));
        deletedMeta.put("scheduledPurgeDate", String.valueOf(purge));
        String deletedKey = deletedKeyKey(account, name, hsm);
        store.put(deletedKey, new StoredObject(deletedKey, obj.data(), deletedMeta,
                obj.lastModified(), obj.etag()));
        store.delete(latestKey);

        String versionId = obj.metadata().getOrDefault("latestVersion", obj.metadata().get("version"));
        return Response.ok(toJson(deletedKeyBundle(account, name, versionId,
                store.get(deletedKey).get(), now, purge, hsm)), "application/json").build();
    }

    Response getDeletedKey(String account, String name, boolean hsm) {
        Optional<StoredObject> opt = store.get(deletedKeyKey(account, name, hsm));
        if (opt.isEmpty()) {
            return deletedKeyNotFound(name);
        }
        StoredObject obj = opt.get();
        String versionId = obj.metadata().getOrDefault("latestVersion", obj.metadata().get("version"));
        long deletedDate = parseLong(obj.metadata().get("deletedDate"), 0L);
        long purgeDate = parseLong(obj.metadata().get("scheduledPurgeDate"), 0L);
        return Response.ok(toJson(deletedKeyBundle(account, name, versionId, obj, deletedDate, purgeDate, hsm)),
                "application/json").build();
    }

    Response listDeletedKeys(String account, boolean hsm) {
        String prefix = deletedKeysPrefix(account, hsm);
        List<Map<String, Object>> items = store.scan(k -> k.startsWith(prefix))
                .stream()
                .map(obj -> {
                    String name = obj.key().substring(prefix.length());
                    String versionId = obj.metadata().getOrDefault("latestVersion", obj.metadata().get("version"));
                    long deleted = parseLong(obj.metadata().get("deletedDate"), 0L);
                    long purge = parseLong(obj.metadata().get("scheduledPurgeDate"), 0L);
                    return deletedKeyItem(account, name, versionId, obj, deleted, purge, hsm);
                })
                .collect(Collectors.toCollection(ArrayList::new));
        return jsonList(items);
    }

    Response recoverDeletedKey(String account, String name, boolean hsm) {
        String deletedKey = deletedKeyKey(account, name, hsm);
        Optional<StoredObject> opt = store.get(deletedKey);
        if (opt.isEmpty()) {
            return deletedKeyNotFound(name);
        }
        StoredObject obj = opt.get();

        Map<String, String> restoredMeta = new HashMap<>(obj.metadata());
        restoredMeta.remove("deletedDate");
        restoredMeta.remove("scheduledPurgeDate");

        String latestKey = keyLatestKey(account, name, hsm);
        store.put(latestKey, new StoredObject(latestKey, obj.data(), restoredMeta,
                obj.lastModified(), obj.etag()));

        String versionId = restoredMeta.getOrDefault("latestVersion", restoredMeta.get("version"));
        String versionKey = keyVersionKey(account, name, versionId, hsm);
        if (store.get(versionKey).isEmpty()) {
            Map<String, String> versionMeta = new HashMap<>(restoredMeta);
            versionMeta.remove("latestVersion");
            store.put(versionKey, new StoredObject(versionKey, obj.data(), versionMeta,
                    obj.lastModified(), obj.etag()));
        }

        store.delete(deletedKey);

        return Response.ok(toJson(keyBundle(account, name, versionId, store.get(latestKey).get(), hsm)),
                "application/json").build();
    }

    Response purgeDeletedKey(String account, String name, boolean hsm) {
        String deletedKey = deletedKeyKey(account, name, hsm);
        if (store.get(deletedKey).isEmpty()) {
            return deletedKeyNotFound(name);
        }
        store.delete(deletedKey);
        String versionPrefix = keyVersionsPrefix(account, name, hsm);
        store.scan(k -> k.startsWith(versionPrefix)).forEach(obj -> store.delete(obj.key()));
        // Also remove any rotation policy so a recreated key does not inherit it.
        store.delete(rotationPolicyKey(account, name, hsm));
        return Response.noContent().build();
    }

    // ── Backup / restore ───────────────────────────────────────────────────────

    Response backupKey(String account, String name, boolean hsm) {
        Optional<StoredObject> opt = store.get(keyLatestKey(account, name, hsm));
        if (opt.isEmpty()) {
            return keyNotFound(name);
        }
        StoredObject obj = opt.get();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        List<Map<String, Object>> versions = new ArrayList<>();
        String versionPrefix = keyVersionsPrefix(account, name, hsm);
        store.scan(k -> k.startsWith(versionPrefix)).forEach(v -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("version", v.key().substring(versionPrefix.length()));
            entry.put("data", parseStoredData(v));
            entry.put("meta", v.metadata());
            versions.add(entry);
        });
        snapshot.put("name", name);
        snapshot.put("versions", versions);
        snapshot.put("latestVersion", obj.metadata().getOrDefault("latestVersion", obj.metadata().get("version")));
        snapshot.put("tags", parseStoredData(obj).get("tags"));
        snapshot.put("rotationPolicy", store.get(rotationPolicyKey(account, name, hsm))
                .map(this::parseStoredData).orElse(null));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("value", URL.encodeToString(toBytes(snapshot)));
        return Response.ok(toJson(response), "application/json").build();
    }

    Response restoreKey(AzureRequest req, String account, boolean hsm) {
        Map<String, Object> body = parseBody(req);
        String value = bodyString(body, "value", null);
        if (value == null) {
            return kvError(400, "BadParameter", "A backup value is required.");
        }
        String name = null;
        try {
            Map<String, Object> snapshot;
            try {
                snapshot = MAPPER.readValue(KeyVaultCrypto.b64UrlDecode(value), new TypeReference<>() {});
            } catch (Exception e) {
                return kvError(400, "BadParameter", "Invalid backup value.");
            }

            name = bodyString(snapshot, "name", null);
            if (name == null || !name.matches("^[a-zA-Z0-9-]+$")) {
                return kvError(400, "BadParameter", "Invalid key name in backup snapshot.");
            }
            if (store.get(keyLatestKey(account, name, hsm)).isPresent()
                    || store.get(deletedKeyKey(account, name, hsm)).isPresent()) {
                return kvError(409, "Conflict", "A key with (name/id) " + name + " already exists in this key vault.");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> versions = snapshot.get("versions") instanceof List<?> l
                    ? (List<Map<String, Object>>) l : List.of();
            if (versions.size() > 100) {
                return kvError(400, "BadParameter", "Backup snapshot exceeds maximum version limit of 100.");
            }
            String latestVersion = bodyString(snapshot, "latestVersion", null);

            // Validate pass: every entry must be a well-formed JWK before anything is written,
            // so a malformed later entry cannot leave orphaned version objects behind.
            List<RestoredVersion> validated = new ArrayList<>();
            for (Map<String, Object> entry : versions) {
                String version = bodyString(entry, "version", null);
                if (version == null || version.isEmpty()) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> data = entry.get("data") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m : null;
                if (data == null) {
                    continue;
                }
                Map<String, Object> validatedJwk = KeyVaultCrypto.importJwk(data);
                if (data.get("hsm") != null) {
                    validatedJwk.put("hsm", data.get("hsm"));
                }
                if (data.get("tags") != null) {
                    validatedJwk.put("tags", data.get("tags"));
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> rawMeta = entry.get("meta") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m : new LinkedHashMap<>();
                Map<String, String> safeMeta = new HashMap<>();
                for (Map.Entry<?, ?> mEntry : rawMeta.entrySet()) {
                    if (mEntry.getKey() != null && mEntry.getValue() != null) {
                        safeMeta.put(String.valueOf(mEntry.getKey()), String.valueOf(mEntry.getValue()));
                    }
                }
                try {
                    normalizeAttrMeta(safeMeta, rawMeta);
                } catch (IllegalArgumentException e) {
                    return kvError(400, "BadParameter", e.getMessage());
                }
                validated.add(new RestoredVersion(version, validatedJwk, safeMeta));
            }

            if (validated.isEmpty()) {
                return kvError(404, "KeyNotFound", "No key versions were restored for " + name + ".");
            }

            String chosenLatest = latestVersion;
            boolean latestFound = false;
            for (RestoredVersion restored : validated) {
                if (restored.version().equals(chosenLatest)) {
                    latestFound = true;
                    break;
                }
            }
            if (!latestFound) {
                return kvError(400, "BadParameter", "Backup snapshot latestVersion does not match any restored version.");
            }

            // Write pass: only after every entry has validated successfully.
            StoredObject latest = null;
            for (RestoredVersion restored : validated) {
                StoredObject versionObj = new StoredObject(keyVersionKey(account, name, restored.version(), hsm),
                        toBytes(restored.jwk()), restored.meta(), Instant.now(), newVersionId().substring(0, 16));
                store.put(versionObj.key(), versionObj);
                if (restored.version().equals(chosenLatest)) {
                    latest = versionObj;
                }
            }

            Map<String, String> latestMeta = new HashMap<>(latest.metadata());
            latestMeta.put("latestVersion", chosenLatest);
            store.put(keyLatestKey(account, name, hsm), new StoredObject(keyLatestKey(account, name, hsm),
                    latest.data(), latestMeta, latest.lastModified(), latest.etag()));

            if (snapshot.get("rotationPolicy") instanceof Map<?, ?> policy) {
                @SuppressWarnings("unchecked")
                Map<String, Object> policyMap = (Map<String, Object>) policy;
                long now = Instant.now().getEpochSecond();
                Map<String, String> meta = new HashMap<>();
                meta.put("created", String.valueOf(now));
                meta.put("updated", String.valueOf(now));
                store.put(rotationPolicyKey(account, name, hsm), new StoredObject(rotationPolicyKey(account, name, hsm),
                        toBytes(policyMap), meta, Instant.now(), newVersionId().substring(0, 16)));
            }

            return Response.ok(toJson(keyBundle(account, name, chosenLatest,
                    store.get(keyLatestKey(account, name, hsm)).get(), hsm)), "application/json").build();
        } catch (KeyVaultCrypto.CryptoException e) {
            return kvError(400, "BadParameter", e.getMessage());
        } catch (Exception e) {
            LOG.warnf("Key restore failed for %s: %s", name, sanitizeLogValue(e.getMessage()));
            return kvError(400, "BadParameter", "Invalid backup snapshot.");
        }
    }

    // ── Rotation policy ────────────────────────────────────────────────────────

    Response getRotationPolicy(String account, String name, boolean hsm) {
        if (store.get(keyLatestKey(account, name, hsm)).isEmpty()) {
            return keyNotFound(name);
        }
        Map<String, Object> policy = store.get(rotationPolicyKey(account, name, hsm))
                .map(this::parseStoredData).orElseGet(this::defaultPolicy);
        return Response.ok(toJson(policyBundle(account, name, policy, hsm)), "application/json").build();
    }

    Response putRotationPolicy(AzureRequest req, String account, String name, boolean hsm) {
        if (store.get(keyLatestKey(account, name, hsm)).isEmpty()) {
            return keyNotFound(name);
        }
        Map<String, Object> body = parseBody(req);
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("lifetimeActions", body.getOrDefault("lifetimeActions", List.of()));
        policy.put("attributes", body.getOrDefault("attributes", new LinkedHashMap<>()));

        long now = Instant.now().getEpochSecond();
        Map<String, String> meta = new HashMap<>();
        meta.put("created", String.valueOf(now));
        meta.put("updated", String.valueOf(now));
        String key = rotationPolicyKey(account, name, hsm);
        store.put(key, new StoredObject(key, toBytes(policy), meta, Instant.now(),
                newVersionId().substring(0, 16)));

        return Response.ok(toJson(policyBundle(account, name, policy, hsm)), "application/json").build();
    }

    Response rotateKey(String account, String name, boolean hsm) {
        Optional<StoredObject> opt = store.get(keyLatestKey(account, name, hsm));
        if (opt.isEmpty()) {
            return keyNotFound(name);
        }
        StoredObject current = opt.get();
        Map<String, Object> data = parseStoredData(current);
        String kty = (String) data.get("kty");
        String crv = (String) data.get("crv");
        int keySize = data.get("keySize") instanceof Number n ? n.intValue() : 0;
        @SuppressWarnings("unchecked")
        List<String> keyOps = data.get("key_ops") instanceof List<?> l ? (List<String>) l : null;

        Map<String, Object> newJwk;
        try {
            newJwk = KeyVaultCrypto.generateJwk(kty, keySize, crv, keyOps);
        } catch (KeyVaultCrypto.CryptoException e) {
            return kvError(400, "BadParameter", e.getMessage());
        }
        if (data.get("hsm") != null) {
            newJwk.put("hsm", data.get("hsm"));
        }
        if (data.get("tags") != null) {
            newJwk.put("tags", data.get("tags"));
        }

        long now = Instant.now().getEpochSecond();
        String versionId = newVersionId();

        Map<String, String> versionMeta = new HashMap<>();
        versionMeta.put("version", versionId);
        versionMeta.put("enabled", current.metadata().getOrDefault("enabled", "true"));
        versionMeta.put("nbf", current.metadata().getOrDefault("nbf", ""));
        versionMeta.put("exp", current.metadata().getOrDefault("exp", ""));
        versionMeta.put("created", String.valueOf(now));
        versionMeta.put("updated", String.valueOf(now));

        StoredObject versionObj = new StoredObject(keyVersionKey(account, name, versionId, hsm),
                toBytes(newJwk), versionMeta, Instant.now(), newVersionId().substring(0, 16));
        store.put(versionObj.key(), versionObj);

        Map<String, String> latestMeta = new HashMap<>(versionMeta);
        latestMeta.put("latestVersion", versionId);
        store.put(keyLatestKey(account, name, hsm), new StoredObject(keyLatestKey(account, name, hsm),
                toBytes(newJwk), latestMeta, Instant.now(), newVersionId().substring(0, 16)));

        return Response.ok(toJson(keyBundle(account, name, versionId,
                store.get(keyVersionKey(account, name, versionId, hsm)).get(), hsm)), "application/json").build();
    }

    // ── Cryptographic operations ───────────────────────────────────────────────

    Response cryptoOp(AzureRequest req, String account, String name, String version, String op, boolean hsm) {
        StoredObject keyObj = resolveKeyForCrypto(account, name, version, hsm);
        if (keyObj == null) {
            return keyNotFound(name + (version != null && !version.isEmpty() ? "/" + version : ""));
        }
        if (!"true".equals(keyObj.metadata().getOrDefault("enabled", "true"))) {
            return kvError(403, "Forbidden", "The key " + name + " is disabled.");
        }
        long nowEpoch = Instant.now().getEpochSecond();
        Long exp = parseLongNullable(keyObj.metadata().get("exp"));
        if (exp != null && nowEpoch > exp) {
            return kvError(403, "Forbidden", "The key " + name + " is expired.");
        }
        Long nbf = parseLongNullable(keyObj.metadata().get("nbf"));
        if (nbf != null && nowEpoch < nbf) {
            return kvError(403, "Forbidden", "The key " + name + " is not yet valid.");
        }

        Map<String, Object> data = parseStoredData(keyObj);

        Map<String, Object> body = parseBody(req);
        String alg = bodyString(body, "alg", null);
        if (alg == null) {
            return kvError(400, "BadParameter", "An algorithm is required.");
        }

        // Key-type/algorithm mismatch (or unknown algorithm) is a 400, before the key_ops check.
        String requiredKty = KeyVaultCrypto.keyTypeForAlg(alg);
        if (requiredKty == null || !requiredKty.equals(KeyVaultCrypto.baseKty((String) data.get("kty")))) {
            return kvError(400, "BadParameter", "Algorithm " + alg + " is not supported for this key.");
        }

        String requiredOp = requiredKeyOp(op);
        if (requiredOp != null && !permits(data.get("key_ops"), requiredOp)) {
            return kvError(403, "Forbidden", "The operation " + op + " is not permitted on this key.");
        }

        String resolvedVersion = version != null && !version.isEmpty()
                ? version : keyObj.metadata().getOrDefault("latestVersion", keyObj.metadata().get("version"));
        String kid = keyVersionId(account, name, resolvedVersion, hsm);

        try {
            switch (op) {
                case "encrypt", "wrapkey" -> {
                    byte[] value = decodeField(body, "value");
                    if (value == null) {
                        return kvError(400, "BadParameter", "A value is required.");
                    }
                    byte[] iv = decodeField(body, "iv");
                    byte[] aad = decodeField(body, "aad");
                    KeyVaultCrypto.CipherResult result = KeyVaultCrypto.encrypt(data, alg, value, iv, aad);
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("kid", kid);
                    resp.put("value", KeyVaultCrypto.b64Url(result.value()));
                    if (result.iv() != null) {
                        resp.put("iv", KeyVaultCrypto.b64Url(result.iv()));
                    }
                    if (result.tag() != null) {
                        resp.put("tag", KeyVaultCrypto.b64Url(result.tag()));
                    }
                    return Response.ok(toJson(resp), "application/json").build();
                }
                case "decrypt", "unwrapkey" -> {
                    byte[] value = decodeField(body, "value");
                    if (value == null) {
                        return kvError(400, "BadParameter", "A value is required.");
                    }
                    byte[] iv = decodeField(body, "iv");
                    byte[] aad = decodeField(body, "aad");
                    byte[] tag = decodeField(body, "tag");
                    byte[] plaintext = KeyVaultCrypto.decrypt(data, alg, value, iv, aad, tag);
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("kid", kid);
                    resp.put("value", KeyVaultCrypto.b64Url(plaintext));
                    return Response.ok(toJson(resp), "application/json").build();
                }
                case "sign" -> {
                    byte[] digest = decodeField(body, "value");
                    if (digest == null) {
                        return kvError(400, "BadParameter", "A value is required.");
                    }
                    byte[] signature = KeyVaultCrypto.sign(data, alg, digest);
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("kid", kid);
                    resp.put("value", KeyVaultCrypto.b64Url(signature));
                    return Response.ok(toJson(resp), "application/json").build();
                }
                case "verify" -> {
                    byte[] digest = decodeField(body, "digest");
                    if (digest == null) {
                        return kvError(400, "BadParameter", "A digest is required.");
                    }
                    byte[] signature = decodeField(body, "value");
                    if (signature == null) {
                        return kvError(400, "BadParameter", "A value is required.");
                    }
                    boolean valid = KeyVaultCrypto.verify(data, alg, digest, signature);
                    // Strictly {"value": bool} — no kid field, or SDK VerifyResult deserialization breaks.
                    return Response.ok(toJson(Map.of("value", valid)), "application/json").build();
                }
                default -> {
                    return kvError(400, "BadParameter", "Unsupported operation: " + op);
                }
            }
        } catch (KeyVaultCrypto.CryptoException e) {
            return kvError(400, "BadParameter", e.getMessage());
        }
    }

    private StoredObject resolveKeyForCrypto(String account, String name, String version, boolean hsm) {
        if (store.get(deletedKeyKey(account, name, hsm)).isPresent()) {
            return null;
        }
        if (version != null && !version.isEmpty()) {
            return store.get(keyVersionKey(account, name, version, hsm)).orElse(null);
        }
        return store.get(keyLatestKey(account, name, hsm)).orElse(null);
    }

    private static String requiredKeyOp(String op) {
        return switch (op) {
            case "encrypt" -> "encrypt";
            case "decrypt" -> "decrypt";
            case "wrapkey" -> "wrapKey";
            case "unwrapkey" -> "unwrapKey";
            case "sign" -> "sign";
            case "verify" -> "verify";
            default -> null;
        };
    }

    private static boolean permits(Object keyOps, String op) {
        if (keyOps == null) {
            return false;
        }
        if (keyOps instanceof List<?> list) {
            return list.contains(op);
        }
        return false;
    }

    // ── Response builders ──────────────────────────────────────────────────────

    private Map<String, Object> keyBundle(String account, String name, String version,
            StoredObject obj, boolean hsm) {
        Map<String, Object> data = parseStoredData(obj);
        Map<String, Object> key = KeyVaultCrypto.publicJwk(data);
        key.put("kid", keyVersionId(account, name, version, hsm));
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("key", key);
        bundle.put("attributes", buildKeyAttributes(obj.metadata()));
        bundle.put("tags", data.getOrDefault("tags", new LinkedHashMap<>()));
        return bundle;
    }

    private Map<String, Object> deletedKeyBundle(String account, String name, String version,
            StoredObject obj, long deletedDate, long purgeDate, boolean hsm) {
        Map<String, Object> bundle = keyBundle(account, name, version, obj, hsm);
        bundle.put("recoveryId", recoveryId(account, name, hsm));
        bundle.put("deletedDate", deletedDate);
        bundle.put("scheduledPurgeDate", purgeDate);
        return bundle;
    }

    private Map<String, Object> keyItem(String account, String idPath, StoredObject obj,
            boolean hsm, boolean includeVersion) {
        Map<String, Object> data = parseStoredData(obj);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kid", includeVersion ? keyVersionIdFromPath(account, idPath, hsm)
                : keyBaseId(account, idPath, hsm));
        item.put("attributes", buildKeyAttributes(obj.metadata()));
        item.put("tags", data.getOrDefault("tags", new LinkedHashMap<>()));
        return item;
    }

    private Map<String, Object> deletedKeyItem(String account, String name, String version,
            StoredObject obj, long deletedDate, long purgeDate, boolean hsm) {
        Map<String, Object> item = keyItem(account, name, obj, hsm, false);
        item.put("recoveryId", recoveryId(account, name, hsm));
        item.put("deletedDate", deletedDate);
        item.put("scheduledPurgeDate", purgeDate);
        return item;
    }

    private Map<String, Object> buildKeyAttributes(Map<String, String> meta) {
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

    private Map<String, Object> defaultPolicy() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("expiryTime", null);
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("lifetimeActions", List.of());
        policy.put("attributes", attributes);
        return policy;
    }

    private Map<String, Object> policyBundle(String account, String name, Map<String, Object> policy, boolean hsm) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", keyBaseId(account, name, hsm) + "/rotationpolicy");
        out.put("lifetimeActions", policy.getOrDefault("lifetimeActions", List.of()));
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = new LinkedHashMap<>(
                policy.get("attributes") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
        attrs.putIfAbsent("created", Instant.now().getEpochSecond());
        attrs.putIfAbsent("updated", Instant.now().getEpochSecond());
        out.put("attributes", attrs);
        return out;
    }

    private Response jsonList(List<Map<String, Object>> items) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", items);
        result.put("nextLink", null);
        return Response.ok(toJson(result), "application/json").build();
    }

    // ── Storage helpers ────────────────────────────────────────────────────────

    // Managed HSM and Key Vault share the same handler, so storage keys are scoped per flavor to
    // keep {account}-keyvault and {account}-managedhsm namespaces fully isolated. The non-HSM
    // layout ({account}/keys/...) is unchanged from before; HSM keys live under {account}/hsm/.

    private String keyLatestKey(String account, String name, boolean hsm) {
        return (hsm ? account + "/hsm/keys/" : account + "/keys/") + name;
    }

    private String keyVersionKey(String account, String name, String version, boolean hsm) {
        return keyLatestKey(account, name, hsm) + "/versions/" + version;
    }

    private String deletedKeyKey(String account, String name, boolean hsm) {
        return (hsm ? account + "/hsm/deletedkeys/" : account + "/deletedkeys/") + name;
    }

    private String rotationPolicyKey(String account, String name, boolean hsm) {
        return keyLatestKey(account, name, hsm) + "/rotationpolicy";
    }

    private String keysPrefix(String account, boolean hsm) {
        return hsm ? account + "/hsm/keys/" : account + "/keys/";
    }

    private String keyVersionsPrefix(String account, String name, boolean hsm) {
        return keyLatestKey(account, name, hsm) + "/versions/";
    }

    private String deletedKeysPrefix(String account, boolean hsm) {
        return hsm ? account + "/hsm/deletedkeys/" : account + "/deletedkeys/";
    }

    // ── URL builders ───────────────────────────────────────────────────────────

    private String vaultHost(String account, boolean hsm) {
        return "https://" + account + (hsm ? ".managedhsm.azure.net" : ".vault.azure.net");
    }

    private String keyVersionId(String account, String name, String version, boolean hsm) {
        return vaultHost(account, hsm) + "/keys/" + name + "/" + version;
    }

    private String keyVersionIdFromPath(String account, String path, boolean hsm) {
        return vaultHost(account, hsm) + "/keys/" + path;
    }

    private String keyBaseId(String account, String name, boolean hsm) {
        return vaultHost(account, hsm) + "/keys/" + name;
    }

    private String recoveryId(String account, String name, boolean hsm) {
        return vaultHost(account, hsm) + "/deletedkeys/" + name;
    }

    // ── Errors ─────────────────────────────────────────────────────────────────

    private Response keyNotFound(String name) {
        return kvError(404, "KeyNotFound",
                "A key with (name/id) " + name + " was not found in this key vault. "
                + "If you recently deleted this key, it may still be recoverable. "
                + "For more information, see https://docs.microsoft.com/en-us/rest/api/keyvault/getkey.");
    }

    private Response deletedKeyNotFound(String name) {
        return kvError(404, "KeyNotFound",
                "A deleted key with (name/id) " + name + " was not found in this key vault.");
    }

    private Response kvError(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("message", message);
        body.put("error", detail);
        return Response.status(status).entity(toJson(body)).type("application/json").build();
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private String newVersionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private byte[] decodeField(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        return KeyVaultCrypto.b64UrlDecode(String.valueOf(value));
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
        return ArmJson.parseBodyMutable(req);
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
        if (val == null || val.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Long parseLongNullable(String val) {
        if (val == null || val.isEmpty() || "null".equals(val)) {
            return null;
        }
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            LOG.warnf("Ignoring malformed nbf/exp metadata value: %s", sanitizeLogValue(val));
            return null;
        }
    }

    /** Neutralizes CR/LF and caps length so attacker-controlled values cannot forge log lines. */
    private static String sanitizeLogValue(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replace("\r", "\\r").replace("\n", "\\n");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) + "..." : cleaned;
    }

    private static Long parseEpochAttribute(Map<String, Object> attrs, String field) {
        Object value = attrs.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                // fall through to the invalid-value error below
            }
        }
        throw new IllegalArgumentException("The '" + field + "' attribute must be a numeric Unix epoch timestamp.");
    }

    /**
     * Sole writer of enabled/nbf/exp metadata: only fields present in {@code attrs} are written and
     * always canonicalized, so partial updates and restore snapshots inherit absent fields.
     *
     * @throws IllegalArgumentException if nbf/exp is present but not a numeric epoch timestamp.
     */
    private static void normalizeAttrMeta(Map<String, String> meta, Map<String, Object> attrs) {
        if (attrs == null) {
            return;
        }
        if (attrs.containsKey("enabled")) {
            meta.put("enabled", String.valueOf(Boolean.parseBoolean(String.valueOf(attrs.get("enabled")))));
        }
        if (attrs.containsKey("nbf")) {
            meta.put("nbf", normalizeEpoch(attrs, "nbf"));
        }
        if (attrs.containsKey("exp")) {
            meta.put("exp", normalizeEpoch(attrs, "exp"));
        }
    }

    /** Canonicalizes one epoch attribute: null/"null"/blank → "" (unset); otherwise parsed (throws if invalid). */
    private static String normalizeEpoch(Map<String, Object> attrs, String field) {
        Object value = attrs.get(field);
        if (value == null || "null".equals(String.valueOf(value))) {
            return "";
        }
        if (value instanceof String s && s.trim().isEmpty()) {
            return "";
        }
        return String.valueOf(parseEpochAttribute(attrs, field));
    }

    private static String bodyString(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v instanceof String s ? s : defaultValue;
    }

    private static int bodyInt(Map<String, Object> map, String key, int defaultValue) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, String>) m;
        }
        return new LinkedHashMap<>();
    }

    private Response validateKeyName(String name) {
        if (name == null || !name.matches("^[a-zA-Z0-9-]+$")) {
            return kvError(400, "BadParameter", "Invalid key name: " + name);
        }
        return null;
    }

    private record RestoredVersion(String version, Map<String, Object> jwk, Map<String, String> meta) {}
}
