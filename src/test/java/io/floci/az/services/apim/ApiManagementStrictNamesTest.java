package io.floci.az.services.apim;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(ApiManagementStrictNamesTest.StrictNames.class)
@DisplayName("API Management: parity-overrides.strict-names refuses a service name outside Azure's rule")
class ApiManagementStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true");
        }
    }

    private static final String BASE = "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.ApiManagement/service/";
    private static final String API = "?api-version=2022-08-01";
    private static final String BODY = "{\"location\":\"eastus\",\"sku\":{\"name\":\"Developer\",\"capacity\":1},"
        + "\"properties\":{\"publisherEmail\":\"a@b.c\",\"publisherName\":\"x\"}}";

    @Test
    void aNameOutsideTheRuleIsRefusedBeforeTheHandler() {
        for (String bad : new String[] {"1apim", "apim-", "apim_x", "x".repeat(51)}) {
            given().contentType("application/json").body(BODY).when().put(BASE + bad + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        }
        for (String good : new String[] {"a", "Apim-1", "x".repeat(50)}) {
            given().contentType("application/json").body(BODY).when().put(BASE + good + API)
                .then().statusCode(200);
        }
    }
}
