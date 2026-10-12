package io.floci.az.services.resourcegraph;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isOneOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@DisplayName("Azure Resource Graph: KQL queries over the emulator's resources, containers and policy resources")
class ResourceGraphTest {

    private static final String SUB = "aaaaaaaa-1111-1111-1111-000000000001";
    private static final String SUB2 = "aaaaaaaa-1111-1111-1111-000000000002";
    private static final String SUB_SCOPE = "/subscriptions/" + SUB;
    private static final String RG = "rg-graph";
    private static final String RG_SCOPE = SUB_SCOPE + "/resourceGroups/" + RG;
    private static final String AUTH = "/providers/Microsoft.Authorization";
    private static final String POLICY_API = "?api-version=2025-03-01";
    private static final String GRAPH = "/providers/Microsoft.ResourceGraph/resources?api-version=2024-04-01";
    private static final String ASSIGNMENT = RG_SCOPE + AUTH + "/policyAssignments/assign1";

    @BeforeEach
    void seed() {
        given().when().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{\"location\": \"East US\", \"tags\": {\"team\": \"ops\"}}")
                .when().put(RG_SCOPE + "?api-version=2021-04-01").then().statusCode(isOneOf(200, 201));
        given().contentType("application/json")
                .body("{\"location\": \"eastus\", \"kind\": \"StorageV2\", \"sku\": {\"name\": \"Standard_LRS\"}, \"tags\": {\"env\": \"prod\"}}")
                .when().put(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/graphsa1?api-version=2023-01-01")
                .then().statusCode(isOneOf(200, 201));
        given().contentType("application/json")
                .body("{\"location\": \"westus\", \"properties\": {\"tenantId\": \"t\", \"sku\": {\"family\": \"A\", \"name\": \"standard\"}}}")
                .when().put(RG_SCOPE + "/providers/Microsoft.KeyVault/vaults/graphkv1?api-version=2023-07-01")
                .then().statusCode(isOneOf(200, 201));
        given().contentType("application/json")
                .body("{\"location\": \"eastus\", \"tags\": {\"env\": \"prod\"}, \"properties\": {\"securityRules\": []}}")
                .when().put(RG_SCOPE + "/providers/Microsoft.Network/networkSecurityGroups/graphnsg1?api-version=2023-09-01")
                .then().statusCode(isOneOf(200, 201));
        putJson(SUB_SCOPE + AUTH + "/policyDefinitions/def1", """
                {"properties": {"mode": "All", "policyRule": {"if": {"field": "type", "equals": "x"}, "then": {"effect": "audit"}}}}""")
                .statusCode(201);
        putJson(ASSIGNMENT, "{\"properties\": {\"policyDefinitionId\": \"" + SUB_SCOPE + AUTH + "/policyDefinitions/def1\"}}")
                .statusCode(201);
        exemption("w1", null);
        exemption("w2", "2099-01-01T00:00:00Z");
        exemption("expired", "2000-01-01T00:00:00Z");
    }

    @Test
    void listsResourcesWithNormalisedColumns() {
        query("resources | where type =~ 'Microsoft.Storage/storageAccounts' | project id, name, type, location, resourceGroup, subscriptionId, tags")
                .statusCode(200)
                .body("totalRecords", equalTo(1))
                .body("count", equalTo(1))
                .body("resultTruncated", equalTo("false"))
                .body("data[0].name", equalTo("graphsa1"))
                .body("data[0].type", equalTo("microsoft.storage/storageaccounts"))
                .body("data[0].location", equalTo("eastus"))
                .body("data[0].resourceGroup", equalTo("rg-graph"))
                .body("data[0].subscriptionId", equalTo(SUB))
                .body("facets", hasSize(0));
        query("resources | where type == 'microsoft.network/networksecuritygroups' | project name, tags, properties")
                .statusCode(200)
                .body("data[0].name", equalTo("graphnsg1"))
                .body("data[0].tags.env", equalTo("prod"))
                .body("data[0].properties.securityRules", hasSize(0));
        query("Resources | summarize n = count() by kindOfThing = type | order by n desc")
                .statusCode(200)
                .body("data.kindOfThing", hasItems("microsoft.storage/storageaccounts", "microsoft.keyvault/vaults"));
    }

    @Test
    void listsResourceContainersAsAHierarchy() {
        scopedQuery(SUB, """
                resourcecontainers
                | where type in~ ('microsoft.resources/subscriptions', 'microsoft.resources/subscriptions/resourcegroups')
                | extend parent = iff(type == 'microsoft.resources/subscriptions',
                      strcat('/providers/Microsoft.Management/managementGroups/', tostring(parse_json(properties.managementGroupAncestorsChain)[0].name)),
                      strcat('/subscriptions/', subscriptionId))
                | union (resourcecontainers | where type == 'microsoft.management/managementgroups' | extend parent = tostring(properties.details.parent.id))
                | project id, name, type, tenantId, parent
                | order by id asc
                """)
                .statusCode(200)
                .body("totalRecords", equalTo(2))
                .body("data[0].id", equalTo(SUB_SCOPE))
                .body("data[0].type", equalTo("microsoft.resources/subscriptions"))
                .body("data[0].tenantId", notNullValue())
                .body("data[1].id", equalTo(RG_SCOPE))
                .body("data[1].name", equalTo(RG))
                .body("data[1].parent", equalTo(SUB_SCOPE));
    }

    @Test
    void listsAndCountsExemptionsOfAnAssignment() {
        query("""
                policyresources
                | where type =~ "microsoft.authorization/policyexemptions" and properties.policyAssignmentId =~ "%s"
                | project id, name, expiry = todatetime(properties.expiresOn), label = tostring(properties.displayName)
                """.formatted(ASSIGNMENT.toUpperCase()))
                .statusCode(200)
                .body("totalRecords", equalTo(3))
                .body("data.name", hasItems("w1", "w2", "expired"))
                .body("data.find { it.name == 'w1' }.expiry", nullValue())
                .body("data.find { it.name == 'w2' }.expiry", equalTo("2099-01-01T00:00:00Z"))
                .body("data[0].label", equalTo("Sample waiver"));

        query("""
                policyresources
                | where type == "microsoft.authorization/policyexemptions" and properties.policyAssignmentId =~ "%s"
                | where isnull(todatetime(properties.expiresOn)) or todatetime(properties.expiresOn) > now()
                | count
                """.formatted(ASSIGNMENT))
                .statusCode(200)
                .body("data[0].Count", equalTo(2));

        query("""
                policyresources
                | where type =~ "microsoft.authorization/policyexemptions" and subscriptionId in ( "%s", "%s" )
                | extend expiry = todatetime(properties.expiresOn)
                | where isnull(expiry) or expiry > now()
                | summarize active = count() by subscriptionId
                | sort by subscriptionId asc
                """.formatted(SUB, SUB2))
                .statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].subscriptionId", equalTo(SUB))
                .body("data[0].active", equalTo(2));
    }

    @Test
    void countsPolicyEntitiesPerScope() {
        query("""
                policyresources
                | extend scope = tostring(split(tolower(id), "/providers/microsoft.authorization/")[0])
                | extend kindLabel = case(type has "policyassignments", "assignment", type has "policyexemptions", "exemption",
                                          type has "policydefinitions", "definition", "other")
                | where isnotempty(scope) and kindLabel != "other"
                | summarize n = count() by scope, kindLabel
                """)
                .statusCode(200)
                .body("data.find { it.kindLabel == 'exemption' }.n", equalTo(3))
                .body("data.find { it.kindLabel == 'exemption' }.scope", equalTo(RG_SCOPE.toLowerCase()))
                .body("data.find { it.kindLabel == 'definition' }.scope", equalTo(SUB_SCOPE.toLowerCase()));
    }

    @Test
    void pagesWithTopAndSkipToken() {
        String firstPage = given().contentType("application/json")
                .body("{\"query\": \"policyresources | project id | order by id asc\", \"options\": {\"$top\": 2, \"resultFormat\": \"objectArray\"}}")
                .when().post(GRAPH)
                .then().statusCode(200)
                .body("totalRecords", equalTo(5))
                .body("count", equalTo(2))
                .body("resultTruncated", equalTo("true"))
                .body("'$skipToken'", notNullValue())
                .extract().path("'$skipToken'");
        given().contentType("application/json")
                .body("{\"query\": \"policyresources | project id | order by id asc\", \"options\": {\"$top\": 2, \"$skipToken\": \"" + firstPage + "\"}}")
                .when().post(GRAPH)
                .then().statusCode(200)
                .body("count", equalTo(2))
                .body("resultTruncated", equalTo("true"));
        given().contentType("application/json")
                .body("{\"query\": \"policyresources | project id | order by id asc\", \"options\": {\"$top\": 2, \"$skip\": 4}}")
                .when().post(GRAPH)
                .then().statusCode(200)
                .body("count", equalTo(1))
                .body("resultTruncated", equalTo("false"))
                .body("'$skipToken'", nullValue());
        given().contentType("application/json")
                .body("{\"query\": \"policyresources | project id\", \"options\": {\"$skip\": -1}}")
                .when().post(GRAPH).then().statusCode(400).body("error.code", equalTo("InvalidQueryOption"));
        given().contentType("application/json")
                .body("{\"query\": \"policyresources | project id\", \"options\": {\"$skipToken\": \"c2tpcDotMQ\"}}")
                .when().post(GRAPH).then().statusCode(400).body("error.code", equalTo("InvalidSkipToken"));
    }

    @Test
    void tableFormatAndSubscriptionScoping() {
        given().contentType("application/json")
                .body("{\"query\": \"resources | project name, type | order by name asc\", \"options\": {\"resultFormat\": \"table\"}}")
                .when().post(GRAPH)
                .then().statusCode(200)
                .body("data.columns[0].name", equalTo("name"))
                .body("data.columns[0].type", equalTo("string"))
                .body("data.rows[0][0]", equalTo("graphkv1"))
                .body("data.rows", hasSize(3));
        // A projection with no rows still names its columns.
        given().contentType("application/json")
                .body("{\"query\": \"resources | where name == 'nothing' | project name, type\", \"options\": {\"resultFormat\": \"table\"}}")
                .when().post(GRAPH)
                .then().statusCode(200)
                .body("data.columns.name", equalTo(java.util.List.of("name", "type")))
                .body("data.rows", hasSize(0));
        // Container rows publish systemData too, as null.
        given().contentType("application/json")
                .body("{\"query\": \"resourcecontainers | project id, systemData\"}")
                .when().post(GRAPH)
                .then().statusCode(200)
                .body("data[0].systemData", nullValue());
        given().contentType("application/json")
                .body("{\"query\": \"resources | count\", \"subscriptions\": [\"" + SUB2 + "\"]}")
                .when().post(GRAPH)
                .then().statusCode(200)
                .body("data[0].Count", equalTo(0));
    }

    @Test
    void rejectsInvalidQueriesTheWayAzureDoes() {
        query("resources | where (name == ")
                .statusCode(400)
                .body("error.code", equalTo("BadRequest"))
                .body("error.details[0].code", equalTo("InvalidQuery"))
                .body("error.details[1].code", equalTo("ParserFailure"));
        query("nosuchtable | count")
                .statusCode(400)
                .body("error.details[1].code", equalTo("SemanticError"));
        given().contentType("application/json").body("{\"options\": {}}")
                .when().post(GRAPH)
                .then().statusCode(400)
                .body("error.code", equalTo("NullOrEmptyQuery"));
        given().contentType("application/json").body("{\"query\": \"resources\"}")
                .when().put(GRAPH)
                .then().statusCode(405);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static ValidatableResponse query(String kql) {
        return given().contentType("application/json").body(java.util.Map.of("query", kql)).when().post(GRAPH).then();
    }

    private static ValidatableResponse scopedQuery(String subscription, String kql) {
        return given().contentType("application/json")
                .body(java.util.Map.of("query", kql, "subscriptions", java.util.List.of(subscription)))
                .when().post(GRAPH).then();
    }

    private static ValidatableResponse putJson(String path, String body) {
        return given().contentType("application/json").body(body).when().put(path + POLICY_API).then();
    }

    private static void exemption(String name, String expiresOn) {
        String expiry = expiresOn == null ? "" : ", \"expiresOn\": \"" + expiresOn + "\"";
        given().contentType("application/json")
                .body("{\"properties\": {\"policyAssignmentId\": \"" + ASSIGNMENT + "\", \"exemptionCategory\": \"Waiver\","
                        + " \"displayName\": \"Sample waiver\"" + expiry + "}}")
                .when().put(RG_SCOPE + AUTH + "/policyExemptions/" + name + "?api-version=2022-07-01-preview")
                .then().statusCode(201);
    }
}
