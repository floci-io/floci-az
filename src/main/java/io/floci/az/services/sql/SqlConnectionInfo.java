package io.floci.az.services.sql;

/**
 * Aggregates all connection string formats for a SQL Server container.
 *
 * <p>Returned by the convenience endpoint
 * {@code GET /{account}-sql/servers/{server}/connect} and embedded inside
 * server/database GET responses for developer convenience.
 *
 * <p>The real Azure SQL REST API does <em>not</em> expose a connection-string
 * endpoint — clients assemble the string from {@code fullyQualifiedDomainName}
 * and credentials.  This endpoint is a floci-az addition.
 */
public record SqlConnectionInfo(
        String host,
        int port,
        String jdbcUrl,
        String adoNet,
        String pyodbc,
        String efCore
) {
    /**
     * Builds connection strings for the given host/port and credentials.
     *
     * @param host      hostname — always {@code localhost} in single-node dev mode
     * @param port      the host port that the SQL Server container is bound to
     * @param login     SQL Server login (typically {@code sa})
     * @param password  SQL Server password
     * @param database  database name, or {@code null} / empty for server-level strings
     */
    public static SqlConnectionInfo of(String host, int port,
                                       String login, String password,
                                       String database) {
        String db = (database != null && !database.isBlank()) ? database : "master";

        // encrypt=true + trustServerCertificate=true: use the mssql-jdbc 12.x secure default
        // while accepting the self-signed SQL Server container certificate without validating
        // the chain.
        String jdbc = String.format(
            "jdbc:sqlserver://%s:%d;databaseName=%s;user=%s;password=%s;"
            + "encrypt=true;trustServerCertificate=true;",
            host, port, db, login, password);

        String ado = String.format(
            "Server=tcp:%s,%d;Initial Catalog=%s;Persist Security Info=False;"
            + "User ID=%s;Password=%s;MultipleActiveResultSets=False;"
            + "Encrypt=True;TrustServerCertificate=True;Connection Timeout=30;",
            host, port, db, login, password);

        String odbc = String.format(
            "DRIVER={ODBC Driver 18 for SQL Server};SERVER=%s,%d;"
            + "DATABASE=%s;UID=%s;PWD=%s;"
            + "TrustServerCertificate=yes;Encrypt=yes;",
            host, port, db, login, password);

        String ef = String.format(
            "Server=%s,%d;Database=%s;User Id=%s;Password=%s;"
            + "Encrypt=True;TrustServerCertificate=True;",
            host, port, db, login, password);

        return new SqlConnectionInfo(host, port, jdbc, ado, odbc, ef);
    }
}
