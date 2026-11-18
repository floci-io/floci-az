package io.floci.az.services.monitor;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.hamcrest.Matchers.*;

/**
 * {@code DeletedWorkspaces_List} and the soft delete behind it: a workspace deleted without {@code force} stays listed
 * and recoverable, one deleted with {@code force} goes. azurerm lists deleted workspaces before every
 * {@code azurerm_log_analytics_workspace} create, so a 404 here fails the create.
 */
@QuarkusTest
@DisplayName("Log Analytics: deleted workspaces are listed and recoverable until a force delete")
class MonitorDeletedWorkspacesTest {

    private static final String API = "?api-version=2025-07-01";
    private static final String SUB = "/subscriptions/test-sub-deleted-ws";
    private static final String RG = SUB + "/resourceGroups/test-rg-deleted-ws";
    private static final String PROVIDER = "/providers/Microsoft.OperationalInsights";
    private static final String WORKSPACE = RG + PROVIDER + "/workspaces/deleted-ws";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void aSubscriptionListsNoDeletedWorkspaces() {
        given().when().get(SUB + PROVIDER + "/deletedWorkspaces" + API)
            .then().statusCode(200)
            .contentType(containsString("application/json"))
            .body("value", empty());
    }

    @Test
    void aResourceGroupListsNoDeletedWorkspaces() {
        given().when().get(RG + PROVIDER + "/deletedWorkspaces" + API)
            .then().statusCode(200)
            .body("value", empty());
    }

    @Test
    void thePathMatchesInAnyCase() {
        given().when().get(SUB + "/providers/microsoft.operationalinsights/deletedworkspaces" + API)
            .then().statusCode(200)
            .body("value", empty());
    }

    @Test
    void aWorkspaceDeletedWithoutForceIsListedAndNoLongerServed() {
        String customerId = createWorkspace("{\"location\":\"eastus\"}");
        given().when().delete(WORKSPACE + API).then().statusCode(200);

        given().when().get(WORKSPACE + API).then().statusCode(404);
        given().when().get(SUB + PROVIDER + "/deletedWorkspaces" + API)
            .then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].id", equalTo(WORKSPACE))
            .body("value[0].properties.customerId", equalTo(customerId));
        given().when().get(RG + PROVIDER + "/deletedWorkspaces" + API)
            .then().statusCode(200)
            .body("value", hasSize(1));
        given().when().get(SUB + "/resourceGroups/another-rg" + PROVIDER + "/deletedWorkspaces" + API)
            .then().statusCode(200)
            .body("value", empty());
    }

    @Test
    void recreatingADeletedWorkspaceRecoversItWithItsCustomerId() {
        String customerId = createWorkspace("{\"location\":\"eastus\"}");
        given().when().delete(WORKSPACE + API).then().statusCode(200);

        String recovered = createWorkspace("{\"location\":\"eastus\",\"tags\":{\"env\":\"dev\"}}");

        assertEquals(customerId, recovered);
        given().when().get(WORKSPACE + API).then().statusCode(200).body("tags.env", equalTo("dev"));
        given().when().get(SUB + PROVIDER + "/deletedWorkspaces" + API)
            .then().statusCode(200)
            .body("value", empty());
    }

    @Test
    void aForceDeleteRemovesTheWorkspaceAndReleasesItsName() {
        String customerId = createWorkspace("{\"location\":\"eastus\"}");
        given().when().delete(WORKSPACE + API + "&force=true").then().statusCode(200);

        given().when().get(SUB + PROVIDER + "/deletedWorkspaces" + API)
            .then().statusCode(200)
            .body("value", empty());
        assertNotEquals(customerId, createWorkspace("{\"location\":\"eastus\"}"));
    }

    private static String createWorkspace(String body) {
        return given().contentType("application/json").body(body)
            .when().put(WORKSPACE + API)
            .then().statusCode(anyOf(is(200), is(201)))
            .extract().path("properties.customerId");
    }

    @Test
    void onlyAGetIsServed() {
        given().contentType("application/json").body("{}")
            .when().post(SUB + PROVIDER + "/deletedWorkspaces" + API)
            .then().statusCode(405);
    }
}
