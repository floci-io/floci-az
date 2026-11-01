package io.floci.az.services.arm;

import io.floci.az.core.cloud.ScopeResolver;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code parity-overrides.strict-subscriptions: false} keeps first use under {@code parity: strict}, and
 * {@code cloud.default} moves the default tenant's cloud.
 */
@QuarkusTest
@TestProfile(ArmSubscriptionStrictOverrideTest.OverrideProfile.class)
@DisplayName("ARM subscriptions: the fine-grained switch overrides parity")
class ArmSubscriptionStrictOverrideTest {

    private static final String SUB = "77777777-0000-0000-0000-000000000000";
    private static final String API = "?api-version=2022-12-01";

    public static class OverrideProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "floci-az.parity", "strict",
                // StrictTokensTest covers the token check; these requests carry no token.
                "floci-az.parity-overrides.strict-tokens", "false",
                "floci-az.parity-overrides.strict-subscriptions", "false",
                "floci-az.cloud.default", "AzureChinaCloud");
        }
    }

    @Inject
    ScopeResolver scopeResolver;

    @Test
    void anUnknownSubscriptionIsRegisteredOnFirstUse() {
        given().when().get("/subscriptions/" + SUB + API)
                .then().statusCode(200)
                .body("subscriptionId", equalTo(SUB))
                .body("state", equalTo("Enabled"));

        given().when().get("/subscriptions" + API)
                .then().statusCode(200)
                .body("value.subscriptionId", hasItem(SUB));
    }

    @Test
    void theDefaultCloudIsTheConfiguredOne() {
        assertEquals("AzureChinaCloud", scopeResolver.resolve("subscriptions/" + SUB, "localhost").cloud().name());
        assertEquals("AzureChinaCloud", scopeResolver.resolve("devstoreaccount1/c", "localhost").cloud().name());
    }
}
