package io.floci.az.core.dns;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerDetector;
import io.quarkus.runtime.Startup;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedSet;

/**
 * Embedded UDP/53 DNS server that runs inside the floci-az container and is injected
 * into every spawned Azure Functions container as their DNS resolver.
 *
 * Resolves *.{floci-az hostname} (and any configured extra-suffixes) to floci-az's own
 * Docker network IP so virtual-hosted Azure Storage URLs work from inside function
 * containers without requiring wildcard Docker aliases.
 *
 * All other queries are forwarded to the upstream resolvers read from /etc/resolv.conf
 * (Docker's embedded DNS at 127.0.0.11), falling back to the configured public resolvers
 * (floci-az.dns.container-fallback-servers) so public hostnames still resolve when the
 * resolv.conf resolver does not answer.
 *
 * Only starts when floci-az detects it is running inside Docker. No-op on the host.
 */
@ApplicationScoped
@Startup
public class EmbeddedDnsServer {

    private static final Logger LOG = Logger.getLogger(EmbeddedDnsServer.class);
    private static final int DNS_PORT = 53;
    private static final int TTL = 60;
    private static final String FALLBACK_UPSTREAM = "127.0.0.11";
    // EDNS0-capable resolvers (Node/c-ares, glibc) advertise UDP payloads well above the
    // legacy 512-byte limit; CDN-backed public names return larger responses. Receiving into
    // a 512-byte buffer silently truncates the datagram and corrupts the forwarded answer.
    private static final int MAX_DNS_UDP_RESPONSE = 4096;
    // Per-upstream timeout. Bounded so trying every upstream stays under a typical 5s client
    // resolver timeout even in the worst case.
    private static final int FORWARD_TIMEOUT_MS = 1500;
    public static final String DEFAULT_SUFFIX = "localhost.floci.io";

    // Well-known wildcard DNS domain that always resolves to floci-az's IP.
    // Covers "localhost.floci.io" itself and "*.localhost.floci.io".
    static final List<String> BUILTIN_SUFFIXES = List.of(DEFAULT_SUFFIX);

    private volatile String serverIp;
    private final SequencedSet<String> suffixes = new LinkedHashSet<>();
    private volatile List<String> upstreamDnsServers = List.of();

    EmbeddedDnsServer(List<String> suffixes) {
        this.suffixes.addAll(BUILTIN_SUFFIXES);
        this.suffixes.addAll(suffixes);
    }

    @Inject
    public EmbeddedDnsServer(EmulatorConfig config, ContainerDetector containerDetector, Vertx vertx) {
        if (!containerDetector.isRunningInContainer()) {
            return;
        }
        try {
            String myIp = InetAddress.getLocalHost().getHostAddress();
            upstreamDnsServers = composeUpstreams(readResolvConfNameservers(),
                    config.dns().containerFallbackServers(),
                    config.dns().containerFallbackEnabled());

            suffixes.addAll(BUILTIN_SUFFIXES);
            config.hostname().ifPresent(suffixes::add);
            config.dns().extraSuffixes().ifPresent(suffixes::addAll);

            DatagramSocket socket = vertx.createDatagramSocket(new DatagramSocketOptions().setIpV6(false));
            socket.listen(DNS_PORT, "0.0.0.0", ar -> {
                if (ar.succeeded()) {
                    serverIp = myIp;
                    LOG.infov("Embedded DNS server started on {0}:53, resolving {1} → {0}", myIp, suffixes);
                    socket.handler(packet -> handleQuery(
                            vertx, socket, packet.data().getBytes(),
                            packet.sender().host(), packet.sender().port(), myIp));
                } else {
                    LOG.warnv("Embedded DNS server failed to bind on port 53: {0}", ar.cause().getMessage());
                }
            });
        } catch (Exception e) {
            LOG.warnv("Failed to initialize embedded DNS server: {0}", e.getMessage());
        }
    }

    public Optional<String> getServerIp() {
        return Optional.ofNullable(serverIp);
    }

    // ── packet handling ───────────────────────────────────────────────────────

    private void handleQuery(Vertx vertx, DatagramSocket socket, byte[] data,
                             String senderHost, int senderPort, String myIp) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(data);
            short txId = buf.getShort();
            short flags = buf.getShort();
            short qdCount = buf.getShort();
            buf.getShort(); // ancount
            buf.getShort(); // nscount
            buf.getShort(); // arcount

            if ((flags & 0x8000) != 0 || qdCount < 1) {
                return; // not a standard query
            }

            int questionOffset = buf.position(); // always 12 for a standard query
            String qname = readName(buf, data);
            short qtype = buf.getShort();
            buf.getShort(); // qclass
            int questionEnd = buf.position();

            Optional<String> resolvedAddress = qtype == 1 ? resolveARecord(qname, myIp) : Optional.empty();
            if (resolvedAddress.isPresent()) {
                byte[] response = buildAResponse(data, txId, questionOffset, questionEnd, resolvedAddress.get());
                socket.send(Buffer.buffer(response), senderPort, senderHost, v -> {});
            } else {
                forwardAsync(vertx, socket, data, senderHost, senderPort);
            }
        } catch (Exception e) {
            LOG.debugv("DNS packet error: {0}", e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    boolean matchesSuffix(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String suffix : suffixes) {
            String s = suffix.toLowerCase();
            if (lower.equals(s) || lower.endsWith("." + s)) {
                return true;
            }
        }
        return false;
    }

    Optional<String> resolveARecord(String name, String myIp) {
        if (matchesSuffix(name)) {
            return Optional.of(myIp);
        }
        return Optional.empty();
    }

    String readName(ByteBuffer buf, byte[] data) {
        StringBuilder sb = new StringBuilder();
        int safety = 0;
        while (buf.hasRemaining() && safety++ < 128) {
            int len = buf.get() & 0xFF;
            if (len == 0) {
                break;
            }
            if ((len & 0xC0) == 0xC0) {
                // compression pointer
                int offset = ((len & 0x3F) << 8) | (buf.get() & 0xFF);
                ByteBuffer ptr = ByteBuffer.wrap(data);
                ptr.position(offset);
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(readName(ptr, data));
                return sb.toString();
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            byte[] label = new byte[len];
            buf.get(label);
            sb.append(new String(label));
        }
        return sb.toString();
    }

    byte[] buildAResponse(byte[] query, short txId, int questionOffset, int questionEnd, String ip) {
        int questionLength = questionEnd - questionOffset;
        // header(12) + question + answer(name-ptr(2) + type(2) + class(2) + ttl(4) + rdlen(2) + rdata(4))
        ByteBuffer resp = ByteBuffer.allocate(12 + questionLength + 16);

        // header
        resp.putShort(txId);
        resp.putShort((short) 0x8180); // QR=1, AA=1, RD=1, RCODE=0
        resp.putShort((short) 1);      // qdcount
        resp.putShort((short) 1);      // ancount
        resp.putShort((short) 0);      // nscount
        resp.putShort((short) 0);      // arcount

        // question (copied verbatim from query)
        resp.put(query, questionOffset, questionLength);

        // answer
        resp.putShort((short) 0xC00C); // name pointer to offset 12 (start of question name)
        resp.putShort((short) 1);       // type A
        resp.putShort((short) 1);       // class IN
        resp.putInt(TTL);
        resp.putShort((short) 4);       // rdlength

        for (String octet : ip.split("\\.")) {
            resp.put((byte) Integer.parseInt(octet));
        }

        return resp.array();
    }

    private void forwardAsync(Vertx vertx, DatagramSocket socket, byte[] query,
                              String senderHost, int senderPort) {
        List<String> upstreams = upstreamDnsServers;
        if (upstreams.isEmpty()) {
            return;
        }
        vertx.executeBlocking(() -> forwardToUpstreams(query, upstreams, DNS_PORT))
                .onSuccess(response ->
                        socket.send(Buffer.buffer(response), senderPort, senderHost, v -> {}))
                .onFailure(e ->
                        LOG.warnv("DNS forwarding failed on all upstreams {0}: {1}",
                                upstreams, e.getMessage()));
    }

    /**
     * Forwards the query to each upstream in order and returns the first valid UDP response.
     * Throws if every upstream times out or errors, so the caller can log a single warning.
     * The {@code upstreamPort} is parameterised for tests; production always uses {@link #DNS_PORT}.
     */
    byte[] forwardToUpstreams(byte[] query, List<String> upstreams, int upstreamPort) throws Exception {
        Exception last = null;
        for (String upstream : upstreams) {
            try (java.net.DatagramSocket fwd = new java.net.DatagramSocket()) {
                fwd.setSoTimeout(FORWARD_TIMEOUT_MS);
                InetAddress addr = InetAddress.getByName(upstream);
                fwd.send(new DatagramPacket(query, query.length, addr, upstreamPort));
                byte[] buf = new byte[MAX_DNS_UDP_RESPONSE];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                fwd.receive(resp);
                return Arrays.copyOf(resp.getData(), resp.getLength());
            } catch (Exception e) {
                last = e;
                LOG.debugv("DNS forward to {0} failed: {1}", upstream, e.getMessage());
            }
        }
        throw last != null ? last : new IOException("no upstream resolvers configured");
    }

    /**
     * Builds the ordered, de-duplicated upstream list the forwarder tries in turn: the
     * resolver(s) from {@code /etc/resolv.conf} first (or Docker's embedded resolver as a
     * baseline when none are usable), then the configured public fallbacks. The fallbacks let
     * public names resolve even when the resolv.conf resolver does not answer, mirroring the
     * {@code --dns <floci-az IP> --dns 8.8.8.8} workaround. When {@code fallbackEnabled} is
     * {@code false} (offline / locked-down networks), the public fallbacks are omitted so no
     * query ever leaves for an external resolver.
     */
    static List<String> composeUpstreams(List<String> resolvConf, List<String> fallbacks,
                                         boolean fallbackEnabled) {
        SequencedSet<String> ordered = new LinkedHashSet<>();
        for (String server : resolvConf) {
            if (isUsableUpstream(server)) {
                ordered.add(server.trim());
            }
        }
        if (ordered.isEmpty()) {
            ordered.add(FALLBACK_UPSTREAM);
        }
        if (fallbackEnabled && fallbacks != null) {
            for (String server : fallbacks) {
                if (isUsableUpstream(server)) {
                    ordered.add(server.trim());
                }
            }
        }
        return List.copyOf(ordered);
    }

    private static boolean isUsableUpstream(String server) {
        return server != null && !server.isBlank() && !server.trim().equals("127.0.0.1");
    }

    private List<String> readResolvConfNameservers() {
        List<String> servers = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(Path.of("/etc/resolv.conf"))) {
                line = line.trim();
                if (line.startsWith("nameserver ")) {
                    servers.add(line.substring("nameserver ".length()).trim());
                }
            }
        } catch (Exception e) {
            LOG.debugv("Could not read /etc/resolv.conf: {0}", e.getMessage());
        }
        return servers;
    }
}
