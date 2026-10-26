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
@TestProfile(CloudDefaultLocationEventGridAliasTest.Both.class)
@DisplayName("Cloud: services.event-grid.default-region still wins for Event Grid over the cloud's default location")
class CloudDefaultLocationEventGridAliasTest {

    public static class Both implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.cloud.AzureCloud.default-location", "westeurope",
                "floci-az.services.event-grid.default-region", "northeurope");
        }
    }

    private static final String RG = "/subscriptions/sub-region-alias/resourcegroups/rg-region-alias";

    @Test
    void aTopicWithoutALocationGetsTheEventGridRegion() {
        given().contentType("application/json").body("{\"properties\":{}}")
            .when().put(RG + "/providers/Microsoft.EventGrid/topics/aliased-topic?api-version=2025-02-15")
            .then().statusCode(200).body("location", equalTo("northeurope"));
    }

    @Test
    void aResourceGroupStillGetsTheCloudDefault() {
        given().contentType("application/json").body("{}").when().put(RG + "?api-version=2021-04-01")
            .then().statusCode(201).body("location", equalTo("westeurope"));
    }
}
