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
            .withEnv("MYSQL_DATABASE", "mysql")
            .withLogRotation()
            .build();

        var info = containerManager.createAndStart(spec);
        String containerId = info.containerId();

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

    private void waitForReady(String host, int port, int timeoutSeconds) {
        LOG.infof("Waiting for MySQL to be ready on %s:%d (timeout=%ds)…", host, port, timeoutSeconds);
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;

        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket(host, port)) {
                LOG.infof("MySQL TCP %s:%d is open — waiting for engine init…", host, port);
                break;
            } catch (Exception e) {
                sleep(1000);
            }
        }

        long postTcpMs = Math.min(5_000L, deadline - System.currentTimeMillis());
        if (postTcpMs > 0) {
            sleep(postTcpMs);
        }

        LOG.infof("MySQL ready: %s:%d", host, port);
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
