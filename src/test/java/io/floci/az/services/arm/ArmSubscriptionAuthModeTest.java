package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * {@code auth.mode: strict} is not read by anything: users may already have set it, so subscriptions keep their
 * compat behaviour under it. Strictness comes from {@code parity} alone.
 */
@QuarkusTest
@TestProfile(ArmSubscriptionAuthModeTest.StrictAuthModeProfile.class)
@DisplayName("ARM subscriptions ignore auth.mode")
class ArmSubscriptionAuthModeTest {

    private static final String DEFAULT_SUB = "00000000-0000-0000-0000-000000000001";
    private static final String API = "?api-version=2022-12-01";

    public static class StrictAuthModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.auth.mode", "strict");
        }
    }

    @Test
    void anUnknownSubscriptionIsServedAndNotListed() {
        String sub = "66666666-0000-0000-0000-000000000000";
        given().when().get("/subscriptions/" + sub + API)
                .then().statusCode(200)
                .body("subscriptionId", equalTo(sub))
                .body("state", equalTo("Enabled"));

        given().when().get("/subscriptions" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].subscriptionId", equalTo(DEFAULT_SUB));
    }
}
