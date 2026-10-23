package io.floci.az.services.mysql;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(MySqlHandlerMockedTest.MockedProfile.class)
@DisplayName("MySQL: a server name is owned through the global name registry")
class MySqlGlobalNameTest {

    private static final String API = "?api-version=2023-12-30";
    private static final String BODY = "{\"location\":\"eastus\",\"sku\":{\"name\":\"Standard_B1ms\",\"tier\":\"Burstable\"},"
        + "\"properties\":{\"administratorLogin\":\"a\",\"administratorLoginPassword\":\"P@ssw0rd1234\",\"version\":\"8.0.21\"}}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String server(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.DBforMySQL/flexibleServers/"
            + name + API;
    }

    private static void assertAvailable(String name, boolean available) {
        given().contentType("application/json").body("{\"name\":\"" + name + "\",\"type\":\"Microsoft.DBforMySQL/flexibleServers\"}")
            .when().post("/subscriptions/sub-check/providers/Microsoft.DBforMySQL/locations/eastus/checkNameAvailability" + API)
            .then().statusCode(200)
            .body("name", equalTo(name))
            .body("nameAvailable", equalTo(available));
    }

    @Test
    void aNameIsTakenWhileItsServerExistsAndFreeAfterItsDelete() {
        assertAvailable("my-owned", true);
        given().contentType("application/json").body(BODY).when().put(server("sub-a", "rg", "my-owned")).then().statusCode(201);
        assertAvailable("MY-OWNED", false);

        given().contentType("application/json").body(BODY).when().put(server("sub-b", "rg", "my-owned"))
            .then().statusCode(409)
            .body("error.code", equalTo("ServerNameAlreadyExists"))
            .body("error.message", equalTo("Specified server name is already used."));
        given().contentType("application/json").body(BODY).when().put(server("sub-a", "rg-other", "my-owned"))
            .then().statusCode(409)
            .body("error.code", equalTo("ServerNameAlreadyExists"));

        given().when().delete(server("sub-a", "rg", "my-owned")).then().statusCode(204);
        assertAvailable("my-owned", true);
        given().contentType("application/json").body(BODY).when().put(server("sub-b", "rg", "my-owned")).then().statusCode(201);
    }

    @Test
    void aServerCreatedOnTheAccountPathOwnsItsName() {
        given().contentType("application/json").body(BODY)
            .when().put("/devstoreaccount1-mysql/flexibleServers/my-account").then().statusCode(201);
        assertAvailable("my-account", false);
        given().contentType("application/json").body(BODY).when().put(server("sub-b", "rg", "my-account"))
            .then().statusCode(409)
            .body("error.code", equalTo("ServerNameAlreadyExists"));

        given().when().delete("/devstoreaccount1-mysql/flexibleServers/my-account").then().statusCode(204);
        assertAvailable("my-account", true);
    }
}
