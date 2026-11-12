package io.floci.az.services.authorization;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Each tenant has its own management-group hierarchy, and a group's id does not name its tenant. Two tenants with a
 * group of the same name keep their assignments apart: neither group's listing shows the other tenant's.
 */
@QuarkusTest
@TestProfile(RbacTenantScopingTest.TwoTenants.class)
class RbacTenantScopingTest {

    private static final String TEAM_TENANT = "eeeeeeee-0000-0000-0000-000000000002";
    private static final String TEAM_SUB = "eeeeeeee-1111-1111-1111-000000000001";
    private static final String DEFAULT_SUB = "eeeeeeee-1111-1111-1111-000000000002";
    private static final String GROUP = "/providers/Microsoft.Management/managementGroups/prod";
    private static final String MG_API = "2023-04-01";
    private static final String ASSIGNMENTS = "/providers/Microsoft.Authorization/roleAssignments";
    private static final String READER = "/providers/Microsoft.Authorization/roleDefinitions/"
            + "acdd72a7-3385-48ef-bd42-f606fba81ae7";
    private static final String PRINCIPAL = "11111111-2222-3333-4444-555555555555";

    public static class TwoTenants implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.parity-overrides.tenant-scoping", "true",
                    "floci-az.tenants[0].id", TEAM_TENANT,
                    "floci-az.tenants[0].display-name", "Team tenant",
                    "floci-az.tenants[0].default-domain", "team.example",
                    "floci-az.subscriptions[0].id", TEAM_SUB,
                    "floci-az.subscriptions[0].tenant-id", TEAM_TENANT);
        }
    }

    @Test
    void sameNamedGroupsOfTwoTenantsKeepTheirAssignmentsApart() {
        given().post("/_admin/reset").then().statusCode(204);
        createGroupWith(given(), DEFAULT_SUB);
        createGroupWith(given().header("Authorization", bearer(TEAM_TENANT)), TEAM_SUB);

        String atDefaultSub = put(given(), "/subscriptions/" + DEFAULT_SUB);
        String atTeamSub = put(given(), "/subscriptions/" + TEAM_SUB);
        String atDefaultGroup = put(given(), GROUP);
        String atTeamGroup = put(given().header("Authorization", bearer(TEAM_TENANT)), GROUP);

        list(given(), GROUP, null)
                .body("value.id", containsInAnyOrder(atDefaultGroup, atDefaultSub));
        list(given().header("Authorization", bearer(TEAM_TENANT)), GROUP, null)
                .body("value.id", containsInAnyOrder(atTeamGroup, atTeamSub));
        list(given(), "/subscriptions/" + TEAM_SUB, "atScope()")
                .body("value.id", hasItems(atTeamGroup, atTeamSub))
                .body("value.id", not(hasItem(atDefaultGroup)));
    }

    private static void createGroupWith(RequestSpecification as, String subscription) {
        as.contentType("application/json").body("{}").queryParam("api-version", MG_API)
                .when().put(GROUP).then().statusCode(200);
        as.queryParam("api-version", MG_API)
                .when().put(GROUP + "/subscriptions/" + subscription).then().statusCode(200);
    }

    private static String put(RequestSpecification as, String scope) {
        String id = scope + ASSIGNMENTS + "/" + UUID.randomUUID();
        as.contentType("application/json").queryParam("api-version", "2022-04-01")
                .body("{\"properties\":{\"roleDefinitionId\":\"" + READER + "\",\"principalId\":\"" + PRINCIPAL + "\"}}")
                .when().put(id).then().statusCode(201)
                .body("$", not(hasKey("_tenant")));
        return id;
    }

    private static ValidatableResponse list(RequestSpecification as, String scope, String filter) {
        RequestSpecification request = as.queryParam("api-version", "2022-04-01");
        if (filter != null) {
            request = request.queryParam("$filter", filter);
        }
        return request.when().get(scope + ASSIGNMENTS).then().statusCode(200);
    }

    /** An unsigned token for {@code tid}: the emulator reads the claim as a hint and never verifies it. */
    private static String bearer(String tid) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(("{\"tid\":\"" + tid + "\"}").getBytes(StandardCharsets.UTF_8)) + ".sig";
    }
}
