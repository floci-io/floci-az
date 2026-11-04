package io.floci.az.services.monitor;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * {@code DeletedWorkspaces_List}: azurerm lists deleted workspaces before every
 * {@code azurerm_log_analytics_workspace} create, so a 404 here fails the create.
 */
@QuarkusTest
@DisplayName("Log Analytics: the deleted workspaces list answers, and is empty")
class MonitorDeletedWorkspacesTest {

    private static final String API = "?api-version=2025-07-01";
    private static final String SUB = "/subscriptions/test-sub-deleted-ws";
    private static final String RG = SUB + "/resourceGroups/test-rg-deleted-ws";
    private static final String PROVIDER = "/providers/Microsoft.OperationalInsights";

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
    void aWorkspaceIsNotListedAfterItsDelete() {
        String workspace = RG + PROVIDER + "/workspaces/deleted-ws";
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(workspace + API)
            .then().statusCode(anyOf(is(200), is(201)));
        given().when().delete(workspace + API).then().statusCode(anyOf(is(200), is(202), is(204)));

        given().when().get(SUB + PROVIDER + "/deletedWorkspaces" + API)
            .then().statusCode(200)
            .body("value", empty());
    }

    @Test
    void onlyAGetIsServed() {
        given().contentType("application/json").body("{}")
            .when().post(SUB + PROVIDER + "/deletedWorkspaces" + API)
            .then().statusCode(405);
    }
}
