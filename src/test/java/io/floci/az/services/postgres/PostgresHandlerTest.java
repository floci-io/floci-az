package io.floci.az.services.postgres;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Quarkus-level tests for {@link PostgresHandler} that do NOT require Docker or a running
 * PostgreSQL container.
 *
 * <p>Covers ARM + convenience path routing, 404s for unknown resources, 400s for missing
 * required fields, checkNameAvailability, and child-resource guards against unknown servers.
 * Operations that would start a real container (a valid server PUT in non-mocked mode) are
 * exercised in {@link PostgresHandlerMockedTest} instead.
 */
@QuarkusTest
@DisplayName("PostgresHandler — routing and error cases (no Docker)")
class PostgresHandlerTest {

    private static final String SUB  = "test-sub-pg";
    private static final String RG   = "test-rg-pg";
    private static final String BASE = "/subscriptions/" + SUB + "/resourceGroups/" + RG
                                     + "/providers/Microsoft.DBforPostgreSQL";
    private static final String API  = "?api-version=2025-08-01";
    private static final String ACCOUNT = "devstoreaccount1";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    @DisplayName("GET unknown server returns 404")
    void getUnknownServer() {
        given()
            .when().get(BASE + "/flexibleServers/no-such-server" + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    @DisplayName("list servers returns empty value array when none exist")
    void listServersEmpty() {
        given()
            .when().get(BASE + "/flexibleServers" + API)
            .then().statusCode(200)
            .body("value", hasSize(0));
    }

    @Test
    @DisplayName("checkNameAvailability returns nameAvailable=true for unused name")
    void checkNameAvailable() {
        given()
            .contentType("application/json")
            .body("{\"name\":\"free-server-name\",\"type\":\"Microsoft.DBforPostgreSQL/flexibleServers\"}")
            .when().post("/subscriptions/" + SUB
                + "/providers/Microsoft.DBforPostgreSQL/locations/eastus/checkNameAvailability" + API)
            .then().statusCode(200)
            .body("nameAvailable", equalTo(true))
            .body("name", equalTo("free-server-name"));
    }

    @Test
    @DisplayName("PUT server without administratorLogin returns 400")
    void putServerMissingLogin() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{\"administratorLoginPassword\":\"P@ss1!\"}}")
            .when().put(BASE + "/flexibleServers/myserver" + API)
            .then().statusCode(400)
            .body("error.message", containsString("administratorLogin"));
    }

    @Test
    @DisplayName("PUT server without administratorLoginPassword returns 400")
    void putServerMissingPassword() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{\"administratorLogin\":\"psqladmin\"}}")
            .when().put(BASE + "/flexibleServers/myserver" + API)
            .then().statusCode(400)
            .body("error.message", containsString("administratorLoginPassword"));
    }

    @Test
    @DisplayName("PATCH unknown server returns 404")
    void patchUnknownServer() {
        given()
            .contentType("application/json")
            .body("{\"sku\":{\"name\":\"Standard_B2s\",\"tier\":\"Burstable\"}}")
            .when().patch(BASE + "/flexibleServers/ghost" + API)
            .then().statusCode(404);
    }

    @Test
    @DisplayName("PUT firewall rule on unknown server returns 404")
    void putFirewallRuleUnknownServer() {
        given()
            .contentType("application/json")
            .body("{\"properties\":{\"startIpAddress\":\"0.0.0.0\",\"endIpAddress\":\"255.255.255.255\"}}")
            .when().put(BASE + "/flexibleServers/ghost/firewallRules/AllowAll" + API)
            .then().statusCode(404);
    }

    @Test
    @DisplayName("GET firewall rules on unknown server returns 404")
    void listFirewallRulesUnknownServer() {
        given()
            .when().get(BASE + "/flexibleServers/ghost/firewallRules" + API)
            .then().statusCode(404);
    }

    @Test
    @DisplayName("GET database on unknown server returns 404")
    void getDatabaseUnknownServer() {
        given()
            .when().get(BASE + "/flexibleServers/ghost/databases/mydb" + API)
            .then().statusCode(404);
    }

    @Test
    @DisplayName("GET configurations on unknown server returns 404")
    void listConfigurationsUnknownServer() {
        given()
            .when().get(BASE + "/flexibleServers/ghost/configurations" + API)
            .then().statusCode(404);
    }

    @Test
    @DisplayName("DELETE unknown server returns 404")
    void deleteUnknownServer() {
        given()
            .when().delete(BASE + "/flexibleServers/ghost" + API)
            .then().statusCode(404);
    }

    @Test
    @DisplayName("GET /connect on unknown server returns 404 (convenience path)")
    void connectUnknownServer() {
        given()
            .when().get("/" + ACCOUNT + "-postgres/flexibleServers/ghost/connect")
            .then().statusCode(404);
    }
}
