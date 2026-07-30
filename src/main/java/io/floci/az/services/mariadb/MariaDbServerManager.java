package io.floci.az.services.mariadb;

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
 * Manages the Docker lifecycle of Azure Database for MariaDB containers.
 *
 * <p>Each logical server maps to one MariaDB Docker container.
 * Mirrors {@link io.floci.az.services.mysql.MySqlServerManager}.
 */
@ApplicationScoped
public class MariaDbServerManager {

    private static final Logger LOG = Logger.getLogger(MariaDbServerManager.class);

    private static final int MARIADB_CONTAINER_PORT = 3306;

    @Inject EmulatorConfig config;
    @Inject ContainerLifecycleManager containerManager;
    @Inject ContainerBuilder containerBuilder;
    @Inject ContainerDetector containerDetector;

    private final ConcurrentHashMap<String, String> managedContainers = new ConcurrentHashMap<>();

    public MariaDbState.ServerEntry startServer(MariaDbState.ServerEntry entry) {
        EmulatorConfig.MariaDbServiceConfig mariaConfig = config.services().mariaDb();
        String image = mariaConfig.image();

        String containerName = containerName(entry.serverName());
        containerManager.removeIfExists(containerName);

        LOG.infof("Starting MariaDB container: server=%s image=%s", entry.serverName(), image);

        ContainerSpec spec = containerBuilder.newContainer(image)
            .withName(containerName)
            .withDynamicPort(MARIADB_CONTAINER_PORT)
            .withDockerNetwork(config.services().dockerNetwork())
            .withEnv("MARIADB_ROOT_PASSWORD", entry.administratorLoginPassword())
            .withEnv("MARIADB_USER", entry.administratorLogin())
            .withEnv("MARIADB_PASSWORD", entry.administratorLoginPassword())
            .withEnv("MARIADB_DATABASE", "floci")
            .withLogRotation()
            .build();

        String containerId = containerManager.create(spec);
        containerManager.copyFileToContainer(containerId, grantAdminSql(entry.administratorLogin()),
            "/docker-entrypoint-initdb.d/10-grant-admin.sql");
        var info = containerManager.startCreated(containerId, spec);

        int hostPort = Optional.ofNullable(info.getEndpoint(MARIADB_CONTAINER_PORT))
            .map(ContainerLifecycleManager.EndpointInfo::port)
            .orElseThrow(() -> new RuntimeException(
                "Could not resolve host port for MariaDB container " + containerName));

        String reachableHost;
        int reachablePort;
        if (containerDetector.isRunningInContainer()) {
            reachableHost = containerName;
            reachablePort = MARIADB_CONTAINER_PORT;
        } else {
            reachableHost = "localhost";
            reachablePort = hostPort;
        }

        managedContainers.put(containerId, containerName);
        LOG.infof("MariaDB container started: server=%s containerId=%s endpoint=%s:%d",
            entry.serverName(), containerId, reachableHost, reachablePort);

        waitForReady(reachableHost, reachablePort, mariaConfig.startupTimeoutSeconds());
        LOG.infof("MariaDB server ready: server=%s endpoint=%s:%d",
            entry.serverName(), reachableHost, reachablePort);

        return entry.withContainer(containerId, reachablePort, reachableHost);
    }

    public void stopServer(MariaDbState.ServerEntry entry) {
        if (entry.containerId() == null) return;
        LOG.infof("Stopping MariaDB container: server=%s containerId=%s",
            entry.serverName(), entry.containerId());
        containerManager.stopAndRemove(entry.containerId(), null);
        managedContainers.remove(entry.containerId());
    }

    /**
     * The admin user created via MARIADB_USER only receives privileges on the init database,
     * but an Azure Database admin can create databases and manage grants. The image
     * entrypoint executes this on first init, before the server starts listening.
     */
    private static String grantAdminSql(String login) {
        String quoted = login.replace("'", "''");
        return "GRANT ALL PRIVILEGES ON *.* TO '" + quoted + "'@'%' WITH GRANT OPTION;\n"
             + "FLUSH PRIVILEGES;\n";
    }

    private void waitForReady(String host, int port, int timeoutSeconds) {
        LOG.infof("Waiting for MariaDB to be ready on %s:%d (timeout=%ds)…", host, port, timeoutSeconds);
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;

        while (System.currentTimeMillis() < deadline) {
            // The server speaks first in the MySQL/MariaDB protocol: a live server sends its
            // handshake packet immediately on accept. A bare TCP connect is not proof of
            // readiness — Docker's port proxy accepts connections long before the server
            // inside the container finishes first-time init and listens.
            try (Socket s = new Socket(host, port)) {
                s.setSoTimeout(2_000);
                if (s.getInputStream().read() != -1) {
                    LOG.infof("MariaDB ready: %s:%d", host, port);
                    return;
                }
            } catch (Exception e) {
                // not ready yet — retry until the deadline
            }
            sleep(1000);
        }

        LOG.warnf("MariaDB on %s:%d did not send a handshake within %ds — continuing anyway",
            host, port, timeoutSeconds);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for MariaDB", ie);
        }
    }

    private static String containerName(String serverName) {
        return "floci-az-mariadb-" + serverName.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }

    @PreDestroy
    void shutdown() {
        for (Map.Entry<String, String> e : managedContainers.entrySet()) {
            try {
                LOG.infof("Stopping MariaDB container on shutdown: %s", e.getValue());
                containerManager.stopAndRemove(e.getKey(), null);
            } catch (Exception ex) {
                LOG.warnf(ex, "Error stopping MariaDB container %s", e.getValue());
            }
        }
        managedContainers.clear();
    }
}
