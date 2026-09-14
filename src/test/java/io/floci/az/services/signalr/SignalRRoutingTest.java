package io.floci.az.services.signalr;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class SignalRRoutingTest {
    @Test
    void requiresTokenOnBothSdkAndAccountEndpoints() {
        given().post("/client/negotiate?hub=test").then().statusCode(401).body("error.code", equalTo("Unauthorized"));
        given().post("/account-signalr/client/negotiate?hub=test").then().statusCode(401);
    }

    @Test
    void reportsUnsupportedRestOperations() {
        given().get("/account-signalr/api/hubs/test").then().statusCode(501).body("error.code", equalTo("NotImplemented"));
    }

    @Test
    void doesNotClaimBlobAccountsNamedClientOrServer() {
        given().get("/client?restype=service&comp=properties").then().statusCode(200).contentType("application/xml");
        given().get("/server?restype=service&comp=properties").then().statusCode(200).contentType("application/xml");
    }
}
