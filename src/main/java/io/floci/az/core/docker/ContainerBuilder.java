package io.floci.az.core.docker;

import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.Volume;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.dns.EmbeddedDnsServer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fluent builder for {@link ContainerSpec} instances.
 */
@ApplicationScoped
public class ContainerBuilder {

    private final EmulatorConfig config;
    private final DockerHostResolver dockerHostResolver;
    private final EmbeddedDnsServer embeddedDnsServer;
    private final CurrentContainerNetworkResolver currentContainerNetworkResolver;

    @Inject
    public ContainerBuilder(EmulatorConfig config,
                            DockerHostResolver dockerHostResolver,
                            EmbeddedDnsServer embeddedDnsServer,
                            CurrentContainerNetworkResolver currentContainerNetworkResolver) {
        this.config = config;
        this.dockerHostResolver = dockerHostResolver;
        this.embeddedDnsServer = embeddedDnsServer;
        this.currentContainerNetworkResolver = currentContainerNetworkResolver;
    }

    public Builder newContainer(String image) {
        return new Builder(image, config, dockerHostResolver, embeddedDnsServer,
                currentContainerNetworkResolver);
    }

    public static class Builder {
        private final String image;
        private final EmulatorConfig config;
        private final DockerHostResolver dockerHostResolver;
        private final EmbeddedDnsServer embeddedDnsServer;
        private final CurrentContainerNetworkResolver currentContainerNetworkResolver;

        private String name;
        private final List<String> env = new ArrayList<>();
        private List<String> cmd;
        private List<String> entrypoint;
        private String workingDir;
        private Long memoryBytes;
        private final Map<Integer, Integer> portBindings = new HashMap<>();
        private final List<Integer> exposedPorts = new ArrayList<>();
        private String networkMode;
        private final List<Mount> mounts = new ArrayList<>();
        private final List<Bind> binds = new ArrayList<>();
        private final List<String> extraHosts = new ArrayList<>();
        private LogConfig logConfig;
        private boolean privileged;
        private final List<String> dnsServers = new ArrayList<>();

        Builder(String image, EmulatorConfig config, DockerHostResolver dockerHostResolver,
                EmbeddedDnsServer embeddedDnsServer,
                CurrentContainerNetworkResolver currentContainerNetworkResolver) {
            this.image = image;
            this.config = config;
            this.dockerHostResolver = dockerHostResolver;
            this.embeddedDnsServer = embeddedDnsServer;
            this.currentContainerNetworkResolver = currentContainerNetworkResolver;
        }

        public Builder withName(String name) {
            this.name = name;
            return this;
        }

        public Builder withEnv(String key, String value) {
            this.env.add(key + "=" + value);
            return this;
        }

        public Builder withEnv(List<String> env) {
            this.env.addAll(env);
            return this;
        }

        public Builder withCmd(List<String> cmd) {
            this.cmd = cmd;
            return this;
        }

        public Builder withCmd(String cmd) {
            this.cmd = List.of(cmd);
            return this;
        }

        public Builder withEntrypoint(List<String> entrypoint) {
            this.entrypoint = entrypoint;
            return this;
        }

        public Builder withWorkingDir(String workingDir) {
            this.workingDir = workingDir;
            return this;
        }

        public Builder withMemoryMb(int memoryMb) {
            this.memoryBytes = (long) memoryMb * 1024 * 1024;
            return this;
        }

        public Builder withMemoryBytes(long memoryBytes) {
            this.memoryBytes = memoryBytes;
            return this;
        }

        public Builder withPortBinding(int containerPort, int hostPort) {
            this.portBindings.put(containerPort, hostPort);
            this.exposedPorts.add(containerPort);
            return this;
        }

        public Builder withDynamicPort(int containerPort) {
            return withPortBinding(containerPort, 0);
        }

        public Builder withExposedPort(int port) {
            this.exposedPorts.add(port);
            return this;
        }

        public Builder withNetworkMode(String networkMode) {
            this.networkMode = networkMode;
            return this;
        }

        /**
         * Sets the Docker network, falling back to the global services.dockerNetwork() config,
         * then to the detected current-container network when running inside Docker.
         */
        public Builder withDockerNetwork(Optional<String> serviceNetwork) {
            Optional<String> configuredNetwork = serviceNetwork
                    .or(() -> config.services().dockerNetwork())
                    .filter(n -> !n.isBlank())
                    .or(() -> currentContainerNetworkResolver != null
                            ? currentContainerNetworkResolver.resolveNetworkName()
                            : Optional.empty());
            configuredNetwork.ifPresent(n -> this.networkMode = n);
            return this;
        }

        public Builder withBind(String hostPath, String containerPath) {
            this.binds.add(new Bind(hostPath, new Volume(containerPath)));
            return this;
        }

        public Builder withReadOnlyBind(String hostPath, String containerPath) {
            this.binds.add(new Bind(hostPath, new Volume(containerPath), AccessMode.ro));
            return this;
        }

        public Builder withNamedVolume(String volumeName, String containerPath) {
            this.mounts.add(new Mount()
                    .withType(MountType.VOLUME)
                    .withSource(volumeName)
                    .withTarget(containerPath));
            return this;
        }

        public Builder withMount(Mount mount) {
            this.mounts.add(mount);
            return this;
        }

        public Builder withHostDockerInternalOnLinux() {
            if (dockerHostResolver.isLinuxHost()) {
                this.extraHosts.add("host.docker.internal:host-gateway");
            }
            return this;
        }

        public Builder withExtraHost(String hostname, String ip) {
            this.extraHosts.add(hostname + ":" + ip);
            return this;
        }

        public Builder withLogRotation() {
            return withLogRotation(config.docker().logMaxSize(), config.docker().logMaxFile());
        }

        public Builder withLogRotation(String maxSize, String maxFile) {
            this.logConfig = new LogConfig(
                    LogConfig.LoggingType.JSON_FILE,
                    Map.of("max-size", maxSize, "max-file", maxFile));
            return this;
        }

        public Builder withLogConfig(LogConfig logConfig) {
            this.logConfig = logConfig;
            return this;
        }

        public Builder withPrivileged(boolean privileged) {
            this.privileged = privileged;
            return this;
        }

        public Builder withEmbeddedDns() {
            embeddedDnsServer.getServerIp().ifPresent(dnsServers::add);
            return this;
        }

        public ContainerSpec build() {
            return new ContainerSpec(
                    image,
                    name,
                    List.copyOf(env),
                    cmd != null ? List.copyOf(cmd) : null,
                    entrypoint != null ? List.copyOf(entrypoint) : null,
                    memoryBytes,
                    Map.copyOf(portBindings),
                    List.copyOf(exposedPorts),
                    networkMode,
                    List.copyOf(mounts),
                    List.copyOf(binds),
                    List.copyOf(extraHosts),
                    logConfig,
                    privileged,
                    List.copyOf(dnsServers),
                    workingDir
            );
        }
    }
}
