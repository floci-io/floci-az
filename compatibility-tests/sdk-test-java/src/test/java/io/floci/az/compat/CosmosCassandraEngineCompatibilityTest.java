package io.floci.az.compat;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compatibility tests for the Cosmos Cassandra engine (cosmos-cassandra).
 *
 * <p>Tests are skipped automatically when the Cassandra engine is not enabled in floci-az config.
 * They require Docker and FLOCI_AZ_SERVICES_COSMOS_ENGINES_CASSANDRA_ENABLED=true.
 * ScyllaDB can take up to 60s to become fully ready.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Cosmos Cassandra Engine Compatibility")
class CosmosCassandraEngineCompatibilityTest {

    private CqlSession session;
    private static final String KEYSPACE = "test_ks";

    @BeforeAll
    void setup() throws InterruptedException {
        Map<String, Object> engineInfo = EmulatorConfig.triggerCosmosEngine("cosmos-cassandra");
        assumeTrue(engineInfo != null, "Cassandra engine not enabled — skipping tests");

        String host = (String) engineInfo.get("host");
        int port = ((Number) engineInfo.get("port")).intValue();

        // Retry connection up to 60s — ScyllaDB takes longer to boot
        Exception lastException = null;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                CqlSession candidate = CqlSession.builder()
                        .addContactPoint(new InetSocketAddress(host, port))
                        .withLocalDatacenter("datacenter1")
                        .build();
                // Quick smoke-test query
                candidate.execute("SELECT release_version FROM system.local");
                session = candidate;
                lastException = null;
                break;
            } catch (Exception e) {
                lastException = e;
                if (session != null) {
                    try { session.close(); } catch (Exception ignored) {}
                    session = null;
                }
                Thread.sleep(2_000);
            }
        }
        if (lastException != null) {
            assumeTrue(false, "Cassandra/ScyllaDB not ready after 60s: " + lastException.getMessage());
        }

        // Create keyspace and table for all tests
        session.execute(
                "CREATE KEYSPACE IF NOT EXISTS " + KEYSPACE
                + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
        session.execute(
                "CREATE TABLE IF NOT EXISTS " + KEYSPACE + ".users "
                + "(id UUID PRIMARY KEY, name TEXT, age INT)");
        // Clean state between runs
        session.execute("TRUNCATE " + KEYSPACE + ".users");
    }

    @AfterAll
    void teardown() {
        if (session != null && !session.isClosed()) {
            session.execute("DROP KEYSPACE IF EXISTS " + KEYSPACE);
            session.close();
        }
    }

    @Test
    @DisplayName("insertAndSelect: INSERT row, SELECT by primary key, verify fields")
    void insertAndSelect() {
        UUID id = UUID.randomUUID();
        session.execute(
                "INSERT INTO " + KEYSPACE + ".users (id, name, age) VALUES (?, ?, ?)",
                id, "Alice", 30);

        Row row = session.execute(
                "SELECT * FROM " + KEYSPACE + ".users WHERE id = ?", id).one();
        assertNotNull(row, "Row should be found");
        assertEquals("Alice", row.getString("name"));
        assertEquals(30, row.getInt("age"));
    }

    @Test
    @DisplayName("updateRow: INSERT, UPDATE SET, SELECT verifies updated value")
    void updateRow() {
        UUID id = UUID.randomUUID();
        session.execute(
                "INSERT INTO " + KEYSPACE + ".users (id, name, age) VALUES (?, ?, ?)",
                id, "Bob", 25);

        session.execute(
                "UPDATE " + KEYSPACE + ".users SET age = 35 WHERE id = ?", id);

        Row row = session.execute(
                "SELECT age FROM " + KEYSPACE + ".users WHERE id = ?", id).one();
        assertNotNull(row);
        assertEquals(35, row.getInt("age"));
    }

    @Test
    @DisplayName("deleteRow: INSERT, DELETE, SELECT returns null")
    void deleteRow() {
        UUID id = UUID.randomUUID();
        session.execute(
                "INSERT INTO " + KEYSPACE + ".users (id, name, age) VALUES (?, ?, ?)",
                id, "Carol", 28);

        session.execute("DELETE FROM " + KEYSPACE + ".users WHERE id = ?", id);

        Row row = session.execute(
                "SELECT * FROM " + KEYSPACE + ".users WHERE id = ?", id).one();
        assertNull(row, "Row should be deleted");
    }

    @Test
    @DisplayName("selectAll: INSERT 3 rows, SELECT * returns at least 3")
    void selectAll() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        session.execute("INSERT INTO " + KEYSPACE + ".users (id, name, age) VALUES (?, ?, ?)", id1, "Dave", 20);
        session.execute("INSERT INTO " + KEYSPACE + ".users (id, name, age) VALUES (?, ?, ?)", id2, "Eve", 22);
        session.execute("INSERT INTO " + KEYSPACE + ".users (id, name, age) VALUES (?, ?, ?)", id3, "Frank", 24);

        ResultSet rs = session.execute("SELECT * FROM " + KEYSPACE + ".users");
        List<Row> rows = rs.all();
        assertTrue(rows.size() >= 3, "Expected at least 3 rows, got " + rows.size());
    }
}
