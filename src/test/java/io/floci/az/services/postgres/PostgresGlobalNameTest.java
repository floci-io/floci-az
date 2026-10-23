package io.floci.az.services.postgres;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(PostgresHandlerMockedTest.MockedProfile.class)
@DisplayName("PostgreSQL: a server name is owned through the global name registry")
class PostgresGlobalNameTest {

    private static final String API = "?api-version=2025-08-01";
    private static final String BODY = "{\"location\":\"eastus\",\"sku\":{\"name\":\"Standard_B1ms\",\"tier\":\"Burstable\"},"
        + "\"properties\":{\"administratorLogin\":\"a\",\"administratorLoginPassword\":\"P@ssw0rd1234\",\"version\":\"16\"}}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String server(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.DBforPostgreSQL/flexibleServers/"
            + name + API;
    }

    private static void assertAvailable(String name, boolean available) {
        given().contentType("application/json").body("{\"name\":\"" + name + "\",\"type\":\"Microsoft.DBforPostgreSQL/flexibleServers\"}")
            .when().post("/subscriptions/sub-check/providers/Microsoft.DBforPostgreSQL/locations/eastus/checkNameAvailability" + API)
            .then().statusCode(200)
            .body("name", equalTo(name))
            .body("nameAvailable", equalTo(available));
    }

    @Test
    void aNameIsTakenWhileItsServerExistsAndFreeAfterItsDelete() {
        assertAvailable("pg-owned", true);
        given().contentType("application/json").body(BODY).when().put(server("sub-a", "rg", "pg-owned")).then().statusCode(202);
        assertAvailable("PG-OWNED", false);

        given().contentType("application/json").body(BODY).when().put(server("sub-b", "rg", "pg-owned"))
            .then().statusCode(409)
            .body("error.code", equalTo("ServerNameAlreadyExists"))
            .body("error.message", equalTo("Specified server name is already used."));
        given().contentType("application/json").body(BODY).when().put(server("sub-a", "rg-other", "pg-owned"))
            .then().statusCode(409)
            .body("error.code", equalTo("ServerNameAlreadyExists"));

        given().when().delete(server("sub-a", "rg", "pg-owned")).then().statusCode(204);
        assertAvailable("pg-owned", true);
        given().contentType("application/json").body(BODY).when().put(server("sub-b", "rg", "pg-owned")).then().statusCode(202);
    }

    @Test
    void aServerCreatedOnTheAccountPathOwnsItsName() {
        given().contentType("application/json").body(BODY)
            .when().put("/devstoreaccount1-postgres/flexibleServers/pg-account").then().statusCode(202);
        assertAvailable("pg-account", false);
        given().contentType("application/json").body(BODY).when().put(server("sub-b", "rg", "pg-account"))
            .then().statusCode(409)
            .body("error.code", equalTo("ServerNameAlreadyExists"));

        given().when().delete("/devstoreaccount1-postgres/flexibleServers/pg-account").then().statusCode(204);
        assertAvailable("pg-account", true);
    }
}
