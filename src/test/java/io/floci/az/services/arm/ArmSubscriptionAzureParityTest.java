package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * {@code parity: azure} turns {@code subscription-listing} on: first-use subscriptions are listed after the
 * default one and echoed with the directory's id, while unknown ids are still registered rather than refused.
 */
@QuarkusTest
@TestProfile(ArmSubscriptionAzureParityTest.AzureParityProfile.class)
@DisplayName("ARM subscriptions under parity: azure")
class ArmSubscriptionAzureParityTest {

    private static final String DEFAULT_SUB = "00000000-0000-0000-0000-000000000001";
    private static final String API = "?api-version=2022-12-01";

    public static class AzureParityProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity", "azure");
        }
    }

    @Test
    void aSubscriptionFirstUsedByAChildRequestIsListedAfterTheDefault() {
        String sub = "33333333-2222-0000-0000-000000000000";
        given().when().get("/subscriptions/" + sub + "/resourceGroups" + API)
                .then().statusCode(200);

        given().when().get("/subscriptions" + API)
                .then().statusCode(200)
                .body("value[0].subscriptionId", equalTo(DEFAULT_SUB))
                .body("value.subscriptionId", hasItem(sub))
                .body("value.find { it.subscriptionId == '" + sub + "' }.state", equalTo("Enabled"))
                .body("value.find { it.subscriptionId == '" + sub + "' }.displayName", equalTo("floci-az local"));
    }

    @Test
    void aSubscriptionIsEchoedWithTheIdItWasFirstSeenWith() {
        String firstSeen = "55555555-bbbb-0000-0000-000000000000";
        given().when().get("/subscriptions/" + firstSeen + API)
                .then().statusCode(200)
                .body("subscriptionId", equalTo(firstSeen));

        given().when().get("/subscriptions/" + firstSeen.toUpperCase(Locale.ROOT) + API)
                .then().statusCode(200)
                .body("id", equalTo("/subscriptions/" + firstSeen))
                .body("subscriptionId", equalTo(firstSeen));
    }
}
