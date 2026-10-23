package io.floci.az.services.policy;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(PolicyStrictNamesTest.StrictNames.class)
@DisplayName("Policy: parity-overrides.strict-names refuses policy resource names outside the spec's rule")
class PolicyStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true");
        }
    }

    private static final String API = "?api-version=2025-03-01";
    private static final String AUTH = "/providers/Microsoft.Authorization";
    private static final String SUB_SCOPE = "/subscriptions/sub-pnames";
    private static final String RG_SCOPE = SUB_SCOPE + "/resourceGroups/rg";
    private static final String MG_SCOPE = "/providers/Microsoft.Management/managementGroups/mg-names";
    private static final String DEFINITION = """
            {"properties": {"policyRule": {"if": {"field": "location", "equals": "x"}, "then": {"effect": "audit"}}}}""";

    private static void assertRefused(String path, String body) {
        given().contentType("application/json").body(body).when().put(path + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
    }

    private static void assertAccepted(String path, String body) {
        given().contentType("application/json").body(body).when().put(path + API)
            .then().body("error.code", not(equalTo("InvalidResourceName")));
    }

    @Test
    void aDefinitionNameOutsideTheRuleIsRefusedAtEveryDefinitionScope() {
        for (String bad : new String[] {"has&amp", "has:colon", "x".repeat(65)}) {
            assertRefused(SUB_SCOPE + AUTH + "/policyDefinitions/" + bad, DEFINITION);
            assertRefused(SUB_SCOPE + AUTH + "/policySetDefinitions/" + bad, "{}");
        }
        assertAccepted(SUB_SCOPE + AUTH + "/policyDefinitions/Good_name-1.(x)", DEFINITION);
        assertAccepted(MG_SCOPE + AUTH + "/policyDefinitions/" + "y".repeat(64), DEFINITION);
    }

    @Test
    void anAssignmentOrExemptionNameOutsideTheRuleIsRefusedAtAnyScope() {
        String assignment = "{\"properties\": {\"policyDefinitionId\": \"/providers/Microsoft.Authorization/"
            + "policyDefinitions/0a914e76-4921-4c19-b460-a2d36003525a\"}}";
        assertRefused(RG_SCOPE + AUTH + "/policyAssignments/" + "a".repeat(65), assignment);
        assertRefused(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/sa1" + AUTH + "/policyAssignments/a&b",
            assignment);
        assertRefused(SUB_SCOPE + AUTH + "/policyExemptions/e&x", "{}");
        assertAccepted(RG_SCOPE + AUTH + "/policyAssignments/good-assignment", assignment);
    }
}
