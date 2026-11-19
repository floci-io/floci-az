package io.floci.az.services.iothub;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * The provider's read-only views: its operations, and a hub's valid SKUs, quota metrics and registry statistics, in
 * the shapes Azure answered the az CLI with (2023-06-30-preview recording).
 */
@QuarkusTest
@DisplayName("Microsoft.Devices: operations, and a hub's SKUs, quota metrics and statistics")
class IotHubViewsTest {

    private static final String SUB = "10700000-0000-4000-8000-000000000003";
    private static final String API = "?api-version=2023-06-30";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String hub(String name) {
        return "/subscriptions/" + SUB + "/resourceGroups/rg/providers/Microsoft.Devices/IotHubs/" + name;
    }

    private static void createHub(String name, String sku, int capacity) {
        given().contentType("application/json")
            .body("{\"location\": \"westus2\", \"sku\": {\"name\": \"" + sku + "\", \"capacity\": " + capacity + "}}")
            .when().put(hub(name) + API).then().statusCode(201);
    }

    @Test
    void operationsListTheIotHubOperations() {
        given().when().get("/providers/Microsoft.Devices/operations" + API).then().statusCode(200)
            .body("value", hasSize(25))
            .body("value.name", hasItem("Microsoft.Devices/iotHubs/eventHubEndpoints/consumerGroups/Write"))
            .body("value.name", not(hasItem("Microsoft.Devices/provisioningServices/Read")))
            .body("value[0].display.provider", equalTo("Microsoft Devices"))
            .body("value[0].display.resource", equalTo("IotHubs"))
            .body("nextLink", nullValue());
    }

    @Test
    void anS1HubListsTheStandardSkusAsAzureAnswered() {
        createHub("hub-skus", "S1", 1);

        given().when().get(hub("hub-skus") + "/skus" + API).then().statusCode(200)
            .body("value.sku.name", contains("S1", "S2", "S3"))
            .body("value.sku.tier", contains("Standard", "Standard", "Standard"))
            .body("value.resourceType", contains("Microsoft.Devices/IotHubs", "Microsoft.Devices/IotHubs",
                "Microsoft.Devices/IotHubs"))
            .body("value.capacity.maximum", contains(200, 200, 10))
            .body("value[0].capacity.minimum", equalTo(1))
            .body("value[0].capacity.default", equalTo(1))
            .body("value[0].capacity.scaleType", equalTo("Manual"));
    }

    @Test
    void basicAndFreeHubsListTheirOwnTier() {
        createHub("hub-basic", "B2", 1);
        createHub("hub-free", "F1", 1);

        given().when().get(hub("hub-basic") + "/skus" + API).then().statusCode(200)
            .body("value.sku.name", contains("B1", "B2", "B3", "S1", "S2", "S3"))
            .body("value.sku.tier", contains("Basic", "Basic", "Basic", "Standard", "Standard", "Standard"));
        given().when().get(hub("hub-free") + "/skus" + API).then().statusCode(200)
            .body("value.sku.name", contains("F1"))
            .body("value[0].capacity.maximum", equalTo(1))
            .body("value[0].capacity.scaleType", equalTo("None"));
    }

    @Test
    void aHubChangesSkuOnlyWhereItsSkusListSays() {
        createHub("hub-move-std", "S1", 1);
        createHub("hub-move-basic", "B1", 1);
        createHub("hub-move-free", "F1", 1);

        for (String[] refused : new String[][] {{"hub-move-std", "B1"}, {"hub-move-std", "F1"},
                {"hub-move-basic", "F1"}, {"hub-move-free", "S1"}, {"hub-move-free", "B1"}}) {
            putSku(refused[0], refused[1]).then().statusCode(400)
                .body("error.code", equalTo("InvalidRequestContent"));
        }
        given().when().get(hub("hub-move-std") + API).then().body("sku.name", equalTo("S1"));

        putSku("hub-move-std", "S2").then().statusCode(201).body("sku.name", equalTo("S2"));
        putSku("hub-move-basic", "B2").then().statusCode(201);
        putSku("hub-move-basic", "S1").then().statusCode(201).body("sku.tier", equalTo("Standard"));
        // Standard now, so no way back to Basic.
        putSku("hub-move-basic", "B1").then().statusCode(400);
    }

    private static Response putSku(String name, String sku) {
        return given().contentType("application/json")
            .body("{\"location\": \"westus2\", \"sku\": {\"name\": \"" + sku + "\", \"capacity\": 1}}")
            .when().put(hub(name) + API);
    }

    @Test
    void quotaMetricsScaleWithTheHubsUnits() {
        createHub("hub-quota", "S1", 1);
        createHub("hub-quota-s2", "S2", 3);
        createHub("hub-quota-free", "F1", 1);

        given().when().get(hub("hub-quota") + "/quotaMetrics" + API).then().statusCode(200)
            .body("value.name", contains("TotalMessages", "TotalDeviceCount"))
            .body("value.currentValue", contains(0, 0))
            .body("value.maxValue", contains(400000, 1000000));
        given().when().get(hub("hub-quota-s2") + "/quotaMetrics" + API).then()
            .body("value[0].maxValue", equalTo(18000000));
        given().when().get(hub("hub-quota-free") + "/quotaMetrics" + API).then()
            .body("value.maxValue", contains(8000, 500));
    }

    @Test
    void statisticsCountNoDevices() {
        createHub("hub-stats", "S1", 1);

        given().when().get(hub("hub-stats") + "/IotHubStats" + API).then().statusCode(200)
            .body("totalDeviceCount", equalTo(0))
            .body("enabledDeviceCount", equalTo(0))
            .body("disabledDeviceCount", equalTo(0));
    }

    @Test
    void theViewsOfAMissingHubAreNotFoundAndOnlyRead() {
        for (String view : new String[] {"skus", "quotaMetrics", "IotHubStats"}) {
            given().when().get(hub("missing-hub") + "/" + view + API).then().statusCode(404);
        }
        createHub("hub-verbs", "S1", 1);
        given().contentType("application/json").body("{}").when().put(hub("hub-verbs") + "/skus" + API)
            .then().statusCode(405);
        given().when().post("/providers/Microsoft.Devices/operations" + API).then().statusCode(405);
    }
}
