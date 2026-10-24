package io.floci.az.services.monitor;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/** ARM resolves a provider namespace and resource type however a client spells them, so Monitor must too. */
@QuarkusTest
class MonitorNamespaceCaseTest {

    private static final String API = "?api-version=2023-09-01";
    private static final String RG_PATH = "/subscriptions/sub-mon-case/resourceGroups/rg-mon-case/providers/";
    private static final String CANONICAL = RG_PATH + "Microsoft.OperationalInsights/workspaces/";
    private static final String LOWERCASE = RG_PATH + "microsoft.operationalinsights/workspaces/";

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void aWorkspaceCreatedThroughALowercaseNamespaceIsTheOneTheCanonicalNamespaceReads() {
        String customerId = given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .when().put(LOWERCASE + "logs" + API)
                .then().statusCode(anyOf(is(200), is(201)))
                .body("name", equalTo("logs"))
                .extract().path("properties.customerId");

        given().when().get(CANONICAL + "logs" + API)
                .then().statusCode(200)
                .body("properties.customerId", equalTo(customerId));

        given().when().get(RG_PATH + "MICROSOFT.OPERATIONALINSIGHTS/WORKSPACES/logs" + API)
                .then().statusCode(200)
                .body("properties.customerId", equalTo(customerId));
    }

    @Test
    void aDataCollectionEndpointAnswersThroughALowercaseNamespace() {
        String endpoint = RG_PATH + "microsoft.insights/datacollectionendpoints/dce-case";
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .when().put(endpoint + "?api-version=2023-03-11")
                .then().statusCode(anyOf(is(200), is(201)))
                .body("name", equalTo("dce-case"));

        given().when().get(RG_PATH + "Microsoft.Insights/dataCollectionEndpoints/dce-case?api-version=2023-03-11")
                .then().statusCode(200)
                .body("name", equalTo("dce-case"));
    }
}
