package io.floci.az.compat;

import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the Event Hubs namespace management REST API:
 *   PUT    /{account}-eventhub/namespaces/{ns}          — create (start) namespace
 *   GET    /{account}-eventhub/namespaces               — list namespaces
 *   GET    /{account}-eventhub/namespaces/{ns}          — get namespace
 *   GET    /{account}-eventhub/namespaces/{ns}/connection — connection info
 *   GET    /{account}-eventhub/namespaces/{ns}/tls-cert   — TLS certificate PEM
 *   DELETE /{account}-eventhub/namespaces/{ns}          — stop namespace
 *
 * Each test run uses a unique namespace name to avoid interference with other
 * tests that use the shared emulatorNs1 namespace.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Event Hubs Namespace Management API")
class EventHubNamespaceManagementTest {

    private static final String BASE    = System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");
    private static final String ACCOUNT = EmulatorConfig.ACCOUNT;

    private String ns;
    private int amqpPort;
    private int amqpsPort;
    private boolean namespaceMocked = false;

    @BeforeAll
    void chooseUniqueNamespace() {
        // Use a unique namespace name so these tests are isolated from the shared emulatorNs1
        ns = "mgmt-test-" + UUID.randomUUID().toString().substring(0, 8);
        // Port hints passed to the PUT — the emulator may return the Docker-internal port
        // (5672/5671) regardless, so tests check presence/format rather than exact values.
        amqpPort  = 15672;
        amqpsPort = 15673;
    }

    @AfterAll
    void cleanup() throws Exception {
        // Best-effort cleanup so stale containers don't accumulate
        try {
            delete(ns);
        } catch (Exception ignored) {}
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private int put(String namespaceName) throws Exception {
        String url = BASE + "/" + ACCOUNT + "-eventhub/namespaces/" + namespaceName;
        String body = String.format("{\"amqpPort\":%d,\"amqpTlsPort\":%d}", amqpPort, amqpsPort);
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
        try (OutputStream os = conn.getOutputStream()) { os.write(bodyBytes); }
        return conn.getResponseCode();
    }

    private int delete(String namespaceName) throws Exception {
        String url = BASE + "/" + ACCOUNT + "-eventhub/namespaces/" + namespaceName;
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("DELETE");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        return conn.getResponseCode();
    }

    private String getBody(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        int status = conn.getResponseCode();
        InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private int getStatus(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        return conn.getResponseCode();
    }

    private String nsUrl()            { return BASE + "/" + ACCOUNT + "-eventhub/namespaces/" + ns; }
    private String listUrl()          { return BASE + "/" + ACCOUNT + "-eventhub/namespaces"; }
    private String connectionUrl()    { return nsUrl() + "/connection"; }
    private String tlsCertUrl()       { return nsUrl() + "/tls-cert"; }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("PUT namespace returns 201 with name and port fields")
    void createNamespace_returns201() throws Exception {
        int status = put(ns);
        assertEquals(201, status, "Expected 201 on namespace creation");

        // The emulator may map to its Docker-internal port (5672/5671) regardless of
        // the hint we passed, so we assert presence of the fields rather than exact values.
        String body = getBody(nsUrl());
        assertTrue(body.contains("\"name\":\"" + ns + "\""), "Response missing namespace name");
        assertTrue(body.contains("\"amqpPort\":"), "Response missing amqpPort field");
        assertTrue(body.contains("\"amqpsPort\":"), "Response missing amqpsPort field");
        namespaceMocked = body.contains("\"mocked\":true");
    }

    @Test
    @Order(2)
    @DisplayName("PUT namespace a second time is idempotent (200)")
    void createNamespace_idempotentReturns200() throws Exception {
        int status = put(ns);
        assertEquals(200, status, "Repeated PUT should return 200 (already running)");
    }

    @Test
    @Order(3)
    @DisplayName("GET namespaces list contains the created namespace")
    void listNamespaces_containsCreatedNamespace() throws Exception {
        String body = getBody(listUrl());
        assertTrue(body.contains("\"namespaces\""), "Response should have namespaces field");
        assertTrue(body.contains("\"name\":\"" + ns + "\""),
                "Namespace list should contain '" + ns + "'");
    }

    @Test
    @Order(4)
    @DisplayName("GET namespace returns name and port fields")
    void getNamespace_returnsDetails() throws Exception {
        int status = getStatus(nsUrl());
        assertEquals(200, status);

        String body = getBody(nsUrl());
        assertTrue(body.contains("\"name\":\"" + ns + "\""), "Response missing namespace name");
        assertTrue(body.contains("\"amqpPort\":"), "Response missing amqpPort field");
    }

    @Test
    @Order(5)
    @DisplayName("GET connection returns amqp:// and amqps:// endpoint URLs")
    void getConnectionInfo_returnsEndpoints() throws Exception {
        int status = getStatus(connectionUrl());
        assertEquals(200, status);

        String body = getBody(connectionUrl());
        assertTrue(body.contains("\"amqpPort\":"), "Missing amqpPort in connection info");
        assertTrue(body.contains("\"amqpsPort\":"), "Missing amqpsPort in connection info");
        assertTrue(body.contains("\"amqpEndpoint\""), "Missing amqpEndpoint");
        assertTrue(body.contains("\"amqpsEndpoint\""), "Missing amqpsEndpoint");
        assertTrue(body.contains("amqp://"), "amqpEndpoint should use amqp:// scheme");
        assertTrue(body.contains("amqps://"), "amqpsEndpoint should use amqps:// scheme");
    }

    @Test
    @Order(6)
    @DisplayName("GET tls-cert returns a PEM certificate")
    void getTlsCert_returnsPem() throws Exception {
        Assumptions.assumeTrue(!namespaceMocked,
                "Namespace is mocked (no Artemis broker) — TLS cert not available");

        int status = getStatus(tlsCertUrl());
        assertEquals(200, status);

        String body = getBody(tlsCertUrl());
        assertTrue(body.startsWith("-----BEGIN CERTIFICATE-----"),
                "TLS cert response should be a PEM certificate");
        assertTrue(body.contains("-----END CERTIFICATE-----"));
    }

    @Test
    @Order(7)
    @DisplayName("DELETE namespace returns 204")
    void deleteNamespace_returns204() throws Exception {
        int status = delete(ns);
        assertEquals(204, status, "Expected 204 on namespace deletion");
    }

    @Test
    @Order(8)
    @DisplayName("GET namespace after deletion returns 404")
    void getNamespace_returns404AfterDeletion() throws Exception {
        int status = getStatus(nsUrl());
        assertEquals(404, status, "Namespace should be gone after DELETE");
    }

    @Test
    @Order(9)
    @DisplayName("DELETE nonexistent namespace returns 404")
    void deleteNonexistentNamespace_returns404() throws Exception {
        int status = delete("does-not-exist-" + UUID.randomUUID());
        assertEquals(404, status);
    }
}
