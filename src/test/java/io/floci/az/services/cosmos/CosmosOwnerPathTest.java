package io.floci.az.services.cosmos;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

/**
 * Reads answer {@code x-ms-alt-content-path}, the owner's full name, as creates do. The Java SDK sets every resource's
 * alt link from it; a container read without it leaves the change feed processor with a null collection link.
 */
@QuarkusTest
class CosmosOwnerPathTest {

    private static final String BASE = "/ownerpathacct-cosmos";
    private static final String DB = "shop";
    private static final String COLL = BASE + "/dbs/" + DB + "/colls/orders";

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{\"id\":\"" + DB + "\"}")
                .when().post(BASE + "/dbs").then().statusCode(201);
        given().contentType("application/json")
                .body("{\"id\":\"orders\",\"partitionKey\":{\"paths\":[\"/id\"],\"kind\":\"Hash\"}}")
                .when().post(BASE + "/dbs/" + DB + "/colls").then().statusCode(201);
    }

    @Test
    @DisplayName("a container read names its database as the owner")
    void containerReadNamesItsDatabase() {
        given().when().get(COLL)
                .then().statusCode(200)
                .header("x-ms-alt-content-path", is("dbs/" + DB))
                .body("id", is("orders"));
    }

    @Test
    @DisplayName("a container read by resource ids answers the container with its name-based owner")
    void containerReadByResourceIds() {
        String dbRid = given().when().get(BASE + "/dbs/" + DB).then().statusCode(200).extract().path("_rid");
        String collRid = given().when().get(COLL).then().statusCode(200).extract().path("_rid");

        given().when().get(BASE + "/dbs/" + dbRid + "/colls/" + collRid)
                .then().statusCode(200)
                .header("x-ms-alt-content-path", is("dbs/" + DB))
                .body("id", is("orders"));
    }

    @Test
    @DisplayName("an item read and replace name the container as the owner")
    void itemReadAndReplaceNameTheContainer() {
        given().contentType("application/json").header("x-ms-documentdb-partitionkey", "[\"o-1\"]")
                .body("{\"id\":\"o-1\",\"total\":1}")
                .when().post(COLL + "/docs").then().statusCode(201);

        given().header("x-ms-documentdb-partitionkey", "[\"o-1\"]")
                .when().get(COLL + "/docs/o-1")
                .then().statusCode(200)
                .header("x-ms-alt-content-path", is("dbs/" + DB + "/colls/orders"));

        given().contentType("application/json").header("x-ms-documentdb-partitionkey", "[\"o-1\"]")
                .body("{\"id\":\"o-1\",\"total\":2}")
                .when().put(COLL + "/docs/o-1")
                .then().statusCode(200)
                .header("x-ms-alt-content-path", is("dbs/" + DB + "/colls/orders"));
    }
}
