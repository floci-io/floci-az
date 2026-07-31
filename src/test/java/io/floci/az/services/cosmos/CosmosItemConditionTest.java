package io.floci.az.services.cosmos;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class CosmosItemConditionTest {

    private static final String BASE = "/conditionacct-cosmos";
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
    }

    @Test
    void staleIfMatchRejectsPointMutationsWithoutChangingDocument() {
        createDocument(1);

        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .header("If-Match", "\"stale\"")
                .body("{\"id\":\"one\",\"pk\":\"p\",\"value\":2}")
                .put(DOCS + "/one").then().statusCode(412);

        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .header("If-Match", "\"stale\"")
                .body("{\"operations\":[{\"op\":\"set\",\"path\":\"/value\",\"value\":3}]}")
                .patch(DOCS + "/one").then().statusCode(412);

        given().header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .header("If-Match", "\"stale\"")
                .delete(DOCS + "/one").then().statusCode(412);

        readDocument().then().statusCode(200).body("value", is(1));
    }

    @Test
    void upsertHonorsIfMatchAndIfNoneMatch() {
        String etag = createDocument(1);

        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .header("x-ms-documentdb-is-upsert", "True")
                .header("If-Match", "\"stale\"")
                .body("{\"id\":\"one\",\"pk\":\"p\",\"value\":2}")
                .post(DOCS).then().statusCode(412);

        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .header("x-ms-documentdb-is-upsert", "True")
                .header("If-None-Match", "*")
                .body("{\"id\":\"one\",\"pk\":\"p\",\"value\":2}")
                .post(DOCS).then().statusCode(412);

        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .header("x-ms-documentdb-is-upsert", "True")
                .header("If-Match", etag)
                .body("{\"id\":\"one\",\"pk\":\"p\",\"value\":2}")
                .post(DOCS).then().statusCode(201);

        readDocument().then().statusCode(200).body("value", is(2));
    }

    @Test
    void transactionalBatchHonorsPerOperationIfMatch() {
        createDocument(1);

        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .header("x-ms-cosmos-is-batch-request", "true")
                .body("""
                        [{
                          "operationType": "Replace",
                          "id": "one",
                          "ifMatch": "\\"stale\\"",
                          "resourceBody": {"id":"one","pk":"p","value":2}
                        }]""")
                .post(DOCS)
                .then().statusCode(200).body("[0].statusCode", is(412));

        readDocument().then().statusCode(200).body("value", is(1));
    }

    private String createDocument(int value) {
        return given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .body("{\"id\":\"one\",\"pk\":\"p\",\"value\":" + value + "}")
                .post(DOCS).then().statusCode(201).extract().header("ETag");
    }

    private io.restassured.response.Response readDocument() {
        return given().header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .get(DOCS + "/one");
    }
}
