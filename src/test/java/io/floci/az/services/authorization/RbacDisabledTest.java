package io.floci.az.services.authorization;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

/** {@code floci-az.services.authorization.enabled=false} removes the role routes; policy keeps its own. */
@QuarkusTest
@TestProfile(RbacDisabledTest.DisabledProfile.class)
class RbacDisabledTest {

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.authorization.enabled", "false");
        }
    }

    private static final String SUB = "/subscriptions/rbac-off-sub";

    @Test
    void roleRoutesAreGatedOff() {
        given().queryParam("api-version", "2022-05-01-preview")
                .when().get(SUB + "/providers/Microsoft.Authorization/roleDefinitions")
                .then().statusCode(404);
    }

    @Test
    void policyStillServesItsTypes() {
        given().queryParam("api-version", "2025-03-01")
                .when().get(SUB + "/providers/Microsoft.Authorization/policyDefinitions")
                .then().statusCode(anyOf(equalTo(200), equalTo(204)));
    }
}
