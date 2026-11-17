package io.floci.az.services.search;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The {@code Microsoft.Search} management plane. The expected bodies are the ones Azure answered in the az CLI's
 * {@code search} test recordings, so these pin property names, casing and defaults, not just status codes.
 */
@QuarkusTest
@DisplayName("Microsoft.Search: search services and their keys")
class SearchArmServiceTest {

    private static final String SUB = "5ea4c400-0000-4000-8000-000000000001";
    private static final String API = "?api-version=2025-05-01";
    private static final String KEY_PATTERN = "[A-Za-z0-9]{52}";
    /** The body {@code az search service create} sends. */
    private static final String CLI_BODY = """
            {"location": "westus",
             "properties": {"hostingMode": "default", "partitionCount": 1, "publicNetworkAccess": "enabled",
                            "replicaCount": 1},
             "sku": {"name": "standard"}}""";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String group(String rg) {
        return "/subscriptions/" + SUB + "/resourceGroups/" + rg + "/providers/Microsoft.Search/searchServices";
    }

    private static String service(String rg, String name) {
        return group(rg) + "/" + name;
    }

    private static Response put(String rg, String name, String body) {
        return given().contentType("application/json").body(body).when().put(service(rg, name) + API);
    }

    private static Response post(String path) {
        return given().when().post(path + API);
    }

    @Test
    void creatingAServiceAnswersTheProvisionedResourceAzureDoes() {
        put("rg", "searchone", CLI_BODY).then().statusCode(201)
            .body("id", equalTo(service("rg", "searchone")))
            .body("name", equalTo("searchone"))
            .body("type", equalTo("Microsoft.Search/searchServices"))
            .body("location", equalTo("West US"))
            .body("tags", anEmptyMap())
            .body("sku.name", equalTo("standard"))
            .body("$", not(hasKey("identity")))
            .body("systemData.createdAt", notNullValue())
            .body("properties.replicaCount", equalTo(1))
            .body("properties.partitionCount", equalTo(1))
            .body("properties.endpoint", equalTo("https://searchone.search.windows.net"))
            .body("properties.status", equalTo("running"))
            .body("properties.statusDetails", equalTo(""))
            .body("properties.provisioningState", equalTo("succeeded"))
            .body("properties.hostingMode", equalTo("default"))
            .body("properties.computeType", equalTo("Default"))
            .body("properties.publicNetworkAccess", equalTo("Enabled"))
            .body("properties.networkRuleSet.ipRules", empty())
            .body("properties.networkRuleSet.bypass", equalTo("None"))
            .body("properties.privateEndpointConnections", empty())
            .body("properties.sharedPrivateLinkResources", empty())
            .body("properties.encryptionWithCmk.enforcement", equalTo("Unspecified"))
            .body("properties.encryptionWithCmk.encryptionComplianceStatus", equalTo("Compliant"))
            .body("properties.disableLocalAuth", equalTo(false))
            .body("properties.authOptions.apiKeyOnly", anEmptyMap())
            .body("properties.dataExfiltrationProtections", empty())
            .body("properties.semanticSearch", equalTo("free"))
            .body("properties.upgradeAvailable", equalTo("notAvailable"))
            .body("properties", hasKey("serviceUpgradedAt"))
            .body("properties.serviceUpgradedAt", nullValue());
    }

    @Test
    void theKeysNeverAppearOnTheResource() {
        put("rg", "nokeys", CLI_BODY).then().statusCode(201)
            .body("$", not(hasKey("primaryKey")))
            .body("$", not(hasKey("queryKeys")))
            .body("properties", not(hasKey("primaryKey")));
        given().when().get(service("rg", "nokeys") + API).then().statusCode(200)
            .body("$", not(hasKey("primaryKey")))
            .body("$", not(hasKey("secondaryKey")))
            .body("$", not(hasKey("queryKeys")));
        given().when().get(group("rg") + API).then().statusCode(200)
            .body("value[0]", not(hasKey("primaryKey")));
    }

    @Test
    void aSecondPutReplacesThePropertiesAndKeepsTheKeys() {
        put("rg", "replace", """
                {"location": "westus", "tags": {"env": "dev"}, "sku": {"name": "standard"},
                 "properties": {"replicaCount": 3, "semanticSearch": "standard"}}""").then().statusCode(201);
        String primary = post(service("rg", "replace") + "/listAdminKeys").then().extract().path("primaryKey");
        String created = given().when().get(service("rg", "replace") + API).then().extract().path("systemData.createdAt");

        // The az CLI's update: the whole resource back, with the display-name location it read.
        put("rg", "replace", """
                {"location": "West US", "tags": {}, "sku": {"name": "standard"},
                 "properties": {"replicaCount": 1, "partitionCount": 2, "hostingMode": "default"}}""")
            .then().statusCode(200)
            .body("location", equalTo("West US"))
            .body("tags", anEmptyMap())
            .body("properties.replicaCount", equalTo(1))
            .body("properties.partitionCount", equalTo(2))
            .body("properties.semanticSearch", equalTo("free"))
            .body("systemData.createdAt", equalTo(created));

        post(service("rg", "replace") + "/listAdminKeys").then().statusCode(200)
            .body("primaryKey", equalTo(primary));
    }

    @Test
    void aPatchChangesWhatItSendsAndKeepsTheRest() {
        put("rg", "patched", """
                {"location": "westus", "tags": {"env": "dev"}, "sku": {"name": "basic"},
                 "properties": {"replicaCount": 2, "hostingMode": "default"}}""").then().statusCode(201);

        given().contentType("application/json")
            .body("{\"properties\": {\"partitionCount\": 3}, \"tags\": {\"env\": \"prod\", \"new\": \"tag\"}}")
            .when().patch(service("rg", "patched") + API)
            .then().statusCode(200)
            .body("tags.env", equalTo("prod"))
            .body("tags.new", equalTo("tag"))
            .body("sku.name", equalTo("basic"))
            .body("location", equalTo("West US"))
            .body("properties.replicaCount", equalTo(2))
            .body("properties.partitionCount", equalTo(3))
            .body("properties.hostingMode", equalTo("default"));

        given().contentType("application/json").body("{\"sku\": {\"name\": \"standard\"}}")
            .when().patch(service("rg", "patched") + API)
            .then().statusCode(200)
            .body("sku.name", equalTo("standard"))
            .body("tags.env", equalTo("prod"))
            .body("properties.partitionCount", equalTo(3));

        given().contentType("application/json").body("{\"tags\": {}}")
            .when().patch(service("rg", "absent") + API)
            .then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void disablingLocalAuthAnswersNoAuthOptions() {
        put("rg", "noauth", """
                {"location": "westus", "sku": {"name": "basic"}, "properties": {"disableLocalAuth": true}}""")
            .then().statusCode(201)
            .body("properties.disableLocalAuth", equalTo(true))
            .body("properties", hasKey("authOptions"))
            .body("properties.authOptions", nullValue());

        put("rg", "noauth", """
                {"location": "westus", "sku": {"name": "basic"}, "properties": {"disableLocalAuth": false,
                 "authOptions": {"aadOrApiKey": {"aadAuthFailureMode": "http401WithBearerChallenge"}}}}""")
            .then().statusCode(200)
            .body("properties.disableLocalAuth", equalTo(false))
            .body("properties.authOptions.aadOrApiKey.aadAuthFailureMode", equalTo("http401WithBearerChallenge"));

        // Disabling local auth on a service that has auth options replaces them; sending both is refused.
        given().contentType("application/json").body("{\"properties\": {\"disableLocalAuth\": true}}")
            .when().patch(service("rg", "noauth") + API)
            .then().statusCode(200).body("properties.authOptions", nullValue());
        put("rg", "noauth", """
                {"location": "westus", "sku": {"name": "basic"}, "properties": {"disableLocalAuth": true,
                 "authOptions": {"apiKeyOnly": {}}}}""")
            .then().statusCode(400)
            .body("error.code", equalTo("InvalidRequestContent"))
            .body("error.message", containsString("authOptions"));
    }

    @Test
    void aSystemAssignedIdentityGetsAPrincipalThatSurvivesUpdates() {
        String body = """
                {"location": "westus", "sku": {"name": "basic"}, "identity": {"type": "SystemAssigned"}}""";
        String principal = put("rg", "withmsi", body).then().statusCode(201)
            .body("identity.type", equalTo("SystemAssigned"))
            .body("identity.tenantId", notNullValue())
            .extract().path("identity.principalId");
        put("rg", "withmsi", body).then().statusCode(200).body("identity.principalId", equalTo(principal));

        given().contentType("application/json").body("{\"identity\": {\"type\": \"None\"}}")
            .when().patch(service("rg", "withmsi") + API)
            .then().statusCode(200).body("$", not(hasKey("identity")));
    }

    @Test
    void aUserAssignedIdentityReadsBackTheIdsOfTheManagedIdentity() {
        String uai = "/subscriptions/" + SUB + "/resourceGroups/rg/providers/Microsoft.ManagedIdentity"
            + "/userAssignedIdentities/search-uai";
        Response created = given().contentType("application/json").body("{\"location\": \"westus\"}")
            .when().put(uai + "?api-version=2023-01-31");
        created.then().statusCode(201);
        String principalId = created.path("properties.principalId");
        String clientId = created.path("properties.clientId");

        // The ids a caller sends are read-only, so they are not echoed.
        put("rg", "withuai", """
                {"location": "westus", "sku": {"name": "basic"},
                 "identity": {"type": "SystemAssigned, UserAssigned", "principalId": "caller-chosen",
                              "userAssignedIdentities": {"%s": {"principalId": "caller-chosen"}}}}""".formatted(uai))
            .then().statusCode(201)
            .body("identity.type", equalTo("SystemAssigned, UserAssigned"))
            .body("identity.principalId", not(equalTo("caller-chosen")))
            .body("identity.userAssignedIdentities.'" + uai + "'.principalId", equalTo(principalId))
            .body("identity.userAssignedIdentities.'" + uai + "'.clientId", equalTo(clientId));
        given().when().get(service("rg", "withuai") + API).then().statusCode(200)
            .body("identity.userAssignedIdentities.'" + uai + "'.clientId", equalTo(clientId));

        // A null entry detaches that identity; the type is answered in the spec's spelling.
        given().contentType("application/json").body("""
                {"identity": {"type": "systemassigned,userassigned", "userAssignedIdentities": {"%s": null}}}"""
                .formatted(uai))
            .when().patch(service("rg", "withuai") + API)
            .then().statusCode(200)
            .body("identity.type", equalTo("SystemAssigned, UserAssigned"))
            .body("identity.userAssignedIdentities", anEmptyMap());

        // An identity the Managed Identity store does not hold is refused by the ARM gate.
        put("rg", "unknownuai", """
                {"location": "westus", "sku": {"name": "basic"},
                 "identity": {"type": "UserAssigned", "userAssignedIdentities": {"%s": {}}}}"""
                .formatted(uai.replace("search-uai", "not-created")))
            .then().statusCode(400).body("error.code", equalTo("FailedIdentityOperation"));
    }

    @Test
    void fieldsSentWithTheWrongJsonTypeAreRefusedRatherThanDefaulted() {
        put("rg", "shapes", CLI_BODY).then().statusCode(201);
        String service = service("rg", "shapes");

        for (String patch : List.of(
                "{\"properties\": \"standard\"}",
                "{\"sku\": \"basic\"}",
                "{\"tags\": [\"env\"]}",
                "{\"identity\": \"SystemAssigned\"}",
                "{\"identity\": {\"type\": \"Bogus\"}}",
                "{\"identity\": {\"type\": \"UserAssigned\", \"userAssignedIdentities\": [\"id\"]}}",
                "{\"properties\": {\"disableLocalAuth\": \"true\"}}",
                "{\"properties\": {\"authOptions\": \"apiKeyOnly\"}}",
                "{\"properties\": {\"networkRuleSet\": \"open\"}}",
                "{\"properties\": {\"networkRuleSet\": {\"ipRules\": \"invalid\"}}}",
                "{\"properties\": {\"encryptionWithCmk\": \"Enabled\"}}",
                "{\"properties\": {\"dataExfiltrationProtections\": \"BlockAll\"}}")) {
            given().contentType("application/json").body(patch).when().patch(service + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidRequestContent"));
        }

        given().when().get(service + API).then().statusCode(200)
            .body("properties.disableLocalAuth", equalTo(false))
            .body("properties.dataExfiltrationProtections", empty())
            .body("$", not(hasKey("identity")));
        // A null is no value, so it still means "keep what is stored".
        given().contentType("application/json").body("{\"properties\": {\"disableLocalAuth\": null}}")
            .when().patch(service + API).then().statusCode(200);
    }

    @Test
    void servicesAreListedByResourceGroupAndBySubscription() {
        put("rg-a", "lista", CLI_BODY).then().statusCode(201);
        put("rg-b", "listb", CLI_BODY).then().statusCode(201);

        given().when().get(group("rg-a") + API).then().statusCode(200)
            .body("value.name", contains("lista"));
        given().when().get("/subscriptions/" + SUB + "/providers/Microsoft.Search/searchServices" + API)
            .then().statusCode(200)
            .body("value", hasSize(2))
            .body("value.name", hasItem("lista"))
            .body("value.name", hasItem("listb"));
        given().when().get(group("rg-empty") + API).then().statusCode(200).body("value", empty());
    }

    @Test
    void theGenericResourceListingShowsTheServiceUnderItsCanonicalLocation() {
        put("rg-index", "indexed", CLI_BODY).then().statusCode(201);
        given().when().get("/subscriptions/" + SUB + "/resourceGroups/rg-index/resources?api-version=2021-04-01")
            .then().statusCode(200)
            .body("value.find { it.name == 'indexed' }.type", equalTo("Microsoft.Search/searchServices"))
            .body("value.find { it.name == 'indexed' }.location", equalTo("westus"));
    }

    @Test
    void deletingAnswers200ThenTheResourceIsGoneAndASecondDeleteAnswers204() {
        put("rg", "deleted", CLI_BODY).then().statusCode(201);
        given().when().delete(service("rg", "deleted") + API).then().statusCode(200);
        given().when().get(service("rg", "deleted") + API).then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"))
            .body("error.message", equalTo("The Resource 'Microsoft.Search/searchServices/deleted' under resource "
                + "group 'rg' was not found. For more details please go to https://aka.ms/ARMResourceNotFoundFix"));
        given().when().delete(service("rg", "deleted") + API).then().statusCode(204);
    }

    @Test
    void adminKeysAreStableAndRegenerateReplacesOnlyTheNamedOne() {
        put("rg", "adminkeys", CLI_BODY).then().statusCode(201);
        String keys = service("rg", "adminkeys");

        Response first = post(keys + "/listAdminKeys");
        first.then().statusCode(200)
            .body("primaryKey", matchesPattern(KEY_PATTERN))
            .body("secondaryKey", matchesPattern(KEY_PATTERN));
        String primary = first.path("primaryKey");
        String secondary = first.path("secondaryKey");
        assertNotEquals(primary, secondary);
        post(keys + "/listAdminKeys").then().body("primaryKey", equalTo(primary))
            .body("secondaryKey", equalTo(secondary));

        Response regenerated = post(keys + "/regenerateAdminKey/primary");
        regenerated.then().statusCode(200).body("secondaryKey", equalTo(secondary));
        String newPrimary = regenerated.path("primaryKey");
        assertNotEquals(primary, newPrimary);

        post(keys + "/regenerateAdminKey/secondary").then().statusCode(200)
            .body("primaryKey", equalTo(newPrimary))
            .body("secondaryKey", not(equalTo(secondary)));

        post(keys + "/regenerateAdminKey/tertiary").then().statusCode(400);
        post(service("rg", "absent") + "/listAdminKeys").then().statusCode(404);
    }

    @Test
    void queryKeysStartWithAnUnnamedDefaultAndCanBeCreatedAndDeleted() {
        put("rg", "querykeys", CLI_BODY).then().statusCode(201);
        String keys = service("rg", "querykeys");

        post(keys + "/listQueryKeys").then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0]", hasKey("name"))
            .body("value[0].name", nullValue())
            .body("value[0].key", matchesPattern(KEY_PATTERN))
            .body("$", hasKey("nextLink"))
            .body("nextLink", nullValue());

        String created = post(keys + "/createQueryKey/browser").then().statusCode(200)
            .body("name", equalTo("browser"))
            .body("key", matchesPattern(KEY_PATTERN))
            .extract().path("key");

        // Before 2020-08-01 the listing was a GET.
        List<String> listed = given().when().get(keys + "/listQueryKeys" + API).then().statusCode(200)
            .body("value.name", contains(null, "browser"))
            .extract().path("value.key");
        assertEquals(created, listed.get(1));

        given().when().delete(keys + "/deleteQueryKey/" + created + API).then().statusCode(200);
        post(keys + "/listQueryKeys").then().body("value", hasSize(1));
        // The service exists but the key is gone: Azure answers 204, so a repeated delete succeeds.
        given().when().delete(keys + "/deleteQueryKey/" + created + API).then().statusCode(204);
        post(keys + "/listQueryKeys").then().body("value", hasSize(1));
    }

    @Test
    void checkNameAvailabilityTellsFreeTakenAndInvalidNamesApart() {
        String check = "/subscriptions/" + SUB + "/providers/Microsoft.Search/checkNameAvailability" + API;

        given().contentType("application/json").body("{\"name\": \"freename\", \"type\": \"searchServices\"}")
            .when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(true))
            .body("$", not(hasKey("reason")));

        put("rg", "takenname", CLI_BODY).then().statusCode(201);
        given().contentType("application/json").body("{\"name\": \"takenname\", \"type\": \"searchServices\"}")
            .when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(false))
            .body("reason", equalTo("AlreadyExists"))
            .body("message", equalTo(""));

        given().contentType("application/json").body("{\"name\": \"Not-Valid-\", \"type\": \"searchServices\"}")
            .when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(false))
            .body("reason", equalTo("Invalid"));

        given().when().delete(service("rg", "takenname") + API).then().statusCode(200);
        given().contentType("application/json").body("{\"name\": \"takenname\", \"type\": \"searchServices\"}")
            .when().post(check).then().statusCode(200).body("nameAvailable", equalTo(true));
    }

    @Test
    void whatTheSpecRulesOutIsRefused() {
        put("rg", "nosku", "{\"location\": \"westus\"}").then().statusCode(400)
            .body("error.code", equalTo("InvalidRequestContent"));
        put("rg", "badsku", "{\"location\": \"westus\", \"sku\": {\"name\": \"premium\"}}").then().statusCode(400)
            .body("error.message", containsString("storage_optimized_l2"));
        put("rg", "toomany", """
                {"location": "westus", "sku": {"name": "standard"}, "properties": {"replicaCount": 13}}""")
            .then().statusCode(400).body("error.message", containsString("replicaCount"));
        put("rg", "toofew", """
                {"location": "westus", "sku": {"name": "standard"}, "properties": {"partitionCount": 0}}""")
            .then().statusCode(400).body("error.message", containsString("partitionCount"));
        put("rg", "notjson", "{\"location\": ").then().statusCode(400)
            .body("error.code", equalTo("InvalidRequestContent"));

        given().when().get(service("rg", "nosku") + API).then().statusCode(404);
    }

    private static String sized(String sku, int replicas, int partitions) {
        return "{\"location\": \"westus\", \"sku\": {\"name\": \"" + sku + "\"}, \"properties\": {\"replicaCount\": "
            + replicas + ", \"partitionCount\": " + partitions + "}}";
    }

    @Test
    void aScaleTheSkuDoesNotReachIsRefused() {
        put("rg", "free2", sized("free", 2, 1)).then().statusCode(400)
            .body("error.code", equalTo("InvalidRequestContent"))
            .body("error.message", containsString("at most 1 replica"));
        put("rg", "free2p", sized("free", 1, 2)).then().statusCode(400)
            .body("error.message", containsString("at most 1 partition"));
        put("rg", "basic4", sized("basic", 4, 1)).then().statusCode(400)
            .body("error.message", containsString("at most 3 replica"));
        put("rg", "basic4p", sized("basic", 1, 4)).then().statusCode(400)
            .body("error.message", containsString("at most 3 partition"));
        put("rg", "odd", sized("standard", 1, 5)).then().statusCode(400)
            .body("error.message", containsString("[1, 2, 3, 4, 6, 12]"));
        put("rg", "units", sized("standard", 12, 4)).then().statusCode(400)
            .body("error.message", containsString("36 search units"));

        given().when().get(service("rg", "basic4") + API).then().statusCode(404);
    }

    @Test
    void aScaleWithinTheSkuIsAccepted() {
        put("rg", "free1", sized("free", 1, 1)).then().statusCode(201);
        put("rg", "basic9", sized("basic", 3, 3)).then().statusCode(201);
        put("rg", "std36", sized("standard", 3, 12)).then().statusCode(201);
        put("rg", "l2", sized("storage_optimized_l2", 12, 3)).then().statusCode(201);
    }

    @Test
    void highDensityHostingIsStandard3AloneAndStopsAtThreePartitions() {
        String highDensity = """
                {"location": "westus", "sku": {"name": "%s"},
                 "properties": {"hostingMode": "highDensity", "partitionCount": %d}}""";
        put("rg", "hd-s2", highDensity.formatted("standard2", 1)).then().statusCode(400)
            .body("error.message", containsString("only available on the 'standard3' SKU"));
        put("rg", "hd-four", highDensity.formatted("standard3", 4)).then().statusCode(400)
            .body("error.message", containsString("at most 3 partition"));
        put("rg", "hd-three", highDensity.formatted("standard3", 3)).then().statusCode(201);
    }

    @Test
    void aPatchIsJudgedOnTheServiceItWouldLeave() {
        put("rg", "grow", sized("basic", 3, 1)).then().statusCode(201);

        given().contentType("application/json").body("{\"properties\": {\"replicaCount\": 4}}")
            .when().patch(service("rg", "grow") + API)
            .then().statusCode(400).body("error.message", containsString("at most 3 replica"));
        given().contentType("application/json").body("{\"sku\": {\"name\": \"free\"}}")
            .when().patch(service("rg", "grow") + API)
            .then().statusCode(400).body("error.message", containsString("at most 1 replica"));
        given().when().get(service("rg", "grow") + API).then().statusCode(200)
            .body("sku.name", equalTo("basic")).body("properties.replicaCount", equalTo(3));

        given().contentType("application/json").body("{\"properties\": {\"partitionCount\": 3}}")
            .when().patch(service("rg", "grow") + API)
            .then().statusCode(200).body("properties.partitionCount", equalTo(3));
    }

    @Test
    void valuesOutsideTheSpecsEnumsAndMalformedIpRulesAreRefused() {
        put("rg", "enums", CLI_BODY).then().statusCode(201);
        String service = service("rg", "enums");

        for (String patch : List.of(
                "{\"properties\": {\"semanticSearch\": \"bogus\"}}",
                "{\"properties\": {\"publicNetworkAccess\": \"bogus\"}}",
                "{\"properties\": {\"encryptionWithCmk\": {\"enforcement\": \"bogus\"}}}",
                "{\"properties\": {\"knowledgeRetrieval\": \"bogus\"}}",
                "{\"properties\": {\"computeType\": \"bogus\"}}",
                "{\"properties\": {\"dataExfiltrationProtections\": [\"bogus\"]}}",
                "{\"properties\": {\"networkRuleSet\": {\"bypass\": \"bogus\"}}}",
                "{\"properties\": {\"networkRuleSet\": {\"ipRules\": [{\"value\": \"not-an-ip\"}]}}}",
                "{\"properties\": {\"networkRuleSet\": {\"ipRules\": [{\"value\": \"10.0.0.256\"}]}}}",
                "{\"properties\": {\"networkRuleSet\": {\"ipRules\": [{\"value\": \"10.0.0.0/33\"}]}}}")) {
            given().contentType("application/json").body(patch).when().patch(service + API)
                .then().statusCode(400).body("error.code", equalTo("InvalidRequestContent"));
        }
        put("rg", "enums-put", """
                {"location": "westus", "sku": {"name": "basic"}, "properties": {"semanticSearch": "bogus"}}""")
            .then().statusCode(400).body("error.message", containsString("semanticSearch"));

        given().when().get(service + API).then().statusCode(200)
            .body("properties.semanticSearch", equalTo("free"))
            .body("properties.publicNetworkAccess", equalTo("Enabled"))
            .body("properties.networkRuleSet.ipRules", empty());
    }

    @Test
    void validEnumValuesInAnyCasingAndIpRulesAreAccepted() {
        put("rg", "valid", CLI_BODY).then().statusCode(201);

        given().contentType("application/json").body("""
                {"properties": {"semanticSearch": "Standard", "publicNetworkAccess": "disabled",
                 "encryptionWithCmk": {"enforcement": "Enabled"}, "dataExfiltrationProtections": ["BlockAll"],
                 "networkRuleSet": {"bypass": "AzurePortal",
                                    "ipRules": [{"value": "123.1.2.3"}, {"value": "10.0.0.0/24"}]}}}""")
            .when().patch(service("rg", "valid") + API)
            .then().statusCode(200)
            .body("properties.publicNetworkAccess", equalTo("Disabled"))
            .body("properties.networkRuleSet.bypass", equalTo("AzurePortal"))
            .body("properties.networkRuleSet.ipRules.value", contains("123.1.2.3", "10.0.0.0/24"));
    }

    @Test
    void knowledgeRetrievalIsAnsweredOnceAClientSetsIt() {
        put("rg", "agentic", CLI_BODY).then().statusCode(201)
            .body("properties", not(hasKey("knowledgeRetrieval")));

        // The az CLI's `search service update --knowledge-retrieval standard` (2026-03-01-preview).
        given().contentType("application/json").body("{\"properties\": {\"knowledgeRetrieval\": \"standard\"}}")
            .when().patch(service("rg", "agentic") + "?api-version=2026-03-01-preview")
            .then().statusCode(200).body("properties.knowledgeRetrieval", equalTo("standard"));
        given().contentType("application/json").body("{\"tags\": {\"env\": \"dev\"}}")
            .when().patch(service("rg", "agentic") + API)
            .then().statusCode(200).body("properties.knowledgeRetrieval", equalTo("standard"));
    }

    @Test
    void aPutCannotMoveAnExistingServiceToAnotherLocation() {
        put("rg", "staysput", CLI_BODY).then().statusCode(201);

        put("rg", "staysput", CLI_BODY.replace("westus", "eastus")).then().statusCode(400)
            .body("error.code", equalTo("InvalidResourceLocation"))
            .body("error.message", containsString("already exists in location 'westus'"));
        given().when().get(service("rg", "staysput") + API).then().statusCode(200)
            .body("location", equalTo("West US"));
    }

    @Test
    void theHostingModeIsFixedWhenTheServiceIsCreated() {
        String highDensity = """
                {"location": "westus", "sku": {"name": "standard3"}, "properties": {"hostingMode": "HighDensity"}}""";
        put("rg", "fixedmode", highDensity).then().statusCode(201);

        put("rg", "fixedmode", """
                {"location": "westus", "sku": {"name": "standard3"}, "properties": {"hostingMode": "Default"}}""")
            .then().statusCode(400)
            .body("error.code", equalTo("InvalidRequestContent"))
            .body("error.message", containsString("hostingMode"));
        given().contentType("application/json").body("{\"properties\": {\"hostingMode\": \"Default\"}}")
            .when().patch(service("rg", "fixedmode") + API)
            .then().statusCode(400).body("error.message", containsString("hostingMode"));

        // The same mode in another casing is no change (Azure echoes the casing it was sent), and a PUT that leaves
        // the mode out keeps it.
        given().contentType("application/json").body("{\"properties\": {\"hostingMode\": \"highdensity\"}}")
            .when().patch(service("rg", "fixedmode") + API).then().statusCode(200);
        put("rg", "fixedmode", "{\"location\": \"westus\", \"sku\": {\"name\": \"standard3\"}}")
            .then().statusCode(200).body("properties.hostingMode", equalTo("highdensity"));
    }

    @Test
    void aPutWithoutASkuKeepsTheStoredOneAndDeletingAKeyOfNoServiceIsNotFound() {
        put("rg", "keepsku", sized("basic", 1, 1)).then().statusCode(201);
        put("rg", "keepsku", "{\"location\": \"westus\"}").then().statusCode(200).body("sku.name", equalTo("basic"));

        given().when().delete(service("rg", "absent") + "/deleteQueryKey/anything" + API).then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void aServicePathWithoutAResourceGroupAndUnservedChildrenAreNotFound() {
        put("rg", "children", CLI_BODY).then().statusCode(201);
        given().when().get("/subscriptions/" + SUB + "/providers/Microsoft.Search/searchServices/children" + API)
            .then().statusCode(404);
        given().when().get(service("rg", "children") + "/sharedPrivateLinkResources" + API).then().statusCode(404);
        given().when().get(service("rg", "children") + "/privateEndpointConnections/pe1" + API).then().statusCode(404);
    }

    @Test
    void theProviderListsTheSearchServiceTypeWithAzuresApiVersions() {
        given().when().get("/subscriptions/" + SUB + "/providers/Microsoft.Search?api-version=2021-04-01")
            .then().statusCode(200)
            .body("namespace", equalTo("Microsoft.Search"))
            .body("resourceTypes.find { it.resourceType == 'searchServices' }.apiVersions", hasItem("2025-05-01"));
    }
}
