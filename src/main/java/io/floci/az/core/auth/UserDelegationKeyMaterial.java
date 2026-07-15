package io.floci.az.core.auth;

import jakarta.enterprise.context.ApplicationScoped;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Derives account-scoped delegation keys from a process-local random master key.
 *
 * <p>No per-account key state is retained, which keeps account churn bounded. Restarting the
 * emulator rotates the master key and invalidates previously signed user delegation SAS tokens.
 */
@ApplicationScoped
public class UserDelegationKeyMaterial {

    public static final String SIGNED_OBJECT_ID = "00000000-0000-0000-0000-000000000000";
    public static final String SIGNED_TENANT_ID = "00000000-0000-0000-0000-000000000000";

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final SecretKey masterKey;

    public UserDelegationKeyMaterial() {
        this.masterKey = generateMasterKey();
    }

    public String signingKeyForAccount(String accountName) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(masterKey);
            byte[] accountKey = mac.doFinal(accountName.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(accountKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to derive user delegation key material", e);
        }
    }

    private static SecretKey generateMasterKey() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(HMAC_SHA256);
            keyGenerator.init(256);
            return keyGenerator.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to generate user delegation master key", e);
        }
    }
}
