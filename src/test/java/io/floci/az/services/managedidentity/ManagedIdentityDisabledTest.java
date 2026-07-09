package io.floci.az.services.managedidentity;

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
 * Verifies that {@code floci-az.services.managed-identity.enabled=false} disables
 * Microsoft.ManagedIdentity and the IMDS token endpoint while the rest of the ARM
 * management plane keeps working.
 */
@QuarkusTest
@TestProfile(ManagedIdentityDisabledTest.DisabledProfile.class)
@DisplayName("Managed Identity disabled — ARM provider and IMDS gated off")
@SuppressWarnings("unused")
class ManagedIdentityDisabledTest {

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.managed-identity.enabled", "false");
        }
    }

    private static final String SUB  = "test-sub-msioff";
    private static final String RG   = "test-rg-msioff";
    private static final String BASE = "/subscriptions/" + SUB + "/resourceGroups/" + RG;
    private static final String API  = "?api-version=2024-11-30";

    @Test
    @DisplayName("PUT userAssignedIdentity returns 404 when managed identity disabled")
    void identityCreateGatedOff() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .when().put(BASE + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/id1" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    @DisplayName("IMDS token endpoint returns 404 when managed identity disabled")
    void imdsGatedOff() {
        given().header("Metadata", "true")
                .when().get("/metadata/identity/oauth2/token?resource=https://management.azure.com/&api-version=2018-02-01")
                .then().statusCode(404);
    }

    @Test
    @DisplayName("non-identity ARM (resource group) still works when managed identity disabled")
    void resourceGroupStillWorks() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .when().put(BASE + API)
                .then().statusCode(anyOf(equalTo(200), equalTo(201)));
    }
}
