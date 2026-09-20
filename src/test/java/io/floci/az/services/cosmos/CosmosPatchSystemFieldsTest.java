package io.floci.az.services.cosmos;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Cosmos owns {@code _rid}, {@code _self}, {@code _etag}, {@code _ts}, {@code _attachments} and {@code id};
 * a patch may not change them. Removing {@code _rid} used to strand the container: it is the query engine's
 * sort tiebreak and continuation bookmark, so every later query failed.
 */
@QuarkusTest
class CosmosPatchSystemFieldsTest {

    private static final String BASE = "/patchsysacct-cosmos";
    private static final String DOCS = BASE + "/dbs/db/colls/items/docs";
    private static final String PARTITION_KEY = "[\"p\"]";

    @BeforeEach
    void setup() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{\"id\":\"db\"}")
                .post(BASE + "/dbs").then().statusCode(201);
        given().contentType("application/json")
                .body("{\"id\":\"items\",\"partitionKey\":{\"paths\":[\"/pk\"],\"kind\":\"Hash\"}}")
                .post(BASE + "/dbs/db/colls").then().statusCode(201);
        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .body("{\"id\":\"one\",\"pk\":\"p\",\"value\":1}")
                .post(DOCS).then().statusCode(201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"_rid", "_self", "_etag", "_ts", "_attachments", "id"})
    void removingAnImmutableSystemPropertyIsRejected(String field) {
        patch("{\"operations\":[{\"op\":\"remove\",\"path\":\"/" + field + "\"}]}")
                .then().statusCode(400).body("code", is("BadRequest"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"_rid", "id"})
    void settingAnImmutableSystemPropertyIsRejected(String field) {
        patch("{\"operations\":[{\"op\":\"set\",\"path\":\"/" + field + "\",\"value\":\"hijacked\"}]}")
                .then().statusCode(400).body("code", is("BadRequest"));
    }

    @Test
    void movingOutOfAnImmutableSystemPropertyIsRejected() {
        patch("{\"operations\":[{\"op\":\"move\",\"from\":\"/_rid\",\"path\":\"/stolen\"}]}")
                .then().statusCode(400).body("code", is("BadRequest"));
    }

    @Test
    void aRejectedPatchLeavesTheDocumentAndTheContainerQueryable() {
        patch("{\"operations\":[{\"op\":\"remove\",\"path\":\"/_rid\"}]}")
                .then().statusCode(400);

        given().header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .get(DOCS + "/one")
                .then().statusCode(200)
                .body("value", is(1))
                .body("_rid", notNullValue());

        given().contentType("application/query+json")
                .header("x-ms-documentdb-isquery", "True")
                .header("x-ms-documentdb-query-enablecrosspartition", "True")
                .body("{\"query\":\"SELECT * FROM c\"}")
                .post(DOCS)
                .then().statusCode(200)
                .body("Documents[0].id", is("one"));
    }

    @Test
    void theTransactionalBatchRejectsTheSameOperation() {
        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .header("x-ms-cosmos-is-batch-request", "true")
                .body("""
                        [{
                          "operationType": "Patch",
                          "id": "one",
                          "resourceBody": {"operations":[{"op":"remove","path":"/_rid"}]}
                        }]""")
                .post(DOCS)
                .then().statusCode(207).body("[0].statusCode", is(400));

        given().header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .get(DOCS + "/one")
                .then().statusCode(200).body("_rid", notNullValue());
    }

    @Test
    void patchingUserDataStillWorks() {
        patch("{\"operations\":[{\"op\":\"set\",\"path\":\"/value\",\"value\":42}]}")
                .then().statusCode(200);

        given().header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .get(DOCS + "/one")
                .then().statusCode(200).body("value", is(42));
    }

    private io.restassured.response.Response patch(String body) {
        return given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .body(body)
                .patch(DOCS + "/one");
    }
}
