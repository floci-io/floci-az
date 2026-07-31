package io.floci.az.services.cosmos;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;

@QuarkusTest
class CosmosCorrelatedExistsTest {

    private static final String BASE = "/existsacct-cosmos";
    private static final String DOCS = BASE + "/dbs/db/colls/items/docs";

    @BeforeEach
    void setup() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{\"id\":\"db\"}")
                .post(BASE + "/dbs").then().statusCode(201);
        given().contentType("application/json")
                .body("{\"id\":\"items\",\"partitionKey\":{\"paths\":[\"/pk\"],\"kind\":\"Hash\"}}")
                .post(BASE + "/dbs/db/colls").then().statusCode(201);

        create("matching", """
                [{"resolvesOn":{"type":"rsvp"}},{"resolvesOn":{"type":"poll"}}]""");
        create("non-matching", """
                [{"resolvesOn":{"type":"comment"}}]""");
        create("empty", "[]");
    }

    @Test
    void correlatedExistsReturnsOnlyDocumentsWithMatchingArrayItem() {
        given().contentType("application/query+json")
                .header("x-ms-documentdb-isquery", "True")
                .body("""
                        {
                          "query": "SELECT * FROM c WHERE EXISTS(SELECT VALUE action FROM action IN c.actions WHERE action.resolvesOn.type = @type)",
                          "parameters": [{"name":"@type","value":"rsvp"}]
                        }""")
                .post(DOCS)
                .then().statusCode(200)
                .body("Documents.id", contains("matching"));
    }

    private void create(String id, String actions) {
        given().contentType("application/json")
                .header("x-ms-documentdb-partitionkey", "[\"p\"]")
                .body("{\"id\":\"" + id + "\",\"pk\":\"p\",\"actions\":" + actions + "}")
                .post(DOCS).then().statusCode(201);
    }
}
