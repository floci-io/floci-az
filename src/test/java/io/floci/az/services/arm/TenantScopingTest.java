package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * {@code parity-overrides.tenant-scoping} alone: the {@code tid} of the caller's token decides whose subscriptions
 * {@code GET /subscriptions} lists, with the default subscription for a tenant that owns none, and which tenant a
 * first-use subscription is registered under. {@code GET /tenants} still lists every tenant as {@code Home}.
 */
@QuarkusTest
@TestProfile(TenantScopingTest.TenantScopingOnly.class)
@DisplayName("ARM tenants: tenant-scoping override alone")
class TenantScopingTest {

    private static final String DEFAULT_TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String DEFAULT_SUB = "00000000-0000-0000-0000-000000000001";
    private static final String TEAM_TENANT = "aaaaaaaa-0000-0000-0000-0000000000a6";
    private static final String EMPTY_TENANT = "bbbbbbbb-0000-0000-0000-0000000000a6";
    private static final String TEAM_SUB = "aaaaaaaa-1111-0000-0000-0000000000a6";
    private static final String GOV_TENANT = "eeeeeeee-0000-0000-0000-0000000000a6";
    private static final String API = "?api-version=2022-12-01";

    public static class TenantScopingOnly implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                Map.entry("floci-az.parity-overrides.tenant-scoping", "true"),
                Map.entry("floci-az.tenants[0].id", TEAM_TENANT),
                Map.entry("floci-az.tenants[0].display-name", "Team tenant"),
                Map.entry("floci-az.tenants[0].default-domain", "team.example"),
                Map.entry("floci-az.tenants[1].id", EMPTY_TENANT),
                Map.entry("floci-az.tenants[1].display-name", "Empty tenant"),
                Map.entry("floci-az.tenants[1].default-domain", "empty.example"),
                Map.entry("floci-az.tenants[2].id", GOV_TENANT),
                Map.entry("floci-az.tenants[2].display-name", "Gov tenant"),
                Map.entry("floci-az.tenants[2].default-domain", "gov.example"),
                Map.entry("floci-az.tenants[2].cloud", "AzureUSGovernment"),
                Map.entry("floci-az.subscriptions[0].id", TEAM_SUB),
                Map.entry("floci-az.subscriptions[0].tenant-id", TEAM_TENANT));
        }
    }

    /** An unsigned token for {@code tid}: the emulator reads the claim as a hint and never verifies it. */
    private static String bearer(String tid) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + encoder.encodeToString(("{\"tid\":\"" + tid + "\"}").getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test
    void aTeamTenantTokenListsOnlyTheTeamSubscription() {
        given().header("Authorization", bearer(TEAM_TENANT))
            .when().get("/subscriptions" + API).then().statusCode(200)
            .body("value.subscriptionId", contains(TEAM_SUB))
            .body("value[0].tenantId", equalTo(TEAM_TENANT));
    }

    @Test
    void aTenantThatOwnsNoSubscriptionSeesTheDefaultOne() {
        given().header("Authorization", bearer(EMPTY_TENANT))
            .when().get("/subscriptions" + API).then().statusCode(200)
            .body("value.subscriptionId", contains(DEFAULT_SUB));
    }

    @Test
    void noTokenOrAnUnknownTenantIsTheDefaultTenant() {
        given().when().get("/subscriptions" + API).then().statusCode(200)
            .body("value.subscriptionId", contains(DEFAULT_SUB));
        given().header("Authorization", bearer("cccccccc-0000-0000-0000-0000000000a6"))
            .when().get("/subscriptions" + API).then().statusCode(200)
            .body("value.subscriptionId", contains(DEFAULT_SUB));
    }

    @Test
    void aFirstUseSubscriptionBelongsToTheCallersTenant() {
        String sub = "dddddddd-1111-0000-0000-0000000000a6";
        given().header("Authorization", bearer(TEAM_TENANT))
            .when().get("/subscriptions/" + sub + "/resourceGroups" + API).then().statusCode(200);

        given().header("Authorization", bearer(TEAM_TENANT))
            .when().get("/subscriptions/" + sub + API).then().statusCode(200)
            .body("tenantId", equalTo(TEAM_TENANT));
    }

    @Test
    void aFirstUseSubscriptionAnswersInItsCallersCloudFromTheFirstRequest() {
        String sub = "ffffffff-1111-0000-0000-0000000000a6";
        given().header("Authorization", bearer(GOV_TENANT))
            .when().get("/subscriptions/" + sub + "/locations" + API).then().statusCode(200)
            .body("value.name", hasItem("usgovvirginia"))
            .body("value.name", not(hasItem("eastus")));
    }

    @Test
    void everyTenantIsStillListedAsHome() {
        given().header("Authorization", bearer(TEAM_TENANT))
            .when().get("/tenants" + API).then().statusCode(200)
            .body("value.tenantId", containsInAnyOrder(DEFAULT_TENANT, TEAM_TENANT, EMPTY_TENANT, GOV_TENANT))
            .body("value.tenantCategory", everyItem(equalTo("Home")));
    }
}
