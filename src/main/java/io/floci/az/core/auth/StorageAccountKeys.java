package io.floci.az.core.auth;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.Resettable;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The two access keys of each storage account, the one owner of their values: {@code listKeys} and
 * {@code regenerateKey} read and change them, and every Shared Key, Shared Key Lite and key-signed SAS check asks
 * {@link #signer} which of them signed a request.
 *
 * <p>An account's keys are created the first time anything asks for them. {@code key1} is the account's entry under
 * {@code floci-az.auth.storage-account-keys}, or the development key, so clients that sign with either keep working;
 * {@code key2} is 512 random bits, as Azure generates its keys. Regenerating a key replaces it with 512 random bits
 * and leaves the other one as it is. Keys live in the {@value #STORE} backend, so they follow the global storage
 * mode and a regenerated key survives a restart.
 */
@ApplicationScoped
public class StorageAccountKeys implements Resettable {

    /** The well-known {@code devstoreaccount1} development key: {@code key1} of each account with no configured key. */
    public static final String DEVELOPMENT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    /** The two keys {@code listKeys} returns and {@code regenerateKey} accepts, in the order they are listed. */
    public static final List<String> KEY_NAMES = List.of("key1", "key2");

    static final String STORE = "storage-account-keys";

    /** "Azure generates two 512-bit storage account access keys." */
    private static final int KEY_BYTES = 64;
    private static final String HMAC_SHA256 = "HmacSHA256";

    /** One account key as {@code listKeys} returns it. */
    public record AccountKey(String keyName, String value, Instant creationTime) {
    }

    private final StorageBackend<String, StoredObject> store;
    private final Map<String, String> configuredKeys;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public StorageAccountKeys(StorageFactory storageFactory, EmulatorConfig config) {
        this.store = storageFactory.create(STORE);
        this.configuredKeys = Map.copyOf(config.auth().storageAccountKeys());
        for (String key : configuredKeys.values()) {
            if (Base64.getDecoder().decode(key).length == 0) {
                throw new IllegalArgumentException("Storage account keys must not be empty");
            }
        }
    }

    /** The account's keys, {@code key1} first, created on first use. */
    public synchronized List<AccountKey> keys(String account) {
        Map<String, String> stored = stored(account);
        return KEY_NAMES.stream()
                .map(name -> new AccountKey(name, stored.get(name), Instant.parse(stored.get(name + ".created"))))
                .toList();
    }

    /**
     * Replaces {@code keyName} with a new random key and returns both keys; empty when {@code keyName} is neither
     * {@code key1} nor {@code key2}.
     */
    public synchronized Optional<List<AccountKey>> regenerate(String account, String keyName) {
        if (!KEY_NAMES.contains(keyName)) {
            return Optional.empty();
        }
        Map<String, String> keys = new HashMap<>(stored(account));
        keys.put(keyName, randomKey());
        keys.put(keyName + ".created", Instant.now().toString());
        put(account, keys);
        return Optional.of(keys(account));
    }

    /** Drops the account's keys, so an account created again under the name gets new ones. */
    public synchronized void forget(String account) {
        store.delete(id(account));
    }

    /** The name of the account key whose HMAC-SHA256 of {@code stringToSign} is {@code signature}, if any. */
    public Optional<String> signer(String account, String stringToSign, String signature) {
        if (signature == null) {
            return Optional.empty();
        }
        byte[] presented = signature.getBytes(StandardCharsets.UTF_8);
        for (AccountKey key : keys(account)) {
            byte[] expected = hmac(key.value(), stringToSign).getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(expected, presented)) {
                return Optional.of(key.keyName());
            }
        }
        return Optional.empty();
    }

    /** The value of one of the account's keys. */
    public String value(String account, String keyName) {
        return keys(account).stream().filter(key -> key.keyName().equals(keyName))
                .findFirst().orElseThrow().value();
    }

    @Override
    public synchronized void clear() {
        store.clear();
    }

    private Map<String, String> stored(String account) {
        Optional<StoredObject> stored = store.get(id(account));
        if (stored.isPresent()) {
            return stored.get().metadata();
        }
        String now = Instant.now().toString();
        Map<String, String> keys = Map.of(
                "key1", configuredKeys.getOrDefault(account, DEVELOPMENT_KEY), "key1.created", now,
                "key2", randomKey(), "key2.created", now);
        put(account, keys);
        return keys;
    }

    private void put(String account, Map<String, String> keys) {
        store.put(id(account), new StoredObject(id(account), new byte[0], Map.copyOf(keys), Instant.now(), null));
    }

    private String randomKey() {
        byte[] bytes = new byte[KEY_BYTES];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String id(String account) {
        return account.toLowerCase(Locale.ROOT);
    }

    static String hmac(String base64Key, String stringToSign) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Key), HMAC_SHA256));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute a storage signature", e);
        }
    }
}
