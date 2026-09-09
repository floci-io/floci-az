package io.floci.az.services.mysql;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Setting {@code default-port} must not make mocked mode start claiming host ports.
 *
 * <p>Mocked mode exists so that {@code plan}/CI runs need no Docker; a configured port there
 * describes a container that is never created, so nothing should be reserved and nothing should
 * be bound. This runs everywhere, with or without a Docker daemon.
 */
@QuarkusTest
@TestProfile(MySqlDefaultPortMockedTest.FixedPortMockedProfile.class)
@DisplayName("MySqlHandler — default-port is inert in mocked mode")
class MySqlDefaultPortMockedTest {

    private static final int CONFIGURED_PORT = 15306;

    public static class FixedPortMockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "floci-az.services.mysql.mocked", "true",
                "floci-az.services.mysql.default-port", String.valueOf(CONFIGURED_PORT));
        }
    }

    private static final String BASE = "/subscriptions/test-sub-mysql-port/resourceGroups/test-rg-mysql-port"
                                     + "/providers/Microsoft.DBforMySQL";

    @Test
    @DisplayName("a mocked server is created without claiming the configured port")
    void mockedServerDoesNotClaimTheConfiguredPort() throws IOException {
        given()
            .contentType("application/json")
            .body("""
                {
                  "location": "eastus",
                  "properties": {
                    "administratorLogin": "mysqladmin",
                    "administratorLoginPassword": "Str0ng!Passw0rd",
                    "version": "8.0"
                  }
                }""")
            .when()
            .put(BASE + "/flexibleServers/port-test-server?api-version=2023-06-30")
            .then()
            .statusCode(201)
            .body("properties.state", equalTo("Ready"));

        // Nothing was started, so the port must still be bindable by anyone else.
        try (ServerSocket probe = new ServerSocket(CONFIGURED_PORT)) {
            assert probe.isBound();
        }
    }
}
