package io.floci.az.core.cloud;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(CloudDefaultLocationTest.WestEurope.class)
@DisplayName("Cloud: floci-az.cloud.<name>.default-location is the location of a resource whose PUT names none")
class CloudDefaultLocationTest {

    public static class WestEurope implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.cloud.AzureCloud.default-location", "West Europe");
        }
    }

    private static final String RG = "/subscriptions/sub-default-location/resourcegroups/rg-default-location";

    @Test
    void aResourceGroupWithoutALocationGetsTheConfiguredOne() {
        given().contentType("application/json").body("{}").when().put(RG + "?api-version=2021-04-01")
            .then().statusCode(201).body("location", equalTo("westeurope"));
    }

    @Test
    void anEventGridTopicWithoutALocationGetsTheConfiguredOne() {
        given().contentType("application/json").body("{\"properties\":{}}")
            .when().put(RG + "/providers/Microsoft.EventGrid/topics/located-topic?api-version=2025-02-15")
            .then().statusCode(200).body("location", equalTo("westeurope"));
    }

    @Test
    void aManagedIdentityWithoutALocationGetsTheConfiguredOne() {
        given().contentType("application/json").body("{}")
            .when().put(RG + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/located-id"
                + "?api-version=2023-01-31")
            .then().statusCode(201).body("location", equalTo("westeurope"));
    }
}
