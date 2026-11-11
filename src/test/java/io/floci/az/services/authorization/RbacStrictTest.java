package io.floci.az.services.authorization;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * The refusals Azure makes that floci-az leaves behind strict switches: an assignment on a resource that does not
 * exist ({@code strict-resource-groups}) and a name that is not a GUID ({@code strict-names}).
 */
@QuarkusTest
@TestProfile(RbacStrictTest.StrictProfile.class)
class RbacStrictTest {

    public static class StrictProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-resource-groups", "true",
                    "floci-az.parity-overrides.strict-names", "true");
        }
    }

    private static final String SUB = "/subscriptions/rbac-strict-sub";
    private static final String RG = SUB + "/resourceGroups/rbac-strict-rg";
    private static final String ASSIGNMENTS = "/providers/Microsoft.Authorization/roleAssignments/";
    private static final String READER = SUB + "/providers/Microsoft.Authorization/roleDefinitions/"
            + "acdd72a7-3385-48ef-bd42-f606fba81ae7";
    private static final String PRINCIPAL = "11111111-2222-3333-4444-555555555555";

    @BeforeEach
    void resetAndCreateTheGroup() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .queryParam("api-version", "2021-04-01").when().put(RG)
                .then().statusCode(anyOf(equalTo(200), equalTo(201)));
    }

    @Test
    void anAssignmentOnAMissingResourceIsRefused() {
        String vault = RG + "/providers/Microsoft.KeyVault/vaults/rbac-missing-kv";
        putAssignment(vault + ASSIGNMENTS + UUID.randomUUID())
                .statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"))
                .body("error.message", containsString("Microsoft.KeyVault/vaults/rbac-missing-kv"));
    }

    @Test
    void anAssignmentOnAnExistingResourceIsAccepted() {
        String identity = RG + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/rbac-strict-id";
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .queryParam("api-version", "2023-01-31").when().put(identity)
                .then().statusCode(anyOf(equalTo(200), equalTo(201)));
        putAssignment(identity + ASSIGNMENTS + UUID.randomUUID()).statusCode(201);
    }

    /** A type floci-az does not serve cannot be read, so whether it exists cannot be told: it is accepted. */
    @Test
    void anAssignmentOnATypeFlociDoesNotServeIsAccepted() {
        String unknown = RG + "/providers/Microsoft.Unknown/widgets/w1";
        putAssignment(unknown + ASSIGNMENTS + UUID.randomUUID()).statusCode(201);
    }

    @Test
    void aNameThatIsNotAGuidIsRefused() {
        putAssignment(RG + ASSIGNMENTS + "not-a-guid")
                .statusCode(400)
                .body("error.code", equalTo("InvalidResourceName"));
    }

    private static ValidatableResponse putAssignment(String id) {
        return given().contentType("application/json").queryParam("api-version", "2022-04-01")
                .body("{\"properties\":{\"roleDefinitionId\":\"" + READER + "\",\"principalId\":\"" + PRINCIPAL + "\"}}")
                .when().put(id).then();
    }
}
