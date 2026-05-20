package io.floci.az.services.sql;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the Docker lifecycle of Azure SQL Server containers.
 *
 * <p>Each logical server maps to one {@code azure-sql-edge} (or configurable)
 * Docker container.  Containers are started on-demand when the first request
 * arrives for a given server name, following the same pattern used by the
 * Cosmos DB engine providers.
 *
 * <p>DDL operations (CREATE DATABASE / DROP DATABASE) are executed via JDBC
 * using the bundled {@code mssql-jdbc} driver.  This approach is portable
 * across all SQL Server images — {@code sqlcmd} is NOT included in
 * {@code azure-sql-edge} and is therefore unreliable as a DDL mechanism.
 */
@ApplicationScoped
public class SqlServerManager {

    private static final Logger LOG = Logger.getLogger(SqlServerManager.class);

    private static final int SQL_CONTAINER_PORT = 1433;

    /** JDBC connection string template used for internal DDL execution. */
    private static final String DDL_JDBC_TEMPLATE =
        "jdbc:sqlserver://localhost:%d;user=sa;password=%s;"
        + "encrypt=true;trustServerCertificate=true;"
        + "loginTimeout=10;";

    @Inject EmulatorConfig config;
    @Inject ContainerLifecycleManager containerManager;
    @Inject ContainerBuilder containerBuilder;

    /** containerId → container name, for cleanup on shutdown. */
    private final ConcurrentHashMap<String, String> managedContainers = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts a SQL Server container for the given server entry and returns the
     * updated entry with {@code containerId} and {@code hostPort} populated.
     *
     * @throws IllegalStateException if the EULA has not been accepted
     * @throws RuntimeException      if the container fails to start or become ready
     */
    public SqlState.SqlServerEntry startServer(SqlState.SqlServerEntry entry) {
        requireEulaAccepted();

        EmulatorConfig.SqlServiceConfig sqlConfig = config.services().sql();
        String image = sqlConfig.image();
        String password = entry.administratorLoginPassword();

        String containerName = containerName(entry.serverName());
        containerManager.removeIfExists(containerName);

        LOG.infof("Starting SQL Server container: server=%s image=%s", entry.serverName(), image);

        ContainerSpec spec = containerBuilder.newContainer(image)
            .withName(containerName)
            .withDynamicPort(SQL_CONTAINER_PORT)   // OS picks host port
            .withEnv("ACCEPT_EULA", "Y")
            .withEnv("MSSQL_SA_PASSWORD", password)
            .withEnv("SA_PASSWORD", password)       // azure-sql-edge uses SA_PASSWORD
            .withLogRotation()
            .build();

        var info = containerManager.createAndStart(spec);
        String containerId = info.containerId();

        int hostPort = Optional.ofNullable(info.getEndpoint(SQL_CONTAINER_PORT))
            .map(ContainerLifecycleManager.EndpointInfo::port)
            .orElseThrow(() -> new RuntimeException(
                "Could not resolve host port for SQL Server container " + containerName));

        managedContainers.put(containerId, containerName);
        LOG.infof("SQL Server container started: server=%s containerId=%s port=%d",
            entry.serverName(), containerId, hostPort);

        waitForReady(hostPort, password, sqlConfig.startupTimeoutSeconds());
        LOG.infof("SQL Server ready: server=%s port=%d", entry.serverName(), hostPort);

        return entry.withContainer(containerId, hostPort);
    }

    /**
     * Stops and removes the container associated with the given server entry.
     */
    public void stopServer(SqlState.SqlServerEntry entry) {
        if (entry.containerId() == null) return;
        LOG.infof("Stopping SQL Server container: server=%s containerId=%s",
            entry.serverName(), entry.containerId());
        containerManager.stopAndRemove(entry.containerId(), null);
        managedContainers.remove(entry.containerId());
    }

    /**
     * Executes {@code CREATE DATABASE [{dbName}] COLLATE {collation}} via JDBC.
     */
    public void createDatabase(SqlState.SqlServerEntry server, String dbName, String collation) {
        String safe = dbName.replace("]", "]]");
        String col  = (collation != null && !collation.isBlank())
            ? collation : "SQL_Latin1_General_CP1_CI_AS";

        String sql = String.format("CREATE DATABASE [%s] COLLATE %s", safe, col);
        execDdl(server, sql);
        LOG.infof("Created database: server=%s db=%s", server.serverName(), dbName);
    }

    /**
     * Executes {@code DROP DATABASE [{dbName}]} via JDBC.
     * The {@code master} database cannot be dropped.
     */
    public void dropDatabase(SqlState.SqlServerEntry server, String dbName) {
        if ("master".equalsIgnoreCase(dbName)) {
            throw new IllegalArgumentException("Cannot drop the master database");
        }
        // Kick out any open connections before dropping
        String safe = dbName.replace("]", "]]");
        String killSql = String.format(
            "ALTER DATABASE [%s] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [%s]",
            safe, safe);
        execDdl(server, killSql);
        LOG.infof("Dropped database: server=%s db=%s", server.serverName(), dbName);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void requireEulaAccepted() {
        String eula = config.services().sql().acceptEula();
        if (!"Y".equalsIgnoreCase(eula)) {
            throw new EulaNotAcceptedException(
                "You must set FLOCI_AZ_SERVICES_SQL_ACCEPT_EULA=Y to accept the "
                + "Microsoft SQL Server End-User License Agreement (EULA) before "
                + "using the Azure SQL Database service. "
                + "See: https://go.microsoft.com/fwlink/?linkid=857698");
        }
    }

    /**
     * Polls the SQL Server port until it accepts JDBC connections (not just TCP),
     * then confirms the engine is ready by running a lightweight query.
     */
    private void waitForReady(int port, String password, int timeoutSeconds) {
        LOG.infof("Waiting for SQL Server to be ready on port %d (timeout=%ds)…", port, timeoutSeconds);
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;

        // Phase 1 — wait for the port to accept TCP connections
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket("localhost", port)) {
                break; // port is open
            } catch (Exception e) {
                sleep(1000);
            }
        }

        // Phase 2 — wait for JDBC login to succeed (SQL Server initialises after TCP opens)
        String jdbcUrl = String.format(DDL_JDBC_TEMPLATE, port, password);
        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                LOG.infof("SQL Server JDBC ready on port %d", port);
                return; // engine is up and accepting logins
            } catch (Exception e) {
                LOG.debugf("SQL Server not ready yet (port=%d): %s", port, e.getMessage());
                sleep(2000);
            }
        }

        throw new RuntimeException(
            "SQL Server did not become ready within " + timeoutSeconds + " seconds on port " + port);
    }

    /**
     * Executes a DDL statement via JDBC against the server's master database.
     * Uses the SA credentials stored in the server entry.
     */
    private void execDdl(SqlState.SqlServerEntry server, String sql) {
        String jdbcUrl = String.format(DDL_JDBC_TEMPLATE,
            server.hostPort(), server.administratorLoginPassword());

        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement  st   = conn.createStatement()) {
            // Some DDL (e.g. ALTER DATABASE) cannot run inside a transaction
            conn.setAutoCommit(true);
            st.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException(
                "DDL failed on server=" + server.serverName() + " sql=[" + sql + "]", e);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for SQL Server", ie);
        }
    }

    private static String containerName(String serverName) {
        return "floci-az-sql-" + serverName.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }

    @PreDestroy
    void shutdown() {
        for (Map.Entry<String, String> e : managedContainers.entrySet()) {
            try {
                LOG.infof("Stopping SQL Server container on shutdown: %s", e.getValue());
                containerManager.stopAndRemove(e.getKey(), null);
            } catch (Exception ex) {
                LOG.warnf(ex, "Error stopping SQL container %s", e.getValue());
            }
        }
        managedContainers.clear();
    }

    /** Thrown when the SQL Server EULA has not been explicitly accepted. */
    public static class EulaNotAcceptedException extends RuntimeException {
        public EulaNotAcceptedException(String message) { super(message); }
    }
}
