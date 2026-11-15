package io.floci.az.core.tls;

import io.floci.az.config.EmulatorConfig;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TlsProxyServer}'s multi-port binding and first-byte protocol detection.
 *
 * <p>Uses ephemeral ports for the public port, the HTTPS port, and the stub HTTP/HTTPS
 * backends, so nothing privileged (443) is bound and the test never collides with a running
 * floci-az. The stub backends simply emit a marker on connect; the proxy pipes it back, letting
 * the test assert which backend a given first byte was routed to.
 */
class TlsProxyServerTest {

    /** TLS ClientHello content type — the first byte the proxy keys protocol detection on. */
    private static final byte TLS_HANDSHAKE = 0x16;

    private Vertx vertx;
    private TlsProxyServer proxy;
    private NetServer httpBackend;
    private NetServer httpsBackend;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (proxy != null) {
            proxy.stop();
        }
        closeBackend(httpBackend);
        closeBackend(httpsBackend);
        vertx.close();
    }

    private static EmulatorConfig configWith(boolean tlsEnabled, int publicPort, int httpsPort) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        when(config.port()).thenReturn(publicPort);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(tlsEnabled);
        when(tls.httpsPort()).thenReturn(httpsPort);
        return config;
    }

    @Test
    void listenPorts_includesPublicAndHttpsPort() {
        proxy = new TlsProxyServer(vertx, configWith(false, 4577, 443), 4570, 4571);
        assertEquals(Set.of(4577, 443), proxy.listenPorts());
    }

    @Test
    void listenPorts_dropsHttpsPortWhenZero() {
        proxy = new TlsProxyServer(vertx, configWith(false, 4577, 0), 4570, 4571);
        assertEquals(Set.of(4577), proxy.listenPorts());
    }

    @Test
    void listenPorts_dedupesWhenHttpsPortEqualsPublic() {
        proxy = new TlsProxyServer(vertx, configWith(false, 4577, 4577), 4570, 4571);
        assertEquals(Set.of(4577), proxy.listenPorts());
    }

    @Test
    @Timeout(20)
    void bindsBothPorts_andRoutesByFirstByte() throws Exception {
        int httpBe = freePort();
        int httpsBe = freePort();
        int publicPort = freePort();
        int httpsPort = freePort();
        httpBackend = startMarkerBackend(httpBe, "PLAIN");
        httpsBackend = startMarkerBackend(httpsBe, "TLS");

        proxy = new TlsProxyServer(vertx, configWith(true, publicPort, httpsPort), httpBe, httpsBe);

        // Public port: TLS ClientHello → HTTPS backend; anything else → HTTP backend.
        assertEquals("TLS", roundTrip(publicPort, TLS_HANDSHAKE));
        assertEquals("PLAIN", roundTrip(publicPort, (byte) 'G'));
        // HTTPS port: same protocol detection as the public port.
        assertEquals("TLS", roundTrip(httpsPort, TLS_HANDSHAKE));
        assertEquals("PLAIN", roundTrip(httpsPort, (byte) 'G'));
    }

    @Test
    @Timeout(20)
    void tlsDisabled_bindsNothing() throws Exception {
        int publicPort = freePort();
        proxy = new TlsProxyServer(vertx, configWith(false, publicPort, 443), 4570, 4571);
        assertFalse(portAccepts(publicPort), "no proxy should listen when TLS is disabled");
    }

    @Test
    @Timeout(30)
    void aClientRejectingTheCertificateUnderTls13IsReported() throws Exception {
        CompletableFuture<TlsHandshakeObserver.Rejection> reported = proxyToTlsEcho();

        assertThrows(SSLHandshakeException.class, () -> handshake(SSLContext.getDefault(), "TLSv1.3"));

        TlsHandshakeObserver.Rejection rejection = reported.get(10, TimeUnit.SECONDS);
        assertNull(rejection.alert(), "TLS 1.3 encrypts the alert");
    }

    @Test
    @Timeout(30)
    void aClientRejectingTheCertificateUnderTls12IsReportedWithTheAlert() throws Exception {
        CompletableFuture<TlsHandshakeObserver.Rejection> reported = proxyToTlsEcho();

        assertThrows(SSLHandshakeException.class, () -> handshake(SSLContext.getDefault(), "TLSv1.2"));

        assertEquals("certificate_unknown", reported.get(10, TimeUnit.SECONDS).alert());
    }

    @Test
    @Timeout(30)
    void aClientThatTrustsTheCertificateIsNotReportedAndItsTrafficPassesUnchanged() throws Exception {
        CompletableFuture<TlsHandshakeObserver.Rejection> reported = proxyToTlsEcho();
        SSLContext trusting = trusting(tlsBackendCertificate.caPem());

        for (String protocol : List.of("TLSv1.3", "TLSv1.2")) {
            try (SSLSocket socket = handshake(trusting, protocol)) {
                socket.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                byte[] echoed = socket.getInputStream().readNBytes(4);
                assertEquals("ping", new String(echoed, StandardCharsets.UTF_8), protocol);
            }
        }

        assertThrows(TimeoutException.class, () -> reported.get(1, TimeUnit.SECONDS));
    }

    // ---- helpers ----

    private CertificateGenerator.GeneratedCertificate tlsBackendCertificate;
    private int tlsProxyPort;

    /** A TLS echo server behind the proxy; the future completes with the first rejection reported. */
    private CompletableFuture<TlsHandshakeObserver.Rejection> proxyToTlsEcho() throws Exception {
        tlsBackendCertificate = new CertificateGenerator().generateCertificate(List.of("localhost", "127.0.0.1"));
        int httpsBe = freePort();
        httpsBackend = vertx.createNetServer(new NetServerOptions().setSsl(true).setKeyCertOptions(new PemKeyCertOptions()
                .setCertValue(Buffer.buffer(tlsBackendCertificate.chainPem()))
                .setKeyValue(Buffer.buffer(tlsBackendCertificate.privateKeyPem()))));
        httpsBackend.connectHandler(sock -> sock.handler(sock::write));
        httpsBackend.listen(httpsBe, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        CompletableFuture<TlsHandshakeObserver.Rejection> reported = new CompletableFuture<>();
        tlsProxyPort = freePort();
        proxy = new TlsProxyServer(vertx, configWith(true, tlsProxyPort, 0), freePort(), httpsBe,
                (address, rejection) -> reported.complete(rejection));
        awaitListening(tlsProxyPort);
        return reported;
    }

    private SSLSocket handshake(SSLContext context, String protocol) throws IOException {
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", tlsProxyPort);
        socket.setEnabledProtocols(new String[] {protocol});
        socket.setSoTimeout(5_000);
        try {
            socket.startHandshake();
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        return socket;
    }

    private static SSLContext trusting(String caPem) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("floci-az", CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(caPem.getBytes(StandardCharsets.UTF_8))));
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    private NetServer startMarkerBackend(int port, String marker) throws Exception {
        NetServer server = vertx.createNetServer();
        server.connectHandler(sock -> sock.write(marker));
        CompletableFuture<Void> started = new CompletableFuture<>();
        server.listen(port, "127.0.0.1").onComplete(ar -> {
            if (ar.succeeded()) {
                started.complete(null);
            } else {
                started.completeExceptionally(ar.cause());
            }
        });
        started.get(5, TimeUnit.SECONDS);
        return server;
    }

    private String roundTrip(int proxyPort, byte firstByte) throws Exception {
        awaitListening(proxyPort);
        try (Socket sock = new Socket("127.0.0.1", proxyPort)) {
            sock.getOutputStream().write(new byte[]{firstByte});
            sock.getOutputStream().flush();
            byte[] buf = new byte[64];
            int n = sock.getInputStream().read(buf);
            assertTrue(n > 0, "expected a marker response from the backend");
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        }
    }

    private void awaitListening(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        fail("port " + port + " not listening within timeout");
    }

    private boolean portAccepts(int port) throws Exception {
        // Give any (unexpected) async bind a brief chance to come up, then probe once.
        Thread.sleep(500);
        try (Socket s = new Socket("127.0.0.1", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void closeBackend(NetServer server) {
        if (server != null) {
            server.close();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
