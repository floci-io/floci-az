package io.floci.az.services.graph;

import io.floci.az.services.entra.EntraStore;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@DisplayName("Microsoft Graph applications: registrations, lookups by id and appId, client secrets")
class GraphApplicationsTest {

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void seededDevAppIsReadableByAppIdObjectIdAndFilter() {
        given().when().get("/v1.0/applications(appId='" + EntraStore.DEV_CLIENT_ID + "')")
                .then().statusCode(200)
                .body("'@odata.context'", equalTo("https://graph.microsoft.com/v1.0/$metadata#applications/$entity"))
                .body("id", equalTo(EntraStore.DEV_APP_OBJECT_ID))
                .body("appId", equalTo(EntraStore.DEV_CLIENT_ID))
                .body("displayName", equalTo("floci-az dev app"))
                .body("passwordCredentials", hasSize(1))
                .body("passwordCredentials[0].hint", equalTo("flo"))
                .body("passwordCredentials[0].keyId", equalTo("dev"))
                .body("passwordCredentials[0].secretText", nullValue())
                .body("keyCredentials", hasSize(0));
        given().when().get("/v1.0/applications/" + EntraStore.DEV_APP_OBJECT_ID)
                .then().statusCode(200).body("appId", equalTo(EntraStore.DEV_CLIENT_ID));
        given().when().get("/v1.0/applications?$filter=appId eq '" + EntraStore.DEV_CLIENT_ID + "'")
                .then().statusCode(200)
                .body("'@odata.context'", equalTo("https://graph.microsoft.com/v1.0/$metadata#applications"))
                .body("value", hasSize(1))
                .body("value[0].id", equalTo(EntraStore.DEV_APP_OBJECT_ID));
        given().when().get("/v1.0/applications?$filter=startswith(displayName,'floci')")
                .then().statusCode(200).body("value.appId", hasItem(EntraStore.DEV_CLIENT_ID));
        given().when().get("/v1.0/applications?$filter=displayName eq 'nobody'")
                .then().statusCode(200).body("value", hasSize(0));
        given().when().get("/v1.0/applications(appId='00000000-0000-0000-0000-00000000dead')")
                .then().statusCode(404)
                .body("error.code", equalTo("Request_ResourceNotFound"))
                .body("error.message", startsWith("Resource '00000000-0000-0000-0000-00000000dead' does not exist"));
    }

    @Test
    void createsAnAppAddsAndRemovesSecretsAndIssuesTokensForIt() {
        String appId = given().contentType("application/json").body("{\"displayName\": \"connector\"}")
                .when().post("/v1.0/applications")
                .then().statusCode(201)
                .body("displayName", equalTo("connector"))
                .body("appId", notNullValue())
                .body("passwordCredentials", hasSize(0))
                .extract().path("appId");
        String objectId = given().when().get("/v1.0/applications(appId='" + appId + "')").then().statusCode(200).extract().path("id");

        String keyId = given().contentType("application/json")
                .body("{\"passwordCredential\": {\"displayName\": \"ci\", \"endDateTime\": \"2030-01-01T00:00:00Z\"}}")
                .when().post("/v1.0/applications/" + objectId + "/addPassword")
                .then().statusCode(200)
                .body("'@odata.context'", equalTo("https://graph.microsoft.com/v1.0/$metadata#microsoft.graph.passwordCredential"))
                .body("secretText", notNullValue())
                .body("hint", notNullValue())
                .body("displayName", equalTo("ci"))
                .body("endDateTime", equalTo("2030-01-01T00:00:00Z"))
                .body("startDateTime", notNullValue())
                .extract().path("keyId");
        given().when().get("/v1.0/applications/" + objectId)
                .then().statusCode(200)
                .body("passwordCredentials", hasSize(1))
                .body("passwordCredentials[0].keyId", equalTo(keyId))
                .body("passwordCredentials[0].secretText", nullValue())
                .body("passwordCredentials[0].endDateTime", equalTo("2030-01-01T00:00:00Z"));

        // The registration is a real principal: the token endpoint issues app tokens for it and
        // service-principal discovery knows its appId.
        given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "client_credentials").formParam("client_id", appId)
                .formParam("client_secret", "whatever").formParam("scope", "https://management.azure.com/.default")
                .when().post("/00000000-0000-0000-0000-000000000002/oauth2/v2.0/token")
                .then().statusCode(200).body("access_token", notNullValue());

        given().contentType("application/json").body("{\"displayName\": \"connector v2\"}")
                .when().patch("/v1.0/applications/" + objectId).then().statusCode(204);
        given().when().get("/v1.0/applications/" + objectId).then().statusCode(200).body("displayName", equalTo("connector v2"));

        given().contentType("application/json").body("{\"keyId\": \"" + keyId + "\"}")
                .when().post("/v1.0/applications/" + objectId + "/removePassword").then().statusCode(204);
        given().when().get("/v1.0/applications/" + objectId).then().statusCode(200).body("passwordCredentials", hasSize(0));
        given().contentType("application/json").body("{\"keyId\": \"" + keyId + "\"}")
                .when().post("/v1.0/applications/" + objectId + "/removePassword").then().statusCode(404);

        given().when().delete("/v1.0/applications/" + objectId).then().statusCode(204);
        given().when().get("/v1.0/applications/" + objectId).then().statusCode(404);
        given().when().get("/v1.0/applications").then().statusCode(200).body("value.appId", not(hasItem(appId)));
    }

    @Test
    void rejectsBadRequests() {
        given().contentType("application/json").body("{}")
                .when().post("/v1.0/applications").then().statusCode(400)
                .body("error.code", equalTo("Request_BadRequest"));
        given().contentType("application/json").body("{\"passwordCredential\": {\"endDateTime\": \"soon\"}}")
                .when().post("/v1.0/applications/" + EntraStore.DEV_APP_OBJECT_ID + "/addPassword")
                .then().statusCode(400);
        given().when().put("/v1.0/applications/" + EntraStore.DEV_APP_OBJECT_ID).then().statusCode(405);
        given().when().post("/v1.0/applications/" + EntraStore.DEV_APP_OBJECT_ID + "/frobnicate").then().statusCode(400);
    }
}
