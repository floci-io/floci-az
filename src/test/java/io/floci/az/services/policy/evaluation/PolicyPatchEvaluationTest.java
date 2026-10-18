package io.floci.az.services.policy.evaluation;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isOneOf;

/**
 * A PATCH is evaluated on the resource it produces, not on the fragment it carries: the stored resource
 * with the patch applied the way Resource Manager applies one. Redis caches take a PATCH of both tags and
 * properties, so they show every case; mocked mode keeps Docker out of it.
 */
@QuarkusTest
@TestProfile(PolicyPatchEvaluationTest.MockedRedis.class)
@DisplayName("Policy evaluation of a PATCH: the stored resource merged with the patch")
class PolicyPatchEvaluationTest {

    public static class MockedRedis implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.redis.mocked", "true");
        }
    }

    private static final String SUB = "test-sub-patch";
    private static final String RG = "test-rg-patch";
    private static final String SUB_SCOPE = "/subscriptions/" + SUB;
    private static final String RG_SCOPE = SUB_SCOPE + "/resourceGroups/" + RG;
    private static final String AUTH = "/providers/Microsoft.Authorization";
    private static final String DEFINITIONS = SUB_SCOPE + AUTH + "/policyDefinitions";
    private static final String ASSIGNMENTS = RG_SCOPE + AUTH + "/policyAssignments";
    private static final String POLICY_API = "?api-version=2025-03-01";
    private static final String REDIS_API = "?api-version=2024-11-01";
    private static final String CACHE = RG_SCOPE + "/providers/Microsoft.Cache/Redis/cache1";
    private static final String CACHE_BODY = "{\"location\": \"eastus\", \"properties\": {\"minimumTlsVersion\": \"1.2\", "
            + "\"sku\": {\"name\": \"Basic\", \"family\": \"C\", \"capacity\": 0}}}";

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put(RG_SCOPE + "?api-version=2021-04-01").then().statusCode(isOneOf(200, 201));
    }

    @Test
    void aPatchIsJudgedOnTheStoredResourceWithThePatchApplied() {
        definition("tls", """
                {"if": {"allOf": [
                    {"field": "type", "equals": "Microsoft.Cache/Redis"},
                    {"field": "Microsoft.Cache/Redis/minimumTlsVersion", "notEquals": "1.2"}]},
                 "then": {"effect": "deny"}}""");
        assign("tls", DEFINITIONS + "/tls");

        cache(CACHE_BODY).statusCode(isOneOf(200, 201));

        // A tag update leaves the governed property as stored: the resource still complies.
        patch("{\"tags\": {\"owner\": \"team\"}}").statusCode(200)
                .body("tags.owner", equalTo("team"))
                .body("properties.minimumTlsVersion", equalTo("1.2"));
        // A patch that sets the governed property to a forbidden value is refused, and nothing changes.
        patch("{\"properties\": {\"minimumTlsVersion\": \"1.0\"}}").statusCode(403)
                .body("error.code", equalTo("RequestDisallowedByPolicy"));
        given().when().get(CACHE + REDIS_API).then().statusCode(200)
                .body("properties.minimumTlsVersion", equalTo("1.2"))
                .body("tags.owner", equalTo("team"));
    }

    @Test
    void aTagsOnlyPatchSkipsPoliciesThatDoNotInspectTags() {
        // Created before the deny was assigned, so the stored cache already breaks it.
        cache("{\"location\": \"eastus\", \"properties\": {\"minimumTlsVersion\": \"1.0\", "
                + "\"sku\": {\"name\": \"Basic\", \"family\": \"C\", \"capacity\": 0}}}").statusCode(isOneOf(200, 201));
        definition("tls", """
                {"if": {"allOf": [
                    {"field": "type", "equals": "Microsoft.Cache/Redis"},
                    {"field": "Microsoft.Cache/Redis/minimumTlsVersion", "notEquals": "1.2"}]},
                 "then": {"effect": "deny"}}""");
        assign("tls", DEFINITIONS + "/tls");

        // A PATCH of only tags is evaluated against tag policies alone: the TLS rule does not run.
        patch("{\"tags\": {\"owner\": \"team\"}}").statusCode(200).body("tags.owner", equalTo("team"));
        // Any other change is evaluated in full, on the stored resource with the patch applied.
        patch("{\"tags\": {\"owner\": \"other\"}, \"properties\": {\"redisConfiguration\": {\"maxmemory-reserved\": \"2\"}}}")
                .statusCode(403).body("error.code", equalTo("RequestDisallowedByPolicy"));
    }

    @Test
    void aTagsOnlyPatchIsEvaluatedAgainstAParameterisedTagField() {
        given().contentType("application/json").body("""
                {"properties": {"mode": "Indexed",
                  "parameters": {"tagName": {"type": "String"}},
                  "policyRule": {"if": {"field": "[concat('tags[', parameters('tagName'), ']')]", "exists": "false"},
                                 "then": {"effect": "deny"}}}}""")
                .when().put(DEFINITIONS + "/require-tag" + POLICY_API).then().statusCode(201);
        given().contentType("application/json")
                .body("{\"properties\": {\"policyDefinitionId\": \"" + DEFINITIONS + "/require-tag\", "
                        + "\"parameters\": {\"tagName\": {\"value\": \"owner\"}}}}")
                .when().put(ASSIGNMENTS + "/require-tag" + POLICY_API).then().statusCode(201);

        cache("{\"location\": \"eastus\", \"tags\": {\"owner\": \"team\"}, \"properties\": {\"minimumTlsVersion\": \"1.2\", "
                + "\"sku\": {\"name\": \"Basic\", \"family\": \"C\", \"capacity\": 0}}}").statusCode(isOneOf(200, 201));
        patch("{\"tags\": {\"env\": \"dev\"}}").statusCode(403);
        patch("{\"tags\": {\"owner\": \"team\", \"env\": \"dev\"}}").statusCode(200).body("tags.env", equalTo("dev"));
    }

    @Test
    void aTagsOnlyPatchIsEvaluatedAgainstAValueExpressionThatNamesTheTagField() {
        // The tag field arrives through a parameter inside field(): resolved, not matched as a literal.
        given().contentType("application/json").body("""
                {"properties": {"mode": "Indexed",
                  "parameters": {"tagField": {"type": "String", "defaultValue": "tags['owner']"}},
                  "policyRule": {"if": {"value": "[empty(field(parameters('tagField')))]", "equals": true},
                                 "then": {"effect": "deny"}}}}""")
                .when().put(DEFINITIONS + "/require-owner" + POLICY_API).then().statusCode(201);
        assign("require-owner", DEFINITIONS + "/require-owner");

        cache("{\"location\": \"eastus\", \"tags\": {\"owner\": \"team\"}, \"properties\": {\"minimumTlsVersion\": \"1.2\", "
                + "\"sku\": {\"name\": \"Basic\", \"family\": \"C\", \"capacity\": 0}}}").statusCode(isOneOf(200, 201));
        patch("{\"tags\": {\"env\": \"dev\"}}").statusCode(403)
                .body("error.code", equalTo("RequestDisallowedByPolicy"));
        patch("{\"tags\": {\"owner\": \"team\", \"env\": \"dev\"}}").statusCode(200).body("tags.env", equalTo("dev"));
    }

    @Test
    void anAppendOnAPatchAmendsTheCallersPatch() {
        // The condition inspects the tag, as Azure's tag-append definitions do, so a tags-only PATCH evaluates it.
        definition("cost-center", """
                {"if": {"allOf": [
                    {"field": "type", "equals": "Microsoft.Cache/Redis"},
                    {"field": "tags['costCenter']", "exists": "false"}]},
                 "then": {"effect": "append", "details": [{"field": "tags['costCenter']", "value": "[resourceGroup().name]"}]}}""");
        assign("cost-center", DEFINITIONS + "/cost-center");

        cache(CACHE_BODY).statusCode(isOneOf(200, 201)).body("tags.costCenter", equalTo(RG));

        // The PATCH replaces the tags; the append puts the tag back into the patch the handler receives.
        patch("{\"tags\": {\"owner\": \"team\"}}").statusCode(200)
                .body("tags.owner", equalTo("team"))
                .body("tags.costCenter", equalTo(RG))
                .body("properties.minimumTlsVersion", equalTo("1.2"));
        // What the caller supplied travels as supplied, unchanged members included, with the amendment added.
        patch("{\"tags\": {\"owner\": \"other\"}, \"properties\": {\"redisConfiguration\": {\"maxmemory-policy\": \"allkeys-lru\", \"maxmemory-reserved\": \"2\"}}}")
                .statusCode(200)
                .body("tags.owner", equalTo("other"))
                .body("tags.costCenter", equalTo(RG))
                .body("properties.redisConfiguration.maxmemory-policy", equalTo("allkeys-lru"))
                .body("properties.redisConfiguration.maxmemory-reserved", equalTo("2"));
        patch("{\"properties\": {\"redisConfiguration\": {\"maxmemory-policy\": \"allkeys-lru\", \"maxmemory-reserved\": \"4\"}}}")
                .statusCode(200)
                .body("properties.redisConfiguration.maxmemory-policy", equalTo("allkeys-lru"))
                .body("properties.redisConfiguration.maxmemory-reserved", equalTo("4"));
    }

    @Test
    void anAppendOnAResourceGroupPatchAmendsItsTags() {
        given().contentType("application/json").body("""
                {"properties": {"mode": "All",
                  "policyRule": {"if": {"allOf": [
                                   {"field": "type", "equals": "Microsoft.Resources/subscriptions/resourceGroups"},
                                   {"field": "tags['costCenter']", "exists": "false"}]},
                                 "then": {"effect": "append", "details": [{"field": "tags['costCenter']", "value": "shared"}]}}}}""")
                .when().put(DEFINITIONS + "/rg-cost" + POLICY_API).then().statusCode(201);
        given().contentType("application/json")
                .body("{\"properties\": {\"policyDefinitionId\": \"" + DEFINITIONS + "/rg-cost\"}}")
                .when().put(SUB_SCOPE + AUTH + "/policyAssignments/rg-cost" + POLICY_API).then().statusCode(201);

        String group = SUB_SCOPE + "/resourceGroups/costed-rg?api-version=2021-04-01";
        given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put(group).then().statusCode(isOneOf(200, 201)).body("tags.costCenter", equalTo("shared"));
        // The stored tags are read back as an unmodifiable map; the append still lands in the PATCH.
        given().contentType("application/json").body("{\"tags\": {\"owner\": \"team\"}}")
                .when().patch(group).then().statusCode(200)
                .body("tags.owner", equalTo("team"))
                .body("tags.costCenter", equalTo("shared"));
    }

    @Test
    void resourceGroupPatchesReplaceTheirTags() {
        given().contentType("application/json").body("""
                {"properties": {"mode": "All",
                  "policyRule": {"if": {"allOf": [
                      {"field": "type", "equals": "Microsoft.Resources/subscriptions/resourceGroups"},
                      {"field": "tags['CreationDate']", "exists": "false"}]},
                   "then": {"effect": "deny"}}}}""")
                .when().put(DEFINITIONS + "/rg-tag" + POLICY_API).then().statusCode(201);
        given().contentType("application/json")
                .body("{\"properties\": {\"policyDefinitionId\": \"" + DEFINITIONS + "/rg-tag\"}}")
                .when().put(SUB_SCOPE + AUTH + "/policyAssignments/rg-tag" + POLICY_API).then().statusCode(201);

        String group = SUB_SCOPE + "/resourceGroups/dated-rg?api-version=2021-04-01";
        given().contentType("application/json").body("{\"location\": \"eastus\", \"tags\": {\"CreationDate\": \"2026-10-13\"}}")
                .when().put(group).then().statusCode(isOneOf(200, 201));
        given().contentType("application/json").body("{\"tags\": {\"owner\": \"team\"}}")
                .when().patch(group).then().statusCode(403);
        given().contentType("application/json").body("{\"tags\": {\"CreationDate\": \"2026-10-13\", \"owner\": \"team\"}}")
                .when().patch(group).then().statusCode(200).body("tags.owner", equalTo("team"));
    }

    private static void definition(String name, String rule) {
        given().contentType("application/json")
                .body("{\"properties\": {\"mode\": \"Indexed\", \"policyRule\": " + rule + "}}")
                .when().put(DEFINITIONS + "/" + name + POLICY_API).then().statusCode(201);
    }

    private static void assign(String name, String definitionId) {
        given().contentType("application/json")
                .body("{\"properties\": {\"policyDefinitionId\": \"" + definitionId + "\"}}")
                .when().put(ASSIGNMENTS + "/" + name + POLICY_API).then().statusCode(201);
    }

    private static ValidatableResponse cache(String body) {
        return given().contentType("application/json").body(body).when().put(CACHE + REDIS_API).then();
    }

    private static ValidatableResponse patch(String body) {
        return given().contentType("application/json").body(body).when().patch(CACHE + REDIS_API).then();
    }
}
