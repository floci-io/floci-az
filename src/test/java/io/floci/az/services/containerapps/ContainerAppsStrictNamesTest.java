package io.floci.az.services.containerapps;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(ContainerAppsStrictNamesTest.StrictNames.class)
@DisplayName("Container Apps: parity-overrides.strict-names refuses a container app name outside Azure's rule")
class ContainerAppsStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true",
                "floci-az.services.container-apps.mocked", "true");
        }
    }

    private static final String BASE = "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.App/containerApps/";
    private static final String API = "?api-version=2025-07-01";
    private static final String BODY = "{\"location\":\"eastus\",\"properties\":{}}";

    @Test
    void aNameOutsideTheRuleIsRefusedBeforeTheHandler() {
        for (String bad : new String[] {"Upper", "1app", "app-", "a", "x".repeat(33)}) {
            given().contentType("application/json").body(BODY).when().put(BASE + bad + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        }
        given().contentType("application/json").body(BODY).when().put(BASE + "good-app-1" + API)
            .then().body("error.code", not(equalTo("InvalidResourceName")));
    }
}
