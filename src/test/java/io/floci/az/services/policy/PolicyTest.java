package io.floci.az.services.policy;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@DisplayName("PolicyHandler - Microsoft.Authorization policy definitions, set definitions, assignments and exemptions")
class PolicyTest {

    private static final String SUB = "test-sub-policy";
    private static final String RG = "test-rg-policy";
    private static final String API = "?api-version=2025-03-01";
    private static final String AUTH = "/providers/Microsoft.Authorization";
    private static final String SUB_SCOPE = "/subscriptions/" + SUB;
    private static final String RG_SCOPE = SUB_SCOPE + "/resourceGroups/" + RG;
    private static final String RESOURCE_SCOPE = RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/sa1";
    private static final String MG_SCOPE = "/providers/Microsoft.Management/managementGroups/test-mg";
    private static final String DEFINITIONS = SUB_SCOPE + AUTH + "/policyDefinitions";
    private static final String SET_DEFINITIONS = SUB_SCOPE + AUTH + "/policySetDefinitions";
    private static final String BUILT_IN_GUID = "0a914e76-4921-4c19-b460-a2d36003525a";
    private static final String BUILT_IN_DEFINITION =
            "/providers/Microsoft.Authorization/policyDefinitions/" + BUILT_IN_GUID;
    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final String RULE = """
            {"if": {"field": "location", "notIn": ["eastus"]}, "then": {"effect": "deny"}}""";
    private static final String DEFINITION_BODY = """
            {"properties": {"displayName": "Allowed locations", "mode": "All", "description": "Only eastus",
                            "metadata": {"category": "General"},
                            "parameters": {"tagName": {"type": "String"}},
                            "policyRule": %s}}""".formatted(RULE);

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    // ── Policy definitions ──────────────────────────────────────────────────────

    @Test
    void definitionLifecycle() {
        String createdOn = putJson(DEFINITIONS + "/allowed-locations", DEFINITION_BODY)
                .statusCode(201)
                .body("name", equalTo("allowed-locations"))
                .body("type", equalTo("Microsoft.Authorization/policyDefinitions"))
                .body("id", equalTo(DEFINITIONS + "/allowed-locations"))
                .body("properties.policyType", equalTo("Custom"))
                .body("properties.mode", equalTo("All"))
                .body("properties.displayName", equalTo("Allowed locations"))
                .body("properties.metadata.category", equalTo("General"))
                .body("properties.metadata.createdBy", matchesPattern(UUID_PATTERN))
                .body("properties.metadata.updatedOn", nullValue())
                .body("properties.version", equalTo("1.0.0"))
                .body("properties.parameters.tagName.type", equalTo("String"))
                .body("properties.policyRule.then.effect", equalTo("deny"))
                .body("systemData.createdAt", notNullValue())
                .extract().path("properties.metadata.createdOn");

        // Definitions answer 201 on update too, keep their creation stamp and gain an update stamp.
        putJson(DEFINITIONS + "/allowed-locations", DEFINITION_BODY)
                .statusCode(201)
                .body("properties.metadata.createdOn", equalTo(createdOn))
                .body("properties.metadata.updatedOn", notNullValue());

        given().when().get(DEFINITIONS + "/allowed-locations" + API)
                .then().statusCode(200)
                .body("name", equalTo("allowed-locations"))
                .body("properties.policyRule.if.field", equalTo("location"));

        given().when().get(DEFINITIONS + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("allowed-locations"));

        given().when().delete(DEFINITIONS + "/allowed-locations" + API)
                .then().statusCode(200);

        given().when().get(DEFINITIONS + "/allowed-locations" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("PolicyDefinitionNotFound"))
                .body("error.message", equalTo("The policy definition 'allowed-locations' could not be found."));

        given().when().delete(DEFINITIONS + "/allowed-locations" + API)
                .then().statusCode(204);
    }

    @Test
    void definitionRequiresAWellFormedPolicyRule() {
        putJson(DEFINITIONS + "/no-rule", "{\"properties\": {\"displayName\": \"x\"}}")
                .statusCode(400)
                .body("error.code", equalTo("InvalidCreatePolicyDefinitionRequest"));

        putJson(DEFINITIONS + "/no-then", "{\"properties\": {\"policyRule\": {\"if\": {\"field\": \"type\"}}}}")
                .statusCode(400)
                .body("error.code", equalTo("InvalidPolicyRule"));

        given().when().get(DEFINITIONS + API)
                .then().statusCode(200)
                .body("value", hasSize(0));
    }

    @Test
    void malformedJsonBodyIsRejected() {
        given().contentType("application/json").body("{not json")
                .when().put(DEFINITIONS + "/broken" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("InvalidRequestContent"));
    }

    @Test
    void definitionListFiltersByPolicyType() {
        putJson(DEFINITIONS + "/custom", DEFINITION_BODY).statusCode(201);

        given().queryParam("api-version", "2025-03-01").queryParam("$filter", "policyType eq 'Custom'")
                .when().get(DEFINITIONS)
                .then().statusCode(200)
                .body("value", hasSize(1));

        // No built-in definitions are seeded.
        given().queryParam("api-version", "2025-03-01").queryParam("$filter", "policyType eq 'BuiltIn'")
                .when().get(DEFINITIONS)
                .then().statusCode(200)
                .body("value", hasSize(0));
    }

    @Test
    void definitionsCannotLiveInResourceGroupsOrHaveSubResources() {
        putJson(RG_SCOPE + AUTH + "/policyDefinitions/rg-scoped", DEFINITION_BODY)
                .statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));

        given().when().get(DEFINITIONS + "/x/versions" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    // ── Policy set definitions ──────────────────────────────────────────────────

    @Test
    void setDefinitionLifecycle() {
        putJson(DEFINITIONS + "/member", DEFINITION_BODY).statusCode(201);
        String definitionId = DEFINITIONS + "/member";

        String setBody = """
                {"properties": {"displayName": "Initiative",
                                "policyDefinitions": [{"policyDefinitionId": "%s",
                                                       "parameters": {"tagName": {"value": "env"}}}]}}"""
                .formatted(definitionId);

        putJson(SET_DEFINITIONS + "/initiative", setBody)
                .statusCode(201)
                .body("type", equalTo("Microsoft.Authorization/policySetDefinitions"))
                .body("properties.policyType", equalTo("Custom"))
                .body("properties.policyDefinitions", hasSize(1))
                .body("properties.policyDefinitions[0].policyDefinitionId", equalTo(definitionId))
                .body("properties.policyDefinitions[0].policyDefinitionReferenceId", notNullValue())
                .body("properties.policyDefinitions[0].definitionVersion", equalTo("1.*.*"))
                .body("properties.policyDefinitions[0].parameters.tagName.value", equalTo("env"));

        // Updates answer 200 (the set-definition spec allows both 200 and 201).
        putJson(SET_DEFINITIONS + "/initiative", setBody).statusCode(200);

        // The member definition is now pinned by the initiative.
        given().when().delete(DEFINITIONS + "/member" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("InvalidDeletePolicyDefinitionRequest"))
                .body("error.message", containsString("policy set definition"));

        given().when().get(SET_DEFINITIONS + API)
                .then().statusCode(200)
                .body("value", hasSize(1));

        given().when().delete(SET_DEFINITIONS + "/initiative" + API)
                .then().statusCode(200);
        given().when().get(SET_DEFINITIONS + "/initiative" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("PolicySetDefinitionNotFound"));

        given().when().delete(DEFINITIONS + "/member" + API)
                .then().statusCode(200);
    }

    @Test
    void setDefinitionValidatesItsReferences() {
        putJson(SET_DEFINITIONS + "/empty", "{\"properties\": {\"policyDefinitions\": []}}")
                .statusCode(400)
                .body("error.code", equalTo("InvalidCreatePolicySetDefinitionRequest"));

        putJson(SET_DEFINITIONS + "/no-id", "{\"properties\": {\"policyDefinitions\": [{\"parameters\": {}}]}}")
                .statusCode(400)
                .body("error.code", equalTo("InvalidPolicyDefinitionReference"));

        // A subscription-scoped definition must exist.
        putJson(SET_DEFINITIONS + "/dangling", """
                {"properties": {"policyDefinitions": [{"policyDefinitionId": "%s/missing"}]}}""".formatted(DEFINITIONS))
                .statusCode(404)
                .body("error.code", equalTo("PolicyDefinitionNotFound"));

        // Built-in ids are accepted without lookup: built-ins are not seeded.
        putJson(SET_DEFINITIONS + "/built-in", """
                {"properties": {"policyDefinitions": [{"policyDefinitionId": "%s"}]}}""".formatted(BUILT_IN_DEFINITION))
                .statusCode(201);
    }

    @Test
    void onlyTenantRootedAuthorizationIdsBypassReferenceLookup() {
        putJson(DEFINITIONS + "/real", DEFINITION_BODY).statusCode(201);
        String assignments = RG_SCOPE + AUTH + "/policyAssignments";

        // Relative ids, other providers and ids with extra segments are looked up, and are missing.
        for (String reference : new String[] {
                "policyDefinitions/missing",
                "/providers/Other.Provider/policyDefinitions/missing",
                "/providers/Microsoft.Authorization/policyDefinitions/missing/extra",
                "/providers/Microsoft.Authorization/policyDefinitions/"}) {
            putJson(SET_DEFINITIONS + "/dangling", """
                    {"properties": {"policyDefinitions": [{"policyDefinitionId": "%s"}]}}""".formatted(reference))
                    .statusCode(404)
                    .body("error.code", equalTo("PolicyDefinitionNotFound"));
            putJson(assignments + "/dangling", """
                    {"properties": {"policyDefinitionId": "%s"}}""".formatted(reference))
                    .statusCode(404)
                    .body("error.code", equalTo("PolicyDefinitionNotFound"));
        }

        // A built-in set definition is not a definition: a set definition cannot nest it.
        putJson(SET_DEFINITIONS + "/nested-built-in", """
                {"properties": {"policyDefinitions": [{"policyDefinitionId": "%s"}]}}"""
                .formatted("/providers/Microsoft.Authorization/policySetDefinitions/" + BUILT_IN_GUID))
                .statusCode(404)
                .body("error.code", equalTo("PolicyDefinitionNotFound"));

        // Built-in ids of the matching type are still accepted, whatever the letter case.
        putJson(assignments + "/built-in-set", """
                {"properties": {"policyDefinitionId": "%s"}}"""
                .formatted("/providers/Microsoft.Authorization/policySetDefinitions/" + BUILT_IN_GUID))
                .statusCode(201);
        putJson(assignments + "/built-in-upper", """
                {"properties": {"policyDefinitionId": "%s"}}""".formatted(BUILT_IN_DEFINITION.toUpperCase()))
                .statusCode(201);
    }

    // ── Policy assignments ──────────────────────────────────────────────────────

    @Test
    void assignmentLifecycleAtResourceGroupScope() {
        putJson(DEFINITIONS + "/assigned", DEFINITION_BODY).statusCode(201);
        String assignments = RG_SCOPE + AUTH + "/policyAssignments";
        String body = """
                {"location": "eastus",
                 "identity": {"type": "SystemAssigned"},
                 "properties": {"displayName": "Enforce locations",
                                "policyDefinitionId": "%s/assigned",
                                "parameters": {"tagName": {"value": "env"}},
                                "nonComplianceMessages": [{"message": "Only eastus is allowed"}]}}"""
                .formatted(DEFINITIONS);

        Map<String, Object> first = putJson(assignments + "/enforce", body)
                .statusCode(201)
                .body("type", equalTo("Microsoft.Authorization/policyAssignments"))
                .body("id", equalTo(assignments + "/enforce"))
                .body("location", equalTo("eastus"))
                .body("identity.type", equalTo("SystemAssigned"))
                .body("identity.principalId", matchesPattern(UUID_PATTERN))
                .body("identity.tenantId", matchesPattern(UUID_PATTERN))
                .body("properties.scope", equalTo(RG_SCOPE))
                .body("properties.policyDefinitionId", equalTo(DEFINITIONS + "/assigned"))
                .body("properties.definitionVersion", equalTo("1.*.*"))
                .body("properties.enforcementMode", equalTo("Default"))
                .body("properties.parameters.tagName.value", equalTo("env"))
                .body("properties.nonComplianceMessages[0].message", equalTo("Only eastus is allowed"))
                .body("properties.metadata.createdBy", matchesPattern(UUID_PATTERN))
                .body("properties.instanceId", matchesPattern(UUID_PATTERN))
                .extract().path("");

        // Re-PUT keeps the server-generated principal and instance ids.
        putJson(assignments + "/enforce", body)
                .statusCode(201)
                .body("identity.principalId", equalTo(identityPrincipal(first)))
                .body("properties.instanceId", equalTo(instanceId(first)));

        given().contentType("application/json")
                .body("{\"properties\": {\"resourceSelectors\": [{\"name\": \"prod\", \"selectors\": []}]}}")
                .when().patch(assignments + "/enforce" + API)
                .then().statusCode(200)
                .body("properties.resourceSelectors", hasSize(1))
                .body("properties.displayName", equalTo("Enforce locations"));

        given().when().get(assignments + "/enforce" + API)
                .then().statusCode(200)
                .body("properties.scope", equalTo(RG_SCOPE));

        // Delete returns the deleted assignment, as on Azure.
        given().when().delete(assignments + "/enforce" + API)
                .then().statusCode(200)
                .body("name", equalTo("enforce"));

        given().when().get(assignments + "/enforce" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("PolicyAssignmentNotFound"))
                .body("error.message", equalTo("The policy assignment 'enforce' is not found."));

        given().when().delete(assignments + "/enforce" + API)
                .then().statusCode(204);
    }

    @SuppressWarnings("unchecked")
    private static String identityPrincipal(Map<String, Object> resource) {
        return String.valueOf(((Map<String, Object>) resource.get("identity")).get("principalId"));
    }

    @SuppressWarnings("unchecked")
    private static String instanceId(Map<String, Object> resource) {
        return String.valueOf(((Map<String, Object>) resource.get("properties")).get("instanceId"));
    }

    @Test
    void assignmentValidatesItsDefinitionReference() {
        String assignments = SUB_SCOPE + AUTH + "/policyAssignments";

        putJson(assignments + "/no-definition", "{\"properties\": {\"displayName\": \"x\"}}")
                .statusCode(400)
                .body("error.code", equalTo("InvalidCreatePolicyAssignmentRequest"));

        putJson(assignments + "/dangling", """
                {"properties": {"policyDefinitionId": "%s/missing"}}""".formatted(DEFINITIONS))
                .statusCode(404)
                .body("error.code", equalTo("PolicyDefinitionNotFound"));

        putJson(assignments + "/dangling-set", """
                {"properties": {"policyDefinitionId": "%s/missing"}}""".formatted(SET_DEFINITIONS))
                .statusCode(404)
                .body("error.code", equalTo("PolicySetDefinitionNotFound"));

        putJson(assignments + "/built-in", """
                {"properties": {"policyDefinitionId": "%s"}}""".formatted(BUILT_IN_DEFINITION))
                .statusCode(201)
                .body("properties.scope", equalTo(SUB_SCOPE));
    }

    @Test
    void assignmentListingHonoursScopeFilters() {
        putJson(DEFINITIONS + "/listed", DEFINITION_BODY).statusCode(201);
        String reference = "{\"properties\": {\"policyDefinitionId\": \"" + DEFINITIONS + "/listed\"}}";
        putJson(SUB_SCOPE + AUTH + "/policyAssignments/at-sub", reference).statusCode(201);
        putJson(RG_SCOPE + AUTH + "/policyAssignments/at-rg", reference).statusCode(201);
        putJson(RESOURCE_SCOPE + AUTH + "/policyAssignments/at-resource", reference)
                .statusCode(201)
                .body("properties.scope", equalTo(RESOURCE_SCOPE));

        String rgAssignments = RG_SCOPE + AUTH + "/policyAssignments";
        // Default: assignments applying to the group from above, at the group, and on resources within it.
        listAt(rgAssignments, null).body("value", hasSize(3));
        listAt(rgAssignments, "atExactScope()").body("value", hasSize(1)).body("value[0].name", equalTo("at-rg"));
        listAt(rgAssignments, "atScope()").body("value", hasSize(2));
        listAt(rgAssignments, "atScopeAndBelow()").body("value", hasSize(2));

        String subAssignments = SUB_SCOPE + AUTH + "/policyAssignments";
        listAt(subAssignments, null).body("value", hasSize(3));
        listAt(subAssignments, "atExactScope()").body("value", hasSize(1)).body("value[0].name", equalTo("at-sub"));
        listAt(subAssignments, "policyDefinitionId eq '" + DEFINITIONS + "/listed'").body("value", hasSize(3));
        listAt(subAssignments, "policyDefinitionId eq '" + DEFINITIONS + "/other'").body("value", hasSize(0));

        // The resource listing form of the spec.
        listAt(RESOURCE_SCOPE + AUTH + "/policyAssignments", "atExactScope()")
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("at-resource"));
    }

    private static ValidatableResponse listAt(String collection, String filter) {
        var request = given().queryParam("api-version", "2025-03-01");
        if (filter != null) {
            request = request.queryParam("$filter", filter);
        }
        return request.when().get(collection).then().statusCode(200);
    }

    @Test
    void definitionInUseByAnAssignmentCannotBeDeleted() {
        putJson(DEFINITIONS + "/pinned", DEFINITION_BODY).statusCode(201);
        putJson(SUB_SCOPE + AUTH + "/policyAssignments/pin",
                "{\"properties\": {\"policyDefinitionId\": \"" + DEFINITIONS + "/pinned\"}}").statusCode(201);

        given().when().delete(DEFINITIONS + "/pinned" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("InvalidDeletePolicyDefinitionRequest"))
                .body("error.message", containsString("policy assignment"));

        given().when().delete(SUB_SCOPE + AUTH + "/policyAssignments/pin" + API).then().statusCode(200);
        given().when().delete(DEFINITIONS + "/pinned" + API).then().statusCode(200);
    }

    @Test
    void assignmentUserAssignedIdentityResolvesManagedIdentities() {
        String identityId = RG_SCOPE + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/policy-mi";
        String clientId = given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put(identityId + "?api-version=2024-11-30")
                .then().statusCode(201)
                .extract().path("properties.clientId");
        putJson(DEFINITIONS + "/with-uai", DEFINITION_BODY).statusCode(201);

        Map<String, Map<String, String>> userAssigned = putJson(RG_SCOPE + AUTH + "/policyAssignments/uai", """
                {"location": "eastus",
                 "identity": {"type": "UserAssigned", "userAssignedIdentities": {"%s": {}}},
                 "properties": {"policyDefinitionId": "%s/with-uai"}}""".formatted(identityId, DEFINITIONS))
                .statusCode(201)
                .body("identity.type", equalTo("UserAssigned"))
                .extract().path("identity.userAssignedIdentities");

        assertEquals(clientId, userAssigned.get(identityId).get("clientId"));
    }

    @Test
    void resourceGroupSegmentIsCaseInsensitive() {
        putJson(DEFINITIONS + "/cased", DEFINITION_BODY).statusCode(201);
        String lowerCased = SUB_SCOPE + "/resourcegroups/" + RG + AUTH + "/policyAssignments/cased";
        putJson(lowerCased, "{\"properties\": {\"policyDefinitionId\": \"" + DEFINITIONS + "/cased\"}}")
                .statusCode(201)
                .body("properties.scope", equalTo(SUB_SCOPE + "/resourcegroups/" + RG));

        given().when().get(RG_SCOPE + AUTH + "/policyAssignments/cased" + API)
                .then().statusCode(200)
                .body("name", equalTo("cased"));
    }

    // ── Policy exemptions ───────────────────────────────────────────────────────

    @Test
    void exemptionLifecycle() {
        putJson(DEFINITIONS + "/exempted", DEFINITION_BODY).statusCode(201);
        String assignmentId = RG_SCOPE + AUTH + "/policyAssignments/to-exempt";
        putJson(assignmentId, "{\"properties\": {\"policyDefinitionId\": \"" + DEFINITIONS + "/exempted\"}}")
                .statusCode(201);
        String exemptions = RESOURCE_SCOPE + AUTH + "/policyExemptions";
        String body = """
                {"properties": {"policyAssignmentId": "%s", "exemptionCategory": "Waiver",
                                "displayName": "Legacy account", "expiresOn": "2999-01-01T00:00:00Z",
                                "metadata": {"ticket": "OPS-1"}}}""".formatted(assignmentId);

        putJson(exemptions + "/legacy", body)
                .statusCode(201)
                .body("type", equalTo("Microsoft.Authorization/policyExemptions"))
                .body("id", equalTo(exemptions + "/legacy"))
                .body("properties.policyAssignmentId", equalTo(assignmentId))
                .body("properties.exemptionCategory", equalTo("Waiver"))
                .body("properties.assignmentScopeValidation", equalTo("Default"))
                .body("properties.metadata.ticket", equalTo("OPS-1"))
                .body("systemData.createdAt", notNullValue());

        putJson(exemptions + "/legacy", body).statusCode(200);

        given().contentType("application/json")
                .body("{\"properties\": {\"assignmentScopeValidation\": \"DoNotValidate\"}}")
                .when().patch(exemptions + "/legacy" + API)
                .then().statusCode(200)
                .body("properties.assignmentScopeValidation", equalTo("DoNotValidate"))
                .body("properties.exemptionCategory", equalTo("Waiver"));

        // An expired exemption is dropped by excludeExpired() but listed by default.
        putJson(exemptions + "/expired", body.replace("2999-01-01T00:00:00Z", "2000-01-01T00:00:00Z"))
                .statusCode(201);
        listAt(RG_SCOPE + AUTH + "/policyExemptions", null).body("value", hasSize(2));
        listAt(RG_SCOPE + AUTH + "/policyExemptions", "excludeExpired()").body("value", hasSize(1));
        listAt(RG_SCOPE + AUTH + "/policyExemptions", "policyAssignmentId eq '" + assignmentId + "'")
                .body("value", hasSize(2));

        // Delete returns the deleted exemption, as on Azure.
        given().when().delete(exemptions + "/expired" + API)
                .then().statusCode(200)
                .body("name", equalTo("expired"));
        given().when().delete(exemptions + "/expired" + API)
                .then().statusCode(204);

        // Exemptions are bound to their assignment and disappear with it.
        given().when().delete(assignmentId + API).then().statusCode(200);
        given().when().get(exemptions + "/legacy" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("PolicyExemptionNotFound"))
                .body("error.message", equalTo("The policy exemption 'legacy' could not be found."));
    }

    @Test
    void exemptionValidatesCategoryAndAssignment() {
        String exemptions = RG_SCOPE + AUTH + "/policyExemptions";

        putJson(exemptions + "/no-assignment", "{\"properties\": {\"exemptionCategory\": \"Waiver\"}}")
                .statusCode(400)
                .body("error.code", equalTo("InvalidCreatePolicyExemptionRequest"));

        putJson(exemptions + "/bad-category", """
                {"properties": {"policyAssignmentId": "%s/policyAssignments/a", "exemptionCategory": "Forever"}}"""
                .formatted(RG_SCOPE + AUTH))
                .statusCode(400)
                .body("error.code", equalTo("InvalidCreatePolicyExemptionRequest"));

        putJson(exemptions + "/dangling", """
                {"properties": {"policyAssignmentId": "%s/policyAssignments/missing", "exemptionCategory": "Waiver"}}"""
                .formatted(RG_SCOPE + AUTH))
                .statusCode(404)
                .body("error.code", equalTo("PolicyAssignmentNotFound"));
    }

    // ── Management group and tenant scopes ──────────────────────────────────────

    @Test
    void managementGroupScopedDefinitionsAndAssignments() {
        String mgDefinitions = MG_SCOPE + AUTH + "/policyDefinitions";
        putJson(mgDefinitions + "/mg-def", DEFINITION_BODY)
                .statusCode(201)
                .body("id", equalTo(mgDefinitions + "/mg-def"));

        given().when().get(mgDefinitions + API)
                .then().statusCode(200)
                .body("value", hasSize(1));
        // Management-group definitions are not listed at the subscription.
        given().when().get(DEFINITIONS + API)
                .then().statusCode(200)
                .body("value", hasSize(0));

        putJson(MG_SCOPE + AUTH + "/policyAssignments/mg-assign",
                "{\"properties\": {\"policyDefinitionId\": \"" + mgDefinitions + "/mg-def\"}}")
                .statusCode(201)
                .body("properties.scope", equalTo(MG_SCOPE));

        // A subscription may assign a definition that lives in a management group.
        putJson(SUB_SCOPE + AUTH + "/policyAssignments/from-mg",
                "{\"properties\": {\"policyDefinitionId\": \"" + mgDefinitions + "/mg-def\"}}")
                .statusCode(201);

        listAt(MG_SCOPE + AUTH + "/policyAssignments", "atExactScope()")
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("mg-assign"));
    }

    @Test
    void tenantRootServesEmptyBuiltInsAndRejectsWrites() {
        given().when().get("/providers/Microsoft.Authorization/policyDefinitions" + API)
                .then().statusCode(200)
                .body("value", hasSize(0));
        given().when().get("/providers/Microsoft.Authorization/policySetDefinitions" + API)
                .then().statusCode(200)
                .body("value", hasSize(0));

        given().when().get(BUILT_IN_DEFINITION + API)
                .then().statusCode(404)
                .body("error.code", equalTo("PolicyDefinitionNotFound"));
        given().when().get("/providers/Microsoft.Authorization/policySetDefinitions/missing" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("PolicySetDefinitionNotFound"));

        putJson(BUILT_IN_DEFINITION, DEFINITION_BODY)
                .statusCode(400)
                .body("error.code", equalTo("MissingSubscription"));
        putJson("/providers/Microsoft.Authorization/policyAssignments/tenant-wide",
                "{\"properties\": {\"policyDefinitionId\": \"" + BUILT_IN_DEFINITION + "\"}}")
                .statusCode(400)
                .body("error.code", equalTo("MissingSubscription"));
    }

    // ── Audit fields ────────────────────────────────────────────────────────────

    @Test
    void auditFieldsNameTheCallerFromTheBearerToken() {
        // Without a token the emulator's fixed author is stamped, as before.
        putJson(DEFINITIONS + "/anonymous", DEFINITION_BODY)
                .statusCode(201)
                .body("systemData.createdBy", equalTo(PolicyHandler.AUTHOR))
                .body("systemData.createdByType", equalTo("Application"))
                .body("properties.metadata.createdBy", equalTo(PolicyHandler.AUTHOR));

        // An application token: createdBy is the application's client id, the type is Application.
        String appToken = token("client_credentials", "eval-app");
        String appId = claim(appToken, "azp");
        String appOid = claim(appToken, "oid");
        given().header("Authorization", "Bearer " + appToken).contentType("application/json").body(DEFINITION_BODY)
                .when().put(DEFINITIONS + "/by-app" + API)
                .then().statusCode(201)
                .body("systemData.createdBy", equalTo(appId))
                .body("systemData.createdByType", equalTo("Application"))
                .body("systemData.lastModifiedBy", equalTo(appId))
                .body("properties.metadata.createdBy", equalTo(appOid));

        // A user token updating it: createdBy stays, lastModifiedBy and updatedBy move to the user's principal name.
        String userToken = token("password", "eval-app");
        String userName = claim(userToken, "preferred_username");
        org.junit.jupiter.api.Assertions.assertEquals("user@floci.local", userName);
        given().header("Authorization", "Bearer " + userToken).contentType("application/json").body(DEFINITION_BODY)
                .when().put(DEFINITIONS + "/by-app" + API)
                .then().statusCode(201)
                .body("systemData.createdBy", equalTo(appId))
                .body("systemData.createdByType", equalTo("Application"))
                .body("systemData.lastModifiedBy", equalTo(userName))
                .body("systemData.lastModifiedByType", equalTo("User"))
                .body("properties.metadata.createdBy", equalTo(appOid))
                .body("properties.metadata.updatedBy", equalTo(userName));

        // Assignments carry the same stamps.
        given().header("Authorization", "Bearer " + appToken).contentType("application/json")
                .body("{\"properties\": {\"policyDefinitionId\": \"" + DEFINITIONS + "/by-app\"}}")
                .when().put(RG_SCOPE + AUTH + "/policyAssignments/by-app" + API)
                .then().statusCode(201)
                .body("systemData.createdBy", equalTo(appId))
                .body("systemData.lastModifiedByType", equalTo("Application"));

        // A managed identity token: the identity's client id, the type is ManagedIdentity.
        String miBody = given().header("Metadata", "true")
                .when().get("/metadata/identity/oauth2/token?resource=https://management.azure.com/&api-version=2018-02-01")
                .then().statusCode(200).extract().asString();
        String miToken = io.restassured.path.json.JsonPath.from(miBody).getString("access_token");
        String miClientId = io.restassured.path.json.JsonPath.from(miBody).getString("client_id");
        given().header("Authorization", "Bearer " + miToken).contentType("application/json").body(DEFINITION_BODY)
                .when().put(DEFINITIONS + "/by-identity" + API)
                .then().statusCode(201)
                .body("systemData.createdBy", equalTo(miClientId))
                .body("systemData.createdByType", equalTo("ManagedIdentity"));

        // A PATCH by another caller moves lastModifiedBy and updatedBy to that caller.
        given().header("Authorization", "Bearer " + userToken).contentType("application/json")
                .body("{\"properties\": {\"overrides\": []}}")
                .when().patch(RG_SCOPE + AUTH + "/policyAssignments/by-app" + API)
                .then().statusCode(200)
                .body("systemData.createdBy", equalTo(appId))
                .body("systemData.lastModifiedBy", equalTo(userName))
                .body("properties.metadata.createdBy", equalTo(appOid))
                .body("properties.metadata.updatedBy", equalTo(userName));
    }

    private static String token(String grant, String clientId) {
        var request = given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", grant)
                .formParam("client_id", clientId)
                .formParam("client_secret", "secret")
                .formParam("scope", "https://management.azure.com/.default");
        if (grant.equals("password")) {
            request = request.formParam("username", "user@floci.local").formParam("password", "pw");
        }
        return request.when().post("/00000000-0000-0000-0000-000000000002/oauth2/v2.0/token")
                .then().statusCode(200).extract().path("access_token");
    }

    private static String claim(String token, String name) {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(payload);
        if (!m.find()) {
            throw new AssertionError("claim " + name + " missing in " + payload);
        }
        return m.group(1);
    }

    // ── Routing boundaries ──────────────────────────────────────────────────────

    @Test
    void otherMicrosoftAuthorizationResourcesAreNotClaimed() {
        // Role assignments stay with the generic ARM handler, which does not implement them.
        given().contentType("application/json").body("{\"properties\": {}}")
                .when().put(SUB_SCOPE + AUTH + "/roleAssignments/11111111-1111-1111-1111-111111111111"
                        + "?api-version=2022-04-01")
                .then().statusCode(404);

        // A policy assignment scoped to another Microsoft.Authorization resource is still ours.
        putJson(DEFINITIONS + "/nested", DEFINITION_BODY).statusCode(201);
        putJson(RG_SCOPE + AUTH + "/roleAssignments/22222222-2222-2222-2222-222222222222"
                        + AUTH + "/policyAssignments/on-role",
                "{\"properties\": {\"policyDefinitionId\": \"" + DEFINITIONS + "/nested\"}}")
                .statusCode(201)
                .body("properties.scope", startsWith(RG_SCOPE + AUTH + "/roleAssignments/"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static ValidatableResponse putJson(String path, String body) {
        return given().contentType("application/json").body(body)
                .when().put(path + API)
                .then();
    }
}
