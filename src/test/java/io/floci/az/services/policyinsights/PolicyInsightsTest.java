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
    private static final String REDIS_API = "?api-version=2024-11-01";
    private static final String NON_SSL_ONLY = "&$filter=policyDefinitionName eq 'nonssl'";

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
                .body("value[0].results.resourceDetails.find { it.complianceState == 'noncompliant' }.count", equalTo(1))
                .body("value[0].results.resourceDetails.find { it.complianceState == 'compliant' }.count", equalTo(1))
                .body("value[0].results.resourceDetails.complianceState", equalTo(java.util.List.of("noncompliant", "compliant", "exempt")))
                .body("value[0].results.policyDetails[0].complianceState", equalTo("noncompliant"))
                .body("value[0].results.policyDetails[0].count", equalTo(1))
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
    void scansReadTheFullResourceBody() {
        // A Redis cache's index entry carries no properties; the scan must read the cache itself.
        String cache = nonSslCache();
        query(cache, NON_SSL_ONLY).statusCode(200).body("'@odata.count'", equalTo(1)).body("value[0].complianceState", equalTo("NonCompliant"));

        given().when().post(RG_SCOPE + "/providers/Microsoft.PolicyInsights/policyStates/latest/triggerEvaluation" + INSIGHTS_API)
                .then().statusCode(202);
        query(cache, NON_SSL_ONLY).statusCode(200).body("'@odata.count'", equalTo(1)).body("value[0].complianceState", equalTo("NonCompliant"));
    }

    @Test
    void tagsOnlyPatchKeepsTheVerdictsItDidNotReEvaluate() {
        String cache = nonSslCache();
        query(cache, NON_SSL_ONLY).statusCode(200).body("'@odata.count'", equalTo(1)).body("value[0].complianceState", equalTo("NonCompliant"));
        // The non-SSL definition does not inspect tags, so a tags-only PATCH leaves its verdict in place.
        given().contentType("application/json").body("{\"tags\": {\"env\": \"dev\"}}")
                .when().patch(cache + REDIS_API).then().statusCode(200);
        query(cache, NON_SSL_ONLY).statusCode(200)
                .body("'@odata.count'", equalTo(1))
                .body("value[0].complianceState", equalTo("NonCompliant"));
    }

    @Test
    void aManagementGroupScopeCoversTheSubscriptionsPlacedUnderIt() {
        String groups = "/providers/Microsoft.Management/managementGroups";
        String mgApi = "?api-version=2023-04-01";
        given().contentType("application/json").body("{\"properties\": {\"displayName\": \"Production\"}}")
                .when().put(groups + "/prod" + mgApi).then().statusCode(200);
        given().contentType("application/json").body("{\"properties\": {\"displayName\": \"Sandbox\", \"details\": {\"parent\": {\"id\": \"" + groups + "/prod\"}}}}")
                .when().put(groups + "/sandbox" + mgApi).then().statusCode(200);
        given().contentType("application/json").body("{\"properties\": {\"displayName\": \"Empty\"}}")
                .when().put(groups + "/empty" + mgApi).then().statusCode(200);
        given().when().put(groups + "/sandbox/subscriptions/" + SUB + mgApi).then().statusCode(200);
        storageAccount("weaksa", "TLS1_0");

        // The subscription sits under sandbox, which sits under prod: both cover its states, the tenant root does too.
        query(groups + "/sandbox", "").statusCode(200).body("'@odata.count'", equalTo(1));
        query(groups + "/prod", "").statusCode(200).body("'@odata.count'", equalTo(1))
                .body("value[0].resourceId", equalTo(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/weaksa"));
        query(groups + "/00000000-0000-0000-0000-000000000002", "").statusCode(200).body("'@odata.count'", equalTo(1));
        query(groups + "/empty", "").statusCode(200).body("'@odata.count'", equalTo(0));
        query(groups + "/nosuchgroup", "").statusCode(200).body("'@odata.count'", equalTo(0));
        given().when().post(groups + "/prod/providers/Microsoft.PolicyInsights/policyStates/latest/summarize" + INSIGHTS_API)
                .then().statusCode(200).body("value[0].results.nonCompliantResources", equalTo(1));
    }

    @Test
    void queriesUnderAnyResourceReachTheHandler() {
        // The resource's own provider sorts ahead of Microsoft.PolicyInsights; the last provider segment decides.
        query(RG_SCOPE + "/providers/Microsoft.App/containerApps/app", "")
                .statusCode(200).body("'@odata.count'", equalTo(0));
        given().when().post(RG_SCOPE + "/providers/Microsoft.App/containerApps/app/providers/Microsoft.PolicyInsights/policyStates/latest/summarize" + INSIGHTS_API)
                .then().statusCode(200).body("value[0].results.nonCompliantResources", equalTo(0));
    }

    @Test
    void summarizeCountsEachResourceOnceByItsOverallState() {
        String kind = SUB_SCOPE + AUTH + "/policyDefinitions/kind";
        given().contentType("application/json").body("""
                {"properties": {"mode": "All", "policyRule": {"if": {"allOf": [
                      {"field": "type", "equals": "Microsoft.Storage/storageAccounts"},
                      {"field": "kind", "notEquals": "StorageV2"}]},
                    "then": {"effect": "audit"}}}}""")
                .when().put(kind + POLICY_API).then().statusCode(201);
        given().contentType("application/json").body("{\"properties\": {\"policyDefinitionId\": \"" + kind + "\"}}")
                .when().put(RG_SCOPE + AUTH + "/policyAssignments/kind-audit" + POLICY_API).then().statusCode(201);
        storageAccount("weaksa", "TLS1_0");
        storageAccount("strongsa", "TLS1_2");
        // weaksa is compliant with the kind policy and non-compliant with the TLS policy: one non-compliant resource, not one of each.
        given().when().post(SUB_SCOPE + "/providers/Microsoft.PolicyInsights/policyStates/latest/summarize" + INSIGHTS_API)
                .then().statusCode(200)
                .body("value[0].results.nonCompliantResources", equalTo(1))
                .body("value[0].results.resourceDetails.find { it.complianceState == 'noncompliant' }.count", equalTo(1))
                .body("value[0].results.resourceDetails.find { it.complianceState == 'compliant' }.count", equalTo(1))
                .body("value[0].results.resourceDetails.find { it.complianceState == 'exempt' }.count", equalTo(0))
                .body("value[0].policyAssignments", hasSize(2));
    }

    @Test
    void anAppliedAppendLeavesTheResourceCompliant() {
        String envTag = SUB_SCOPE + AUTH + "/policyDefinitions/env-tag";
        String cache = RG_SCOPE + "/providers/Microsoft.Cache/Redis/tagme";
        given().contentType("application/json").body("""
                {"properties": {"mode": "Indexed", "policyRule": {"if": {"allOf": [
                      {"field": "type", "equals": "Microsoft.Cache/Redis"},
                      {"field": "tags['env']", "exists": "false"}]},
                    "then": {"effect": "append", "details": [{"field": "tags['env']", "value": "dev"}]}}}}""")
                .when().put(envTag + POLICY_API).then().statusCode(201);
        given().contentType("application/json").body("{\"properties\": {\"policyDefinitionId\": \"" + envTag + "\"}}")
                .when().put(RG_SCOPE + AUTH + "/policyAssignments/env-tag" + POLICY_API).then().statusCode(201);
        given().contentType("application/json")
                .body("{\"location\": \"eastus\", \"properties\": {\"sku\": {\"name\": \"Basic\", \"family\": \"C\", \"capacity\": 0}}}")
                .when().put(cache + REDIS_API).then().statusCode(isOneOf(200, 201));
        given().when().get(cache + REDIS_API).then().statusCode(200).body("tags.env", equalTo("dev"));
        query(envTag, "").statusCode(200)
                .body("'@odata.count'", equalTo(1))
                .body("value[0].complianceState", equalTo("Compliant"))
                .body("value[0].policyDefinitionAction", equalTo("append"));
    }

    @Test
    void scansApplyNoAppendAndRecordItsConditionAsNonCompliant() {
        String envTag = SUB_SCOPE + AUTH + "/policyDefinitions/env-tag";
        String cache = RG_SCOPE + "/providers/Microsoft.Cache/Redis/untagged";
        // The cache exists before the append assignment, so the write never appended the tag.
        given().contentType("application/json")
                .body("{\"location\": \"eastus\", \"properties\": {\"sku\": {\"name\": \"Basic\", \"family\": \"C\", \"capacity\": 0}}}")
                .when().put(cache + REDIS_API).then().statusCode(isOneOf(200, 201));
        given().contentType("application/json").body("""
                {"properties": {"mode": "Indexed", "policyRule": {"if": {"allOf": [
                      {"field": "type", "equals": "Microsoft.Cache/Redis"},
                      {"field": "tags['env']", "exists": "false"}]},
                    "then": {"effect": "append", "details": [{"field": "tags['env']", "value": "dev"}]}}}}""")
                .when().put(envTag + POLICY_API).then().statusCode(201);
        given().contentType("application/json").body("{\"properties\": {\"policyDefinitionId\": \"" + envTag + "\"}}")
                .when().put(RG_SCOPE + AUTH + "/policyAssignments/env-tag" + POLICY_API).then().statusCode(201);

        given().when().post(RG_SCOPE + "/providers/Microsoft.PolicyInsights/policyStates/latest/triggerEvaluation" + INSIGHTS_API)
                .then().statusCode(202);
        // An evaluation cycle changes nothing and reports the missing tag, as Azure's does.
        given().when().get(cache + REDIS_API).then().statusCode(200).body("tags.env", nullValue());
        query(envTag, "").statusCode(200)
                .body("'@odata.count'", equalTo(1))
                .body("value[0].complianceState", equalTo("NonCompliant"));
    }

    @Test
    void negativeSkiptokenIsRejected() {
        storageAccount("weaksa", "TLS1_0");
        query(SUB_SCOPE, "&$skiptoken=-1").statusCode(400).body("error.code", equalTo("InvalidQueryOption"));
        query(SUB_SCOPE, "&$skiptoken=abc").statusCode(400).body("error.code", equalTo("InvalidQueryOption"));
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

    /** A Redis cache with its non-SSL port on, under an audit assignment of a definition that flags exactly that. */
    private static String nonSslCache() {
        String cache = RG_SCOPE + "/providers/Microsoft.Cache/Redis/cache1";
        String nonSsl = SUB_SCOPE + AUTH + "/policyDefinitions/nonssl";
        given().contentType("application/json").body("""
                {"properties": {"mode": "All", "policyRule": {"if": {"allOf": [
                      {"field": "type", "equals": "Microsoft.Cache/Redis"},
                      {"field": "Microsoft.Cache/Redis/enableNonSslPort", "equals": "true"}]},
                    "then": {"effect": "audit"}}}}""")
                .when().put(nonSsl + POLICY_API).then().statusCode(201);
        given().contentType("application/json").body("{\"properties\": {\"policyDefinitionId\": \"" + nonSsl + "\"}}")
                .when().put(RG_SCOPE + AUTH + "/policyAssignments/nonssl-audit" + POLICY_API).then().statusCode(201);
        given().contentType("application/json")
                .body("{\"location\": \"eastus\", \"properties\": {\"sku\": {\"name\": \"Basic\", \"family\": \"C\", \"capacity\": 0}, \"enableNonSslPort\": true}}")
                .when().put(cache + REDIS_API).then().statusCode(isOneOf(200, 201));
        return cache;
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
