package io.floci.az.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public final class UserDelegationKeyMaterial {

    public static final String SIGNED_OBJECT_ID = "00000000-0000-0000-0000-000000000000";
    public static final String SIGNED_TENANT_ID = "00000000-0000-0000-0000-000000000000";

    private static final String SIGNING_KEY_PREFIX = "floci-az-user-delegation:";

    private UserDelegationKeyMaterial() {
    }

    public static String signingKeyForAccount(String accountName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((SIGNING_KEY_PREFIX + accountName).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }
}
