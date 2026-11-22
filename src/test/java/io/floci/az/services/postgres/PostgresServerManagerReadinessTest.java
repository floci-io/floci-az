package io.floci.az.services.postgres;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresServerManagerReadinessTest {

    @Test
    void readinessFailsWhenThePortNeverOpensBeforeTheDeadline() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> PostgresServerManager.waitForReady("127.0.0.1", closedPort, 1));

        assertThat(failure.getMessage(), containsString("did not accept a connection within 1s"));
    }

    @Test
    void readinessReturnsOnceThePortAcceptsAConnection() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            assertDoesNotThrow(() -> PostgresServerManager.waitForReady("127.0.0.1", socket.getLocalPort(), 1));
        }
    }
}
