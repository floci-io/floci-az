package io.floci.az.services.policy;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies that {@code floci-az.services.policy.enabled=false} removes the Microsoft.Authorization
 * policy resources while the rest of the ARM management plane keeps working.
 */
@QuarkusTest
@TestProfile(PolicyDisabledTest.DisabledProfile.class)
@DisplayName("Azure Policy disabled: provider route gated off, ARM otherwise intact")
@SuppressWarnings("unused")
class PolicyDisabledTest {

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.policy.enabled", "false");
        }
    }

    private static final String SUB = "test-sub-policyoff";
    private static final String RG = "test-rg-policyoff";
    private static final String API = "?api-version=2025-03-01";
    private static final String DEFINITION = "/subscriptions/" + SUB
            + "/providers/Microsoft.Authorization/policyDefinitions/d1";
    private static final String ASSIGNMENT = "/subscriptions/" + SUB + "/resourceGroups/" + RG
            + "/providers/Microsoft.Authorization/policyAssignments/a1";

    @Test
    @DisplayName("PUT policy definition returns 404 when the policy service is disabled")
    void definitionCreateGatedOff() {
        given().contentType("application/json")
                .body("{\"properties\":{\"policyRule\":{\"if\":{\"field\":\"type\"},\"then\":{\"effect\":\"audit\"}}}}")
                .when().put(DEFINITION + API)
                .then().statusCode(404);
    }

    @Test
    @DisplayName("PUT resource-group policy assignment returns 404 when the policy service is disabled")
    void assignmentCreateGatedOff() {
        given().contentType("application/json")
                .body("{\"properties\":{\"policyDefinitionId\":\"" + DEFINITION + "\"}}")
                .when().put(ASSIGNMENT + API)
                .then().statusCode(404);
    }

    @Test
    @DisplayName("resource groups still work when the policy service is disabled")
    void resourceGroupStillWorks() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .when().put("/subscriptions/" + SUB + "/resourceGroups/" + RG + "?api-version=2021-04-01")
                .then().statusCode(anyOf(equalTo(200), equalTo(201)));
    }
}
