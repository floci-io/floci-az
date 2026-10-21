package io.floci.az.services.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.redis.RedisModels.RedisCache;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Redis: one store keyed by canonical id, and the global name registry")
class RedisGlobalNameTest {

    private static final String API = "?api-version=2024-11-01";
    private static final String BODY = "{\"location\":\"eastus\",\"properties\":{\"sku\":"
        + "{\"name\":\"Basic\",\"family\":\"C\",\"capacity\":0}}}";

    @Inject
    StorageFactory storageFactory;

    @Inject
    GlobalNameRegistry names;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String cache(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Cache/Redis/" + name;
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneCache() {
        given().contentType("application/json").body(BODY).when().put(cache("sub-case", "RG-Case", "casecache") + API)
            .then().statusCode(201);
        given().contentType("application/json").body(BODY).when().put(cache("sub-case", "rg-case", "casecache") + API)
            .then().statusCode(200)
            .body("id", equalTo(cache("sub-case", "RG-Case", "casecache")));

        given().when().get("/subscriptions/sub-case/resourceGroups/rg-case/providers/Microsoft.Cache/redis" + API)
            .then().statusCode(200).body("value", hasSize(1));
    }

    @Test
    void checkNameAvailabilityAnswersFromTheRegistry() {
        String check = "/subscriptions/sub-check/providers/Microsoft.Cache/checkNameAvailability" + API;
        String request = "{\"name\":\"checkcache\",\"type\":\"Microsoft.Cache/Redis\"}";

        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body(emptyString());
        given().contentType("application/json").body(BODY).when().put(cache("sub-owner", "rg", "checkcache") + API)
            .then().statusCode(201);
        given().contentType("application/json").body(request).when().post(check).then().statusCode(409)
            .body("error.code", equalTo("NameNotAvailable"));
        given().when().delete(cache("sub-owner", "rg", "checkcache") + API).then().statusCode(202);
        given().contentType("application/json").body(request).when().post(check).then().statusCode(200);
    }

    @Test
    void aCacheStoredUnderTheOldKeyIsServedOwnsItsNameAndMovesOnItsFirstWrite() throws Exception {
        RedisCache legacy = new RedisCache();
        legacy.setInstanceId("abcd1234");
        legacy.setSubscriptionId("Sub-Legacy");
        legacy.setResourceGroup("RG-Legacy");
        legacy.setName("legacycache");
        legacy.setLocation("eastus");
        legacy.setSkuName("Basic");
        legacy.setSkuFamily("C");
        legacy.setProvisioningState("Succeeded");
        legacy.setCreatedAt(Instant.now());
        StorageBackend<String, StoredObject> backend = storageFactory.create("redis");
        String oldKey = "Sub-Legacy/RG-Legacy/legacycache";
        backend.put(oldKey, new StoredObject(oldKey,
            new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsBytes(legacy), Map.of(), Instant.now(),
            oldKey));
        names.clear();

        given().when().get(cache("sub-legacy", "rg-legacy", "legacycache") + API).then().statusCode(200)
            .body("name", equalTo("legacycache"));
        given().contentType("application/json").body(BODY).when().put(cache("sub-other", "rg", "legacycache") + API)
            .then().statusCode(409).body("error.code", equalTo("NameNotAvailable"));

        given().contentType("application/json").body("{\"tags\":{\"env\":\"t\"}}")
            .when().patch(cache("Sub-Legacy", "RG-Legacy", "legacycache") + API).then().statusCode(200);
        assertFalse(backend.get(oldKey).isPresent(), "the write moved the entry off its old key");
        assertTrue(backend.keys().stream().anyMatch(key -> key.equals(cache("sub-legacy", "rg-legacy", "legacycache")
            .replace("Redis", "redis").toLowerCase())));
    }
}
