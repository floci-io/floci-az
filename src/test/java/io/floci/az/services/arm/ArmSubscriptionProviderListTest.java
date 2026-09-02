package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;

/**
 * {@code ArmHandler.dispatch()} generalizes {@code subscriptions/{sub}/providers/{ns}/{type}}
 * (no {@code /resourceGroups/} segment) to delegate through the same {@code ArmProviderService}
 * lane used for resource-group-scoped provider routes, so a lane service's subscription-wide list
 * is reachable without ArmHandler special-casing each namespace. This must not silently answer an
 * empty-but-200 list for a lane service that resolves its resource group from the path — API
 * Management's {@code extractRg()} defaults to {@code "unknown"} when there is no
 * {@code /resourceGroups/} segment, so the handler must recognize the subscription-wide shape and
 * route to its own subscription-wide listing instead of {@code listServices(sub, "unknown")}.
 */
@QuarkusTest
@DisplayName("ArmHandler — subscription-wide provider-resource list delegates through the lane")
class ArmSubscriptionProviderListTest {

    private static final String SUB = "sub-subprov";
    private static final String RG = "rg-subprov";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void apiManagementSubscriptionWideListIncludesServicesFromEveryResourceGroup() {
        String servicePath = String.format(
            "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.ApiManagement/service/apim-subprov", SUB, RG);
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("location", "eastus", "sku", Map.of("name", "Developer", "capacity", 1),
                "properties", Map.of("publisherEmail", "a@b.com", "publisherName", "b")))
            .when().put(servicePath + "?api-version=2022-08-01")
            .then().statusCode(200);

        String listPath = String.format("/subscriptions/%s/providers/Microsoft.ApiManagement/service", SUB);
        given()
            .when().get(listPath + "?api-version=2022-08-01")
            .then().statusCode(200)
            .body("value.name", hasItem("apim-subprov"));
    }

    @Test
    void operationalInsightsSubscriptionWideListReachesMonitorHandler() {
        String workspacePath = String.format(
            "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.OperationalInsights/workspaces/law-subprov", SUB, RG);
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("location", "eastus", "properties", Map.of()))
            .when().put(workspacePath + "?api-version=2020-08-01")
            .then().statusCode(201);

        String listPath = String.format("/subscriptions/%s/providers/Microsoft.OperationalInsights/workspaces", SUB);
        given()
            .when().get(listPath + "?api-version=2020-08-01")
            .then().statusCode(200)
            .body("value.name", hasItem("law-subprov"));
    }

    @Test
    void unknownProviderNamespaceStillNotFound() {
        given()
            .when().get(String.format("/subscriptions/%s/providers/Microsoft.NotARealNamespace/things", SUB))
            .then().statusCode(404);
    }
}
