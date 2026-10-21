package io.floci.az.services.policyinsights;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isOneOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@DisplayName("Policy Insights: compliance states from evaluations and scans, queries, summaries, trigger evaluation")
class PolicyInsightsTest {

    private static final String SUB = "aaaaaaaa-4444-4444-4444-000000000001";
    private static final String SUB_SCOPE = "/subscriptions/" + SUB;
    private static final String RG = "rg-insights";
    private static final String RG_SCOPE = SUB_SCOPE + "/resourceGroups/" + RG;
    private static final String AUTH = "/providers/Microsoft.Authorization";
    private static final String POLICY_API = "?api-version=2025-03-01";
    private static final String INSIGHTS_API = "?api-version=2024-10-01";
    private static final String DEFINITION = SUB_SCOPE + AUTH + "/policyDefinitions/tls";
    private static final String ASSIGNMENT = RG_SCOPE + AUTH + "/policyAssignments/tls-audit";

    @BeforeEach
    void seed() {
        given().when().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put(RG_SCOPE + "?api-version=2021-04-01").then().statusCode(isOneOf(200, 201));
        given().contentType("application/json").body("""
                {"properties": {"displayName": "Secure transfer", "mode": "All", "metadata": {"category": "Storage"},
                  "policyRule": {"if": {"allOf": [
                      {"field": "type", "equals": "Microsoft.Storage/storageAccounts"},
                      {"field": "Microsoft.Storage/storageAccounts/minimumTlsVersion", "notEquals": "TLS1_2"}]},
                    "then": {"effect": "audit"}}}}""")
                .when().put(DEFINITION + POLICY_API).then().statusCode(201);
        given().contentType("application/json").body("{\"properties\": {\"policyDefinitionId\": \"" + DEFINITION + "\"}}")
                .when().put(ASSIGNMENT + POLICY_API).then().statusCode(201);
    }

    @Test
    void writesRecordComplianceStatesQueriedPerScope() {
        storageAccount("weaksa", "TLS1_0");
        storageAccount("strongsa", "TLS1_2");

        query(SUB_SCOPE, "")
                .statusCode(200)
                .body("'@odata.count'", equalTo(2))
                .body("value.find { it.resourceId.endsWith('/weaksa') }.complianceState", equalTo("NonCompliant"))
                .body("value.find { it.resourceId.endsWith('/weaksa') }.isCompliant", equalTo(false))
                .body("value.find { it.resourceId.endsWith('/strongsa') }.complianceState", equalTo("Compliant"))
                .body("value[0].policyAssignmentId", equalTo(ASSIGNMENT))
                .body("value[0].policyAssignmentName", equalTo("tls-audit"))
                .body("value[0].policyAssignmentScope", equalTo(RG_SCOPE))
                .body("value[0].policyDefinitionId", equalTo(DEFINITION))
                .body("value[0].policyDefinitionName", equalTo("tls"))
                .body("value[0].policyDefinitionAction", equalTo("audit"))
                .body("value[0].policyDefinitionCategory", equalTo("Storage"))
                .body("value[0].resourceType", equalTo("Microsoft.Storage/storageAccounts"))
                .body("value[0].resourceLocation", equalTo("eastus"))
                .body("value[0].resourceGroup", equalTo(RG.toLowerCase()))
                .body("value[0].subscriptionId", equalTo(SUB))
                .body("value[0].timestamp", notNullValue())
                .body("value[0].'@odata.context'", containsString("$metadata#PolicyStates/$entity"));

        query(RG_SCOPE, "&$filter=complianceState eq 'NonCompliant'")
                .statusCode(200).body("'@odata.count'", equalTo(1))
                .body("value[0].resourceId", equalTo(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/weaksa"));
        query(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/strongsa", "")
                .statusCode(200).body("'@odata.count'", equalTo(1)).body("value[0].complianceState", equalTo("Compliant"));
        query(ASSIGNMENT, "&$filter=isCompliant eq false").statusCode(200).body("'@odata.count'", equalTo(1));
        query(DEFINITION, "").statusCode(200).body("'@odata.count'", equalTo(2));
        query(SUB_SCOPE, "&$select=resourceId,complianceState&$top=1")
                .statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].policyAssignmentId", nullValue())
                .body("value[0].complianceState", notNullValue())
                .body("'@odata.nextLink'", containsString("$skiptoken=1"));
        query(SUB_SCOPE, "&$filter=colour eq 'blue'").statusCode(400).body("error.code", equalTo("InvalidQueryFilter"));
    }

    @Test
    void summarizeCountsNonCompliantResourcesAndPolicies() {
        storageAccount("weaksa", "TLS1_0");
        storageAccount("strongsa", "TLS1_2");
        given().when().post(SUB_SCOPE + "/providers/Microsoft.PolicyInsights/policyStates/latest/summarize" + INSIGHTS_API)
                .then().statusCode(200)
                .body("'@odata.count'", equalTo(1))
                .body("value[0].results.nonCompliantResources", equalTo(1))
                .body("value[0].results.nonCompliantPolicies", equalTo(1))
                .body("value[0].results.resourceDetails.find { it.complianceState == 'compliant' }.count", equalTo(1))
                .body("value[0].policyAssignments", hasSize(1))
                .body("value[0].policyAssignments[0].policyAssignmentId", equalTo(ASSIGNMENT))
                .body("value[0].policyAssignments[0].policyDefinitions[0].policyDefinitionId", equalTo(DEFINITION))
                .body("value[0].policyAssignments[0].policyDefinitions[0].effect", equalTo("audit"));
    }

    @Test
    void triggerEvaluationScansExistingResourcesAndAnswersTheOperation() {
        // The resources exist before the assignment that covers them.
        given().when().delete(ASSIGNMENT + POLICY_API).then().statusCode(200);
        storageAccount("latesa", "TLS1_0");
        query(SUB_SCOPE, "").statusCode(200).body("'@odata.count'", equalTo(0));
        given().contentType("application/json").body("{\"properties\": {\"policyDefinitionId\": \"" + DEFINITION + "\"}}")
                .when().put(ASSIGNMENT + POLICY_API).then().statusCode(201);

        String location = given().when().post(SUB_SCOPE + "/providers/Microsoft.PolicyInsights/policyStates/latest/triggerEvaluation" + INSIGHTS_API)
                .then().statusCode(202)
                .header("Retry-After", notNullValue())
                .header("Location", containsString("/providers/Microsoft.PolicyInsights/asyncOperationResults/"))
                .extract().header("Location");
        given().urlEncodingEnabled(false).when().get(location.substring(location.indexOf("/subscriptions/")))
                .then().statusCode(200).body("status", equalTo("Succeeded"));
        // Mode All also evaluates the resource group itself, which the rule does not match.
        query(SUB_SCOPE, "").statusCode(200)
                .body("'@odata.count'", equalTo(2))
                .body("value.find { it.resourceId.endsWith('/latesa') }.complianceState", equalTo("NonCompliant"))
                .body("value.find { it.resourceType == 'Microsoft.Resources/subscriptions/resourceGroups' }.complianceState", equalTo("Compliant"));
        query(SUB_SCOPE, "&$filter=complianceState eq 'NonCompliant'").statusCode(200)
                .body("'@odata.count'", equalTo(1))
                .body("value[0].resourceId", equalTo(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/latesa"));

        given().when().post(RG_SCOPE + "/providers/Microsoft.PolicyInsights/policyStates/latest/triggerEvaluation" + INSIGHTS_API)
                .then().statusCode(202);
        given().when().post(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/latesa/providers/Microsoft.PolicyInsights/policyStates/latest/triggerEvaluation" + INSIGHTS_API)
                .then().statusCode(400);
    }

    @Test
    void statesFollowDeletesAndExemptions() {
        storageAccount("weaksa", "TLS1_0");
        query(SUB_SCOPE, "").statusCode(200).body("'@odata.count'", equalTo(1));

        given().contentType("application/json").body("{\"properties\": {\"policyAssignmentId\": \"" + ASSIGNMENT + "\", \"exemptionCategory\": \"Waiver\"}}")
                .when().put(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/weaksa" + AUTH + "/policyExemptions/w1?api-version=2022-07-01-preview")
                .then().statusCode(201);
        storageAccount("weaksa", "TLS1_0");
        query(SUB_SCOPE, "").statusCode(200).body("value[0].complianceState", equalTo("Exempt"));

        given().when().delete(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/weaksa?api-version=2023-01-01")
                .then().statusCode(isOneOf(200, 202, 204));
        query(SUB_SCOPE, "").statusCode(200).body("'@odata.count'", equalTo(0));

        storageAccount("othersa", "TLS1_0");
        query(SUB_SCOPE, "").statusCode(200).body("'@odata.count'", equalTo(1));
        given().when().delete(ASSIGNMENT + POLICY_API).then().statusCode(200);
        query(SUB_SCOPE, "").statusCode(200).body("'@odata.count'", equalTo(0));
    }

    @Test
    void operationsAndUnknownPaths() {
        given().when().get("/providers/Microsoft.PolicyInsights/operations" + INSIGHTS_API)
                .then().statusCode(200).body("value.name", hasItems("Microsoft.PolicyInsights/policyStates/queryResults/action"));
        given().when().get("/providers/Microsoft.PolicyInsights/policyMetadata" + INSIGHTS_API)
                .then().statusCode(200).body("value", hasSize(0));
        given().when().get("/providers/Microsoft.PolicyInsights/policyMetadata/ACF1000" + INSIGHTS_API)
                .then().statusCode(404).body("error.code", equalTo("PolicyMetadataNotFound"));
        given().when().get(SUB_SCOPE + "/providers/Microsoft.PolicyInsights/remediations" + INSIGHTS_API)
                .then().statusCode(404).body("error.code", equalTo("InvalidResourceType"));
    }

    private static ValidatableResponse query(String scope, String options) {
        return given().when().post(scope + "/providers/Microsoft.PolicyInsights/policyStates/latest/queryResults" + INSIGHTS_API + options).then();
    }

    private static void storageAccount(String name, String tls) {
        given().contentType("application/json")
                .body("{\"location\": \"eastus\", \"kind\": \"StorageV2\", \"sku\": {\"name\": \"Standard_LRS\"}, \"properties\": {\"minimumTlsVersion\": \"" + tls + "\"}}")
                .when().put(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/" + name + "?api-version=2023-01-01")
                .then().statusCode(isOneOf(200, 201));
    }
}
