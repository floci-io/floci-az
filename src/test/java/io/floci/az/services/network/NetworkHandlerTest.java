package io.floci.az.services.network;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@DisplayName("NetworkHandler - ARM Virtual Network compatibility")
class NetworkHandlerTest {

    private static final String SUB = "test-sub-net";
    private static final String RG = "test-rg-net";
    private static final String API = "?api-version=2024-05-01";
    private static final String BASE =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.Network";

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void virtualNetworkLifecycleAndSubnetListing() {
        String vnetBody = """
                {
                  "location": "eastus",
                  "tags": {"env": "test"},
                  "properties": {
                    "addressSpace": {
                      "addressPrefixes": ["10.10.0.0/16"]
                    }
                  }
                }
                """;

        given().contentType("application/json").body(vnetBody)
                .when().put(BASE + "/virtualNetworks/vnet1" + API)
                .then().statusCode(200)
                .body("name", equalTo("vnet1"))
                .body("type", equalTo("Microsoft.Network/virtualNetworks"))
                .body("location", equalTo("eastus"))
                .body("tags.env", equalTo("test"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.addressSpace.addressPrefixes", hasItem("10.10.0.0/16"));

        given().when().get(BASE + "/virtualNetworks" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("vnet1"));

        String subnetBody = """
                {
                  "properties": {
                    "addressPrefix": "10.10.1.0/24"
                  }
                }
                """;

        given().contentType("application/json").body(subnetBody)
                .when().put(BASE + "/virtualNetworks/vnet1/subnets/default" + API)
                .then().statusCode(200)
                .body("name", equalTo("default"))
                .body("type", equalTo("Microsoft.Network/virtualNetworks/subnets"))
                .body("properties.addressPrefix", equalTo("10.10.1.0/24"))
                .body("properties.provisioningState", equalTo("Succeeded"));

        given().when().get(BASE + "/virtualNetworks/vnet1/subnets" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("default"));

        given().when().delete(BASE + "/virtualNetworks/vnet1" + API)
                .then().statusCode(200);

        given().when().get(BASE + "/virtualNetworks/vnet1" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void networkInterfaceSynthesizesPrivateIpForVmCompatibility() {
        String nicBody = """
                {
                  "location": "eastus",
                  "properties": {
                    "ipConfigurations": [
                      {
                        "name": "ipconfig1",
                        "properties": {
                          "subnet": {
                            "id": "/subscriptions/test-sub-net/resourceGroups/test-rg-net/providers/Microsoft.Network/virtualNetworks/vnet1/subnets/default"
                          }
                        }
                      }
                    ]
                  }
                }
                """;

        given().contentType("application/json").body(nicBody)
                .when().put(BASE + "/networkInterfaces/nic1" + API)
                .then().statusCode(200)
                .body("name", equalTo("nic1"))
                .body("type", equalTo("Microsoft.Network/networkInterfaces"))
                .body("properties.ipConfigurations[0].properties.privateIPAddress", equalTo("10.0.0.4"))
                .body("properties.ipConfigurations[0].properties.privateIPAllocationMethod", equalTo("Dynamic"))
                .body("properties.ipConfigurations[0].properties.primary", equalTo(true))
                .body("properties.ipConfigurations[0].properties.provisioningState", equalTo("Succeeded"));
    }
}
