package io.floci.az.services.iothub;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IotHubStrictNamesTest.StrictNames.class)
@DisplayName("IoT Hub: parity-overrides.strict-names refuses hub names outside the spec's rule")
class IotHubStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true");
        }
    }

    private static final String BASE = "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.Devices/"
        + "IotHubs/";
    private static final String API = "?api-version=2023-06-30";
    private static final String BODY = "{\"location\":\"eastus\",\"sku\":{\"name\":\"S1\"}}";

    @Test
    void aHubNameOutsideTheRuleIsRefused() {
        for (String bad : new String[] {"ab", "12345", "-leading", "trailing-", "under_score", "x".repeat(51)}) {
            given().contentType("application/json").body(BODY).when().put(BASE + bad + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        }
    }

    @Test
    void aConsumerGroupNameOutsideTheRuleIsRefusedByTheGate() {
        given().contentType("application/json").body(BODY).when().put(BASE + "cg-hub" + API).then().statusCode(201);
        String groups = BASE + "cg-hub/eventHubEndpoints/events/ConsumerGroups/";
        for (String bad : new String[] {"with~tilde", "x".repeat(51)}) {
            given().contentType("application/json").body("{\"properties\": {\"name\": \"" + bad + "\"}}")
                .when().put(groups + bad + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        }
        for (String good : new String[] {"cg.one_2-x", "$Default"}) {
            given().contentType("application/json").body("{\"properties\": {\"name\": \"" + good + "\"}}")
                .when().put(groups + good + API).then().statusCode(200);
        }
    }

    @Test
    void aHubNameInsideTheRuleIsCreated() {
        for (String good : new String[] {"abc", "Mixed-Case-Hub", "1digit-first", "x".repeat(50)}) {
            given().contentType("application/json").body(BODY).when().put(BASE + good + API)
                .then().statusCode(201);
        }
    }
}
