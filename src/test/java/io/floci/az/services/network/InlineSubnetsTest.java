package io.floci.az.services.network;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class InlineSubnetsTest {

    private static final String RG = "/subscriptions/sub-inlinesubnets/resourceGroups/rg-inlinesubnets";
    private static final String VNETS = RG + "/providers/Microsoft.Network/virtualNetworks/";
    private static final String API = "?api-version=2024-05-01";

    private static void putVnet(String name, String subnets) {
        given().contentType("application/json")
            .body("{\"location\":\"westeurope\",\"properties\":{\"addressSpace\":{\"addressPrefixes\":[\"10.40.0.0/16\"]}"
                + (subnets == null ? "" : ",\"subnets\":" + subnets) + "}}")
            .when().put(VNETS + name + API)
            .then().statusCode(200);
    }

    @Test
    void inlineSubnetsBecomeChildSubnets() {
        putVnet("vnet-a", "[{\"name\":\"apps\",\"properties\":{\"addressPrefix\":\"10.40.0.0/23\"}}]");

        given().when().get(VNETS + "vnet-a/subnets/apps" + API)
            .then().statusCode(200)
            .body("id", endsWith("/virtualNetworks/vnet-a/subnets/apps"))
            .body("type", equalTo("Microsoft.Network/virtualNetworks/subnets"))
            .body("properties.addressPrefix", equalTo("10.40.0.0/23"));
        given().when().get(VNETS + "vnet-a/subnets" + API)
            .then().statusCode(200).body("value.name", contains("apps"));
        given().when().get(VNETS + "vnet-a" + API)
            .then().statusCode(200).body("properties.subnets[0].id", endsWith("/vnet-a/subnets/apps"));
    }

    @Test
    void aVnetPutThatDropsASubnetRemovesIt() {
        putVnet("vnet-b", "[{\"name\":\"apps\",\"properties\":{\"addressPrefix\":\"10.40.0.0/23\"}},"
            + "{\"name\":\"db\",\"properties\":{\"addressPrefix\":\"10.40.2.0/24\"}}]");
        putVnet("vnet-b", "[{\"name\":\"apps\",\"properties\":{\"addressPrefix\":\"10.40.0.0/23\"}}]");

        given().when().get(VNETS + "vnet-b/subnets/db" + API).then().statusCode(404);
        given().when().get(VNETS + "vnet-b/subnets" + API)
            .then().statusCode(200).body("value.name", contains("apps"));
    }

    @Test
    void aSubnetWrittenOnItsOwnSurvivesAVnetPutThatEchoesTheVnet() {
        putVnet("vnet-c", null);
        given().contentType("application/json").body("{\"properties\":{\"addressPrefix\":\"10.40.4.0/24\"}}")
            .when().put(VNETS + "vnet-c/subnets/web" + API).then().statusCode(200);

        String echoed = given().when().get(VNETS + "vnet-c" + API)
            .then().statusCode(200).body("properties.subnets.name", contains("web"))
            .extract().body().jsonPath().prettify();
        given().contentType("application/json").body(echoed)
            .when().put(VNETS + "vnet-c" + API).then().statusCode(200);
        putVnet("vnet-c", null);

        given().when().get(VNETS + "vnet-c/subnets/web" + API).then().statusCode(200);
        given().when().delete(VNETS + "vnet-c/subnets/web" + API).then().statusCode(200);
        given().when().get(VNETS + "vnet-c" + API)
            .then().statusCode(200).body("properties.subnets.name", containsInAnyOrder());
    }
}
