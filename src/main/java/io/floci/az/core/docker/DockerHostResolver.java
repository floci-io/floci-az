package io.floci.az.core.docker;

import io.floci.az.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detects the hostname that containers should use to reach the floci-az host.
 * Different on Linux native Docker vs Docker Desktop (macOS/Windows).
 */
@ApplicationScoped
public class DockerHostResolver {

    private static final Logger LOG = Logger.getLogger(DockerHostResolver.class);

    private static final String HOST_DOCKER_INTERNAL = "host.docker.internal";
    private static final String LINUX_DOCKER_BRIDGE = "172.17.0.1";

    private final EmulatorConfig config;
    private final ContainerDetector containerDetector;
    private final CurrentContainerNetworkResolver currentContainerNetworkResolver;
    private final AtomicBoolean ufwHintLogged = new AtomicBoolean(false);

    @Inject
    public DockerHostResolver(EmulatorConfig config, ContainerDetector containerDetector,
                              CurrentContainerNetworkResolver currentContainerNetworkResolver) {
        this.config = config;
        this.containerDetector = containerDetector;
        this.currentContainerNetworkResolver = currentContainerNetworkResolver;
    }

    public DockerHostResolver(EmulatorConfig config, ContainerDetector containerDetector) {
        this(config, containerDetector, null);
    }

    public String resolve() {
        Optional<String> override = config.services().functions().dockerHostOverride();
        if (override.isPresent() && !override.get().isBlank()) {
            LOG.debugv("Using configured docker host override: {0}", override.get());
            return override.get();
        }

        if (containerDetector.isRunningInContainer()) {
            if (currentContainerNetworkResolver != null) {
                Optional<String> currentNetworkIp = currentContainerNetworkResolver.resolveContainerIp();
                if (currentNetworkIp.isPresent()) {
                    LOG.infov("Running in Docker — using current network IP for function containers: {0}",
                            currentNetworkIp.get());
                    return currentNetworkIp.get();
                }
            }

            try {
                String ip = InetAddress.getLocalHost().getHostAddress();
                LOG.infov("Running in Docker — using container IP for function containers: {0}", ip);
                return ip;
            } catch (Exception e) {
                LOG.warnv("Could not resolve local host address, falling back to bridge IP: {0}", e.getMessage());
                return LINUX_DOCKER_BRIDGE;
            }
        }

        // floci-az is running natively on the host. Always return host.docker.internal:
        //   - On macOS/Windows (Docker Desktop), the alias is auto-injected into every
        //     container's /etc/hosts and routes through the Docker VM to the host.
        //   - On native Linux Docker, the alias is NOT auto-injected, so ContainerLauncher
        //     must add `host.docker.internal:host-gateway` to each container's extra-hosts.
        LOG.debugv("floci-az on host ({0}) — function containers will use host.docker.internal",
                System.getProperty("os.name"));

        if (isLinuxHost() && ufwHintLogged.compareAndSet(false, true)) {
            LOG.info("Function containers will reach floci-az via host.docker.internal "
                    + "(translated to the docker bridge gateway). On Linux hosts running UFW "
                    + "with the default 'INPUT DROP' policy this path is blocked and invocations "
                    + "will time out. If you see that, run: 'sudo ufw allow in on docker0'.");
        }

        return HOST_DOCKER_INTERNAL;
    }

    /** True when the floci-az JVM is running natively on a Linux host (not on Docker Desktop). */
    public boolean isLinuxHost() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux") || os.contains("nix") || os.contains("nux");
    }
}
