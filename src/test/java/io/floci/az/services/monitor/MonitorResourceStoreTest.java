package io.floci.az.services.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Monitor: workspaces, data collection rules and diagnostic settings in the ARM resource store")
class MonitorResourceStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API = "?api-version=2022-10-01";
    private static final String GROUP = "/subscriptions/Sub-Monitor/resourceGroups/RG-Monitor";
    private static final String LEGACY_GROUP = "/subscriptions/Sub-Legacy/resourceGroups/RG-Legacy";
    private static final String STREAM = "Custom-Legacy_CL";

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static void putLegacy(StorageBackend<String, StoredObject> backend, String key, Object value)
            throws Exception {
        backend.put(key, new StoredObject(key, MAPPER.writeValueAsBytes(value), Map.of(), Instant.now(), "etag"));
    }

    private static void putLookup(StorageBackend<String, StoredObject> backend, String key, String target) {
        backend.put(key, new StoredObject(key, target.getBytes(StandardCharsets.UTF_8), Map.of(), Instant.now(), ""));
    }

    @Test
    void aWorkspacePutThroughAnotherCasingUpdatesTheOneWorkspace() {
        String type = "/providers/Microsoft.OperationalInsights/workspaces/";
        String workspace = GROUP + type + "Case-WS";
        String customerId = given().contentType(ContentType.JSON).body("{\"location\":\"eastus\"}")
            .when().put(workspace + API).then().statusCode(201).extract().path("properties.customerId");

        given().contentType(ContentType.JSON).body("{\"location\":\"eastus\"}")
            .when().put(GROUP.toLowerCase() + type + "case-ws" + API).then().statusCode(201)
            .body("id", equalTo(workspace))
            .body("properties.customerId", equalTo(customerId));

        given().when().get(GROUP + "/providers/Microsoft.OperationalInsights/workspaces" + API)
            .then().statusCode(200).body("value", hasSize(1)).body("value[0].name", equalTo("Case-WS"));
        given().when().delete(GROUP.toUpperCase().replace("SUBSCRIPTIONS", "subscriptions")
            .replace("RESOURCEGROUPS", "resourceGroups") + type + "CASE-WS" + API).then().statusCode(200);
        given().when().get(workspace + API).then().statusCode(404);
    }

    @Test
    void aResourceGroupListsItsOwnWorkspacesEndpointsAndRules() {
        for (String group : List.of(GROUP, GROUP + "-Other")) {
            given().contentType(ContentType.JSON).body("{\"location\":\"eastus\"}")
                .when().put(group + "/providers/Microsoft.OperationalInsights/workspaces/list-ws" + API)
                .then().statusCode(201);
            given().contentType(ContentType.JSON).body("{\"location\":\"eastus\"}")
                .when().put(group + "/providers/Microsoft.Insights/dataCollectionEndpoints/list-dce" + API)
                .then().statusCode(201);
            given().contentType(ContentType.JSON).body("{\"location\":\"eastus\",\"properties\":{}}")
                .when().put(group + "/providers/Microsoft.Insights/dataCollectionRules/list-dcr" + API)
                .then().statusCode(201);
        }

        given().when().get(GROUP + "/providers/Microsoft.OperationalInsights/workspaces" + API)
            .then().statusCode(200).body("value", hasSize(1))
            .body("value[0].id", equalTo(GROUP + "/providers/Microsoft.OperationalInsights/workspaces/list-ws"));
        given().when().get(GROUP + "/providers/Microsoft.Insights/dataCollectionEndpoints" + API)
            .then().statusCode(200).body("value", hasSize(1));
        given().when().get(GROUP + "/providers/Microsoft.Insights/dataCollectionRules" + API)
            .then().statusCode(200).body("value", hasSize(1));
    }

    @Test
    void aRedeployedRuleKeepsItsImmutableId() {
        String rule = GROUP + "/providers/Microsoft.Insights/dataCollectionRules/stable-dcr";
        String immutableId = given().contentType(ContentType.JSON).body("{\"location\":\"eastus\",\"properties\":{}}")
            .when().put(rule + API).then().statusCode(201).extract().path("properties.immutableId");

        given().contentType(ContentType.JSON).body("{\"location\":\"eastus\",\"properties\":{}}")
            .when().put(rule + API).then().statusCode(201)
            .body("properties.immutableId", equalTo(immutableId));
    }

    @Test
    void diagnosticSettingsAreListedByTheResourceTheyExtendInAnyCasing() {
        String server = GROUP + "/providers/Microsoft.Sql/servers/diag-sql";
        String other = GROUP + "/providers/Microsoft.Sql/servers/diag-other";
        given().contentType(ContentType.JSON).body("{\"properties\":{\"workspaceId\":\"w\"}}")
            .when().put(server + "/providers/Microsoft.Insights/diagnosticSettings/audit" + API)
            .then().statusCode(200).body("name", equalTo("audit"));
        given().contentType(ContentType.JSON).body("{\"properties\":{\"workspaceId\":\"w2\"}}")
            .when().put(server.toLowerCase() + "/providers/microsoft.insights/diagnosticSettings/AUDIT" + API)
            .then().statusCode(200).body("name", equalTo("audit"));
        given().contentType(ContentType.JSON).body("{\"properties\":{}}")
            .when().put(other + "/providers/Microsoft.Insights/diagnosticSettings/audit" + API)
            .then().statusCode(200);

        given().when().get(server.toUpperCase() + "/providers/Microsoft.Insights/diagnosticSettings" + API)
            .then().statusCode(200).body("value", hasSize(1))
            .body("value[0].properties.workspaceId", equalTo("w2"));
    }

    @Test
    void entriesStoredUnderTheOldKeysAreServedAndMoveOnTheirFirstWrite() throws Exception {
        String workspaceId = LEGACY_GROUP + "/providers/Microsoft.OperationalInsights/workspaces/legacy-ws";
        String ruleId = LEGACY_GROUP + "/providers/Microsoft.Insights/dataCollectionRules/legacy-dcr";
        String server = LEGACY_GROUP + "/providers/Microsoft.Sql/servers/legacy-sql";
        String settingId = server + "/providers/microsoft.insights/diagnosticSettings/legacy-diag";
        String customerId = "6b0f4c5e-0000-4000-8000-00000000c0de";
        StorageBackend<String, StoredObject> backend = storageFactory.create("monitor");
        String workspaceKey = "workspace/Sub-Legacy/RG-Legacy/legacy-ws";
        String ruleKey = "dcr/Sub-Legacy/RG-Legacy/legacy-dcr";
        String settingKey = "diag/" + server.substring(1).toLowerCase() + "/legacy-diag";
        putLegacy(backend, workspaceKey, Map.of("id", workspaceId, "name", "legacy-ws",
            "type", "Microsoft.OperationalInsights/workspaces", "location", "eastus",
            "properties", Map.of("provisioningState", "Succeeded", "customerId", customerId)));
        putLookup(backend, "workspaceId/" + customerId, workspaceKey);
        putLegacy(backend, ruleKey, Map.of("id", ruleId, "name", "legacy-dcr",
            "type", "Microsoft.Insights/dataCollectionRules", "location", "eastus",
            "properties", Map.of("provisioningState", "Succeeded", "immutableId", "dcr-legacy0000000001",
                "destinations", Map.of("logAnalytics", List.of(Map.of("name", "la",
                    "workspaceResourceId", workspaceId.toLowerCase()))))));
        putLookup(backend, "dcrId/dcr-legacy0000000001", ruleKey);
        putLegacy(backend, settingKey, Map.of("id", settingId, "name", "legacy-diag",
            "type", "Microsoft.Insights/diagnosticSettings", "properties", Map.of("workspaceId", "w")));

        given().when().get(workspaceId + API).then().statusCode(200)
            .body("properties.customerId", equalTo(customerId));
        given().when().get(LEGACY_GROUP + "/providers/Microsoft.OperationalInsights/workspaces" + API)
            .then().statusCode(200).body("value.name", containsInAnyOrder("legacy-ws"));
        given().when().get(server + "/providers/Microsoft.Insights/diagnosticSettings" + API)
            .then().statusCode(200).body("value", hasSize(1)).body("value[0].name", equalTo("legacy-diag"));

        // The data planes find the legacy rule by its immutableId and the workspace by its customerId
        given().contentType(ContentType.JSON).body("[{\"Level\":\"Error\",\"Message\":\"legacy\"}]")
            .when().post("/dataCollectionRules/{dcr}/streams/{stream}", "dcr-legacy0000000001", STREAM)
            .then().statusCode(204);
        given().contentType(ContentType.JSON).body(Map.of("query", STREAM + " | project Message"))
            .when().post("/v1/workspaces/{workspaceId}/query", customerId)
            .then().statusCode(200).body("tables[0].rows[0][0]", equalTo("legacy"));
        given().contentType(ContentType.JSON).body(Map.of("query", STREAM + " | project Message"))
            .when().post("/v1/workspaces/{workspaceId}/query", "legacy-ws")
            .then().statusCode(200).body("tables[0].rows", hasSize(1));

        given().contentType(ContentType.JSON).body("{\"location\":\"eastus\"}")
            .when().put(workspaceId + API).then().statusCode(201)
            .body("properties.customerId", equalTo(customerId));
        assertFalse(backend.get(workspaceKey).isPresent(), "the write moved the workspace off its old key");
        assertTrue(backend.keys().contains(workspaceId.toLowerCase()));
        given().when().post(workspaceId + "/sharedKeys?api-version=2020-08-01").then().statusCode(200);

        given().contentType(ContentType.JSON).body("{\"properties\":{}}")
            .when().put(settingId + API).then().statusCode(200);
        assertFalse(backend.get(settingKey).isPresent(), "the write moved the setting off its old key");
        given().when().get(server + "/providers/Microsoft.Insights/diagnosticSettings" + API)
            .then().statusCode(200).body("value", hasSize(1));

        given().when().delete(ruleId + API).then().statusCode(200);
        assertFalse(backend.get(ruleKey).isPresent(), "the delete removed the rule's old key");
        assertFalse(backend.get("dcrId/dcr-legacy0000000001").isPresent(), "the delete removed the old lookup");
        given().when().delete(workspaceId + API).then().statusCode(200);
        assertFalse(backend.get("workspaceId/" + customerId).isPresent(), "the delete removed the old lookup");
        assertEquals(1, backend.keys().stream().filter(key -> key.startsWith(customerId + "/")).count(),
            "the ingested record stays under the customerId");
    }
}
