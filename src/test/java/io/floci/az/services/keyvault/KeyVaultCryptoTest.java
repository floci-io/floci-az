package io.floci.az.services.keyvault;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.security.AlgorithmParameters;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quarkus-level crypto round-trip tests: RSA-OAEP(-256)/RSA1_5, AES-GCM, RS256/PS256/ES256
 * sign/verify (with independent JDK cross-checks), wrap/unwrap, and error mapping.
 */
@QuarkusTest
@DisplayName("KeyVaultCrypto — cryptographic operations")
class KeyVaultCryptoTest {

    private static final String BASE = "/devstoreaccount1-keyvault";
    private static final String API = "?api-version=7.4";
    private static final String AUTH = "Bearer fake";
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    // ── RSA encrypt/decrypt ────────────────────────────────────────────────────

    @Test
    @DisplayName("RSA-OAEP encrypt/decrypt round-trip")
    void rsaOaepRoundTrip() {
        assertRsaRoundTrip("RSA-OAEP");
    }

    @Test
    @DisplayName("RSA-OAEP-256 encrypt/decrypt round-trip")
    void rsaOaep256RoundTrip() {
        assertRsaRoundTrip("RSA-OAEP-256");
    }

    @Test
    @DisplayName("RSA1_5 encrypt/decrypt round-trip")
    void rsa15RoundTrip() {
        assertRsaRoundTrip("RSA1_5");
    }

    private void assertRsaRoundTrip(String alg) {
        Response created = createKey("rsa-" + alg.replace("_", "-"), "RSA", null, 2048);
        String kid = created.jsonPath().getString("key.kid");
        String pt = b64Url("hello " + alg);

        String ct = given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"" + alg + "\",\"value\":\"" + pt + "\"}")
                .when().post(kidPath(kid) + "/encrypt" + API)
                .then().statusCode(200).extract().jsonPath().getString("value");

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"" + alg + "\",\"value\":\"" + ct + "\"}")
                .when().post(kidPath(kid) + "/decrypt" + API)
                .then().statusCode(200)
                .body("value", equalTo(pt));
    }

    // ── AES-GCM ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A256GCM encrypt/decrypt round-trip with explicit iv/aad/tag")
    void aesGcmRoundTrip() {
        Response created = createKey("gcm1", "oct", null, 256);
        String kid = created.jsonPath().getString("key.kid");
        String pt = b64Url("secret-data");
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        String ivB64 = URL.encodeToString(iv);
        String aad = b64Url("authenticated-header");

        Response enc = given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"A256GCM\",\"value\":\"" + pt + "\",\"iv\":\"" + ivB64 + "\",\"aad\":\"" + aad + "\"}")
                .when().post(kidPath(kid) + "/encrypt" + API);
        enc.then().statusCode(200)
                .body("tag", notNullValue());
        String serverIv = enc.jsonPath().getString("iv");
        assertTrue(serverIv != null);
        assertEquals(12, URL_DECODER.decode(serverIv).length);
        assertFalse(Arrays.equals(iv, URL_DECODER.decode(serverIv)));
        String ct = enc.jsonPath().getString("value");
        String tag = enc.jsonPath().getString("tag");

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"A256GCM\",\"value\":\"" + ct + "\",\"iv\":\"" + serverIv + "\",\"aad\":\"" + aad + "\",\"tag\":\"" + tag + "\"}")
                .when().post(kidPath(kid) + "/decrypt" + API)
                .then().statusCode(200)
                .body("value", equalTo(pt));
    }

    @Test
    @DisplayName("AES-GCM ciphertext/tag tamper is rejected with 400")
    void aesGcmTagTamperRejected() {
        Response created = createKey("gcmtamper", "oct", null, 256);
        String kid = created.jsonPath().getString("key.kid");
        String pt = b64Url("tamper-target");
        String aad = b64Url("aad");

        Response enc = given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"A256GCM\",\"value\":\"" + pt + "\",\"aad\":\"" + aad + "\"}")
                .when().post(kidPath(kid) + "/encrypt" + API);
        enc.then().statusCode(200);
        String iv = enc.jsonPath().getString("iv");
        String ct = enc.jsonPath().getString("value");
        String tag = enc.jsonPath().getString("tag");

        byte[] ctBytes = URL_DECODER.decode(ct);
        ctBytes[0] ^= 1;
        String tamperedCt = URL.encodeToString(ctBytes);

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"A256GCM\",\"value\":\"" + tamperedCt + "\",\"iv\":\"" + iv + "\",\"aad\":\"" + aad + "\",\"tag\":\"" + tag + "\"}")
                .when().post(kidPath(kid) + "/decrypt" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));

        byte[] tagBytes = URL_DECODER.decode(tag);
        tagBytes[0] ^= 1;
        String tamperedTag = URL.encodeToString(tagBytes);

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"A256GCM\",\"value\":\"" + ct + "\",\"iv\":\"" + iv + "\",\"aad\":\"" + aad + "\",\"tag\":\"" + tamperedTag + "\"}")
                .when().post(kidPath(kid) + "/decrypt" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));
    }

    @Test
    @DisplayName("AES-GCM encrypt ignores caller IV and generates its own 12-byte IV")
    void gcmEncryptIgnoresCallerIv() {
        Response created = createKey("gcmiv", "oct", null, 256);
        String kid = created.jsonPath().getString("key.kid");
        String pt = b64Url("ignore-my-iv");
        byte[] callerIv = new byte[12];
        new SecureRandom().nextBytes(callerIv);

        Response enc = given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"A256GCM\",\"value\":\"" + pt + "\",\"iv\":\"" + URL.encodeToString(callerIv) + "\"}")
                .when().post(kidPath(kid) + "/encrypt" + API);
        enc.then().statusCode(200);

        String serverIv = enc.jsonPath().getString("iv");
        assertTrue(serverIv != null);
        assertEquals(12, URL_DECODER.decode(serverIv).length);
        assertFalse(Arrays.equals(callerIv, URL_DECODER.decode(serverIv)));
    }

    @Test
    @DisplayName("AES-GCM algorithm/key-size mismatch returns 400 BadParameter")
    void gcmAlgorithmKeySizeMismatch400() {
        // A128GCM against a 256-bit key and A256GCM against a 128-bit key must both be rejected.
        Response small = createKey("gcmsmall", "oct", null, 128);
        Response large = createKey("gcmlarge", "oct", null, 256);

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"A256GCM\",\"value\":\"" + b64Url("x") + "\"}")
                .when().post(kidPath(small.jsonPath().getString("key.kid")) + "/encrypt" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"A128GCM\",\"value\":\"" + b64Url("x") + "\"}")
                .when().post(kidPath(large.jsonPath().getString("key.kid")) + "/encrypt" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"A192GCM\",\"value\":\"" + b64Url("x") + "\"}")
                .when().post(kidPath(large.jsonPath().getString("key.kid")) + "/encrypt" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));
    }

    // ── Sign / verify ──────────────────────────────────────────────────────────
    @Test
    @DisplayName("RS256 sign/verify; verify returns {\"value\":true} with no kid")
    void rs256SignVerify() {
        Response created = createKey("rs256", "RSA", null, 2048);
        String kid = created.jsonPath().getString("key.kid");
        byte[] message = "hello world".getBytes(StandardCharsets.UTF_8);
        String digest = b64Url(sha256(message));

        String sig = sign(kid, "RS256", digest);

        // Verify via the service — strictly {"value": true} with no kid field.
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"RS256\",\"digest\":\"" + digest + "\",\"value\":\"" + sig + "\"}")
                .when().post(kidPath(kid) + "/verify" + API)
                .then().statusCode(200)
                .body("value", equalTo(true))
                .body("kid", nullValue());

        // Independent cross-check with the JDK.
        PublicKey pub = rsaPublicKey(created.jsonPath().getString("key.n"),
                created.jsonPath().getString("key.e"));
        assertTrue(jdkRsaVerify("SHA256withRSA", pub, message, URL_DECODER.decode(sig)),
                "JDK SHA256withRSA must accept the service signature");
    }

    @Test
    @DisplayName("PS256 sign/verify (manual EMSA-PSS) cross-checked with the JDK")
    void ps256SignVerify() {
        Response created = createKey("ps256", "RSA", null, 2048);
        String kid = created.jsonPath().getString("key.kid");
        byte[] message = "pss message".getBytes(StandardCharsets.UTF_8);
        String digest = b64Url(sha256(message));

        String sig = sign(kid, "PS256", digest);

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"PS256\",\"digest\":\"" + digest + "\",\"value\":\"" + sig + "\"}")
                .when().post(kidPath(kid) + "/verify" + API)
                .then().statusCode(200)
                .body("value", equalTo(true))
                .body("kid", nullValue());

        PublicKey pub = rsaPublicKey(created.jsonPath().getString("key.n"),
                created.jsonPath().getString("key.e"));
        assertTrue(jdkPssVerify(pub, message, URL_DECODER.decode(sig)),
                "JDK RSASSA-PSS must accept the service signature");
    }

    @Test
    @DisplayName("ES256 sign/verify (NONEwithECDSA + DER↔raw) cross-checked with the JDK")
    void es256SignVerify() {
        Response created = createKey("es256", "EC", "P-256", 0);
        String kid = created.jsonPath().getString("key.kid");
        byte[] message = "ecdsa message".getBytes(StandardCharsets.UTF_8);
        String digest = b64Url(sha256(message));

        String sig = sign(kid, "ES256", digest);

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"ES256\",\"digest\":\"" + digest + "\",\"value\":\"" + sig + "\"}")
                .when().post(kidPath(kid) + "/verify" + API)
                .then().statusCode(200)
                .body("value", equalTo(true))
                .body("kid", nullValue());

        // Cross-check: raw R||S → DER → JDK SHA256withECDSA over the original message.
        byte[] raw = URL_DECODER.decode(sig);
        byte[] der = KeyVaultCrypto.rawRsToDer(raw, 32);
        PublicKey pub = ecPublicKey(created.jsonPath().getString("key.crv"),
                created.jsonPath().getString("key.x"), created.jsonPath().getString("key.y"));
        try {
            Signature jdk = Signature.getInstance("SHA256withECDSA");
            jdk.initVerify(pub);
            jdk.update(message);
            assertTrue(jdk.verify(der), "JDK SHA256withECDSA must accept the DER-transformed signature");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("ES512 (P-521) sign/verify round-trip cross-checked with the JDK")
    void es512SignVerifyRoundTrip() {
        Response created = createKey("es512", "EC", "P-521", 0);
        String kid = created.jsonPath().getString("key.kid");
        byte[] message = "es512 message".getBytes(StandardCharsets.UTF_8);
        String digest = b64Url(sha512(message));

        String sig = sign(kid, "ES512", digest);

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"ES512\",\"digest\":\"" + digest + "\",\"value\":\"" + sig + "\"}")
                .when().post(kidPath(kid) + "/verify" + API)
                .then().statusCode(200)
                .body("value", equalTo(true))
                .body("kid", nullValue());

        byte[] der = KeyVaultCrypto.rawRsToDer(URL_DECODER.decode(sig), 66);
        PublicKey pub = ecPublicKey(created.jsonPath().getString("key.crv"),
                created.jsonPath().getString("key.x"), created.jsonPath().getString("key.y"));
        try {
            Signature jdk = Signature.getInstance("SHA512withECDSA");
            jdk.initVerify(pub);
            jdk.update(message);
            assertTrue(jdk.verify(der), "JDK SHA512withECDSA must accept the DER-transformed signature");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Wrap / unwrap ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("wrapKey/unwrapKey round-trip (RSA-OAEP-256)")
    void wrapUnwrapRoundTrip() {
        Response created = createKey("wrap1", "RSA", null, 2048);
        String kid = created.jsonPath().getString("key.kid");
        String pt = b64Url("wrap-me");

        String wrapped = given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"RSA-OAEP-256\",\"value\":\"" + pt + "\"}")
                .when().post(kidPath(kid) + "/wrapkey" + API)
                .then().statusCode(200).extract().jsonPath().getString("value");

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"RSA-OAEP-256\",\"value\":\"" + wrapped + "\"}")
                .when().post(kidPath(kid) + "/unwrapkey" + API)
                .then().statusCode(200)
                .body("value", equalTo(pt));
    }

    // ── Error mapping ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("unknown algorithm returns 400 BadParameter")
    void unknownAlg400() {
        Response created = createKey("unknown", "RSA", null, 2048);
        String kid = created.jsonPath().getString("key.kid");
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"NOT-AN-ALG\",\"value\":\"" + b64Url("x") + "\"}")
                .when().post(kidPath(kid) + "/sign" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));
    }

    @Test
    @DisplayName("crypto on a disabled key returns 403 Forbidden")
    void disabledKeyCrypto403() {
        Response created = createKey("disabled", "RSA", null, 2048);
        String kid = created.jsonPath().getString("key.kid");
        String version = kid.substring(kid.lastIndexOf('/') + 1);

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"attributes\":{\"enabled\":false}}")
                .when().patch(BASE + "/keys/disabled/" + version + API)
                .then().statusCode(200);

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"RSA-OAEP-256\",\"value\":\"" + b64Url("x") + "\"}")
                .when().post(kidPath(kid) + "/encrypt" + API)
                .then().statusCode(403)
                .body("error.code", equalTo("Forbidden"));
    }

    @Test
    @DisplayName("RSA algorithm on an EC/oct key returns 400 BadParameter")
    void signWithWrongKeyType400() {
        Response created = createKey("wrongtype", "oct", null, 256);
        String kid = created.jsonPath().getString("key.kid");
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"RS256\",\"value\":\"" + b64Url("digest") + "\"}")
                .when().post(kidPath(kid) + "/sign" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));
    }

    @Test
    @DisplayName("wrong digest length returns 400 BadParameter")
    void invalidDigestLengthRejected() {
        String shortDigest = b64Url(new byte[]{1, 2, 3, 4});
        assertSignRejectsDigestLength("badlen-rs256", "RSA", null, 2048, "RS256", shortDigest);
        assertSignRejectsDigestLength("badlen-ps256", "RSA", null, 2048, "PS256", shortDigest);
        assertSignRejectsDigestLength("badlen-es256", "EC", "P-256", 0, "ES256", shortDigest);
    }

    private void assertSignRejectsDigestLength(String name, String kty, String crv, int keySize,
                                               String alg, String digest) {
        Response created = createKey(name, kty, crv, keySize);
        String kid = created.jsonPath().getString("key.kid");
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"" + alg + "\",\"value\":\"" + digest + "\"}")
                .when().post(kidPath(kid) + "/sign" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Response createKey(String name, String kty, String crv, int keySize) {
        String body = "{\"kty\":\"" + kty + "\""
                + (crv != null ? ",\"crv\":\"" + crv + "\"" : "")
                + (keySize > 0 ? ",\"key_size\":" + keySize : "")
                + "}";
        Response created = given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body(body)
                .when().post(BASE + "/keys/" + name + "/create" + API);
        created.then().statusCode(200);
        return created;
    }

    private String sign(String kid, String alg, String digest) {
        Response resp = given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"" + alg + "\",\"value\":\"" + digest + "\"}")
                .when().post(kidPath(kid) + "/sign" + API);
        resp.then().statusCode(200);
        return resp.jsonPath().getString("value");
    }

    /** Rewrites an absolute {@code https://.../keys/{name}/{version}} kid to a local emulator path. */
    private static String kidPath(String kid) {
        return BASE + kid.substring(kid.indexOf("/keys/"));
    }

    private static String b64Url(byte[] bytes) {
        return URL.encodeToString(bytes);
    }

    private static String b64Url(String value) {
        return b64Url(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha512(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PublicKey rsaPublicKey(String nB64, String eB64) {
        try {
            BigInteger n = new BigInteger(1, URL_DECODER.decode(nB64));
            BigInteger e = new BigInteger(1, URL_DECODER.decode(eB64));
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean jdkRsaVerify(String alg, PublicKey pub, byte[] message, byte[] signature) {
        try {
            Signature sig = Signature.getInstance(alg);
            sig.initVerify(pub);
            sig.update(message);
            return sig.verify(signature);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean jdkPssVerify(PublicKey pub, byte[] message, byte[] signature) {
        try {
            Signature sig = Signature.getInstance("RSASSA-PSS");
            sig.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
            sig.initVerify(pub);
            sig.update(message);
            return sig.verify(signature);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PublicKey ecPublicKey(String crv, String xB64, String yB64) {
        try {
            String curve = switch (crv) {
                case "P-384" -> "secp384r1";
                case "P-521" -> "secp521r1";
                default -> "secp256r1";
            };
            AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
            ap.init(new ECGenParameterSpec(curve));
            ECParameterSpec params = ap.getParameterSpec(ECParameterSpec.class);
            ECPoint w = new ECPoint(new BigInteger(1, URL_DECODER.decode(xB64)),
                    new BigInteger(1, URL_DECODER.decode(yB64)));
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, params));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
