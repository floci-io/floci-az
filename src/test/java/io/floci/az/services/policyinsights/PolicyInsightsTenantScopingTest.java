package io.floci.az.services.policyinsights;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isOneOf;

/**
 * Under tenant scoping a management-group scope names the caller's tenant's group: two tenants each with a
 * {@code prod} group answer their own subscriptions' states, never the other's.
 */
@QuarkusTest
@TestProfile(PolicyInsightsTenantScopingTest.TenantScoping.class)
@DisplayName("Policy Insights: a management-group scope is the caller's tenant's group")
class PolicyInsightsTenantScopingTest {

    private static final String TEAM_TENANT = "aaaaaaaa-0000-0000-0000-0000000000c8";
    private static final String TEAM_SUB = "aaaaaaaa-1111-0000-0000-0000000000c8";
    private static final String HOME_SUB = "aaaaaaaa-4444-4444-4444-0000000000c8";
    private static final String GROUPS = "/providers/Microsoft.Management/managementGroups";
    private static final String MG_API = "?api-version=2023-04-01";
    private static final String POLICY_API = "?api-version=2025-03-01";
    private static final String INSIGHTS_API = "?api-version=2024-10-01";

    public static class TenantScoping implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "floci-az.parity-overrides.tenant-scoping", "true",
                "floci-az.tenants[0].id", TEAM_TENANT,
                "floci-az.tenants[0].display-name", "Team tenant",
                "floci-az.tenants[0].default-domain", "team.example",
                "floci-az.subscriptions[0].id", TEAM_SUB,
                "floci-az.subscriptions[0].tenant-id", TEAM_TENANT);
        }
    }

    /** An unsigned token for {@code tid}: the emulator reads the claim as a hint and never verifies it. */
    private static String bearer(String tid) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + encoder.encodeToString(("{\"tid\":\"" + tid + "\"}").getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test
    void eachTenantsProdGroupAnswersItsOwnSubscriptions() {
        given().when().post("/_admin/reset").then().statusCode(204);
        nonCompliantStorage(HOME_SUB, "weaksahome", null);
        nonCompliantStorage(TEAM_SUB, "weaksateam", bearer(TEAM_TENANT));
        // Both tenants have a group called prod, each holding its own subscription.
        given().contentType("application/json").body("{}").when().put(GROUPS + "/prod" + MG_API).then().statusCode(200);
        given().when().put(GROUPS + "/prod/subscriptions/" + HOME_SUB + MG_API).then().statusCode(200);
        given().header("Authorization", bearer(TEAM_TENANT)).contentType("application/json").body("{}")
                .when().put(GROUPS + "/prod" + MG_API).then().statusCode(200);
        given().header("Authorization", bearer(TEAM_TENANT))
                .when().put(GROUPS + "/prod/subscriptions/" + TEAM_SUB + MG_API).then().statusCode(200);

        given().when().post(GROUPS + "/prod/providers/Microsoft.PolicyInsights/policyStates/latest/queryResults" + INSIGHTS_API)
                .then().statusCode(200)
                .body("'@odata.count'", equalTo(1))
                .body("value[0].subscriptionId", equalTo(HOME_SUB));
        given().header("Authorization", bearer(TEAM_TENANT))
                .when().post(GROUPS + "/prod/providers/Microsoft.PolicyInsights/policyStates/latest/queryResults" + INSIGHTS_API)
                .then().statusCode(200)
                .body("'@odata.count'", equalTo(1))
                .body("value[0].subscriptionId", equalTo(TEAM_SUB));
        given().header("Authorization", bearer(TEAM_TENANT))
                .when().post(GROUPS + "/prod/providers/Microsoft.PolicyInsights/policyStates/latest/summarize" + INSIGHTS_API)
                .then().statusCode(200)
                .body("value[0].results.nonCompliantResources", equalTo(1))
                .body("value[0].policyAssignments[0].policyAssignmentId", containsString(TEAM_SUB));
    }

    /** A TLS audit assignment at the subscription and one storage account that trips it, written by {@code authorization}'s caller. */
    private static void nonCompliantStorage(String subscription, String accountName, String authorization) {
        String sub = "/subscriptions/" + subscription;
        var spec = authorization == null ? given() : given().header("Authorization", authorization);
        spec.contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put(sub + "/resourceGroups/rg-mg?api-version=2021-04-01").then().statusCode(isOneOf(200, 201));
        spec = authorization == null ? given() : given().header("Authorization", authorization);
        spec.contentType("application/json").body("""
                {"properties": {"mode": "All", "policyRule": {"if": {"allOf": [
                      {"field": "type", "equals": "Microsoft.Storage/storageAccounts"},
                      {"field": "Microsoft.Storage/storageAccounts/minimumTlsVersion", "notEquals": "TLS1_2"}]},
                    "then": {"effect": "audit"}}}}""")
                .when().put(sub + "/providers/Microsoft.Authorization/policyDefinitions/tls" + POLICY_API).then().statusCode(201);
        spec = authorization == null ? given() : given().header("Authorization", authorization);
        spec.contentType("application/json")
                .body("{\"properties\": {\"policyDefinitionId\": \"" + sub + "/providers/Microsoft.Authorization/policyDefinitions/tls\"}}")
                .when().put(sub + "/providers/Microsoft.Authorization/policyAssignments/tls-audit" + POLICY_API).then().statusCode(201);
        spec = authorization == null ? given() : given().header("Authorization", authorization);
        spec.contentType("application/json")
                .body("{\"location\": \"eastus\", \"kind\": \"StorageV2\", \"sku\": {\"name\": \"Standard_LRS\"}, \"properties\": {\"minimumTlsVersion\": \"TLS1_0\"}}")
                .when().put(sub + "/resourceGroups/rg-mg/providers/Microsoft.Storage/storageAccounts/" + accountName + "?api-version=2023-01-01")
                .then().statusCode(isOneOf(200, 201));
    }
}
