package io.floci.az.core.dns;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedDnsServerTest {

    private EmbeddedDnsServer dns;

    @BeforeEach
    void setUp() {
        dns = new EmbeddedDnsServer(List.of("myapp.internal"));
    }

    // ── matchesSuffix — configured suffix ────────────────────────────────────

    @Test
    void matchesSuffix_exactMatch() {
        assertTrue(dns.matchesSuffix("myapp.internal"));
    }

    @Test
    void matchesSuffix_singleSubdomain() {
        assertTrue(dns.matchesSuffix("mystorageaccount.myapp.internal"));
    }

    @Test
    void matchesSuffix_deeplyNested() {
        assertTrue(dns.matchesSuffix("deeply.nested.host.myapp.internal"));
    }

    @Test
    void matchesSuffix_caseInsensitive() {
        assertTrue(dns.matchesSuffix("MyAccount.MyApp.INTERNAL"));
    }

    @Test
    void matchesSuffix_noMatch() {
        assertFalse(dns.matchesSuffix("mystorageaccount.blob.core.windows.net"));
    }

    @Test
    void matchesSuffix_partialSuffixNoMatch() {
        assertFalse(dns.matchesSuffix("internal"));
    }

    @Test
    void matchesSuffix_nullAndEmpty() {
        assertFalse(dns.matchesSuffix(null));
        assertFalse(dns.matchesSuffix(""));
    }

    // ── matchesSuffix — built-in emulator domain ──────────────────────────────

    @Test
    void matchesSuffix_localhostFlociIo_exact() {
        assertTrue(dns.matchesSuffix("localhost.floci.io"));
    }

    @Test
    void matchesSuffix_localhostFlociIo_subdomain() {
        assertTrue(dns.matchesSuffix("mystorageaccount.localhost.floci.io"));
    }

    // bare *.floci.io (without localhost.) must NOT match — only *.localhost.floci.io is registered
    @Test
    void matchesSuffix_bareFlociIoSubdomainNoMatch() {
        assertFalse(dns.matchesSuffix("mystorageaccount.floci.io"));
    }

    // ── resolveARecord ────────────────────────────────────────────────────────

    @Test
    void resolveARecord_suffixMatchResolvesToOwnIp() {
        assertEquals("172.19.0.2",
                dns.resolveARecord("mystorageaccount.localhost.floci.io", "172.19.0.2").orElseThrow());
    }

    @Test
    void resolveARecord_noMatchIsEmpty() {
        assertTrue(dns.resolveARecord("example.com", "172.19.0.2").isEmpty());
    }

    // ── readName ──────────────────────────────────────────────────────────────

    @Test
    void readName_simple() {
        byte[] encoded = encodeName("mystorageaccount.localhost.floci.io");
        ByteBuffer buf = ByteBuffer.wrap(encoded);
        assertEquals("mystorageaccount.localhost.floci.io", dns.readName(buf, encoded));
    }

    @Test
    void readName_singleLabel() {
        byte[] encoded = encodeName("floci");
        ByteBuffer buf = ByteBuffer.wrap(encoded);
        assertEquals("floci", dns.readName(buf, encoded));
    }

    @Test
    void readName_withCompressionPointer() {
        // Build a buffer where the name at offset 4 is "floci.io" and
        // a pointer at offset 0 points to it.
        byte[] data = new byte[20];
        // pointer at offset 0 → offset 4
        data[0] = (byte) 0xC0;
        data[1] = 0x04;
        // "floci.io" at offset 4
        byte[] name = encodeName("floci.io");
        System.arraycopy(name, 0, data, 4, name.length);

        ByteBuffer buf = ByteBuffer.wrap(data);
        assertEquals("floci.io", dns.readName(buf, data));
    }

    // ── buildAResponse ────────────────────────────────────────────────────────

    @Test
    void buildAResponse_hasCorrectTransactionId() {
        byte[] query = buildQuery("mystorageaccount.localhost.floci.io", (short) 0x1234);
        byte[] response = dns.buildAResponse(query, (short) 0x1234, 12, query.length, "172.19.0.2");
        short txId = ByteBuffer.wrap(response).getShort(0);
        assertEquals((short) 0x1234, txId);
    }

    @Test
    void buildAResponse_flagsIndicateResponse() {
        byte[] query = buildQuery("account.localhost.floci.io", (short) 1);
        byte[] response = dns.buildAResponse(query, (short) 1, 12, query.length, "10.0.0.1");
        short flags = ByteBuffer.wrap(response).getShort(2);
        assertTrue((flags & 0x8000) != 0, "QR bit must be set");
    }

    @Test
    void buildAResponse_answerCountIsOne() {
        byte[] query = buildQuery("account.localhost.floci.io", (short) 2);
        byte[] response = dns.buildAResponse(query, (short) 2, 12, query.length, "10.0.0.1");
        short ancount = ByteBuffer.wrap(response).getShort(6);
        assertEquals(1, ancount);
    }

    @Test
    void buildAResponse_ipAddressIsCorrect() {
        byte[] query = buildQuery("account.localhost.floci.io", (short) 3);
        byte[] response = dns.buildAResponse(query, (short) 3, 12, query.length, "172.19.0.42");
        // IP starts at offset: 12 (header) + questionLength + 2+2+2+4+2 = questionLength + 24
        int questionLength = query.length - 12;
        ByteBuffer resp = ByteBuffer.wrap(response);
        resp.position(12 + questionLength + 10); // skip header + question + name-ptr(2) + type(2) + class(2) + ttl(4)
        short rdlen = resp.getShort();
        assertEquals(4, rdlen);
        assertEquals((byte) 172, resp.get());
        assertEquals((byte) 19, resp.get());
        assertEquals((byte) 0, resp.get());
        assertEquals((byte) 42, resp.get());
    }

    // ── composeUpstreams — forwarder upstream ordering ────────────────────────

    @Test
    void composeUpstreams_resolvConfFirstThenFallbacks() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of("192.168.65.7"), List.of("8.8.8.8", "8.8.4.4"), true);
        assertEquals(List.of("192.168.65.7", "8.8.8.8", "8.8.4.4"), upstreams);
    }

    @Test
    void composeUpstreams_usesDockerResolverBaselineWhenResolvConfEmpty() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of(), List.of("8.8.8.8"), true);
        // Docker's embedded resolver is the baseline, then the configured fallback.
        assertEquals(List.of("127.0.0.11", "8.8.8.8"), upstreams);
    }

    @Test
    void composeUpstreams_skipsLoopbackAndBlankEntries() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of("127.0.0.1", "  ", "10.0.0.2"), List.of("", "8.8.8.8"), true);
        assertEquals(List.of("10.0.0.2", "8.8.8.8"), upstreams);
    }

    @Test
    void composeUpstreams_dedupesAcrossResolvConfAndFallbacks() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of("8.8.8.8"), List.of("8.8.8.8", "8.8.4.4"), true);
        assertEquals(List.of("8.8.8.8", "8.8.4.4"), upstreams);
    }

    @Test
    void composeUpstreams_toleratesNullFallbacks() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(List.of("10.0.0.2"), null, true);
        assertEquals(List.of("10.0.0.2"), upstreams);
    }

    @Test
    void composeUpstreams_omitsPublicFallbacksWhenDisabled() {
        // FLOCI_AZ_DNS_CONTAINER_FALLBACK_ENABLED=false: no query may leave for a public
        // resolver in offline / locked-down networks.
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of("192.168.65.7"), List.of("8.8.8.8", "8.8.4.4"), false);
        assertEquals(List.of("192.168.65.7"), upstreams);
    }

    @Test
    void composeUpstreams_disabledFallbacksStillKeepDockerResolverBaseline() {
        List<String> upstreams = EmbeddedDnsServer.composeUpstreams(
                List.of(), List.of("8.8.8.8"), false);
        assertEquals(List.of("127.0.0.11"), upstreams);
    }

    // ── forwarding — response buffer size regression guard ────────────────────

    @Test
    void forwardToUpstreams_returnsResponseLargerThan512BytesIntact() throws Exception {
        // Regression guard: a 512-byte receive buffer silently truncated EDNS0 responses from
        // CDN-backed public hosts, corrupting the answer forwarded back to function containers.
        byte[] bigResponse = new byte[1500];
        for (int i = 0; i < bigResponse.length; i++) {
            bigResponse[i] = (byte) (i & 0xFF);
        }

        try (DatagramSocket responder = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            startResponder(responder, bigResponse);
            byte[] query = buildQuery("business-api.tiktok.com", (short) 0x1234);

            byte[] response = dns.forwardToUpstreams(
                    query, List.of("127.0.0.1"), responder.getLocalPort());

            assertEquals(bigResponse.length, response.length,
                    "response larger than 512 bytes must be forwarded without truncation");
            assertArrayEquals(bigResponse, response);
        }
    }

    /** Replies to the first datagram received with a fixed payload, on a daemon thread. */
    private void startResponder(DatagramSocket responder, byte[] responsePayload) {
        Thread t = new Thread(() -> {
            try {
                DatagramPacket req = new DatagramPacket(new byte[4096], 4096);
                responder.receive(req);
                responder.send(new DatagramPacket(
                        responsePayload, responsePayload.length, req.getAddress(), req.getPort()));
            } catch (Exception ignored) {
                // socket closed when the test completes
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private byte[] encodeName(String name) {
        String[] labels = name.split("\\.");
        int len = 1; // trailing zero
        for (String l : labels) {
            len += 1 + l.length();
        }
        byte[] buf = new byte[len];
        int pos = 0;
        for (String label : labels) {
            buf[pos++] = (byte) label.length();
            for (char c : label.toCharArray()) {
                buf[pos++] = (byte) c;
            }
        }
        buf[pos] = 0;
        return buf;
    }

    private byte[] buildQuery(String name, short txId) {
        byte[] encodedName = encodeName(name);
        // header(12) + name + type(2) + class(2)
        ByteBuffer buf = ByteBuffer.allocate(12 + encodedName.length + 4);
        buf.putShort(txId);
        buf.putShort((short) 0x0100); // standard query, RD=1
        buf.putShort((short) 1);       // qdcount
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.putShort((short) 0);
        buf.put(encodedName);
        buf.putShort((short) 1); // type A
        buf.putShort((short) 1); // class IN
        return buf.array();
    }
}
