package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * A6 behavior change: ARM-plane providers now parse the request body strictly. A malformed /
 * non-deserializable JSON body returns the real ARM {@code 400 InvalidRequestContent} rather than
 * being silently swallowed by lenient parsing.
 *
 * <p>Contract confirmed from {@code az} CLI recordings
 * ({@code netappfiles/.../test_account_changekeyvault_fails.yaml}) and the reservations ARM error
 * enum: {@code 400 Bad Request}, body
 * {@code {"error":{"code":"InvalidRequestContent","message":"The request content was invalid and
 * could not be deserialized..."}}}. Matches the pre-existing ManagedIdentity precedent.</p>
 */
@QuarkusTest
@DisplayName("ARM providers — malformed body returns 400 InvalidRequestContent")
class ArmStrictBodyTest {

    private static final String RG = "/subscriptions/sub-a6/resourceGroups/rg-a6";
    private static final String MALFORMED = "{ this is not valid json";

    private void assertMalformedBodyRejected(String armPath, String apiVersion) {
        given()
            .contentType("application/json")
            .body(MALFORMED)
            .when().put(RG + armPath + "?api-version=" + apiVersion)
            .then().statusCode(400)
            .body(containsString("InvalidRequestContent"))
            .body(containsString("could not be deserialized"));
    }

    @Test
    void networkRejectsMalformedBody() {
        assertMalformedBodyRejected("/providers/Microsoft.Network/virtualNetworks/vnet-a6", "2023-09-01");
    }

    @Test
    void apiManagementRejectsMalformedBody() {
        assertMalformedBodyRejected("/providers/Microsoft.ApiManagement/service/apim-a6", "2022-08-01");
    }

    @Test
    void monitorWorkspaceRejectsMalformedBody() {
        assertMalformedBodyRejected("/providers/Microsoft.OperationalInsights/workspaces/law-a6", "2022-10-01");
    }

    @Test
    void eventGridRejectsMalformedBody() {
        // Also exercises the A6 lane move: Event Grid's control plane now flows through ArmHandler.
        assertMalformedBodyRejected("/providers/Microsoft.EventGrid/topics/topic-a6", "2022-06-15");
    }

    /** An empty body is NOT a deserialization failure — parseBodyStrict yields an empty map. */
    @Test
    void emptyBodyIsNotRejectedAsMalformed() {
        given()
            .contentType("application/json")
            .when().put(RG + "/providers/Microsoft.EventGrid/topics/topic-empty?api-version=2022-06-15")
            .then().statusCode(org.hamcrest.Matchers.not(400));
    }
}
