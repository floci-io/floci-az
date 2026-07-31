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
        insertRawDoc(coll, conversationId, "{\"id\":\"" + id + "\",\"conversationId\":\""
                + conversationId + "\",\"sequence\":" + sequence + "}");
    }

    private io.restassured.response.Response query(String coll, String sql) {
        return queryRaw(coll, "{\"query\":\"" + sql + "\",\"parameters\":[]}");
    }

    private io.restassured.response.Response queryRaw(String coll, String bodyJson) {
        return given().contentType("application/query+json")
                .header("x-ms-documentdb-isquery", "True")
                .body(bodyJson)
                .when().post(BASE + "/dbs/" + DB + "/colls/" + coll + "/docs");
    }

    private void insertRawDoc(String coll, String pk, String docJson) {
        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", "[\"" + pk + "\"]")
                .body(docJson)
                .when().post(BASE + "/dbs/" + DB + "/colls/" + coll + "/docs")
                .then().statusCode(201);
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

    @Test
    void createContainerWithInvalidPolicyDoesNotCreateContainer() {
        String body = "{\"id\":\"bad\",\"partitionKey\":{\"paths\":[\"/pk\"],\"kind\":\"Hash\"},"
                + "\"indexingPolicy\":{\"indexingMode\":\"bogus\"}}";
        given().contentType("application/json").body(body)
                .when().post(BASE + "/dbs/" + DB + "/colls")
                .then().statusCode(400);

        given().when().get(BASE + "/dbs/" + DB + "/colls/bad")
                .then().statusCode(404);
    }

    @Test
    void createContainerWithNonObjectPolicyFails() {
        String body = "{\"id\":\"bad\",\"partitionKey\":{\"paths\":[\"/pk\"],\"kind\":\"Hash\"},"
                + "\"indexingPolicy\":\"not-an-object\"}";
        given().contentType("application/json").body(body)
                .when().post(BASE + "/dbs/" + DB + "/colls")
                .then().statusCode(400)
                .body("code", is("BadRequest"));
    }

    @Test
    void createContainerPreservesCustomIncludedAndExcludedPaths() {
        createContainer("chat", """
                {"includedPaths": [{"path": "/conversationId/?"}],
                 "excludedPaths": [{"path": "/*"}]}""");

        given().when().get(BASE + "/dbs/" + DB + "/colls/chat")
                .then().statusCode(200)
                .body("indexingPolicy.includedPaths[0].path", is("/conversationId/?"))
                .body("indexingPolicy.excludedPaths[0].path", is("/*"));
    }

    @Test
    void createContainerWithEmptyCompositeIndexesOmitsThemAndRejectsMultiOrderBy() {
        createContainer("chat", "{\"compositeIndexes\": []}");
        insertDoc("chat", "m1", "conv1", 1);

        given().when().get(BASE + "/dbs/" + DB + "/colls/chat")
                .then().statusCode(200)
                .body("indexingPolicy.compositeIndexes", nullValue());

        query("chat", "SELECT * FROM c ORDER BY c.conversationId, c.sequence")
                .then().statusCode(400);
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

    @Test
    void mixedDirectionCompositeServesMixedOrderByAndItsInverse() {
        createContainer("chat", """
                {"compositeIndexes": [[
                  {"path": "/conversationId", "order": "ascending"},
                  {"path": "/sequence", "order": "descending"}
                ]]}""");
        insertDoc("chat", "m1", "conv1", 1);
        insertDoc("chat", "m2", "conv1", 2);

        query("chat", "SELECT * FROM c ORDER BY c.conversationId ASC, c.sequence DESC")
                .then().statusCode(200)
                .body("Documents.id", contains("m2", "m1"));
        query("chat", "SELECT * FROM c ORDER BY c.conversationId DESC, c.sequence ASC")
                .then().statusCode(200)
                .body("Documents.id", contains("m1", "m2"));
        // same-direction pair not covered by an (asc, desc) index
        query("chat", "SELECT * FROM c ORDER BY c.conversationId, c.sequence")
                .then().statusCode(400);
    }

    @Test
    void threePropertyOrderByRequiresThreePathComposite() {
        createContainer("chat", """
                {"compositeIndexes": [[
                  {"path": "/conversationId"},
                  {"path": "/sequence"},
                  {"path": "/kind"}
                ]]}""");
        insertRawDoc("chat", "conv1",
                "{\"id\":\"m1\",\"conversationId\":\"conv1\",\"sequence\":1,\"kind\":\"b\"}");
        insertRawDoc("chat", "conv1",
                "{\"id\":\"m2\",\"conversationId\":\"conv1\",\"sequence\":1,\"kind\":\"a\"}");

        query("chat", "SELECT * FROM c ORDER BY c.conversationId, c.sequence, c.kind")
                .then().statusCode(200)
                .body("Documents.id", contains("m2", "m1"));

        // two-property prefix of the three-path index is NOT served (Azure parity)
        query("chat", "SELECT * FROM c ORDER BY c.conversationId, c.sequence")
                .then().statusCode(400);
    }

    @Test
    void secondOfMultipleCompositeIndexesServesQuery() {
        createContainer("chat", """
                {"compositeIndexes": [
                  [{"path": "/x"}, {"path": "/y"}],
                  [{"path": "/conversationId"}, {"path": "/sequence"}]
                ]}""");
        insertDoc("chat", "m1", "conv1", 2);
        insertDoc("chat", "m2", "conv1", 1);

        query("chat", "SELECT * FROM c ORDER BY c.conversationId, c.sequence")
                .then().statusCode(200)
                .body("Documents.id", contains("m2", "m1"));
    }

    @Test
    void nestedPathCompositeIndexServesNestedOrderBy() {
        createContainer("chat", """
                {"compositeIndexes": [[
                  {"path": "/conversationId"},
                  {"path": "/meta/seq"}
                ]]}""");
        insertRawDoc("chat", "conv1",
                "{\"id\":\"m1\",\"conversationId\":\"conv1\",\"meta\":{\"seq\":2}}");
        insertRawDoc("chat", "conv1",
                "{\"id\":\"m2\",\"conversationId\":\"conv1\",\"meta\":{\"seq\":1}}");

        query("chat", "SELECT * FROM c ORDER BY c.conversationId, c.meta.seq")
                .then().statusCode(200)
                .body("Documents.id", contains("m2", "m1"));
    }

    @Test
    void orderByWithOffsetLimitStillEnforcedAndServed() {
        createContainer("chat", COMPOSITE_POLICY);
        insertDoc("chat", "m1", "conv1", 1);
        insertDoc("chat", "m2", "conv1", 2);
        insertDoc("chat", "m3", "conv1", 3);

        query("chat", "SELECT * FROM c ORDER BY c.conversationId, c.sequence OFFSET 1 LIMIT 1")
                .then().statusCode(200)
                .body("Documents.id", contains("m2"));

        createContainer("plain", null);
        insertDoc("plain", "p1", "conv1", 1);
        query("plain", "SELECT * FROM c ORDER BY c.conversationId, c.sequence OFFSET 1 LIMIT 1")
                .then().statusCode(400);
    }

    @Test
    void lowercaseOrderByStillEnforced() {
        createContainer("chat", null);
        insertDoc("chat", "m1", "conv1", 1);

        query("chat", "select * from c order by c.conversationId, c.sequence")
                .then().statusCode(400);
    }

    @Test
    void parameterizedWhereWithMultiPropertyOrderByServed() {
        createContainer("chat", COMPOSITE_POLICY);
        insertDoc("chat", "m1", "conv1", 2);
        insertDoc("chat", "m2", "conv1", 1);
        insertDoc("chat", "m3", "conv2", 1);

        queryRaw("chat", """
                {"query": "SELECT * FROM c WHERE c.conversationId = @conv ORDER BY c.conversationId, c.sequence",
                 "parameters": [{"name": "@conv", "value": "conv1"}]}""")
                .then().statusCode(200)
                .body("Documents.id", contains("m2", "m1"));
    }

    @Test
    void paramValueWithApostropheStillEnforcesOrderBy() {
        // "Alice's" substitutes to a literal with an escaped quote. Enforcement
        // must not be bypassed: without a composite index the multi-property
        // ORDER BY still fails 400 (SC2104).
        createContainer("chat", null);
        insertDoc("chat", "m1", "conv1", 1);

        queryRaw("chat", """
                {"query": "SELECT * FROM c WHERE c.name = @name ORDER BY c.conversationId, c.sequence",
                 "parameters": [{"name": "@name", "value": "Alice's"}]}""")
                .then().statusCode(400);
    }

    @Test
    void paramValueWithApostropheServedAndSorted() {
        createContainer("chat", COMPOSITE_POLICY);
        insertRawDoc("chat", "conv1",
                "{\"id\":\"m1\",\"conversationId\":\"conv1\",\"sequence\":2,\"name\":\"Alice's\"}");
        insertRawDoc("chat", "conv1",
                "{\"id\":\"m2\",\"conversationId\":\"conv1\",\"sequence\":1,\"name\":\"Alice's\"}");
        insertRawDoc("chat", "conv1",
                "{\"id\":\"m3\",\"conversationId\":\"conv1\",\"sequence\":9,\"name\":\"Bob\"}");

        queryRaw("chat", """
                {"query": "SELECT * FROM c WHERE c.name = @name ORDER BY c.conversationId, c.sequence",
                 "parameters": [{"name": "@name", "value": "Alice's"}]}""")
                .then().statusCode(200)
                .body("Documents.id", contains("m2", "m1"));
    }

    @Test
    void orderByInsideStringLiteralDoesNotTriggerEnforcement() {
        createContainer("chat", null);
        insertRawDoc("chat", "conv1",
                "{\"id\":\"m1\",\"conversationId\":\"conv1\",\"note\":\"order by c.a, c.b\"}");

        queryRaw("chat", """
                {"query": "SELECT * FROM c WHERE c.note = 'order by c.a, c.b'", "parameters": []}""")
                .then().statusCode(200)
                .body("Documents.id", contains("m1"));
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

    @Test
    void replaceWithInvalidPolicyKeepsExistingPolicy() {
        createContainer("chat", COMPOSITE_POLICY);
        insertDoc("chat", "m1", "conv1", 1);
        insertDoc("chat", "m2", "conv1", 2);

        given().contentType("application/json")
                .body("{\"id\":\"chat\",\"indexingPolicy\":{\"indexingMode\":\"bogus\"}}")
                .when().put(BASE + "/dbs/" + DB + "/colls/chat")
                .then().statusCode(400);

        // old composite index survives the failed replace
        given().when().get(BASE + "/dbs/" + DB + "/colls/chat")
                .then().statusCode(200)
                .body("indexingPolicy.compositeIndexes[0].path",
                        contains("/conversationId", "/sequence"));
        query("chat", "SELECT * FROM c ORDER BY c.conversationId, c.sequence")
                .then().statusCode(200)
                .body("Documents.id", contains("m1", "m2"));
    }

    @Test
    void replaceWithoutPolicyResetsToDefault() {
        createContainer("chat", COMPOSITE_POLICY);
        insertDoc("chat", "m1", "conv1", 1);

        String replaceBody = "{\"id\":\"chat\","
                + "\"partitionKey\":{\"paths\":[\"/conversationId\"],\"kind\":\"Hash\"}}";
        given().contentType("application/json").body(replaceBody)
                .when().put(BASE + "/dbs/" + DB + "/colls/chat")
                .then().statusCode(200)
                .body("indexingPolicy.compositeIndexes", nullValue());

        // composite index gone → multi-property ORDER BY now rejected, like Azure
        query("chat", "SELECT * FROM c ORDER BY c.conversationId, c.sequence")
                .then().statusCode(400);
    }

    @Test
    void replaceKeepsDocumentsAndIdentity() {
        createContainer("chat", null);
        insertDoc("chat", "m1", "conv1", 1);

        String ridBefore = given().when().get(BASE + "/dbs/" + DB + "/colls/chat")
                .then().statusCode(200).extract().path("_rid");

        String replaceBody = "{\"id\":\"chat\",\"indexingPolicy\":" + COMPOSITE_POLICY + "}";
        given().contentType("application/json").body(replaceBody)
                .when().put(BASE + "/dbs/" + DB + "/colls/chat")
                .then().statusCode(200)
                .body("_rid", is(ridBefore))
                .body("partitionKey.paths[0]", is("/conversationId"));

        given().header("x-ms-documentdb-partitionkey", "[\"conv1\"]")
                .when().get(BASE + "/dbs/" + DB + "/colls/chat/docs/m1")
                .then().statusCode(200)
                .body("id", is("m1"));
    }
}
