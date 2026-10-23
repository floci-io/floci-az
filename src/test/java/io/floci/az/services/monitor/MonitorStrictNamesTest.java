package io.floci.az.services.monitor;

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
@TestProfile(MonitorStrictNamesTest.StrictNames.class)
@DisplayName("Monitor: parity-overrides.strict-names refuses a workspace name outside Azure's rule")
class MonitorStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true");
        }
    }

    private static final String BASE =
        "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.OperationalInsights/workspaces/";
    private static final String API = "?api-version=2025-07-01";
    private static final String BODY = "{\"location\":\"eastus\"}";

    @Test
    void aNameOutsideTheRuleIsRefusedBeforeTheHandler() {
        for (String bad : new String[] {"abc", "-logs", "logs-", "log_s", "x".repeat(64)}) {
            given().contentType("application/json").body(BODY).when().put(BASE + bad + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        }
        given().contentType("application/json").body(BODY).when().put(BASE + "Logs-1" + API)
            .then().statusCode(201).body("error.code", not(equalTo("InvalidResourceName")));
    }
}
