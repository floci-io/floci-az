package io.floci.az.services.monitor;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.floci.az.services.monitor.DiagnosticSettingsTest.API;
import static io.floci.az.services.monitor.DiagnosticSettingsTest.SETTINGS;
import static io.floci.az.services.monitor.DiagnosticSettingsTest.STORAGE;
import static io.floci.az.services.monitor.DiagnosticSettingsTest.SUB;
import static io.floci.az.services.monitor.DiagnosticSettingsTest.VAULT;
import static io.floci.az.services.monitor.DiagnosticSettingsTest.WORKSPACE;
import static io.floci.az.services.monitor.DiagnosticSettingsTest.put;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(DiagnosticSettingsStrictTest.Strict.class)
@DisplayName("Monitor: parity-overrides.strict-diagnostic-settings refuses diagnostic settings as Azure does")
class DiagnosticSettingsStrictTest {

    public static class Strict implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-diagnostic-settings", "true");
        }
    }

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    private static void refused(String path, String body, int status, String code, String message) {
        given().contentType("application/json").body(body).when().put(path + API).then().statusCode(status)
                .body("code", equalTo(code))
                .body("message", equalTo(message));
    }

    @Test
    void aSubscriptionSettingTakesTheActivityLogCategoriesOnly() {
        refused(SUB + SETTINGS + "made-up", """
                {"properties": {"workspaceId": "%s", "logs": [{"category": "NotACategory", "enabled": true}]}}"""
                .formatted(WORKSPACE), 400, "BadRequest", "Category 'NotACategory' is not supported.");
        refused(SUB + SETTINGS + "group-as-category", """
                {"properties": {"workspaceId": "%s", "logs": [{"category": "AllLogs", "enabled": true}]}}"""
                .formatted(WORKSPACE), 400, "BadRequest", "Category 'AllLogs' is not supported.");
        put(SUB + SETTINGS + "activity", """
                {"properties": {"workspaceId": "%s", "logs": [{"category": "Administrative", "enabled": true},
                  {"category": "Policy", "enabled": true}]}}""".formatted(WORKSPACE), 200);
    }

    @Test
    void retentionIsRefusedForANewSetting() {
        refused(VAULT + SETTINGS + "retained", """
                {"properties": {"workspaceId": "%s",
                  "logs": [{"category": "AuditEvent", "enabled": true, "retentionPolicy": {"enabled": true, "days": 7}}]}}"""
                .formatted(WORKSPACE), 400, "BadRequest", "Diagnostic settings does not support retention for new diagnostic settings.");
        put(VAULT + SETTINGS + "not-retained", """
                {"properties": {"workspaceId": "%s",
                  "logs": [{"category": "AuditEvent", "enabled": true, "retentionPolicy": {"enabled": false, "days": 0}}]}}"""
                .formatted(WORKSPACE), 200);
    }

    @Test
    void aDataSinkIsNotReusedForTheSameCategoryOfOneResource() {
        put(VAULT + SETTINGS + "first", """
                {"properties": {"workspaceId": "%s", "storageAccountId": "%s",
                  "logs": [{"category": "AuditEvent", "enabled": true}], "metrics": [{"category": "AllMetrics", "enabled": true}]}}"""
                .formatted(WORKSPACE, STORAGE), 200);
        refused(VAULT + SETTINGS + "second", """
                {"properties": {"storageAccountId": "%s", "logs": [{"category": "AuditEvent", "enabled": true}]}}"""
                .formatted(STORAGE), 409, "Conflict",
                "Data sink '" + STORAGE + "' is already used in diagnostic setting 'first' for category 'AuditEvent'. "
                        + "Data sinks can't be reused in different settings on the same category for the same resource.");
        refused(VAULT + SETTINGS + "metrics", """
                {"properties": {"workspaceId": "%s", "metrics": [{"category": "AllMetrics", "enabled": true}]}}"""
                .formatted(WORKSPACE), 409, "Conflict",
                "Data sink '" + WORKSPACE + "' is already used in diagnostic setting 'first' for category 'AllMetrics'. "
                        + "Data sinks can't be reused in different settings on the same category for the same resource.");

        // Another category, the same setting again, or another resource: none conflicts.
        put(VAULT + SETTINGS + "other-category", """
                {"properties": {"storageAccountId": "%s", "logs": [{"category": "AzurePolicyEvaluationDetails", "enabled": true}]}}"""
                .formatted(STORAGE), 200);
        put(VAULT + SETTINGS + "first", """
                {"properties": {"storageAccountId": "%s", "logs": [{"category": "AuditEvent", "enabled": true}]}}"""
                .formatted(STORAGE), 200);
        put(SUB + "/resourceGroups/rg-diag/providers/Microsoft.KeyVault/vaults/kv-other" + SETTINGS + "first", """
                {"properties": {"storageAccountId": "%s", "logs": [{"category": "AuditEvent", "enabled": true}]}}"""
                .formatted(STORAGE), 200);
    }
}
