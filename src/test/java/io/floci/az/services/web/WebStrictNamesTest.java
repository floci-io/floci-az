package io.floci.az.services.web;

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
@TestProfile(WebStrictNamesTest.StrictNames.class)
@DisplayName("Web ARM: parity-overrides.strict-names refuses a site name outside Azure's rule")
class WebStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true");
        }
    }

    private static final String BASE = "/subscriptions/sub-sitenames/resourceGroups/rg/providers/Microsoft.Web/sites/";
    private static final String API = "?api-version=2023-01-01";
    private static final String SITE_BODY = "{\"location\":\"eastus\",\"properties\":{}}";

    @ParameterizedTest
    @ValueSource(strings = {"a", "-site", "site-", "my_site", "my.site",
        "a2345678901234567890123456789012345678901234567890123456789x1"})
    void aSiteNameOutsideTheRuleIsRefused(String name) {
        given().contentType("application/json").body(SITE_BODY).when().put(BASE + name + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
    }

    @Test
    void namesInsideTheRuleAreCreated() {
        given().contentType("application/json").body(SITE_BODY).when().put(BASE + "ab" + API)
            .then().statusCode(200);
        given().contentType("application/json").body(SITE_BODY).when().put(BASE + "1-My--Site-2" + API)
            .then().statusCode(200);
        given().contentType("application/json").body(SITE_BODY)
            .when().put(BASE + "a23456789012345678901234567890123456789012345678901234567890" + API)
            .then().statusCode(200);
    }
}
