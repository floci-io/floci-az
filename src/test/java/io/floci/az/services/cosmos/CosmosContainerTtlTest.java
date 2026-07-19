package io.floci.az.services.cosmos;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * HTTP-level tests for issue #126: container {@code defaultTtl} on
 * create/replace, per-document {@code ttl} overrides, and expired documents
 * disappearing from reads, lists, queries, and batches.
 *
 * <p>The TTL decision matrix itself is unit-tested in {@link CosmosTtlTest}
 * with an explicit clock; only {@link #expiredDocumentsVanishEndToEnd()} waits
 * for real time to pass, proving the wiring end to end.</p>
 */
@QuarkusTest
public class CosmosContainerTtlTest {

    private static final String ACCT = "ttlacct";
    private static final String BASE = "/" + ACCT + "-cosmos";
    private static final String DB   = "ttldb";

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{\"id\":\"" + DB + "\"}")
                .when().post(BASE + "/dbs").then().statusCode(201);
    }

    private io.restassured.response.Response createContainer(String id, String defaultTtlJson) {
        String body = "{\"id\":\"" + id + "\","
                + "\"partitionKey\":{\"paths\":[\"/category\"],\"kind\":\"Hash\"}"
                + (defaultTtlJson == null ? "" : ",\"defaultTtl\":" + defaultTtlJson)
                + "}";
        return given().contentType("application/json").body(body)
                .when().post(BASE + "/dbs/" + DB + "/colls");
    }

    private io.restassured.response.Response replaceContainer(String id, String defaultTtlJson) {
        String body = "{\"id\":\"" + id + "\","
                + "\"partitionKey\":{\"paths\":[\"/category\"],\"kind\":\"Hash\"}"
                + (defaultTtlJson == null ? "" : ",\"defaultTtl\":" + defaultTtlJson)
                + "}";
        return given().contentType("application/json").body(body)
                .when().put(BASE + "/dbs/" + DB + "/colls/" + id);
    }

    private void insertDoc(String coll, String docJson) {
        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", "[\"x\"]")
                .body(docJson)
                .when().post(BASE + "/dbs/" + DB + "/colls/" + coll + "/docs")
                .then().statusCode(201);
    }

    private int getDocStatus(String coll, String docId) {
        return given().when().get(BASE + "/dbs/" + DB + "/colls/" + coll + "/docs/" + docId)
                .statusCode();
    }

    // ------------------------------------------------------------------ create

    @Test
    void createContainerPersistsDefaultTtl() {
        createContainer("events", "3600").then().statusCode(201).body("defaultTtl", is(3600));

        given().when().get(BASE + "/dbs/" + DB + "/colls/events")
                .then().statusCode(200).body("defaultTtl", is(3600));

        given().when().get(BASE + "/dbs/" + DB + "/colls")
                .then().statusCode(200)
                .body("DocumentCollections[0].defaultTtl", is(3600));
    }

    @Test
    void createContainerPersistsMinusOne() {
        createContainer("events", "-1").then().statusCode(201).body("defaultTtl", is(-1));
    }

    @Test
    void createContainerWithoutTtlOmitsProperty() {
        createContainer("events", null).then().statusCode(201).body("defaultTtl", nullValue());

        given().when().get(BASE + "/dbs/" + DB + "/colls/events")
                .then().statusCode(200).body("defaultTtl", nullValue());
    }

    @Test
    void createContainerRejectsInvalidDefaultTtl() {
        for (String bad : new String[] {"0", "-2", "1.5", "\"3600\"", "2147483648"}) {
            createContainer("events", bad).then().statusCode(400)
                    .body("message", containsString("defaultTtl"));
        }
        // none of the rejected creates may have left a container behind
        given().when().get(BASE + "/dbs/" + DB + "/colls/events").then().statusCode(404);
    }

    // ------------------------------------------------------------------ replace

    @Test
    void replaceContainerEnablesAndAdjustsTtl() {
        createContainer("events", null).then().statusCode(201);

        replaceContainer("events", "60").then().statusCode(200).body("defaultTtl", is(60));
        given().when().get(BASE + "/dbs/" + DB + "/colls/events")
                .then().statusCode(200).body("defaultTtl", is(60));

        replaceContainer("events", "-1").then().statusCode(200).body("defaultTtl", is(-1));
    }

    @Test
    void replaceContainerOmittingTtlDisablesIt() {
        createContainer("events", "3600").then().statusCode(201);

        replaceContainer("events", null).then().statusCode(200).body("defaultTtl", nullValue());
        given().when().get(BASE + "/dbs/" + DB + "/colls/events")
                .then().statusCode(200).body("defaultTtl", nullValue());
    }

    @Test
    void replaceContainerRejectsInvalidTtlAndKeepsStoredValue() {
        createContainer("events", "3600").then().statusCode(201);

        replaceContainer("events", "0").then().statusCode(400)
                .body("message", containsString("defaultTtl"));
        given().when().get(BASE + "/dbs/" + DB + "/colls/events")
                .then().statusCode(200).body("defaultTtl", is(3600));
    }

    @Test
    void replaceContainerRejectsIdMismatch() {
        createContainer("events", null).then().statusCode(201);

        given().contentType("application/json").body("{\"id\":\"other\",\"defaultTtl\":60}")
                .when().put(BASE + "/dbs/" + DB + "/colls/events")
                .then().statusCode(400).body("message", containsString("id"));
    }

    @Test
    void replaceContainerRejectsPartitionKeyChange() {
        createContainer("events", null).then().statusCode(201);

        given().contentType("application/json")
                .body("{\"id\":\"events\",\"partitionKey\":{\"paths\":[\"/other\"],\"kind\":\"Hash\"}}")
                .when().put(BASE + "/dbs/" + DB + "/colls/events")
                .then().statusCode(400).body("message", containsString("partition key"));
    }

    @Test
    void replaceMissingContainerIs404() {
        replaceContainer("ghost", "60").then().statusCode(404);
    }

    // ------------------------------------------------------------------ expiry

    /**
     * End-to-end expiry: container "events" has {@code defaultTtl = 2}; doc "a"
     * uses the default, doc "b" opts out with {@code ttl = -1}.  Container
     * "keep" has TTL off, so its doc "c" ({@code ttl = 1}) must never expire.
     */
    @Test
    void expiredDocumentsVanishEndToEnd() throws InterruptedException {
        createContainer("events", "2").then().statusCode(201);
        createContainer("keep", null).then().statusCode(201);

        insertDoc("events", "{\"id\":\"a\",\"category\":\"x\"}");
        insertDoc("events", "{\"id\":\"b\",\"category\":\"x\",\"ttl\":-1}");
        insertDoc("keep",   "{\"id\":\"c\",\"category\":\"x\",\"ttl\":1}");

        // wait (up to 8s) for "a" to expire
        long deadline = System.currentTimeMillis() + 8_000;
        while (getDocStatus("events", "a") != 404) {
            if (System.currentTimeMillis() > deadline) fail("doc 'a' did not expire within 8s");
            Thread.sleep(200);
        }

        // "b" (ttl -1) and "c" (TTL off on container) are still readable
        given().when().get(BASE + "/dbs/" + DB + "/colls/events/docs/b").then().statusCode(200);
        given().when().get(BASE + "/dbs/" + DB + "/colls/keep/docs/c").then().statusCode(200);

        // list and query exclude the expired doc
        given().when().get(BASE + "/dbs/" + DB + "/colls/events/docs")
                .then().statusCode(200)
                .body("_count", is(1))
                .body("Documents[0].id", is("b"));

        given().contentType("application/query+json")
                .header("x-ms-documentdb-isquery", "True")
                .body("{\"query\":\"SELECT * FROM c\",\"parameters\":[]}")
                .when().post(BASE + "/dbs/" + DB + "/colls/events/docs")
                .then().statusCode(200)
                .body("_count", is(1))
                .body("Documents[0].id", is("b"));

        // transactional batch sees the expired doc as gone too
        given().contentType("application/json")
                .header("x-ms-cosmos-is-batch-request", "true")
                .header("x-ms-documentdb-partitionkey", "[\"x\"]")
                .body("[{\"operationType\":\"Read\",\"id\":\"a\"},{\"operationType\":\"Read\",\"id\":\"b\"}]")
                .when().post(BASE + "/dbs/" + DB + "/colls/events/docs")
                .then().statusCode(200)
                .body("statusCode", contains(404, 200));

        // an expired doc no longer blocks re-creating the same id
        insertDoc("events", "{\"id\":\"a\",\"category\":\"x\"}");
        given().when().get(BASE + "/dbs/" + DB + "/colls/events/docs/a").then().statusCode(200);
    }
}
