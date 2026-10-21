package io.floci.az.core.arm;

import io.floci.az.core.ArmRouter;
import io.floci.az.core.AzureRequest;
import io.floci.az.services.policy.evaluation.PolicyEvaluationRule;
import io.floci.az.core.cloud.ScopeSource;
import io.floci.az.services.arm.ArmHandler;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the {@link ArmRequestGate} sits on every ARM management-plane route, once per request, whichever
 * lane serves it, and on nothing else. A recording rule is built in for this profile only and runs ahead of the
 * production rules, which in the default parity let every request here through.
 */
@QuarkusTest
@TestProfile(ArmRequestGateCoverageTest.RecordingRuleProfile.class)
@DisplayName("ArmRequestGate: every ARM request passes the gate exactly once")
class ArmRequestGateCoverageTest {

    public static class RecordingRuleProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(RecordingArmRequestRule.ENABLED_BY, "true");
        }
    }

    @Inject
    RecordingArmRequestRule recorder;

    @Inject
    ArmRequestGate gate;

    @Inject
    ArmHandler armHandler;

    @Inject
    ArmRouter armRouter;

    @BeforeEach
    void clearRecorder() {
        recorder.clear();
    }

    private static RequestSpecification json() {
        return given().header("Authorization", "Bearer test").contentType("application/json");
    }

    /** Asserts one entry and one exit for the same call, with the status the client saw. */
    private ArmCall assertGatedOnce(int clientStatus) {
        List<ArmCall> entered = recorder.entered();
        List<RecordingArmRequestRule.Passage> exited = recorder.exited();
        assertEquals(1, entered.size(), "gate entries: " + entered);
        assertEquals(1, exited.size(), "gate exits: " + exited);
        assertSame(entered.getFirst(), exited.getFirst().call());
        assertEquals(clientStatus, exited.getFirst().exitStatus());
        return entered.getFirst();
    }

    private void assertNotGated() {
        assertTrue(recorder.entered().isEmpty(), "gate entries: " + recorder.entered());
        assertTrue(recorder.exited().isEmpty(), "gate exits: " + recorder.exited());
    }

    @Test
    void theRecordingRuleRunsAheadOfTheProductionRules() {
        assertEquals(6, gate.rules().size(), "rules: " + gate.rules());
        assertInstanceOf(RecordingArmRequestRule.class, gate.rules().getFirst());
        assertInstanceOf(SubscriptionRule.class, gate.rules().get(1));
        assertInstanceOf(ProviderRule.class, gate.rules().get(2));
        assertInstanceOf(ResourceGroupRule.class, gate.rules().get(3));
        assertInstanceOf(LocationRule.class, gate.rules().get(4));
        assertInstanceOf(PolicyEvaluationRule.class, gate.rules().get(5));
    }

    // ── Requests floci-az makes to itself ───────────────────────────────────────

    @Test
    void anInternalRequestPassesTheGateOnceAndReachesItsHandler() {
        String account = "/subscriptions/sub-gate/resourceGroups/rg-gate/providers/Microsoft.Storage/storageAccounts/"
            + "gateinternal0001";
        json().body("{\"location\":\"eastus\"}").put(account + "?api-version=2023-01-01").then().statusCode(200);
        recorder.clear();

        AzureRequest origin = new AzureRequest("DELETE", "arm", "arm",
            "subscriptions/sub-gate/resourceGroups/rg-gate", null, null, Map.of(), Map.of(), null, false,
            "localhost", null, "subscriptions/sub-gate/resourceGroups/rg-gate", null, "localhost:4577");
        Response response = armRouter.dispatchInternal("DELETE", account, Map.of(), origin);

        assertEquals(200, response.getStatus());
        ArmCall call = assertGatedOnce(200);
        assertEquals("DELETE", call.method());
        assertEquals(account, call.id().id());
        assertEquals("sub-gate", call.scope().subscriptionId());
        json().get(account + "?api-version=2023-01-01").then().statusCode(404);
    }

    @Test
    void anInternalRequestNoStageClaimsIsAnArmNotFound() {
        AzureRequest origin = new AzureRequest("DELETE", "arm", "arm", "", null, null, Map.of(), null, false);

        Response response = armRouter.dispatchInternal("DELETE", "not-an-arm-path/x", Map.of(), origin);

        assertEquals(404, response.getStatus());
        assertNotGated();
    }

    // ── Generic ARM stage (ArmHandler) ──────────────────────────────────────────

    @Test
    void subscriptionGetPassesTheGate() {
        int status = json().get("/subscriptions/sub-gate?api-version=2022-12-01").then().extract().statusCode();

        ArmCall call = assertGatedOnce(status);
        assertEquals(200, status);
        assertEquals("GET", call.method());
        assertEquals("/subscriptions/sub-gate", call.id().id());
        assertEquals(ScopeSource.ARM_PATH, call.scope().source());
        assertEquals("sub-gate", call.scope().subscriptionId());
        assertNull(call.type(), "a subscription is not a declared resource type");
    }

    @Test
    void subscriptionAndTenantListsPassTheGate() {
        int subscriptions = json().get("/subscriptions?api-version=2022-12-01").then().extract().statusCode();
        assertEquals(List.of("subscriptions"), assertGatedOnce(subscriptions).id().types());

        recorder.clear();
        int tenants = json().get("/tenants?api-version=2022-12-01").then().extract().statusCode();
        assertEquals(List.of("tenants"), assertGatedOnce(tenants).id().types());
    }

    @Test
    void resourceGroupPutPassesTheGate() {
        int status = json().body("{\"location\":\"eastus\"}")
            .put("/subscriptions/sub-gate/resourcegroups/rg-gate?api-version=2022-09-01")
            .then().extract().statusCode();

        ArmCall call = assertGatedOnce(status);
        assertEquals("PUT", call.method());
        assertEquals("rg-gate", call.id().resourceGroup());
        assertEquals("rg-gate", call.scope().resourceGroup());
        assertEquals("Microsoft.Resources/resourceGroups", call.type());
    }

    @Test
    void inlineArmHandlerProviderPassesTheGate() {
        int status = json()
            .get("/subscriptions/sub-gate/resourceGroups/rg-gate/providers/Microsoft.Storage/storageAccounts/nosuchacct"
                + "?api-version=2023-05-01")
            .then().extract().statusCode();

        ArmCall call = assertGatedOnce(status);
        assertEquals("Microsoft.Storage/storageAccounts", call.id().typePath());
        assertEquals("Microsoft.Storage/storageAccounts", call.type());
    }

    // ── ArmHandler's provider lane (ArmProviderService) ─────────────────────────

    @Test
    void providerLaneNamespacePassesTheGateOnce() {
        int status = json()
            .get("/subscriptions/sub-gate/resourceGroups/rg-gate/providers/Microsoft.EventGrid/topics?api-version=2022-06-15")
            .then().extract().statusCode();

        ArmCall call = assertGatedOnce(status);
        assertEquals("Microsoft.EventGrid/topics", call.id().typePath());
        assertEquals("Microsoft.EventGrid/topics", call.type());
    }

    @Test
    void networkLanePassesTheGateOnce() {
        int status = json()
            .get("/subscriptions/sub-gate/providers/Microsoft.Network/virtualNetworks?api-version=2023-09-01")
            .then().extract().statusCode();

        ArmCall call = assertGatedOnce(status);
        assertEquals("Microsoft.Network/virtualNetworks", call.id().typePath());
        assertEquals("Microsoft.Network/virtualNetworks", call.type());
    }

    @Test
    void armHandlerCalledDirectlyEntersTheGateItself() {
        AzureRequest request = new AzureRequest("GET", "arm", "arm", "subscriptions/sub-direct", null, null,
            Map.of(), Map.of(), null, false, null, null, "subscriptions/sub-direct", null);

        Response response = armHandler.handle(request);

        ArmCall call = assertGatedOnce(response.getStatus());
        assertEquals("/subscriptions/sub-direct", call.id().id());
        assertNull(call.scope(), "a request built outside the filter carries no scope");
    }

    // ── Filter-lane provider stage ──────────────────────────────────────────────

    @Test
    void filterLaneProviderPassesTheGate() {
        int status = json()
            .get("/subscriptions/sub-gate/resourceGroups/rg-gate/providers/Microsoft.Cache/redis?api-version=2023-08-01")
            .then().extract().statusCode();

        ArmCall call = assertGatedOnce(status);
        assertEquals("Microsoft.Cache/redis", call.id().typePath());
        assertEquals("Microsoft.Cache/redis", call.type());
    }

    @Test
    void tenantRootedProviderPassesTheGate() {
        int status = json().get("/providers/Microsoft.Authorization/policyDefinitions?api-version=2025-03-01")
            .then().extract().statusCode();

        ArmCall call = assertGatedOnce(status);
        assertEquals(200, status);
        assertNull(call.id().subscriptionId());
        assertEquals(ScopeSource.DEFAULT, call.scope().source());
        assertEquals("Microsoft.Authorization/policyDefinitions", call.type());
    }

    // ── Monitor stage ───────────────────────────────────────────────────────────

    @Test
    void diagnosticSettingsPassTheGate() {
        int status = json()
            .get("/subscriptions/sub-gate/resourceGroups/rg-gate/providers/Microsoft.Storage/storageAccounts/acct"
                + "/providers/microsoft.insights/diagnosticSettings?api-version=2021-05-01-preview")
            .then().extract().statusCode();

        ArmCall call = assertGatedOnce(status);
        assertTrue(call.id().isExtension());
        assertEquals("microsoft.insights/diagnosticSettings", call.id().typePath());
        assertEquals("Microsoft.Insights/diagnosticSettings", call.type(), "the declared spelling, not the client's");
    }

    @Test
    void monitorDataPlaneAtTheArmBaseDoesNotPassTheGate() {
        json().body("[]").post("/dataCollectionRules/dcr-gate/streams/Custom-Gate?api-version=2023-01-01");
        given().get("/v1/workspaces/ws-gate/query?query=x");

        assertNotGated();
    }

    // ── Data-plane stages at the ARM base, the metadata anti-route, and the rest ──

    @Test
    void metadataEndpointsDoNotPassTheGate() {
        given().get("/metadata/endpoints?api-version=2022-09-01");

        assertNotGated();
    }

    @Test
    void keyVaultAtTheArmBaseDoesNotPassTheGate() {
        given().get("/secrets/foo?api-version=7.4").then().statusCode(401);

        assertNotGated();
    }

    @Test
    void emailAtTheArmBaseDoesNotPassTheGate() {
        given().get("/emails/operations/op-gate?api-version=2023-03-31");

        assertNotGated();
    }

    @Test
    void storageDataPlaneDoesNotPassTheGate() {
        given().get("/devstoreaccount1/?comp=list").then().statusCode(200);
        given().header("Host", "acct.blob.core.windows.net").get("/?comp=list").then().statusCode(200);
        given().get("/devstoreaccount1-queue/?comp=list").then().statusCode(200);

        assertNotGated();
    }

    @Test
    void graphAndEntraDoNotPassTheGate() {
        given().get("/v1.0/servicePrincipals").then().statusCode(200);
        given().get("/common/v2.0/.well-known/openid-configuration");

        assertNotGated();
    }
}
