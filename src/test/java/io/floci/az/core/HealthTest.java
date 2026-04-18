package io.floci.az.core;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

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
    public void testReadyEndpoint() {
        given()
          .when().get("/ready")
          .then()
             .statusCode(200)
             .body("status", is("UP"));
    }
}
