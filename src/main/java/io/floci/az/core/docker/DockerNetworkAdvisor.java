package io.floci.az.core.docker;

import io.floci.az.config.EmulatorConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Warns once at startup when floci-az runs in a container whose sidecars land on a Docker network
 * that does not resolve container names (the default {@code bridge}).
 *
 * <p>floci-az reaches its sidecars there by container IP, so resources still provision. The host
 * names it reports to clients for Redis, PostgreSQL, MySQL, MariaDB and SQL Server stay the
 * container names, which a client on the same default bridge cannot resolve either; a
 * user-defined network fixes both, which is what the warning recommends.
 */
@ApplicationScoped
public class DockerNetworkAdvisor {

    private static final Logger LOG = Logger.getLogger(DockerNetworkAdvisor.class);

    private final EmulatorConfig config;
    private final ContainerDetector containerDetector;
    private final CurrentContainerNetworkResolver networkResolver;

    @Inject
    public DockerNetworkAdvisor(EmulatorConfig config,
                                ContainerDetector containerDetector,
                                CurrentContainerNetworkResolver networkResolver) {
        this.config = config;
        this.containerDetector = containerDetector;
        this.networkResolver = networkResolver;
    }

    void onStart(@Observes StartupEvent event) {
        Optional<String> network;
        try {
            network = sidecarNetworkWithoutNameResolution();
        } catch (RuntimeException e) {
            LOG.debugv("Could not determine the sidecar Docker network: {0}", e.getMessage());
            return;
        }
        network.ifPresent(name -> LOG.warnv(
                "Sidecar containers join Docker network ''{0}'', which does not resolve container "
                        + "names. floci-az reaches them by container IP, but the host names it reports "
                        + "for Redis, PostgreSQL, MySQL, MariaDB and SQL Server are container names that "
                        + "clients on this network cannot resolve. Run floci-az on a user-defined network "
                        + "(docker network create <name>, then --network <name>) and set "
                        + "FLOCI_AZ_SERVICES_DOCKER_NETWORK=<name>.",
                name));
    }

    /**
     * The network sidecars join when it cannot resolve container names, or empty when it can, when
     * floci-az is not in a container, or when sidecars are addressed by published port anyway.
     */
    Optional<String> sidecarNetworkWithoutNameResolution() {
        if (!containerDetector.isRunningInContainer()
                || config.docker().endpointMode() == EmulatorConfig.DockerEndpointMode.PUBLISHED) {
            return Optional.empty();
        }
        Optional<String> network = config.services().dockerNetwork()
                .filter(name -> !name.isBlank())
                .or(networkResolver::resolveNetworkName);
        return network.filter(name -> !ContainerLifecycleManager.resolvesContainerNames(name));
    }
}
