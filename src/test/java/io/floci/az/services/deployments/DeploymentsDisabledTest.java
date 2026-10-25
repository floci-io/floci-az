package io.floci.az.services.deployments;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code floci-az.services.deployments.enabled=false} turns template deployments off: a deployment path is the
 * generic ARM {@code 404 ResourceNotFound} it was before deployments existed, and resource groups keep working.
 */
@QuarkusTest
@TestProfile(DeploymentsDisabledTest.DisabledProfile.class)
@DisplayName("Deployments disabled: the provider route is gated off")
class DeploymentsDisabledTest {

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.deployments.enabled", "false");
        }
    }

    private static final String RG = "/subscriptions/7a1d0000-0000-4000-8000-0000000000f2/resourceGroups/rg-off";

    @Test
    void aDeploymentIsNotFoundAndResourceGroupsStillWork() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(RG + "?api-version=2021-04-01").then().statusCode(201);

        given().contentType("application/json").body("{\"properties\":{\"mode\":\"Incremental\",\"template\":{}}}")
            .when().put(RG + "/providers/Microsoft.Resources/deployments/d1?api-version=2025-04-01")
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
    }
}
