package io.floci.az.services.monitor;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

/** {@code floci-az.services.monitor.enabled=false} stops the ARM lane serving Monitor's types. */
@QuarkusTest
@TestProfile(MonitorDisabledTest.DisabledProfile.class)
@DisplayName("Monitor disabled: its ARM types are not served")
class MonitorDisabledTest {

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.monitor.enabled", "false");
        }
    }

    private static final String API = "?api-version=2022-10-01";
    private static final String GROUP = "/subscriptions/sub-monoff/resourceGroups/rg-monoff/providers/";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Microsoft.OperationalInsights/workspaces", "Microsoft.Insights/dataCollectionEndpoints",
        "Microsoft.Insights/dataCollectionRules"})
    void aPutOfAMonitorTypeAnswersNotFound(String type) {
        given().contentType(ContentType.JSON).body("{\"location\":\"eastus\",\"properties\":{}}")
            .when().put(GROUP + type + "/off" + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void theRestOfTheArmPlaneKeepsWorking() {
        given().contentType(ContentType.JSON).body("{\"location\":\"eastus\"}")
            .when().put("/subscriptions/sub-monoff/resourceGroups/rg-monoff?api-version=2021-04-01")
            .then().statusCode(anyOf(equalTo(200), equalTo(201)));
    }
}
