package io.floci.az.services.deployments;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tenant-scope deployments belong to the caller's tenant: with two tenants and tenant scoping on, the same name
 * deployed by each is two deployments, and each tenant reads, lists and deletes only its own.
 */
@QuarkusTest
@TestProfile(DeploymentsTenantIsolationTest.TwoTenants.class)
@DisplayName("Deployments: tenant-scope history is per tenant")
class DeploymentsTenantIsolationTest {

    private static final String TEAM_TENANT = "aaaaaaaa-0000-0000-0000-0000000000d1";
    private static final String OTHER_TENANT = "bbbbbbbb-0000-0000-0000-0000000000d1";
    private static final String DEPLOYMENTS = "/providers/Microsoft.Resources/deployments";
    private static final String API = "?api-version=2025-04-01";
    private static final String BODY = """
        {"location": "westeurope", "properties": {"mode": "Incremental", "template": {"resources": [],
          "outputs": {"tenant": {"type": "string", "value": "[tenant().tenantId]"}}}}}
        """;

    public static class TwoTenants implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                Map.entry("floci-az.parity-overrides.tenant-scoping", "true"),
                Map.entry("floci-az.tenants[0].id", TEAM_TENANT),
                Map.entry("floci-az.tenants[0].display-name", "Team tenant"),
                Map.entry("floci-az.tenants[0].default-domain", "team.example"),
                Map.entry("floci-az.tenants[1].id", OTHER_TENANT),
                Map.entry("floci-az.tenants[1].display-name", "Other tenant"),
                Map.entry("floci-az.tenants[1].default-domain", "other.example"));
        }
    }

    /** An unsigned token for {@code tid}: the emulator reads the claim as a hint and never verifies it. */
    private static String bearer(String tid) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "Bearer " + encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + encoder.encodeToString(("{\"tid\":\"" + tid + "\"}").getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    private static String deployAndAwait(String tenant) throws InterruptedException {
        Response accepted = given().header("Authorization", bearer(tenant)).contentType("application/json")
            .body(BODY).when().put(DEPLOYMENTS + "/shared" + API);
        accepted.then().statusCode(201);
        URI status = URI.create(accepted.header("Azure-AsyncOperation"));
        for (int attempt = 0; attempt < 200; attempt++) {
            String state = given().header("Authorization", bearer(tenant))
                .when().get(status.getRawPath() + "?" + status.getRawQuery()).then().statusCode(200)
                .extract().path("status");
            if (!state.equals("Accepted") && !state.equals("Running")) {
                return state;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the deployment did not finish");
    }

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void eachTenantHasItsOwnDeploymentOfTheSameName() throws Exception {
        assertEquals("Succeeded", deployAndAwait(TEAM_TENANT));
        assertEquals("Succeeded", deployAndAwait(OTHER_TENANT), "the other tenant's PUT creates, not replaces");

        given().header("Authorization", bearer(TEAM_TENANT)).when().get(DEPLOYMENTS + "/shared" + API)
            .then().statusCode(200).body("properties.outputs.tenant.value", equalTo(TEAM_TENANT));
        given().header("Authorization", bearer(OTHER_TENANT)).when().get(DEPLOYMENTS + API)
            .then().statusCode(200)
            .body("value.properties.outputs.tenant.value", contains(OTHER_TENANT));

        given().header("Authorization", bearer(OTHER_TENANT)).when().delete(DEPLOYMENTS + "/shared" + API)
            .then().statusCode(204);
        given().header("Authorization", bearer(TEAM_TENANT)).when().get(DEPLOYMENTS + "/shared" + API)
            .then().statusCode(200);
        given().header("Authorization", bearer(OTHER_TENANT)).when().head(DEPLOYMENTS + "/shared" + API)
            .then().statusCode(404);
    }
}
