package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;

@QuarkusTest
@TestProfile(ArmStorageAccountKeysTest.ConfiguredKey.class)
@DisplayName("ARM storage account listKeys")
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
    void listKeysReturnsTheConfiguredKeyForThatAccount() {
        createAccount(CONFIGURED_ACCOUNT);

        given()
            .when().post(ARM + CONFIGURED_ACCOUNT + "/listKeys" + API)
            .then()
            .statusCode(200)
            .body("keys.keyName", contains("key1", "key2"))
            .body("keys.value", everyItem(equalTo(CONFIGURED_KEY)))
            .body("keys.permissions", everyItem(equalTo("Full")));
    }

    @Test
    void listKeysReturnsTheDevelopmentKeyForAnUnconfiguredAccount() {
        createAccount("unconfiguredkeyacct");

        given()
            .when().post(ARM + "unconfiguredkeyacct/listKeys" + API)
            .then()
            .statusCode(200)
            .body("keys.value", everyItem(equalTo(DEFAULT_KEY)))
            .body("keys.permissions", everyItem(equalTo("Full")));
    }

    @Test
    void serviceSasSignedWithTheListedKeyReadsTheBlob() throws Exception {
        createAccount(CONFIGURED_ACCOUNT);
        String listedKey = given()
            .when().post(ARM + CONFIGURED_ACCOUNT + "/listKeys" + API)
            .then()
            .statusCode(200)
            .extract().path("keys[0].value");

        String blobPath = "/" + CONFIGURED_ACCOUNT + "/listed-key/file";
        given().put("/" + CONFIGURED_ACCOUNT + "/listed-key?restype=container");
        given().header("x-ms-blob-type", "BlockBlob").body("signed")
                .put(blobPath).then().statusCode(201);

        given().queryParams(blobSas(listedKey)).get(blobPath)
                .then().statusCode(200).body(equalTo("signed"));
        given().queryParams(blobSas(DEFAULT_KEY)).get(blobPath)
                .then().statusCode(403).header("x-ms-error-code", "AuthenticationFailed");
    }

    private static void createAccount(String account) {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\"}")
            .when().put(ARM + account + API)
            .then()
            .statusCode(200);
    }

    private static Map<String, String> blobSas(String key) throws Exception {
        String version = "2020-12-06";
        String expiry = Instant.now().plusSeconds(3600).toString();
        String canonical = "/blob/" + CONFIGURED_ACCOUNT + "/listed-key/file";
        String fields = "r\n\n" + expiry + "\n" + canonical + "\n\n\n\n" + version + "\nb\n\n\n\n\n\n\n";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(key), "HmacSHA256"));
        Map<String, String> query = new LinkedHashMap<>(Map.of("sv", version, "sp", "r", "sr", "b", "se", expiry));
        query.put("sig", Base64.getEncoder().encodeToString(mac.doFinal(fields.getBytes(StandardCharsets.UTF_8))));
        return query;
    }
}
