package io.floci.az.services.sql;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Tests managed-provider validation without requiring Docker. */
@QuarkusTest
@TestProfile(SqlHandlerManagedTest.ManagedProfile.class)
@DisplayName("SqlHandler — managed provider validation")
class SqlHandlerManagedTest {

    public static class ManagedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "floci-az.services.sql.data-plane.provider", "managed",
                "floci-az.services.sql.mocked", "true");
        }
    }

    @Test
    @DisplayName("explicit managed provider overrides legacy mocked alias and requires EULA")
    void managedProviderRequiresEula() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{"
                + "\"administratorLogin\":\"sa\","
                + "\"administratorLoginPassword\":\"FlociAz_Strong123!\"}}")
            .when().put("/subscriptions/test-sub/resourceGroups/test-rg/providers/Microsoft.Sql"
                + "/servers/managedserver?api-version=2021-11-01")
            .then().statusCode(503)
            .body("error.code", equalTo("EulaNotAccepted"));
    }
}
