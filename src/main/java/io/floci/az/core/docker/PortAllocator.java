package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Finds free TCP ports for Docker container port bindings without TOCTOU races.
 */
@ApplicationScoped
public class PortAllocator {

    private static final Logger LOG = Logger.getLogger(PortAllocator.class);

    // Ports reserved by this process but not yet bound by Docker.
    // Prevents TOCTOU races when multiple containers are launched concurrently.
    private final Set<Integer> reserved = new ConcurrentSkipListSet<>();

    // Nullable: the no-arg constructor leaves it null, which turns publishedHostPorts() into a
    // no-op and keeps plain unit tests free of any Docker interaction. Only the CDI-managed
    // instance, built through the @Inject constructor, has one.
    private final DockerClient dockerClient;

    public PortAllocator() {
        this(null);
    }

    @Inject
    public PortAllocator(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    /**
     * Atomically finds and reserves a free TCP port within the specified range.
     * The port is held in-memory until {@link #release(int)} is called, preventing
     * concurrent callers from picking the same port before Docker binds it.
     *
     * @param basePort the lowest port number to try (inclusive)
     * @param maxPort  the highest port number to try (inclusive)
     * @return a reserved free port within the range
     * @throws RuntimeException if no free port is available in the range
     */
    public synchronized int allocate(int basePort, int maxPort) {
        // One Docker API round-trip per allocation, not one per candidate: the loop runs inside
        // the lock every caller of the shared allocator blocks on.
        Set<Integer> published = publishedHostPorts();
        for (int port = basePort; port <= maxPort; port++) {
            if (!reserved.contains(port) && !published.contains(port) && isPortFree(port)) {
                reserved.add(port);
                LOG.debugv("Allocated port {0} from range {1}-{2}",
                        String.valueOf(port), String.valueOf(basePort), String.valueOf(maxPort));
                return port;
            }
        }
        throw new RuntimeException("No free port available in range " + basePort + "-" + maxPort);
    }

    /**
     * Claims one specific host port when it is configured and actually available.
     *
     * <p>Used by the per-server database sidecars, where a configured port expresses a preference
     * that only the first server can be given: the second server asking for the same port, or a
     * port an unrelated process already holds, falls back to an OS-assigned one. Returning
     * {@code 0} rather than throwing is what makes that fallback expressible, and {@code 0} is
     * already the repo-wide "let the OS pick" sentinel, so the result can be handed straight to
     * {@code withPortBinding}.
     *
     * <p>Availability is judged when the claim is made, against both this process's own probe and
     * the host ports other containers already publish ({@link #publishedHostPorts()}). A port that
     * another process takes between that check and the container's bind is not caught here: Docker
     * fails the bind and the create fails like any other port conflict, the same window
     * {@link #allocate(int, int)} already has.
     *
     * @param port the configured host port; {@code 0} or negative means no preference
     * @return {@code port} if it was claimed (release it with {@link #release(int)}), else {@code 0}
     */
    public synchronized int claimOrZero(int port) {
        if (port <= 0) {
            return 0;
        }
        if (reserved.contains(port) || !isPortFree(port) || publishedHostPorts().contains(port)) {
            return 0;
        }
        reserved.add(port);
        LOG.debugv("Claimed configured host port {0}", String.valueOf(port));
        return port;
    }

    /**
     * Marks a port as reserved without probing whether it is free. Used on restart to
     * re-reserve host ports already held by surviving containers so the allocator does
     * not hand them out again.
     */
    public synchronized void markReserved(int port) {
        reserved.add(port);
    }

    /**
     * Reserves one specific port without probing whether it is free here. For ports a remote
     * Docker daemon binds: whether they're free on floci-az's own host says nothing about the
     * daemon, which rejects a taken port itself when the container binds it.
     *
     * @return {@code true} if the port was reserved (release it with {@link #release(int)}),
     *         {@code false} if floci-az already holds it
     */
    public synchronized boolean reserveUnprobed(int port) {
        boolean added = reserved.add(port);
        if (added) {
            LOG.debugv("Reserved port {0} without probing", String.valueOf(port));
        }
        return added;
    }

    /**
     * Releases a previously allocated port back to the pool.
     * Should be called when the Docker container that was using the port is removed.
     */
    public void release(int port) {
        if (reserved.remove(port)) {
            LOG.debugv("Released port {0}", String.valueOf(port));
        }
    }

    /**
     * Finds any free TCP port using ephemeral port allocation.
     * This is the fastest method when any port will do.
     *
     * @return a free port
     * @throws RuntimeException if no free port can be allocated
     */
    public int allocateAny() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            int port = socket.getLocalPort();
            LOG.debugv("Allocated ephemeral port {0}", String.valueOf(port));
            return port;
        } catch (IOException e) {
            throw new RuntimeException("Could not find a free port", e);
        }
    }

    /**
     * Checks if a specific port is currently free.
     *
     * <p>This binds a {@link ServerSocket} in floci-az's own network namespace. That answers the
     * question only when floci-az runs as a bare process. Inside its own container, a port a
     * sibling container publishes with {@code -p hostPort:containerPort} (another floci-az
     * instance's ACR registry or Redis cache, or any unrelated container) is invisible here, so
     * "free" can only mean "free inside this container". {@link #publishedHostPorts()} is the other
     * half {@link #allocate(int, int)} and {@link #claimOrZero(int)} consult.
     *
     * @param port the port to check
     * @return true if the port is available, false otherwise
     */
    public boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Snapshots every host port any Docker container currently publishes, running or not: a
     * container that was created but has not started yet already holds its port binding. This is
     * what {@link #isPortFree(int)} cannot see from inside floci-az's own container, and it is the
     * case where two floci-az instances on one Docker host would otherwise both pick the same port
     * for their own sidecars and the second {@code docker start} fails with "port is already
     * allocated".
     *
     * <p>Returns an empty set, never blocking allocation, when this instance has no
     * {@link DockerClient} or when the Docker API call fails, matching the fail-permissive posture of
     * {@link #isPortFree(int)}.
     */
    Set<Integer> publishedHostPorts() {
        if (dockerClient == null) {
            return Set.of();
        }
        try {
            List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
            Set<Integer> published = new HashSet<>();
            for (Container container : containers) {
                ContainerPort[] ports = container.getPorts();
                if (ports == null) {
                    continue;
                }
                for (ContainerPort port : ports) {
                    Integer publicPort = port.getPublicPort();
                    if (publicPort != null) {
                        published.add(publicPort);
                    }
                }
            }
            return published;
        } catch (Exception e) {
            LOG.debugv("Could not list Docker port bindings, probing locally only: {0}", e.getMessage());
            return Set.of();
        }
    }
}
