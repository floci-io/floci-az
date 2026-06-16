package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class MonitorServiceTest {

    private static final String SUB = "00000000-0000-0000-0000-000000000001";
    private static final String RG = "myResourceGroup";
    private static final String WORKSPACE_NAME = "myWorkspace";
    private static final String DCE_NAME = "myDce";
    private static final String DCR_NAME = "myDcr";
    private static final String STREAM = "Custom-MyStream_CL";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void testWorkspaceCrud() {
        // Create workspace
        String workspacePath = String.format("/subscriptions/%s/resourceGroups/%s/providers/Microsoft.OperationalInsights/workspaces/%s", SUB, RG, WORKSPACE_NAME);
        String customerId = given()
            .contentType(ContentType.JSON)
            .body(Map.of("location", "eastus", "properties", Map.of("retentionInDays", 30)))
            .when().put(workspacePath)
            .then().statusCode(201)
            .body("name", equalTo(WORKSPACE_NAME))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.customerId", notNullValue())
            .extract().path("properties.customerId");

        // Get workspace
        given()
            .when().get(workspacePath)
            .then().statusCode(200)
            .body("name", equalTo(WORKSPACE_NAME))
            .body("properties.customerId", equalTo(customerId));

        // Delete workspace
        given()
            .when().delete(workspacePath)
            .then().statusCode(200);

        // Get missing workspace
        given()
            .when().get(workspacePath)
            .then().statusCode(404);
    }

    @Test
    void testDceCrud() {
        String dcePath = String.format("/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Insights/dataCollectionEndpoints/%s", SUB, RG, DCE_NAME);
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("location", "eastus"))
            .when().put(dcePath)
            .then().statusCode(201)
            .body("name", equalTo(DCE_NAME))
            .body("properties.logsIngestion.endpoint", notNullValue());

        given()
            .when().get(dcePath)
            .then().statusCode(200)
            .body("name", equalTo(DCE_NAME));

        given()
            .when().delete(dcePath)
            .then().statusCode(200);
    }

    @Test
    void testDcrCrud() {
        String dcrPath = String.format("/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Insights/dataCollectionRules/%s", SUB, RG, DCR_NAME);
        String immutableId = given()
            .contentType(ContentType.JSON)
            .body(Map.of("location", "eastus", "properties", Map.of()))
            .when().put(dcrPath)
            .then().statusCode(201)
            .body("name", equalTo(DCR_NAME))
            .body("properties.immutableId", notNullValue())
            .extract().path("properties.immutableId");

        given()
            .when().get(dcrPath)
            .then().statusCode(200)
            .body("name", equalTo(DCR_NAME))
            .body("properties.immutableId", equalTo(immutableId));

        given()
            .when().delete(dcrPath)
            .then().statusCode(200);
    }

    @Test
    void testLogsIngestionAndQuery() {
        // 1. Create Workspace
        String workspacePath = String.format("/subscriptions/%s/resourceGroups/%s/providers/Microsoft.OperationalInsights/workspaces/%s", SUB, RG, WORKSPACE_NAME);
        String customerId = given()
            .contentType(ContentType.JSON)
            .body(Map.of("location", "eastus"))
            .when().put(workspacePath)
            .then().statusCode(201)
            .extract().path("properties.customerId");

        // 2. Create DCR mapping to this workspace
        String dcrPath = String.format("/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Insights/dataCollectionRules/%s", SUB, RG, DCR_NAME);
        String immutableId = given()
            .contentType(ContentType.JSON)
            .body(Map.of("location", "eastus", "properties", Map.of(
                "destinations", Map.of(
                    "logAnalytics", List.of(Map.of(
                        "workspaceResourceId", workspacePath,
                        "name", "la-destination"
                    ))
                )
            )))
            .when().put(dcrPath)
            .then().statusCode(201)
            .extract().path("properties.immutableId");

        // 3. Ingest logs
        Instant now = Instant.now();
        List<Map<String, Object>> logs = List.of(
            Map.of("TimeGenerated", now.toString(), "Level", "Error", "Message", "Failed to login", "Value", 10),
            Map.of("TimeGenerated", now.minusSeconds(600).toString(), "Level", "Warning", "Message", "Slow response time", "Value", 5),
            Map.of("TimeGenerated", now.minusSeconds(7200).toString(), "Level", "Info", "Message", "User logged out", "Value", 2)
        );

        given()
            .contentType(ContentType.JSON)
            .body(logs)
            .when().post("/dataCollectionRules/{dcr}/streams/{stream}", immutableId, STREAM)
            .then().statusCode(204);

        // 4. Query logs
        // Test basic query and default sort (descending TimeGenerated)
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", STREAM + " | project TimeGenerated, Level, Message, Value"))
            .when().post("/v1/workspaces/{workspaceId}/query", customerId)
            .then().statusCode(200)
            .body("tables[0].name", equalTo("PrimaryResult"))
            .body("tables[0].columns.name", contains("TimeGenerated", "Level", "Message", "Value"))
            .body("tables[0].rows[0][1]", equalTo("Error"))
            .body("tables[0].rows[1][1]", equalTo("Warning"))
            .body("tables[0].rows[2][1]", equalTo("Info"));

        // Test filter (where Level == "Error")
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", STREAM + " | where Level == \"Error\" | project TimeGenerated, Level"))
            .when().post("/v1/workspaces/{workspaceId}/query", customerId)
            .then().statusCode(200)
            .body("tables[0].rows.size()", equalTo(1))
            .body("tables[0].rows[0][1]", equalTo("Error"));

        // Test filter inequality and numbers (where Value > 2)
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", STREAM + " | where Value > 2"))
            .when().post("/v1/workspaces/{workspaceId}/query", customerId)
            .then().statusCode(200)
            .body("tables[0].rows.size()", equalTo(2));

        // Test take N operator
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", STREAM + " | take 2"))
            .when().post("/v1/workspaces/{workspaceId}/query", customerId)
            .then().statusCode(200)
            .body("tables[0].rows.size()", equalTo(2));

        // Test project operator
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", STREAM + " | project Message, Level"))
            .when().post("/v1/workspaces/{workspaceId}/query", customerId)
            .then().statusCode(200)
            .body("tables[0].columns.name", contains("Message", "Level"))
            .body("tables[0].rows[0][0]", equalTo("Failed to login"))
            .body("tables[0].rows[0][1]", equalTo("Error"));

        // Test time filter with ago (where TimeGenerated > ago(1h))
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", STREAM + " | where TimeGenerated > ago(1h) | project TimeGenerated, Level"))
            .when().post("/v1/workspaces/{workspaceId}/query", customerId)
            .then().statusCode(200)
            .body("tables[0].rows.size()", equalTo(2)) // Error and Warning are within 1h, Info is not.
            .body("tables[0].rows[0][1]", equalTo("Error"))
            .body("tables[0].rows[1][1]", equalTo("Warning"));
    }
}
