package io.floci.az.services.postgres;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerStorageHelper;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.PortAllocator;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.Socket;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the Docker lifecycle of Azure Database for PostgreSQL (Flexible Server)
 * containers.
 *
 * <p>Each logical flexible server maps to one {@code postgres} (or configurable)
 * Docker container. Containers are started on-demand when the first create request
 * arrives, mirroring {@code SqlServerManager}.
 *
 * <p>Unlike Azure SQL there is <em>no EULA</em>: the {@code postgres} image is
 * PostgreSQL-licensed.
 *
 * <p>This class is intentionally free of SQL/DDL logic. Database creation and schema
 * migrations are the responsibility of the application (Flyway, Liquibase, EF Core,
 * etc.) — the emulator only manages container lifecycle and tracks resource metadata
 * in {@link PostgresState}.
 */
@ApplicationScoped
public class PostgresServerManager {

    private static final Logger LOG = Logger.getLogger(PostgresServerManager.class);

    private static final int PG_CONTAINER_PORT = 5432;

    @Inject EmulatorConfig config;
    @Inject ContainerLifecycleManager containerManager;
    @Inject ContainerBuilder containerBuilder;
    @Inject ContainerDetector containerDetector;
    @Inject PortAllocator portAllocator;

    /** containerId → container name, for cleanup on shutdown. */
    private final ConcurrentHashMap<String, String> managedContainers = new ConcurrentHashMap<>();

    /** containerId -> the fixed host port claimed for it, so the claim is released with the container. */
    private final ConcurrentHashMap<String, Integer> claimedPorts = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts a PostgreSQL container for the given server entry and returns the updated
     * entry with {@code containerId} and {@code hostPort} populated.
     *
     * @throws RuntimeException if the container fails to start or become ready
     */
    public PostgresState.ServerEntry startServer(PostgresState.ServerEntry entry) {
        EmulatorConfig.PostgresServiceConfig pgConfig = config.services().postgres();
        String image = pgConfig.image();

        String containerName = containerName(entry.serverName());
        containerManager.removeIfExists(containerName);

        LOG.infof("Starting PostgreSQL container: server=%s image=%s", entry.serverName(), image);

        // Pull before claiming, not after: create() pulls the image, and a cold pull of a large

        // image would otherwise hold the port claimed but unbound for minutes, widening the

        // window for something else to take it. The call is idempotent and free once cached.

        containerManager.ensureImageAvailable(image);


        int configuredPort = pgConfig.defaultPort();
        int requestedHostPort = portAllocator.claimOrZero(configuredPort);
        if (configuredPort > 0 && requestedHostPort == 0) {
            LOG.warnf("Configured PostgreSQL default-port %d is already claimed or in use - falling back "
                + "to an OS-assigned host port for server=%s", configuredPort, entry.serverName());
        }

        ContainerSpec spec = containerBuilder.newContainer(image)
            .withName(containerName)
            .withPortBinding(PG_CONTAINER_PORT, requestedHostPort)   // 0 = OS picks (default-port unset or unavailable)
            .withDockerNetwork(config.services().dockerNetwork())  // join the shared network when running in Docker
            .withEnv("POSTGRES_USER", entry.administratorLogin())
            .withEnv("POSTGRES_PASSWORD", entry.administratorLoginPassword())
            .withEnv("POSTGRES_DB", "postgres")
            .withLogRotation()
            .build();

        // Everything from here to the hand-off is inside the claim's ownership window: if it ends
        // in a throw, nothing has taken responsibility for the port yet, so the finally gives it
        // back. Guarding each individual step instead is what let this leak twice already.
        boolean claimTransferred = false;
        try {
            ContainerLifecycleManager.ContainerInfo info = containerManager.createAndStart(spec);
            String containerId = info.containerId();

            int hostPort = Optional.ofNullable(info.getEndpoint(PG_CONTAINER_PORT))
                .map(ContainerLifecycleManager.EndpointInfo::port)
                .orElseThrow(() -> new RuntimeException(
                    "Could not resolve host port for PostgreSQL container " + containerName));

            // Pick the address an application can actually reach (mirrors RedisCacheManager):
            // when floci-az runs inside a container, clients on the shared Docker network reach the
            // sidecar by its container name on the container port; otherwise via localhost:hostPort.
            String reachableHost;
            int reachablePort;
            if (containerDetector.isRunningInContainer()) {
                reachableHost = containerName;
                reachablePort = PG_CONTAINER_PORT;
            } else {
                reachableHost = "localhost";
                reachablePort = hostPort;
            }

            managedContainers.put(containerId, containerName);

            // Record the port we claimed, not the one Docker reported: only a claimed port is
            // reserved in the allocator, and only that one may be released later.
            if (requestedHostPort > 0) {
                claimedPorts.put(containerId, requestedHostPort);
                claimTransferred = true;
            }
            LOG.infof("PostgreSQL container started: server=%s containerId=%s endpoint=%s:%d",
                entry.serverName(), containerId, reachableHost, reachablePort);

            try {
                waitForReady(reachableHost, reachablePort, pgConfig.startupTimeoutSeconds());
            } catch (RuntimeException startupFailure) {
                // The container itself is left for the pre-existing cleanup gap to deal with;
                // the port claim is this method's to give back.
                managedContainers.remove(containerId);
                claimedPorts.remove(containerId);
                claimTransferred = false;
                throw startupFailure;
            }
            LOG.infof("PostgreSQL server ready: server=%s endpoint=%s:%d",
                entry.serverName(), reachableHost, reachablePort);

            return entry.withContainer(containerId, reachablePort, reachableHost);
        } finally {
            if (!claimTransferred) {
                releaseClaimedPort(requestedHostPort);
            }
        }
    }

    /**
     * Stops and removes the container associated with the given server entry.
     */
    /**
     * Gives a claimed fixed port back so a later create can have it again. Takes the port
     * rather than the containerId on purpose: a start that fails before the container is
     * registered has nothing in the map, and looking it up there would leak the claim.
     */
    private void releaseClaimedPort(int claimedPort) {
        if (claimedPort > 0) {
            portAllocator.release(claimedPort);
        }
    }

    public void stopServer(PostgresState.ServerEntry entry) {
        if (entry.containerId() == null) return;
        LOG.infof("Stopping PostgreSQL container: server=%s containerId=%s",
            entry.serverName(), entry.containerId());
        // Give the port and the bookkeeping back first: stopAndRemove can throw on a daemon
        // hiccup, every caller swallows that, and the delete path has already dropped the
        // server from state, so nothing would ever retry the release.
        managedContainers.remove(entry.containerId());
        Integer claimed = claimedPorts.remove(entry.containerId());
        if (claimed != null) {
            releaseClaimedPort(claimed);
        }
        containerManager.stopAndRemove(entry.containerId(), null);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Polls the PostgreSQL port until it accepts TCP connections, then waits a short
     * additional period to allow the engine to finish internal initialisation.
     *
     * <p>The {@code postgres} image briefly opens and then closes the port during its
     * first-boot init phase; a short post-TCP sleep covers that window without requiring
     * a JDBC/libpq driver on the main runtime classpath. Clients use their own retry logic.
     */
    private void waitForReady(String host, int port, int timeoutSeconds) {
        LOG.infof("Waiting for PostgreSQL to be ready on %s:%d (timeout=%ds)…", host, port, timeoutSeconds);
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;

        // Phase 1 — wait for the port to accept TCP connections
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket(host, port)) {
                LOG.infof("PostgreSQL TCP %s:%d is open — waiting for engine init…", host, port);
                break;
            } catch (Exception e) {
                sleep(1000);
            }
        }

        // Phase 2 — give the engine a moment to finish startup after the port opens.
        long postTcpMs = Math.min(5_000L, deadline - System.currentTimeMillis());
        if (postTcpMs > 0) {
            sleep(postTcpMs);
        }

        LOG.infof("PostgreSQL ready: %s:%d", host, port);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for PostgreSQL", ie);
        }
    }

    private String containerName(String serverName) {
        return ContainerStorageHelper.dockerName(config, "pg-" + serverName.toLowerCase().replaceAll("[^a-z0-9-]", "-"));
    }

    @PreDestroy
    void shutdown() {
        for (Map.Entry<String, String> e : managedContainers.entrySet()) {
            try {
                LOG.infof("Stopping PostgreSQL container on shutdown: %s", e.getValue());
                containerManager.stopAndRemove(e.getKey(), null);
            } catch (Exception ex) {
                LOG.warnf(ex, "Error stopping PostgreSQL container %s", e.getValue());
            }
        }
        managedContainers.clear();
        claimedPorts.values().forEach(portAllocator::release);
        claimedPorts.clear();
    }
}
