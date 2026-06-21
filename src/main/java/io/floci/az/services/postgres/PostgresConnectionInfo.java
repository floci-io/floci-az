package io.floci.az.services.postgres;

/**
 * Aggregates connection string formats for a PostgreSQL flexible-server container.
 *
 * <p>Returned by the convenience endpoint
 * {@code GET /{account}-postgres/flexibleServers/{server}/connect} and embedded inside
 * server GET responses for developer convenience.
 *
 * <p>The real Azure REST API does <em>not</em> expose a connection-string endpoint —
 * clients assemble the string from {@code fullyQualifiedDomainName} and credentials.
 * This endpoint is a floci-az addition.
 *
 * <p>The stock {@code postgres} image does not serve TLS, so connection strings use
 * {@code sslmode=disable}. Azure enforces TLS in the cloud ({@code require_secure_transport=ON});
 * this is a deliberate local-only divergence documented in {@code docs/services/postgresql.md}.
 */
public record PostgresConnectionInfo(
        String host,
        int port,
        String jdbcUrl,
        String uri,
        String psql,
        String dotNet
) {
    /**
     * Builds connection strings for the given host/port and credentials.
     *
     * @param host      hostname — always {@code localhost} in single-node dev mode
     * @param port      the host port the PostgreSQL container is bound to
     * @param login     PostgreSQL admin login
     * @param password  PostgreSQL admin password
     * @param database  database name, or {@code null} / empty for the default {@code postgres} db
     */
    public static PostgresConnectionInfo of(String host, int port,
                                            String login, String password,
                                            String database) {
        String db = (database != null && !database.isBlank()) ? database : "postgres";

        String jdbc = String.format(
            "jdbc:postgresql://%s:%d/%s?user=%s&password=%s&sslmode=disable",
            host, port, db, login, password);

        String uri = String.format(
            "postgresql://%s:%s@%s:%d/%s?sslmode=disable",
            login, password, host, port, db);

        String psql = String.format(
            "psql \"host=%s port=%d dbname=%s user=%s password=%s sslmode=disable\"",
            host, port, db, login, password);

        String dotNet = String.format(
            "Host=%s;Port=%d;Database=%s;Username=%s;Password=%s;SSL Mode=Disable;",
            host, port, db, login, password);

        return new PostgresConnectionInfo(host, port, jdbc, uri, psql, dotNet);
    }
}
