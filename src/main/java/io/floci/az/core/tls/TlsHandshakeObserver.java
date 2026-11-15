package io.floci.az.core.tls;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Optional;

/**
 * Watches the opening TLS records of one proxied connection and tells whether the client gave up
 * on the handshake, which almost always means it does not trust floci-az's certificate. Quarkus
 * terminates TLS behind the proxy and reports no handshake failure anywhere, so the bytes the proxy
 * already relays are the only place to see one.
 *
 * <p>Two shapes, both read straight off the record layer (RFC 8446 section 5, RFC 5246 section 6.2):
 * <ul>
 *   <li>A plaintext alert (content type 21, length 2) before the client sends any application
 *       data record. OpenSSL (Python, {@code openssl s_client}) sends this even under TLS 1.3, and
 *       under TLS 1.2 every client does, since its alert comes before ChangeCipherSpec. The
 *       description names the reason, such as {@code unknown_ca}.</li>
 *   <li>Under TLS 1.3, which the server's ServerHello announces, a first encrypted client record too
 *       short to hold a Finished message: the client's flight is then an encrypted alert. Go (and so
 *       the azurerm provider), Java and curl on macOS send this, and TLS 1.3 hides the reason.</li>
 * </ul>
 *
 * <p>Only the first few kilobytes of a connection are looked at. Inspection stops at the first
 * application data record, at any alert, or when the cap is reached, and the observer then costs one
 * volatile read per chunk.
 */
final class TlsHandshakeObserver {

    /** A client's rejection: the certificate alert it sent, or {@code null} when TLS 1.3 encrypted it. */
    record Rejection(String alert) {

        /** The reason for the log: the alert name, or what is known when it was encrypted. */
        String reason() {
            return alert != null ? alert : "TLS 1.3 hides the alert";
        }
    }

    private static final int CHANGE_CIPHER_SPEC = 20;
    private static final int HANDSHAKE = 22;
    private static final int ALERT = 21;
    private static final int APPLICATION_DATA = 23;
    private static final int SERVER_HELLO = 2;
    private static final int SUPPORTED_VERSIONS = 43;
    private static final int TLS_1_3 = 0x0304;
    private static final int RECORD_HEADER = 5;

    /**
     * The smallest encrypted record that can carry a TLS 1.3 Finished: a 4-byte handshake header and
     * a 32-byte verify_data (SHA-256), the inner content type byte, and a 16-byte AEAD tag. An alert
     * is 2 bytes plus the same overhead (19), and padded alerts stay below this too (Java sends 35).
     */
    static final int SMALLEST_TLS13_FINISHED = 4 + 32 + 1 + 16;

    /** The handful of records before the client's Finished fit well inside this, ClientHello included. */
    private static final int INSPECTION_LIMIT = 32 * 1024;

    /** The alerts a client sends when it rejects the server's certificate (RFC 8446 section 6). */
    private static final Map<Integer, String> CERTIFICATE_ALERTS = Map.of(
            42, "bad_certificate",
            43, "unsupported_certificate",
            44, "certificate_revoked",
            45, "certificate_expired",
            46, "certificate_unknown",
            48, "unknown_ca");

    private final ByteArrayOutputStream fromClient = new ByteArrayOutputStream();
    private final ByteArrayOutputStream fromServer = new ByteArrayOutputStream();
    private volatile boolean done;
    private Boolean tls13;
    private Rejection rejection;

    /** Feeds bytes the client sent; returns the rejection the first time one is seen. */
    synchronized Optional<Rejection> client(byte[] bytes) {
        if (done) {
            return Optional.empty();
        }
        append(fromClient, bytes);
        inspectClient();
        return Optional.ofNullable(rejection);
    }

    /** Feeds bytes the server sent, which tell whether TLS 1.3 was negotiated. */
    synchronized void server(byte[] bytes) {
        if (done || tls13 != null) {
            return;
        }
        append(fromServer, bytes);
        tls13 = negotiatedTls13(fromServer.toByteArray());
        if (fromServer.size() >= INSPECTION_LIMIT && tls13 == null) {
            tls13 = false;
        }
    }

    boolean done() {
        return done;
    }

    private void inspectClient() {
        byte[] stream = fromClient.toByteArray();
        int offset = 0;
        while (offset + RECORD_HEADER <= stream.length) {
            int type = stream[offset] & 0xFF;
            int length = ((stream[offset + 3] & 0xFF) << 8) | (stream[offset + 4] & 0xFF);
            if (type == APPLICATION_DATA) {
                // The client's first encrypted record: a Finished, or an alert in its place.
                if (Boolean.TRUE.equals(tls13) && length < SMALLEST_TLS13_FINISHED) {
                    rejection = new Rejection(null);
                }
                done = true;
                return;
            }
            if (type == ALERT) {
                if (length == 2 && offset + RECORD_HEADER + 2 <= stream.length) {
                    String name = CERTIFICATE_ALERTS.get(stream[offset + RECORD_HEADER + 1] & 0xFF);
                    if (name != null) {
                        rejection = new Rejection(name);
                    }
                } else if (length == 2) {
                    return; // the alert body has not arrived yet
                }
                done = true;
                return;
            }
            if (type != HANDSHAKE && type != CHANGE_CIPHER_SPEC) {
                done = true; // not a TLS stream after all
                return;
            }
            offset += RECORD_HEADER + length;
        }
        if (stream.length >= INSPECTION_LIMIT) {
            done = true;
        }
    }

    /**
     * Whether the ServerHello at the start of {@code stream} selects TLS 1.3, or {@code null} while
     * it is incomplete. TLS 1.3 is announced only in the supported_versions extension; the version
     * fields themselves say TLS 1.2 for compatibility.
     */
    static Boolean negotiatedTls13(byte[] stream) {
        if (stream.length > 0 && (stream[0] & 0xFF) != HANDSHAKE) {
            return false;
        }
        if (stream.length > RECORD_HEADER && (stream[RECORD_HEADER] & 0xFF) != SERVER_HELLO) {
            return false;
        }
        if (stream.length < RECORD_HEADER + 4) {
            return null;
        }
        int bodyLength = u24(stream, RECORD_HEADER + 1);
        int body = RECORD_HEADER + 4;
        int end = body + bodyLength;
        if (stream.length < end) {
            return null;
        }
        int cursor = body + 2 + 32;                       // legacy_version, random
        if (cursor >= end) {
            return false;
        }
        cursor += 1 + (stream[cursor] & 0xFF);            // legacy_session_id_echo
        cursor += 2 + 1;                                  // cipher_suite, legacy_compression_method
        if (cursor + 2 > end) {
            return false;                                 // no extensions: TLS 1.2 or older
        }
        int extensionsEnd = Math.min(end, cursor + 2 + u16(stream, cursor));
        cursor += 2;
        while (cursor + 4 <= extensionsEnd) {
            int extension = u16(stream, cursor);
            int length = u16(stream, cursor + 2);
            if (extension == SUPPORTED_VERSIONS && length == 2 && cursor + 6 <= extensionsEnd) {
                return u16(stream, cursor + 4) == TLS_1_3;
            }
            cursor += 4 + length;
        }
        return false;
    }

    private static int u16(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
    }

    private static int u24(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 16) | u16(bytes, at + 1);
    }

    private static void append(ByteArrayOutputStream stream, byte[] bytes) {
        int room = INSPECTION_LIMIT - stream.size();
        if (room > 0) {
            stream.write(bytes, 0, Math.min(room, bytes.length));
        }
    }
}
