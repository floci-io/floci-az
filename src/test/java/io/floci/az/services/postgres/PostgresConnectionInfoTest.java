package io.floci.az.services.postgres;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PostgresConnectionInfo} — verifies that all connection string
 * formats are assembled correctly for different host/port/database combos. No Quarkus,
 * no Docker.
 */
@DisplayName("PostgresConnectionInfo — connection string builder")
class PostgresConnectionInfoTest {

    @Test
    @DisplayName("JDBC URL contains host, port, database and sslmode=disable")
    void jdbcUrl() {
        PostgresConnectionInfo info = PostgresConnectionInfo.of("localhost", 5432, "psqladmin", "Pass123!", "mydb");
        String jdbc = info.jdbcUrl();
        assertTrue(jdbc.startsWith("jdbc:postgresql://localhost:5432/mydb"), "jdbc prefix + db");
        assertTrue(jdbc.contains("user=psqladmin"), "login");
        assertTrue(jdbc.contains("sslmode=disable"), "stock postgres serves no TLS locally");
    }

    @Test
    @DisplayName("libpq URI contains credentials, host, port and database")
    void uri() {
        PostgresConnectionInfo info = PostgresConnectionInfo.of("localhost", 15432, "psqladmin", "Pass123!", "orders");
        String uri = info.uri();
        assertTrue(uri.startsWith("postgresql://psqladmin:Pass123!@localhost:15432/orders"), "uri shape");
        assertTrue(uri.contains("sslmode=disable"), "sslmode");
    }

    @Test
    @DisplayName("psql command-line string contains all key parts")
    void psql() {
        PostgresConnectionInfo info = PostgresConnectionInfo.of("localhost", 5432, "psqladmin", "Pass123!", "mydb");
        String psql = info.psql();
        assertTrue(psql.contains("host=localhost"), "host");
        assertTrue(psql.contains("port=5432"), "port");
        assertTrue(psql.contains("dbname=mydb"), "database");
        assertTrue(psql.contains("user=psqladmin"), "user");
    }

    @Test
    @DisplayName(".NET (Npgsql) string is usable with NpgsqlConnection")
    void dotNet() {
        PostgresConnectionInfo info = PostgresConnectionInfo.of("localhost", 5432, "psqladmin", "Pass123!", "mydb");
        String net = info.dotNet();
        assertTrue(net.contains("Host=localhost"), "host");
        assertTrue(net.contains("Port=5432"), "port");
        assertTrue(net.contains("Database=mydb"), "database");
        assertTrue(net.contains("Username=psqladmin"), "username");
        assertTrue(net.contains("SSL Mode=Disable"), "ssl mode");
    }

    @Test
    @DisplayName("null database defaults to postgres")
    void nullDatabaseDefaultsToPostgres() {
        PostgresConnectionInfo info = PostgresConnectionInfo.of("localhost", 5432, "psqladmin", "Pass123!", null);
        assertTrue(info.jdbcUrl().contains("/postgres"));
        assertTrue(info.uri().contains("/postgres"));
    }

    @Test
    @DisplayName("blank database defaults to postgres")
    void blankDatabaseDefaultsToPostgres() {
        PostgresConnectionInfo info = PostgresConnectionInfo.of("localhost", 5432, "psqladmin", "Pass123!", "");
        assertTrue(info.jdbcUrl().contains("/postgres"));
    }

    @Test
    @DisplayName("host and port are stored correctly")
    void hostAndPort() {
        PostgresConnectionInfo info = PostgresConnectionInfo.of("myhost", 15999, "admin", "secret", "db");
        assertEquals("myhost", info.host());
        assertEquals(15999, info.port());
    }

    @Test
    @DisplayName("non-default port appears in all formats")
    void nonDefaultPort() {
        PostgresConnectionInfo info = PostgresConnectionInfo.of("localhost", 15567, "psqladmin", "P@ssw0rd!", "app");
        assertTrue(info.jdbcUrl().contains("15567"), "jdbc port");
        assertTrue(info.uri().contains("15567"),     "uri port");
        assertTrue(info.psql().contains("15567"),    "psql port");
        assertTrue(info.dotNet().contains("15567"),  "dotnet port");
    }
}
