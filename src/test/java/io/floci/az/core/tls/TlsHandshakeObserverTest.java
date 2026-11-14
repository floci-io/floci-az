package io.floci.az.core.tls;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record sequences real clients sent through the proxy (captured with a relay in front of a TLS
 * floci-az): what a client that rejects the certificate looks like, and what a trusting one does.
 */
class TlsHandshakeObserverTest {

    private static final int CLIENT_HELLO_LENGTH = 1466;

    @Test
    void anOpensslClientsPlaintextUnknownCaAlertIsARejectionWithItsReason() {
        TlsHandshakeObserver observer = new TlsHandshakeObserver();
        observer.server(serverHello(true));

        Optional<TlsHandshakeObserver.Rejection> rejection =
                observer.client(concat(clientHello(), record(21, new byte[] {2, 48})));

        assertEquals("unknown_ca", rejection.orElseThrow().alert());
        assertTrue(observer.done());
    }

    @Test
    void aGoClientsEncryptedAlertUnderTls13IsARejection() {
        TlsHandshakeObserver observer = new TlsHandshakeObserver();
        observer.client(clientHello());
        observer.server(serverHello(true));

        Optional<TlsHandshakeObserver.Rejection> rejection =
                observer.client(concat(record(20, new byte[] {1}), record(23, new byte[19])));

        assertNull(rejection.orElseThrow().alert());
        assertEquals("TLS 1.3 hides the alert", rejection.orElseThrow().reason());
    }

    @Test
    void javasPaddedEncryptedAlertIsARejectionToo() {
        TlsHandshakeObserver observer = new TlsHandshakeObserver();
        observer.client(clientHello());
        observer.server(serverHello(true));

        assertTrue(observer.client(concat(record(20, new byte[] {1}), record(23, new byte[35]))).isPresent());
    }

    @Test
    void aTrustingTls13ClientsFinishedIsNotARejection() {
        TlsHandshakeObserver observer = new TlsHandshakeObserver();
        observer.client(clientHello());
        observer.server(serverHello(true));

        Optional<TlsHandshakeObserver.Rejection> rejection = observer.client(
                concat(record(20, new byte[] {1}), record(23, new byte[TlsHandshakeObserver.SMALLEST_TLS13_FINISHED])));

        assertFalse(rejection.isPresent());
        assertTrue(observer.done(), "inspection stops at the first encrypted record");
        assertFalse(observer.client(record(23, new byte[19])).isPresent(), "a later close_notify is ignored");
    }

    @Test
    void aShortTls12ApplicationDataRecordIsNotARejection() {
        // openssl s_client -tls1_2 sending one byte: 25 bytes of application data after the handshake.
        TlsHandshakeObserver observer = new TlsHandshakeObserver();
        observer.client(clientHello());
        observer.server(serverHello(false));

        Optional<TlsHandshakeObserver.Rejection> rejection = observer.client(concat(record(22, new byte[37]),
                record(20, new byte[] {1}), record(22, new byte[40]), record(23, new byte[25])));

        assertFalse(rejection.isPresent());
    }

    @Test
    void anAlertThatIsNotAboutTheCertificateIsNotReported() {
        TlsHandshakeObserver observer = new TlsHandshakeObserver();

        assertFalse(observer.client(concat(clientHello(), record(21, new byte[] {2, 40}))).isPresent(),
                "handshake_failure means no common cipher or version, not an untrusted CA");
        assertTrue(observer.done());
    }

    @Test
    void recordsSplitAcrossChunksAreReassembled() {
        TlsHandshakeObserver observer = new TlsHandshakeObserver();
        observer.server(serverHello(true));
        byte[] stream = concat(clientHello(), record(21, new byte[] {2, 46}));

        Optional<TlsHandshakeObserver.Rejection> rejection = Optional.empty();
        for (byte b : stream) {
            rejection = observer.client(new byte[] {b});
            if (rejection.isPresent()) {
                break;
            }
        }

        assertEquals("certificate_unknown", rejection.orElseThrow().alert());
    }

    @Test
    void theNegotiatedVersionComesFromSupportedVersionsAndWaitsForTheWholeServerHello() {
        byte[] hello13 = serverHello(true);
        byte[] partial = new byte[hello13.length - 3];
        System.arraycopy(hello13, 0, partial, 0, partial.length);

        assertEquals(Boolean.TRUE, TlsHandshakeObserver.negotiatedTls13(hello13));
        assertEquals(Boolean.FALSE, TlsHandshakeObserver.negotiatedTls13(serverHello(false)));
        assertNull(TlsHandshakeObserver.negotiatedTls13(partial));
        assertEquals(Boolean.FALSE, TlsHandshakeObserver.negotiatedTls13(record(21, new byte[] {2, 40})));
    }

    @Test
    void aStreamThatIsNotTlsEndsInspection() {
        TlsHandshakeObserver observer = new TlsHandshakeObserver();

        observer.client(concat(clientHello(), "GET / HTTP/1.1\r\n".getBytes()));

        assertTrue(observer.done());
    }

    private static byte[] clientHello() {
        byte[] body = new byte[CLIENT_HELLO_LENGTH];
        body[0] = 1;
        return record(22, body);
    }

    /** A ServerHello whose extensions select TLS 1.3 through supported_versions, or carry none of it. */
    private static byte[] serverHello(boolean tls13) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x03);
        body.write(0x03);
        body.writeBytes(new byte[32]);                    // random
        body.write(32);
        body.writeBytes(new byte[32]);                    // legacy_session_id_echo
        body.write(0x13);
        body.write(0x01);                                 // TLS_AES_128_GCM_SHA256
        body.write(0);                                    // compression
        ByteArrayOutputStream extensions = new ByteArrayOutputStream();
        extensions.writeBytes(new byte[] {0x00, 0x33, 0x00, 0x04, 0x00, 0x1d, 0x00, 0x00}); // key_share stub
        if (tls13) {
            extensions.writeBytes(new byte[] {0x00, 0x2b, 0x00, 0x02, 0x03, 0x04});
        } else {
            extensions.writeBytes(new byte[] {(byte) 0xff, 0x01, 0x00, 0x01, 0x00});          // renegotiation_info
        }
        body.write(extensions.size() >> 8);
        body.write(extensions.size());
        body.writeBytes(extensions.toByteArray());
        byte[] hello = body.toByteArray();
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        handshake.write(2);
        handshake.write(hello.length >> 16);
        handshake.write(hello.length >> 8);
        handshake.write(hello.length);
        handshake.writeBytes(hello);
        return record(22, handshake.toByteArray());
    }

    private static byte[] record(int type, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type);
        out.write(0x03);
        out.write(0x03);
        out.write(body.length >> 8);
        out.write(body.length);
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
