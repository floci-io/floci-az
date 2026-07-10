package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Verifies that {@code floci-az.services.arm.enabled=false} turns off the ARM management plane,
 * so {@code /subscriptions/...} and {@code /providers/...} calls are no longer served.
 */
@QuarkusTest
@TestProfile(ArmDisabledTest.DisabledProfile.class)
@DisplayName("ARM disabled — management plane gated off")
@SuppressWarnings("unused")
class ArmDisabledTest {

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.arm.enabled", "false");
        }
    }

    @Test
    @DisplayName("GET subscription reports ServiceDisabled when ARM disabled")
    void subscriptionGatedOff() {
        // With ARM enabled this returns 200. Disabled, the ARM stage now answers 503 ServiceDisabled.
        // It previously declined and let the request fall through to the account-suffix terminal, which
        // read "subscriptions" as a storage account name and let the blob handler answer 501 — the same
        // mis-routing that made a disabled Key Vault return a storage error. See DisabledServiceRoutingTest.
        given()
                .when().get("/subscriptions/test-sub-armoff?api-version=2021-04-01")
                .then().statusCode(503)
                .body(containsString("ServiceDisabled"));
    }
}
