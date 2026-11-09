package io.floci.az.core;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * floci-az routes ARM, Entra and Graph by path, so a client that addresses Azure's own hosts reaches them, and the
 * URLs floci-az answers with stay on the host the client used. This is what azd depends on, since it cannot be pointed
 * at a custom cloud.
 */
@QuarkusTest
class PublicHostRoutingTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";

    @Test
    void armAnswersOnItsPublicHost() {
        given().header("Host", "management.azure.com")
            .when().get("/subscriptions?api-version=2022-12-01")
            .then().statusCode(200).body("value", notNullValue());
        given().header("Host", "management.core.windows.net")
            .when().get("/tenants?api-version=2022-12-01")
            .then().statusCode(200).body("value", notNullValue());
    }

    @Test
    void entraAnswersOnItsPublicHostWithThatHostInItsDocument() {
        given().header("Host", "login.microsoftonline.com")
            .when().get("/" + TENANT + "/v2.0/.well-known/openid-configuration")
            .then().statusCode(200)
            .body("token_endpoint", containsString("login.microsoftonline.com/" + TENANT))
            .body("issuer", startsWith("http"));
    }

    @Test
    void graphAnswersOnItsPublicHost() {
        given().header("Host", "graph.microsoft.com")
            .when().get("/v1.0/groups")
            .then().statusCode(200).body("value", notNullValue());
    }
}
