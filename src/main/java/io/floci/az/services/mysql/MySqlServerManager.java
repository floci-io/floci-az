package io.floci.az.services.mysql;

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
 * Manages the Docker lifecycle of Azure Database for MySQL (Flexible Server) containers.
 *
 * <p>Each logical flexible server maps to one MySQL Docker container.
 * Mirrors {@link io.floci.az.services.postgres.PostgresServerManager}.
 */
@ApplicationScoped
public class MySqlServerManager {

    private static final Logger LOG = Logger.getLogger(MySqlServerManager.class);

    private static final int MYSQL_CONTAINER_PORT = 3306;

    @Inject EmulatorConfig config;
    @Inject ContainerLifecycleManager containerManager;
    @Inject ContainerBuilder containerBuilder;
    @Inject ContainerDetector containerDetector;

    private final ConcurrentHashMap<String, String> managedContainers = new ConcurrentHashMap<>();

    public MySqlState.ServerEntry startServer(MySqlState.ServerEntry entry) {
        EmulatorConfig.MySqlServiceConfig mysqlConfig = config.services().mysql();
        String image = mysqlConfig.image();

        String containerName = containerName(entry.serverName());
        containerManager.removeIfExists(containerName);

        LOG.infof("Starting MySQL container: server=%s image=%s", entry.serverName(), image);

        ContainerSpec spec = containerBuilder.newContainer(image)
            .withName(containerName)
            .withDynamicPort(MYSQL_CONTAINER_PORT)
            .withDockerNetwork(config.services().dockerNetwork())
            .withEnv("MYSQL_ROOT_PASSWORD", entry.administratorLoginPassword())
            .withEnv("MYSQL_USER", entry.administratorLogin())
            .withEnv("MYSQL_PASSWORD", entry.administratorLoginPassword())
            .withEnv("MYSQL_DATABASE", "floci")
            .withLogRotation()
            .build();

        String containerId = containerManager.create(spec);
        containerManager.copyFileToContainer(containerId, grantAdminSql(entry.administratorLogin()),
            "/docker-entrypoint-initdb.d/10-grant-admin.sql");
        var info = containerManager.startCreated(containerId, spec);

        int hostPort = Optional.ofNullable(info.getEndpoint(MYSQL_CONTAINER_PORT))
            .map(ContainerLifecycleManager.EndpointInfo::port)
            .orElseThrow(() -> new RuntimeException(
                "Could not resolve host port for MySQL container " + containerName));

        String reachableHost;
        int reachablePort;
        if (containerDetector.isRunningInContainer()) {
            reachableHost = containerName;
            reachablePort = MYSQL_CONTAINER_PORT;
        } else {
            reachableHost = "localhost";
            reachablePort = hostPort;
        }

        managedContainers.put(containerId, containerName);
        LOG.infof("MySQL container started: server=%s containerId=%s endpoint=%s:%d",
            entry.serverName(), containerId, reachableHost, reachablePort);

        waitForReady(reachableHost, reachablePort, mysqlConfig.startupTimeoutSeconds());
        LOG.infof("MySQL server ready: server=%s endpoint=%s:%d",
            entry.serverName(), reachableHost, reachablePort);

        return entry.withContainer(containerId, reachablePort, reachableHost);
    }

    public void stopServer(MySqlState.ServerEntry entry) {
        if (entry.containerId() == null) return;
        LOG.infof("Stopping MySQL container: server=%s containerId=%s",
            entry.serverName(), entry.containerId());
        containerManager.stopAndRemove(entry.containerId(), null);
        managedContainers.remove(entry.containerId());
    }

    /**
     * The admin user created via MYSQL_USER only receives privileges on the init database,
     * but an Azure Database admin can create databases and manage grants. The image
     * entrypoint executes this on first init, before the server starts listening.
     */
    private static String grantAdminSql(String login) {
        String quoted = login.replace("'", "''");
        return "GRANT ALL PRIVILEGES ON *.* TO '" + quoted + "'@'%' WITH GRANT OPTION;\n"
             + "FLUSH PRIVILEGES;\n";
    }

    private void waitForReady(String host, int port, int timeoutSeconds) {
        LOG.infof("Waiting for MySQL to be ready on %s:%d (timeout=%ds)…", host, port, timeoutSeconds);
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;

        while (System.currentTimeMillis() < deadline) {
            // The server speaks first in the MySQL protocol: a live mysqld sends its handshake
            // packet immediately on accept. A bare TCP connect is not proof of readiness —
            // Docker's port proxy accepts connections long before mysqld inside the container
            // finishes first-time init and listens.
            try (Socket s = new Socket(host, port)) {
                s.setSoTimeout(2_000);
                if (s.getInputStream().read() != -1) {
                    LOG.infof("MySQL ready: %s:%d", host, port);
                    return;
                }
            } catch (Exception e) {
                // not ready yet — retry until the deadline
            }
            sleep(1000);
        }

        // Callers surface this as a failed create (500 + state rollback) — a server that never
        // spoke the protocol must not be reported Ready.
        throw new RuntimeException(String.format(
            "MySQL on %s:%d did not send a handshake within %ds", host, port, timeoutSeconds));
    }

    /**
     * Rotates the admin password inside the live container via ALTER USER, so credential
     * rotation through ARM keeps the data plane reachable. The root password is rotated in
     * the same batch: it starts equal to the create-time admin password, and keeping the two
     * in lockstep lets every future rotation authenticate with the state's current password.
     */
    public void rotateAdminPassword(MySqlState.ServerEntry entry, String newPassword) {
        String sql = "ALTER USER '" + sqlQuote(entry.administratorLogin()) + "'@'%' IDENTIFIED BY '"
                + sqlQuote(newPassword) + "'; "
                + "ALTER USER 'root'@'localhost' IDENTIFIED BY '" + sqlQuote(newPassword) + "'; "
                + "FLUSH PRIVILEGES;";
        ContainerLifecycleManager.ExecResult result = containerManager.execInContainer(
            entry.containerId(), "mysql", "-uroot", "-p" + entry.administratorLoginPassword(), "-e", sql);
        if (result.exitCode() != 0) {
            throw new RuntimeException("Password rotation failed for MySQL server '"
                + entry.serverName() + "': " + result.output());
        }
        LOG.infof("Rotated admin password for MySQL server %s", entry.serverName());
    }

    private static String sqlQuote(String s) {
        return s.replace("\\", "\\\\").replace("'", "''");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for MySQL", ie);
        }
    }

    private static String containerName(String serverName) {
        return "floci-az-mysql-" + serverName.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }

    @PreDestroy
    void shutdown() {
        for (Map.Entry<String, String> e : managedContainers.entrySet()) {
            try {
                LOG.infof("Stopping MySQL container on shutdown: %s", e.getValue());
                containerManager.stopAndRemove(e.getKey(), null);
            } catch (Exception ex) {
                LOG.warnf(ex, "Error stopping MySQL container %s", e.getValue());
            }
        }
        managedContainers.clear();
    }
}
