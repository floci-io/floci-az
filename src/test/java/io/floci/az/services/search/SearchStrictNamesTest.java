package io.floci.az.services.search;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(SearchStrictNamesTest.StrictNames.class)
@DisplayName("Search: parity-overrides.strict-names refuses service names outside the spec's rule")
class SearchStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true");
        }
    }

    private static final String BASE = "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.Search/"
        + "searchServices/";
    private static final String API = "?api-version=2025-05-01";
    private static final String BODY = "{\"location\":\"eastus\",\"sku\":{\"name\":\"basic\"}}";

    @Test
    void aServiceNameOutsideTheRuleIsRefused() {
        for (String bad : new String[] {"x", "Upper-Case", "-leading", "a-second", "trailing-", "double--hyphen",
            "under_score", "x".repeat(61)}) {
            given().contentType("application/json").body(BODY).when().put(BASE + bad + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        }
    }

    @Test
    void aServiceNameInsideTheRuleIsCreated() {
        for (String good : new String[] {"ab", "strict-search-1", "0digits-first", "x".repeat(60)}) {
            given().contentType("application/json").body(BODY).when().put(BASE + good + API)
                .then().statusCode(201);
        }
    }
}
