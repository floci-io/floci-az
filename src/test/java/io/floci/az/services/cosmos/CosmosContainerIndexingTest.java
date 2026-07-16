package io.floci.az.services.cosmos;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.contains;

/**
 * HTTP-level tests for issue #127: custom indexing policies on container
 * create/replace, and the Azure rule that a multi-property ORDER BY fails
 * with 400 unless a matching composite index exists.
 */
@QuarkusTest
public class CosmosContainerIndexingTest {

    private static final String ACCT = "idxacct";
    private static final String BASE = "/" + ACCT + "-cosmos";
    private static final String DB   = "chatdb";

    private static final String COMPOSITE_POLICY = """
            {
              "indexingMode": "consistent",
              "automatic": true,
              "includedPaths": [{"path": "/*"}],
              "excludedPaths": [{"path": "/\\"_etag\\"/?"}],
              "compositeIndexes": [[
                {"path": "/conversationId", "order": "ascending"},
                {"path": "/sequence", "order": "ascending"}
              ]]
            }""";

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{\"id\":\"" + DB + "\"}")
                .when().post(BASE + "/dbs").then().statusCode(201);
    }

    private void createContainer(String id, String indexingPolicyJson) {
        String body = "{\"id\":\"" + id + "\","
                + "\"partitionKey\":{\"paths\":[\"/conversationId\"],\"kind\":\"Hash\"}"
                + (indexingPolicyJson == null ? "" : ",\"indexingPolicy\":" + indexingPolicyJson)
                + "}";
        given().contentType("application/json").body(body)
                .when().post(BASE + "/dbs/" + DB + "/colls")
                .then().statusCode(201);
    }

    private void insertDoc(String coll, String id, String conversationId, int sequence) {
        String body = "{\"id\":\"" + id + "\",\"conversationId\":\"" + conversationId
                + "\",\"sequence\":" + sequence + "}";
        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", "[\"" + conversationId + "\"]")
                .body(body)
                .when().post(BASE + "/dbs/" + DB + "/colls/" + coll + "/docs")
                .then().statusCode(201);
    }

    private io.restassured.response.Response query(String coll, String sql) {
        return given().contentType("application/query+json")
                .header("x-ms-documentdb-isquery", "True")
                .body("{\"query\":\"" + sql + "\",\"parameters\":[]}")
                .when().post(BASE + "/dbs/" + DB + "/colls/" + coll + "/docs");
    }

    // ------------------------------------------------------------------ create

    @Test
    void createContainerPersistsCustomIndexingPolicy() {
        createContainer("chat", COMPOSITE_POLICY);

        given().when().get(BASE + "/dbs/" + DB + "/colls/chat")
                .then().statusCode(200)
                .body("indexingPolicy.indexingMode", is("consistent"))
                .body("indexingPolicy.compositeIndexes[0].path",
                        contains("/conversationId", "/sequence"))
                .body("indexingPolicy.compositeIndexes[0].order",
                        contains("ascending", "ascending"));
    }

    @Test
    void createContainerFillsCompositeOrderDefault() {
        createContainer("chat", """
                {"compositeIndexes": [[{"path": "/a"}, {"path": "/b", "order": "descending"}]]}""");

        given().when().get(BASE + "/dbs/" + DB + "/colls/chat")
                .then().statusCode(200)
                .body("indexingPolicy.compositeIndexes[0].order",
                        contains("ascending", "descending"));
    }

    @Test
    void createContainerWithoutPolicyReturnsDefault() {
        createContainer("plain", null);

        given().when().get(BASE + "/dbs/" + DB + "/colls/plain")
                .then().statusCode(200)
                .body("indexingPolicy.indexingMode", is("consistent"))
                .body("indexingPolicy.includedPaths[0].path", is("/*"))
                .body("indexingPolicy.compositeIndexes", nullValue());
    }

    @Test
    void createContainerWithSinglePathCompositeIndexFails() {
        String body = "{\"id\":\"bad\",\"partitionKey\":{\"paths\":[\"/pk\"],\"kind\":\"Hash\"},"
                + "\"indexingPolicy\":{\"compositeIndexes\":[[{\"path\":\"/a\"}]]}}";
        given().contentType("application/json").body(body)
                .when().post(BASE + "/dbs/" + DB + "/colls")
                .then().statusCode(400)
                .body("code", is("BadRequest"));
    }

    // ------------------------------------------------------------------ ORDER BY enforcement

    @Test
    void multiPropertyOrderByWithoutCompositeIndexReturns400() {
        createContainer("chat", null);
        insertDoc("chat", "m1", "conv1", 2);
        insertDoc("chat", "m2", "conv1", 1);

        query("chat", "SELECT * FROM c ORDER BY c.conversationId, c.sequence")
                .then().statusCode(400)
                .body("code", is("BadRequest"))
                .body("message", containsString(
                        "The order by query does not have a corresponding composite index"));
    }

    @Test
    void multiPropertyOrderByWithMatchingCompositeIndexSucceeds() {
        createContainer("chat", COMPOSITE_POLICY);
        insertDoc("chat", "m1", "conv1", 2);
        insertDoc("chat", "m2", "conv1", 1);
        insertDoc("chat", "m3", "conv0", 5);

        query("chat", "SELECT * FROM c ORDER BY c.conversationId, c.sequence")
                .then().statusCode(200)
                .body("Documents.id", contains("m3", "m2", "m1"));
    }

    @Test
    void fullyInvertedOrderByServedByCompositeIndex() {
        createContainer("chat", COMPOSITE_POLICY);
        insertDoc("chat", "m1", "conv1", 1);
        insertDoc("chat", "m2", "conv2", 1);

        query("chat", "SELECT * FROM c ORDER BY c.conversationId DESC, c.sequence DESC")
                .then().statusCode(200)
                .body("Documents.id", contains("m2", "m1"));
    }

    @Test
    void mixedDirectionOrderByWithoutMatchingCompositeReturns400() {
        createContainer("chat", COMPOSITE_POLICY);
        insertDoc("chat", "m1", "conv1", 1);

        query("chat", "SELECT * FROM c ORDER BY c.conversationId ASC, c.sequence DESC")
                .then().statusCode(400)
                .body("message", containsString("composite index"));
    }

    @Test
    void wrongSequenceOrderByReturns400() {
        createContainer("chat", COMPOSITE_POLICY);
        insertDoc("chat", "m1", "conv1", 1);

        query("chat", "SELECT * FROM c ORDER BY c.sequence, c.conversationId")
                .then().statusCode(400);
    }

    @Test
    void singlePropertyOrderByAlwaysServed() {
        createContainer("chat", null);
        insertDoc("chat", "m1", "conv1", 2);
        insertDoc("chat", "m2", "conv1", 1);

        query("chat", "SELECT * FROM c ORDER BY c.sequence DESC")
                .then().statusCode(200)
                .body("Documents.id", contains("m1", "m2"));
    }

    // ------------------------------------------------------------------ replace

    @Test
    void replaceContainerAddsCompositeIndex() {
        createContainer("chat", null);
        insertDoc("chat", "m1", "conv1", 2);
        insertDoc("chat", "m2", "conv1", 1);

        query("chat", "SELECT * FROM c ORDER BY c.conversationId, c.sequence")
                .then().statusCode(400);

        String replaceBody = "{\"id\":\"chat\","
                + "\"partitionKey\":{\"paths\":[\"/conversationId\"],\"kind\":\"Hash\"},"
                + "\"indexingPolicy\":" + COMPOSITE_POLICY + "}";
        given().contentType("application/json").body(replaceBody)
                .when().put(BASE + "/dbs/" + DB + "/colls/chat")
                .then().statusCode(200)
                .body("indexingPolicy.compositeIndexes[0].path",
                        contains("/conversationId", "/sequence"));

        query("chat", "SELECT * FROM c ORDER BY c.conversationId, c.sequence")
                .then().statusCode(200)
                .body("Documents.id", contains("m2", "m1"));
    }

    @Test
    void replaceContainerWithMismatchedIdFails() {
        createContainer("chat", null);
        given().contentType("application/json").body("{\"id\":\"other\"}")
                .when().put(BASE + "/dbs/" + DB + "/colls/chat")
                .then().statusCode(400);
    }

    @Test
    void replaceContainerCannotChangePartitionKey() {
        createContainer("chat", null);
        String replaceBody = "{\"id\":\"chat\","
                + "\"partitionKey\":{\"paths\":[\"/other\"],\"kind\":\"Hash\"}}";
        given().contentType("application/json").body(replaceBody)
                .when().put(BASE + "/dbs/" + DB + "/colls/chat")
                .then().statusCode(400)
                .body("message", containsString("partition key"));
    }

    @Test
    void replaceUnknownContainerReturns404() {
        given().contentType("application/json").body("{\"id\":\"ghost\"}")
                .when().put(BASE + "/dbs/" + DB + "/colls/ghost")
                .then().statusCode(404);
    }
}
