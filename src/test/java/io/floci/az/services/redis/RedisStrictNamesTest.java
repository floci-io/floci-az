package io.floci.az.services.redis;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(RedisStrictNamesTest.StrictNames.class)
@DisplayName("Redis: parity-overrides.strict-names refuses a cache name outside Azure's rule")
class RedisStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.parity-overrides.strict-names", "true",
                    "floci-az.services.redis.mocked", "true");
        }
    }

    private static final String BASE = "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.Cache/Redis/";
    private static final String API = "?api-version=2024-11-01";
    private static final String BODY = "{\"location\":\"eastus\",\"properties\":{\"sku\":"
        + "{\"name\":\"Basic\",\"family\":\"C\",\"capacity\":0}}}";

    @Test
    void aNameOutsideTheRuleIsRefusedAndAValidOneIsCreated() {
        given().contentType("application/json").body(BODY).when().put(BASE + "bad--name" + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        given().contentType("application/json").body(BODY).when().put(BASE + "good-name" + API)
            .then().statusCode(201);
    }
}
