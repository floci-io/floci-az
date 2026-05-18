package io.floci.az.compat;

import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compatibility tests for the Cosmos PostgreSQL engine (cosmos-postgresql).
 *
 * <p>Tests are skipped automatically when the PostgreSQL engine is not enabled in floci-az config.
 * They require Docker and FLOCI_AZ_SERVICES_COSMOS_ENGINES_POSTGRESQL_ENABLED=true.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Cosmos PostgreSQL Engine Compatibility")
class CosmosPostgresEngineCompatibilityTest {

    private Connection connection;
    private static final String TABLE = "floci_test_pg";

    @BeforeAll
    void setup() throws Exception {
        Map<String, Object> engineInfo = EmulatorConfig.triggerCosmosEngine("cosmos-postgresql");
        assumeTrue(engineInfo != null, "PostgreSQL engine not enabled — skipping tests");

        String host = (String) engineInfo.get("host");
        int port = ((Number) engineInfo.get("port")).intValue();
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/citus";

        // DriverManager.getConnection() can block indefinitely even with connectTimeout
        // because the JDBC timeout only applies to the TCP handshake, not the PG auth phase.
        // We wrap each attempt in a Future with a hard 8s wall-clock timeout.
        Exception lastException = null;
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            ExecutorService ex = Executors.newSingleThreadExecutor();
            Future<Connection> future = ex.submit(
                    () -> DriverManager.getConnection(jdbcUrl, "citus", "mypassword"));
            try {
                connection = future.get(8, TimeUnit.SECONDS);
                lastException = null;
                break;
            } catch (TimeoutException e) {
                future.cancel(true);
                lastException = new Exception("Connection attempt timed out after 8s");
            } catch (ExecutionException e) {
                lastException = (Exception) e.getCause();
            } finally {
                ex.shutdownNow();
            }
            Thread.sleep(3_000);
        }
        if (lastException != null) {
            assumeTrue(false, "PostgreSQL not ready after 90s: " + lastException.getMessage());
        }

        // Create the test table once for all tests
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS " + TABLE
                    + " (id VARCHAR(64) PRIMARY KEY, name VARCHAR(255), age INT)");
            stmt.execute("DELETE FROM " + TABLE); // clean state between runs
        }
    }

    @AfterAll
    void teardown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS " + TABLE);
            }
            connection.close();
        }
    }

    @Test
    @DisplayName("createTableAndInsert: INSERT row, SELECT COUNT = 1")
    void createTableAndInsert() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO " + TABLE + " (id, name, age) VALUES ('ins-1', 'Alice', 30)");
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE id='ins-1'");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("updateRow: INSERT, UPDATE, SELECT verifies new value")
    void updateRow() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO " + TABLE + " (id, name, age) VALUES ('upd-1', 'Bob', 25)");
            stmt.execute("UPDATE " + TABLE + " SET age = 35 WHERE id = 'upd-1'");
            ResultSet rs = stmt.executeQuery("SELECT age FROM " + TABLE + " WHERE id = 'upd-1'");
            assertTrue(rs.next());
            assertEquals(35, rs.getInt("age"));
        }
    }

    @Test
    @DisplayName("deleteRow: INSERT, DELETE, SELECT COUNT = 0")
    void deleteRow() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO " + TABLE + " (id, name, age) VALUES ('del-1', 'Carol', 28)");
            stmt.execute("DELETE FROM " + TABLE + " WHERE id = 'del-1'");
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + TABLE + " WHERE id = 'del-1'");
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("queryWithWhere: INSERT 3 rows, SELECT WHERE age > 25 returns correct subset")
    void queryWithWhere() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("INSERT INTO " + TABLE + " (id, name, age) VALUES ('whr-1', 'Dave', 20)");
            stmt.execute("INSERT INTO " + TABLE + " (id, name, age) VALUES ('whr-2', 'Eve', 30)");
            stmt.execute("INSERT INTO " + TABLE + " (id, name, age) VALUES ('whr-3', 'Frank', 40)");

            ResultSet rs = stmt.executeQuery(
                    "SELECT id FROM " + TABLE + " WHERE age > 25 AND id LIKE 'whr-%' ORDER BY id");
            int count = 0;
            while (rs.next()) {
                count++;
                String id = rs.getString("id");
                assertTrue(id.equals("whr-2") || id.equals("whr-3"),
                        "Unexpected id: " + id);
            }
            assertEquals(2, count);
        }
    }
}
