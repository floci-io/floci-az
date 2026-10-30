package io.floci.az.services.graph;

import io.floci.az.services.entra.EntraStore;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@DisplayName("Microsoft Graph applications: owners, the caller as the first one, add and remove by reference")
class GraphApplicationOwnersTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String DIRECTORY_OBJECTS = "https://graph.microsoft.com/v1.0/directoryObjects/";

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void theCallerOwnsWhatItRegistersAndOwnersAreAddedAndRemovedByReference() {
        // An app token for the dev app: its service principal registers the new app and owns it.
        String token = given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "client_credentials").formParam("client_id", EntraStore.DEV_CLIENT_ID)
                .formParam("client_secret", EntraStore.DEV_CLIENT_SECRET).formParam("scope", "https://graph.microsoft.com/.default")
                .when().post("/" + TENANT + "/oauth2/v2.0/token").then().statusCode(200).extract().path("access_token");
        String objectId = given().header("Authorization", "Bearer " + token).contentType("application/json")
                .body("{\"displayName\": \"owned\"}")
                .when().post("/v1.0/applications").then().statusCode(201).extract().path("id");
        String appId = given().when().get("/v1.0/applications/" + objectId).then().extract().path("appId");

        given().when().get("/v1.0/applications/" + objectId + "/owners")
                .then().statusCode(200)
                .body("'@odata.context'", equalTo("https://graph.microsoft.com/v1.0/$metadata#directoryObjects"))
                .body("value", hasSize(1))
                .body("value[0].'@odata.type'", equalTo("#microsoft.graph.servicePrincipal"))
                .body("value[0].id", equalTo(EntraStore.DEV_SP_OBJECT_ID))
                .body("value[0].appId", equalTo(EntraStore.DEV_CLIENT_ID));
        given().when().get("/v1.0/applications(appId='" + appId + "')/owners")
                .then().statusCode(200).body("value", hasSize(1));

        // The provider's pattern: drop the caller, add the owners it manages.
        given().when().delete("/v1.0/applications/" + objectId + "/owners/" + EntraStore.DEV_SP_OBJECT_ID + "/$ref")
                .then().statusCode(204);
        given().when().get("/v1.0/applications/" + objectId + "/owners").then().statusCode(200).body("value", hasSize(0));
        given().contentType("application/json").body("{\"@odata.id\": \"" + DIRECTORY_OBJECTS + EntraStore.DEV_USER_OBJECT_ID + "\"}")
                .when().post("/v1.0/applications/" + objectId + "/owners/$ref").then().statusCode(204);
        given().when().get("/v1.0/applications/" + objectId + "/owners")
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].'@odata.type'", equalTo("#microsoft.graph.user"))
                .body("value[0].id", equalTo(EntraStore.DEV_USER_OBJECT_ID))
                .body("value[0].userPrincipalName", equalTo(EntraStore.DEV_USER_UPN));

        // Already an owner, an unknown principal, an owner that is not one, and a segment after the action.
        given().contentType("application/json").body("{\"@odata.id\": \"" + DIRECTORY_OBJECTS + EntraStore.DEV_USER_OBJECT_ID + "\"}")
                .when().post("/v1.0/applications/" + objectId + "/owners/$ref").then().statusCode(400)
                .body("error.code", equalTo("Request_BadRequest"));
        given().contentType("application/json").body("{\"@odata.id\": \"" + DIRECTORY_OBJECTS + "00000000-0000-0000-0000-00000000dead\"}")
                .when().post("/v1.0/applications/" + objectId + "/owners/$ref").then().statusCode(404)
                .body("error.code", equalTo("Request_ResourceNotFound"));
        given().when().delete("/v1.0/applications/" + objectId + "/owners/" + EntraStore.DEV_SP_OBJECT_ID + "/$ref")
                .then().statusCode(404);
        given().contentType("application/json").body("{}")
                .when().post("/v1.0/applications/" + objectId + "/owners/$ref/extra").then().statusCode(400);
        given().when().get("/v1.0/applications/" + objectId + "/owners").then().statusCode(200).body("value", hasSize(1));

        // Without a token nobody is named, so a registration starts with no owner.
        String anonymous = given().contentType("application/json").body("{\"displayName\": \"unowned\"}")
                .when().post("/v1.0/applications").then().statusCode(201).extract().path("id");
        given().when().get("/v1.0/applications/" + anonymous + "/owners").then().statusCode(200).body("value", hasSize(0));
    }
}
