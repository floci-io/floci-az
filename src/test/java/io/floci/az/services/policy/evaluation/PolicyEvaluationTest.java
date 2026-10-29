package io.floci.az.services.policy.evaluation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@DisplayName("Policy evaluation on resource writes: deny, append, audit, exemptions, scopes and modes")
class PolicyEvaluationTest {

    private static final String SUB = "test-sub-eval";
    private static final String RG = "test-rg-eval";
    private static final String SUB_SCOPE = "/subscriptions/" + SUB;
    private static final String RG_SCOPE = SUB_SCOPE + "/resourceGroups/" + RG;
    private static final String AUTH = "/providers/Microsoft.Authorization";
    private static final String DEFINITIONS = SUB_SCOPE + AUTH + "/policyDefinitions";
    private static final String SET_DEFINITIONS = SUB_SCOPE + AUTH + "/policySetDefinitions";
    private static final String ASSIGNMENTS = RG_SCOPE + AUTH + "/policyAssignments";
    private static final String EXEMPTIONS = RG_SCOPE + AUTH + "/policyExemptions";
    private static final String POLICY_API = "?api-version=2025-03-01";
    private static final String MG_SCOPE = "/providers/Microsoft.Management/managementGroups/eval-mg";

    private static final String STORAGE_API = "?api-version=2023-01-01";
    private static final String NETWORK_API = "?api-version=2023-09-01";

    private static final String TLS_RULE = """
            {"if": {"allOf": [
                {"field": "type", "equals": "Microsoft.Storage/storageAccounts"},
                {"field": "Microsoft.Storage/storageAccounts/minimumTlsVersion", "notEquals": "TLS1_2"}]},
             "then": {"effect": "%s"}}""";

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put(RG_SCOPE + "?api-version=2021-04-01").then().statusCode(admitted());
    }

    // ── Deny ────────────────────────────────────────────────────────────────────

    @Test
    void denyAnswersRequestDisallowedByPolicyInAzuresShape() {
        definition("tls", TLS_RULE.formatted("deny"), "Secure transfer");
        assign("tls-assign", DEFINITIONS + "/tls", "{}");

        storageAccount("sa1", "TLS1_0")
                .statusCode(403)
                .body("error.code", equalTo("RequestDisallowedByPolicy"))
                .body("error.target", equalTo("sa1"))
                .body("error.message", startsWith("Resource 'sa1' was disallowed by policy. Policy identifiers: '[{\"policyAssignment\":{\"name\":\"tls-assign\""))
                .body("error.message", containsString("\"policyDefinition\":{\"name\":\"Secure transfer\",\"id\":\"" + DEFINITIONS + "/tls\",\"version\":\"1.0.0\"}"))
                .body("error.additionalInfo[0].type", equalTo("PolicyViolation"))
                .body("error.additionalInfo[0].info.policyDefinitionEffect", equalTo("deny"))
                .body("error.additionalInfo[0].info.policyAssignmentId", equalTo(ASSIGNMENTS + "/tls-assign"))
                .body("error.additionalInfo[0].info.policyAssignmentScope", equalTo(RG_SCOPE))
                .body("error.additionalInfo[0].info.evaluationDetails.evaluatedExpressions", hasSize(2))
                .body("error.additionalInfo[0].info.evaluationDetails.evaluatedExpressions[1].expressionKind", equalTo("Field"))
                .body("error.additionalInfo[0].info.evaluationDetails.evaluatedExpressions[1].expressionValue", equalTo("TLS1_0"))
                .body("error.additionalInfo[0].info.evaluationDetails.evaluatedExpressions[1].operator", equalTo("NotEquals"))
                .body("error.additionalInfo[0].info.evaluationDetails.evaluatedExpressions[1].result", equalTo("True"));

        storageAccount("sa2", "TLS1_2").statusCode(admitted());
        given().when().get(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/sa1" + STORAGE_API)
                .then().statusCode(404);

        given().when().get("/_admin/policy/evaluations?effect=deny")
                .then().statusCode(200)
                .body("", hasSize(1))
                .body("[0].operationName", equalTo("Microsoft.Authorization/policies/deny/action"))
                .body("[0].level", equalTo("Error"))
                .body("[0].status", equalTo("Failed"))
                .body("[0].resourceId", equalTo(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/sa1"))
                .body("[0].resourceType", equalTo("Microsoft.Storage/storageAccounts"))
                .body("[0].policyAssignmentId", equalTo(ASSIGNMENTS + "/tls-assign"))
                .body("[0].httpMethod", equalTo("PUT"))
                .body("[0].apiVersion", equalTo("2023-01-01"));
    }

    @Test
    void nonComplianceMessageBecomesTheReason() {
        definition("tls", TLS_RULE.formatted("deny"), "Secure transfer");
        assign("tls-assign", DEFINITIONS + "/tls", "\"nonComplianceMessages\": [{\"message\": \"Storage must use TLS 1.2\"}]");

        storageAccount("sa1", "TLS1_0")
                .statusCode(403)
                .body("error.message", equalTo("Resource 'sa1' was disallowed by policy. Reasons: 'Storage must use TLS 1.2'. See error details for policy resource IDs."))
                .body("error.additionalInfo[0].info.evaluationDetails.reason", equalTo("Storage must use TLS 1.2"));
    }

    @Test
    void parametersAndParameterizedEffectResolveFromTheAssignment() {
        putJson(DEFINITIONS + "/locations", """
                {"properties": {"mode": "Indexed",
                  "parameters": {
                    "allowedLocations": {"type": "Array", "defaultValue": ["eastus"]},
                    "effect": {"type": "String", "defaultValue": "Audit", "allowedValues": ["Audit", "Deny"]}},
                  "policyRule": {"if": {"not": {"field": "location", "in": "[parameters('allowedLocations')]"}},
                                 "then": {"effect": "[parameters('effect')]"}}}}""", POLICY_API).statusCode(201);
        assign("locations", DEFINITIONS + "/locations",
                "\"parameters\": {\"allowedLocations\": {\"value\": [\"West US\"]}, \"effect\": {\"value\": \"Deny\"}}");

        storageAccount("saeast", "TLS1_2", "eastus").statusCode(403)
                // Azure flattens the assignment's {name: {value}} parameters in the violation.
                .body("error.additionalInfo[0].info.policyAssignmentParameters.allowedLocations[0]", equalTo("West US"))
                .body("error.additionalInfo[0].info.policyAssignmentParameters.effect", equalTo("Deny"))
                .body("error.additionalInfo[0].info.policyExemptionIds", hasSize(0))
                .body("error.additionalInfo[0].info.policyEnrollmentIds", hasSize(0))
                .body("error.additionalInfo[0].info.evaluationDetails.evaluatedExpressions[0].targetValue[0]", equalTo("West US"));
        storageAccount("sawest", "TLS1_2", "westus").statusCode(admitted());
    }

    @Test
    void failedEvaluationIsAnImplicitDeny() {
        putJson(DEFINITIONS + "/broken", """
                {"properties": {"mode": "All",
                  "policyRule": {"if": {"value": "[substring(field('name'), 0, 20)]", "equals": "never"},
                                 "then": {"effect": "deny"}}}}""", POLICY_API).statusCode(201);
        assign("broken", DEFINITIONS + "/broken", "{}");

        storageAccount("short", "TLS1_2").statusCode(403)
                .body("error.code", equalTo("RequestDisallowedByPolicy"));
    }

    @Test
    void failedEvaluationDeniesWhateverTheEffect() {
        putJson(DEFINITIONS + "/broken-audit", """
                {"properties": {"mode": "All",
                  "policyRule": {"if": {"value": "[substring(field('name'), 0, 20)]", "equals": "never"},
                                 "then": {"effect": "audit"}}}}""", POLICY_API).statusCode(201);
        assign("broken-audit", DEFINITIONS + "/broken-audit", "{}");

        storageAccount("short", "TLS1_2").statusCode(403)
                .body("error.code", equalTo("RequestDisallowedByPolicy"))
                .body("error.additionalInfo[0].info.policyDefinitionEffect", equalTo("deny"));

        // Under DoNotEnforce the failure is logged and the write proceeds.
        given().when().delete(ASSIGNMENTS + "/broken-audit" + POLICY_API).then().statusCode(200);
        assign("broken-relaxed", DEFINITIONS + "/broken-audit", "\"enforcementMode\": \"DoNotEnforce\"");
        storageAccount("short", "TLS1_2").statusCode(admitted());
    }

    @Test
    void unresolvableParametersDeny() {
        definition("tls", TLS_RULE.formatted("audit"), "Secure transfer");
        putJson(SET_DEFINITIONS + "/broken-set", """
                {"properties": {"policyDefinitions": [
                    {"policyDefinitionReferenceId": "tls", "policyDefinitionId": "%s",
                     "parameters": {"unused": {"value": "[parameters('missing')]"}}}]}}"""
                .formatted(DEFINITIONS + "/tls"), POLICY_API).statusCode(201);
        assign("broken-set", SET_DEFINITIONS + "/broken-set", "{}");

        storageAccount("sa1", "TLS1_2").statusCode(403)
                .body("error.code", equalTo("RequestDisallowedByPolicy"))
                .body("error.additionalInfo[0].info.policyDefinitionReferenceId", equalTo("tls"));
    }

    @Test
    void emptyBodiesAreEvaluatedAsEmptyContent() {
        putJson(DEFINITIONS + "/rg-tag", """
                {"properties": {"mode": "All",
                  "policyRule": {"if": {"allOf": [
                      {"field": "type", "equals": "Microsoft.Resources/subscriptions/resourceGroups"},
                      {"field": "tags['CreationDate']", "exists": "false"}]},
                   "then": {"effect": "deny"}}}}""", POLICY_API).statusCode(201);
        putJson(SUB_SCOPE + AUTH + "/policyAssignments/rg-tag", "{\"properties\": {\"policyDefinitionId\": \"" + DEFINITIONS + "/rg-tag\"}}", POLICY_API)
                .statusCode(201);

        given().contentType("application/json")
                .when().put(SUB_SCOPE + "/resourceGroups/bodyless-rg?api-version=2021-04-01")
                .then().statusCode(403).body("error.code", equalTo("RequestDisallowedByPolicy"));
    }

    // ── Audit and append ────────────────────────────────────────────────────────

    @Test
    void auditIsRecordedOnceTheHandlerSucceeds() {
        definition("tls", TLS_RULE.formatted("audit"), "Secure transfer");
        assign("tls-audit", DEFINITIONS + "/tls", "{}");

        storageAccount("sa1", "TLS1_0").statusCode(admitted());
        storageAccount("sa2", "TLS1_2").statusCode(admitted());

        given().when().get("/_admin/policy/evaluations")
                .then().statusCode(200)
                .body("", hasSize(1))
                .body("[0].operationName", equalTo("Microsoft.Authorization/policies/audit/action"))
                .body("[0].level", equalTo("Warning"))
                .body("[0].status", equalTo("Succeeded"))
                .body("[0].effect", equalTo("audit"))
                .body("[0].resourceId", equalTo(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/sa1"));
    }

    @Test
    void disabledMembersStayDisabledWhenParametersFail() {
        definition("tls", TLS_RULE.formatted("deny"), "Secure transfer");
        putJson(SET_DEFINITIONS + "/skipped-set", """
                {"properties": {"policyDefinitions": [
                    {"policyDefinitionReferenceId": "tls", "policyDefinitionId": "%s",
                     "parameters": {"unused": {"value": "[parameters('missing')]"}}}]}}"""
                .formatted(DEFINITIONS + "/tls"), POLICY_API).statusCode(201);
        assign("skipped-set", SET_DEFINITIONS + "/skipped-set", """
                "overrides": [{"kind": "policyEffect", "value": "disabled",
                               "selectors": [{"kind": "policyDefinitionReferenceId", "in": ["tls"]}]}]""");

        storageAccount("sa1", "TLS1_0").statusCode(admitted());
    }

    @Test
    void runtimeFailuresFollowTheEnforcementMode() {
        putJson(DEFINITIONS + "/bad-base64", """
                {"properties": {"mode": "All",
                  "policyRule": {"if": {"value": "[base64ToString('!')]", "equals": "never"},
                                 "then": {"effect": "audit"}}}}""", POLICY_API).statusCode(201);
        assign("bad-relaxed", DEFINITIONS + "/bad-base64", "\"enforcementMode\": \"DoNotEnforce\"");
        storageAccount("sa1", "TLS1_2").statusCode(admitted());

        assign("bad-enforced", DEFINITIONS + "/bad-base64", "{}");
        storageAccount("sa2", "TLS1_2").statusCode(403)
                .body("error.code", equalTo("RequestDisallowedByPolicy"));
    }

    @Test
    void malformedBodiesReachTheHandlerUnchanged() {
        putJson(DEFINITIONS + "/tag-all", """
                {"properties": {"mode": "Indexed",
                  "policyRule": {"if": {"field": "type", "equals": "Microsoft.Network/networkSecurityGroups"},
                                 "then": {"effect": "append", "details": [{"field": "tags['costCenter']", "value": "cc"}]}}}}""",
                POLICY_API).statusCode(201);
        assign("tag-all", DEFINITIONS + "/tag-all", "{}");

        nsg("broken", "{").statusCode(400);
        nsg("array", "[]").statusCode(400);
    }

    @Test
    void appendAddsMissingValuesAndDeniesConflicts() {
        putJson(DEFINITIONS + "/tag-nsg", """
                {"properties": {"mode": "Indexed",
                  "policyRule": {"if": {"field": "type", "equals": "Microsoft.Network/networkSecurityGroups"},
                                 "then": {"effect": "append", "details": [
                                    {"field": "tags['costCenter']", "value": "[resourceGroup().name]"},
                                    {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*]",
                                     "value": {"name": "appended", "properties": {"priority": 4000, "access": "Deny",
                                               "direction": "Inbound", "protocol": "*", "sourcePortRange": "*",
                                               "destinationPortRange": "*", "sourceAddressPrefix": "*",
                                               "destinationAddressPrefix": "*"}}}]}}}}""", POLICY_API).statusCode(201);
        assign("tag-nsg", DEFINITIONS + "/tag-nsg", "{}");

        nsg("nsg1", "{\"tags\": {\"owner\": \"team\"}, \"properties\": {\"securityRules\": []}}").statusCode(admitted())
                .body("tags.costCenter", equalTo(RG))
                .body("tags.owner", equalTo("team"))
                .body("properties.securityRules", hasSize(1))
                .body("properties.securityRules[0].name", equalTo("appended"));

        given().when().get("/_admin/policy/evaluations?effect=append")
                .then().statusCode(200)
                .body("", hasSize(1))
                .body("[0].operationName", equalTo("Microsoft.Authorization/policies/append/action"))
                .body("[0].level", equalTo("Informational"));

        nsg("nsg2", "{\"tags\": {\"costCenter\": \"other\"}}").statusCode(403)
                .body("error.code", equalTo("RequestDisallowedByPolicy"))
                .body("error.additionalInfo[0].info.policyDefinitionEffect", equalTo("deny"));
    }

    // ── Set definitions, overrides and identity gating ─────────────────────────

    @Test
    void setDefinitionOverridesPickTheEffectPerReferenceAndRequestContextGatesIdentities() {
        // One definition referenced twice by a set definition: the assignment overrides one reference to
        // audit and the other to deny, and the rule itself only enforces for non-application callers.
        putJson(DEFINITIONS + "/gated", """
                {"properties": {"mode": "All",
                  "policyRule": {"if": {"allOf": [
                      {"field": "type", "equals": "Microsoft.Storage/storageAccounts"},
                      {"anyOf": [
                        {"value": "[policy().definitionReferenceId]", "equals": "audit-only"},
                        {"value": "[tryGet(requestContext().identity, 'idtyp')]", "notEquals": "app"}]},
                      {"field": "Microsoft.Storage/storageAccounts/minimumTlsVersion", "notEquals": "TLS1_2"}]},
                   "then": {"effect": "disabled"}}}}""", POLICY_API).statusCode(201);
        putJson(SET_DEFINITIONS + "/gated-set", """
                {"properties": {"policyDefinitions": [
                    {"policyDefinitionReferenceId": "audit-only", "policyDefinitionId": "%1$s"},
                    {"policyDefinitionReferenceId": "enforced", "policyDefinitionId": "%1$s"}]}}"""
                .formatted(DEFINITIONS + "/gated"), POLICY_API).statusCode(201);
        assign("gated", SET_DEFINITIONS + "/gated-set", """
                "overrides": [
                  {"kind": "policyEffect", "value": "audit", "selectors": [{"kind": "policyDefinitionReferenceId", "in": ["audit-only"]}]},
                  {"kind": "policyEffect", "value": "deny", "selectors": [{"kind": "policyDefinitionReferenceId", "in": ["enforced"]}]}]""");

        // No token: not an application, so the enforced reference denies.
        storageAccount("sauser", "TLS1_0").statusCode(403)
                .body("error.additionalInfo[0].info.policyDefinitionReferenceId", equalTo("enforced"))
                .body("error.additionalInfo[0].info.policySetDefinitionId", equalTo(SET_DEFINITIONS + "/gated-set"))
                .body("error.message", containsString("\"policySetDefinition\":{\"name\":\"gated-set\""));

        // An application token: the enforced reference stands aside, the audit-only one still audits.
        String token = appToken();
        given().header("Authorization", "Bearer " + token).contentType("application/json")
                .body(storageBody("TLS1_0", "eastus"))
                .when().put(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/saapp" + STORAGE_API)
                .then().statusCode(admitted());
        given().when().get("/_admin/policy/evaluations?effect=audit")
                .then().statusCode(200)
                .body("", hasSize(1))
                .body("[0].policyDefinitionReferenceId", equalTo("audit-only"))
                .body("[0].claims.idtyp", equalTo("app"))
                .body("[0].claims.appid", equalTo("eval-app"))
                .body("[0].caller", Matchers.notNullValue());
    }

    // ── Exemptions, scopes and enforcement mode ────────────────────────────────

    @Test
    void exemptionsLiftTheAssignmentUntilTheyExpire() {
        definition("tls", TLS_RULE.formatted("deny"), "Secure transfer");
        assign("tls-assign", DEFINITIONS + "/tls", "{}");
        String future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString();
        String past = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString();

        exemption("waiver", RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/sa1", future);
        storageAccount("sa1", "TLS1_0").statusCode(admitted());
        storageAccount("sa2", "TLS1_0").statusCode(403);

        exemption("expired", RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/sa2", past);
        storageAccount("sa2", "TLS1_0").statusCode(403);
    }

    @Test
    void excludedScopesAndDoNotEnforceSkipEnforcement() {
        definition("tls", TLS_RULE.formatted("deny"), "Secure transfer");
        putJson(SUB_SCOPE + AUTH + "/policyAssignments/sub-wide", """
                {"properties": {"policyDefinitionId": "%s", "notScopes": ["%s"]}}""".formatted(DEFINITIONS + "/tls", RG_SCOPE), POLICY_API)
                .statusCode(201);
        storageAccount("saexcluded", "TLS1_0").statusCode(admitted());

        given().when().delete(SUB_SCOPE + AUTH + "/policyAssignments/sub-wide" + POLICY_API).then().statusCode(200);
        assign("not-enforced", DEFINITIONS + "/tls", "\"enforcementMode\": \"DoNotEnforce\"");
        storageAccount("satolerated", "TLS1_0").statusCode(admitted());
        given().when().get("/_admin/policy/evaluations").then().statusCode(200).body("", hasSize(0));
    }

    @Test
    void managementGroupAssignmentsApplyToEverySubscription() {
        putJson(MG_SCOPE + AUTH + "/policyDefinitions/tls", TLS_RULE_DEFINITION("deny"), POLICY_API).statusCode(201);
        putJson(MG_SCOPE + AUTH + "/policyAssignments/mg-tls", """
                {"properties": {"policyDefinitionId": "%s"}}""".formatted(MG_SCOPE + AUTH + "/policyDefinitions/tls"), POLICY_API)
                .statusCode(201);
        storageAccount("sa1", "TLS1_0").statusCode(403)
                .body("error.additionalInfo[0].info.policyAssignmentScope", equalTo(MG_SCOPE));
    }

    // ── Arrays, counts, modes and PATCH ─────────────────────────────────────────

    @Test
    void countWhereAndArrayAliasesFollowAzureSemantics() {
        putJson(DEFINITIONS + "/no-ssh", """
                {"properties": {"mode": "All",
                  "policyRule": {"if": {"allOf": [
                      {"field": "type", "equals": "Microsoft.Network/networkSecurityGroups"},
                      {"count": {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*]",
                                 "where": {"allOf": [
                                   {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*].access", "equals": "Allow"},
                                   {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*].direction", "equals": "Inbound"},
                                   {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*].destinationPortRange", "equals": "22"}]}},
                       "greater": 0}]},
                   "then": {"effect": "deny"}}}}""", POLICY_API).statusCode(201);
        putJson(DEFINITIONS + "/no-any-source", """
                {"properties": {"mode": "All",
                  "policyRule": {"if": {"allOf": [
                      {"field": "type", "equals": "Microsoft.Network/networkSecurityGroups"},
                      {"not": {"field": "Microsoft.Network/networkSecurityGroups/securityRules[*].sourceAddressPrefix", "notEquals": "*"}}]},
                   "then": {"effect": "deny"}}}}""", POLICY_API).statusCode(201);
        assign("no-ssh", DEFINITIONS + "/no-ssh", "{}");
        assign("no-any-source", DEFINITIONS + "/no-any-source", "{}");

        nsg("ssh-open", rules(rule("ssh", "Allow", "Inbound", "22", "10.0.0.0/8"))).statusCode(403)
                .body("error.additionalInfo[0].info.policyDefinitionId", equalTo(DEFINITIONS + "/no-ssh"))
                .body("error.additionalInfo[0].info.evaluationDetails.evaluatedExpressions[-1].expressionKind", equalTo("Count"))
                .body("error.additionalInfo[0].info.evaluationDetails.evaluatedExpressions[-1].expressionValue", equalTo(1));
        nsg("ssh-blocked", rules(rule("ssh", "Deny", "Inbound", "22", "10.0.0.0/8"))).statusCode(admitted());
        nsg("https-anywhere", rules(rule("https", "Allow", "Inbound", "443", "*"))).statusCode(403)
                .body("error.additionalInfo[0].info.policyDefinitionId", equalTo(DEFINITIONS + "/no-any-source"));
        nsg("empty", rules()).statusCode(admitted());
    }

    @Test
    void indexedModeSkipsResourcesWithoutTagsAndLocationAndPatchIsEvaluated() {
        putJson(DEFINITIONS + "/require-env", """
                {"properties": {"mode": "Indexed",
                  "policyRule": {"if": {"field": "tags['env']", "exists": "false"}, "then": {"effect": "deny"}}}}""", POLICY_API)
                .statusCode(201);
        assign("require-env", DEFINITIONS + "/require-env", "{}");

        given().contentType("application/json").body(storageBody("TLS1_2", "eastus"))
                .when().put(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/untagged" + STORAGE_API)
                .then().statusCode(403);
        given().contentType("application/json").body("{\"location\": \"eastus\", \"tags\": {\"env\": \"dev\"}, \"kind\": \"StorageV2\", \"sku\": {\"name\": \"Standard_LRS\"}}")
                .when().put(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/tagged" + STORAGE_API)
                .then().statusCode(admitted());
        // A nested child type takes neither tags nor a location, so Indexed mode leaves it alone.
        given().contentType("application/json").body("{\"properties\": {}}")
                .when().put(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/tagged/blobServices/default/containers/c1" + STORAGE_API)
                .then().statusCode(admitted());
        // A PATCH replaces the tags, so one that drops env is refused; one that keeps it is not.
        given().contentType("application/json").body("{\"tags\": {\"owner\": \"me\"}}")
                .when().patch(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/tagged" + STORAGE_API)
                .then().statusCode(403);
        given().contentType("application/json").body("{\"tags\": {\"env\": \"dev\", \"owner\": \"me\"}}")
                .when().patch(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/tagged" + STORAGE_API)
                .then().statusCode(not(equalTo(403)));
    }

    @Test
    void resourceGroupsAreEvaluatedInAllMode() {
        putJson(DEFINITIONS + "/rg-tag", """
                {"properties": {"mode": "All",
                  "policyRule": {"if": {"allOf": [
                      {"field": "type", "equals": "Microsoft.Resources/subscriptions/resourceGroups"},
                      {"field": "tags['CreationDate']", "exists": "false"}]},
                   "then": {"effect": "deny"}}}}""", POLICY_API).statusCode(201);
        putJson(SUB_SCOPE + AUTH + "/policyAssignments/rg-tag", """
                {"properties": {"policyDefinitionId": "%s", "nonComplianceMessages": [{"message": "CreationDate tag is required for resource groups."}]}}"""
                .formatted(DEFINITIONS + "/rg-tag"), POLICY_API).statusCode(201);

        given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put(SUB_SCOPE + "/resourceGroups/contoso-rg?api-version=2021-04-01")
                .then().statusCode(403)
                .body("error.message", equalTo("Resource 'contoso-rg' was disallowed by policy. Reasons: 'CreationDate tag is required for resource groups.'. See error details for policy resource IDs."));
        given().contentType("application/json").body("{\"location\": \"eastus\", \"tags\": {\"CreationDate\": \"2026-10-12\"}}")
                .when().put(SUB_SCOPE + "/resourceGroups/contoso-rg?api-version=2021-04-01")
                .then().statusCode(admitted());
    }

    @Test
    void policyResourcesThemselvesStillWriteWhenNothingTargetsThem() {
        definition("tls", TLS_RULE.formatted("deny"), "Secure transfer");
        assign("tls-assign", DEFINITIONS + "/tls", "{}");
        assign("another", DEFINITIONS + "/tls", "{}");
        given().when().get(ASSIGNMENTS + POLICY_API).then().statusCode(200).body("value", hasSize(2));
        given().when().get("/_admin/policy/evaluations").then().statusCode(200).body("", hasSize(0));
    }

    @Test
    void evaluationLogCanBeCleared() {
        definition("tls", TLS_RULE.formatted("deny"), "Secure transfer");
        assign("tls-assign", DEFINITIONS + "/tls", "{}");
        storageAccount("sa1", "TLS1_0").statusCode(403);
        given().when().delete("/_admin/policy/evaluations").then().statusCode(204);
        given().when().get("/_admin/policy/evaluations").then().statusCode(200).body("", hasSize(0));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static String TLS_RULE_DEFINITION(String effect) {
        return "{\"properties\": {\"mode\": \"All\", \"policyRule\": " + TLS_RULE.formatted(effect) + "}}";
    }

    private static void definition(String name, String rule, String displayName) {
        putJson(DEFINITIONS + "/" + name,
                "{\"properties\": {\"displayName\": \"" + displayName + "\", \"mode\": \"All\", \"policyRule\": " + rule + "}}", POLICY_API)
                .statusCode(201);
    }

    private static void assign(String name, String definitionId, String extraProperties) {
        String extra = extraProperties.equals("{}") ? "" : ", " + extraProperties;
        putJson(ASSIGNMENTS + "/" + name, "{\"properties\": {\"policyDefinitionId\": \"" + definitionId + "\"" + extra + "}}", POLICY_API)
                .statusCode(201);
    }

    private static void exemption(String name, String scope, String expiresOn) {
        putJson(scope + AUTH + "/policyExemptions/" + name, """
                {"properties": {"policyAssignmentId": "%s", "exemptionCategory": "Waiver", "expiresOn": "%s"}}"""
                .formatted(ASSIGNMENTS + "/tls-assign", expiresOn), "?api-version=2022-07-01-preview")
                .statusCode(201);
    }

    private static ValidatableResponse storageAccount(String name, String tls) {
        return storageAccount(name, tls, "eastus");
    }

    private static ValidatableResponse storageAccount(String name, String tls, String location) {
        return given().contentType("application/json").body(storageBody(tls, location))
                .when().put(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/" + name + STORAGE_API)
                .then();
    }

    private static String storageBody(String tls, String location) {
        return "{\"location\": \"" + location + "\", \"kind\": \"StorageV2\", \"sku\": {\"name\": \"Standard_LRS\"},"
                + " \"properties\": {\"minimumTlsVersion\": \"" + tls + "\"}}";
    }

    private static ValidatableResponse nsg(String name, String body) {
        return given().contentType("application/json").body(body)
                .when().put(RG_SCOPE + "/providers/Microsoft.Network/networkSecurityGroups/" + name + NETWORK_API)
                .then();
    }

    private static String rules(String... rules) {
        return "{\"location\": \"eastus\", \"properties\": {\"securityRules\": [" + String.join(",", rules) + "]}}";
    }

    private static String rule(String name, String access, String direction, String port, String source) {
        return """
                {"name": "%s", "properties": {"access": "%s", "direction": "%s", "destinationPortRange": "%s",
                 "sourceAddressPrefix": "%s", "protocol": "Tcp", "priority": 100, "sourcePortRange": "*",
                 "destinationAddressPrefix": "*"}}""".formatted(name, access, direction, port, source);
    }

    private static String appToken() {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", "eval-app")
                .formParam("client_secret", "secret")
                .formParam("scope", "https://management.azure.com/.default")
                .when().post("/00000000-0000-0000-0000-000000000002/oauth2/v2.0/token")
                .then().statusCode(200).extract().path("access_token");
    }

    /** A write the gate let through: 201 on create, 200 on update. */
    private static Matcher<Integer> admitted() {
        return Matchers.isOneOf(200, 201);
    }

    private static ValidatableResponse putJson(String path, String body, String api) {
        return given().contentType("application/json").body(body).when().put(path + api).then();
    }
}
