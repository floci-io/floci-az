package io.floci.az.services.monitor;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Monitor: diagnostic settings answered in Azure's shape, at resource and at subscription scope")
class DiagnosticSettingsTest {

    static final String API = "?api-version=2021-05-01-preview";
    static final String SUB = "/subscriptions/00000000-0000-0000-0000-000000000001";
    static final String VAULT = SUB + "/resourceGroups/rg-diag/providers/Microsoft.KeyVault/vaults/kv-diag";
    static final String STORAGE = SUB + "/resourceGroups/rg-diag/providers/Microsoft.Storage/storageAccounts/sadiag";
    static final String WORKSPACE = SUB + "/resourceGroups/rg-diag/providers/Microsoft.OperationalInsights/workspaces/ws-diag";
    static final String SETTINGS = "/providers/Microsoft.Insights/diagnosticSettings/";
    static final List<String> ACTIVITY_LOG_CATEGORIES = List.of("Security", "Administrative", "ServiceHealth", "Alert",
            "Recommendation", "Policy", "Autoscale", "ResourceHealth");

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    static JsonPath put(String path, String body, int status) {
        return given().contentType("application/json").body(body).when().put(path + API)
                .then().statusCode(status).extract().jsonPath();
    }

    @Test
    void aSettingWithNeitherLogsNorMetricsIsKeptAsSentWithNoCategoryMadeUp() {
        JsonPath created = put(VAULT + SETTINGS + "audit", "{\"properties\": {\"storageAccountId\": \"" + STORAGE + "\"}}", 200);
        for (JsonPath setting : List.of(created,
                given().when().get(VAULT + SETTINGS + "audit" + API).then().statusCode(200).extract().jsonPath())) {
            assertEquals("Microsoft.Insights/diagnosticSettings", setting.getString("type"));
            assertEquals("audit", setting.getString("name"));
            Map<String, Object> top = setting.getMap("");
            for (String key : List.of("location", "kind", "tags", "identity")) {
                assertTrue(top.containsKey(key) && top.get(key) == null, key + " is written as null");
            }
            Map<String, Object> properties = setting.getMap("properties");
            assertEquals(List.of("storageAccountId", "serviceBusRuleId", "workspaceId", "eventHubAuthorizationRuleId",
                    "eventHubName", "metrics", "logs", "logAnalyticsDestinationType"), List.copyOf(properties.keySet()));
            assertEquals(STORAGE, properties.get("storageAccountId"));
            assertNull(properties.get("workspaceId"));
            assertEquals(List.of(), properties.get("logs"), "no category is made up for the setting");
            assertEquals(List.of(), properties.get("metrics"));
        }
    }

    @Test
    void logAndMetricEntriesCarryTheirCategoryGroupAndRetentionPolicy() {
        JsonPath created = put(VAULT + SETTINGS + "audit", """
                {"properties": {"workspaceId": "%s", "logAnalyticsDestinationType": "Dedicated",
                  "logs": [{"category": "AuditEvent", "enabled": true}, {"categoryGroup": "allLogs", "enabled": false}],
                  "metrics": [{"category": "AllMetrics", "enabled": true}]}}""".formatted(WORKSPACE), 200);
        assertEquals("Dedicated", created.getString("properties.logAnalyticsDestinationType"));
        assertEquals(Map.of("category", "AuditEvent", "enabled", true, "retentionPolicy", Map.of("enabled", false, "days", 0)),
                withoutNulls(created.getMap("properties.logs[0]")));
        assertTrue(created.getMap("properties.logs[0]").containsKey("categoryGroup"));
        assertEquals(List.of("category", "categoryGroup", "enabled", "retentionPolicy"),
                List.copyOf(created.getMap("properties.logs[0]").keySet()));
        assertEquals("allLogs", created.getString("properties.logs[1].categoryGroup"));
        assertNull(created.get("properties.logs[1].category"));
        assertEquals("AllMetrics", created.getString("properties.metrics[0].category"));
        assertEquals(Map.of("enabled", false, "days", 0), created.getMap("properties.metrics[0].retentionPolicy"));

        given().when().get(VAULT + "/providers/Microsoft.Insights/diagnosticSettings" + API).then().statusCode(200)
                .body("value.size()", equalTo(1))
                .body("value[0].properties.logs[0].category", equalTo("AuditEvent"));
    }

    @Test
    void aSubscriptionSettingListsEveryActivityLogCategoryOnRead() {
        JsonPath created = put(SUB + SETTINGS + "activity", """
                {"properties": {"workspaceId": "%s", "logs": [{"category": "Recommendation", "enabled": true}]}}"""
                .formatted(WORKSPACE), 200);
        assertEquals(SUB + "/providers/microsoft.insights/diagnosticSettings/activity", created.getString("id"));
        assertEquals("Microsoft.Insights/diagnosticSettings", created.getString("type"));
        assertEquals("global", created.getString("location"));
        assertEquals(List.of("workspaceId", "logs"), List.copyOf(created.getMap("properties").keySet()),
                "a subscription setting leaves out what was not sent");
        assertEquals(List.of(Map.of("category", "Recommendation", "enabled", true)),
                created.getList("properties.logs").stream().map(e -> withoutNulls((Map<String, Object>) e)).toList());

        JsonPath read = given().when().get(SUB + SETTINGS + "activity" + API).then().statusCode(200).extract().jsonPath();
        assertEquals(ACTIVITY_LOG_CATEGORIES, read.getList("properties.logs.category"));
        assertEquals(List.of(false, false, false, false, true, false, false, false), read.getList("properties.logs.enabled"));
        assertFalse(read.getMap("properties").containsKey("metrics"));

        given().when().get(SUB + "/providers/Microsoft.Insights/diagnosticSettings" + API).then().statusCode(200)
                .body("value.size()", equalTo(1))
                .body("value[0].name", equalTo("activity"))
                .body("value[0].properties.logs.size()", equalTo(8));
    }

    @Test
    void aMissingSettingIsResourceNotFoundAndTheCategoriesListIsNotServedForASubscription() {
        given().when().get(VAULT + SETTINGS + "missing" + API).then().statusCode(404)
                .body("code", equalTo("ResourceNotFound"))
                .body("message", equalTo("The diagnostic setting 'missing' doesn't exist."));
        given().when().get(SUB + "/providers/Microsoft.Insights/diagnosticSettingsCategories" + API).then().statusCode(400)
                .body("code", equalTo("ResourceTypeNotSupported"))
                .body("message", equalTo("The resource type '/' does not support diagnostic settings."));
        put(VAULT + SETTINGS + "audit", "{\"properties\": {\"storageAccountId\": \"" + STORAGE + "\"}}", 200);
        given().when().get(VAULT + "/providers/Microsoft.Insights/diagnosticSettingsCategories" + API).then()
                .statusCode(404);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutTheStrictSwitchWhatAzureRefusesIsKept() {
        put(SUB + SETTINGS + "made-up", """
                {"properties": {"workspaceId": "%s", "logs": [{"category": "NotACategory", "enabled": true}]}}"""
                .formatted(WORKSPACE), 200);
        put(VAULT + SETTINGS + "kept", """
                {"properties": {"workspaceId": "%s",
                  "logs": [{"category": "AuditEvent", "enabled": true, "retentionPolicy": {"enabled": true, "days": 7}}]}}"""
                .formatted(WORKSPACE), 200);
        put(VAULT + SETTINGS + "again", """
                {"properties": {"workspaceId": "%s", "logs": [{"category": "AuditEvent", "enabled": true}]}}"""
                .formatted(WORKSPACE), 200);
    }

    static Map<String, Object> withoutNulls(Map<String, Object> entry) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        entry.forEach((key, value) -> {
            if (value != null) {
                out.put(key, value);
            }
        });
        return out;
    }
}
