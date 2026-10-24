package io.floci.az.services.network;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** ARM resolves a provider namespace however a client spells it, so Network must too. */
@QuarkusTest
class NetworkNamespaceCaseTest {

    private static final String API = "?api-version=2024-05-01";
    private static final String RG_PATH = "/subscriptions/sub-net-case/resourceGroups/rg-net-case/providers/";
    private static final String CANONICAL = RG_PATH + "Microsoft.Network";
    private static final String LOWERCASE = RG_PATH + "microsoft.network";
    private static final String VNET_BODY = """
            {"location": "eastus", "properties": {"addressSpace": {"addressPrefixes": ["10.20.0.0/16"]}}}
            """;

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void aVirtualNetworkCreatedThroughALowercaseNamespaceIsTheOneTheCanonicalNamespaceReads() {
        String id = given().contentType("application/json").body(VNET_BODY)
                .when().put(LOWERCASE + "/virtualNetworks/vnet-case" + API)
                .then().statusCode(200)
                .body("name", equalTo("vnet-case"))
                .body("type", equalTo("Microsoft.Network/virtualNetworks"))
                .extract().path("id");

        given().when().get(CANONICAL + "/virtualNetworks/vnet-case" + API)
                .then().statusCode(200)
                .body("id", equalTo(id))
                .body("properties.addressSpace.addressPrefixes[0]", equalTo("10.20.0.0/16"));

        given().when().get(LOWERCASE + "/virtualnetworks/vnet-case" + API)
                .then().statusCode(200)
                .body("id", equalTo(id));

        given().when().get(LOWERCASE + "/virtualNetworks" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].id", equalTo(id));
    }

    @Test
    void aVirtualNetworkCreatedThroughTheCanonicalNamespaceIsDeletedThroughALowercaseOne() {
        given().contentType("application/json").body(VNET_BODY)
                .when().put(CANONICAL + "/virtualNetworks/vnet-gone" + API)
                .then().statusCode(200);

        given().when().delete(LOWERCASE + "/virtualNetworks/vnet-gone" + API)
                .then().statusCode(200);

        given().when().get(CANONICAL + "/virtualNetworks/vnet-gone" + API)
                .then().statusCode(404);
    }
}
