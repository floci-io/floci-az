package io.floci.az.services.arm;

import io.floci.az.core.cloud.ScopeContext;
import io.floci.az.core.cloud.ScopeResolver;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code parity: strict} with configured tenants and subscriptions: unknown subscriptions are refused, the
 * configured ones are served by their state, and a tenant's cloud reaches the request scope.
 */
@QuarkusTest
@TestProfile(ArmStrictSubscriptionTest.StrictProfile.class)
@DisplayName("ARM subscriptions under parity: strict")
class ArmStrictSubscriptionTest {

    private static final String DEFAULT_SUB = "00000000-0000-0000-0000-000000000001";
    private static final String DEFAULT_TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String GOV_TENANT = "99999999-0000-0000-0000-000000000000";
    private static final String TEAM_SUB = "aaaaaaaa-1111-0000-0000-000000000000";
    private static final String DISABLED_SUB = "dddddddd-1111-0000-0000-000000000000";
    private static final String GOV_SUB = "eeeeeeee-1111-0000-0000-000000000000";
    private static final String UNKNOWN_SUB = "ae0a5678-da86-4bd9-a3a2-9a7558415de5";
    private static final String API = "?api-version=2022-12-01";
    private static final String RG_API = "?api-version=2021-04-01";

    public static class StrictProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                Map.entry("floci-az.parity", "strict"),
                // StrictTokensTest covers the token check; these requests carry no token.
                Map.entry("floci-az.parity-overrides.strict-tokens", "false"),
                Map.entry("floci-az.tenants[0].id", GOV_TENANT),
                Map.entry("floci-az.tenants[0].display-name", "Gov tenant"),
                Map.entry("floci-az.tenants[0].default-domain", "gov.example"),
                Map.entry("floci-az.tenants[0].cloud", "AzureUSGovernment"),
                Map.entry("floci-az.subscriptions[0].id", TEAM_SUB),
                Map.entry("floci-az.subscriptions[0].display-name", "team"),
                Map.entry("floci-az.subscriptions[1].id", DISABLED_SUB),
                Map.entry("floci-az.subscriptions[1].state", "Disabled"),
                Map.entry("floci-az.subscriptions[2].id", GOV_SUB),
                Map.entry("floci-az.subscriptions[2].tenant-id", GOV_TENANT));
        }
    }

    @Inject
    ScopeResolver scopeResolver;

    private static RequestSpecification json() {
        return given().contentType("application/json");
    }

    @Test
    void anUnknownSubscriptionIsNotFoundWithAzuresBody() {
        given().when().get("/subscriptions/" + UNKNOWN_SUB + API)
                .then().statusCode(404)
                .body("error.code", equalTo("SubscriptionNotFound"))
                .body("error.message", equalTo("The subscription '" + UNKNOWN_SUB + "' could not be found."));

        json().body("{\"location\":\"eastus\"}")
                .when().put("/subscriptions/" + UNKNOWN_SUB + "/resourceGroups/rg-strict" + RG_API)
                .then().statusCode(404)
                .body("error.code", equalTo("SubscriptionNotFound"));

        given().when().get("/subscriptions" + API)
                .then().statusCode(200)
                .body("value.subscriptionId", not(hasItem(UNKNOWN_SUB)));
    }

    @Test
    void aConfiguredSubscriptionIsServed() {
        json().body("{\"location\":\"eastus\"}")
                .when().put("/subscriptions/" + TEAM_SUB + "/resourceGroups/rg-team" + RG_API)
                .then().statusCode(201);

        given().when().get("/subscriptions/" + TEAM_SUB + API)
                .then().statusCode(200)
                .body("displayName", equalTo("team"))
                .body("state", equalTo("Enabled"))
                .body("tenantId", equalTo(DEFAULT_TENANT));
    }

    @Test
    void aDisabledSubscriptionServesReadsAndRefusesWrites() {
        given().when().get("/subscriptions/" + DISABLED_SUB + API)
                .then().statusCode(200)
                .body("state", equalTo("Disabled"));
        given().when().get("/subscriptions/" + DISABLED_SUB + "/resourceGroups" + RG_API)
                .then().statusCode(200);

        json().body("{\"location\":\"eastus\"}")
                .when().put("/subscriptions/" + DISABLED_SUB + "/resourceGroups/rg-off" + RG_API)
                .then().statusCode(409)
                .body("error.code", equalTo("ReadOnlyDisabledSubscription"));
    }

    @Test
    void theSubscriptionListHoldsTheDefaultTenantsSubscriptionsInOrder() {
        given().when().get("/subscriptions" + API)
                .then().statusCode(200)
                .body("value.subscriptionId", contains(DEFAULT_SUB, TEAM_SUB, DISABLED_SUB))
                .body("value[2].state", equalTo("Disabled"));
    }

    @Test
    void aSubscriptionOfAnotherTenantReportsThatTenant() {
        given().when().get("/subscriptions/" + GOV_SUB + API)
                .then().statusCode(200)
                .body("tenantId", equalTo(GOV_TENANT));
    }

    @Test
    void theTenantListHoldsTheConfiguredTenants() {
        given().when().get("/tenants" + API)
                .then().statusCode(200)
                .body("value.tenantId", contains(DEFAULT_TENANT, GOV_TENANT))
                .body("value[1].displayName", equalTo("Gov tenant"))
                .body("value[1].defaultDomain", equalTo("gov.example"))
                .body("value[1].domains", contains("gov.example"));
    }

    @Test
    void aSubscriptionsCloudComesFromItsTenant() {
        ScopeContext gov = scopeResolver.resolve("subscriptions/" + GOV_SUB + "/resourceGroups/rg", "localhost");
        ScopeContext team = scopeResolver.resolve("subscriptions/" + TEAM_SUB, "localhost");

        assertEquals(GOV_TENANT, gov.tenantId());
        assertEquals("AzureUSGovernment", gov.cloud().name());
        assertEquals(DEFAULT_TENANT, team.tenantId());
        assertEquals("AzureCloud", team.cloud().name());
    }
}
