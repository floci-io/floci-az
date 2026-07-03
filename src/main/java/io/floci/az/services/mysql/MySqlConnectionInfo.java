package io.floci.az.services.mysql;

/**
 * Aggregates connection string formats for an Azure Database for MySQL container.
 *
 * <p>Returned by the convenience endpoint
 * {@code GET /{account}-mysql/flexibleServers/{server}/connect} and embedded inside
 * server GET responses for developer convenience.
 *
 * <p>The real Azure REST API does <em>not</em> expose a connection-string endpoint —
 * clients assemble the string from {@code fullyQualifiedDomainName} and credentials.
 * This endpoint is a floci-az addition.
 */
public record MySqlConnectionInfo(
        String host,
        int port,
        String jdbcUrl,
        String uri,
        String mysql,
        String dotNet
) {
    /**
     * Builds connection strings for the given host/port and credentials.
     *
     * @param host      hostname
     * @param port      the host port the MySQL container is bound to
     * @param login     MySQL admin login
     * @param password  MySQL admin password
     * @param database  database name, or {@code null} / empty for the default database
     */
    public static MySqlConnectionInfo of(String host, int port,
                                         String login, String password,
                                         String database) {
        String db = (database != null && !database.isBlank()) ? database : "mysql";

        String jdbc = String.format(
            "jdbc:mysql://%s:%d/%s?user=%s&password=%s&useSSL=false&allowPublicKeyRetrieval=true",
            host, port, db, login, password);

        String uri = String.format(
            "mysql://%s:%s@%s:%d/%s",
            login, password, host, port, db);

        String mysql = String.format(
            "mysql -h %s -P %d -u %s -p%s %s",
            host, port, login, password, db);

        String dotNet = String.format(
            "Server=%s;Port=%d;Database=%s;Uid=%s;Pwd=%s;SslMode=none;",
            host, port, db, login, password);

        return new MySqlConnectionInfo(host, port, jdbc, uri, mysql, dotNet);
    }
}
