package io.floci.az.services.eventgrid;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(EventGridStrictNamesTest.StrictNames.class)
@DisplayName("Event Grid: parity-overrides.strict-names refuses topic and event subscription names outside Azure's rule")
class EventGridStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true");
        }
    }

    private static final String BASE =
        "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.EventGrid/topics/";
    private static final String API = "?api-version=2025-02-15";
    private static final String TOPIC_BODY = "{\"location\":\"eastus\",\"properties\":{}}";
    private static final String SUBSCRIPTION_BODY = "{\"properties\":{\"destination\":{\"endpointType\":\"WebHook\","
        + "\"properties\":{\"endpointUrl\":\"http://127.0.0.1:1/hook\"}}}}";

    @Test
    void aTopicNameOutsideTheRuleIsRefusedAndAValidOneIsCreated() {
        for (String bad : new String[] {"ab", "under_score", "t".repeat(51)}) {
            given().contentType("application/json").body(TOPIC_BODY).when().put(BASE + bad + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        }
        given().contentType("application/json").body(TOPIC_BODY).when().put(BASE + "Good-Topic-1" + API)
            .then().statusCode(200);
    }

    @Test
    void anEventSubscriptionNameOutsideTheRuleIsRefusedAndAValidOneIsCreated() {
        given().contentType("application/json").body(TOPIC_BODY).when().put(BASE + "names-topic" + API)
            .then().statusCode(200);
        String subscriptions = BASE + "names-topic/providers/Microsoft.EventGrid/eventSubscriptions/";
        for (String bad : new String[] {"ab", "dot.ted", "s".repeat(65)}) {
            given().contentType("application/json").body(SUBSCRIPTION_BODY).when().put(subscriptions + bad + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        }
        given().contentType("application/json").body(SUBSCRIPTION_BODY)
            .when().put(subscriptions + "good-subscription" + API).then().statusCode(200);
    }
}
