package io.floci.az.services.mariadb;

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
 * Tests for {@link MariaDbHandler} in {@code mocked=true} mode.
 * Exercises the full server + child-resource CRUD surface without Docker.
 */
@QuarkusTest
@TestProfile(MariaDbHandlerMockedTest.MockedProfile.class)
@DisplayName("MariaDbHandler — mocked mode (no Docker)")
@SuppressWarnings("unused")
class MariaDbHandlerMockedTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.maria-db.mocked", "true");
        }
    }

    private static final String SUB  = "test-sub-mariadb-mocked";
    private static final String RG   = "test-rg-mariadb-mocked";
    private static final String BASE = "/subscriptions/" + SUB + "/resourceGroups/" + RG
                                     + "/providers/Microsoft.DBforMariaDB";
    private static final String API  = "?api-version=2018-06-01";

    private static final String SERVER_BODY =
        "{\"location\":\"eastus\","
        + "\"properties\":{"
        + "\"administratorLogin\":\"mariadbadmin\","
        + "\"administratorLoginPassword\":\"FlociAz_Strong123!\","
        + "\"version\":\"10.3\","
        + "\"storageProfile\":{\"storageMB\":5120},"
        + "\"sslEnforcement\":\"Disabled\"}}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private void createServer(String name) {
        given().contentType("application/json").body(SERVER_BODY)
            .when().put(BASE + "/servers/" + name + API)
            .then().statusCode(201);
    }

    @Test
    @DisplayName("PUT server returns 201 with userVisibleState=Ready and provisioningState=Succeeded")
    void putServerReady() {
        given().contentType("application/json").body(SERVER_BODY)
            .when().put(BASE + "/servers/mariadbserver" + API)
            .then().statusCode(201)
            .body("name", equalTo("mariadbserver"))
            .body("type", equalTo("Microsoft.DBforMariaDB/servers"))
            .body("properties.version", equalTo("10.3"))
            .body("properties.administratorLogin", equalTo("mariadbadmin"))
            .body("properties.userVisibleState", equalTo("Ready"))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.storageProfile.storageMB", equalTo(5120))
            .body("properties.fullyQualifiedDomainName", notNullValue())
            .body("properties", not(hasKey("localPort")));
    }

    @Test
    @DisplayName("created server is gettable and listable")
    void createdServerVisible() {
        createServer("listme");

        given().when().get(BASE + "/servers/listme" + API)
            .then().statusCode(200)
            .body("properties.provisioningState", equalTo("Succeeded"));

        given().when().get(BASE + "/servers" + API)
            .then().statusCode(200)
            .body("value", hasSize(1));
    }

    @Test
    @DisplayName("GET unknown server returns 404")
    void getUnknownServer() {
        given().when().get(BASE + "/servers/ghost" + API)
            .then().statusCode(404);
    }

    @Test
    @DisplayName("PATCH server updates version without recreating")
    void patchServer() {
        createServer("patchme");
        given().contentType("application/json")
            .body("{\"properties\":{\"version\":\"10.6\"}}")
            .when().patch(BASE + "/servers/patchme" + API)
            .then().statusCode(200)
            .body("properties.version", equalTo("10.6"))
            .body("properties.administratorLogin", equalTo("mariadbadmin"));
    }

    @Test
    @DisplayName("database create/get/list/delete")
    void databaseCrud() {
        createServer("dbhost");

        given().contentType("application/json")
            .body("{\"properties\":{\"charset\":\"utf8mb4\",\"collation\":\"utf8mb4_general_ci\"}}")
            .when().put(BASE + "/servers/dbhost/databases/appdb" + API)
            .then().statusCode(201)
            .body("name", equalTo("appdb"))
            .body("type", equalTo("Microsoft.DBforMariaDB/servers/databases"))
            .body("properties.charset", equalTo("utf8mb4"));

        given().when().get(BASE + "/servers/dbhost/databases/appdb" + API)
            .then().statusCode(200);

        given().when().get(BASE + "/servers/dbhost/databases" + API)
            .then().statusCode(200)
            .body("value", hasSize(1));

        given().when().delete(BASE + "/servers/dbhost/databases/appdb" + API)
            .then().statusCode(204);

        given().when().get(BASE + "/servers/dbhost/databases/appdb" + API)
            .then().statusCode(404);
    }

    @Test
    @DisplayName("firewall rule create/list/delete")
    void firewallRuleCrud() {
        createServer("fwhost");

        given().contentType("application/json")
            .body("{\"properties\":{\"startIpAddress\":\"0.0.0.0\",\"endIpAddress\":\"255.255.255.255\"}}")
            .when().put(BASE + "/servers/fwhost/firewallRules/AllowAll" + API)
            .then().statusCode(201)
            .body("name", equalTo("AllowAll"))
            .body("type", equalTo("Microsoft.DBforMariaDB/servers/firewallRules"))
            .body("properties.startIpAddress", equalTo("0.0.0.0"));

        given().when().get(BASE + "/servers/fwhost/firewallRules" + API)
            .then().statusCode(200)
            .body("value", hasSize(1));

        given().when().delete(BASE + "/servers/fwhost/firewallRules/AllowAll" + API)
            .then().statusCode(204);
    }

    @Test
    @DisplayName("configuration put/get")
    void configurationPutGet() {
        createServer("cfghost");

        given().contentType("application/json")
            .body("{\"properties\":{\"value\":\"150\",\"source\":\"user-override\"}}")
            .when().put(BASE + "/servers/cfghost/configurations/max_connections" + API)
            .then().statusCode(200)
            .body("name", equalTo("max_connections"))
            .body("properties.value", equalTo("150"));

        given().when().get(BASE + "/servers/cfghost/configurations/max_connections" + API)
            .then().statusCode(200)
            .body("properties.value", equalTo("150"));
    }

    @Test
    @DisplayName("DELETE server returns 204 and removes it")
    void deleteServer() {
        createServer("deleteme");
        given().when().delete(BASE + "/servers/deleteme" + API)
            .then().statusCode(204);
        given().when().get(BASE + "/servers/deleteme" + API)
            .then().statusCode(404);
    }

    @Test
    @DisplayName("convenience /connect returns connection strings")
    void connectReturnsStrings() {
        createServer("connhost");
        given().when().get("/devstoreaccount1-mariadb/servers/connhost/connect")
            .then().statusCode(200)
            .body("server", equalTo("connhost"))
            .body("jdbcUrl", containsString("jdbc:mariadb://"))
            .body("uri", containsString("mariadb://"));
    }

    @Test
    @DisplayName("checkNameAvailability returns available=true for new name")
    void checkNameAvailability() {
        given().contentType("application/json")
            .body("{\"name\":\"newserver\",\"type\":\"Microsoft.DBforMariaDB/servers\"}")
            .when().post(BASE + "/checkNameAvailability" + API)
            .then().statusCode(200)
            .body("nameAvailable", equalTo(true));
    }
}
