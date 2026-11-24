package io.floci.az.services.postgres;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Readiness is decided by PostgreSQL's own startup handshake, answered here by a fake server on a local socket:
 * an authentication request or a non-startup error means ready, {@code cannot_connect_now} or a closed connection
 * means not yet.
 */
class PostgresServerManagerReadinessTest {

    @Test
    void readinessFailsWhenThePortNeverOpensBeforeTheDeadline() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> PostgresServerManager.waitForReady("127.0.0.1", closedPort, "admin", 1));

        assertThat(failure.getMessage(), containsString("did not accept a connection within 1s"));
    }

    @Test
    void aPortThatAcceptsAndClosesIsNotReady() throws Exception {
        // What Docker's port proxy does while nothing in the container listens yet.
        try (FakeServer server = new FakeServer(attempt -> null)) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> PostgresServerManager.waitForReady("127.0.0.1", server.port(), "admin", 1));

            assertThat(failure.getMessage(), containsString("did not accept sessions within 1s"));
        }
    }

    @Test
    void aSilentServerStillFailsWithinTheDeadline() throws Exception {
        // The kernel completes the connect from the backlog, but nothing ever reads or answers the startup
        // message, so only the read timeout ends each attempt. It must not run past the 1 s deadline.
        try (ServerSocket silent = new ServerSocket(0)) {
            RuntimeException failure = assertTimeout(Duration.ofMillis(1_500), () -> assertThrows(
                    RuntimeException.class,
                    () -> PostgresServerManager.waitForReady("127.0.0.1", silent.getLocalPort(), "admin", 1)));

            assertThat(failure.getMessage(), containsString("did not accept sessions within 1s"));
        }
    }

    @Test
    void anAuthenticationRequestMeansReadyAndTheStartupMessageNamesTheAdmin() throws Exception {
        try (FakeServer server = new FakeServer(attempt -> authenticationRequest())) {
            assertDoesNotThrow(() -> PostgresServerManager.waitForReady("127.0.0.1", server.port(), "admin", 5));

            assertThat(server.startupParameters(), hasItem("user=admin"));
            assertThat(server.startupParameters(), hasItem("database=postgres"));
        }
    }

    @Test
    void aServerStillStartingUpIsPolledAgainUntilItAcceptsSessions() throws Exception {
        try (FakeServer server = new FakeServer(attempt -> attempt < 2 ? error("57P03") : authenticationRequest())) {
            assertDoesNotThrow(() -> PostgresServerManager.waitForReady("127.0.0.1", server.port(), "admin", 10));

            assertTrue(server.attempts() >= 3, "answered on the third attempt only");
        }
    }

    @Test
    void anErrorOtherThanStartingUpMeansTheServerIsUp() throws Exception {
        // invalid_authorization_specification: a role the server does not know is still a server accepting sessions.
        try (FakeServer server = new FakeServer(attempt -> error("28000"))) {
            assertDoesNotThrow(() -> PostgresServerManager.waitForReady("127.0.0.1", server.port(), "admin", 5));
        }
    }

    private static byte[] authenticationRequest() {
        // AuthenticationSASL-style request header: 'R', length 8, auth code 10 (no mechanisms needed for the probe).
        return message('R', new byte[] {0, 0, 0, 10});
    }

    private static byte[] error(String sqlState) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, 'S', "FATAL");
        writeField(body, 'C', sqlState);
        writeField(body, 'M', "fake");
        body.write(0);
        return message('E', body.toByteArray());
    }

    private static void writeField(ByteArrayOutputStream body, char type, String value) {
        body.write(type);
        body.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        body.write(0);
    }

    private static byte[] message(char type, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type);
        int length = body.length + 4;
        out.write(length >>> 24);
        out.write(length >>> 16);
        out.write(length >>> 8);
        out.write(length);
        out.writeBytes(body);
        return out.toByteArray();
    }

    /** Answers each connection's startup message with what {@code reply} returns for that attempt; null closes. */
    private static final class FakeServer implements AutoCloseable {
        private final ServerSocket socket = new ServerSocket(0);
        private final AtomicInteger attempts = new AtomicInteger();
        private final List<String> parameters = new CopyOnWriteArrayList<>();
        private final Thread acceptor;

        FakeServer(IntFunction<byte[]> reply) throws IOException {
            acceptor = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        int attempt = attempts.getAndIncrement();
                        readStartup(new DataInputStream(client.getInputStream()));
                        byte[] answer = reply.apply(attempt);
                        if (answer != null) {
                            new DataOutputStream(client.getOutputStream()).write(answer);
                            client.getOutputStream().flush();
                        }
                    } catch (IOException ignored) {
                        // The socket closes when the test ends; nothing to answer then.
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private void readStartup(DataInputStream in) throws IOException {
            byte[] body = new byte[in.readInt() - 4];
            in.readFully(body);
            List<String> strings = new ArrayList<>();
            int start = 4;
            for (int i = 4; i < body.length; i++) {
                if (body[i] == 0) {
                    if (i > start) {
                        strings.add(new String(body, start, i - start, StandardCharsets.UTF_8));
                    }
                    start = i + 1;
                }
            }
            for (int i = 0; i + 1 < strings.size(); i += 2) {
                parameters.add(strings.get(i) + "=" + strings.get(i + 1));
            }
        }

        int port() {
            return socket.getLocalPort();
        }

        int attempts() {
            return attempts.get();
        }

        List<String> startupParameters() {
            return parameters;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
