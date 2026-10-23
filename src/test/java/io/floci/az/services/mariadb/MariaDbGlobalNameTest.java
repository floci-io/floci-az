package io.floci.az.services.mariadb;

import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(MariaDbHandlerMockedTest.MockedProfile.class)
@DisplayName("MariaDB: a server name is owned through the global name registry")
class MariaDbGlobalNameTest {

    private static final String API = "?api-version=2018-06-01";
    private static final String BODY = "{\"location\":\"eastus\","
        + "\"properties\":{\"administratorLogin\":\"a\",\"administratorLoginPassword\":\"P@ssw0rd1234\",\"version\":\"10.3\"}}";

    private static final CloudProfile PUBLIC = CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD);

    @Inject
    GlobalNameRegistry names;

    @Inject
    MariaDbState state;

    @Inject
    MariaDbHandler handler;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String server(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.DBforMariaDB/servers/"
            + name + API;
    }

    private static void assertAvailable(String name, boolean available) {
        given().contentType("application/json").body("{\"name\":\"" + name + "\",\"type\":\"Microsoft.DBforMariaDB/servers\"}")
            .when().post("/subscriptions/sub-check/providers/Microsoft.DBforMariaDB/locations/eastus/checkNameAvailability" + API)
            .then().statusCode(200)
            .body("name", equalTo(name))
            .body("nameAvailable", equalTo(available));
    }

    @Test
    void aNameIsTakenWhileItsServerExistsAndFreeAfterItsDelete() {
        assertAvailable("md-owned", true);
        given().contentType("application/json").body(BODY).when().put(server("sub-a", "rg", "md-owned")).then().statusCode(201);
        assertAvailable("MD-OWNED", false);

        given().contentType("application/json").body(BODY).when().put(server("sub-b", "rg", "md-owned"))
            .then().statusCode(409)
            .body("error.code", equalTo("ServerNameAlreadyExists"))
            .body("error.message", equalTo("Specified server name is already used."));
        given().contentType("application/json").body(BODY).when().put(server("sub-a", "rg-other", "md-owned"))
            .then().statusCode(409)
            .body("error.code", equalTo("ServerNameAlreadyExists"));

        given().when().delete(server("sub-a", "rg", "md-owned")).then().statusCode(204);
        assertAvailable("md-owned", true);
        given().contentType("application/json").body(BODY).when().put(server("sub-b", "rg", "md-owned")).then().statusCode(201);
    }

    @Test
    void aServerCreatedOnTheAccountPathOwnsItsName() {
        given().contentType("application/json").body(BODY)
            .when().put("/devstoreaccount1-mariadb/servers/md-account").then().statusCode(201);
        assertAvailable("md-account", false);
        given().contentType("application/json").body(BODY).when().put(server("sub-b", "rg", "md-account"))
            .then().statusCode(409)
            .body("error.code", equalTo("ServerNameAlreadyExists"));

        given().when().delete("/devstoreaccount1-mariadb/servers/md-account").then().statusCode(204);
        assertAvailable("md-account", true);
    }

    @Test
    void anAccountPathCreateDoesNotOvertakeAnArmCreateInFlight() {
        GlobalNameRegistry.Reservation armCreate = names.reserve(PUBLIC, CloudSuffix.MARIADB, "md-pending",
            MariaDbState.serverId("sub-a", "rg", "md-pending"));
        assertEquals(GlobalNameRegistry.Outcome.RESERVED, armCreate.outcome());

        given().contentType("application/json").body(BODY)
            .when().put("/devstoreaccount1-mariadb/servers/md-pending")
            .then().statusCode(409)
            .body("error.code", equalTo("ServerNameAlreadyExists"));

        // The ARM create fails and frees its reservation: the account path may now take the name, and keeps it.
        names.releasePending(PUBLIC, CloudSuffix.MARIADB, "md-pending", armCreate.token());
        assertAvailable("md-pending", true);
        given().contentType("application/json").body(BODY)
            .when().put("/devstoreaccount1-mariadb/servers/md-pending").then().statusCode(201);
        assertAvailable("md-pending", false);
    }

    @Test
    void aDeleteDoesNotFreeTheNameOfAServerCreatedAgainAtItsId() {
        given().contentType("application/json").body(BODY)
            .when().put("/devstoreaccount1-mariadb/servers/md-again").then().statusCode(201);
        MariaDbState.ServerEntry deleted = state.getServer("md-again").orElseThrow();

        // A delete removes the server, then a create at the same id runs to completion before the delete frees
        // the name.
        state.removeServer(MariaDbState.serverId(deleted));
        given().contentType("application/json").body(BODY)
            .when().put("/devstoreaccount1-mariadb/servers/md-again").then().statusCode(201);
        handler.releaseNameIfGone(PUBLIC, deleted);

        assertAvailable("md-again", false);
    }
}
