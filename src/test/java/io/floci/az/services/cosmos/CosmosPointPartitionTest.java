package io.floci.az.services.cosmos;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class CosmosPointPartitionTest {
    private static final String BASE = "/pointpartition-cosmos/dbs/db";
    private static final String DOCS = BASE + "/colls/items/docs";

    @BeforeEach
    void setup() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body(Map.of("id", "db"))
                .post("/pointpartition-cosmos/dbs").then().statusCode(201);
        given().contentType("application/json")
                .body("{\"id\":\"items\",\"partitionKey\":{\"paths\":[\"/pk\"],\"kind\":\"Hash\"}}")
                .post(BASE + "/colls").then().statusCode(201);
        create("alice");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"bob\"]", "[\"\"]", "[null]", "[{}]"})
    void readWithMissingPartitionDoesNotFindAnotherPartitionsDocument(String partition) {
        given().header("x-ms-documentdb-partitionkey", partition)
                .get(DOCS + "/same-id").then().statusCode(404);
        assertAliceExists();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"bob\"]", "[\"\"]", "[null]", "[{}]"})
    void deleteWithMissingPartitionDoesNotDeleteAnotherPartitionsDocument(String partition) {
        given().header("x-ms-documentdb-partitionkey", partition)
                .delete(DOCS + "/same-id").then().statusCode(404);
        assertAliceExists();
    }

    @Test
    void sameIdInDifferentPartitionsRemainsIndependent() {
        create("bob");
        given().header("x-ms-documentdb-partitionkey", "[\"bob\"]")
                .get(DOCS + "/same-id").then().statusCode(200).body("pk", is("bob"));
        given().header("x-ms-documentdb-partitionkey", "[\"bob\"]")
                .delete(DOCS + "/same-id").then().statusCode(204);
        given().header("x-ms-documentdb-partitionkey", "[\"bob\"]")
                .get(DOCS + "/same-id").then().statusCode(404);
        assertAliceExists();
    }

    private void create(String partition) {
        given().contentType("application/json")
                .body(Map.of("id", "same-id", "pk", partition))
                .post(DOCS).then().statusCode(201);
    }

    @Test
    void typedPartitionValuesCannotReadOrDeleteStringPartitions() {
        create("");
        create("42");
        for (String partition : new String[] {"[null]", "[42]"}) {
            given().header("x-ms-documentdb-partitionkey", partition)
                    .get(DOCS + "/same-id").then().statusCode(404);
            given().header("x-ms-documentdb-partitionkey", partition)
                    .delete(DOCS + "/same-id").then().statusCode(404);
        }
        given().header("x-ms-documentdb-partitionkey", "[\"\"]")
                .get(DOCS + "/same-id").then().statusCode(200);
        given().header("x-ms-documentdb-partitionkey", "[\"42\"]")
                .get(DOCS + "/same-id").then().statusCode(200);
    }

    @Test
    void equivalentNumericPartitionRepresentationsStillMatch() {
        given().contentType("application/json").body(Map.of("id", "numeric", "pk", 42))
                .post(DOCS).then().statusCode(201);
        given().header("x-ms-documentdb-partitionkey", "[42.0]")
                .get(DOCS + "/numeric").then().statusCode(200);
    }

    private void assertAliceExists() {
        given().header("x-ms-documentdb-partitionkey", "[\"alice\"]")
                .get(DOCS + "/same-id").then().statusCode(200).body("pk", is("alice"));
    }
}
