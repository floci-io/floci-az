package io.floci.az.services.redis;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Quarkus-level tests for {@link RedisHandler}.
 *
 * <p>Always runs in mocked mode (no Redis container) regardless of the default in application.yml.
 * Covers ARM path routing, CRUD, and the listKeys / regenerateKey actions.</p>
 */
@QuarkusTest
@TestProfile(RedisHandlerTest.MockedProfile.class)
@DisplayName("RedisHandler — routing and CRUD (mocked mode)")
@SuppressWarnings("unused")
class RedisHandlerTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.redis.mocked", "true");
        }
    }

    private static final String SUB  = "test-sub-redis";
    private static final String RG   = "test-rg-redis";
    private static final String API  = "?api-version=2024-11-01";
    private static final String BASE =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.Cache";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static final String CREATE_BODY = """
            {
              "location": "eastus",
              "properties": {
                "sku": {"name": "Basic", "family": "C", "capacity": 0},
                "enableNonSslPort": true,
                "minimumTlsVersion": "1.2"
              }
            }
            """;

    @Test
    @DisplayName("GET unknown cache returns 404")
    void getUnknownCacheReturns404() {
        given()
            .when().get(BASE + "/redis/no-such-cache" + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    @DisplayName("PUT cache creates cache and returns 201 with Succeeded state")
    void createCacheReturns201() {
        given()
            .contentType("application/json").body(CREATE_BODY)
            .when().put(BASE + "/redis/mycache" + API)
            .then().statusCode(201)
            .body("name", equalTo("mycache"))
            .body("type", equalTo("Microsoft.Cache/Redis"))
            .body("location", equalTo("eastus"))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.hostName", equalTo("localhost"))
            .body("properties.port", equalTo(6379))
            .body("properties.sslPort", equalTo(6380))
            .body("properties.sku.name", equalTo("Basic"))
            .body("properties.enableNonSslPort", equalTo(true))
            .body("properties.accessKeys.primaryKey", notNullValue())
            .body("properties.accessKeys.secondaryKey", notNullValue())
            .body("id", containsString("/providers/Microsoft.Cache/Redis/mycache"));
    }

    @Test
    @DisplayName("GET cache after create returns 200")
    void getCacheAfterCreate() {
        given()
            .contentType("application/json").body(CREATE_BODY)
            .put(BASE + "/redis/get-test" + API)
            .then().statusCode(201);

        given()
            .when().get(BASE + "/redis/get-test" + API)
            .then().statusCode(200)
            .body("name", equalTo("get-test"))
            .body("properties.provisioningState", equalTo("Succeeded"));
    }

    @Test
    @DisplayName("DELETE cache returns 202 and subsequent GET returns 404")
    void deleteCacheReturns202() {
        given()
            .contentType("application/json").body(CREATE_BODY)
            .put(BASE + "/redis/del-cache" + API)
            .then().statusCode(201);

        given()
            .when().delete(BASE + "/redis/del-cache" + API)
            .then().statusCode(202);

        given()
            .when().get(BASE + "/redis/del-cache" + API)
            .then().statusCode(404);
    }

    @Test
    @DisplayName("LIST caches by resource group returns value array")
    void listByResourceGroup() {
        given().contentType("application/json").body(CREATE_BODY)
            .put(BASE + "/redis/list-a" + API).then().statusCode(201);
        given().contentType("application/json").body(CREATE_BODY)
            .put(BASE + "/redis/list-b" + API).then().statusCode(201);

        given()
            .when().get(BASE + "/redis" + API)
            .then().statusCode(200)
            .body("value", hasSize(greaterThanOrEqualTo(2)));
    }

    @Test
    @DisplayName("LIST caches by subscription returns value array")
    void listBySubscription() {
        given().contentType("application/json").body(CREATE_BODY)
            .put(BASE + "/redis/sub-cache" + API).then().statusCode(201);

        given()
            .when().get("/subscriptions/" + SUB + "/providers/Microsoft.Cache/redis" + API)
            .then().statusCode(200)
            .body("value", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("listKeys returns both access keys")
    void listKeysReturnsBothKeys() {
        given().contentType("application/json").body(CREATE_BODY)
            .put(BASE + "/redis/keys-cache" + API).then().statusCode(201);

        given()
            .contentType("application/json")
            .when().post(BASE + "/redis/keys-cache/listKeys" + API)
            .then().statusCode(200)
            .body("primaryKey", notNullValue())
            .body("secondaryKey", notNullValue());
    }

    @Test
    @DisplayName("regenerateKey rotates the primary key")
    void regenerateKeyRotatesPrimary() {
        given().contentType("application/json").body(CREATE_BODY)
            .put(BASE + "/redis/regen-cache" + API).then().statusCode(201);

        String original = given()
            .contentType("application/json")
            .post(BASE + "/redis/regen-cache/listKeys" + API)
            .then().statusCode(200)
            .extract().path("primaryKey");

        String rotated = given()
            .contentType("application/json").body("{\"keyType\":\"Primary\"}")
            .when().post(BASE + "/redis/regen-cache/regenerateKey" + API)
            .then().statusCode(200)
            .body("primaryKey", not(equalTo(original)))
            .extract().path("primaryKey");

        // The rotated key is persisted
        given()
            .contentType("application/json")
            .post(BASE + "/redis/regen-cache/listKeys" + API)
            .then().statusCode(200)
            .body("primaryKey", equalTo(rotated));
    }

    @Test
    @DisplayName("PATCH updates tags")
    void patchTags() {
        given().contentType("application/json").body(CREATE_BODY)
            .put(BASE + "/redis/tag-cache" + API).then().statusCode(201);

        given()
            .contentType("application/json").body("{\"tags\":{\"env\":\"test\"}}")
            .when().patch(BASE + "/redis/tag-cache" + API)
            .then().statusCode(200)
            .body("tags.env", equalTo("test"));
    }
}
