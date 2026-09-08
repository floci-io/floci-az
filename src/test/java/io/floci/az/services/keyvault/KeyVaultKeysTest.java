package io.floci.az.services.keyvault;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quarkus-level tests for the Key Vault keys data plane (CRUD, versions, soft-delete,
 * backup/restore, rotation policy, rng, Managed HSM flavor detection, crypto version resolution).
 */
@QuarkusTest
@DisplayName("KeyVaultKeys — key CRUD and lifecycle")
class KeyVaultKeysTest {

    private static final String BASE = "/devstoreaccount1-keyvault";
    private static final String API = "?api-version=7.4";
    private static final String AUTH = "Bearer fake";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    // ── Creation ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST create RSA key returns public JWK without private fields")
    void createRsaKeyReturnsJwk() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048}")
                .when().post(BASE + "/keys/rsa1/create" + API)
                .then().statusCode(200)
                .body("key.kty", equalTo("RSA"))
                .body("key.n", notNullValue())
                .body("key.e", notNullValue())
                .body("key.d", nullValue())
                .body("key.p", nullValue())
                .body("key.q", nullValue())
                .body("key.kid", containsString(".vault.azure.net/keys/rsa1/"));
    }

    @Test
    @DisplayName("POST create EC P-256 key returns crv/x/y")
    void createEcKeyP256() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"EC\",\"crv\":\"P-256\"}")
                .when().post(BASE + "/keys/ec1/create" + API)
                .then().statusCode(200)
                .body("key.kty", equalTo("EC"))
                .body("key.crv", equalTo("P-256"))
                .body("key.x", notNullValue())
                .body("key.y", notNullValue())
                .body("key.d", nullValue());
    }

    @Test
    @DisplayName("POST create oct key returns k (never stripped)")
    void createOctKeyReturnsK() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"oct\",\"key_size\":256}")
                .when().post(BASE + "/keys/oct1/create" + API)
                .then().statusCode(200)
                .body("key.kty", equalTo("oct"))
                .body("key.k", notNullValue());
    }

    @Test
    @DisplayName("PUT import returns the same public RSA fields")
    void importRsaKey() {
        Map<String, Object> jwk = fullRsaJwk();
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"key\":" + toJson(jwk) + "}")
                .when().put(BASE + "/keys/imported" + API)
                .then().statusCode(200)
                .body("key.kty", equalTo("RSA"))
                .body("key.n", equalTo(jwk.get("n")))
                .body("key.e", equalTo(jwk.get("e")))
                .body("key.d", nullValue());
    }

    @Test
    @DisplayName("create echoes key_ops, nbf and exp")
    void createWithKeyOpsAndAttributes() {
        long nbf = 1000000000L;
        long exp = 2000000000L;
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048,"
                        + "\"key_ops\":[\"encrypt\",\"decrypt\"],"
                        + "\"attributes\":{\"nbf\":" + nbf + ",\"exp\":" + exp + "}}")
                .when().post(BASE + "/keys/ops1/create" + API)
                .then().statusCode(200)
                .body("key.key_ops", hasItems("encrypt", "decrypt"))
                .body("attributes.nbf", equalTo((int) nbf))
                .body("attributes.exp", equalTo((int) exp));
    }

    // ── Read ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET latest and version, list keys omits versions, list versions includes them")
    void getKeyLatestAndVersion() {
        Response created = given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048}")
                .when().post(BASE + "/keys/g1/create" + API);
        created.then().statusCode(200);
        String kid = created.jsonPath().getString("key.kid");
        String version = kid.substring(kid.lastIndexOf('/') + 1);

        given().header("Authorization", AUTH)
                .when().get(BASE + "/keys/g1" + API)
                .then().statusCode(200).body("key.kid", equalTo(kid));

        given().header("Authorization", AUTH)
                .when().get(BASE + "/keys/g1/" + version + API)
                .then().statusCode(200).body("key.kid", equalTo(kid));

        given().header("Authorization", AUTH)
                .when().get(BASE + "/keys" + API)
                .then().statusCode(200)
                .body("value.size()", greaterThanOrEqualTo(1))
                .body("value[0].kid", equalTo("https://devstoreaccount1.vault.azure.net/keys/g1"));

        given().header("Authorization", AUTH)
                .when().get(BASE + "/keys/g1/versions" + API)
                .then().statusCode(200)
                .body("value.size()", equalTo(1))
                .body("value[0].kid", equalTo(kid));
    }

    @Test
    @DisplayName("missing key returns 404 KeyNotFound")
    void getMissingKey404() {
        given().header("Authorization", AUTH)
                .when().get(BASE + "/keys/nope" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("KeyNotFound"));
    }

    // ── Update / disable ───────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH attributes and tags; crypto on a disabled key is 403")
    void patchKeyAttributesAndTags() {
        Response created = given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048}")
                .when().post(BASE + "/keys/p1/create" + API);
        created.then().statusCode(200);
        String version = created.jsonPath().getString("key.kid");
        version = version.substring(version.lastIndexOf('/') + 1);

        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"attributes\":{\"enabled\":false},\"tags\":{\"env\":\"test\"}}")
                .when().patch(BASE + "/keys/p1/" + version + API)
                .then().statusCode(200)
                .body("attributes.enabled", equalTo(false))
                .body("tags.env", equalTo("test"));

        // Crypto on the disabled key must be forbidden.
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"alg\":\"RSA-OAEP-256\",\"value\":\"" + b64Url("hello") + "\"}")
                .when().post(BASE + "/keys/p1/" + version + "/encrypt" + API)
                .then().statusCode(403)
                .body("error.code", equalTo("Forbidden"));
    }

    // ── Soft delete / recover / purge ──────────────────────────────────────────

    @Test
    @DisplayName("delete/recover/purge lifecycle; purge removes the rotation policy")
    void deleteKeySoftDeleteRecoverPurge() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048}")
                .when().post(BASE + "/keys/lc1/create" + API)
                .then().statusCode(200);

        // Set a rotation policy.
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"lifetimeActions\":[{\"trigger\":{\"timeAfterCreate\":\"P30D\"},\"action\":{\"type\":\"Rotate\"}}],\"attributes\":{}}")
                .when().put(BASE + "/keys/lc1/rotationpolicy" + API)
                .then().statusCode(200)
                .body("lifetimeActions.size()", equalTo(1));

        // Soft delete.
        given().header("Authorization", AUTH)
                .when().delete(BASE + "/keys/lc1" + API)
                .then().statusCode(200)
                .body("recoveryId", containsString("/deletedkeys/lc1"));

        // Rotation policy on a soft-deleted key must 404.
        given().header("Authorization", AUTH)
                .when().get(BASE + "/keys/lc1/rotationpolicy" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("KeyNotFound"));

        // Recover.
        given().header("Authorization", AUTH)
                .when().post(BASE + "/deletedkeys/lc1/recover" + API)
                .then().statusCode(200)
                .body("key.kid", containsString("/keys/lc1/"));

        // Delete again and purge.
        given().header("Authorization", AUTH)
                .when().delete(BASE + "/keys/lc1" + API)
                .then().statusCode(200);
        given().header("Authorization", AUTH)
                .when().delete(BASE + "/deletedkeys/lc1" + API)
                .then().statusCode(204);

        // Recreating the key must NOT inherit the purged rotation policy.
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048}")
                .when().post(BASE + "/keys/lc1/create" + API)
                .then().statusCode(200);
        given().header("Authorization", AUTH)
                .when().get(BASE + "/keys/lc1/rotationpolicy" + API)
                .then().statusCode(200)
                .body("lifetimeActions.size()", equalTo(0));
    }

    // ── Backup / restore ───────────────────────────────────────────────────────

    @Test
    @DisplayName("backup → restore round-trip; restore over existing is 409")
    void backupRestoreRoundTrip() {
        Response created = given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048}")
                .when().post(BASE + "/keys/bk1/create" + API);
        created.then().statusCode(200);
        String kid = created.jsonPath().getString("key.kid");
        String version = kid.substring(kid.lastIndexOf('/') + 1);

        Response backup = given().header("Authorization", AUTH)
                .when().post(BASE + "/keys/bk1/backup" + API);
        backup.then().statusCode(200);
        String backupValue = backup.jsonPath().getString("value");
        assertTrue(backupValue != null && !backupValue.isEmpty());

        // Restore over an existing key → 409 Conflict.
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"value\":\"" + backupValue + "\"}")
                .when().post(BASE + "/keys/restore" + API)
                .then().statusCode(409)
                .body("error.code", equalTo("Conflict"));

        // Delete + purge, then restore.
        given().header("Authorization", AUTH).when().delete(BASE + "/keys/bk1" + API).then().statusCode(200);
        given().header("Authorization", AUTH).when().delete(BASE + "/deletedkeys/bk1" + API).then().statusCode(204);

        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"value\":\"" + backupValue + "\"}")
                .when().post(BASE + "/keys/restore" + API)
                .then().statusCode(200)
                .body("key.kid", equalTo(kid));

        given().header("Authorization", AUTH)
                .when().get(BASE + "/keys/bk1/" + version + API)
                .then().statusCode(200)
                .body("key.kid", equalTo(kid));
    }

    // ── Rotation policy ────────────────────────────────────────────────────────

    @Test
    @DisplayName("rotation policy PUT/GET round-trip; unset returns default")
    void rotationPolicyPutGet() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048}")
                .when().post(BASE + "/keys/rp1/create" + API)
                .then().statusCode(200);

        // Unset → default (empty lifetimeActions).
        given().header("Authorization", AUTH)
                .when().get(BASE + "/keys/rp1/rotationpolicy" + API)
                .then().statusCode(200)
                .body("lifetimeActions.size()", equalTo(0))
                .body("id", equalTo("https://devstoreaccount1.vault.azure.net/keys/rp1/rotationpolicy"));

        String policy = "{\"lifetimeActions\":["
                + "{\"trigger\":{\"timeAfterCreate\":\"P30D\"},\"action\":{\"type\":\"Rotate\"}},"
                + "{\"trigger\":{\"timeBeforeExpiry\":\"P7D\"},\"action\":{\"type\":\"Notify\"}}],"
                + "\"attributes\":{\"expiryTime\":\"P1Y\"}}";
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body(policy)
                .when().put(BASE + "/keys/rp1/rotationpolicy" + API)
                .then().statusCode(200)
                .body("lifetimeActions.size()", equalTo(2));

        given().header("Authorization", AUTH)
                .when().get(BASE + "/keys/rp1/rotationpolicy" + API)
                .then().statusCode(200)
                .body("lifetimeActions.size()", equalTo(2))
                .body("lifetimeActions[0].action.type", equalTo("Rotate"))
                .body("attributes.expiryTime", equalTo("P1Y"));
    }

    // ── rng ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /rng returns the requested number of random bytes")
    void rngReturnsRequestedCount() {
        for (String path : new String[]{BASE + "/rng", "/rng"}) {
            Response resp = given().header("Authorization", AUTH)
                    .contentType(ContentType.JSON)
                    .body("{\"count\":64}")
                    .when().post(path + API);
            resp.then().statusCode(200);
            byte[] bytes = Base64.getUrlDecoder().decode(resp.jsonPath().getString("value"));
            assertEquals(64, bytes.length, "rng byte count via " + path);
        }
    }

    // ── Managed HSM flavor detection ───────────────────────────────────────────

    @Test
    @DisplayName("Host with port still detects managed HSM flavor for kid URLs")
    void managedHsmKidHostWithPort() {
        given().header("Authorization", AUTH)
                .header("Host", "hsm1.managedhsm.azure.net:4577")
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048}")
                .when().post("/keys/hsmkey/create" + API)
                .then().statusCode(200)
                .body("key.kid", startsWith("https://hsm1.managedhsm.azure.net/keys/hsmkey/"));
    }

    @Test
    @DisplayName("challenge resource and root probe are flavor-aware")
    void unauthorizedChallengeAndRootProbeFlavor() {
        given().when().get(BASE + "/secrets/foo" + API)
                .then().statusCode(401)
                .header("WWW-Authenticate", containsString("resource=\"https://vault.azure.net\""));

        given().header("Host", "hsm1.managedhsm.azure.net:4577")
                .when().get("/secrets/foo" + API)
                .then().statusCode(401)
                .header("WWW-Authenticate", containsString("resource=\"https://managedhsm.azure.net\""));

        given().header("Authorization", AUTH)
                .when().get(BASE + API)
                .then().statusCode(200)
                .body("type", equalTo("Microsoft.KeyVault/vaults"))
                .body("id", equalTo("https://devstoreaccount1.vault.azure.net/"));

        given().header("Authorization", AUTH)
                .header("Host", "hsm1.managedhsm.azure.net:4577")
                .when().get("/" + API)
                .then().statusCode(200)
                .body("type", equalTo("Microsoft.KeyVault/managedHSMs"))
                .body("id", equalTo("https://hsm1.managedhsm.azure.net/"));
    }

    // ── Crypto version resolution ──────────────────────────────────────────────

    @Test
    @DisplayName("crypto ops with empty or omitted version resolve to the latest version")
    void cryptoOpWithEmptyVersionResolvesLatest() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048}")
                .when().post(BASE + "/keys/vr1/create" + API)
                .then().statusCode(200);

        String pt = b64Url("resolve-latest");
        for (String opPath : new String[]{"/keys/vr1//encrypt", "/keys/vr1/encrypt"}) {
            Response enc = given().header("Authorization", AUTH)
                    .contentType(ContentType.JSON)
                    .body("{\"alg\":\"RSA-OAEP-256\",\"value\":\"" + pt + "\"}")
                    .when().post(BASE + opPath + API);
            enc.then().statusCode(200);
            String ct = enc.jsonPath().getString("value");

            Response dec = given().header("Authorization", AUTH)
                    .contentType(ContentType.JSON)
                    .body("{\"alg\":\"RSA-OAEP-256\",\"value\":\"" + ct + "\"}")
                    .when().post(BASE + "/keys/vr1//decrypt" + API);
            dec.then().statusCode(200);
            assertEquals(pt, dec.jsonPath().getString("value"), "round-trip via " + opPath);
        }
    }

    // ── Soft-delete tombstoning ─────────────────────────────────────────────────

    @Test
    @DisplayName("soft-deleted key rejects version GET and crypto with 404")
    void softDeletedKeyRejectsVersionAccess() {
        Response created = given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048}")
                .when().post(BASE + "/keys/sd1/create" + API);
        created.then().statusCode(200);
        String kid = created.jsonPath().getString("key.kid");
        String version = kid.substring(kid.lastIndexOf('/') + 1);

        given().header("Authorization", AUTH)
                .when().delete(BASE + "/keys/sd1" + API)
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .when().get(BASE + "/keys/sd1/" + version + API)
                .then().statusCode(404)
                .body("error.code", equalTo("KeyNotFound"));

        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"alg\":\"RSA-OAEP-256\",\"value\":\"" + b64Url("hello") + "\"}")
                .when().post(BASE + "/keys/sd1/" + version + "/encrypt" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("KeyNotFound"));

        given().header("Authorization", AUTH)
                .when().get(BASE + "/keys/sd1/versions" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("KeyNotFound"));

        given().header("Authorization", AUTH)
                .when().post(BASE + "/deletedkeys/sd1/recover" + API)
                .then().statusCode(200);
    }

    @Test
    @DisplayName("expired and not-yet-valid keys return 403 on crypto")
    void expiredAndNotYetValidKeysForbidCrypto() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048,\"attributes\":{\"exp\":1000000000}}")
                .when().post(BASE + "/keys/exp1/create" + API)
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"alg\":\"RS256\",\"value\":\"" + b64Url("digest") + "\"}")
                .when().post(BASE + "/keys/exp1/sign" + API)
                .then().statusCode(403)
                .body("error.code", equalTo("Forbidden"));

        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048,\"attributes\":{\"nbf\":9999999999}}")
                .when().post(BASE + "/keys/nbf1/create" + API)
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"alg\":\"RS256\",\"value\":\"" + b64Url("digest") + "\"}")
                .when().post(BASE + "/keys/nbf1/sign" + API)
                .then().statusCode(403)
                .body("error.code", equalTo("Forbidden"));
    }

    @Test
    @DisplayName("malformed/attacker-crafted restore is rejected with 400")
    void restoreRejectsMalformedBackup() {
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post(BASE + "/keys/restore" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));

        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"value\":\"!!!\"}")
                .when().post(BASE + "/keys/restore" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));

        Map<String, Object> badName = new LinkedHashMap<>();
        badName.put("name", "bad/name");
        badName.put("versions", new ArrayList<>());
        String badNameValue = URL.encodeToString(
                toJson(badName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"value\":\"" + badNameValue + "\"}")
                .when().post(BASE + "/keys/restore" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));

        Map<String, Object> badJwkData = new LinkedHashMap<>();
        badJwkData.put("kty", "RSA");
        badJwkData.put("n", "not-base64!");
        Map<String, Object> badJwkVersion = new LinkedHashMap<>();
        badJwkVersion.put("version", "v1");
        badJwkVersion.put("data", badJwkData);
        List<Map<String, Object>> badJwkVersions = new ArrayList<>();
        badJwkVersions.add(badJwkVersion);
        Map<String, Object> badJwk = new LinkedHashMap<>();
        badJwk.put("name", "badkey1");
        badJwk.put("versions", badJwkVersions);
        String badJwkValue = URL.encodeToString(
                toJson(badJwk).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"value\":\"" + badJwkValue + "\"}")
                .when().post(BASE + "/keys/restore" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));

        Map<String, Object> full = fullRsaJwk();
        List<Map<String, Object>> manyVersions = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("version", "v" + i);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("kty", "RSA");
            data.put("n", full.get("n"));
            data.put("e", full.get("e"));
            entry.put("data", data);
            manyVersions.add(entry);
        }
        Map<String, Object> tooMany = new LinkedHashMap<>();
        tooMany.put("name", "badkey2");
        tooMany.put("versions", manyVersions);
        String tooManyValue = URL.encodeToString(
                toJson(tooMany).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"value\":\"" + tooManyValue + "\"}")
                .when().post(BASE + "/keys/restore" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));
    }

    @Test
    @DisplayName("rotateKey generates fresh key material")
    void rotateKeyGeneratesFreshMaterial() {
        Response created = given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048}")
                .when().post(BASE + "/keys/rot1/create" + API);
        created.then().statusCode(200);
        String v1 = created.jsonPath().getString("key.n");
        String v1Kid = created.jsonPath().getString("key.kid");
        String v1Version = v1Kid.substring(v1Kid.lastIndexOf('/') + 1);

        Response rotated = given().header("Authorization", AUTH)
                .when().post(BASE + "/keys/rot1/rotate" + API);
        rotated.then().statusCode(200);
        String v2 = rotated.jsonPath().getString("key.n");
        String v2Version = rotated.jsonPath().getString("key.kid");
        v2Version = v2Version.substring(v2Version.lastIndexOf('/') + 1);

        assertNotEquals(v1, v2, "rotated key must have fresh modulus");
        assertNotEquals(v1Version, v2Version, "rotated key must get a new version id");
        assertTrue(rotated.jsonPath().getLong("attributes.created") > 0, "rotated version needs a created timestamp");
        assertTrue(rotated.jsonPath().getLong("attributes.updated") > 0, "rotated version needs an updated timestamp");
    }

    @Test
    @DisplayName("key_ops [] forbids all operations")
    void emptyKeyOpsForbidsAllOperations() {
        Map<String, Object> jwk = fullRsaJwk();
        jwk.put("key_ops", List.of());
        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"key\":" + toJson(jwk) + "}")
                .when().put(BASE + "/keys/emptyops" + API)
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("{\"alg\":\"RS256\",\"value\":\"" + b64Url("digest") + "\"}")
                .when().post(BASE + "/keys/emptyops/sign" + API)
                .then().statusCode(403)
                .body("error.code", equalTo("Forbidden"));
    }

    // ── Regression: security/robustness fixes ──────────────────────────────────

    @Test
    @DisplayName("non-numeric nbf/exp attributes are rejected with 400")
    void nonNumericAttributesRejected() {
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048,\"attributes\":{\"exp\":\"not-a-number\"}}")
                .when().post(BASE + "/keys/nn1/create" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));

        Response created = given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048}")
                .when().post(BASE + "/keys/nn2/create" + API);
        created.then().statusCode(200);
        String kid = created.jsonPath().getString("key.kid");
        String version = kid.substring(kid.lastIndexOf('/') + 1);

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"attributes\":{\"nbf\":\"not-a-number\"}}")
                .when().patch(BASE + "/keys/nn2/" + version + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));
    }

    @Test
    @DisplayName("numeric-string exp is enforced, not silently ignored")
    void numericStringExpIsEnforced() {
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048,\"attributes\":{\"exp\":\"1000000000\"}}")
                .when().post(BASE + "/keys/nn3/create" + API)
                .then().statusCode(200);

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"RS256\",\"value\":\"" + b64Url("digest") + "\"}")
                .when().post(BASE + "/keys/nn3/sign" + API)
                .then().statusCode(403)
                .body("error.code", equalTo("Forbidden"));
    }

    @Test
    @DisplayName("non-string kty on import is rejected with 400")
    void importNonStringKtyRejected() {
        Map<String, Object> jwk = fullRsaJwk();
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"key\":{\"kty\":42,\"n\":\"" + jwk.get("n") + "\",\"e\":\"" + jwk.get("e") + "\"}}")
                .when().put(BASE + "/keys/badkty" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));
    }

    @Test
    @DisplayName("restore with a partially invalid backup leaves no orphaned key")
    void restorePartialFailureLeavesNoOrphans() {
        Map<String, Object> full = fullRsaJwk();
        Map<String, Object> validData = new LinkedHashMap<>();
        validData.put("kty", "RSA");
        validData.put("n", full.get("n"));
        validData.put("e", full.get("e"));
        Map<String, Object> validVersion = new LinkedHashMap<>();
        validVersion.put("version", "v1");
        validVersion.put("data", validData);

        Map<String, Object> invalidData = new LinkedHashMap<>();
        invalidData.put("kty", "RSA");
        Map<String, Object> invalidVersion = new LinkedHashMap<>();
        invalidVersion.put("version", "v2");
        invalidVersion.put("data", invalidData);

        List<Map<String, Object>> versions = new ArrayList<>();
        versions.add(validVersion);
        versions.add(invalidVersion);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", "orphan1");
        snapshot.put("versions", versions);
        String value = URL.encodeToString(toJson(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"value\":\"" + value + "\"}")
                .when().post(BASE + "/keys/restore" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));

        given().header("Authorization", AUTH)
                .when().get(BASE + "/keys/orphan1/v1" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("KeyNotFound"));
    }

    @Test
    @DisplayName("key_ops allow-list permits only the listed operations")
    void keyOpsAllowList() {
        Map<String, Object> jwk = fullRsaJwk();
        jwk.put("key_ops", List.of("encrypt"));
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"key\":" + toJson(jwk) + "}")
                .when().put(BASE + "/keys/allowops" + API)
                .then().statusCode(200);

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"RSA-OAEP-256\",\"value\":\"" + b64Url("hello") + "\"}")
                .when().post(BASE + "/keys/allowops/encrypt" + API)
                .then().statusCode(200);

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"RS256\",\"value\":\"" + b64Url("digest") + "\"}")
                .when().post(BASE + "/keys/allowops/sign" + API)
                .then().statusCode(403)
                .body("error.code", equalTo("Forbidden"));
    }

    @Test
    @DisplayName("invalid key names (containing /) are rejected with 400")
    void invalidKeyNameRejected() {
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048}")
                .when().post(BASE + "/keys/bad/name/create" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));
    }

    @Test
    @DisplayName("rng count outside 1..128 is rejected with 400")
    void rngCountBounds() {
        for (int count : new int[]{0, -5, 129, 1024}) {
            given().header("Authorization", AUTH).contentType(ContentType.JSON)
                    .body("{\"count\":" + count + "}")
                    .when().post(BASE + "/rng" + API)
                    .then().statusCode(400)
                    .body("error.code", equalTo("BadParameter"));
        }
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"count\":128}")
                .when().post(BASE + "/rng" + API)
                .then().statusCode(200);
    }

    @Test
    @DisplayName("rotating a key with an unsupported stored size returns 400")
    void rotateUnsupportedKeySize400() {
        byte[] k = new byte[20];
        new java.security.SecureRandom().nextBytes(k);
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"key\":{\"kty\":\"oct\",\"k\":\"" + URL.encodeToString(k) + "\"}}")
                .when().put(BASE + "/keys/weirdoct" + API)
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .when().post(BASE + "/keys/weirdoct/rotate" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));
    }

    @Test
    @DisplayName("non-string private JWK fields on import are rejected with 400")
    void importNonStringPrivateFieldRejected() {
        // RSA d
        Map<String, Object> rsaD = fullRsaJwk();
        rsaD.put("d", 123);
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"key\":" + toJson(rsaD) + "}")
                .when().put(BASE + "/keys/badd" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));

        // RSA p (a second RSA private field, distinct code path from d)
        Map<String, Object> rsaP = fullRsaJwk();
        rsaP.put("p", 123);
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"key\":" + toJson(rsaP) + "}")
                .when().put(BASE + "/keys/badp" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));

        // EC d — harvest a valid EC public JWK from the emulator, then corrupt the private field.
        Response ecResp = given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"kty\":\"EC\",\"crv\":\"P-256\"}")
                .when().post(BASE + "/keys/ecsrc/create" + API);
        ecResp.then().statusCode(200);
        Map<String, Object> ecJwk = new LinkedHashMap<>();
        ecJwk.put("kty", "EC");
        ecJwk.put("crv", ecResp.jsonPath().getString("key.crv"));
        ecJwk.put("x", ecResp.jsonPath().getString("key.x"));
        ecJwk.put("y", ecResp.jsonPath().getString("key.y"));
        ecJwk.put("d", 123);
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"key\":" + toJson(ecJwk) + "}")
                .when().put(BASE + "/keys/badecd" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));
    }

    @Test
    @DisplayName("restore normalizes whitespace-padded exp; expired restored key is 403, not bypassed")
    void restoreNormalizesWhitespaceExp() {
        Map<String, Object> full = fullRsaJwk();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kty", "RSA");
        data.put("n", full.get("n"));
        data.put("e", full.get("e"));
        data.put("d", full.get("d"));
        data.put("p", full.get("p"));
        data.put("q", full.get("q"));
        data.put("dp", full.get("dp"));
        data.put("dq", full.get("dq"));
        data.put("qi", full.get("qi"));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("exp", " 1000000000 "); // long in the past; only enforced if trimmed, not if dropped

        Map<String, Object> version = new LinkedHashMap<>();
        version.put("version", "v1");
        version.put("data", data);
        version.put("meta", meta);
        List<Map<String, Object>> versions = new ArrayList<>();
        versions.add(version);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", "wsexp");
        snapshot.put("latestVersion", "v1");
        snapshot.put("versions", versions);
        String value = URL.encodeToString(toJson(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"value\":\"" + value + "\"}")
                .when().post(BASE + "/keys/restore" + API)
                .then().statusCode(200);

        // If exp were dropped (unset), this encrypt would 200; trimmed-and-enforced it must 403.
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"RSA-OAEP-256\",\"value\":\"" + b64Url("hello") + "\"}")
                .when().post(BASE + "/keys/wsexp/encrypt" + API)
                .then().statusCode(403)
                .body("error.code", equalTo("Forbidden"));
    }

    @Test
    @DisplayName("restore with a latestVersion absent from versions is rejected with 400")
    void restoreLatestVersionMismatch400() {
        Map<String, Object> full = fullRsaJwk();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kty", "RSA");
        data.put("n", full.get("n"));
        data.put("e", full.get("e"));
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("version", "v1");
        version.put("data", data);
        List<Map<String, Object>> versions = new ArrayList<>();
        versions.add(version);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", "mismatch");
        snapshot.put("latestVersion", "v999");
        snapshot.put("versions", versions);
        String value = URL.encodeToString(toJson(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"value\":\"" + value + "\"}")
                .when().post(BASE + "/keys/restore" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadParameter"));
    }

    @Test
    @DisplayName("PATCH enabled=\"TRUE\" normalizes to true and leaves the key operable")
    void patchEnabledUppercaseNormalized() {
        Response created = given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048}")
                .when().post(BASE + "/keys/enup/create" + API);
        created.then().statusCode(200);
        String kid = created.jsonPath().getString("key.kid");
        String version = kid.substring(kid.lastIndexOf('/') + 1);

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"attributes\":{\"enabled\":\"TRUE\"}}")
                .when().patch(BASE + "/keys/enup/" + version + API)
                .then().statusCode(200)
                .body("attributes.enabled", equalTo(true));

        given().header("Authorization", AUTH)
                .when().get(BASE + "/keys/enup" + API)
                .then().statusCode(200)
                .body("attributes.enabled", equalTo(true));

        // The normalized value must satisfy the strict reader too: crypto op succeeds, not 403.
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"RS256\",\"value\":\"" + b64Url("0123456789abcdef0123456789abcdef") + "\"}")
                .when().post(BASE + "/keys/enup/sign" + API)
                .then().statusCode(200);
    }

    @Test
    @DisplayName("rotating a key with deny-all key_ops preserves the restriction")
    void rotatePreservesKeyOpsRestriction() {
        Map<String, Object> jwk = fullRsaJwk();
        jwk.put("key_ops", List.of());
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"key\":" + toJson(jwk) + "}")
                .when().put(BASE + "/keys/rotops" + API)
                .then().statusCode(200);

        given().header("Authorization", AUTH)
                .when().post(BASE + "/keys/rotops/rotate" + API)
                .then().statusCode(200);

        // The rotated version must still deny everything.
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"RS256\",\"value\":\"" + b64Url("digest") + "\"}")
                .when().post(BASE + "/keys/rotops/sign" + API)
                .then().statusCode(403)
                .body("error.code", equalTo("Forbidden"));
    }

    @Test
    @DisplayName("create with explicit empty key_ops denies all operations")
    void createWithExplicitEmptyKeyOps() {
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"kty\":\"RSA\",\"key_size\":2048,\"key_ops\":[]}")
                .when().post(BASE + "/keys/emptyops/create" + API)
                .then().statusCode(200);

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"RS256\",\"value\":\"" + b64Url("digest") + "\"}")
                .when().post(BASE + "/keys/emptyops/sign" + API)
                .then().statusCode(403)
                .body("error.code", equalTo("Forbidden"));
    }

    @Test
    @DisplayName("key_ops null forbids all operations")
    void nullKeyOpsForbidsAllOperations() {
        Map<String, Object> jwk = fullRsaJwk();
        jwk.put("key_ops", null);
        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"key\":" + toJson(jwk) + "}")
                .when().put(BASE + "/keys/nullops" + API)
                .then().statusCode(200);

        given().header("Authorization", AUTH).contentType(ContentType.JSON)
                .body("{\"alg\":\"RS256\",\"value\":\"" + b64Url("digest") + "\"}")
                .when().post(BASE + "/keys/nullops/sign" + API)
                .then().statusCode(403)
                .body("error.code", equalTo("Forbidden"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private static String b64Url(String value) {
        return URL.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Builds a full RSA JWK map (private CRT fields included) from a fresh JDK keypair. */
    private static Map<String, Object> fullRsaJwk() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
            RSAPrivateCrtKey priv = (RSAPrivateCrtKey) pair.getPrivate();
            Map<String, Object> jwk = new LinkedHashMap<>();
            jwk.put("kty", "RSA");
            jwk.put("n", URL.encodeToString(unsigned(pub.getModulus())));
            jwk.put("e", URL.encodeToString(unsigned(pub.getPublicExponent())));
            jwk.put("d", URL.encodeToString(unsigned(priv.getPrivateExponent())));
            jwk.put("p", URL.encodeToString(unsigned(priv.getPrimeP())));
            jwk.put("q", URL.encodeToString(unsigned(priv.getPrimeQ())));
            jwk.put("dp", URL.encodeToString(unsigned(priv.getPrimeExponentP())));
            jwk.put("dq", URL.encodeToString(unsigned(priv.getPrimeExponentQ())));
            jwk.put("qi", URL.encodeToString(unsigned(priv.getCrtCoefficient())));
            return jwk;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private static String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
