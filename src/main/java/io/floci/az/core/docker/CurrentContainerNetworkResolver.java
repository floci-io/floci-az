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

        String containerId = currentContainerId();
        if (containerId.isBlank()) {
            LOG.debug("Could not determine current Docker container id");
            return Optional.empty();
        }

        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            Map<String, ContainerNetwork> networks = inspect.getNetworkSettings().getNetworks();
            if (networks == null || networks.isEmpty()) {
                return Optional.empty();
            }

            Optional<CurrentContainerNetwork> selected = selectNetwork(networks);
            selected.ifPresent(network -> LOG.infov(
                    "Detected current Docker network for spawned containers: {0} ({1})",
                    network.name(), network.ipAddress()));
            return selected;
        } catch (Exception e) {
            LOG.debugv("Could not inspect current Docker container {0}: {1}", containerId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Prefers a usable user-defined network over Docker's built-in bridge/host/none networks,
     * so spawned sibling containers join a network where DNS-based service discovery works.
     * Falls back to any network with a usable IP when no user-defined network is attached.
     */
    private Optional<CurrentContainerNetwork> selectNetwork(Map<String, ContainerNetwork> networks) {
        return networks.entrySet().stream()
                .filter(entry -> isUsable(entry.getValue()))
                .filter(entry -> isUserDefinedNetwork(entry.getKey()))
                .findFirst()
                .or(() -> networks.entrySet().stream()
                        .filter(entry -> isUsable(entry.getValue()))
                        .findFirst())
                .map(entry -> new CurrentContainerNetwork(entry.getKey(), entry.getValue().getIpAddress()));
    }

    private boolean isUsable(ContainerNetwork network) {
        return network != null && network.getIpAddress() != null && !network.getIpAddress().isBlank();
    }

    private boolean isUserDefinedNetwork(String networkName) {
        return !"bridge".equals(networkName) && !"host".equals(networkName) && !"none".equals(networkName);
    }

    String currentContainerId() {
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
        return "";
    }

    record CurrentContainerNetwork(String name, String ipAddress) {}
}
