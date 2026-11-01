package io.floci.az.services.servicebus;

import io.floci.az.core.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.az.services.eventhub.ArtemisTlsGenerator;
import io.floci.az.services.eventhub.ArtemisTlsGenerator.TlsBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedConstruction;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;

class ArtemisReadinessProbeTest {

    private static final byte[] AMQP_SASL_HEADER = {'A', 'M', 'Q', 'P', 3, 1, 0, 0};

    @ParameterizedTest
    @MethodSource("unresponsiveEndpoints")
    void unresponsiveEndpointReachesReadinessDeadline(String host, int timeoutSeconds) {
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class, (socket, context) ->
                doAnswer(invocation -> {
                    int timeoutMillis = invocation.getArgument(1);
                    assertTrue(timeoutMillis > 0, "connect must have a finite deadline");
                    assertTrue(timeoutMillis <= 1_000, "connect must stay within its one-second budget");
                    Thread.sleep(timeoutMillis);
                    throw new SocketTimeoutException("endpoint no longer replies");
                }).when(socket).connect(any(), anyInt()))) {
            RuntimeException failure = assertTimeout(Duration.ofSeconds(timeoutSeconds).plusMillis(300), () ->
                    assertThrows(RuntimeException.class, () ->
                            ArtemisReadinessProbe.awaitAmqp(new EndpointInfo(host, 5672), timeoutSeconds)));
            assertTrue(failure.getMessage().contains("within " + timeoutSeconds + "s"));
        }
    }

    static Stream<Arguments> unresponsiveEndpoints() {
        return Stream.of(Arguments.of(null, 1), Arguments.of("192.0.2.1", 1),
                Arguments.of("192.0.2.1", 2));
    }

    @Test
    void quickFailureRetriesWithoutSleepingPastRemainingBudget() {
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class, (socket, context) ->
                doAnswer(invocation -> {
                    if (context.getCount() > 1) {
                        int timeoutMillis = invocation.getArgument(1);
                        Thread.sleep(Math.min(400, timeoutMillis));
                    }
                    throw new SocketTimeoutException("endpoint no longer replies");
                }).when(socket).connect(any(), anyInt()))) {
            RuntimeException failure = assertTimeout(Duration.ofMillis(1_300), () ->
                    assertThrows(RuntimeException.class, () ->
                            ArtemisReadinessProbe.awaitAmqp(new EndpointInfo("192.0.2.1", 5672), 1)));
            assertTrue(sockets.constructed().size() >= 2, "an immediate failure must retry within the deadline");
            assertTrue(failure.getMessage().contains("within 1s"));
        }
    }

    @Test
    void aPortThatAcceptsAndClosesIsNotReady() throws Exception {
        // What Docker Desktop's port forwarder does while nothing in the container listens yet.
        try (FakeListener listener = FakeListener.plain(connectionNumber -> false)) {
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                    ArtemisReadinessProbe.awaitAmqp(listener.endpoint(), 1));
            assertTrue(failure.getMessage().contains("within 1s"));
            assertTrue(listener.accepted() >= 1, "the probe must have connected");
        }
    }

    @Test
    void aBrokerThatAnswersTheProtocolHeaderIsReady() throws Exception {
        try (FakeListener listener = FakeListener.plain(connectionNumber -> true)) {
            assertDoesNotThrow(() -> ArtemisReadinessProbe.awaitAmqp(listener.endpoint(), 5));
            assertEquals(1, listener.accepted());
        }
    }

    @Test
    void theProbeKeepsTryingUntilTheBrokerAnswers() throws Exception {
        try (FakeListener listener = FakeListener.plain(connectionNumber -> connectionNumber >= 3)) {
            assertDoesNotThrow(() -> ArtemisReadinessProbe.awaitAmqp(listener.endpoint(), 10));
            assertEquals(3, listener.accepted());
        }
    }

    @Test
    void aTlsAcceptorPresentingTheGeneratedCertificateIsReady() throws Exception {
        TlsBundle tls = new ArtemisTlsGenerator().generate();
        try (FakeListener listener = FakeListener.tls(tls)) {
            assertDoesNotThrow(() -> ArtemisReadinessProbe.awaitAmqps(listener.endpoint(), tls.certPem(), 5));
        }
    }

    @Test
    void aTlsAcceptorPresentingAnotherCertificateIsNotReady() throws Exception {
        TlsBundle served = new ArtemisTlsGenerator().generate();
        TlsBundle expected = new ArtemisTlsGenerator().generate();
        try (FakeListener listener = FakeListener.tls(served)) {
            assertThrows(RuntimeException.class, () ->
                    ArtemisReadinessProbe.awaitAmqps(listener.endpoint(), expected.certPem(), 1));
        }
    }

    @Test
    void aPeerThatAcceptsButNeverAnswersTheProtocolHeaderReachesTheDeadline() throws Exception {
        try (SilentListener listener = new SilentListener()) {
            RuntimeException failure = assertTimeoutPreemptively(Duration.ofSeconds(4), () ->
                    assertThrows(RuntimeException.class, () ->
                            ArtemisReadinessProbe.awaitAmqp(listener.endpoint(), 2)));
            assertTrue(failure.getMessage().contains("within 2s"));
            assertTrue(listener.accepted() >= 1, "the probe must have connected");
        }
    }

    @Test
    void aPeerThatAcceptsButNeverAnswersTheTlsHandshakeReachesTheDeadline() throws Exception {
        TlsBundle tls = new ArtemisTlsGenerator().generate();
        try (SilentListener listener = new SilentListener()) {
            RuntimeException failure = assertTimeoutPreemptively(Duration.ofSeconds(4), () ->
                    assertThrows(RuntimeException.class, () ->
                            ArtemisReadinessProbe.awaitAmqps(listener.endpoint(), tls.certPem(), 2)));
            assertTrue(failure.getMessage().contains("within 2s"));
            assertTrue(listener.accepted() >= 1, "the probe must have connected");
        }
    }

    /** A loopback listener that accepts connections and holds them open without ever writing a byte. */
    private static final class SilentListener implements AutoCloseable {

        private final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final List<Socket> held = new CopyOnWriteArrayList<>();
        private final Thread acceptor = Thread.ofVirtual().start(this::serve);

        SilentListener() throws IOException {
        }

        EndpointInfo endpoint() {
            return new EndpointInfo(server.getInetAddress().getHostAddress(), server.getLocalPort());
        }

        int accepted() {
            return held.size();
        }

        private void serve() {
            while (!server.isClosed()) {
                try {
                    held.add(server.accept());
                } catch (IOException expected) {
                    // Closing the server socket ends the loop.
                }
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
            for (Socket socket : held) {
                socket.close();
            }
            acceptor.join(Duration.ofSeconds(5));
        }
    }

    @FunctionalInterface
    private interface Answers {
        boolean on(int connectionNumber);
    }

    /** A loopback listener that either answers like a broker or closes every connection it accepts. */
    private static final class FakeListener implements AutoCloseable {

        private final ServerSocket server;
        private final AtomicInteger accepted = new AtomicInteger();
        private final Thread acceptor;

        private FakeListener(ServerSocket server, Answers answers) {
            this.server = server;
            this.acceptor = Thread.ofVirtual().start(() -> serve(answers));
        }

        static FakeListener plain(Answers answers) throws IOException {
            return new FakeListener(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()), answers);
        }

        static FakeListener tls(TlsBundle tls) throws Exception {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(new ByteArrayInputStream(tls.pkcs12Bytes()),
                    ArtemisTlsGenerator.KEYSTORE_PASSWORD.toCharArray());
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(keyStore, ArtemisTlsGenerator.KEYSTORE_PASSWORD.toCharArray());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory()
                    .createServerSocket(0, 50, InetAddress.getLoopbackAddress());
            return new FakeListener(server, connectionNumber -> true);
        }

        EndpointInfo endpoint() {
            return new EndpointInfo(server.getInetAddress().getHostAddress(), server.getLocalPort());
        }

        int accepted() {
            return accepted.get();
        }

        private void serve(Answers answers) {
            while (!server.isClosed()) {
                try (Socket connection = server.accept()) {
                    int number = accepted.incrementAndGet();
                    if (!answers.on(number)) {
                        continue;
                    }
                    if (connection instanceof SSLSocket tls) {
                        tls.startHandshake();
                        continue;
                    }
                    InputStream in = connection.getInputStream();
                    if (in.readNBytes(AMQP_SASL_HEADER.length).length == AMQP_SASL_HEADER.length) {
                        connection.getOutputStream().write(AMQP_SASL_HEADER);
                        connection.getOutputStream().flush();
                    }
                } catch (IOException expected) {
                    // A closed server socket ends the loop; a failed handshake is what one test wants.
                }
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
            acceptor.join(Duration.ofSeconds(5));
        }
    }
}
