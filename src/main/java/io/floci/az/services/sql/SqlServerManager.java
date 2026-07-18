package io.floci.az.services.sql;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.Socket;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the Docker lifecycle of Azure SQL Server containers.
 *
 * <p>Each logical server maps to one SQL Server container. Containers are
 * started on-demand when the first request
 * arrives for a given server name, following the same pattern used by the
 * Cosmos DB engine providers.
 *
 * <p>This class is intentionally free of JDBC / DDL logic.  Database creation
 * and schema migrations are the responsibility of the application (Flyway,
 * Liquibase, EF Core, etc.) — the emulator only manages container lifecycle
 * and tracks resource metadata in {@link SqlState}.
 */
@ApplicationScoped
public class SqlServerManager {

    private static final Logger LOG = Logger.getLogger(SqlServerManager.class);

    private static final int SQL_CONTAINER_PORT = 1433;

    @Inject EmulatorConfig config;
    @Inject ContainerLifecycleManager containerManager;
    @Inject ContainerBuilder containerBuilder;
    @Inject ContainerDetector containerDetector;

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
            .withDynamicPort(SQL_CONTAINER_PORT)   // OS picks host port (used for host networking)
            .withDockerNetwork(config.services().dockerNetwork())  // join the shared network when running in Docker
            .withEnv("ACCEPT_EULA", "Y")
            .withEnv("MSSQL_SA_PASSWORD", password)
            .withLogRotation()
            .build();

        var info = containerManager.createAndStart(spec);
        String containerId = info.containerId();

        int hostPort = Optional.ofNullable(info.getEndpoint(SQL_CONTAINER_PORT))
            .map(ContainerLifecycleManager.EndpointInfo::port)
            .orElseThrow(() -> new RuntimeException(
                "Could not resolve host port for SQL Server container " + containerName));

        // Pick the address an application can actually reach (mirrors RedisCacheManager):
        // when floci-az runs inside a container, clients on the shared Docker network reach the
        // sidecar by its container name on the container port; otherwise via localhost:hostPort.
        String reachableHost;
        int reachablePort;
        if (containerDetector.isRunningInContainer()) {
            reachableHost = containerName;
            reachablePort = SQL_CONTAINER_PORT;
        } else {
            reachableHost = "localhost";
            reachablePort = hostPort;
        }

        managedContainers.put(containerId, containerName);
        LOG.infof("SQL Server container started: server=%s containerId=%s endpoint=%s:%d",
            entry.serverName(), containerId, reachableHost, reachablePort);

        waitForReady(reachableHost, reachablePort, sqlConfig.startupTimeoutSeconds());
        LOG.infof("SQL Server ready: server=%s endpoint=%s:%d", entry.serverName(), reachableHost, reachablePort);

        return entry.withContainer(containerId, reachablePort, reachableHost);
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
     * Polls the SQL Server port until it accepts TCP connections, then waits a
     * short additional period to allow the engine to finish internal initialisation.
     *
     * <p>SQL Server opens its TDS port before it is fully ready to authenticate
     * clients.  A brief post-TCP sleep covers the remaining boot window without
     * requiring a JDBC driver on the main runtime classpath.
     */
    private void waitForReady(String host, int port, int timeoutSeconds) {
        LOG.infof("Waiting for SQL Server to be ready on %s:%d (timeout=%ds)…", host, port, timeoutSeconds);
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;

        // Phase 1 — wait for the port to accept TCP connections
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket(host, port)) {
                LOG.infof("SQL Server TCP %s:%d is open — waiting for engine init…", host, port);
                break;
            } catch (Exception e) {
                sleep(1000);
            }
        }

        // Phase 2 — give the engine a moment to finish startup after the port opens.
        // SQL Server initialises system databases after TCP becomes available; a fixed
        // post-TCP sleep is sufficient since clients use their own retry/timeout logic.
        long postTcpMs = Math.min(10_000L, deadline - System.currentTimeMillis());
        if (postTcpMs > 0) {
            sleep(postTcpMs);
        }

        LOG.infof("SQL Server ready: %s:%d", host, port);
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
