package io.floci.az.services.network;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(NetworkStrictNamesTest.StrictNames.class)
@DisplayName("Network: parity-overrides.strict-names refuses names outside resource-name-rules.md")
class NetworkStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true");
        }
    }

    private static final String BASE = "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.Network/";
    private static final String API = "?api-version=2023-09-01";
    private static final String BODY = "{\"location\":\"eastus\"}";

    private static void assertRefused(String path) {
        given().contentType("application/json").body(BODY).when().put(BASE + path + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
    }

    private static void assertAccepted(String path) {
        given().contentType("application/json").body(BODY).when().put(BASE + path + API)
            .then().statusCode(200);
    }

    @Test
    void aVirtualNetworkNameOutsideTheRuleIsRefused() {
        for (String bad : new String[] {"v", "-vnet", "vnet-", "vnet.", "has space", "x".repeat(65)}) {
            assertRefused("virtualNetworks/" + bad);
        }
        assertAccepted("virtualNetworks/good_vnet.1-a_");
    }

    @Test
    void aSubnetNameOutsideTheRuleIsRefused() {
        assertAccepted("virtualNetworks/subnet-host");
        for (String bad : new String[] {"_subnet", "subnet-", "x".repeat(81)}) {
            assertRefused("virtualNetworks/subnet-host/subnets/" + bad);
        }
        assertAccepted("virtualNetworks/subnet-host/subnets/s");
    }

    @Test
    void nicPublicIpAndNsgNamesOutsideTheRuleAreRefused() {
        for (String type : new String[] {"networkInterfaces", "publicIPAddresses", "networkSecurityGroups"}) {
            assertRefused(type + "/bad!name");
            assertRefused(type + "/" + "x".repeat(81));
            assertAccepted(type + "/" + "x".repeat(80));
            assertAccepted(type + "/n");
        }
    }
}
