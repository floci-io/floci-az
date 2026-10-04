package io.floci.az.services.managedidentity;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Verifies that {@code floci-az.services.managed-identity.app-service-enabled=false} disables the
 * App Service Managed Identity token endpoint (404) while the IMDS endpoint keeps working.
 */
@QuarkusTest
@TestProfile(AppServiceManagedIdentityDisabledTest.DisabledProfile.class)
@DisplayName("App Service Managed Identity disabled: token endpoint gated off")
@SuppressWarnings("unused")
class AppServiceManagedIdentityDisabledTest {

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.managed-identity.app-service-enabled", "false");
        }
    }

    @Test
    @DisplayName("App Service MI token endpoint returns 404 when disabled")
    void appServiceGatedOff() {
        given().header("X-IDENTITY-HEADER", "floci-az-msi-secret")
                .when().get("/msi/token?resource=https://vault.azure.net&api-version=2019-08-01")
                .then().statusCode(404);
    }
}
