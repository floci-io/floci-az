package io.floci.az.services.cosmos;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CosmosQueryPartitionTest {
    private static final String DB = "/partitionacct-cosmos/dbs/queryscope";
    private static final String DOCS = DB + "/colls/items/docs";

    @BeforeEach
    void setup() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body(Map.of("id", "queryscope"))
                .post("/partitionacct-cosmos/dbs").then().statusCode(201);
        given().contentType("application/json").body("""
                {"id":"items","partitionKey":{"paths":["/tenant/key"],"kind":"Hash"}}
                """).post(DB + "/colls").then().statusCode(201);
        insert("a1", "\"alice\"", 2);
        insert("b1", "\"bob\"", 0);
        insert("a2", "\"alice\"", 1);
    }

    @Test
    void scopesRowsBeforeAggregationAndPagination() {
        query("SELECT * FROM c", "[\"alice\"]", null).then()
                .body("Documents.id", containsInAnyOrder("a1", "a2"));
        query("SELECT VALUE COUNT(1) FROM c", "[\"alice\"]", null).then()
                .body("Documents", contains(2));
        String sql = "SELECT * FROM c ORDER BY c.rank";
        Response first = query(sql, "[\"alice\"]", null);
        first.then().body("Documents.id", contains("a2"));
        query(sql, "[\"alice\"]", first.header("x-ms-continuation")).then()
                .body("Documents.id", contains("a1"))
                .header("x-ms-continuation", nullValue());
        query("SELECT TOP 1 * FROM c ORDER BY c.rank", "[\"alice\"]", null)
                .then().body("Documents.id", contains("a2"));
    }

    @Test
    void missingPartitionIsEmptyAndUnscopedQueriesStillCrossPartitions() {
        query("SELECT * FROM c", "[\"nobody\"]", null).then().body("_count", is(0));
        query("SELECT * FROM c", null, null).then()
                .body("Documents.id", containsInAnyOrder("a1", "a2", "b1"));
    }

    @Test
    void preservesPartitionValueTypes() {
        insert("number", "1", 3);
        insert("string", "\"1\"", 4);
        insert("null", "null", 5);
        insert("boolean", "true", 6);
        given().contentType("application/json").body("{\"id\":\"missing\"}")
                .post(DOCS).then().statusCode(201);
        query("SELECT * FROM c", "[1.0]", null).then().body("Documents.id", contains("number"));
        query("SELECT * FROM c", "[\"1\"]", null).then().body("Documents.id", contains("string"));
        query("SELECT * FROM c", "[null]", null).then().body("Documents.id", contains("null"));
        query("SELECT * FROM c", "[true]", null).then().body("Documents.id", contains("boolean"));
        query("SELECT * FROM c", "[{}]", null).then().body("Documents.id", contains("missing"));
    }

    @Test
    void malformedScopeDoesNotBecomeCrossPartitionQuery() {
        for (String scope : new String[] {"oops", "{}", "[]", "[[]]", "[{\"x\":1}]", "[1,2]"}) {
            given().contentType("application/query+json")
                    .header("x-ms-documentdb-isquery", "true")
                    .header("x-ms-documentdb-partitionkey", scope)
                    .body(Map.of("query", "SELECT * FROM c"))
                    .post(DOCS).then().statusCode(400);
        }
    }

    private void insert(String id, String key, int rank) {
        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", "[" + key + "]")
                .body("{\"id\":\"%s\",\"tenant\":{\"key\":%s},\"rank\":%d}".formatted(id, key, rank))
                .post(DOCS).then().statusCode(201);
    }

    private Response query(String sql, String scope, String continuation) {
        var request = given().contentType("application/query+json")
                .header("x-ms-documentdb-isquery", "true").body(Map.of("query", sql));
        if (scope != null) {
            request.header("x-ms-documentdb-partitionkey", scope);
        }
        if (sql.equals("SELECT * FROM c ORDER BY c.rank")) {
            request.header("x-ms-max-item-count", "1");
        }
        if (continuation != null) {
            request.header("x-ms-continuation", continuation);
        }
        return request.post(DOCS).then().statusCode(200).extract().response();
    }
}
