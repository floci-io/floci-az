package io.floci.az.services.redis;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * With {@code floci-az.services.redis.tls-enabled} the SSL port is served, so a create that omits
 * {@code enableNonSslPort} reports the spec default, {@code false}. Mocked mode keeps Docker out.
 */
@QuarkusTest
@TestProfile(RedisHandlerTlsTest.TlsProfile.class)
@DisplayName("RedisHandler with TLS enabled (mocked mode)")
class RedisHandlerTlsTest {

    public static class TlsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.services.redis.mocked", "true",
                    "floci-az.services.redis.tls-enabled", "true");
        }
    }

    private static final String BASE = "/subscriptions/test-sub-redis-tls/resourceGroups/test-rg-redis-tls"
            + "/providers/Microsoft.Cache";
    private static final String API = "?api-version=2024-11-01";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void aCreateWithoutEnableNonSslPortReportsTheSpecDefaultFalse() {
        given()
            .contentType("application/json")
            .body("""
                    {"location": "eastus", "properties": {"sku": {"name": "Basic", "family": "C", "capacity": 0}}}
                    """)
            .when().put(BASE + "/redis/tls-defaults" + API)
            .then().statusCode(201)
            .body("properties.enableNonSslPort", equalTo(false))
            .body("properties.sslPort", equalTo(6380));
    }

    @Test
    void anExplicitEnableNonSslPortIsKept() {
        given()
            .contentType("application/json")
            .body("""
                    {"location": "eastus", "properties": {"sku": {"name": "Basic", "family": "C", "capacity": 0},
                     "enableNonSslPort": true}}
                    """)
            .when().put(BASE + "/redis/tls-explicit" + API)
            .then().statusCode(201)
            .body("properties.enableNonSslPort", equalTo(true));
    }
}
