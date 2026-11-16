package io.floci.az.services.postgres;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(PostgresEntraAuthTest.MockedProfile.class)
@DisplayName("PostgreSQL flexible servers: Entra-only authentication and Microsoft Entra administrators")
class PostgresEntraAuthTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.postgres.mocked", "true");
        }
    }

    private static final String BASE = "/subscriptions/sub-pg-entra/resourceGroups/rg-pg-entra"
        + "/providers/Microsoft.DBforPostgreSQL/flexibleServers/";
    private static final String API = "?api-version=2025-08-01";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String OBJECT_ID = "22222222-2222-2222-2222-222222222222";

    /** What azurerm sends for an `authentication` block with password sign-in off: no administrator login. */
    private static final String ENTRA_ONLY_BODY = "{\"location\":\"eastus\","
        + "\"sku\":{\"name\":\"Standard_B1ms\",\"tier\":\"Burstable\"},"
        + "\"properties\":{\"version\":\"16\",\"storage\":{\"storageSizeGB\":32},"
        + "\"authConfig\":{\"activeDirectoryAuth\":\"Enabled\",\"passwordAuth\":\"Disabled\",\"tenantId\":\""
        + TENANT + "\"}}}";
    /** What azurerm_postgresql_flexible_server_active_directory_administrator sends; the object id is the path's. */
    private static final String ADMIN_BODY = "{\"properties\":{\"principalName\":\"id-repro\","
        + "\"principalType\":\"ServicePrincipal\",\"tenantId\":\"" + TENANT + "\"}}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void anEntraOnlyServerIsCreatedWithoutAnAdministratorLoginAndReadsBackItsAuthConfig() {
        given().contentType("application/json").body(ENTRA_ONLY_BODY).when().put(BASE + "entraonly" + API)
            .then().statusCode(202)
            .body("properties.provisioningState", equalTo("Succeeded"));

        given().when().get(BASE + "entraonly" + API)
            .then().statusCode(200)
            .body("properties.authConfig.activeDirectoryAuth", equalTo("Enabled"))
            .body("properties.authConfig.passwordAuth", equalTo("Disabled"))
            .body("properties.authConfig.tenantId", equalTo(TENANT))
            .body("properties", not(hasKey("administratorLogin")));
    }

    @Test
    void aServerWithPasswordSignInStillNeedsALogin() {
        String body = ENTRA_ONLY_BODY.replace("\"passwordAuth\":\"Disabled\"", "\"passwordAuth\":\"Enabled\"");
        given().contentType("application/json").body(body).when().put(BASE + "needslogin" + API)
            .then().statusCode(400).body("error.message", equalTo("administratorLogin is required"));
    }

    @Test
    void anAdministratorIsKeptByObjectIdUntilItIsDeleted() {
        given().contentType("application/json").body(ENTRA_ONLY_BODY).when().put(BASE + "withadmin" + API)
            .then().statusCode(202);
        String admin = BASE + "withadmin/administrators/" + OBJECT_ID + API;

        given().when().get(admin).then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));

        given().contentType("application/json").body(ADMIN_BODY).when().put(admin)
            .then().statusCode(202)
            .body("type", equalTo("Microsoft.DBforPostgreSQL/flexibleServers/administrators"))
            .body("properties.objectId", equalTo(OBJECT_ID));

        given().when().get(admin)
            .then().statusCode(200)
            .body("id", equalTo(BASE + "withadmin/administrators/" + OBJECT_ID))
            .body("name", equalTo("id-repro"))
            .body("properties.objectId", equalTo(OBJECT_ID))
            .body("properties.principalName", equalTo("id-repro"))
            .body("properties.principalType", equalTo("ServicePrincipal"))
            .body("properties.tenantId", equalTo(TENANT));
        given().when().get(BASE + "withadmin/administrators" + API)
            .then().statusCode(200).body("value", hasSize(1));

        given().when().delete(admin).then().statusCode(204);
        given().when().get(admin).then().statusCode(404);
        given().when().delete(admin).then().statusCode(204);
    }

    @Test
    void anAdministratorOfAMissingServerIsNotFound() {
        given().contentType("application/json").body(ADMIN_BODY)
            .when().put(BASE + "noserver/administrators/" + OBJECT_ID + API)
            .then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void anUnknownChildPathAnswersTheArmEnvelope() {
        given().contentType("application/json").body(ENTRA_ONLY_BODY).when().put(BASE + "unknownchild" + API)
            .then().statusCode(202);
        given().when().get(BASE + "unknownchild/advancedThreatProtectionSettings/Default" + API)
            .then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));
    }
}
