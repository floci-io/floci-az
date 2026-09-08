package io.floci.az.services.keyvault;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-JDK crypto engine for the Key Vault keys data plane: static helpers using only built-in
 * JDK providers (SunJCE/SunEC/SunRsaSign), avoiding BouncyCastle for GraalVM native image.
 * The {@code value}/{@code digest} given to {@link #sign}/{@link #verify} is the client's
 * <em>pre-hashed</em> digest, never the raw message, so signing must never double-hash.
 */
final class KeyVaultCrypto {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private KeyVaultCrypto() {
    }

    /** Raised on unsupported algorithm / wrong key type / malformed input. Maps to a 400. */
    static final class CryptoException extends RuntimeException {
        CryptoException(String message) {
            super(message);
        }

        CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ── base64url helpers ──────────────────────────────────────────────────────

    static String b64Url(byte[] data) {
        return URL.encodeToString(data);
    }

    static byte[] b64UrlDecode(String value) {
        if (value == null || value.isEmpty()) {
            return new byte[0];
        }
        try {
            String padded = value;
            int rem = value.length() % 4;
            if (rem != 0) {
                padded = value + "=".repeat(4 - rem);
            }
            return URL_DECODER.decode(padded);
        } catch (IllegalArgumentException e) {
            throw new CryptoException("Invalid base64url value", e);
        }
    }

    /** Big-endian, sign-bit-free encoding required by JWK {@code n}/{@code e}/{@code d}/... */
    static byte[] toUnsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    private static BigInteger bigint(Object value) {
        if (!(value instanceof String s)) {
            throw new CryptoException("JWK field must be a base64url string");
        }
        return new BigInteger(1, b64UrlDecode(s));
    }

    private static byte[] fixedLengthUnsigned(BigInteger value, int len) {
        byte[] raw = toUnsignedBytes(value);
        if (raw.length > len) {
            throw new CryptoException("JWK field does not fit curve field size");
        }
        if (raw.length == len) {
            return raw;
        }
        byte[] out = new byte[len];
        System.arraycopy(raw, 0, out, len - raw.length, raw.length);
        return out;
    }

    // ── Key type helpers ───────────────────────────────────────────────────────

    /** Strips the {@code -HSM} suffix, e.g. {@code RSA-HSM → RSA}. */
    static String baseKty(String kty) {
        if (kty == null) {
            return "";
        }
        int dash = kty.indexOf('-');
        return dash < 0 ? kty : kty.substring(0, dash);
    }

    static boolean isRsaKty(String kty) {
        return "RSA".equals(baseKty(kty));
    }

    static boolean isEcKty(String kty) {
        return "EC".equals(baseKty(kty));
    }

    static boolean isOctKty(String kty) {
        return "oct".equals(baseKty(kty));
    }

    static List<String> defaultKeyOps(String baseKty) {
        return switch (baseKty) {
            case "RSA" -> List.of("encrypt", "decrypt", "sign", "verify", "wrapKey", "unwrapKey");
            case "EC" -> List.of("sign", "verify");
            case "oct" -> List.of("encrypt", "decrypt", "wrapKey", "unwrapKey");
            default -> List.of();
        };
    }

    private static String curveToStd(String crv) {
        if (crv == null) {
            throw new CryptoException("EC keys require a curve");
        }
        return switch (crv) {
            case "P-256" -> "secp256r1";
            case "P-384" -> "secp384r1";
            case "P-521" -> "secp521r1";
            default -> throw new CryptoException("Unsupported curve: " + crv);
        };
    }

    // ── JWK generation / import / public strip ─────────────────────────────────

    static Map<String, Object> generateJwk(String kty, int keySize, String crv, List<String> keyOps) {
        String base = baseKty(kty);
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", kty);
        jwk.put("key_ops", keyOps);
        try {
            switch (base) {
                case "RSA" -> {
                    int size = keySize > 0 ? keySize : 2048;
                    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                    gen.initialize(size, SECURE_RANDOM);
                    KeyPair pair = gen.generateKeyPair();
                    RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
                    RSAPrivateCrtKey priv = (RSAPrivateCrtKey) pair.getPrivate();
                    jwk.put("n", b64Url(toUnsignedBytes(pub.getModulus())));
                    jwk.put("e", b64Url(toUnsignedBytes(pub.getPublicExponent())));
                    jwk.put("d", b64Url(toUnsignedBytes(priv.getPrivateExponent())));
                    jwk.put("p", b64Url(toUnsignedBytes(priv.getPrimeP())));
                    jwk.put("q", b64Url(toUnsignedBytes(priv.getPrimeQ())));
                    jwk.put("dp", b64Url(toUnsignedBytes(priv.getPrimeExponentP())));
                    jwk.put("dq", b64Url(toUnsignedBytes(priv.getPrimeExponentQ())));
                    jwk.put("qi", b64Url(toUnsignedBytes(priv.getCrtCoefficient())));
                    jwk.put("keySize", size);
                }
                case "EC" -> {
                    String curve = curveToStd(crv);
                    KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
                    gen.initialize(new ECGenParameterSpec(curve), SECURE_RANDOM);
                    KeyPair pair = gen.generateKeyPair();
                    ECPublicKey pub = (ECPublicKey) pair.getPublic();
                    ECPrivateKey priv = (ECPrivateKey) pair.getPrivate();
                    int fieldLen = (pub.getParams().getCurve().getField().getFieldSize() + 7) / 8;
                    jwk.put("crv", crv != null ? crv : "P-256");
                    jwk.put("x", b64Url(fixedLengthUnsigned(pub.getW().getAffineX(), fieldLen)));
                    jwk.put("y", b64Url(fixedLengthUnsigned(pub.getW().getAffineY(), fieldLen)));
                    jwk.put("d", b64Url(fixedLengthUnsigned(priv.getS(), fieldLen)));
                }
                case "oct" -> {
                    int bits = keySize > 0 ? keySize : 256;
                    if (bits != 128 && bits != 192 && bits != 256) {
                        throw new CryptoException("Unsupported oct key size: " + bits);
                    }
                    byte[] k = new byte[bits / 8];
                    SECURE_RANDOM.nextBytes(k);
                    jwk.put("k", b64Url(k));
                    jwk.put("keySize", bits);
                }
                default -> throw new CryptoException("Unsupported key type: " + kty);
            }
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("Key generation failed for " + kty, e);
        }
        return jwk;
    }

    /**
     * Validates and normalizes a client-supplied JWK (import). Rebuilds the full key map from the
     * supplied fields so it can be stored exactly like a generated key.
     */
    static Map<String, Object> importJwk(Map<String, Object> supplied) {
        Object ktyVal = supplied.get("kty");
        if (!(ktyVal instanceof String kty) || kty.isBlank()) {
            throw new CryptoException("key type (kty) is required");
        }
        String base = baseKty(kty);
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", kty);
        jwk.put("key_ops", supplied.getOrDefault("key_ops", defaultKeyOps(base)));
        switch (base) {
            case "RSA" -> {
                requireStringField(supplied, "n");
                requireStringField(supplied, "e");
                requireStringIfPresent(supplied, "d");
                requireStringIfPresent(supplied, "p");
                requireStringIfPresent(supplied, "q");
                requireStringIfPresent(supplied, "dp");
                requireStringIfPresent(supplied, "dq");
                requireStringIfPresent(supplied, "qi");
                copyFields(jwk, supplied, "n", "e", "d", "p", "q", "dp", "dq", "qi");
                jwk.put("keySize", b64UrlDecode((String) supplied.get("n")).length * 8);
            }
            case "EC" -> {
                requireStringField(supplied, "crv");
                requireStringField(supplied, "x");
                requireStringField(supplied, "y");
                requireStringIfPresent(supplied, "d");
                curveToStd((String) supplied.get("crv"));
                copyFields(jwk, supplied, "crv", "x", "y", "d");
            }
            case "oct" -> {
                requireStringField(supplied, "k");
                copyFields(jwk, supplied, "k");
                jwk.put("keySize", b64UrlDecode((String) supplied.get("k")).length * 8);
            }
            default -> throw new CryptoException("Unsupported key type: " + kty);
        }
        return jwk;
    }

    private static void requireStringField(Map<String, Object> supplied, String field) {
        Object value = supplied.get(field);
        if (!(value instanceof String)) {
            throw new CryptoException("JWK field '" + field + "' is required and must be a string");
        }
    }

    private static void requireStringIfPresent(Map<String, Object> supplied, String field) {
        Object value = supplied.get(field);
        if (value != null && !(value instanceof String)) {
            throw new CryptoException("JWK field '" + field + "' must be a string");
        }
    }

    private static void copyFields(Map<String, Object> target, Map<String, Object> source, String... fields) {
        for (String field : fields) {
            if (source.get(field) != null) {
                target.put(field, source.get(field));
            }
        }
    }

    /**
     * Strips private fields for the wire. Symmetric ({@code oct}) key material is never released in
     * any response; the full JWK (including {@code k}) stays server-side for crypto operations.
     */
    static Map<String, Object> publicJwk(Map<String, Object> full) {
        String kty = (String) full.get("kty");
        String base = baseKty(kty);
        Map<String, Object> pub = new LinkedHashMap<>();
        pub.put("kty", kty);
        if (full.containsKey("key_ops")) {
            pub.put("key_ops", full.get("key_ops"));
        }
        switch (base) {
            case "RSA" -> {
                pub.put("n", full.get("n"));
                pub.put("e", full.get("e"));
                if (full.containsKey("keySize")) {
                    pub.put("key_size", full.get("keySize"));
                }
            }
            case "EC" -> {
                pub.put("crv", full.get("crv"));
                pub.put("x", full.get("x"));
                pub.put("y", full.get("y"));
            }
            case "oct" -> {
                if (full.containsKey("keySize")) {
                    pub.put("key_size", full.get("keySize"));
                }
            }
            default -> {
            }
        }
        return pub;
    }

    // ── Key reconstruction ─────────────────────────────────────────────────────

    static PrivateKey reconstructRsaPrivate(Map<String, Object> jwk) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            BigInteger n = bigint(jwk.get("n"));
            BigInteger e = bigint(jwk.get("e"));
            if (jwk.get("d") != null && jwk.get("p") != null) {
                return kf.generatePrivate(new RSAPrivateCrtKeySpec(n, e, bigint(jwk.get("d")),
                        bigint(jwk.get("p")), bigint(jwk.get("q")), bigint(jwk.get("dp")),
                        bigint(jwk.get("dq")), bigint(jwk.get("qi"))));
            }
            if (jwk.get("d") != null) {
                return kf.generatePrivate(new RSAPrivateKeySpec(n, bigint(jwk.get("d"))));
            }
            throw new CryptoException("private key material missing");
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("RSA private key reconstruction failed", e);
        }
    }

    static PublicKey reconstructRsaPublic(Map<String, Object> jwk) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(new RSAPublicKeySpec(bigint(jwk.get("n")), bigint(jwk.get("e"))));
        } catch (Exception e) {
            throw new CryptoException("RSA public key reconstruction failed", e);
        }
    }

    static PrivateKey reconstructEcPrivate(Map<String, Object> jwk) {
        try {
            KeyFactory kf = KeyFactory.getInstance("EC");
            return kf.generatePrivate(new ECPrivateKeySpec(bigint(jwk.get("d")), ecParams((String) jwk.get("crv"))));
        } catch (Exception e) {
            throw new CryptoException("EC private key reconstruction failed", e);
        }
    }

    static PublicKey reconstructEcPublic(Map<String, Object> jwk) {
        try {
            KeyFactory kf = KeyFactory.getInstance("EC");
            ECPoint w = new ECPoint(bigint(jwk.get("x")), bigint(jwk.get("y")));
            return kf.generatePublic(new ECPublicKeySpec(w, ecParams((String) jwk.get("crv"))));
        } catch (Exception e) {
            throw new CryptoException("EC public key reconstruction failed", e);
        }
    }

    static SecretKey reconstructSecretKey(Map<String, Object> jwk) {
        return new SecretKeySpec(b64UrlDecode((String) jwk.get("k")), "AES");
    }

    private static ECParameterSpec ecParams(String crv) {
        try {
            AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
            ap.init(new ECGenParameterSpec(curveToStd(crv)));
            return ap.getParameterSpec(ECParameterSpec.class);
        } catch (Exception e) {
            throw new CryptoException("Unsupported curve: " + crv, e);
        }
    }

    // ── Encrypt / decrypt / wrap / unwrap ──────────────────────────────────────

    record CipherResult(byte[] value, byte[] iv, byte[] tag) {}

    static CipherResult encrypt(Map<String, Object> jwk, String alg, byte[] value, byte[] iv, byte[] aad) {
        String base = baseKty((String) jwk.get("kty"));
        return switch (alg) {
            case "RSA1_5" -> {
                requireRsa(base, alg);
                yield new CipherResult(rsaCrypt(jwk, "RSA/ECB/PKCS1Padding", Cipher.ENCRYPT_MODE, value), null, null);
            }
            case "RSA-OAEP" -> {
                requireRsa(base, alg);
                yield new CipherResult(rsaCrypt(jwk, "RSA/ECB/OAEPWithSHA-1AndMGF1Padding", Cipher.ENCRYPT_MODE, value), null, null);
            }
            case "RSA-OAEP-256" -> {
                requireRsa(base, alg);
                yield new CipherResult(rsaCrypt(jwk, "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", Cipher.ENCRYPT_MODE, value), null, null);
            }
            case "A128GCM", "A192GCM", "A256GCM" -> {
                requireOct(base, alg);
                requireOctSize(jwk, alg);
                yield gcmEncrypt(jwk, value, iv, aad);
            }
            default -> throw new CryptoException("Unsupported algorithm: " + alg);
        };
    }

    static byte[] decrypt(Map<String, Object> jwk, String alg, byte[] value, byte[] iv, byte[] aad, byte[] tag) {
        String base = baseKty((String) jwk.get("kty"));
        return switch (alg) {
            case "RSA1_5" -> {
                requireRsa(base, alg);
                yield rsaCrypt(jwk, "RSA/ECB/PKCS1Padding", Cipher.DECRYPT_MODE, value);
            }
            case "RSA-OAEP" -> {
                requireRsa(base, alg);
                yield rsaCrypt(jwk, "RSA/ECB/OAEPWithSHA-1AndMGF1Padding", Cipher.DECRYPT_MODE, value);
            }
            case "RSA-OAEP-256" -> {
                requireRsa(base, alg);
                yield rsaCrypt(jwk, "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", Cipher.DECRYPT_MODE, value);
            }
            case "A128GCM", "A192GCM", "A256GCM" -> {
                requireOct(base, alg);
                requireOctSize(jwk, alg);
                yield gcmDecrypt(jwk, value, iv, aad, tag);
            }
            default -> throw new CryptoException("Unsupported algorithm: " + alg);
        };
    }

    private static byte[] rsaCrypt(Map<String, Object> jwk, String transform, int mode, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(transform);
            cipher.init(mode, mode == Cipher.ENCRYPT_MODE ? reconstructRsaPublic(jwk) : reconstructRsaPrivate(jwk));
            return cipher.doFinal(input);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("RSA operation failed", e);
        }
    }

    private static CipherResult gcmEncrypt(Map<String, Object> jwk, byte[] plaintext, byte[] ivIgnored, byte[] aad) {
        byte[] ivBytes = randomIv(12);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, reconstructSecretKey(jwk), new GCMParameterSpec(128, ivBytes));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            byte[] out = cipher.doFinal(plaintext);
            byte[] ct = Arrays.copyOfRange(out, 0, out.length - 16);
            byte[] tag = Arrays.copyOfRange(out, out.length - 16, out.length);
            return new CipherResult(ct, ivBytes, tag);
        } catch (Exception e) {
            throw new CryptoException("AES-GCM encryption failed", e);
        }
    }

    private static byte[] gcmDecrypt(Map<String, Object> jwk, byte[] ciphertext, byte[] iv, byte[] aad, byte[] tag) {
        if (tag == null || tag.length == 0) {
            throw new CryptoException("AES-GCM decryption requires a tag");
        }
        byte[] combined = new byte[ciphertext.length + tag.length];
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
        System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, reconstructSecretKey(jwk), new GCMParameterSpec(128, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(combined);
        } catch (Exception e) {
            throw new CryptoException("AES-GCM decryption failed", e);
        }
    }

    private static byte[] randomIv(int length) {
        byte[] iv = new byte[length];
        SECURE_RANDOM.nextBytes(iv);
        return iv;
    }

    // ── Sign / verify ──────────────────────────────────────────────────────────

    static byte[] sign(Map<String, Object> jwk, String alg, byte[] digest) {
        validateDigestLength(alg, digest);
        String base = baseKty((String) jwk.get("kty"));
        return switch (alg) {
            case "RS256", "RS384", "RS512" -> {
                requireRsa(base, alg);
                yield rsSign(jwk, digest, digestInfoPrefix(alg));
            }
            case "PS256", "PS384", "PS512" -> {
                requireRsa(base, alg);
                yield pssSign(jwk, digest, hashName(alg));
            }
            case "ES256", "ES384", "ES512" -> {
                requireEc(base, alg);
                requireCurve(jwk, alg);
                yield ecSign(jwk, digest, curveByteLength(alg));
            }
            default -> throw new CryptoException("Unsupported algorithm: " + alg);
        };
    }

    static boolean verify(Map<String, Object> jwk, String alg, byte[] digest, byte[] signature) {
        validateDigestLength(alg, digest);
        String base = baseKty((String) jwk.get("kty"));
        return switch (alg) {
            case "RS256", "RS384", "RS512" -> {
                requireRsa(base, alg);
                yield rsVerify(jwk, digest, digestInfoPrefix(alg), signature);
            }
            case "PS256", "PS384", "PS512" -> {
                requireRsa(base, alg);
                yield pssVerify(jwk, digest, signature, hashName(alg));
            }
            case "ES256", "ES384", "ES512" -> {
                requireEc(base, alg);
                requireCurve(jwk, alg);
                yield ecVerify(jwk, digest, signature, curveByteLength(alg));
            }
            default -> throw new CryptoException("Unsupported algorithm: " + alg);
        };
    }

    private static void requireRsa(String base, String alg) {
        if (!"RSA".equals(base)) {
            throw new CryptoException("Algorithm " + alg + " requires an RSA key");
        }
    }

    private static void requireEc(String base, String alg) {
        if (!"EC".equals(base)) {
            throw new CryptoException("Algorithm " + alg + " requires an EC key");
        }
    }

    private static void requireOct(String base, String alg) {
        if (!"oct".equals(base)) {
            throw new CryptoException("Algorithm " + alg + " requires an oct key");
        }
    }

    /** Rejects an AES-GCM algorithm when the stored oct key's size does not match the algorithm. */
    private static void requireOctSize(Map<String, Object> jwk, String alg) {
        int bits = jwk.get("keySize") instanceof Number n ? n.intValue() : 0;
        if (bits == 0 && jwk.get("k") instanceof String k) {
            bits = b64UrlDecode(k).length * 8;
        }
        int expected = switch (alg) {
            case "A128GCM" -> 128;
            case "A192GCM" -> 192;
            case "A256GCM" -> 256;
            default -> 0;
        };
        if (bits != expected) {
            throw new CryptoException("Algorithm " + alg + " requires a " + expected + "-bit key");
        }
    }

    /** The key type (RSA/EC/oct) required by an algorithm, or {@code null} for an unknown algorithm. */
    static String keyTypeForAlg(String alg) {
        return switch (alg) {
            case "RSA1_5", "RSA-OAEP", "RSA-OAEP-256",
                 "RS256", "RS384", "RS512", "PS256", "PS384", "PS512" -> "RSA";
            case "A128GCM", "A192GCM", "A256GCM" -> "oct";
            case "ES256", "ES384", "ES512" -> "EC";
            default -> null;
        };
    }

    // ── RS* (EMSA-PKCS1-v1_5 by hand, raw digest input) ────────────────────────

    private static byte[] rsSign(Map<String, Object> jwk, byte[] digest, byte[] prefix) {
        byte[] em = concat(prefix, digest);
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, reconstructRsaPrivate(jwk));
            return cipher.doFinal(em);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("RSA sign failed", e);
        }
    }

    private static boolean rsVerify(Map<String, Object> jwk, byte[] digest, byte[] prefix, byte[] signature) {
        byte[] em = concat(prefix, digest);
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, reconstructRsaPublic(jwk));
            byte[] recovered = cipher.doFinal(signature);
            return MessageDigest.isEqual(em, recovered);
        } catch (Exception e) {
            return false;
        }
    }

    // ── PS* (manual EMSA-PSS, RFC 8017 §9.1.1, salt length = hash length) ──────

    private static byte[] pssSign(Map<String, Object> jwk, byte[] mHash, String hashName) {
        PrivateKey priv = reconstructRsaPrivate(jwk);
        int modBits = rsaModulusBits(priv);
        int emBits = modBits - 1;
        int emLen = (emBits + 7) / 8;
        int hLen = mHash.length;
        int sLen = hLen;

        byte[] salt = new byte[sLen];
        SECURE_RANDOM.nextBytes(salt);

        byte[] mPrime = new byte[8 + hLen + sLen];
        System.arraycopy(mHash, 0, mPrime, 8, hLen);
        System.arraycopy(salt, 0, mPrime, 8 + hLen, sLen);
        byte[] h = hash(hashName, mPrime);

        int psLen = emLen - sLen - hLen - 2;
        if (psLen < 0) {
            throw new CryptoException("RSA key too small for " + hashName + " PSS signature");
        }
        byte[] db = new byte[psLen + 1 + sLen];
        db[psLen] = 1;
        System.arraycopy(salt, 0, db, psLen + 1, sLen);

        byte[] dbMask = mgf1(h, emLen - hLen - 1, hashName);
        byte[] maskedDb = xor(db, dbMask);
        maskedDb[0] &= (byte) (0xFF >>> (8 * emLen - emBits));

        byte[] em = new byte[emLen];
        System.arraycopy(maskedDb, 0, em, 0, emLen - hLen - 1);
        System.arraycopy(h, 0, em, emLen - hLen - 1, hLen);
        em[emLen - 1] = (byte) 0xbc;

        return rawRsaPrivateOp(priv, em, emLen);
    }

    private static boolean pssVerify(Map<String, Object> jwk, byte[] mHash, byte[] signature, String hashName) {
        PublicKey pub = reconstructRsaPublic(jwk);
        int modBits = rsaModulusBits(pub);
        int emBits = modBits - 1;
        int emLen = (emBits + 7) / 8;
        int hLen = mHash.length;
        int sLen = hLen;

        byte[] em;
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, pub);
            em = leftPad(cipher.doFinal(signature), emLen);
        } catch (Exception e) {
            return false;
        }
        if (em.length != emLen || em[emLen - 1] != (byte) 0xbc) {
            return false;
        }

        int psLen = emLen - sLen - hLen - 2;
        if (psLen < 0) {
            return false;
        }

        byte[] maskedDb = Arrays.copyOfRange(em, 0, emLen - hLen - 1);
        byte[] h = Arrays.copyOfRange(em, emLen - hLen - 1, emLen - 1);
        maskedDb[0] &= (byte) (0xFF >>> (8 * emLen - emBits));

        byte[] dbMask = mgf1(h, emLen - hLen - 1, hashName);
        byte[] db = xor(maskedDb, dbMask);
        db[0] &= (byte) (0xFF >>> (8 * emLen - emBits));

        for (int i = 0; i < psLen; i++) {
            if (db[i] != 0) {
                return false;
            }
        }
        if (db[psLen] != 1) {
            return false;
        }

        byte[] salt = Arrays.copyOfRange(db, psLen + 1, db.length);
        byte[] mPrime = new byte[8 + hLen + sLen];
        System.arraycopy(mHash, 0, mPrime, 8, hLen);
        System.arraycopy(salt, 0, mPrime, 8 + hLen, sLen);
        byte[] h2 = hash(hashName, mPrime);
        return MessageDigest.isEqual(h, h2);
    }

    // ── ES* (NONEwithECDSA DER + manual DER ↔ raw R‖S) ─────────────────────────

    private static byte[] ecSign(Map<String, Object> jwk, byte[] digest, int curveByteLength) {
        try {
            Signature signature = Signature.getInstance("NONEwithECDSA");
            signature.initSign(reconstructEcPrivate(jwk));
            signature.update(digest);
            return derToRawRs(signature.sign(), curveByteLength);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("ECDSA sign failed", e);
        }
    }

    private static boolean ecVerify(Map<String, Object> jwk, byte[] digest, byte[] raw, int curveByteLength) {
        try {
            Signature signature = Signature.getInstance("NONEwithECDSA");
            signature.initVerify(reconstructEcPublic(jwk));
            signature.update(digest);
            return signature.verify(rawRsToDer(raw, curveByteLength));
        } catch (Exception e) {
            return false;
        }
    }

    /** ASN.1 DER {@code SEQUENCE { INTEGER r, INTEGER s }} → fixed-size {@code R‖S} (IEEE P1363). */
    static byte[] derToRawRs(byte[] der, int curveByteLength) {
        BigInteger[] rs = decodeDerSignature(der);
        byte[] raw = new byte[2 * curveByteLength];
        writeFixedLength(raw, 0, rs[0], curveByteLength);
        writeFixedLength(raw, curveByteLength, rs[1], curveByteLength);
        return raw;
    }

    /** Fixed-size {@code R‖S} (IEEE P1363) → ASN.1 DER {@code SEQUENCE { INTEGER r, INTEGER s }}. */
    static byte[] rawRsToDer(byte[] raw, int curveByteLength) {
        if (raw.length != 2 * curveByteLength) {
            throw new CryptoException("invalid raw R||S signature length");
        }
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(raw, 0, curveByteLength));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(raw, curveByteLength, 2 * curveByteLength));
        byte[] rDer = derInteger(r);
        byte[] sDer = derInteger(s);
        byte[] len = derLength(rDer.length + sDer.length);
        byte[] out = new byte[1 + len.length + rDer.length + sDer.length];
        int pos = 0;
        out[pos++] = 0x30;
        System.arraycopy(len, 0, out, pos, len.length);
        pos += len.length;
        System.arraycopy(rDer, 0, out, pos, rDer.length);
        pos += rDer.length;
        System.arraycopy(sDer, 0, out, pos, sDer.length);
        return out;
    }

    private static BigInteger[] decodeDerSignature(byte[] der) {
        int pos = 0;
        if (pos >= der.length || der[pos++] != 0x30) {
            throw new CryptoException("invalid DER signature");
        }
        int[] l1 = readDerLength(der, pos);
        pos = l1[1];
        if (pos >= der.length || der[pos++] != 0x02) {
            throw new CryptoException("invalid DER signature");
        }
        int[] l2 = readDerLength(der, pos);
        pos = l2[1];
        byte[] rBytes = Arrays.copyOfRange(der, pos, pos + l2[0]);
        pos += l2[0];
        if (pos >= der.length || der[pos++] != 0x02) {
            throw new CryptoException("invalid DER signature");
        }
        int[] l3 = readDerLength(der, pos);
        pos = l3[1];
        byte[] sBytes = Arrays.copyOfRange(der, pos, pos + l3[0]);
        return new BigInteger[]{new BigInteger(rBytes), new BigInteger(sBytes)};
    }

    private static int[] readDerLength(byte[] der, int pos) {
        int b = der[pos] & 0xFF;
        if (b < 0x80) {
            return new int[]{b, pos + 1};
        }
        int numBytes = b & 0x7F;
        int len = 0;
        for (int i = 0; i < numBytes; i++) {
            len = (len << 8) | (der[pos + 1 + i] & 0xFF);
        }
        return new int[]{len, pos + 1 + numBytes};
    }

    private static byte[] derInteger(BigInteger v) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[2 + b.length];
        out[0] = 0x02;
        out[1] = (byte) b.length;
        System.arraycopy(b, 0, out, 2, b.length);
        return out;
    }

    private static byte[] derLength(int len) {
        if (len < 0x80) {
            return new byte[]{(byte) len};
        } else if (len <= 0xFF) {
            return new byte[]{(byte) 0x81, (byte) len};
        } else {
            return new byte[]{(byte) 0x82, (byte) (len >> 8), (byte) len};
        }
    }

    private static void writeFixedLength(byte[] out, int off, BigInteger v, int len) {
        byte[] b = v.toByteArray();
        int start = 0;
        if (b.length > len) {
            if (b.length == len + 1 && b[0] == 0) {
                start = 1;
            } else {
                throw new CryptoException("R||S component too large for curve");
            }
        }
        int pad = len - (b.length - start);
        Arrays.fill(out, off, off + pad, (byte) 0);
        System.arraycopy(b, start, out, off + pad, b.length - start);
    }

    // ── Shared primitives ──────────────────────────────────────────────────────

    private static byte[] rawRsaPrivateOp(PrivateKey priv, byte[] em, int modLen) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, priv);
            return leftPad(cipher.doFinal(em), modLen);
        } catch (Exception e) {
            throw new CryptoException("RSA private operation failed", e);
        }
    }

    private static int rsaModulusBits(java.security.Key key) {
        return ((RSAKey) key).getModulus().bitLength();
    }

    private static byte[] hash(String name, byte[] data) {
        try {
            return MessageDigest.getInstance(name).digest(data);
        } catch (Exception e) {
            throw new CryptoException("Hash algorithm unavailable: " + name, e);
        }
    }

    private static byte[] mgf1(byte[] seed, int maskLen, String hashName) {
        try {
            int hLen = hashLen(hashName);
            byte[] out = new byte[maskLen];
            MessageDigest md = MessageDigest.getInstance(hashName);
            byte[] counter = new byte[4];
            for (int i = 0, done = 0; done < maskLen; i++) {
                counter[0] = (byte) (i >>> 24);
                counter[1] = (byte) (i >>> 16);
                counter[2] = (byte) (i >>> 8);
                counter[3] = (byte) i;
                md.update(seed);
                md.update(counter);
                byte[] t = md.digest();
                int toCopy = Math.min(hLen, maskLen - done);
                System.arraycopy(t, 0, out, done, toCopy);
                done += toCopy;
            }
            return out;
        } catch (Exception e) {
            throw new CryptoException("MGF1 unavailable for " + hashName, e);
        }
    }

    private static int hashLen(String hashName) {
        return switch (hashName) {
            case "SHA-256" -> 32;
            case "SHA-384" -> 48;
            case "SHA-512" -> 64;
            default -> throw new CryptoException("Unsupported hash: " + hashName);
        };
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] leftPad(byte[] in, int len) {
        if (in.length >= len) {
            return in;
        }
        byte[] out = new byte[len];
        System.arraycopy(in, 0, out, len - in.length, in.length);
        return out;
    }

    private static byte[] digestInfoPrefix(String alg) {
        return switch (alg) {
            case "RS256" -> hex("3031300d060960864801650304020105000420");
            case "RS384" -> hex("3041300d060960864801650304020205000430");
            case "RS512" -> hex("3051300d060960864801650304020305000440");
            default -> throw new CryptoException("Unsupported algorithm: " + alg);
        };
    }

    private static String hashName(String alg) {
        return switch (alg) {
            case "PS256" -> "SHA-256";
            case "PS384" -> "SHA-384";
            case "PS512" -> "SHA-512";
            default -> throw new CryptoException("Unsupported algorithm: " + alg);
        };
    }

    private static int curveByteLength(String alg) {
        return switch (alg) {
            case "ES256" -> 32;
            case "ES384" -> 48;
            case "ES512" -> 66;
            default -> throw new CryptoException("Unsupported algorithm: " + alg);
        };
    }

    private static int digestByteLength(String alg) {
        return switch (alg) {
            case "RS256", "PS256", "ES256" -> 32;
            case "RS384", "PS384", "ES384" -> 48;
            case "RS512", "PS512", "ES512" -> 64;
            default -> throw new CryptoException("Unsupported algorithm: " + alg);
        };
    }

    private static String curveForAlg(String alg) {
        return switch (alg) {
            case "ES256" -> "P-256";
            case "ES384" -> "P-384";
            case "ES512" -> "P-521";
            default -> null;
        };
    }

    private static void validateDigestLength(String alg, byte[] digest) {
        if (digest == null || digest.length != digestByteLength(alg)) {
            throw new CryptoException("Digest length " + (digest == null ? 0 : digest.length)
                    + " does not match algorithm " + alg);
        }
    }

    private static void requireCurve(Map<String, Object> jwk, String alg) {
        String expected = curveForAlg(alg);
        String crv = (String) jwk.get("crv");
        if (expected != null && (crv == null || !expected.equals(crv))) {
            throw new CryptoException("Algorithm " + alg + " requires curve " + expected);
        }
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
