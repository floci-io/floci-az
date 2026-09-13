package io.floci.az.services.cosmos;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CosmosQueryContinuationTest {
    private static final String DB = "/continuationacct-cosmos/dbs/pagination";
    private static final String DOCS = DB + "/colls/items/docs";

    @BeforeEach
    void setup() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body(Map.of("id", "pagination"))
                .post("/continuationacct-cosmos/dbs").then().statusCode(201);
        given().contentType("application/json").body("""
                {"id":"items","partitionKey":{"paths":["/tenant"],"kind":"Hash"},
                 "indexingPolicy":{"compositeIndexes":[[
                     {"path":"/rank","order":"ascending"},
                     {"path":"/id","order":"descending"}]]}}
                """).post(DB + "/colls").then().statusCode(201);
        for (int i = 0; i < 7; i++) {
            insert("item-" + i, "alice", i < 2 ? null : i / 3);
            insert("other-" + i, "bob", i < 2 ? null : i / 3);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT c.id FROM c",
            "SELECT c.id FROM c ORDER BY c.rank",
            "SELECT c.id FROM c ORDER BY c.rank DESC",
            "SELECT c.id FROM c ORDER BY c.rank, c.id DESC",
            "SELECT c.id FROM c ORDER BY c.rank DESC, c.id",
            "SELECT VALUE c.id FROM c ORDER BY c.rank",
            "SELECT * FROM c ORDER BY c.rank DESC"
    })
    void visitsEveryDocumentWhenConsumedPagesAreDeleted(String sql) {
        List<String> expected = ids(query(sql, null, -1));
        List<String> visited = new ArrayList<>();
        String continuation = null;
        int pages = 0;
        do {
            Response page = query(sql, continuation, 2);
            List<String> ids = ids(page);
            assertFalse(ids.isEmpty(), "Continuation must not skip remaining documents");
            assertTrue(ids.size() <= 2);
            visited.addAll(ids);
            for (String id : ids) {
                given().header("x-ms-documentdb-partitionkey", "[\"alice\"]")
                        .delete(DOCS + "/" + id).then().statusCode(204);
            }
            continuation = page.header("x-ms-continuation");
            assertTrue(++pages <= 4, "Continuation must terminate");
        } while (continuation != null);

        assertEquals(expected, visited);
        assertEquals(4, pages);
        query("SELECT c.id FROM c", null, -1).then().body("_count", is(0));
        given().contentType("application/query+json").header("x-ms-documentdb-isquery", "true")
                .header("x-ms-documentdb-partitionkey", "[\"bob\"]")
                .body(Map.of("query", "SELECT c.id FROM c"))
                .post(DOCS).then().statusCode(200).body("_count", is(7));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT TOP 5 c.id FROM c ORDER BY c.rank",
            "SELECT c.id FROM c ORDER BY c.rank DESC OFFSET 1 LIMIT 5",
            "SELECT TOP 6 c.id FROM c ORDER BY c.rank OFFSET 1 LIMIT 5"
    })
    void preservesQueryLimitsAndReplaysBookmarksAfterDeletion(String sql) {
        List<String> expected = ids(query(sql, null, -1));
        List<String> visited = new ArrayList<>();
        String continuation = null;
        do {
            Response page = query(sql, continuation, visited.isEmpty() ? 2 : 1);
            List<String> pageIds = ids(page);
            assertFalse(pageIds.isEmpty());
            visited.addAll(pageIds);
            assertTrue(visited.size() <= 5);
            for (String id : pageIds) {
                given().header("x-ms-documentdb-partitionkey", "[\"alice\"]")
                        .delete(DOCS + "/" + id).then().statusCode(204);
            }
            continuation = page.header("x-ms-continuation");
            if (continuation != null) {
                assertEquals(ids(query(sql, continuation, 1)), ids(query(sql, continuation, 1)));
            }
        } while (continuation != null);
        assertEquals(expected, visited);
        assertEquals(5, visited.size());
    }

    @Test
    void resumesLegacyOffsetTokens() {
        String sql = "SELECT c.id FROM c ORDER BY c.rank, c.id DESC";
        List<String> expected = ids(query(sql, null, -1));
        String token = Base64.getEncoder().encodeToString("{\"skip\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Response page = query(sql, token, 2);
        assertEquals(expected.subList(2, 4), ids(page));
        assertEquals(expected.subList(4, 7), ids(query(sql, page.header("x-ms-continuation"), -1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[]", "{\"skip\":-1}", "{\"skip\":1.5}",
            "{\"skip\":9223372036854775808}", "{\"skip\":\"2\"}",
            "{\"skip\":2,\"rid\":\"bookmark\",\"orderValues\":[]}"})
    void rejectsInvalidContinuationWithoutRestartingQuery(String json) {
        String token = Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        given().contentType("application/query+json").header("x-ms-documentdb-isquery", "true")
                .header("x-ms-continuation", token)
                .body(Map.of("query", "SELECT c.id FROM c ORDER BY c.rank"))
                .post(DOCS).then().statusCode(400).body("code", is("BadRequest"));
    }

    @ParameterizedTest
    @ValueSource(longs = {2147483648L, 4294967296L, Long.MAX_VALUE})
    void acceptsLongLegacyOffsetsWithoutWrapping(long offset) {
        String token = Base64.getEncoder().encodeToString(
                ("{\"skip\":" + offset + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        query("SELECT c.id FROM c", token, 2).then().body("_count", is(0))
                .header("x-ms-continuation", nullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT c.id FROM c ORDER BY c.rank DESC",
            "SELECT c.id FROM c ORDER BY c.id", "SELECT VALUE c.id FROM c ORDER BY c.rank",
            "SELECT c.id FROM c WHERE c.rank = 1 ORDER BY c.rank",
            "SELECT TOP 3 c.id FROM c ORDER BY c.rank"})
    void rejectsBookmarkForDifferentQuery(String sql) {
        String token = query("SELECT c.id FROM c ORDER BY c.rank", null, 2).header("x-ms-continuation");
        given().contentType("application/query+json").header("x-ms-documentdb-isquery", "true")
                .header("x-ms-documentdb-partitionkey", "[\"alice\"]")
                .header("x-ms-continuation", token).body(Map.of("query", sql))
                .post(DOCS).then().statusCode(400).body("code", is("BadRequest"));
    }

    @Test
    void bindsBookmarkToPartitionButIgnoresHeaderWhitespace() {
        String sql = "SELECT c.id FROM c";
        String token = query(sql, null, 2).header("x-ms-continuation");
        given().contentType("application/query+json").header("x-ms-documentdb-isquery", "true")
                .header("x-ms-documentdb-partitionkey", "[\"bob\"]")
                .header("x-ms-continuation", token).body(Map.of("query", sql))
                .post(DOCS).then().statusCode(400).body("code", is("BadRequest"));
        given().contentType("application/query+json").header("x-ms-documentdb-isquery", "true")
                .header("x-ms-continuation", token).body(Map.of("query", sql))
                .post(DOCS).then().statusCode(400).body("code", is("BadRequest"));
        given().contentType("application/query+json").header("x-ms-documentdb-isquery", "true")
                .header("x-ms-documentdb-partitionkey", "[ \"alice\" ]")
                .header("x-ms-continuation", token).body(Map.of("query", sql))
                .post(DOCS).then().statusCode(200).body("_count", is(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/otheracct-cosmos/dbs/pagination/colls/items",
            "/continuationacct-cosmos/dbs/otherdb/colls/items",
            DB + "/colls/otheritems", DB + "/colls/items"})
    void rejectsBookmarkForDifferentOrRecreatedContainer(String containerPath) {
        String sql = "SELECT c.id FROM c";
        String token = query(sql, null, 2).header("x-ms-continuation");
        if (containerPath.equals(DB + "/colls/items")) {
            given().delete(containerPath).then().statusCode(204);
        } else if (!containerPath.startsWith(DB + "/colls/")) {
            int dbStart = containerPath.indexOf("/dbs/");
            String dbId = containerPath.substring(dbStart + 5, containerPath.indexOf("/colls/"));
            given().contentType("application/json").body(Map.of("id", dbId))
                    .post(containerPath.substring(0, dbStart) + "/dbs").then().statusCode(201);
        }
        String collectionId = containerPath.substring(containerPath.lastIndexOf('/') + 1);
        given().contentType("application/json").body(Map.of("id", collectionId,
                        "partitionKey", Map.of("paths", List.of("/tenant"), "kind", "Hash")))
                .post(containerPath.substring(0, containerPath.lastIndexOf('/'))).then().statusCode(201);
        given().contentType("application/query+json").header("x-ms-documentdb-isquery", "true")
                .header("x-ms-documentdb-partitionkey", "[\"alice\"]")
                .header("x-ms-continuation", token).body(Map.of("query", sql))
                .post(containerPath + "/docs").then().statusCode(400).body("code", is("BadRequest"));
    }

    @Test
    void rejectsBookmarkForDifferentParameterValues() {
        String sql = "SELECT c.id FROM c WHERE c.rank >= @min";
        String token = given().contentType("application/query+json")
                .header("x-ms-documentdb-isquery", "true").header("x-ms-max-item-count", 2)
                .body(Map.of("query", sql, "parameters", List.of(Map.of("name", "@min", "value", 0))))
                .post(DOCS).then().statusCode(200).extract().header("x-ms-continuation");
        assertNotNull(token);
        given().contentType("application/query+json").header("x-ms-documentdb-isquery", "true")
                .header("x-ms-continuation", token)
                .body(Map.of("query", sql, "parameters", List.of(Map.of("name", "@min", "value", 1))))
                .post(DOCS).then().statusCode(400).body("code", is("BadRequest"));
    }

    private void insert(String id, String tenant, Integer rank) {
        Map<String, Object> document = new HashMap<>(Map.of("id", id, "tenant", tenant));
        document.put("rank", rank);
        given().contentType("application/json").body(document)
                .post(DOCS).then().statusCode(201);
    }

    private Response query(String sql, String continuation, int pageSize) {
        var request = given().contentType("application/query+json")
                .header("x-ms-documentdb-isquery", "true")
                .header("x-ms-documentdb-partitionkey", "[\"alice\"]")
                .header("x-ms-max-item-count", pageSize)
                .body(Map.of("query", sql));
        if (continuation != null) {
            request.header("x-ms-continuation", continuation);
        }
        return request.post(DOCS).then().statusCode(200).extract().response();
    }

    private List<String> ids(Response response) {
        return response.jsonPath().getList("Documents").stream()
                .map(item -> item instanceof Map<?, ?> doc ? (String) doc.get("id") : (String) item)
                .toList();
    }
}
