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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;

class ArtemisReadinessProbeTest {

    private static final byte[] AMQP_SASL_HEADER = {'A', 'M', 'Q', 'P', 3, 1, 0, 0};
    /** The production timing scaled down tenfold, so deadline tests wait milliseconds rather than seconds. */
    private static final ArtemisReadinessProbe.Timing FAST = new ArtemisReadinessProbe.Timing(100L, 50L);

    @ParameterizedTest
    @MethodSource("unresponsiveEndpoints")
    void unresponsiveEndpointReachesReadinessDeadline(String host, int timeoutMillis) {
        FakeTicker ticker = new FakeTicker();
        List<Integer> connectTimeouts = new ArrayList<>();
        try (MockedConstruction<Socket> sockets = unresponsiveSockets(ticker, connectTimeouts)) {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> ArtemisReadinessProbe.awaitAmqp(
                    new EndpointInfo(host, 5672), Duration.ofMillis(timeoutMillis), FAST, ticker));
            assertTrue(failure.getMessage().contains("within " + timeoutMillis + "ms"), failure.getMessage());
            for (int connectTimeout : connectTimeouts) {
                assertTrue(connectTimeout > 0, "connect must have a finite deadline");
                assertTrue(connectTimeout <= FAST.attemptMillis(), "connect must stay within its attempt budget");
            }
            assertEquals(timeoutMillis, ticker.elapsedMillis(), "the probe must stop at its deadline");
        }
    }

    static Stream<Arguments> unresponsiveEndpoints() {
        return Stream.of(Arguments.of(null, 200), Arguments.of("192.0.2.1", 200),
                Arguments.of("192.0.2.1", 400));
    }

    @Test
    void theSecondsApiKeepsItsOneSecondAttemptBudget() {
        // A 2 s budget leaves room above one second, so a larger or smaller attempt budget shows in the
        // first connect's timeout. The connect stops the probe at once instead of waiting it out.
        List<Integer> connectTimeouts = new CopyOnWriteArrayList<>();
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class, (socket, context) ->
                doAnswer(invocation -> {
                    connectTimeouts.add(invocation.getArgument(1));
                    throw new IllegalStateException("stop after the first connect");
                }).when(socket).connect(any(), anyInt()))) {
            assertThrows(IllegalStateException.class, () ->
                    ArtemisReadinessProbe.awaitAmqp(new EndpointInfo("192.0.2.1", 5672), 2));
            assertEquals(List.of(1_000), connectTimeouts);
        }
    }

    /** Sockets whose connect records its timeout and then waits it out on {@code ticker} before failing. */
    private static MockedConstruction<Socket> unresponsiveSockets(FakeTicker ticker, List<Integer> connectTimeouts) {
        return mockConstruction(Socket.class, (socket, context) ->
                doAnswer(invocation -> {
                    int timeoutMillis = invocation.getArgument(1);
                    connectTimeouts.add(timeoutMillis);
                    ticker.advanceMillis(timeoutMillis);
                    throw new SocketTimeoutException("endpoint no longer replies");
                }).when(socket).connect(any(), anyInt()));
    }

    @Test
    void quickFailureRetriesWithoutSleepingPastRemainingBudget() {
        // The first connect fails at once, the pause takes 200 of the 300 ms, and the second connect waits out
        // the remaining 100 ms. The pause after it must be clamped to the empty remainder, not the full 200 ms.
        ArtemisReadinessProbe.Timing longPause = new ArtemisReadinessProbe.Timing(1_000L, 200L);
        FakeTicker ticker = new FakeTicker();
        List<Integer> connectTimeouts = new ArrayList<>();
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class, (socket, context) ->
                doAnswer(invocation -> {
                    int timeoutMillis = invocation.getArgument(1);
                    connectTimeouts.add(timeoutMillis);
                    if (context.getCount() > 1) {
                        ticker.advanceMillis(timeoutMillis);
                    }
                    throw new SocketTimeoutException("endpoint no longer replies");
                }).when(socket).connect(any(), anyInt()))) {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> ArtemisReadinessProbe.awaitAmqp(
                    new EndpointInfo("192.0.2.1", 5672), Duration.ofMillis(300), longPause, ticker));
            assertEquals(List.of(300, 100), connectTimeouts, "an immediate failure must retry within the deadline");
            assertEquals(List.of(200L, 0L), ticker.sleeps(), "no pause may outlast the remaining budget");
            assertEquals(300L, ticker.elapsedMillis());
            assertTrue(failure.getMessage().contains("within 300ms"), failure.getMessage());
            assertTrue(failure.getCause() instanceof SocketTimeoutException, "the last failure must be the cause");
        }
    }

    @Test
    void aPortThatAcceptsAndClosesIsNotReady() throws Exception {
        // What Docker Desktop's port forwarder does while nothing in the container listens yet.
        try (FakeListener listener = FakeListener.plain(connectionNumber -> false)) {
            FakeTicker ticker = new FakeTicker();
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                    ArtemisReadinessProbe.awaitAmqp(listener.endpoint(), Duration.ofMillis(300), FAST, ticker));
            assertTrue(failure.getMessage().contains("within 300ms"), failure.getMessage());
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
            assertDoesNotThrow(() -> ArtemisReadinessProbe.awaitAmqp(
                    listener.endpoint(), Duration.ofSeconds(10), FAST, new FakeTicker()));
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
            assertThrows(RuntimeException.class, () -> ArtemisReadinessProbe.awaitAmqps(
                    listener.endpoint(), expected.certPem(), Duration.ofMillis(300), FAST, new FakeTicker()));
        }
    }

    @Test
    void aPeerThatAcceptsButNeverAnswersTheProtocolHeaderReachesTheDeadline() throws Exception {
        try (SilentListener listener = new SilentListener()) {
            RuntimeException failure = assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThrows(RuntimeException.class, () ->
                            ArtemisReadinessProbe.awaitAmqp(listener.endpoint(), Duration.ofMillis(400), FAST)));
            assertTrue(failure.getMessage().contains("within 400ms"));
            assertTrue(listener.accepted() >= 1, "the probe must have connected");
        }
    }

    @Test
    void aPeerThatAcceptsButNeverAnswersTheTlsHandshakeReachesTheDeadline() throws Exception {
        TlsBundle tls = new ArtemisTlsGenerator().generate();
        try (SilentListener listener = new SilentListener()) {
            RuntimeException failure = assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThrows(RuntimeException.class, () -> ArtemisReadinessProbe.awaitAmqps(
                            listener.endpoint(), tls.certPem(), Duration.ofMillis(400), FAST)));
            assertTrue(failure.getMessage().contains("within 400ms"));
            assertTrue(listener.accepted() >= 1, "the probe must have connected");
        }
    }

    /**
     * Virtual time for the probe: it moves only when the probe pauses or a mocked socket advances it, so a
     * deadline test asserts exact budgets however slow the machine running it is.
     */
    private static final class FakeTicker implements ArtemisReadinessProbe.Ticker {

        private final List<Long> sleeps = new ArrayList<>();
        private long nanos;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
            advanceMillis(millis);
        }

        void advanceMillis(long millis) {
            nanos += TimeUnit.MILLISECONDS.toNanos(millis);
        }

        long elapsedMillis() {
            return TimeUnit.NANOSECONDS.toMillis(nanos);
        }

        List<Long> sleeps() {
            return List.copyOf(sleeps);
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
