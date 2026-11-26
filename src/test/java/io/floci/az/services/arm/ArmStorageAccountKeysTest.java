package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
@TestProfile(ArmStorageAccountKeysTest.ConfiguredKey.class)
@DisplayName("ARM storage account listKeys and regenerateKey")
class ArmStorageAccountKeysTest {

    private static final String DEFAULT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final String CONFIGURED_ACCOUNT = "configuredkeyacct";
    private static final String CONFIGURED_KEY = configuredKey();
    private static final String ARM = "/subscriptions/sub-list-keys/resourceGroups/rg-list-keys"
            + "/providers/Microsoft.Storage/storageAccounts/";
    private static final String API = "?api-version=2025-08-01";

    public static class ConfiguredKey implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.auth.storage-account-keys." + CONFIGURED_ACCOUNT, CONFIGURED_KEY);
        }
    }

    private static String configuredKey() {
        byte[] bytes = new byte[64];
        Arrays.fill(bytes, (byte) 7);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void key1IsTheConfiguredKeyAndKey2IsAnotherOne() {
        createAccount(CONFIGURED_ACCOUNT);

        JsonPath keys = given()
            .when().post(ARM + CONFIGURED_ACCOUNT + "/listKeys" + API)
            .then()
            .statusCode(200)
            .body("keys.keyName", contains("key1", "key2"))
            .body("keys.permissions", everyItem(equalTo("Full")))
            .body("keys.creationTime", everyItem(notNullValue()))
            .extract().jsonPath();
        assertEquals(CONFIGURED_KEY, keys.getString("keys[0].value"));
        assertNotEquals(CONFIGURED_KEY, keys.getString("keys[1].value"));
        assertEquals(64, Base64.getDecoder().decode(keys.getString("keys[1].value")).length);
    }

    @Test
    void key1OfAnUnconfiguredAccountIsTheDevelopmentKey() {
        createAccount("unconfiguredkeyacct");

        JsonPath keys = listKeys("unconfiguredkeyacct");
        assertEquals(DEFAULT_KEY, keys.getString("keys[0].value"));
        assertNotEquals(DEFAULT_KEY, keys.getString("keys[1].value"));
    }

    @Test
    void regenerateKeyReplacesTheNamedKeyOnly() {
        createAccount("regenkeyacct");
        JsonPath before = listKeys("regenkeyacct");

        JsonPath after = given()
            .contentType("application/json")
            .body("{\"keyName\":\"key2\"}")
            .when().post(ARM + "regenkeyacct/regenerateKey" + API)
            .then()
            .statusCode(200)
            .body("keys.keyName", contains("key1", "key2"))
            .extract().jsonPath();

        assertEquals(before.getString("keys[0].value"), after.getString("keys[0].value"));
        assertEquals(before.getString("keys[0].creationTime"), after.getString("keys[0].creationTime"));
        assertNotEquals(before.getString("keys[1].value"), after.getString("keys[1].value"));
        assertEquals(64, Base64.getDecoder().decode(after.getString("keys[1].value")).length);
        assertEquals(after.getString("keys[1].value"), listKeys("regenkeyacct").getString("keys[1].value"));
    }

    @Test
    void regenerateKeyRefusesAnotherKeyName() {
        createAccount("regenbadacct");

        given()
            .contentType("application/json")
            .body("{\"keyName\":\"key3\"}")
            .when().post(ARM + "regenbadacct/regenerateKey" + API)
            .then()
            .statusCode(400);
    }

    @Test
    void regenerateKeyOfAMissingAccountIsNotFound() {
        given()
            .contentType("application/json")
            .body("{\"keyName\":\"key1\"}")
            .when().post(ARM + "nosuchkeyacct/regenerateKey" + API)
            .then()
            .statusCode(404);
    }

    @Test
    void anAccountCreatedAgainGetsANewKey2() {
        createAccount("recreatedkeyacct");
        String key2 = listKeys("recreatedkeyacct").getString("keys[1].value");

        given().when().delete(ARM + "recreatedkeyacct" + API).then().statusCode(200);
        createAccount("recreatedkeyacct");

        assertNotEquals(key2, listKeys("recreatedkeyacct").getString("keys[1].value"));
    }

    @Test
    void serviceSasSignedWithEitherListedKeyReadsTheBlob() throws Exception {
        createAccount(CONFIGURED_ACCOUNT);
        JsonPath keys = listKeys(CONFIGURED_ACCOUNT);

        String blobPath = "/" + CONFIGURED_ACCOUNT + "/listed-key/file";
        given().put("/" + CONFIGURED_ACCOUNT + "/listed-key?restype=container");
        given().header("x-ms-blob-type", "BlockBlob").body("signed")
                .put(blobPath).then().statusCode(201);

        given().queryParams(blobSas(keys.getString("keys[0].value"))).get(blobPath)
                .then().statusCode(200).body(equalTo("signed"));
        given().queryParams(blobSas(keys.getString("keys[1].value"))).get(blobPath)
                .then().statusCode(200).body(equalTo("signed"));
        given().queryParams(blobSas(DEFAULT_KEY)).get(blobPath)
                .then().statusCode(403).header("x-ms-error-code", "AuthenticationFailed");
    }

    @Test
    void aRegeneratedKeyNoLongerSignsAServiceSas() throws Exception {
        String account = "rotatedsasacct";
        createAccount(account);
        String blobPath = "/" + account + "/rotated/file";
        given().put("/" + account + "/rotated?restype=container");
        given().header("x-ms-blob-type", "BlockBlob").body("rotated").put(blobPath).then().statusCode(201);
        Map<String, String> oldSas = blobSas(account, "/rotated/file", listKeys(account).getString("keys[0].value"));

        given().contentType("application/json").body("{\"keyName\":\"key1\"}")
            .when().post(ARM + account + "/regenerateKey" + API).then().statusCode(200);

        Response refused = given().queryParams(oldSas).get(blobPath);
        refused.then().statusCode(403)
                .header("x-ms-error-code", "AuthenticationFailed")
                .body(containsString("<AuthenticationErrorDetail>Signature did not match. String to sign used was r"))
                .body(containsString("RequestId:" + refused.getHeader("x-ms-request-id")));
        given().queryParams(blobSas(account, "/rotated/file", listKeys(account).getString("keys[0].value")))
                .get(blobPath).then().statusCode(200).body(equalTo("rotated"));
        given().queryParams(blobSas(account, "/rotated/file", listKeys(account).getString("keys[1].value")))
                .get(blobPath).then().statusCode(200).body(equalTo("rotated"));
    }

    @Test
    void accountSasSignedWithAnAccountKeyReadsTheBlob() throws Exception {
        String account = "accountsasacct";
        createAccount(account);
        String blobPath = "/" + account + "/granted/file";
        given().put("/" + account + "/granted?restype=container");
        given().header("x-ms-blob-type", "BlockBlob").body("granted").put(blobPath).then().statusCode(201);
        JsonPath keys = listKeys(account);

        given().queryParams(accountSas(account, keys.getString("keys[1].value"), "b", "o", "r")).get(blobPath)
                .then().statusCode(200).body(equalTo("granted"));
        given().queryParams(accountSas(account, configuredKey(), "b", "o", "r")).get(blobPath)
                .then().statusCode(403).header("x-ms-error-code", "AuthenticationFailed")
                .body(containsString("Signature did not match. String to sign used was " + account + "\nr\nb\no\n"));
        given().queryParams(accountSas(account, keys.getString("keys[0].value"), "q", "o", "r")).get(blobPath)
                .then().statusCode(403).header("x-ms-error-code", "AuthorizationServiceMismatch");
        given().queryParams(accountSas(account, keys.getString("keys[0].value"), "b", "c", "r")).get(blobPath)
                .then().statusCode(403).header("x-ms-error-code", "AuthorizationResourceTypeMismatch");
        given().queryParams(accountSas(account, keys.getString("keys[0].value"), "b", "o", "l")).get(blobPath)
                .then().statusCode(403).header("x-ms-error-code", "AuthorizationPermissionMismatch");
    }

    private static void createAccount(String account) {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\"}")
            .when().put(ARM + account + API)
            .then()
            .statusCode(200);
    }

    private static JsonPath listKeys(String account) {
        return given().when().post(ARM + account + "/listKeys" + API)
                .then().statusCode(200).extract().jsonPath();
    }

    private static Map<String, String> blobSas(String key) throws Exception {
        return blobSas(CONFIGURED_ACCOUNT, "/listed-key/file", key);
    }

    private static Map<String, String> blobSas(String account, String path, String key) throws Exception {
        String version = "2020-12-06";
        String expiry = Instant.now().plusSeconds(3600).toString();
        String canonical = "/blob/" + account + path;
        String fields = "r\n\n" + expiry + "\n" + canonical + "\n\n\n\n" + version + "\nb\n\n\n\n\n\n\n";
        Map<String, String> query = new LinkedHashMap<>(Map.of("sv", version, "sp", "r", "sr", "b", "se", expiry));
        query.put("sig", hmac(key, fields));
        return query;
    }

    /** An account SAS as Create an account SAS signs it at 2020-12-06 and later. */
    private static Map<String, String> accountSas(String account, String key, String services, String types,
                                                  String permissions) throws Exception {
        String version = "2022-11-02";
        String expiry = Instant.now().plusSeconds(3600).toString().replaceAll("\\.\\d+Z$", "Z");
        String fields = account + "\n" + permissions + "\n" + services + "\n" + types + "\n\n" + expiry
                + "\n\nhttps,http\n" + version + "\n\n";
        Map<String, String> query = new LinkedHashMap<>(Map.of("sv", version, "ss", services, "srt", types,
                "sp", permissions, "se", expiry, "spr", "https,http"));
        query.put("sig", hmac(key, fields));
        return query;
    }

    private static String hmac(String key, String fields) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(key), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(fields.getBytes(StandardCharsets.UTF_8)));
    }
}
