package io.floci.az.services.policy.evaluation;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@TestProfile(PolicyEnforcementOffTest.EnforcementOffProfile.class)
@DisplayName("Policy enforcement off: assignments are stored but never evaluated")
class PolicyEnforcementOffTest {

    public static class EnforcementOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.policy.enforcement", "false");
        }
    }

    private static final String SUB_SCOPE = "/subscriptions/test-sub-noenforce";
    private static final String RG_SCOPE = SUB_SCOPE + "/resourceGroups/test-rg-noenforce";
    private static final String AUTH = "/providers/Microsoft.Authorization";
    private static final String API = "?api-version=2025-03-01";

    @Test
    void denyAssignmentsDoNotBlockWrites() {
        given().when().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{\"properties\": {\"mode\": \"All\", \"policyRule\": {\"if\": {\"field\": \"type\", \"equals\": \"Microsoft.Storage/storageAccounts\"}, \"then\": {\"effect\": \"deny\"}}}}")
                .when().put(SUB_SCOPE + AUTH + "/policyDefinitions/deny-all" + API).then().statusCode(201);
        given().contentType("application/json").body("{\"properties\": {\"policyDefinitionId\": \"" + SUB_SCOPE + AUTH + "/policyDefinitions/deny-all\"}}")
                .when().put(RG_SCOPE + AUTH + "/policyAssignments/deny-all" + API).then().statusCode(201);

        given().contentType("application/json").body("{\"location\": \"eastus\", \"kind\": \"StorageV2\", \"sku\": {\"name\": \"Standard_LRS\"}}")
                .when().put(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/sa1?api-version=2023-01-01")
                .then().statusCode(200);
        given().when().get("/_admin/policy/evaluations").then().statusCode(200).body("", hasSize(0));
    }
}
