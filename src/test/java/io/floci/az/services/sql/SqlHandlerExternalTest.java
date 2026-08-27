package io.floci.az.services.sql;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(SqlHandlerExternalTest.ExternalProfile.class)
class SqlHandlerExternalTest {

    @Inject
    SqlState state;

    @BeforeEach
    void resetState() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void externalProviderRejectsConnectionDiscoveryForPersistedManagedServer() {
        state.putServer(new SqlState.SqlServerEntry(
            "externalserver", "test-sub", "test-rg", "eastus", "sa", "StrongPass1!",
            null, 14330, "localhost", Map.of(), new ConcurrentHashMap<>(),
            new ConcurrentHashMap<>(), Instant.now()));

        given()
            .when().get("/devstoreaccount1-sql/servers/externalserver/connect")
            .then().statusCode(503)
            .body("error.code", equalTo("DataPlaneProviderUnavailable"));
    }

    public static class ExternalProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.sql.data-plane.provider", "external");
        }
    }
}
