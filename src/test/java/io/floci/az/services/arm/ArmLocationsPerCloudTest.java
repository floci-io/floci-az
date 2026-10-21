package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/**
 * {@code GET /subscriptions/{sub}/locations} and the provider list's {@code locations} follow the cloud the
 * subscription belongs to: a subscription under a US Gov tenant sees the US Gov regions, the default one the
 * public regions it always has.
 */
@QuarkusTest
@TestProfile(ArmLocationsPerCloudTest.GovSubscription.class)
@DisplayName("ARM locations: per-cloud region list")
class ArmLocationsPerCloudTest {

    private static final String DEFAULT_SUB = "00000000-0000-0000-0000-000000000001";
    private static final String GOV_TENANT = "99999999-0000-0000-0000-0000000000c5";
    private static final String GOV_SUB = "eeeeeeee-1111-0000-0000-0000000000c5";
    private static final String API = "?api-version=2022-12-01";

    public static class GovSubscription implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "floci-az.tenants[0].id", GOV_TENANT,
                "floci-az.tenants[0].display-name", "Gov tenant",
                "floci-az.tenants[0].default-domain", "gov.example",
                "floci-az.tenants[0].cloud", "AzureUSGovernment",
                "floci-az.subscriptions[0].id", GOV_SUB,
                "floci-az.subscriptions[0].tenant-id", GOV_TENANT);
        }
    }

    @Test
    void aGovSubscriptionListsTheUsGovRegions() {
        given().when().get("/subscriptions/" + GOV_SUB + "/locations" + API).then().statusCode(200)
            .body("value", hasSize(6))
            .body("value[0].name", equalTo("usgovvirginia"))
            .body("value[0].displayName", equalTo("US Gov Virginia"))
            .body("value[0].id", equalTo("/subscriptions/" + GOV_SUB + "/locations/usgovvirginia"))
            .body("value.name", not(hasItem("eastus")));
    }

    @Test
    void theDefaultSubscriptionStillListsThePublicRegions() {
        given().when().get("/subscriptions/" + DEFAULT_SUB + "/locations" + API).then().statusCode(200)
            .body("value", hasSize(51))
            .body("value[0].name", equalTo("eastus"));
    }

    @Test
    void theProviderListOffersTheSubscriptionsCloudRegions() {
        given().when().get("/subscriptions/" + GOV_SUB + "/providers/Microsoft.Storage" + API).then().statusCode(200)
            .body("resourceTypes.find { it.resourceType == 'storageAccounts' }.locations", hasSize(6))
            .body("resourceTypes.find { it.resourceType == 'storageAccounts' }.locations", hasItem("US Gov Virginia"));
    }
}
