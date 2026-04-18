package io.floci.az.core.docker;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detects the hostname that containers should use to reach the floci-az host.
 *
 * - macOS / Windows (Docker Desktop): "host.docker.internal" is auto-injected into
 *   every container's /etc/hosts and routes through the Docker VM to the host.
 * - Native Linux Docker: "host.docker.internal" is NOT auto-injected.
 *   ContainerLauncher must add "host.docker.internal:host-gateway" to extra-hosts.
 * - When floci-az itself runs inside Docker: use the container's own IP so sibling
 *   containers on the same bridge network can reach it directly.
 *
 * NOTE: Kept identical to aws-local's DockerHostResolver — both will move to a shared lib.
 */
@ApplicationScoped
public class DockerHostResolver {

    private static final Logger LOG = Logger.getLogger(DockerHostResolver.class);

    private static final String HOST_DOCKER_INTERNAL = "host.docker.internal";
    private static final String LINUX_DOCKER_BRIDGE  = "172.17.0.1";

    private final ContainerDetector containerDetector;
    private final AtomicBoolean ufwHintLogged = new AtomicBoolean(false);

    @Inject
    public DockerHostResolver(ContainerDetector containerDetector) {
        this.containerDetector = containerDetector;
    }

    public String resolve() {
        if (containerDetector.isRunningInContainer()) {
            try {
                String ip = InetAddress.getLocalHost().getHostAddress();
                LOG.infov("Running in Docker — containers will reach floci-az at: {0}", ip);
                return ip;
            } catch (Exception e) {
                LOG.warnv("Could not resolve local address, falling back to bridge IP: {0}", e.getMessage());
                return LINUX_DOCKER_BRIDGE;
            }
        }

        LOG.debugv("Running on host ({0}) — containers will use {1}",
                System.getProperty("os.name"), HOST_DOCKER_INTERNAL);

        if (isLinuxHost() && ufwHintLogged.compareAndSet(false, true)) {
            LOG.info("Function containers will reach floci-az via host.docker.internal "
                    + "(translated to the docker bridge gateway). On Linux hosts running UFW "
                    + "with the default 'INPUT DROP' policy this path is blocked and invocations "
                    + "will time out. If you see that, run: 'sudo ufw allow in on docker0'.");
        }

        return HOST_DOCKER_INTERNAL;
    }

    /** True when floci-az JVM is running natively on a Linux host (not on Docker Desktop). */
    public boolean isLinuxHost() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux") || os.contains("nix") || os.contains("nux");
    }
}
