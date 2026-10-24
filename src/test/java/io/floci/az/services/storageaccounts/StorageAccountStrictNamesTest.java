package io.floci.az.services.storageaccounts;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(StorageAccountStrictNamesTest.StrictNames.class)
@DisplayName("Storage accounts: parity-overrides.strict-names answers a name outside the rule as before")
class StorageAccountStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true");
        }
    }

    private static final String BASE =
        "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/";
    private static final String API = "?api-version=2023-01-01";
    private static final String BODY = "{\"location\":\"eastus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}";

    @Test
    void aNameOutsideTheRuleIsRefusedAndAValidOneIsCreated() {
        given().contentType("application/json").body(BODY).when().put(BASE + "bad-name" + API)
            .then().statusCode(400)
            .body("error.code", equalTo("AccountNameInvalid"))
            .body("error.message", equalTo("bad-name is not a valid storage account name. Storage account name must "
                + "be between 3 and 24 characters in length and use numbers and lower-case letters only."));
        given().contentType("application/json").body(BODY).when().put(BASE + "ab" + API)
            .then().statusCode(400).body("error.code", equalTo("AccountNameInvalid"));
        given().contentType("application/json").body(BODY).when().put(BASE + "a".repeat(25) + API)
            .then().statusCode(400).body("error.code", equalTo("AccountNameInvalid"));
        given().contentType("application/json").body(BODY).when().put(BASE + "goodname01" + API)
            .then().statusCode(200);
    }
}
