package io.floci.az.services.graph;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/** Secrets minted by {@code addPassword} are what the token endpoint checks under {@code validate-client-credentials}. */
@QuarkusTest
@TestProfile(GraphApplicationsCredentialTest.Enforced.class)
@DisplayName("Microsoft Graph applications: minted secrets authenticate, within their dates, until removed")
class GraphApplicationsCredentialTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";

    public static class Enforced implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.entra.validate-client-credentials", "true");
        }
    }

    @Test
    void aMintedSecretAuthenticatesUntilRemovedAndOnlyWithinItsDates() {
        given().when().post("/_admin/reset").then().statusCode(204);
        String appId = given().contentType("application/json").body("{\"displayName\": \"ci\"}")
                .when().post("/v1.0/applications").then().statusCode(201).extract().path("appId");
        String objectId = given().when().get("/v1.0/applications(appId='" + appId + "')").then().statusCode(200).extract().path("id");

        String secret = addPassword(objectId, "{\"passwordCredential\": {\"displayName\": \"current\"}}").extract().path("secretText");
        String keyId = given().when().get("/v1.0/applications/" + objectId).then().extract().path("passwordCredentials[0].keyId");
        token(appId, secret).statusCode(200).body("access_token", notNullValue());
        token(appId, "not-the-secret").statusCode(401)
                .body("error", equalTo("invalid_client"))
                .body("error_codes", contains(7000215));

        // A secret past its endDateTime is refused with Azure's expired-keys error; one before its start is not yet a secret.
        String expired = addPassword(objectId,
                "{\"passwordCredential\": {\"startDateTime\": \"2020-01-01T00:00:00Z\", \"endDateTime\": \"2020-06-01T00:00:00Z\"}}")
                .extract().path("secretText");
        token(appId, expired).statusCode(401)
                .body("error", equalTo("invalid_client"))
                .body("error_codes", contains(7000222))
                .body("error_description", startsWith("AADSTS7000222: The provided client secret keys for app '" + appId + "' are expired."));
        String future = addPassword(objectId, "{\"passwordCredential\": {\"startDateTime\": \"2030-01-01T00:00:00Z\"}}")
                .extract().path("secretText");
        token(appId, future).statusCode(401).body("error_codes", contains(7000215));
        token(appId, secret).statusCode(200);

        // Once removed, the secret no longer authenticates.
        given().contentType("application/json").body("{\"keyId\": \"" + keyId + "\"}")
                .when().post("/v1.0/applications/" + objectId + "/removePassword").then().statusCode(204);
        token(appId, secret).statusCode(401).body("error_codes", contains(7000215));
    }

    private static ValidatableResponse addPassword(String objectId, String body) {
        return given().contentType("application/json").body(body)
                .when().post("/v1.0/applications/" + objectId + "/addPassword").then().statusCode(200);
    }

    private static ValidatableResponse token(String appId, String secret) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "client_credentials").formParam("client_id", appId)
                .formParam("client_secret", secret).formParam("scope", "https://management.azure.com/.default")
                .when().post("/" + TENANT + "/oauth2/v2.0/token").then();
    }
}
