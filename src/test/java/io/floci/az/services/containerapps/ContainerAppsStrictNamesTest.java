package io.floci.az.services.containerapps;

import io.floci.az.testing.StrictNamesProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(StrictNamesProfile.class)
@DisplayName("Container Apps: parity-overrides.strict-names refuses a container app name outside Azure's rule")
class ContainerAppsStrictNamesTest {

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
