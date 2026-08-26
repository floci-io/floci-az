package io.floci.az.services.cosmos;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CosmosAccountInfoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void accountAdvertisesDotnetQueryEngineConfiguration() throws Exception {
        String serializedConfiguration = given()
                .get("/queryconfig-cosmos/")
                .then()
                .statusCode(200)
                .extract()
                .path("queryEngineConfiguration");

        Map<String, Object> configuration = MAPPER.readValue(
                serializedConfiguration, new TypeReference<>() {});

        assertFalse(configuration.isEmpty());
        assertEquals(262144, configuration.get("maxSqlQueryInputLength"));
        assertEquals(16000, configuration.get("maxInExpressionItemsCount"));
        assertTrue((Boolean) configuration.get("sqlAllowAggregateFunctions"));
        assertTrue((Boolean) configuration.get("sqlAllowGroupByClause"));
        assertTrue((Boolean) configuration.get("sqlAllowTop"));
    }

    @Test
    void partitionKeyRangesCanBeReadThroughRidLink() {
        String base = "/queryconfig-cosmos";
        String databaseRid = given().contentType("application/json").body("{\"id\":\"db\"}")
                .post(base + "/dbs").then().statusCode(201)
                .extract().path("_rid");
        String containerRid = given().contentType("application/json")
                .body("{\"id\":\"items\",\"partitionKey\":{\"paths\":[\"/pk\"],\"kind\":\"Hash\"}}")
                .post(base + "/dbs/db/colls")
                .then().statusCode(201)
                .extract().path("_rid");
        byte[] databaseRidBytes = Base64.getDecoder().decode(databaseRid.replace('-', '/'));
        byte[] containerRidBytes = Base64.getDecoder().decode(containerRid.replace('-', '/'));
        assertArrayEquals(databaseRidBytes, Arrays.copyOf(containerRidBytes, databaseRidBytes.length));

        Response response = given().pathParam("databaseRid", databaseRid).pathParam("containerRid", containerRid)
                .get(base + "/dbs/{databaseRid}/colls/{containerRid}/pkranges");
        response.then().statusCode(200)
                .body("_count", is(1))
                .body("PartitionKeyRanges[0].minInclusive", is(""))
                .body("PartitionKeyRanges[0].maxExclusive", is("FF"));

        given().pathParam("databaseRid", databaseRid).pathParam("containerRid", containerRid)
                .header("If-None-Match", response.header("etag"))
                .get(base + "/dbs/{databaseRid}/colls/{containerRid}/pkranges")
                .then().statusCode(304);

        String otherDatabaseRid = given().contentType("application/json").body("{\"id\":\"other\"}")
                .post(base + "/dbs").then().statusCode(201)
                .extract().path("_rid");
        given().pathParam("databaseRid", otherDatabaseRid).pathParam("containerRid", containerRid)
                .get(base + "/dbs/{databaseRid}/colls/{containerRid}/pkranges")
                .then().statusCode(404);
    }
}
