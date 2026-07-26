package io.floci.az.services.graph;

import io.floci.az.services.entra.EntraStore;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class GraphServiceHandlerTest {

    @Test
    void servicePrincipalsResolvesAppIdFromFilter() {
        given()
          .when().get("/v1.0/servicePrincipals?$filter=appId eq '11111111-1111-1111-1111-111111111111'")
          .then()
            .statusCode(200)
            .body("value[0].appId", is("11111111-1111-1111-1111-111111111111"))
            .body("value[0].id", not(emptyOrNullString()));
    }

    @Test
    void getMemberGroupsReturnsDevGroupForDevUser() {
        given()
          .contentType("application/json")
          .body("{\"securityEnabledOnly\": false}")
          .when().post("/v1.0/users/{id}/getMemberGroups", EntraStore.DEV_USER_OBJECT_ID)
          .then()
            .statusCode(200)
            .body("value", hasItem(EntraStore.DEV_GROUP_OBJECT_ID));
    }

    @Test
    void getMemberGroupsResolvesUserByUpnToo() {
        given()
          .contentType("application/json")
          .body("{\"securityEnabledOnly\": true}")
          .when().post("/v1.0/users/{upn}/getMemberGroups", EntraStore.DEV_USER_UPN)
          .then()
            .statusCode(200)
            .body("value", hasItem(EntraStore.DEV_GROUP_OBJECT_ID));
    }

    @Test
    void getMemberGroupsUnknownUserReturns404() {
        given()
          .contentType("application/json")
          .body("{}")
          .when().post("/v1.0/users/{id}/getMemberGroups", "no-such-user")
          .then()
            .statusCode(404)
            .body("error.code", is("Request_ResourceNotFound"));
    }

    @Test
    void addAndRemoveGroupMemberRoundTrips() {
        String memberId = "test-member-" + java.util.UUID.randomUUID();

        given()
          .contentType("application/json")
          .body("{\"@odata.id\": \"https://graph.microsoft.com/v1.0/directoryObjects/" + memberId + "\"}")
          .when().post("/v1.0/groups/{id}/members/$ref", EntraStore.DEV_GROUP_OBJECT_ID)
          .then()
            .statusCode(204);

        given()
          .contentType("application/json")
          .body("{}")
          .when().post("/v1.0/users/{id}/getMemberGroups", memberId)
          .then()
            .statusCode(404); // the member is a bare id with no User record, only a membership row

        given()
          .when().delete("/v1.0/groups/{groupId}/members/{memberId}/$ref",
                EntraStore.DEV_GROUP_OBJECT_ID, memberId)
          .then()
            .statusCode(204);
    }

    @Test
    void addMemberToUnknownGroupReturns404() {
        given()
          .contentType("application/json")
          .body("{\"@odata.id\": \"https://graph.microsoft.com/v1.0/directoryObjects/whoever\"}")
          .when().post("/v1.0/groups/{id}/members/$ref", "no-such-group")
          .then()
            .statusCode(404)
            .body("error.code", is("Request_ResourceNotFound"));
    }
}
