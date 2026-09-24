package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@DisplayName("ARM tenants, subscriptions and locations")
class ArmSubscriptionTest {

    private static final String DEFAULT_SUB = "00000000-0000-0000-0000-000000000001";
    private static final String DEFAULT_TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String API = "?api-version=2022-12-01";

    @Test
    void tenantListReportsTheConfiguredTenant() {
        given().when().get("/tenants" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].id", equalTo("/tenants/" + DEFAULT_TENANT))
                .body("value[0].tenantId", equalTo(DEFAULT_TENANT))
                .body("value[0].tenantCategory", equalTo("Home"))
                .body("value[0].domains", contains("floci-az.local"));
    }

    @Test
    void subscriptionListReportsTheConfiguredSubscription() {
        given().when().get("/subscriptions" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].subscriptionId", equalTo(DEFAULT_SUB))
                .body("value[0].tenantId", equalTo(DEFAULT_TENANT));
    }

    @Test
    void anySubscriptionIdIsServedWithTheSubscriptionsGetShape() {
        String sub = "11111111-2222-3333-4444-555555555555";
        given().when().get("/subscriptions/" + sub + API)
                .then().statusCode(200)
                .body("id", equalTo("/subscriptions/" + sub))
                .body("subscriptionId", equalTo(sub))
                .body("state", equalTo("Enabled"))
                .body("tenantId", equalTo(DEFAULT_TENANT))
                .body("authorizationSource", equalTo("RoleBased"))
                .body("managedByTenants", empty())
                .body("subscriptionPolicies.spendingLimit", equalTo("Off"))
                .body("subscriptionPolicies.quotaId", equalTo("Internal_2014-09-01"));
    }

    @Test
    void locationListIsScopedToThePathSubscriptionAndPhysical() {
        String sub = "22222222-0000-0000-0000-000000000000";
        List<Map<String, Object>> locations = given().when().get("/subscriptions/" + sub + "/locations" + API)
                .then().statusCode(200)
                .body("value.name", hasItem("eastus"))
                .body("value.name", hasItem("westus"))
                .body("value.subscriptionId", everyItem(equalTo(sub)))
                .body("value.type", everyItem(equalTo("Region")))
                .body("value.metadata.regionType", everyItem(equalTo("Physical")))
                .body("value.name", not(hasItem("usgovvirginia")))
                .body("value.name", not(hasItem("chinanorth")))
                .extract().path("value");

        Map<String, Object> eastus = locations.stream()
                .filter(l -> "eastus".equals(l.get("name")))
                .findFirst().orElseThrow();
        assertEquals("/subscriptions/" + sub + "/locations/eastus", eastus.get("id"));
        assertEquals("East US", eastus.get("displayName"));
        assertEquals(List.of(
                Map.of("logicalZone", "1", "physicalZone", "eastus-az1"),
                Map.of("logicalZone", "2", "physicalZone", "eastus-az2"),
                Map.of("logicalZone", "3", "physicalZone", "eastus-az3")),
                eastus.get("availabilityZoneMappings"));
    }

    @Test
    void locationListAcceptsIncludeExtendedLocations() {
        given().when().get("/subscriptions/" + DEFAULT_SUB + "/locations" + API + "&includeExtendedLocations=true")
                .then().statusCode(200)
                .body("value.type", everyItem(equalTo("Region")));
    }

    @Test
    void resourceGroupListOnlyShowsTheSubscriptionsOwnGroups() {
        String subA = "aaaaaaaa-0000-0000-0000-000000000000";
        String subB = "bbbbbbbb-0000-0000-0000-000000000000";
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .when().put("/subscriptions/" + subA + "/resourceGroups/rg-only-in-a?api-version=2021-04-01")
                .then().statusCode(201);

        given().when().get("/subscriptions/" + subA + "/resourceGroups?api-version=2021-04-01")
                .then().statusCode(200)
                .body("value", hasItem(hasEntry("name", "rg-only-in-a")));

        given().when().get("/subscriptions/" + subB + "/resourceGroups?api-version=2021-04-01")
                .then().statusCode(200)
                .body("value.name", not(hasItem("rg-only-in-a")));
    }
}
