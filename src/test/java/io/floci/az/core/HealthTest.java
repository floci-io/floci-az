package io.floci.az.core;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
public class HealthTest {

    @Test
    public void testHealthEndpoint() {
        given()
          .when().get("/health")
          .then()
             .statusCode(200)
             .body("status", is("UP"));
    }

    @Test
    public void testFlociHealthEndpoint() {
        given()
          .when().get("/_floci/health")
          .then()
             .statusCode(200)
             .body("status", is("UP"))
             .body("edition", is("floci-az-always-free"));
    }

    @Test
    public void testReadyEndpoint() {
        given()
          .when().get("/ready")
          .then()
             .statusCode(200)
             .body("status", is("UP"));
    }

    @Test
    public void testTlsCertWhenDisabledReturnsActionableError() {
        // TLS is disabled by default in tests — the hint must tell the user how to enable it
        // (this is what azurerm/Terraform users hit when metadata discovery fails over HTTPS).
        given()
          .when().get("/_floci/tls-cert")
          .then()
             .statusCode(404)
             .body("tlsEnabled", is(false))
             .body("message", containsString("FLOCI_AZ_TLS_ENABLED=true"));
    }
}
