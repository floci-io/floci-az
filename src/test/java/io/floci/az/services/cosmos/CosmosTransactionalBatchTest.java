package io.floci.az.services.cosmos;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class CosmosTransactionalBatchTest {

    private static final String BASE = "/batchacct-cosmos";
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
                .body("{\"id\":\"one\",\"pk\":\"p\",\"value\":1,\"counter\":2}")
                .post(DOCS).then().statusCode(201);
    }

    @Test
    void failedBatchRollsBackEarlierMutations() {
        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .header("x-ms-cosmos-is-batch-request", "true")
                .body("""
                        [
                          {
                            "operationType":"Replace",
                            "id":"one",
                            "resourceBody":{"id":"one","pk":"p","value":777}
                          },
                          {"operationType":"Delete","id":"missing"}
                        ]""")
                .post(DOCS)
                .then().statusCode(200)
                .body("statusCode", contains(424, 404));

        read("one").then().statusCode(200).body("value", is(1));
    }

    @Test
    void patchParticipatesInBatchAndIsVisibleToFollowingRead() {
        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .header("x-ms-cosmos-is-batch-request", "true")
                .body("""
                        [
                          {
                            "operationType":"Patch",
                            "id":"one",
                            "resourceBody":{"operations":[
                              {"op":"set","path":"/value","value":3},
                              {"op":"incr","path":"/counter","value":5}
                            ]}
                          },
                          {"operationType":"Read","id":"one"}
                        ]""")
                .post(DOCS)
                .then().statusCode(200)
                .body("statusCode", contains(200, 200))
                .body("[1].resourceBody.value", is(3))
                .body("[1].resourceBody.counter", is(7));

        read("one").then().statusCode(200)
                .body("value", is(3))
                .body("counter", is(7));
    }

    @Test
    void malformedPatchOperationReturnsBatchFailure() {
        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .header("x-ms-cosmos-is-batch-request", "true")
                .body("""
                        [
                          {
                            "operationType":"Patch",
                            "id":"one",
                            "resourceBody":{"operations":[42]}
                          },
                          {"operationType":"Read","id":"one"}
                        ]""")
                .post(DOCS)
                .then().statusCode(200)
                .body("statusCode", contains(400, 424));

        read("one").then().statusCode(200)
                .body("value", is(1))
                .body("counter", is(2));
    }

    private io.restassured.response.Response read(String id) {
        return given().header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .get(DOCS + "/" + id);
    }
}
