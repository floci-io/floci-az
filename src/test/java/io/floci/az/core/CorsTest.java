package io.floci.az.core;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * A browser SPA (msal-react) calling the Entra authorize/token endpoints from its own origin needs
 * a handled CORS preflight. Confirms the Vert.x-level CORS filter answers {@code OPTIONS} before the
 * request reaches {@link AzureRoutingFilter} — a pre-matching JAX-RS filter that could otherwise
 * misroute or reject a bodiless preflight before CORS headers are attached.
 */
@QuarkusTest
class CorsTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";

    @Test
    void preflightOnTokenEndpointReturnsCorsHeaders() {
        given()
          .header("Origin", "https://app.local")
          .header("Access-Control-Request-Method", "POST")
          .when().options("/{tenant}/oauth2/v2.0/token", TENANT)
          .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("https://app.local"))
            .header("Access-Control-Allow-Methods", not(equalTo(null)));
    }

    @Test
    void actualRequestCarriesAllowOriginHeader() {
        given()
          .header("Origin", "https://app.local")
          .when().get("/{tenant}/discovery/v2.0/keys", TENANT)
          .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("https://app.local"));
    }
}
