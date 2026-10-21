package io.floci.az.services.acr;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(AcrStrictNamesTest.StrictNames.class)
@DisplayName("ACR: parity-overrides.strict-names refuses a registry name outside Azure's rule")
class AcrStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true", "floci-az.services.acr.mocked", "true");
        }
    }

    private static final String BASE =
        "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.ContainerRegistry/registries/";
    private static final String API = "?api-version=2023-07-01";
    private static final String BODY = "{\"location\":\"eastus\",\"sku\":{\"name\":\"Basic\"}}";

    @Test
    void aNameOutsideTheRuleIsRefusedAndAValidOneIsCreated() {
        given().contentType("application/json").body(BODY).when().put(BASE + "bad-name" + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        given().contentType("application/json").body(BODY).when().put(BASE + "abc" + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        given().contentType("application/json").body(BODY).when().put(BASE + "goodname" + API)
            .then().statusCode(201);
    }
}
