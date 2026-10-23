package io.floci.az.services.postgres;

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
@TestProfile(PostgresHandlerMockedTest.MockedProfile.class)
@DisplayName("PostgreSQL: a server name is owned through the global name registry")
class PostgresGlobalNameTest {

    private static final String API = "?api-version=2025-08-01";
    private static final String BODY = "{\"location\":\"eastus\",\"sku\":{\"name\":\"Standard_B1ms\",\"tier\":\"Burstable\"},"
        + "\"properties\":{\"administratorLogin\":\"a\",\"administratorLoginPassword\":\"P@ssw0rd1234\",\"version\":\"16\"}}";

    private static final CloudProfile PUBLIC = CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD);

    @Inject
    GlobalNameRegistry names;

    @Inject
    PostgresState state;

    @Inject
    PostgresHandler handler;

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

    @Test
    void anAccountPathCreateDoesNotOvertakeAnArmCreateInFlight() {
        GlobalNameRegistry.Reservation armCreate = names.reserve(PUBLIC, CloudSuffix.POSTGRES, "pg-pending",
            PostgresState.serverId("sub-a", "rg", "pg-pending"));
        assertEquals(GlobalNameRegistry.Outcome.RESERVED, armCreate.outcome());

        given().contentType("application/json").body(BODY)
            .when().put("/devstoreaccount1-postgres/flexibleServers/pg-pending")
            .then().statusCode(409)
            .body("error.code", equalTo("ServerNameAlreadyExists"));

        // The ARM create fails and frees its reservation: the account path may now take the name, and keeps it.
        names.releasePending(PUBLIC, CloudSuffix.POSTGRES, "pg-pending", armCreate.token());
        assertAvailable("pg-pending", true);
        given().contentType("application/json").body(BODY)
            .when().put("/devstoreaccount1-postgres/flexibleServers/pg-pending").then().statusCode(202);
        assertAvailable("pg-pending", false);
    }

    @Test
    void aDeleteDoesNotFreeTheNameOfAServerCreatedAgainAtItsId() {
        given().contentType("application/json").body(BODY)
            .when().put("/devstoreaccount1-postgres/flexibleServers/pg-again").then().statusCode(202);
        PostgresState.ServerEntry deleted = state.getServer("pg-again").orElseThrow();

        // A delete removes the server, then a create at the same id runs to completion before the delete frees
        // the name.
        state.removeServer(PostgresState.serverId(deleted));
        given().contentType("application/json").body(BODY)
            .when().put("/devstoreaccount1-postgres/flexibleServers/pg-again").then().statusCode(202);
        handler.releaseNameIfGone(PUBLIC, deleted);

        assertAvailable("pg-again", false);
    }
}
