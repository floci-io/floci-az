package io.floci.az.core.docker;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Finds free TCP ports for Docker container port bindings without TOCTOU races.
 */
@ApplicationScoped
public class PortAllocator {

    private static final Logger LOG = Logger.getLogger(PortAllocator.class);

    private final Set<Integer> reserved = new ConcurrentSkipListSet<>();

    public synchronized int allocate(int basePort, int maxPort) {
        for (int port = basePort; port <= maxPort; port++) {
            if (!reserved.contains(port) && isPortFree(port)) {
                reserved.add(port);
                LOG.debugv("Allocated port {0} from range {1}-{2}", port, basePort, maxPort);
                return port;
            }
        }
        throw new RuntimeException("No free port available in range " + basePort + "-" + maxPort);
    }

    public void release(int port) {
        if (reserved.remove(port)) {
            LOG.debugv("Released port {0}", port);
        }
    }

    public int allocateAny() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            int port = socket.getLocalPort();
            LOG.debugv("Allocated ephemeral port {0}", port);
            return port;
        } catch (IOException e) {
            throw new RuntimeException("Could not find a free port", e);
        }
    }

    public boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
