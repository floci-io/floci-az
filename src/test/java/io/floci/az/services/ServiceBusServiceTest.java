package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
public class ServiceBusServiceTest {

    @Test
    void testExistingPathRouting() {
        given()
            .when().get("/devstoreaccount1-servicebus/$namespaceinfo")
            .then()
            .statusCode(200)
            .contentType("application/atom+xml")
            .body(containsString("<NamespaceInfo"));
    }

    @Test
    void testRootLevelPathRouting() {
        // Test routing based on spec path segment
        given()
            .when().get("/$namespaceinfo")
            .then()
            .statusCode(200)
            .contentType("application/atom+xml")
            .body(containsString("<NamespaceInfo"));
    }

    @Test
    void testRootLevelAtomPubHeaderRouting() {
        // Test routing based on Content-Type/Accept header
        given()
            .accept("application/atom+xml")
            .when().get("/some-custom-spec-path")
            .then()
            .statusCode(404) // Should route to ServiceBusHandler and return AtomPub 404 (not foundAtom)
            .contentType("application/atom+xml");
    }

    @Test
    void testHostBasedRouting() {
        // Test routing based on Host header
        given()
            .header("Host", "devstoreaccount1.servicebus.windows.net")
            .when().get("/$namespaceinfo")
            .then()
            .statusCode(200)
            .contentType("application/atom+xml")
            .body(containsString("<NamespaceInfo"));
    }
}
