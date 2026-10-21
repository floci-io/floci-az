package io.floci.az.services.monitor;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class WorkspaceSharedKeysTest {

    private static final String WS =
        "/subscriptions/sub-wskeys/resourceGroups/rg-wskeys/providers/Microsoft.OperationalInsights/workspaces/";

    private String create(String name) {
        return given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(WS + name + "?api-version=2023-09-01")
            .then().statusCode(anyOf(is(200), is(201)))
            .extract().path("properties.customerId");
    }

    @Test
    void sharedKeysAndListKeysAnswerTheSameStableKeys() {
        create("logs");
        String primary = given().when().post(WS + "logs/sharedKeys?api-version=2020-08-01")
            .then().statusCode(200)
            .body("secondarySharedKey", not(equalTo(null)))
            .extract().path("primarySharedKey");

        given().when().post(WS + "logs/listKeys?api-version=2023-09-01")
            .then().statusCode(200)
            .body("primarySharedKey", equalTo(primary));
    }

    @Test
    void redeployingAWorkspaceKeepsItsCustomerIdAndKeys() {
        String customerId = create("logs-redeploy");
        String primary = given().when().post(WS + "logs-redeploy/sharedKeys?api-version=2020-08-01")
            .then().statusCode(200).extract().path("primarySharedKey");

        assertEquals(customerId, create("logs-redeploy"));
        given().when().post(WS + "logs-redeploy/sharedKeys?api-version=2020-08-01")
            .then().statusCode(200)
            .body("primarySharedKey", equalTo(primary));
    }

    @Test
    void keysOfAMissingWorkspaceAre404() {
        given().when().post(WS + "absent/sharedKeys?api-version=2020-08-01")
            .then().statusCode(404);
    }
}
