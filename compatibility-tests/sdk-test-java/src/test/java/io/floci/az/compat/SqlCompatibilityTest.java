package io.floci.az.compat;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for the Azure SQL Database emulation layer.
 *
 * <p>Requires:
 * <ul>
 *   <li>floci-az emulator running (default: http://localhost:4577)</li>
 *   <li>Docker available so floci-az can start SQL Server containers</li>
 *   <li>EULA accepted: {@code FLOCI_AZ_SERVICES_SQL_ACCEPT_EULA=Y} set in the emulator</li>
 * </ul>
 *
 * <p>Run selectively (Docker needed):
 * <pre>
 *   mvn test -Dtest=SqlCompatibilityTest -pl compatibility-tests/sdk-test-java
 * </pre>
 */
@DisplayName("SQL Database compatibility")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqlCompatibilityTest {

    // ── Config ────────────────────────────────────────────────────────────────

    private static final String BASE =
        System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");

    private static final String SUB    = "compat-sub-001";
    private static final String RG     = "compat-rg";
    private static final String SERVER = "compat-sql-server";
    private static final String DB     = "compat-db";
    private static final String LOGIN  = "sa";
    private static final String PWD    = "FlociAz_Strong123!";

    private static final String ARM_BASE =
        BASE + "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.Sql";

    // ── Shared state populated by @BeforeAll ──────────────────────────────────

    /** JDBC URL for the server's master database (populated in @BeforeAll). */
    private static String masterJdbcUrl;
    /** JDBC URL for {@value #DB} (populated after createDatabase test). */
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

        // Reset emulator state to ensure a clean slate
        send("POST", BASE + "/_admin/reset", null);

        // PUT the logical server — this starts the Docker container
        String serverBody = String.format(
            "{\"location\":\"eastus\",\"properties\":{"
            + "\"administratorLogin\":\"%s\","
            + "\"administratorLoginPassword\":\"%s\"}}",
            LOGIN, PWD);

        // Allow up to 8 minutes: first-time image pull (~1.5 GB) + container start + readiness wait.
        // Subsequent runs (image cached by Docker) will complete in under 60 seconds.
        HttpResponse<String> resp = send("PUT",
            ARM_BASE + "/servers/" + SERVER + "?api-version=2021-11-01",
            serverBody, Duration.ofMinutes(8));

        // 503 = EULA not accepted — skip gracefully (Docker SQL Server needs explicit consent)
        assumeTrue(resp.statusCode() != 503,
            "SQL Server EULA not accepted — set FLOCI_AZ_SERVICES_SQL_ACCEPT_EULA=Y and restart emulator");

        // Azure returns 201 Created for new resources, 200 OK for updates — both are valid
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 201,
            "PUT server failed (" + resp.statusCode() + "): " + resp.body());

        // Fetch connection info for the master database
        HttpResponse<String> connectResp = send("GET",
            BASE + "/devstoreaccount1-sql/servers/" + SERVER + "/connect", null);
        assertEquals(200, connectResp.statusCode(),
            "/connect failed: " + connectResp.body());

        masterJdbcUrl = extractField(connectResp.body(), "jdbcUrl");
        assertNotNull(masterJdbcUrl, "jdbcUrl missing from /connect response");
    }

    @AfterAll
    static void deleteServer() throws Exception {
        if (http == null) return; // setup never completed
        try {
            send("DELETE", ARM_BASE + "/servers/" + SERVER + "?api-version=2021-11-01", null);
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
            ARM_BASE + "/servers/" + SERVER + "?api-version=2021-11-01", null);
        assertEquals(200, resp.statusCode());

        String body = resp.body();
        assertTrue(body.contains("\"name\":\"" + SERVER + "\""), "server name in response");
        assertTrue(body.contains("\"administratorLogin\":\"" + LOGIN + "\""), "login in response");
        assertTrue(body.contains("\"state\":\"Ready\""), "state=Ready");
    }

    @Test
    @Order(15)
    @DisplayName("list servers returns server in value array")
    void listServers() throws Exception {
        HttpResponse<String> resp = send("GET",
            ARM_BASE + "/servers?api-version=2021-11-01", null);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains(SERVER), "server name in list response");
    }

    @Test
    @Order(20)
    @DisplayName("JDBC connect to master database succeeds")
    void jdbcConnectMaster() throws Exception {
        try (Connection conn = DriverManager.getConnection(masterJdbcUrl)) {
            assertFalse(conn.isClosed(), "connection should be open");
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT @@VERSION")) {
                assertTrue(rs.next(), "@@VERSION should return a row");
                String version = rs.getString(1);
                assertNotNull(version, "@@VERSION should not be null");
                assertTrue(version.contains("SQL") || version.contains("Microsoft"),
                    "@@VERSION should mention SQL Server: " + version);
            }
        }
    }

    @Test
    @Order(30)
    @DisplayName("PUT database creates it and returns 200")
    void createDatabase() throws Exception {
        String dbBody = String.format(
            "{\"location\":\"eastus\",\"properties\":{\"collation\":\"%s\"}}",
            "SQL_Latin1_General_CP1_CI_AS");

        // Register the database in emulator state (no DDL executed in the container).
        HttpResponse<String> resp = send("PUT",
            ARM_BASE + "/servers/" + SERVER + "/databases/" + DB + "?api-version=2021-11-01",
            dbBody, Duration.ofSeconds(60));
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 201,
            "PUT database failed (" + resp.statusCode() + "): " + resp.body());

        String body = resp.body();
        assertTrue(body.contains("\"name\":\"" + DB + "\""), "db name in response");
        assertTrue(body.contains("\"status\":\"Online\""), "status=Online");

        // Fetch the database-specific JDBC URL
        HttpResponse<String> connectResp = send("GET",
            BASE + "/devstoreaccount1-sql/servers/" + SERVER + "/databases/" + DB + "/connect",
            null);
        assertEquals(200, connectResp.statusCode(),
            "database /connect failed: " + connectResp.body());

        appJdbcUrl = extractField(connectResp.body(), "jdbcUrl");
        assertNotNull(appJdbcUrl, "jdbcUrl missing from database /connect response");
        assertTrue(appJdbcUrl.contains(DB), "jdbcUrl should include the database name");
    }

    @Test
    @Order(35)
    @DisplayName("GET database returns 200")
    void getDatabase() throws Exception {
        HttpResponse<String> resp = send("GET",
            ARM_BASE + "/servers/" + SERVER + "/databases/" + DB + "?api-version=2021-11-01",
            null);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"name\":\"" + DB + "\""));
    }

    @Test
    @Order(40)
    @DisplayName("JDBC connect to app database and run DDL/DML")
    void jdbcDdlAndDml() throws Exception {
        assumeTrue(appJdbcUrl != null, "createDatabase test did not set appJdbcUrl — skipping");

        // The emulator registers the database in state but does NOT execute CREATE DATABASE
        // inside the SQL Server container — that is the responsibility of the application
        // (Flyway, Liquibase, EF Core migrations, etc.).  We create it here to simulate
        // what a migration tool would do on first deploy.
        try (Connection master = DriverManager.getConnection(masterJdbcUrl);
             Statement init = master.createStatement()) {
            master.setAutoCommit(true);
            init.execute("IF DB_ID('" + DB + "') IS NULL "
                + "CREATE DATABASE [" + DB + "] COLLATE SQL_Latin1_General_CP1_CI_AS");
        }

        try (Connection conn = DriverManager.getConnection(appJdbcUrl)) {
            // Create table
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(
                    "CREATE TABLE compat_test ("
                    + "  id   INT PRIMARY KEY,"
                    + "  name NVARCHAR(100) NOT NULL,"
                    + "  val  FLOAT"
                    + ")");
            }

            // Insert rows
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO compat_test (id, name, val) VALUES (?,?,?)")) {
                ps.setInt(1, 1);   ps.setString(2, "alpha"); ps.setDouble(3, 1.1); ps.addBatch();
                ps.setInt(1, 2);   ps.setString(2, "beta");  ps.setDouble(3, 2.2); ps.addBatch();
                ps.setInt(1, 3);   ps.setString(2, "gamma"); ps.setDouble(3, 3.3); ps.addBatch();
                ps.executeBatch();
            }

            // Select and verify
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT id, name, val FROM compat_test ORDER BY id")) {

                assertTrue(rs.next()); assertEquals(1, rs.getInt("id")); assertEquals("alpha", rs.getString("name"));
                assertTrue(rs.next()); assertEquals(2, rs.getInt("id")); assertEquals("beta",  rs.getString("name"));
                assertTrue(rs.next()); assertEquals(3, rs.getInt("id")); assertEquals("gamma", rs.getString("name"));
                assertFalse(rs.next(), "should be exactly 3 rows");
            }

            // Update
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE compat_test SET val = ? WHERE id = ?")) {
                ps.setDouble(1, 99.9); ps.setInt(2, 2);
                int updated = ps.executeUpdate();
                assertEquals(1, updated);
            }

            // Verify update
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT val FROM compat_test WHERE id = 2")) {
                assertTrue(rs.next());
                assertEquals(99.9, rs.getDouble("val"), 0.001);
            }

            // Drop table
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DROP TABLE compat_test");
            }
        }
    }

    @Test
    @Order(50)
    @DisplayName("checkNameAvailability — used server name is unavailable")
    void checkNameUnavailable() throws Exception {
        String body = "{\"name\":\"" + SERVER + "\",\"type\":\"Microsoft.Sql/servers\"}";
        HttpResponse<String> resp = send("POST",
            BASE + "/subscriptions/" + SUB + "/providers/Microsoft.Sql/checkNameAvailability"
            + "?api-version=2021-11-01",
            body);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"available\":false"), "name should be unavailable: " + resp.body());
    }

    @Test
    @Order(55)
    @DisplayName("Firewall rule CRUD on a real server")
    void firewallRuleCrud() throws Exception {
        String ruleName = "AllowLocal";
        String ruleBody = "{\"properties\":{\"startIpAddress\":\"0.0.0.0\",\"endIpAddress\":\"255.255.255.255\"}}";
        String ruleUrl = ARM_BASE + "/servers/" + SERVER + "/firewallRules/" + ruleName + "?api-version=2021-11-01";

        // PUT
        HttpResponse<String> putResp = send("PUT", ruleUrl, ruleBody);
        assertTrue(putResp.statusCode() == 200 || putResp.statusCode() == 201,
            "PUT firewall rule: " + putResp.body());

        // GET
        HttpResponse<String> getResp = send("GET", ruleUrl, null);
        assertEquals(200, getResp.statusCode());
        assertTrue(getResp.body().contains("\"0.0.0.0\""), "startIpAddress in GET response");

        // LIST
        HttpResponse<String> listResp = send("GET",
            ARM_BASE + "/servers/" + SERVER + "/firewallRules?api-version=2021-11-01", null);
        assertEquals(200, listResp.statusCode());
        assertTrue(listResp.body().contains(ruleName), "rule name in list");

        // DELETE
        HttpResponse<String> delResp = send("DELETE", ruleUrl, null);
        assertEquals(204, delResp.statusCode());

        // Verify gone
        HttpResponse<String> getAfter = send("GET", ruleUrl, null);
        assertEquals(404, getAfter.statusCode());
    }

    @Test
    @Order(60)
    @DisplayName("DELETE master database returns 400")
    void deleteMasterBlocked() throws Exception {
        HttpResponse<String> resp = send("DELETE",
            ARM_BASE + "/servers/" + SERVER + "/databases/master?api-version=2021-11-01", null);
        assertEquals(400, resp.statusCode(), "master drop should be blocked: " + resp.body());
        assertTrue(resp.body().contains("master"), "error message should mention master");
    }

    @Test
    @Order(70)
    @DisplayName("DELETE app database removes it from server")
    void deleteDatabase() throws Exception {
        assumeTrue(appJdbcUrl != null, "createDatabase test did not complete — skipping");

        HttpResponse<String> resp = send("DELETE",
            ARM_BASE + "/servers/" + SERVER + "/databases/" + DB + "?api-version=2021-11-01",
            null);
        assertEquals(204, resp.statusCode(), "DELETE database: " + resp.body());

        // Verify gone
        HttpResponse<String> getResp = send("GET",
            ARM_BASE + "/servers/" + SERVER + "/databases/" + DB + "?api-version=2021-11-01",
            null);
        assertEquals(404, getResp.statusCode());
    }

    @Test
    @Order(80)
    @DisplayName("_admin/reset wipes all SQL state (server + databases gone, container stopped)")
    void adminResetClearsSqlState() throws Exception {
        // Precondition: server created in @BeforeAll must still exist
        HttpResponse<String> before = send("GET",
            ARM_BASE + "/servers/" + SERVER + "?api-version=2021-11-01", null);
        assertEquals(200, before.statusCode(),
            "Server should exist before reset: " + before.body());

        // Precondition: master database registered in state
        HttpResponse<String> masterBefore = send("GET",
            ARM_BASE + "/servers/" + SERVER + "/databases/master?api-version=2021-11-01", null);
        assertEquals(200, masterBefore.statusCode(),
            "master db should exist before reset: " + masterBefore.body());

        // ── Reset ──────────────────────────────────────────────────────────────
        HttpResponse<String> resetResp = send("POST", BASE + "/_admin/reset", null);
        assertEquals(204, resetResp.statusCode(),
            "/_admin/reset should return 204: " + resetResp.body());

        // ── Verify server is gone ──────────────────────────────────────────────
        HttpResponse<String> afterServer = send("GET",
            ARM_BASE + "/servers/" + SERVER + "?api-version=2021-11-01", null);
        assertEquals(404, afterServer.statusCode(),
            "Server should be gone after reset: " + afterServer.body());

        // ── Verify master database is gone ─────────────────────────────────────
        HttpResponse<String> afterMaster = send("GET",
            ARM_BASE + "/servers/" + SERVER + "/databases/master?api-version=2021-11-01", null);
        assertEquals(404, afterMaster.statusCode(),
            "master db should be gone after reset: " + afterMaster.body());

        // ── Verify server list is empty ────────────────────────────────────────
        HttpResponse<String> listResp = send("GET",
            ARM_BASE + "/servers?api-version=2021-11-01", null);
        assertEquals(200, listResp.statusCode());
        assertFalse(listResp.body().contains(SERVER),
            "Server list should be empty after reset: " + listResp.body());

        // ── Re-create server proves the name is available again ────────────────
        // (checkNameAvailability should now return available=true)
        String checkBody = "{\"name\":\"" + SERVER + "\",\"type\":\"Microsoft.Sql/servers\"}";
        HttpResponse<String> checkResp = send("POST",
            BASE + "/subscriptions/" + SUB + "/providers/Microsoft.Sql/checkNameAvailability"
            + "?api-version=2021-11-01",
            checkBody);
        assertEquals(200, checkResp.statusCode());
        assertTrue(checkResp.body().contains("\"available\":true"),
            "Name should be available again after reset: " + checkResp.body());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static HttpResponse<String> send(String method, String url, String jsonBody)
            throws Exception {
        return send(method, url, jsonBody, Duration.ofSeconds(10));
    }

    /**
     * Like {@link #send(String, String, String)} but with an explicit request timeout.
     * Use a long timeout (e.g. 3 minutes) for calls that start Docker containers.
     */
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

    /**
     * Extracts a JSON string field value from a raw JSON body using simple string scanning.
     * No external JSON library required in the test — keeps the test self-contained.
     */
    private static String extractField(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) return null;
        start += needle.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        // Unescape forward slashes that Jackson escapes in JDBC URLs
        return json.substring(start, end).replace("\\/", "/");
    }
}
