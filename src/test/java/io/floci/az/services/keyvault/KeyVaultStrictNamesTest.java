package io.floci.az.services.keyvault;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(KeyVaultStrictNamesTest.StrictNames.class)
@DisplayName("Key Vault ARM: parity-overrides.strict-names refuses a vault or managed HSM name outside Azure's rule")
class KeyVaultStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true");
        }
    }

    private static final String BASE = "/subscriptions/sub-kvnames/resourceGroups/rg/providers/Microsoft.KeyVault/";
    private static final String API = "?api-version=2023-07-01";
    private static final String VAULT_BODY = "{\"location\":\"eastus\",\"properties\":{\"tenantId\":"
        + "\"00000000-0000-0000-0000-000000000002\",\"sku\":{\"family\":\"A\",\"name\":\"standard\"}}}";
    private static final String HSM_BODY = "{\"location\":\"eastus\",\"sku\":{\"family\":\"B\",\"name\":\"Standard_B1\"},"
        + "\"properties\":{\"initialAdminObjectIds\":[\"o\"]}}";

    @ParameterizedTest
    @ValueSource(strings = {"kv", "1vault", "vault-", "my--vault", "vault_name", "a234567890123456789012345"})
    void aVaultNameOutsideTheRuleIsRefused(String name) {
        given().contentType("application/json").body(VAULT_BODY).when().put(BASE + "vaults/" + name + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hs", "1hsm", "hsm-", "my--hsm", "a234567890123456789012345"})
    void anHsmNameOutsideTheRuleIsRefused(String name) {
        given().contentType("application/json").body(HSM_BODY).when().put(BASE + "managedHSMs/" + name + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
    }

    @Test
    void namesInsideTheRuleAreCreated() {
        given().contentType("application/json").body(VAULT_BODY).when().put(BASE + "vaults/my-Vault-1" + API)
            .then().statusCode(200);
        given().contentType("application/json").body(VAULT_BODY).when()
            .put(BASE + "vaults/a23456789012345678901234" + API)
            .then().statusCode(200);
        given().contentType("application/json").body(HSM_BODY).when().put(BASE + "managedHSMs/my-hsm-1" + API)
            .then().statusCode(200);
    }
}
