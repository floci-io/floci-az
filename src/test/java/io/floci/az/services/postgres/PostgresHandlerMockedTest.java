package io.floci.az.services.postgres;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link PostgresHandler} in {@code mocked=true} mode: flexible servers are created
 * in state with no PostgreSQL container, transitioning immediately to {@code state=Ready} /
 * {@code provisioningState=Succeeded}. Exercises the full server + child-resource CRUD surface
 * without Docker.
 */
@QuarkusTest
@TestProfile(PostgresHandlerMockedTest.MockedProfile.class)
@DisplayName("PostgresHandler — mocked mode (no Docker)")
@SuppressWarnings("unused")
class PostgresHandlerMockedTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.postgres.mocked", "true");
        }
    }

    private static final String SUB  = "test-sub-pg-mocked";
    private static final String RG   = "test-rg-pg-mocked";
    private static final String BASE = "/subscriptions/" + SUB + "/resourceGroups/" + RG
                                     + "/providers/Microsoft.DBforPostgreSQL";
    private static final String API  = "?api-version=2025-08-01";

    private static final String SERVER_BODY =
        "{\"location\":\"eastus\","
        + "\"sku\":{\"name\":\"Standard_B1ms\",\"tier\":\"Burstable\"},"
        + "\"properties\":{"
        + "\"administratorLogin\":\"psqladmin\","
        + "\"administratorLoginPassword\":\"FlociAz_Strong123!\","
        + "\"version\":\"16\","
        + "\"storage\":{\"storageSizeGB\":32}}}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private void createServer(String name) {
        given().contentType("application/json").body(SERVER_BODY)
            .when().put(BASE + "/flexibleServers/" + name + API)
            .then().statusCode(200);
    }

    @Test
    @DisplayName("PUT server returns 200 with state=Ready, provisioningState=Succeeded and no localPort")
    void putServerReady() {
        // 200 (not 201): azurerm 4.x rejects bare 201 + Succeeded body for Flexible Server create.
        given().contentType("application/json").body(SERVER_BODY)
            .when().put(BASE + "/flexibleServers/pgserver" + API)
            .then().statusCode(200)
            .body("name", equalTo("pgserver"))
            .body("type", equalTo("Microsoft.DBforPostgreSQL/flexibleServers"))
            .body("sku.name", equalTo("Standard_B1ms"))
            .body("sku.tier", equalTo("Burstable"))
            .body("properties.version", equalTo("16"))
            .body("properties.administratorLogin", equalTo("psqladmin"))
            .body("properties.state", equalTo("Ready"))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.storage.storageSizeGB", equalTo(32))
            .body("properties.fullyQualifiedDomainName", notNullValue())
            .body("properties", not(hasKey("localPort")));
    }

    @Test
    @DisplayName("PUT existing server is idempotent update and still returns 200")
    void putServerIdempotentUpdate() {
        createServer("idempotent");
        given().contentType("application/json").body(SERVER_BODY)
            .when().put(BASE + "/flexibleServers/idempotent" + API)
            .then().statusCode(200)
            .body("name", equalTo("idempotent"))
            .body("properties.provisioningState", equalTo("Succeeded"));
    }

    @Test
    @DisplayName("created server is gettable and listable")
    void createdServerVisible() {
        createServer("listme");

        given().when().get(BASE + "/flexibleServers/listme" + API)
            .then().statusCode(200)
            .body("properties.provisioningState", equalTo("Succeeded"));

        given().when().get(BASE + "/flexibleServers" + API)
            .then().statusCode(200)
            .body("value", hasSize(1));
    }

    @Test
    @DisplayName("PATCH server updates sku without recreating")
    void patchServer() {
        createServer("patchme");
        given().contentType("application/json")
            .body("{\"sku\":{\"name\":\"Standard_B2s\",\"tier\":\"Burstable\"}}")
            .when().patch(BASE + "/flexibleServers/patchme" + API)
            .then().statusCode(200)
            .body("sku.name", equalTo("Standard_B2s"))
            .body("properties.administratorLogin", equalTo("psqladmin"));
    }

    @Test
    @DisplayName("database create/get/list/delete")
    void databaseCrud() {
        createServer("dbhost");

        given().contentType("application/json")
            .body("{\"properties\":{\"charset\":\"UTF8\",\"collation\":\"en_US.utf8\"}}")
            .when().put(BASE + "/flexibleServers/dbhost/databases/appdb" + API)
            .then().statusCode(201)
            .body("name", equalTo("appdb"))
            .body("properties.charset", equalTo("UTF8"));

        given().when().get(BASE + "/flexibleServers/dbhost/databases/appdb" + API)
            .then().statusCode(200);

        given().when().get(BASE + "/flexibleServers/dbhost/databases" + API)
            .then().statusCode(200)
            .body("value", hasSize(1));

        given().when().delete(BASE + "/flexibleServers/dbhost/databases/appdb" + API)
            .then().statusCode(204);

        given().when().get(BASE + "/flexibleServers/dbhost/databases/appdb" + API)
            .then().statusCode(404);
    }

    @Test
    @DisplayName("firewall rule create/list/delete")
    void firewallRuleCrud() {
        createServer("fwhost");

        given().contentType("application/json")
            .body("{\"properties\":{\"startIpAddress\":\"0.0.0.0\",\"endIpAddress\":\"255.255.255.255\"}}")
            .when().put(BASE + "/flexibleServers/fwhost/firewallRules/AllowAll" + API)
            .then().statusCode(201)
            .body("properties.startIpAddress", equalTo("0.0.0.0"));

        given().when().get(BASE + "/flexibleServers/fwhost/firewallRules" + API)
            .then().statusCode(200)
            .body("value", hasSize(1));

        given().when().delete(BASE + "/flexibleServers/fwhost/firewallRules/AllowAll" + API)
            .then().statusCode(204);
    }

    @Test
    @DisplayName("configuration put/get")
    void configurationPutGet() {
        createServer("cfghost");

        given().contentType("application/json")
            .body("{\"properties\":{\"value\":\"200\",\"source\":\"user-override\"}}")
            .when().put(BASE + "/flexibleServers/cfghost/configurations/max_connections" + API)
            .then().statusCode(200)
            .body("name", equalTo("max_connections"))
            .body("properties.value", equalTo("200"));

        given().when().get(BASE + "/flexibleServers/cfghost/configurations/max_connections" + API)
            .then().statusCode(200)
            .body("properties.value", equalTo("200"));
    }

    @Test
    @DisplayName("DELETE server returns 204 and removes it")
    void deleteServer() {
        createServer("deleteme");
        given().when().delete(BASE + "/flexibleServers/deleteme" + API)
            .then().statusCode(204);
        given().when().get(BASE + "/flexibleServers/deleteme" + API)
            .then().statusCode(404);
    }

    @Test
    @DisplayName("convenience /connect returns connection strings")
    void connectReturnsStrings() {
        createServer("connhost");
        given().when().get("/devstoreaccount1-postgres/flexibleServers/connhost/connect")
            .then().statusCode(200)
            .body("server", equalTo("connhost"))
            .body("jdbcUrl", containsString("jdbc:postgresql://"))
            .body("uri", containsString("postgresql://"));
    }
}
