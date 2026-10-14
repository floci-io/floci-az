package io.floci.az.services.entra;

import org.jboss.logging.Logger;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Hashes and verifies application client secrets with PBKDF2 (HMAC-SHA256) and a random salt,
 * using only the JCA. The iteration count is stored with every hash, so raising it later leaves
 * existing hashes verifiable.
 */
public final class SecretHasher {

    public static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    /** OWASP's current recommendation for PBKDF2-HMAC-SHA256. */
    static final int ITERATIONS = 600_000;

    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger LOG = Logger.getLogger(SecretHasher.class);

    private SecretHasher() {
    }

    /**
     * A stored secret hash. Persisted inside an application's {@code passwordCredentials}, so the
     * plaintext is never written to storage.
     *
     * @param algorithm  the JCA algorithm name, {@link #ALGORITHM}
     * @param iterations the PBKDF2 iteration count the hash was derived with
     * @param salt       base64 salt
     * @param hash       base64 derived key
     */
    public record SecretHash(String algorithm, int iterations, String salt, String hash) {
    }

    /** Hashes {@code secret} under a fresh random salt. */
    public static SecretHash hash(String secret) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return hash(secret, salt, ITERATIONS);
    }

    static SecretHash hash(String secret, byte[] salt, int iterations) {
        byte[] derived = derive(secret, salt, iterations);
        Base64.Encoder encoder = Base64.getEncoder();
        return new SecretHash(ALGORITHM, iterations, encoder.encodeToString(salt), encoder.encodeToString(derived));
    }

    /**
     * Whether {@code candidate} is the secret {@code stored} was derived from. Constant time in the
     * hash. A stored hash that does not decode (invalid base64, an empty salt) matches nothing.
     */
    public static boolean matches(SecretHash stored, String candidate) {
        if (stored == null || candidate == null || !ALGORITHM.equals(stored.algorithm())
                || stored.iterations() <= 0 || stored.salt() == null || stored.hash() == null) {
            return false;
        }
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] expected = decoder.decode(stored.hash());
            byte[] actual = derive(candidate, decoder.decode(stored.salt()), stored.iterations());
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            LOG.warnv("Entra: a stored client secret hash is malformed and matches nothing: {0}", e.getMessage());
            return false;
        }
    }

    /** Constant-time comparison for a plaintext secret held by a legacy directory entry. */
    public static boolean matchesPlaintext(String stored, String candidate) {
        if (stored == null || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] derive(String secret, byte[] salt, int iterations) {
        char[] chars = secret.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is unavailable", e);
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }
}
