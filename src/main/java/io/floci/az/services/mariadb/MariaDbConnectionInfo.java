package io.floci.az.services.mariadb;

/**
 * Aggregates connection string formats for an Azure Database for MariaDB container.
 *
 * <p>Returned by the convenience endpoint
 * {@code GET /{account}-mariadb/servers/{server}/connect} and embedded inside
 * server GET responses for developer convenience.
 *
 * <p>The real Azure REST API does <em>not</em> expose a connection-string endpoint.
 * This endpoint is a floci-az addition.
 */
public record MariaDbConnectionInfo(
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
     * @param port      the host port the MariaDB container is bound to
     * @param login     MariaDB admin login
     * @param password  MariaDB admin password
     * @param database  database name, or {@code null} / empty for the default database
     */
    public static MariaDbConnectionInfo of(String host, int port,
                                            String login, String password,
                                            String database) {
        String db = (database != null && !database.isBlank()) ? database : "floci";

        String jdbc = String.format(
            "jdbc:mariadb://%s:%d/%s?user=%s&password=%s&useSSL=false",
            host, port, db, login, password);

        String uri = String.format(
            "mariadb://%s:%s@%s:%d/%s",
            login, password, host, port, db);

        String mysql = String.format(
            "mysql -h %s -P %d -u %s -p%s %s",
            host, port, login, password, db);

        String dotNet = String.format(
            "Server=%s;Port=%d;Database=%s;Uid=%s;Pwd=%s;SslMode=none;",
            host, port, db, login, password);

        return new MariaDbConnectionInfo(host, port, jdbc, uri, mysql, dotNet);
    }
}
