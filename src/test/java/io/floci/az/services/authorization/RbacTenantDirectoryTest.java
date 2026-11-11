package io.floci.az.services.authorization;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * An omitted {@code principalType} is resolved in the directory of the tenant that owns the scope's subscription,
 * not always the default tenant's.
 */
@QuarkusTest
@TestProfile(RbacTenantDirectoryTest.OtherTenant.class)
class RbacTenantDirectoryTest {

    private static final String OTHER_TENANT = "dddddddd-0000-0000-0000-000000000002";
    private static final String OTHER_SUBSCRIPTION = "dddddddd-2222-2222-2222-000000000001";
    private static final String OTHER_USER = "dddddddd-1111-1111-1111-000000000001";
    private static final String DEFAULT_SUBSCRIPTION = "dddddddd-2222-2222-2222-000000000002";
    private static final String READER = "/providers/Microsoft.Authorization/roleDefinitions/"
            + "acdd72a7-3385-48ef-bd42-f606fba81ae7";

    public static class OtherTenant implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.tenants[0].id", OTHER_TENANT,
                    "floci-az.subscriptions[0].id", OTHER_SUBSCRIPTION,
                    "floci-az.subscriptions[0].tenant-id", OTHER_TENANT,
                    "floci-az.services.entra.seed-file",
                    Path.of("src/test/resources/authorization/other-tenant-seed.json").toAbsolutePath().toString());
        }
    }

    @Test
    void aUserOfTheSubscriptionsTenantIsResolved() {
        putAssignment("/subscriptions/" + OTHER_SUBSCRIPTION)
                .statusCode(201)
                .body("properties.principalType", equalTo("User"));
    }

    @Test
    void theSameUserIsUnknownUnderASubscriptionOfAnotherTenant() {
        putAssignment("/subscriptions/" + DEFAULT_SUBSCRIPTION)
                .statusCode(201)
                .body("properties.principalType", nullValue());
    }

    private static ValidatableResponse putAssignment(String scope) {
        return given().contentType("application/json").queryParam("api-version", "2022-04-01")
                .body("{\"properties\":{\"roleDefinitionId\":\"" + scope + READER + "\",\"principalId\":\""
                        + OTHER_USER + "\"}}")
                .when().put(scope + "/providers/Microsoft.Authorization/roleAssignments/" + UUID.randomUUID())
                .then();
    }
}
