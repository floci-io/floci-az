package io.floci.az.services.sql;

import io.floci.az.core.arm.ArmResourceId;
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
@TestProfile(SqlHandlerMockedTest.MockedProfile.class)
@DisplayName("SQL: a server name is a global name held in the name registry")
class SqlGlobalNameTest {

    private static final String API = "?api-version=2021-11-01";
    private static final String BODY = "{\"location\":\"eastus\",\"properties\":{"
        + "\"administratorLogin\":\"sa\",\"administratorLoginPassword\":\"FlociAz_Strong123!\"}}";
    private static final String CHECK =
        "/subscriptions/sub-check/providers/Microsoft.Sql/checkNameAvailability" + API;

    private static final CloudProfile PUBLIC = CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD);

    @Inject
    GlobalNameRegistry names;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String server(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Sql/servers/" + name;
    }

    private static String check(String name) {
        return "{\"name\":\"" + name + "\",\"type\":\"Microsoft.Sql/servers\"}";
    }

    @Test
    void aNameOwnedByAnotherSubscriptionOrGroupIsRefusedWithAzureSqlsConflict() {
        given().contentType("application/json").body(BODY).when().put(server("sub-owner", "rg", "ownedsrv") + API)
            .then().statusCode(201);
        given().contentType("application/json").body(BODY).when().put(server("sub-other", "rg", "ownedsrv") + API)
            .then().statusCode(400).body("error.code", equalTo("NameAlreadyExists"))
            .body("error.message", equalTo("The name 'ownedsrv' already exists. Choose a different name."));
        given().contentType("application/json").body(BODY).when().put(server("sub-owner", "rg-two", "ownedsrv") + API)
            .then().statusCode(400).body("error.code", equalTo("NameAlreadyExists"));
        given().when().get(server("sub-owner", "rg-two", "ownedsrv") + "/databases/master" + API)
            .then().statusCode(404);
    }

    @Test
    void checkNameAvailabilityAnswersFromTheRegistry() {
        given().contentType("application/json").body(check("checksrv")).when().post(CHECK).then().statusCode(200)
            .body("available", equalTo(true));
        given().contentType("application/json").body(BODY).when().put(server("sub-owner", "rg", "checksrv") + API)
            .then().statusCode(201);
        given().contentType("application/json").body(check("checksrv")).when().post(CHECK).then().statusCode(200)
            .body("available", equalTo(false))
            .body("reason", equalTo("AlreadyExists"))
            .body("message", equalTo("Server name 'checksrv' is already taken."));
        given().when().delete(server("sub-owner", "rg", "checksrv") + API).then().statusCode(204);
        given().contentType("application/json").body(check("checksrv")).when().post(CHECK).then().statusCode(200)
            .body("available", equalTo(true));
    }

    @Test
    void aServerCreatedThroughTheConveniencePathHoldsItsNameUntilDeleted() {
        given().contentType("application/json").body(BODY).when().put("/devstoreaccount1-sql/servers/convsrv")
            .then().statusCode(201);
        given().contentType("application/json").body(check("convsrv")).when().post(CHECK).then().statusCode(200)
            .body("available", equalTo(false));
        given().contentType("application/json").body(BODY).when().put(server("sub-arm", "rg", "convsrv") + API)
            .then().statusCode(400).body("error.code", equalTo("NameAlreadyExists"));

        given().when().delete("/devstoreaccount1-sql/servers/convsrv").then().statusCode(204);
        given().contentType("application/json").body(BODY).when().put(server("sub-arm", "rg", "convsrv") + API)
            .then().statusCode(201);
    }

    @Test
    void anAccountPathUpdateKeepsTheServerAndItsNameWhereTheyAre() {
        given().contentType("application/json").body(BODY).when().put(server("sub-owner", "rg", "keptsrv") + API)
            .then().statusCode(201);
        given().contentType("application/json").body(BODY).when().put("/devstoreaccount1-sql/servers/keptsrv")
            .then().statusCode(200)
            .body("id", equalTo(server("sub-owner", "rg", "keptsrv")));
        given().when().get(server("sub-owner", "rg", "keptsrv") + API).then().statusCode(200);

        given().when().delete("/devstoreaccount1-sql/servers/keptsrv").then().statusCode(204);
        given().contentType("application/json").body(check("keptsrv")).when().post(CHECK).then().statusCode(200)
            .body("available", equalTo(true));
        given().contentType("application/json").body(BODY).when().put(server("sub-other", "rg", "keptsrv") + API)
            .then().statusCode(201);
    }

    @Test
    void anAccountPathCreateDoesNotOvertakeAnArmCreateInFlight() {
        GlobalNameRegistry.Reservation armCreate = names.reserve(PUBLIC, CloudSuffix.SQL, "pendingsrv",
            ArmResourceId.parse(server("sub-arm", "rg", "pendingsrv")).orElseThrow());
        assertEquals(GlobalNameRegistry.Outcome.RESERVED, armCreate.outcome());

        given().contentType("application/json").body(BODY).when().put("/devstoreaccount1-sql/servers/pendingsrv")
            .then().statusCode(400).body("error.code", equalTo("NameAlreadyExists"));

        // The ARM create fails and frees its reservation: the account path may now take the name, and keeps it.
        names.releasePending(PUBLIC, CloudSuffix.SQL, "pendingsrv", armCreate.token());
        given().contentType("application/json").body(check("pendingsrv")).when().post(CHECK).then().statusCode(200)
            .body("available", equalTo(true));
        given().contentType("application/json").body(BODY).when().put("/devstoreaccount1-sql/servers/pendingsrv")
            .then().statusCode(201);
        given().contentType("application/json").body(check("pendingsrv")).when().post(CHECK).then().statusCode(200)
            .body("available", equalTo(false));
    }
}
