package io.floci.az.services.network;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies that {@code floci-az.services.network.enabled=false} disables Microsoft.Network
 * (VNet/DNS) while the rest of the ARM management plane keeps working.
 */
@QuarkusTest
@TestProfile(NetworkDisabledTest.DisabledProfile.class)
@DisplayName("Network disabled — Microsoft.Network gated off, ARM still works")
@SuppressWarnings("unused")
class NetworkDisabledTest {

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.network.enabled", "false");
        }
    }

    private static final String SUB  = "test-sub-netoff";
    private static final String RG   = "test-rg-netoff";
    private static final String BASE = "/subscriptions/" + SUB + "/resourceGroups/" + RG;
    private static final String API  = "?api-version=2024-05-01";

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    @DisplayName("PUT virtualNetwork returns 404 when network disabled")
    void vnetCreateGatedOff() {
        given().contentType("application/json")
                .body("{\"location\":\"eastus\",\"properties\":{\"addressSpace\":{\"addressPrefixes\":[\"10.0.0.0/16\"]}}}")
                .when().put(BASE + "/providers/Microsoft.Network/virtualNetworks/vnet1" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    @DisplayName("DNS zone create returns 404 when network disabled")
    void dnsZoneGatedOff() {
        given().contentType("application/json")
                .body("{\"location\":\"global\",\"properties\":{\"zoneType\":\"Public\"}}")
                .when().put(BASE + "/providers/Microsoft.Network/dnsZones/example.com" + API)
                .then().statusCode(404);
    }

    @Test
    @DisplayName("non-network ARM (resource group) still works when network disabled")
    void resourceGroupStillWorks() {
        given().contentType("application/json")
                .body("{\"location\":\"eastus\"}")
                .when().put(BASE + API)
                .then().statusCode(anyOf(equalTo(200), equalTo(201)));
    }
}
