package io.floci.az.services.mysql;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Setting {@code default-port} must not make mocked mode start claiming host ports.
 *
 * <p>Mocked mode exists so that {@code plan}/CI runs need no Docker; a configured port there
 * describes a container that is never created, so nothing should be reserved and nothing bound.
 *
 * <p>The port is chosen at class-init time from an ephemeral bind rather than hardcoded, because
 * a fixed number would make this test fail on any machine already using it.
 */
@QuarkusTest
@TestProfile(MySqlDefaultPortMockedTest.FixedPortMockedProfile.class)
@DisplayName("MySqlHandler — default-port is inert in mocked mode")
class MySqlDefaultPortMockedTest {

    // Shared through a system property, not a static field: Quarkus loads the profile and the test
    // in different classloaders, so a plain static is initialised twice and the assertion below
    // would probe a port the emulator was never configured with.
    private static final String PORT_PROPERTY = "flociaz.test.mysqlport.mocked";

    private static int configuredPort() {
        String existing = System.getProperty(PORT_PROPERTY);
        if (existing != null) {
            return Integer.parseInt(existing);
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            int port = socket.getLocalPort();
            System.setProperty(PORT_PROPERTY, String.valueOf(port));
            return port;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not find a free port for the test profile", e);
        }
    }

    public static class FixedPortMockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "floci-az.services.mysql.mocked", "true",
                "floci-az.services.mysql.default-port", String.valueOf(configuredPort()));
        }
    }

    private static final String BASE = "/subscriptions/test-sub-mysql-port/resourceGroups/test-rg-mysql-port"
                                     + "/providers/Microsoft.DBforMySQL";

    @Test
    @DisplayName("a mocked server is created without claiming the configured port")
    void mockedServerDoesNotClaimTheConfiguredPort() {
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
        assertDoesNotThrow(() -> {
            try (ServerSocket probe = new ServerSocket(configuredPort())) {
                probe.getLocalPort();
            }
        }, "mocked mode must not bind or reserve the configured port");
    }
}
