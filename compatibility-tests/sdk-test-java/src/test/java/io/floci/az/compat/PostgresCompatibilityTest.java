package io.floci.az.compat;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for the Azure Database for PostgreSQL (Flexible Server) emulation layer.
 *
 * <p>Requires:
 * <ul>
 *   <li>floci-az emulator running (default: http://localhost:4577)</li>
 *   <li>Docker available so floci-az can start PostgreSQL containers</li>
 * </ul>
 *
 * <p>Unlike Azure SQL there is no EULA — the {@code postgres} image is PostgreSQL-licensed.
 *
 * <p>Run selectively (Docker needed):
 * <pre>
 *   mvn test -Dtest=PostgresCompatibilityTest -pl compatibility-tests/sdk-test-java
 * </pre>
 */
@DisplayName("PostgreSQL Flexible Server compatibility")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresCompatibilityTest {

    // ── Config ────────────────────────────────────────────────────────────────

    private static final String BASE =
        System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");

    private static final String SUB    = "compat-sub-001";
    private static final String RG     = "compat-rg";
    private static final String SERVER = "compat-pg-server";
    private static final String DB     = "compat_db";
    private static final String LOGIN  = "psqladmin";
    private static final String PWD    = "FlociAz_Strong123!";

    private static final String ARM_BASE =
        BASE + "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.DBforPostgreSQL";
    private static final String API = "?api-version=2024-08-01";

    // ── Shared state populated by @BeforeAll ──────────────────────────────────

    /** JDBC URL for the server's default {@code postgres} database. */
    private static String defaultJdbcUrl;
    /** JDBC URL for {@value #DB}, derived from the default URL. */
    private static volatile String appJdbcUrl;

    private static HttpClient http;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeAll
    @Timeout(value = 10, unit = java.util.concurrent.TimeUnit.MINUTES)
    static void createServer() throws Exception {
        EmulatorConfig.assumeEmulatorRunning();

        http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        send("POST", BASE + "/_admin/reset", null);

        String serverBody = String.format(
            "{\"location\":\"eastus\","
            + "\"sku\":{\"name\":\"Standard_B1ms\",\"tier\":\"Burstable\"},"
            + "\"properties\":{"
            + "\"administratorLogin\":\"%s\","
            + "\"administratorLoginPassword\":\"%s\","
            + "\"version\":\"16\","
            + "\"storage\":{\"storageSizeGB\":32}}}",
            LOGIN, PWD);

        // Allow up to 5 minutes: first-time image pull (postgres:17-alpine) + container start.
        HttpResponse<String> resp = send("PUT",
            ARM_BASE + "/flexibleServers/" + SERVER + API, serverBody, Duration.ofMinutes(5));

        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 201,
            "PUT server failed (" + resp.statusCode() + "): " + resp.body());

        HttpResponse<String> connectResp = send("GET",
            BASE + "/devstoreaccount1-postgres/flexibleServers/" + SERVER + "/connect", null);
        assertEquals(200, connectResp.statusCode(), "/connect failed: " + connectResp.body());

        defaultJdbcUrl = extractField(connectResp.body(), "jdbcUrl");
        assertNotNull(defaultJdbcUrl, "jdbcUrl missing from /connect response");
        // Derive the app-database URL from the default (.../postgres?...  ->  .../compat_db?...).
        appJdbcUrl = defaultJdbcUrl.replace("/postgres?", "/" + DB + "?");
    }

    @AfterAll
    static void deleteServer() throws Exception {
        if (http == null) return;
        try {
            send("DELETE", ARM_BASE + "/flexibleServers/" + SERVER + API, null);
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("GET server returns 200 with expected properties")
    void getServer() throws Exception {
        HttpResponse<String> resp = send("GET",
            ARM_BASE + "/flexibleServers/" + SERVER + API, null);
        assertEquals(200, resp.statusCode());

        String body = resp.body();
        assertTrue(body.contains("\"name\":\"" + SERVER + "\""), "server name in response");
        assertTrue(body.contains("\"administratorLogin\":\"" + LOGIN + "\""), "login in response");
        assertTrue(body.contains("\"provisioningState\":\"Succeeded\""), "provisioningState=Succeeded");
        assertTrue(body.contains("fullyQualifiedDomainName"), "fqdn present");
    }

    @Test
    @Order(15)
    @DisplayName("list servers returns server in value array")
    void listServers() throws Exception {
        HttpResponse<String> resp = send("GET", ARM_BASE + "/flexibleServers" + API, null);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains(SERVER), "server name in list response");
    }

    @Test
    @Order(20)
    @DisplayName("JDBC connect to the default postgres database succeeds")
    void jdbcConnectDefault() throws Exception {
        try (Connection conn = DriverManager.getConnection(defaultJdbcUrl)) {
            assertFalse(conn.isClosed(), "connection should be open");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT version()")) {
                assertTrue(rs.next(), "version() should return a row");
                String version = rs.getString(1);
                assertNotNull(version);
                assertTrue(version.contains("PostgreSQL"), "version() should mention PostgreSQL: " + version);
            }
        }
    }

    @Test
    @Order(30)
    @DisplayName("PUT database registers it and returns 200/201")
    void createDatabase() throws Exception {
        String dbBody = "{\"properties\":{\"charset\":\"UTF8\",\"collation\":\"en_US.utf8\"}}";

        HttpResponse<String> resp = send("PUT",
            ARM_BASE + "/flexibleServers/" + SERVER + "/databases/" + DB + API,
            dbBody, Duration.ofSeconds(60));
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 201,
            "PUT database failed (" + resp.statusCode() + "): " + resp.body());
        assertTrue(resp.body().contains("\"name\":\"" + DB + "\""), "db name in response");
    }

    @Test
    @Order(40)
    @DisplayName("JDBC: CREATE DATABASE then run DDL/DML against it")
    void jdbcDdlAndDml() throws Exception {
        assumeTrue(appJdbcUrl != null, "server setup did not complete — skipping");

        // The emulator registers the database in state but does NOT execute CREATE DATABASE
        // inside the container — that is the application's responsibility (Flyway, Liquibase,
        // migrations, etc.). We create it here to simulate what a migration tool would do.
        // CREATE DATABASE cannot run while connected to the target db, so use the default one.
        try (Connection admin = DriverManager.getConnection(defaultJdbcUrl)) {
            admin.setAutoCommit(true);
            boolean exists;
            try (Statement st = admin.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + DB + "'")) {
                exists = rs.next();
            }
            if (!exists) {
                try (Statement st = admin.createStatement()) {
                    st.execute("CREATE DATABASE " + DB);
                }
            }
        }

        try (Connection conn = DriverManager.getConnection(appJdbcUrl)) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(
                    "CREATE TABLE compat_test ("
                    + "  id   INT PRIMARY KEY,"
                    + "  name VARCHAR(100) NOT NULL,"
                    + "  val  DOUBLE PRECISION"
                    + ")");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO compat_test (id, name, val) VALUES (?,?,?)")) {
                ps.setInt(1, 1); ps.setString(2, "alpha"); ps.setDouble(3, 1.1); ps.addBatch();
                ps.setInt(1, 2); ps.setString(2, "beta");  ps.setDouble(3, 2.2); ps.addBatch();
                ps.setInt(1, 3); ps.setString(2, "gamma"); ps.setDouble(3, 3.3); ps.addBatch();
                ps.executeBatch();
            }

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, name, val FROM compat_test ORDER BY id")) {
                assertTrue(rs.next()); assertEquals(1, rs.getInt("id")); assertEquals("alpha", rs.getString("name"));
                assertTrue(rs.next()); assertEquals(2, rs.getInt("id")); assertEquals("beta",  rs.getString("name"));
                assertTrue(rs.next()); assertEquals(3, rs.getInt("id")); assertEquals("gamma", rs.getString("name"));
                assertFalse(rs.next(), "should be exactly 3 rows");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE compat_test SET val = ? WHERE id = ?")) {
                ps.setDouble(1, 99.9); ps.setInt(2, 2);
                assertEquals(1, ps.executeUpdate());
            }

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT val FROM compat_test WHERE id = 2")) {
                assertTrue(rs.next());
                assertEquals(99.9, rs.getDouble("val"), 0.001);
            }

            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DROP TABLE compat_test");
            }
        }
    }

    @Test
    @Order(50)
    @DisplayName("checkNameAvailability — used server name is unavailable")
    void checkNameUnavailable() throws Exception {
        String body = "{\"name\":\"" + SERVER + "\",\"type\":\"Microsoft.DBforPostgreSQL/flexibleServers\"}";
        HttpResponse<String> resp = send("POST",
            BASE + "/subscriptions/" + SUB
            + "/providers/Microsoft.DBforPostgreSQL/locations/eastus/checkNameAvailability" + API,
            body);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"nameAvailable\":false"),
            "name should be unavailable: " + resp.body());
    }

    @Test
    @Order(55)
    @DisplayName("Firewall rule CRUD on a real server")
    void firewallRuleCrud() throws Exception {
        String ruleName = "AllowLocal";
        String ruleBody = "{\"properties\":{\"startIpAddress\":\"0.0.0.0\",\"endIpAddress\":\"255.255.255.255\"}}";
        String ruleUrl = ARM_BASE + "/flexibleServers/" + SERVER + "/firewallRules/" + ruleName + API;

        HttpResponse<String> putResp = send("PUT", ruleUrl, ruleBody);
        assertTrue(putResp.statusCode() == 200 || putResp.statusCode() == 201,
            "PUT firewall rule: " + putResp.body());

        HttpResponse<String> getResp = send("GET", ruleUrl, null);
        assertEquals(200, getResp.statusCode());
        assertTrue(getResp.body().contains("\"0.0.0.0\""), "startIpAddress in GET response");

        HttpResponse<String> listResp = send("GET",
            ARM_BASE + "/flexibleServers/" + SERVER + "/firewallRules" + API, null);
        assertEquals(200, listResp.statusCode());
        assertTrue(listResp.body().contains(ruleName), "rule name in list");

        HttpResponse<String> delResp = send("DELETE", ruleUrl, null);
        assertEquals(204, delResp.statusCode());

        HttpResponse<String> getAfter = send("GET", ruleUrl, null);
        assertEquals(404, getAfter.statusCode());
    }

    @Test
    @Order(60)
    @DisplayName("Configuration put/get round-trips a server parameter")
    void configurationRoundTrip() throws Exception {
        String cfgUrl = ARM_BASE + "/flexibleServers/" + SERVER + "/configurations/max_connections" + API;

        HttpResponse<String> putResp = send("PUT", cfgUrl,
            "{\"properties\":{\"value\":\"200\",\"source\":\"user-override\"}}");
        assertEquals(200, putResp.statusCode(), "PUT configuration: " + putResp.body());

        HttpResponse<String> getResp = send("GET", cfgUrl, null);
        assertEquals(200, getResp.statusCode());
        assertTrue(getResp.body().contains("\"value\":\"200\""), "configuration value persisted");
    }

    @Test
    @Order(70)
    @DisplayName("DELETE app database removes it from server")
    void deleteDatabase() throws Exception {
        HttpResponse<String> resp = send("DELETE",
            ARM_BASE + "/flexibleServers/" + SERVER + "/databases/" + DB + API, null);
        assertEquals(204, resp.statusCode(), "DELETE database: " + resp.body());

        HttpResponse<String> getResp = send("GET",
            ARM_BASE + "/flexibleServers/" + SERVER + "/databases/" + DB + API, null);
        assertEquals(404, getResp.statusCode());
    }

    @Test
    @Order(80)
    @DisplayName("_admin/reset wipes all PostgreSQL state and frees the server name")
    void adminResetClearsState() throws Exception {
        HttpResponse<String> before = send("GET", ARM_BASE + "/flexibleServers/" + SERVER + API, null);
        assertEquals(200, before.statusCode(), "Server should exist before reset: " + before.body());

        HttpResponse<String> resetResp = send("POST", BASE + "/_admin/reset", null);
        assertEquals(204, resetResp.statusCode(), "/_admin/reset should return 204: " + resetResp.body());

        HttpResponse<String> afterServer = send("GET", ARM_BASE + "/flexibleServers/" + SERVER + API, null);
        assertEquals(404, afterServer.statusCode(), "Server should be gone after reset: " + afterServer.body());

        String checkBody = "{\"name\":\"" + SERVER + "\",\"type\":\"Microsoft.DBforPostgreSQL/flexibleServers\"}";
        HttpResponse<String> checkResp = send("POST",
            BASE + "/subscriptions/" + SUB
            + "/providers/Microsoft.DBforPostgreSQL/locations/eastus/checkNameAvailability" + API,
            checkBody);
        assertEquals(200, checkResp.statusCode());
        assertTrue(checkResp.body().contains("\"nameAvailable\":true"),
            "Name should be available again after reset: " + checkResp.body());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static HttpResponse<String> send(String method, String url, String jsonBody) throws Exception {
        return send(method, url, jsonBody, Duration.ofSeconds(10));
    }

    private static HttpResponse<String> send(String method, String url, String jsonBody,
                                             Duration timeout) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout);

        if (jsonBody != null) {
            builder.header("Content-Type", "application/json")
                   .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String extractField(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) return null;
        start += needle.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end).replace("\\/", "/");
    }
}
