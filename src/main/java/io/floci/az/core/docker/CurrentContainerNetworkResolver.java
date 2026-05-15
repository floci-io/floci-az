package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the Docker network used by the floci-az container itself.
 *
 * When floci-az runs in Docker and launches sibling containers through the
 * mounted Docker socket, those siblings must join the same Docker network to
 * reach floci-az's in-container endpoints.
 */
@ApplicationScoped
public class CurrentContainerNetworkResolver {

    private static final Logger LOG = Logger.getLogger(CurrentContainerNetworkResolver.class);

    private static final String HOSTNAME_FILE = "/etc/hostname";

    private final DockerClient dockerClient;
    private final ContainerDetector containerDetector;

    private volatile Optional<CurrentContainerNetwork> cachedNetwork;

    @Inject
    public CurrentContainerNetworkResolver(DockerClient dockerClient, ContainerDetector containerDetector) {
        this.dockerClient = dockerClient;
        this.containerDetector = containerDetector;
    }

    public Optional<String> resolveNetworkName() {
        return resolve().map(CurrentContainerNetwork::name);
    }

    public Optional<String> resolveContainerIp() {
        return resolve().map(CurrentContainerNetwork::ipAddress);
    }

    Optional<CurrentContainerNetwork> resolve() {
        Optional<CurrentContainerNetwork> cached = cachedNetwork;
        if (cached != null) {
            return cached;
        }
        cachedNetwork = detect();
        return cachedNetwork;
    }

    private Optional<CurrentContainerNetwork> detect() {
        if (!containerDetector.isRunningInContainer()) {
            return Optional.empty();
        }

        try {
            String containerId = readContainerId();
            if (containerId == null || containerId.isBlank()) {
                LOG.debugv("Could not determine container ID from {0}", HOSTNAME_FILE);
                return Optional.empty();
            }

            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            Map<String, ContainerNetwork> networks = inspect.getNetworkSettings().getNetworks();
            if (networks == null || networks.isEmpty()) {
                return Optional.empty();
            }

            Map.Entry<String, ContainerNetwork> first = networks.entrySet().iterator().next();
            String networkName = first.getKey();
            String ipAddress = first.getValue().getIpAddress();

            LOG.infov("Detected current container network: {0}, IP: {1}", networkName, ipAddress);
            return Optional.of(new CurrentContainerNetwork(networkName, ipAddress));
        } catch (Exception e) {
            LOG.debugv("Could not detect current container network: {0}", e.getMessage());
            return Optional.empty();
        }
    }

    private String readContainerId() {
        try {
            String hostname = System.getenv("HOSTNAME");
            if (hostname != null && !hostname.isBlank()) {
                return hostname;
            }
            Path hostnameFile = Path.of(HOSTNAME_FILE);
            if (Files.exists(hostnameFile)) {
                return Files.readString(hostnameFile).strip();
            }
        } catch (Exception e) {
            LOG.debugv("Could not read container ID: {0}", e.getMessage());
        }
        return null;
    }

    record CurrentContainerNetwork(String name, String ipAddress) {}
}
