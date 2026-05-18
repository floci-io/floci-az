package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages Docker container lifecycle: create, start, stop, remove, volume management.
 */
@ApplicationScoped
public class ContainerLifecycleManager {

    private static final Logger LOG = Logger.getLogger(ContainerLifecycleManager.class);

    private final DockerClient dockerClient;
    private final ImageCacheService imageCacheService;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;

    @Inject
    public ContainerLifecycleManager(DockerClient dockerClient,
                                     ImageCacheService imageCacheService,
                                     ContainerDetector containerDetector,
                                     PortAllocator portAllocator) {
        this.dockerClient = dockerClient;
        this.imageCacheService = imageCacheService;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
    }

    public ContainerInfo createAndStart(ContainerSpec spec) {
        String containerId = create(spec);
        return startCreated(containerId, spec);
    }

    public String create(ContainerSpec spec) {
        LOG.debugv("Creating container: image={0}, name={1}", spec.image(), spec.name());

        imageCacheService.ensureImageExists(spec.image());

        HostConfig hostConfig = buildHostConfig(spec);

        CreateContainerCmd createCmd = dockerClient.createContainerCmd(spec.image())
                .withHostConfig(hostConfig);

        if (spec.name() != null) createCmd.withName(spec.name());
        if (spec.env() != null && !spec.env().isEmpty()) createCmd.withEnv(spec.env());
        if (spec.cmd() != null && !spec.cmd().isEmpty()) createCmd.withCmd(spec.cmd());
        if (spec.entrypoint() != null && !spec.entrypoint().isEmpty())
            createCmd.withEntrypoint(spec.entrypoint());
        if (spec.workingDir() != null && !spec.workingDir().isBlank())
            createCmd.withWorkingDir(spec.workingDir());
        if (spec.exposedPorts() != null && !spec.exposedPorts().isEmpty()) {
            ExposedPort[] exposed = spec.exposedPorts().stream()
                    .map(ExposedPort::tcp)
                    .toArray(ExposedPort[]::new);
            createCmd.withExposedPorts(exposed);
        }

        CreateContainerResponse response = createCmd.exec();
        String containerId = response.getId();
        LOG.infov("Created container {0} (name={1})", containerId, spec.name());
        return containerId;
    }

    public ContainerInfo startCreated(String containerId, ContainerSpec spec) {
        dockerClient.startContainerCmd(containerId).exec();
        LOG.infov("Started container {0}", containerId);

        if (spec.networkMode() != null && !spec.networkMode().isBlank() && spec.hasPortBindings()) {
            try {
                dockerClient.connectToNetworkCmd()
                        .withContainerId(containerId)
                        .withNetworkId(spec.networkMode())
                        .exec();
                LOG.debugv("Connected container {0} to network {1}", containerId, spec.networkMode());
            } catch (Exception e) {
                LOG.warnv("Could not connect container {0} to network {1}: {2}",
                        containerId, spec.networkMode(), e.getMessage());
            }
        }

        Map<Integer, EndpointInfo> endpoints = resolveEndpoints(containerId, spec);
        return new ContainerInfo(containerId, endpoints);
    }

    public void stopAndRemove(String containerId, Closeable logStream) {
        LOG.infov("Stopping container {0}", containerId);

        if (logStream != null) {
            try { logStream.close(); } catch (Exception e) {
                LOG.debugv("Error closing log stream: {0}", e.getMessage());
            }
        }

        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(5).exec();
        } catch (NotFoundException e) {
            LOG.debugv("Container {0} not found (already removed)", containerId);
            return;
        } catch (Exception e) {
            LOG.warnv("Error stopping container {0}: {1}", containerId, e.getMessage());
        }

        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            LOG.debugv("Removed container {0}", containerId);
        } catch (NotFoundException ignored) {
        } catch (Exception e) {
            LOG.warnv("Error removing container {0}: {1}", containerId, e.getMessage());
        }
    }

    public void ensureVolume(String volumeName) {
        if (!volumeExists(volumeName)) {
            dockerClient.createVolumeCmd()
                    .withName(volumeName)
                    .withLabels(Map.of("floci", "true"))
                    .exec();
            LOG.debugv("Created volume {0}", volumeName);
        }
    }

    public void removeVolume(String volumeName) {
        try {
            dockerClient.removeVolumeCmd(volumeName).exec();
            LOG.debugv("Removed volume {0}", volumeName);
        } catch (NotFoundException ignored) {
        } catch (Exception e) {
            LOG.warnv("Error removing volume {0}: {1}", volumeName, e.getMessage());
        }
    }

    public Optional<Container> findByName(String name) {
        try {
            List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
            for (Container c : containers) {
                String[] names = c.getNames();
                if (names == null) continue;
                for (String n : names) {
                    if (n.equals("/" + name) || n.equals(name)) return Optional.of(c);
                }
            }
        } catch (Exception e) {
            LOG.debugv("Error searching for container {0}: {1}", name, e.getMessage());
        }
        return Optional.empty();
    }

    public void removeIfExists(String name) {
        try {
            dockerClient.removeContainerCmd(name).withForce(true).exec();
            LOG.infov("Removed stale container {0}", name);
        } catch (NotFoundException ignored) {
        } catch (Exception e) {
            LOG.debugv("Could not remove container {0}: {1}", name, e.getMessage());
        }
    }

    public boolean isContainerRunning(String containerId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            return Boolean.TRUE.equals(inspect.getState().getRunning());
        } catch (NotFoundException e) {
            return false;
        } catch (Exception e) {
            LOG.warnv("Liveness check failed for container {0}: {1}", containerId, e.getMessage());
            return true;
        }
    }

    public EndpointInfo resolveEndpoint(String containerId, int containerPort) {
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        return resolveEndpoint(inspect, containerPort, null);
    }

    public DockerClient getDockerClient() {
        return dockerClient;
    }

    /**
     * Copies a UTF-8 text file into a container before it is started.
     * Works correctly whether floci-az runs on the Docker host or inside a container,
     * because it uses the Docker API's archive injection instead of a bind mount.
     * The full directory tree is created if it does not yet exist.
     */
    public void copyFileToContainer(String containerId, String content, String targetPath) {
        copyBytesToContainer(containerId, content.getBytes(StandardCharsets.UTF_8), targetPath);
    }

    /** Copies arbitrary binary content into a container via the Docker archive API. */
    public void copyBytesToContainer(String containerId, byte[] bytes, String targetPath) {
        String entryName = targetPath.startsWith("/") ? targetPath.substring(1) : targetPath;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(baos)) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
                TarArchiveEntry entry = new TarArchiveEntry(entryName);
                entry.setSize(bytes.length);
                tar.putArchiveEntry(entry);
                tar.write(bytes);
                tar.closeArchiveEntry();
            }
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withRemotePath("/")
                    .withTarInputStream(new ByteArrayInputStream(baos.toByteArray()))
                    .exec();
            LOG.debugv("Copied {0} ({1} bytes) into container {2}", targetPath, bytes.length, containerId);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy file to container: " + targetPath, e);
        }
    }

    public boolean volumeExists(String name) {
        if (name == null || name.isBlank()) return false;
        if (name.startsWith("/") || name.startsWith(".")) return false;
        if (name.length() >= 3 && Character.isLetter(name.charAt(0))
                && name.charAt(1) == ':' && (name.charAt(2) == '\\' || name.charAt(2) == '/'))
            return false;
        try {
            dockerClient.inspectVolumeCmd(name).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        } catch (DockerException e) {
            LOG.warnv("Failed to inspect volume ''{0}'': {1}", name, e.getMessage());
            return false;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private HostConfig buildHostConfig(ContainerSpec spec) {
        HostConfig hostConfig = HostConfig.newHostConfig();

        if (spec.privileged()) hostConfig.withPrivileged(true);
        if (spec.hasMemoryLimit()) hostConfig.withMemory(spec.memoryBytes());

        if (spec.hasPortBindings()) {
            Ports ports = new Ports();
            for (Map.Entry<Integer, Integer> entry : spec.portBindings().entrySet()) {
                int containerPort = entry.getKey();
                int hostPort = entry.getValue() == 0 ? portAllocator.allocateAny() : entry.getValue();
                ports.bind(ExposedPort.tcp(containerPort), Ports.Binding.bindPort(hostPort));
                LOG.debugv("Port binding: {0} -> {1}", containerPort, hostPort);
            }
            hostConfig.withPortBindings(ports);
        }

        // Only set networkMode during creation when there are no host port bindings;
        // containers with port bindings connect to the named network via connectToNetworkCmd()
        // after start to avoid suppressing port publishing on Docker Desktop (macOS).
        if (spec.networkMode() != null && !spec.networkMode().isBlank() && !spec.hasPortBindings()) {
            hostConfig.withNetworkMode(spec.networkMode());
        }

        if (spec.mounts() != null && !spec.mounts().isEmpty()) hostConfig.withMounts(spec.mounts());
        if (spec.binds() != null && !spec.binds().isEmpty())
            hostConfig.withBinds(spec.binds().toArray(new Bind[0]));
        if (spec.extraHosts() != null && !spec.extraHosts().isEmpty())
            hostConfig.withExtraHosts(spec.extraHosts().toArray(new String[0]));
        if (spec.hasLogConfig()) hostConfig.withLogConfig(spec.logConfig());
        if (spec.dnsServers() != null && !spec.dnsServers().isEmpty())
            hostConfig.withDns(spec.dnsServers().toArray(new String[0]));

        return hostConfig;
    }

    private Map<Integer, EndpointInfo> resolveEndpoints(String containerId, ContainerSpec spec) {
        if (spec.exposedPorts() == null || spec.exposedPorts().isEmpty()) return Map.of();
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        Map<Integer, EndpointInfo> endpoints = new HashMap<>();
        for (int containerPort : spec.exposedPorts()) {
            endpoints.put(containerPort, resolveEndpoint(inspect, containerPort, spec.networkMode()));
        }
        return endpoints;
    }

    private EndpointInfo resolveEndpoint(InspectContainerResponse inspect, int containerPort) {
        return resolveEndpoint(inspect, containerPort, null);
    }

    private EndpointInfo resolveEndpoint(InspectContainerResponse inspect, int containerPort,
                                         String preferredNetwork) {
        if (!containerDetector.isRunningInContainer()) {
            var bindings = inspect.getNetworkSettings().getPorts().getBindings();
            var binding = bindings.get(ExposedPort.tcp(containerPort));
            if (binding != null && binding.length > 0) {
                int hostPort = Integer.parseInt(binding[0].getHostPortSpec());
                return new EndpointInfo("localhost", hostPort);
            }
            return new EndpointInfo("localhost", containerPort);
        } else {
            String containerIp = resolveContainerIp(inspect, preferredNetwork);
            return new EndpointInfo(containerIp, containerPort);
        }
    }

    private String resolveContainerIp(InspectContainerResponse inspect, String preferredNetwork) {
        var networks = inspect.getNetworkSettings().getNetworks();
        if (networks != null) {
            if (preferredNetwork != null && networks.containsKey(preferredNetwork)) {
                String ip = networks.get(preferredNetwork).getIpAddress();
                if (ip != null && !ip.isBlank()) return ip;
            }
            for (Map.Entry<String, ContainerNetwork> entry : networks.entrySet()) {
                String ip = entry.getValue().getIpAddress();
                if (ip != null && !ip.isBlank()) return ip;
            }
        }
        return inspect.getNetworkSettings().getIpAddress();
    }

    public record ContainerInfo(String containerId, Map<Integer, EndpointInfo> endpoints) {
        public EndpointInfo getEndpoint(int containerPort) {
            return endpoints.get(containerPort);
        }
    }

    public record EndpointInfo(String host, int port) {
        @Override
        public String toString() {
            return host + ":" + port;
        }
    }
}
