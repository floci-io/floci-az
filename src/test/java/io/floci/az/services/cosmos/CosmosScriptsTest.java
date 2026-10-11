package io.floci.az.services.cosmos;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Stored procedures, triggers and user-defined functions: metadata CRUD under a container, and the
 * Cosmos error envelope for what the emulator does not do (running a stored procedure).
 */
@QuarkusTest
class CosmosScriptsTest {

    private static final String BASE = "/scriptacct-cosmos";
    private static final String DB   = "scriptdb";
    private static final String COLL = BASE + "/dbs/" + DB + "/colls/items";

    private static final String SPROC =
            "{\"id\":\"hello\",\"body\":\"function () { getContext().getResponse().setBody('hi'); }\"}";
    private static final String TRIGGER =
            "{\"id\":\"stamp\",\"body\":\"function () {}\",\"triggerType\":\"Pre\",\"triggerOperation\":\"Create\"}";
    private static final String UDF =
            "{\"id\":\"tax\",\"body\":\"function (x) { return x * 0.1; }\"}";

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
        createDatabaseAndContainer();
    }

    private void createDatabaseAndContainer() {
        given().contentType("application/json").body("{\"id\":\"" + DB + "\"}")
                .when().post(BASE + "/dbs").then().statusCode(201);
        given().contentType("application/json")
                .body("{\"id\":\"items\",\"partitionKey\":{\"paths\":[\"/pk\"],\"kind\":\"Hash\"}}")
                .when().post(BASE + "/dbs/" + DB + "/colls").then().statusCode(201);
    }

    private Response create(String segment, String json) {
        return given().contentType("application/json").body(json).when().post(COLL + "/" + segment);
    }

    @Test
    void storedProcedureRoundTrip() {
        Response created = create("sprocs", SPROC);
        created.then().statusCode(201)
                .header("x-ms-alt-content-path", is("dbs/" + DB + "/colls/items"))
                .body("id", is("hello"))
                .body("body", containsString("setBody"))
                .body("_rid", notNullValue())
                .body("_self", is("dbs/" + DB + "/colls/items/sprocs/hello/"))
                .body("_etag", notNullValue())
                .body("_ts", notNullValue());
        String rid = created.path("_rid");

        given().when().get(COLL + "/sprocs/hello")
                .then().statusCode(200).body("id", is("hello")).body("_rid", is(rid));

        given().when().get(COLL + "/sprocs")
                .then().statusCode(200)
                .header("x-ms-item-count", is("1"))
                .body("_count", is(1))
                .body("StoredProcedures.id", contains("hello"));

        Response replaced = given().contentType("application/json")
                .body("{\"id\":\"hello\",\"body\":\"function () { return 2; }\"}")
                .when().put(COLL + "/sprocs/hello");
        replaced.then().statusCode(200).body("body", is("function () { return 2; }")).body("_rid", is(rid));
        assertNotEquals((String) created.path("_etag"), (String) replaced.path("_etag"));

        given().when().delete(COLL + "/sprocs/hello").then().statusCode(204);
        given().when().get(COLL + "/sprocs/hello").then().statusCode(404).body("code", is("NotFound"));
        given().when().get(COLL + "/sprocs").then().statusCode(200).body("StoredProcedures", empty());
    }

    @Test
    void storedProcedureRidIsAStoredProcedureChildOfTheContainer() {
        String collRid = given().when().get(COLL).then().statusCode(200).extract().path("_rid");
        String rid = create("sprocs", SPROC).then().statusCode(201).extract().path("_rid");

        byte[] bytes = Base64.getDecoder().decode(rid.replace('-', '/'));
        byte[] coll  = Base64.getDecoder().decode(collRid.replace('-', '/'));
        assertEquals(16, bytes.length);
        assertEquals(Arrays.toString(Arrays.copyOf(coll, 8)),
                Arrays.toString(Arrays.copyOf(bytes, 8)));
        assertEquals(0x8, (bytes[15] & 0xF0) >> 4);
    }

    @Test
    void triggerRoundTripKeepsTypeAndOperation() {
        create("triggers", TRIGGER).then().statusCode(201)
                .body("triggerType", is("Pre"))
                .body("triggerOperation", is("Create"))
                .body("_self", endsWith("/triggers/stamp/"));

        given().when().get(COLL + "/triggers/stamp")
                .then().statusCode(200).body("triggerType", is("Pre")).body("triggerOperation", is("Create"));
        given().when().get(COLL + "/triggers")
                .then().statusCode(200).body("Triggers.id", contains("stamp"));

        given().contentType("application/json")
                .body("{\"id\":\"stamp\",\"body\":\"function () {}\",\"triggerType\":\"post\",\"triggerOperation\":\"all\"}")
                .when().put(COLL + "/triggers/stamp")
                .then().statusCode(200).body("triggerType", is("post")).body("triggerOperation", is("all"));

        given().when().delete(COLL + "/triggers/stamp").then().statusCode(204);
        given().when().get(COLL + "/triggers/stamp").then().statusCode(404);
    }

    @Test
    void userDefinedFunctionRoundTrip() {
        create("udfs", UDF).then().statusCode(201).body("id", is("tax"));
        given().when().get(COLL + "/udfs/tax").then().statusCode(200).body("body", containsString("0.1"));
        given().when().get(COLL + "/udfs").then().statusCode(200).body("UserDefinedFunctions.id", contains("tax"));
        given().when().delete(COLL + "/udfs/tax").then().statusCode(204);
        given().when().get(COLL + "/udfs").then().statusCode(200).body("UserDefinedFunctions", empty());
    }

    @Test
    void scriptKindsAreSeparateNamespaces() {
        create("sprocs", "{\"id\":\"same\",\"body\":\"function () {}\"}").then().statusCode(201);
        create("udfs", "{\"id\":\"same\",\"body\":\"function () {}\"}").then().statusCode(201);
        given().when().get(COLL + "/triggers").then().statusCode(200).body("Triggers", empty());
    }

    @Test
    void creatingAnExistingScriptConflicts() {
        create("sprocs", SPROC).then().statusCode(201);
        create("sprocs", SPROC).then().statusCode(409).body("code", is("Conflict")).body("message", notNullValue());
    }

    @Test
    void invalidScriptsAreRejectedWithACosmosError() {
        create("sprocs", "{\"id\":\"nobody\"}").then().statusCode(400).body("code", is("BadRequest"));
        create("udfs", "{\"body\":\"function () {}\"}").then().statusCode(400).body("code", is("BadRequest"));
        create("triggers", "{\"id\":\"t\",\"body\":\"function () {}\",\"triggerOperation\":\"All\"}")
                .then().statusCode(400).body("code", is("BadRequest"));
        create("triggers", "{\"id\":\"t\",\"body\":\"function () {}\",\"triggerType\":\"Pre\",\"triggerOperation\":\"Upsert\"}")
                .then().statusCode(400).body("code", is("BadRequest"));
    }

    @Test
    void replacingAMissingScriptIsNotFound() {
        given().contentType("application/json").body(SPROC)
                .when().put(COLL + "/sprocs/hello")
                .then().statusCode(404).body("code", is("NotFound"));
    }

    @Test
    void replaceHonoursIfMatch() {
        create("sprocs", SPROC).then().statusCode(201);
        given().contentType("application/json").header("If-Match", "\"stale\"").body(SPROC)
                .when().put(COLL + "/sprocs/hello")
                .then().statusCode(412).body("code", is("PreconditionFailed"));
    }

    @Test
    void scriptsCanBeQueried() {
        create("sprocs", SPROC).then().statusCode(201);
        create("sprocs", "{\"id\":\"other\",\"body\":\"function () {}\"}").then().statusCode(201);

        given().contentType("application/query+json").header("x-ms-documentdb-isquery", "True")
                .body("{\"query\":\"SELECT * FROM root r WHERE r.id = @id\","
                        + "\"parameters\":[{\"name\":\"@id\",\"value\":\"other\"}]}")
                .when().post(COLL + "/sprocs")
                .then().statusCode(200).body("StoredProcedures.id", contains("other"));
    }

    @Test
    void scriptQueriesArePagedByMaxItemCountAndContinuation() {
        for (String id : List.of("a", "b", "c")) {
            create("sprocs", "{\"id\":\"" + id + "\",\"body\":\"function () {}\"}").then().statusCode(201);
        }
        String query = "{\"query\":\"SELECT * FROM root r ORDER BY r.id\"}";

        Response first = given().contentType("application/query+json").header("x-ms-documentdb-isquery", "True")
                .header("x-ms-max-item-count", "2").body(query)
                .when().post(COLL + "/sprocs");
        first.then().statusCode(200)
                .header("x-ms-item-count", is("2"))
                .header("x-ms-continuation", notNullValue())
                .body("_count", is(2))
                .body("StoredProcedures.id", contains("a", "b"));
        String continuation = first.header("x-ms-continuation");

        given().contentType("application/query+json").header("x-ms-documentdb-isquery", "True")
                .header("x-ms-max-item-count", "2").header("x-ms-continuation", continuation).body(query)
                .when().post(COLL + "/sprocs")
                .then().statusCode(200)
                .header("x-ms-continuation", nullValue())
                .body("StoredProcedures.id", contains("c"));

        given().contentType("application/query+json").header("x-ms-documentdb-isquery", "True")
                .header("x-ms-continuation", continuation).body(query)
                .when().post(COLL + "/udfs")
                .then().statusCode(400).body("code", is("BadRequest"));
    }

    @Test
    void scriptFeedsArePagedByMaxItemCountAndContinuation() {
        for (String id : List.of("a", "b", "c")) {
            create("udfs", "{\"id\":\"" + id + "\",\"body\":\"function () {}\"}").then().statusCode(201);
        }
        Set<String> seen = new HashSet<>();
        String continuation = null;
        int pages = 0;
        do {
            RequestSpecification request = given().header("x-ms-max-item-count", "1");
            if (continuation != null) {
                request = request.header("x-ms-continuation", continuation);
            }
            Response page = request.when().get(COLL + "/udfs");
            page.then().statusCode(200).body("_count", is(1));
            seen.addAll(page.jsonPath().getList("UserDefinedFunctions.id", String.class));
            continuation = page.header("x-ms-continuation");
            pages++;
        } while (continuation != null && pages < 10);

        assertEquals(3, pages);
        assertEquals(Set.of("a", "b", "c"), seen);
    }

    @Test
    void scriptIdsContainingTheKeySeparatorStayInTheirOwnContainer() {
        String other = "items|sprocs|nested";
        String otherColl = BASE + "/dbs/" + DB + "/colls/" + other;
        given().contentType("application/json")
                .body("{\"id\":\"" + other + "\",\"partitionKey\":{\"paths\":[\"/pk\"],\"kind\":\"Hash\"}}")
                .when().post(BASE + "/dbs/" + DB + "/colls").then().statusCode(201);

        create("sprocs", "{\"id\":\"nested|sprocs|hello\",\"body\":\"function () {}\"}").then().statusCode(201);

        given().when().get(otherColl + "/sprocs/hello").then().statusCode(404);
        given().when().get(otherColl + "/sprocs").then().statusCode(200).body("StoredProcedures", empty());
        given().contentType("application/json").body(SPROC)
                .when().post(otherColl + "/sprocs").then().statusCode(201);
        given().when().get(COLL + "/sprocs/nested|sprocs|hello")
                .then().statusCode(200).body("body", is("function () {}"));
    }

    @Test
    void deletingAContainerKeepsScriptsOfAContainerWhoseIdExtendsIts() {
        String backupColl = BASE + "/dbs/" + DB + "/colls/items|backup";
        given().contentType("application/json")
                .body("{\"id\":\"items|backup\",\"partitionKey\":{\"paths\":[\"/pk\"],\"kind\":\"Hash\"}}")
                .when().post(BASE + "/dbs/" + DB + "/colls").then().statusCode(201);
        given().contentType("application/json").body(SPROC)
                .when().post(backupColl + "/sprocs").then().statusCode(201);

        given().when().delete(COLL).then().statusCode(204);

        given().when().get(backupColl + "/sprocs/hello").then().statusCode(200).body("id", is("hello"));
    }

    @Test
    void deletingTheDatabaseRemovesItsScripts() {
        create("sprocs", SPROC).then().statusCode(201);
        given().when().delete(BASE + "/dbs/" + DB).then().statusCode(204);
        createDatabaseAndContainer();
        given().when().get(COLL + "/sprocs").then().statusCode(200).body("StoredProcedures", empty());
    }

    @Test
    void replacingWithoutAStringIdMatchingTheUrlIsRejected() {
        create("sprocs", "{\"id\":\"123\",\"body\":\"function () { return 1; }\"}").then().statusCode(201);

        for (String body : List.of(
                "{\"body\":\"function () {}\"}",
                "{\"id\":null,\"body\":\"function () {}\"}",
                "{\"id\":123,\"body\":\"function () {}\"}",
                "{\"id\":\" \",\"body\":\"function () {}\"}",
                "{\"id\":\"other\",\"body\":\"function () {}\"}")) {
            given().contentType("application/json").body(body)
                    .when().put(COLL + "/sprocs/123")
                    .then().statusCode(400).body("code", is("BadRequest")).body("message", notNullValue());
        }

        given().when().get(COLL + "/sprocs/123")
                .then().statusCode(200).body("body", is("function () { return 1; }"));
    }

    @Test
    void executingAStoredProcedureFailsInTheCosmosEnvelope() {
        create("sprocs", SPROC).then().statusCode(201);
        given().contentType("application/json").header("x-ms-documentdb-partitionkey", "[\"a\"]").body("[]")
                .when().post(COLL + "/sprocs/hello")
                .then().statusCode(501)
                .contentType(containsString("application/json"))
                .body("code", is("NotImplemented"))
                .body("message", containsString("not supported"));
    }

    @Test
    void executingAMissingStoredProcedureIsNotFound() {
        given().contentType("application/json").body("[]")
                .when().post(COLL + "/sprocs/missing")
                .then().statusCode(404).body("code", is("NotFound"));
    }

    @Test
    void scriptsUnderAMissingContainerAreNotFound() {
        given().contentType("application/json").body(SPROC)
                .when().post(BASE + "/dbs/" + DB + "/colls/nope/sprocs")
                .then().statusCode(404).body("code", is("NotFound"));
    }

    @Test
    void deletingTheContainerRemovesItsScripts() {
        create("sprocs", SPROC).then().statusCode(201);
        given().when().delete(COLL).then().statusCode(204);
        given().contentType("application/json")
                .body("{\"id\":\"items\",\"partitionKey\":{\"paths\":[\"/pk\"],\"kind\":\"Hash\"}}")
                .when().post(BASE + "/dbs/" + DB + "/colls").then().statusCode(201);
        given().when().get(COLL + "/sprocs").then().statusCode(200).body("StoredProcedures", empty());
    }

    @Test
    void unsupportedOperationsCarryACosmosErrorBody() {
        given().contentType("application/json").body("{}").when().patch(BASE + "/dbs/" + DB)
                .then().statusCode(501)
                .contentType(containsString("application/json"))
                .body("code", is("NotImplemented"))
                .body("message", notNullValue());
    }
}
