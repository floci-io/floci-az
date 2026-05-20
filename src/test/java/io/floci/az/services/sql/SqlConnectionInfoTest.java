package io.floci.az.services.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SqlConnectionInfo} — verifies that all four connection
 * string formats are assembled correctly for different host/port/database combos.
 * No Quarkus, no Docker.
 */
@DisplayName("SqlConnectionInfo — connection string builder")
class SqlConnectionInfoTest {

    @Test
    @DisplayName("JDBC URL contains host, port, database, and TrustServerCertificate")
    void jdbcUrl() {
        SqlConnectionInfo info = SqlConnectionInfo.of("localhost", 1433, "sa", "Pass123!", "mydb");
        String jdbc = info.jdbcUrl();
        assertTrue(jdbc.startsWith("jdbc:sqlserver://localhost:1433"), "should start with jdbc:sqlserver://localhost:1433");
        assertTrue(jdbc.contains("databaseName=mydb"), "should include databaseName");
        assertTrue(jdbc.contains("trustServerCertificate=true"), "should trust server cert");
        assertTrue(jdbc.contains("encrypt=true"), "should use encrypt=true (azure-sql-edge requires TLS)");
    }

    @Test
    @DisplayName("ADO.NET connection string contains all key parts")
    void adoNetConnectionString() {
        SqlConnectionInfo info = SqlConnectionInfo.of("localhost", 14330, "sa", "Pass123!", "orders");
        String ado = info.adoNet();
        assertTrue(ado.contains("Server=tcp:localhost,14330"), "server/port");
        assertTrue(ado.contains("Initial Catalog=orders"), "database");
        assertTrue(ado.contains("User ID=sa"), "login");
        assertTrue(ado.contains("TrustServerCertificate=True"), "trust cert");
        assertTrue(ado.contains("Encrypt=True"), "encrypt=true (azure-sql-edge requires TLS)");
    }

    @Test
    @DisplayName("pyodbc string uses semicolon-separated key=value format")
    void pyodbc() {
        SqlConnectionInfo info = SqlConnectionInfo.of("localhost", 1433, "sa", "Pass123!", "mydb");
        String odbc = info.pyodbc();
        assertTrue(odbc.contains("SERVER=localhost,1433"), "server and port");
        assertTrue(odbc.contains("DATABASE=mydb"), "database");
        assertTrue(odbc.contains("UID=sa"), "user");
        assertTrue(odbc.contains("TrustServerCertificate=yes"), "trust cert");
    }

    @Test
    @DisplayName("EF Core string is compact and usable with UseSqlServer()")
    void efCore() {
        SqlConnectionInfo info = SqlConnectionInfo.of("localhost", 1433, "sa", "Pass123!", "mydb");
        String ef = info.efCore();
        assertTrue(ef.contains("Server=localhost,1433"), "server");
        assertTrue(ef.contains("Database=mydb"), "database");
        assertTrue(ef.contains("User Id=sa"), "login");
        assertTrue(ef.contains("TrustServerCertificate=True"), "trust cert");
    }

    @Test
    @DisplayName("null database defaults to master")
    void nullDatabaseDefaultsToMaster() {
        SqlConnectionInfo info = SqlConnectionInfo.of("localhost", 1433, "sa", "Pass123!", null);
        assertTrue(info.jdbcUrl().contains("databaseName=master"));
        assertTrue(info.adoNet().contains("Initial Catalog=master"));
    }

    @Test
    @DisplayName("blank database defaults to master")
    void blankDatabaseDefaultsToMaster() {
        SqlConnectionInfo info = SqlConnectionInfo.of("localhost", 1433, "sa", "Pass123!", "");
        assertTrue(info.jdbcUrl().contains("databaseName=master"));
    }

    @Test
    @DisplayName("host and port are stored correctly")
    void hostAndPort() {
        SqlConnectionInfo info = SqlConnectionInfo.of("myhost", 14999, "admin", "secret", "db");
        assertEquals("myhost", info.host());
        assertEquals(14999, info.port());
    }

    @Test
    @DisplayName("non-default port appears in all formats")
    void nonDefaultPort() {
        SqlConnectionInfo info = SqlConnectionInfo.of("localhost", 14567, "sa", "P@ssw0rd!", "app");
        assertTrue(info.jdbcUrl().contains("14567"), "jdbc should have non-default port");
        assertTrue(info.adoNet().contains("14567"),  "ado.net should have non-default port");
        assertTrue(info.pyodbc().contains("14567"),  "pyodbc should have non-default port");
        assertTrue(info.efCore().contains("14567"),  "ef core should have non-default port");
    }
}
