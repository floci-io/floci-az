package io.floci.az.services.network;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class NetworkUpdateTagsTest {

    private static final String RG = "/subscriptions/sub-net-tags/resourceGroups/rg-net-tags";
    private static final String API = "?api-version=2023-09-01";

    @Test
    void patchReplacesTheTagsAndKeepsEverythingElse() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(RG + "?api-version=2021-04-01").then().statusCode(anyOf(is(200), is(201)));
        String vnet = RG + "/providers/Microsoft.Network/virtualNetworks/vnet-tags" + API;
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"tags\":{\"a\":\"1\",\"b\":\"2\"},"
                + "\"properties\":{\"addressSpace\":{\"addressPrefixes\":[\"10.1.0.0/16\"]}}}")
            .when().put(vnet).then().statusCode(anyOf(is(200), is(201)));

        given().contentType("application/json").body("{\"tags\":{\"tofu-estate\":\"e1\"}}")
            .when().patch(vnet)
            .then().statusCode(200)
            .body("tags", equalTo(Map.of("tofu-estate", "e1")))
            .body("location", equalTo("eastus"))
            .body("properties.addressSpace.addressPrefixes[0]", equalTo("10.1.0.0/16"));

        given().when().get(vnet)
            .then().statusCode(200)
            .body("tags", equalTo(Map.of("tofu-estate", "e1")));

        given().contentType("application/json").body("{\"tags\":{}}")
            .when().patch(vnet)
            .then().statusCode(200)
            .body("tags", nullValue());
    }

    @Test
    void patchOnAPublicIpPrefixWorksAndAMissingResourceIsNotFound() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(RG + "?api-version=2021-04-01").then().statusCode(anyOf(is(200), is(201)));
        String prefix = RG + "/providers/Microsoft.Network/publicIPPrefixes/prefix-tags" + API;
        given().contentType("application/json").body("{\"location\":\"eastus\",\"properties\":{\"prefixLength\":28}}")
            .when().put(prefix).then().statusCode(anyOf(is(200), is(201)));

        given().contentType("application/json").body("{\"tags\":{\"env\":\"dev\"}}")
            .when().patch(prefix)
            .then().statusCode(200)
            .body("tags", equalTo(Map.of("env", "dev")));

        given().contentType("application/json").body("{\"tags\":{\"env\":\"dev\"}}")
            .when().patch(RG + "/providers/Microsoft.Network/networkInterfaces/missing-nic" + API)
            .then().statusCode(404);
    }
}
