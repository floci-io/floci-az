package io.floci.az.services.cosmos;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
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
                .then().statusCode(207).body("[0].statusCode", is(412));

        readDocument().then().statusCode(200).body("value", is(1));
    }

    @Test
    void batchPreconditionFailureRollsBackEarlierMutation() {
        String etag = createDocument(1);

        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .header("x-ms-cosmos-is-batch-request", "true")
                .body("""
                        [
                          {
                            "operationType": "Replace",
                            "id": "one",
                            "ifMatch": "%s",
                            "resourceBody": {"id":"one","pk":"p","value":2}
                          },
                          {
                            "operationType": "Replace",
                            "id": "one",
                            "ifMatch": "\\\"stale\\\"",
                            "resourceBody": {"id":"one","pk":"p","value":3}
                          }
                        ]""".formatted(etag.replace("\"", "\\\"")))
                .post(DOCS)
                .then().statusCode(207)
                .body("statusCode", contains(424, 412));

        readDocument().then().statusCode(200).body("value", is(1));
    }

    @Test
    void concurrentMutationsWithSameEtagAllowOnlyOne() throws Exception {
        String etag = createDocument(1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> replaceAfterSignal(2, etag, ready, start));
            Future<Integer> second = executor.submit(() -> replaceAfterSignal(3, etag, ready, start));
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            assertThat(List.of(first.get(), second.get()), containsInAnyOrder(200, 412));
        } finally {
            executor.shutdownNow();
        }
    }

    private int replaceAfterSignal(int value, String etag, CountDownLatch ready,
                                   CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", PARTITION_KEY)
                .header("If-Match", etag)
                .body("{\"id\":\"one\",\"pk\":\"p\",\"value\":" + value + "}")
                .put(DOCS + "/one").statusCode();
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
