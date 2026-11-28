package io.floci.az.services.servicebus;

import io.floci.az.core.docker.ContainerLifecycleManager.EndpointInfo;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Waits until an Artemis sidecar's AMQP acceptors answer, not merely until their ports accept a TCP
 * connection.
 *
 * <p>A published Docker port can accept a connection before anything inside the container listens on
 * it: Docker Desktop's port forwarder completes the handshake itself and closes the connection once it
 * finds no listener. A TCP-only check therefore reports a starting broker as ready, and a client that
 * connects in that window sees {@code Transport connection remotely closed}. Each probe here needs a
 * reply only the broker can give: the AMQP protocol header on the plain port, and a TLS handshake
 * signed by the namespace's own certificate on the TLS port.
 */
public final class ArtemisReadinessProbe {

    /** The SASL layer's protocol header; Artemis answers it with the same eight bytes. */
    private static final byte[] AMQP_SASL_HEADER = {'A', 'M', 'Q', 'P', 3, 1, 0, 0};
    /** One second per connect or read, then half a second before the next attempt. */
    static final Timing DEFAULT_TIMING = new Timing(1_000L, 500L);

    /** How long one attempt may take and how long to pause after a failed one, in milliseconds. */
    record Timing(long attemptMillis, long retryPauseMillis) {
    }

    /** The clock and the pause the probe uses, so tests can run the deadline logic on virtual time. */
    interface Ticker {
        /** The real monotonic clock and {@link Thread#sleep(long)}. */
        Ticker SYSTEM = new Ticker() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public void sleep(long millis) throws InterruptedException {
                Thread.sleep(millis);
            }
        };

        long nanoTime();

        void sleep(long millis) throws InterruptedException;
    }

    private ArtemisReadinessProbe() {
    }

    /** Waits until the plain AMQP acceptor at {@code endpoint} answers the AMQP protocol header. */
    public static void awaitAmqp(EndpointInfo endpoint, int timeoutSeconds) {
        awaitAmqp(endpoint, Duration.ofSeconds(timeoutSeconds), DEFAULT_TIMING);
    }

    /** {@link #awaitAmqp(EndpointInfo, int)} with a sub-second budget, so tests need not wait out whole seconds. */
    static void awaitAmqp(EndpointInfo endpoint, Duration timeout, Timing timing) {
        awaitAmqp(endpoint, timeout, timing, Ticker.SYSTEM);
    }

    /** {@link #awaitAmqp(EndpointInfo, Duration, Timing)} measuring the budget with {@code ticker}. */
    static void awaitAmqp(EndpointInfo endpoint, Duration timeout, Timing timing, Ticker ticker) {
        await(endpoint, "AMQP", timeout, timing, ticker, ArtemisReadinessProbe::exchangeProtocolHeader);
    }

    /**
     * Waits until the TLS acceptor at {@code endpoint} completes a handshake with the certificate in
     * {@code certPem}, the one floci-az generated for this broker.
     */
    public static void awaitAmqps(EndpointInfo endpoint, String certPem, int timeoutSeconds) {
        awaitAmqps(endpoint, certPem, Duration.ofSeconds(timeoutSeconds), DEFAULT_TIMING);
    }

    /** {@link #awaitAmqps(EndpointInfo, String, int)} with a sub-second budget, for tests. */
    static void awaitAmqps(EndpointInfo endpoint, String certPem, Duration timeout, Timing timing) {
        awaitAmqps(endpoint, certPem, timeout, timing, Ticker.SYSTEM);
    }

    /** {@link #awaitAmqps(EndpointInfo, String, Duration, Timing)} measuring the budget with {@code ticker}. */
    static void awaitAmqps(EndpointInfo endpoint, String certPem, Duration timeout, Timing timing, Ticker ticker) {
        SSLSocketFactory factory = trusting(certPem);
        await(endpoint, "AMQPS", timeout, timing, ticker, socket -> {
            try (SSLSocket tls = (SSLSocket) factory.createSocket(
                    socket, endpoint.host(), endpoint.port(), false)) {
                tls.startHandshake();
            }
        });
    }

    @FunctionalInterface
    private interface Exchange {
        void run(Socket connected) throws IOException;
    }

    private static void await(EndpointInfo endpoint, String label, Duration timeout, Timing timing,
                              Ticker ticker, Exchange exchange) {
        long deadline = ticker.nanoTime() + timeout.toNanos();
        IOException lastFailure = null;
        while (ticker.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getByName(endpoint.host()), endpoint.port()),
                        attemptMillis(deadline, timing, ticker));
                socket.setSoTimeout(attemptMillis(deadline, timing, ticker));
                exchange.run(socket);
                return;
            } catch (IOException e) {
                // A starting or terminated sidecar is expected to fail until the readiness deadline.
                lastFailure = e;
            }
            try {
                ticker.sleep(Math.min(timing.retryPauseMillis(), remainingMillis(deadline, ticker)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for Artemis " + label + " on " + endpoint, e);
            }
        }
        throw new RuntimeException("Artemis did not answer " + label + " on " + endpoint
                + " within " + describe(timeout), lastFailure);
    }

    private static void exchangeProtocolHeader(Socket socket) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(AMQP_SASL_HEADER);
        out.flush();
        byte[] reply = socket.getInputStream().readNBytes(AMQP_SASL_HEADER.length);
        if (!Arrays.equals(reply, AMQP_SASL_HEADER)) {
            throw new IOException("no AMQP protocol header in the reply (" + reply.length + " bytes)");
        }
    }

    private static int attemptMillis(long deadline, Timing timing, Ticker ticker) {
        return Math.toIntExact(Math.max(1L, Math.min(timing.attemptMillis(), remainingMillis(deadline, ticker))));
    }

    /** Whole seconds as {@code 5s}, as the message read before sub-second budgets existed; otherwise {@code 300ms}. */
    private static String describe(Duration timeout) {
        return timeout.toMillisPart() == 0 ? timeout.toSeconds() + "s" : timeout.toMillis() + "ms";
    }

    private static long remainingMillis(long deadline, Ticker ticker) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadline - ticker.nanoTime()));
    }

    private static SSLSocketFactory trusting(String certPem) {
        try {
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(certPem.getBytes(StandardCharsets.US_ASCII)));
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("artemis", certificate);
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Cannot trust the generated Artemis certificate", e);
        }
    }
}
