package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import io.floci.az.config.EmulatorConfig;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages Docker container lifecycle operations including create, start, stop, remove,
 * and volume management. Consolidates common container management patterns used across
 * floci-az services.
 */
@ApplicationScoped
public class ContainerLifecycleManager {

    private static final Logger LOG = Logger.getLogger(ContainerLifecycleManager.class);

    private final DockerClient dockerClient;
    private final ImageCacheService imageCacheService;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;

    /** Volumes whose shared-ownership root has already been initialised this process (run-once guard). */
    private final ConcurrentHashMap<String, Boolean> initializedSharedVolumes = new ConcurrentHashMap<>();

    @Inject
    public ContainerLifecycleManager(DockerClient dockerClient,
                                     ImageCacheService imageCacheService,
                                     ContainerDetector containerDetector,
                                     PortAllocator portAllocator,
                                     EmulatorConfig config) {
        this.dockerClient = dockerClient;
        this.imageCacheService = imageCacheService;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.config = config;
    }

    /**
     * Creates and immediately starts a container. Delegates to
     * {@link #create} and {@link #startCreated}. Suitable when no
     * filesystem modifications are needed between creation and start.
     *
     * @param spec the container specification
     * @return information about the created container including resolved endpoints
     */
    public ContainerInfo createAndStart(ContainerSpec spec) {
        String containerId = create(spec);
        try {
            return startCreated(containerId, spec);
        } catch (Exception e) {
            // A failed start (e.g. host-port conflict) must not leak the created
            // container: retrying callers would accumulate Created containers and
            // fixed-name callers would hit name conflicts on the next attempt.
            removeIfExists(containerId);
            throw e;
        }
    }

    /**
     * Creates a container without starting it. Use {@link #startCreated} to
     * start it after any pre-start setup (e.g. copying files into the
     * container filesystem).
     *
     * @param spec the container specification
     * @return the container ID
     */
    public String create(ContainerSpec spec) {
        LOG.debugv("Creating container: image={0}, name={1}", spec.image(), spec.name());

        imageCacheService.ensureImageExists(spec.image());

        HostConfig hostConfig = buildHostConfig(spec);

        CreateContainerCmd createCmd = dockerClient.createContainerCmd(spec.image())
                .withHostConfig(hostConfig);

        if (spec.name() != null) createCmd.withName(spec.name());
        if (spec.user() != null && !spec.user().isBlank()) createCmd.withUser(spec.user());
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
        createCmd.withLabels(mergedLabels(spec.labels()));

        CreateContainerResponse response = createCmd.exec();
        String containerId = response.getId();
        LOG.infov("Created container {0} (name={1})", containerId, spec.name());
        return containerId;
    }

    /**
     * Starts a previously created container and resolves its endpoints.
     *
     * @param containerId the container ID returned by {@link #create}
     * @param spec the original spec (needed for network and endpoint resolution)
     * @return information about the running container including resolved endpoints
     */
    public ContainerInfo startCreated(String containerId, ContainerSpec spec) {
        dockerClient.startContainerCmd(containerId).exec();
        LOG.infov("Started container {0}", containerId);

        if (spec.networkMode() != null && !spec.networkMode().isBlank()
                && spec.hasPortBindings() && !containerDetector.isRunningInContainer()) {
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

    /** Returns IP addresses assigned to a container's Docker network namespace. */
    public List<String> containerAddresses(String containerId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            if (inspect.getNetworkSettings() == null) {
                return List.of();
            }
            var networks = inspect.getNetworkSettings().getNetworks();
            if (networks == null || networks.isEmpty()) {
                return List.of();
            }
            List<String> addresses = new ArrayList<>();
            for (ContainerNetwork network : networks.values()) {
                if (network.getIpAddress() != null && !network.getIpAddress().isBlank()) {
                    addresses.add(network.getIpAddress());
                }
                if (network.getGlobalIPv6Address() != null && !network.getGlobalIPv6Address().isBlank()) {
                    addresses.add(network.getGlobalIPv6Address());
                }
            }
            return List.copyOf(addresses);
        } catch (NotFoundException e) {
            LOG.debugv("Container {0} disappeared before its addresses could be inspected", containerId);
            return List.of();
        } catch (RuntimeException e) {
            LOG.warnv("Could not inspect Docker addresses for container {0}: {1}",
                    containerId, e.getMessage());
            return List.of();
        }
    }

    /** Returns IP addresses for running containers carrying every required label. */
    public List<String> runningContainerAddresses(Map<String, String> requiredLabels) {
        try {
            return dockerClient.listContainersCmd().withShowAll(false).exec().stream()
                    .filter(container -> hasRequiredLabels(container, requiredLabels))
                    .flatMap(container -> containerAddresses(container.getId()).stream())
                    .distinct()
                    .toList();
        } catch (RuntimeException e) {
            LOG.warnv("Could not inspect running Docker containers by label: {0}", e.getMessage());
            return List.of();
        }
    }

    /** Tests whether an IP literal exactly matches one of the supplied IP literals. */
    public static boolean matchesAnyAddress(String address, Collection<String> addresses) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            byte[] candidate = InetAddress.getByName(address).getAddress();
            for (String expected : addresses) {
                if (Arrays.equals(candidate, InetAddress.getByName(expected).getAddress())) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            return false;
        }
        return false;
    }

    /**
     * Stops and removes a container, closing any associated log stream.
     *
     * @param containerId the container ID to stop and remove
     * @param logStream optional log stream to close (may be null)
     */
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

    /**
     * Creates a named volume if it does not already exist. Idempotent — safe to call on every
     * container start. Labels the volume {@code floci=true} and {@code floci_emulator=floci-az}
     * so both {@code docker volume prune --filter label=floci=true} (all emulators) and
     * {@code --filter label=floci_emulator=floci-az} (this emulator only) work.
     */
    public void ensureVolume(String volumeName) {
        if (!volumeExists(volumeName)) {
            dockerClient.createVolumeCmd()
                    .withName(volumeName)
                    .withLabels(ContainerStorageHelper.defaultLabels(config))
                    .exec();
            LOG.debugv("Created volume {0}", volumeName);
        }
    }

    /**
     * Ensures the named volume exists (see {@link #ensureVolume}) and, when POSIX ownership is
     * requested, initialises the volume root once so shared-file-storage semantics hold. A Docker
     * named volume is created {@code root:root 0755}, so a container whose image runs as a
     * non-root {@code USER} cannot create files on it. This chowns/chmods the mount root inside a
     * short-lived helper container. A 4-digit octal {@code rootPermissions} (e.g. {@code "2775"})
     * carries the setgid bit, so subdirectories inherit the owner gid.
     *
     * <p>The initialisation runs at most once per volume name per process. When no ownership is
     * configured (all of {@code ownerUid}/{@code ownerGid}/{@code rootPermissions} empty) this
     * degrades to a plain {@link #ensureVolume}, so the default behaviour is unchanged.
     *
     * @param volumeName      the named volume
     * @param ownerUid        owner uid for the volume root
     * @param ownerGid        owner gid for the volume root
     * @param rootPermissions octal permissions for the volume root (e.g. {@code "0777"}); empty skips init
     * @param initImage       lightweight image used for the one-off chown/chmod helper
     */
    public void ensureSharedVolume(String volumeName, OptionalInt ownerUid, OptionalInt ownerGid,
                                   Optional<String> rootPermissions, String initImage) {
        ensureVolume(volumeName);
        if (rootPermissions.isEmpty() && ownerUid.isEmpty() && ownerGid.isEmpty()) {
            return;
        }
        // Owner uid and gid are only meaningful together; reject a partial ownership config
        // rather than emitting a malformed `chown uid:` (whose trailing colon makes chown
        // resolve the login group and fail in busybox for an unknown uid).
        if (ownerUid.isPresent() != ownerGid.isPresent()) {
            throw new IllegalArgumentException(
                    "shared-volume owner-uid and owner-gid must be set together");
        }
        // Validate before splicing into the helper's `sh -c` (^[0-7]{3,4}$), so a typo can't
        // produce a mangled script that soft-fails.
        rootPermissions.ifPresent(p -> {
            if (!p.matches("^[0-7]{3,4}$")) {
                throw new IllegalArgumentException(
                        "shared-volume root-permissions must be 3-4 octal digits (e.g. \"0777\","
                                + " or \"2775\" for setgid): " + p);
            }
        });
        // computeIfAbsent runs the one-off init under a per-volume lock, so a concurrent launch for
        // the same volume waits for it to finish rather than mounting a still root:root 0755 root.
        // Returning null on failure leaves the volume unmemoised, so the next launch retries.
        initializedSharedVolumes.computeIfAbsent(volumeName, k -> {
            try {
                initSharedVolumeRoot(volumeName, ownerUid, ownerGid, rootPermissions, initImage);
                return Boolean.TRUE;
            } catch (RuntimeException e) {
                LOG.warnv("Failed to initialise shared volume {0} ownership: {1}", volumeName, e.getMessage());
                return null;
            }
        });
    }

    private void initSharedVolumeRoot(String volumeName, OptionalInt ownerUid, OptionalInt ownerGid,
                                      Optional<String> rootPermissions, String initImage) {
        String mount = "/floci-shared-volume";
        StringBuilder script = new StringBuilder();
        // ownerUid and ownerGid are validated to be present together by the caller, so the chown
        // always has both operands (no trailing colon). setgid is expressed via a 4-digit octal
        // rootPermissions (e.g. "2775").
        if (ownerUid.isPresent() && ownerGid.isPresent()) {
            script.append("chown ").append(ownerUid.getAsInt()).append(':').append(ownerGid.getAsInt())
                    .append(' ').append(mount).append(" && ");
        }
        rootPermissions.ifPresent(p -> script.append("chmod ").append(p).append(' ').append(mount).append(" && "));
        script.append("true");

        String image = (initImage != null && !initImage.isBlank()) ? initImage : "busybox:stable";
        imageCacheService.ensureImageExists(image);

        HostConfig hostConfig = HostConfig.newHostConfig().withMounts(List.of(
                new Mount().withType(MountType.VOLUME).withSource(volumeName).withTarget(mount)));
        CreateContainerResponse created = dockerClient.createContainerCmd(image)
                .withHostConfig(hostConfig)
                .withCmd("sh", "-c", script.toString())
                .withLabels(mergedLabels(null))
                .exec();
        String helperId = created.getId();
        try {
            dockerClient.startContainerCmd(helperId).exec();
            Integer status = dockerClient.waitContainerCmd(helperId)
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode(60, TimeUnit.SECONDS);
            if (status == null || status != 0) {
                // Throw so the caller leaves the volume unmemoised and retries on the next launch,
                // rather than leaving it root:root 0755 with no further attempt.
                throw new IllegalStateException("shared-volume init for " + volumeName
                        + " exited with status " + status + " (cmd: " + script + ")");
            }
            LOG.infov("Initialised shared volume {0} root (cmd: {1})", volumeName, script);
        } finally {
            try {
                dockerClient.removeContainerCmd(helperId).withForce(true).exec();
            } catch (Exception e) {
                // best-effort cleanup of the one-off helper
                LOG.debugv("Could not remove shared-volume init helper {0}: {1}", helperId, e.getMessage());
            }
        }
    }

    /** Default emulator labels merged with per-spec labels; spec labels win on collision. */
    private Map<String, String> mergedLabels(Map<String, String> specLabels) {
        Map<String, String> labels = ContainerStorageHelper.defaultLabels(config);
        if (specLabels != null) {
            labels.putAll(specLabels);
        }
        return labels;
    }

    /**
     * Removes a named Docker volume, ignoring errors if it does not exist or is still in use.
     */
    public void removeVolume(String volumeName) {
        try {
            dockerClient.removeVolumeCmd(volumeName).exec();
            LOG.debugv("Removed volume {0}", volumeName);
        } catch (NotFoundException ignored) {
        } catch (Exception e) {
            LOG.warnv("Error removing volume {0}: {1}", volumeName, e.getMessage());
        }
    }

    /**
     * Finds an existing container by name.
     *
     * @param name the container name to search for
     * @return the container if found
     */
    public Optional<Container> findByName(String name) {
        try {
            List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
            for (Container c : containers) {
                String[] names = c.getNames();
                if (names == null) continue;
                for (String n : names) {
                    // Docker prefixes names with /
                    if (n.equals("/" + name) || n.equals(name)) return Optional.of(c);
                }
            }
        } catch (Exception e) {
            LOG.debugv("Error searching for container {0}: {1}", name, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Adopts an existing container, starting it if stopped.
     * Useful for services that reuse containers across restarts.
     *
     * @param containerId the container ID to adopt
     * @param ports the container ports to resolve endpoints for
     * @return information about the adopted container
     */
    public ContainerInfo adopt(String containerId, List<Integer> ports) {
        LOG.infov("Adopting existing container {0}", containerId);

        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        boolean running = Boolean.TRUE.equals(inspect.getState().getRunning());

        if (!running) {
            dockerClient.startContainerCmd(containerId).exec();
            LOG.infov("Started adopted container {0}", containerId);
            inspect = dockerClient.inspectContainerCmd(containerId).exec();
        }

        Map<Integer, EndpointInfo> endpoints = new HashMap<>();
        Map<Integer, Integer> publishedHostPorts = new HashMap<>();
        for (int port : ports) {
            endpoints.put(port, resolveEndpoint(inspect, port));
            OptionalInt published = readPublishedHostPort(inspect, port);
            if (published.isPresent()) {
                publishedHostPorts.put(port, published.getAsInt());
            }
        }

        return new ContainerInfo(containerId, endpoints, publishedHostPorts);
    }

    /**
     * Reads the host port a container's internal port is published on, independent of
     * whether floci-az itself runs inside a container. Unlike {@link #resolveEndpoint} —
     * which switches to container-IP + internal port in container mode — this always
     * reads the port binding, for URIs consumed by the host-side Docker daemon.
     */
    private static OptionalInt readPublishedHostPort(InspectContainerResponse inspect, int containerPort) {
        Ports ports = inspect.getNetworkSettings().getPorts();
        if (ports != null) {
            Ports.Binding[] binding = ports.getBindings().get(ExposedPort.tcp(containerPort));
            if (binding != null && binding.length > 0) {
                try {
                    return OptionalInt.of(Integer.parseInt(binding[0].getHostPortSpec()));
                } catch (NumberFormatException e) {
                    LOG.debugv("Unparseable host port binding for container port {0}: {1}",
                            String.valueOf(containerPort), binding[0].getHostPortSpec());
                }
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Removes a container by name if it exists. Useful for cleaning up stale containers
     * from previous runs before creating a new one.
     *
     * @param name the container name to remove
     */
    public void removeIfExists(String name) {
        tryRemoveIfExists(name);
    }

    private boolean tryRemoveIfExists(String name) {
        try {
            dockerClient.removeContainerCmd(name).withForce(true).exec();
            LOG.infov("Removed stale container {0}", name);
            return true;
        } catch (NotFoundException ignored) {
            return false;
        } catch (Exception e) {
            LOG.debugv("Could not remove container {0}: {1}", name, e.getMessage());
            return false;
        }
    }

    /**
     * Removes containers whose owning emulator container no longer exists or is stopped. Name and
     * emulator-label checks constrain the candidates; an explicit owner label proves whether each
     * matching sidecar is orphaned. Ownerless legacy containers and containers whose owner cannot
     * be inspected are left untouched because they cannot be removed safely.
     *
     * @return the number of orphaned containers successfully removed
     */
    public int removeOrphanedContainers(
            String namePrefix, Map<String, String> requiredLabels, String ownerLabel) {
        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true).exec();
        int removed = 0;
        for (Container container : containers) {
            if (hasNamePrefix(container, namePrefix)
                    && hasRequiredLabels(container, requiredLabels)
                    && ownerIsStoppedOrMissing(container, ownerLabel)
                    && tryRemoveIfExists(container.getId())) {
                removed++;
            }
        }
        return removed;
    }

    private static boolean hasNamePrefix(Container container, String namePrefix) {
        String[] names = container.getNames();
        if (names == null) {
            return false;
        }
        for (String name : names) {
            String normalized = name.startsWith("/") ? name.substring(1) : name;
            if (normalized.startsWith(namePrefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRequiredLabels(
            Container container, Map<String, String> requiredLabels) {
        Map<String, String> labels = container.getLabels();
        return labels != null && requiredLabels.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(labels.get(entry.getKey())));
    }

    private boolean ownerIsStoppedOrMissing(Container container, String ownerLabel) {
        Map<String, String> labels = container.getLabels();
        String ownerId = labels == null ? null : labels.get(ownerLabel);
        if (ownerId == null || ownerId.isBlank()) {
            return false;
        }
        try {
            InspectContainerResponse owner = dockerClient.inspectContainerCmd(ownerId).exec();
            return owner.getState() != null && !Boolean.TRUE.equals(owner.getState().getRunning());
        } catch (NotFoundException e) {
            return true;
        } catch (Exception e) {
            LOG.warnv("Could not inspect owner container {0}; skipping orphan cleanup: {1}",
                    ownerId, e.getMessage());
            return false;
        }
    }

    /** Starts a previously-created (stopped) container in place, without removing it. */
    public void start(String containerId) {
        try {
            dockerClient.startContainerCmd(containerId).exec();
            LOG.infov("Started container {0}", containerId);
        } catch (NotFoundException e) {
            LOG.warnv("Container {0} not found; cannot start", containerId);
        } catch (Exception e) {
            LOG.warnv("Error starting container {0}: {1}", containerId, e.getMessage());
        }
    }

    /** Stops a running container without removing it, so it can be started again later. */
    public void stop(String containerId, int timeoutSeconds) {
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(timeoutSeconds).exec();
            LOG.infov("Stopped container {0}", containerId);
        } catch (NotFoundException e) {
            LOG.debugv("Container {0} not found (already stopped/removed)", containerId);
        } catch (Exception e) {
            LOG.warnv("Error stopping container {0}: {1}", containerId, e.getMessage());
        }
    }

    /** Restarts a container in place (stop + start), keeping it for further use. */
    public void restart(String containerId, int timeoutSeconds) {
        try {
            dockerClient.restartContainerCmd(containerId).withTimeout(timeoutSeconds).exec();
            LOG.infov("Restarted container {0}", containerId);
        } catch (NotFoundException e) {
            LOG.warnv("Container {0} not found; cannot restart", containerId);
        } catch (Exception e) {
            LOG.warnv("Error restarting container {0}: {1}", containerId, e.getMessage());
        }
    }

    /**
     * Returns whether the container is currently running. A missing container is treated as
     * not-running; any other Docker error (e.g. an inspect timeout under daemon overload) is also
     * treated as not-running, so a hung/dead container is not reused — a false negative merely
     * triggers a clean cold-start, which is far cheaper than blocking until an invocation timeout.
     *
     * @param containerId the container ID to inspect
     * @return true only if the container exists and is reported as running; false on any error
     */
    public boolean isContainerRunning(String containerId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            return Boolean.TRUE.equals(inspect.getState().getRunning());
        } catch (NotFoundException e) {
            return false;
        } catch (Exception e) {
            // Treat an inspect failure/timeout as NOT running. Under Docker-daemon overload,
            // returning true here would let callers "reuse" dead/hung containers, so the
            // invocation blocked until its timeout every time. A false negative merely
            // triggers a clean cold-start, which is far cheaper than a hang.
            LOG.warnv("Liveness check failed for container {0}; treating as not running: {1}",
                    containerId, e.getMessage());
            return false;
        }
    }

    /**
     * Resolves the endpoint (host and port) to connect to a specific container port.
     *
     * @param containerId the container ID
     * @param containerPort the container port to resolve
     * @return the endpoint information
     */
    public EndpointInfo resolveEndpoint(String containerId, int containerPort) {
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        return resolveEndpoint(inspect, containerPort, null);
    }

    /**
     * Returns the underlying DockerClient for operations not covered by this manager.
     * Prefer using manager methods when available.
     */
    public DockerClient getDockerClient() {
        return dockerClient;
    }

    /** Result of a command executed inside a running container. */
    public record ExecResult(int exitCode, String output) {}

    /**
     * Runs a command inside a running container and waits for it to finish.
     * The command is passed as an argv array directly to the container runtime — no shell
     * is involved, so arguments need no shell quoting.
     */
    public ExecResult execInContainer(String containerId, String... cmd) {
        try {
            String execId = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmd)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec()
                    .getId();

            StringBuilder output = new StringBuilder();
            dockerClient.execStartCmd(execId)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    })
                    .awaitCompletion(60, TimeUnit.SECONDS);

            Long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            return new ExecResult(exitCode == null ? -1 : exitCode.intValue(), output.toString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted executing command in container " + containerId, ie);
        }
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

    /**
     * Returns {@code true} if the container runtime (Docker, Moby, or Podman) has a volume
     * with the given name. The volume does not need to be attached to the current container.
     * <p>
     * This method uses the Docker Engine API ({@code /volumes/{name}}) which is supported
     * by Docker, Moby, and Podman runtimes on all operating systems.
     *
     * @param name the volume name to look up
     * @return {@code true} if the volume exists, {@code false} otherwise
     */
    public boolean volumeExists(String name) {
        if (name == null || name.isBlank()) return false;
        // Is a Unix absolute or relative path (e.g. "/var/lib/data", "./data", "../data")
        if (name.startsWith("/") || name.startsWith(".")) return false;
        // Is a Windows absolute path (e.g. "C:\Users\data", "D:/sources/data")
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

        if (spec.cgroupnsMode() != null && !spec.cgroupnsMode().isBlank()) {
            hostConfig.withCgroupnsMode(spec.cgroupnsMode());
        }

        // Supplementary groups (Docker --group-add), e.g. to give a process access to a
        // group-shared volume without changing its primary uid/gid.
        if (spec.groupAdd() != null && !spec.groupAdd().isEmpty()) {
            hostConfig.withGroupAdd(spec.groupAdd());
        }

        if (spec.hasMemoryLimit()) hostConfig.withMemory(spec.memoryBytes());

        if (spec.hasPortBindings()) {
            Ports ports = new Ports();
            for (Map.Entry<Integer, Integer> entry : spec.portBindings().entrySet()) {
                int containerPort = entry.getKey();
                int hostPort = entry.getValue() == 0 ? portAllocator.allocateAny() : entry.getValue();
                ports.bind(ExposedPort.tcp(containerPort), Ports.Binding.bindPort(hostPort));
                LOG.debugv("Port binding: {0} -> {1}",
                        String.valueOf(containerPort), String.valueOf(hostPort));
            }
            hostConfig.withPortBindings(ports);
        }

        // Only set networkMode during creation when there are no host port bindings;
        // containers with port bindings connect to the named network via connectToNetworkCmd()
        // after start to avoid suppressing port publishing on Docker Desktop (macOS).
        if (spec.networkMode() != null && !spec.networkMode().isBlank()
                && (!spec.hasPortBindings() || containerDetector.isRunningInContainer())) {
            hostConfig.withNetworkMode(spec.networkMode());
        }

        if (spec.mounts() != null && !spec.mounts().isEmpty()) hostConfig.withMounts(spec.mounts());
        if (spec.binds() != null && !spec.binds().isEmpty())
            hostConfig.withBinds(spec.binds().toArray(new Bind[0]));
        if (spec.extraHosts() != null && !spec.extraHosts().isEmpty())
            hostConfig.withExtraHosts(spec.extraHosts().toArray(new String[0]));
        if (spec.hasLogConfig()) hostConfig.withLogConfig(spec.logConfig());
        // DNS servers — used to inject floci-az's embedded DNS so spawned containers
        // can resolve emulated hostnames to floci-az's Docker network IP.
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
            // Native mode: use localhost and the bound host port
            var bindings = inspect.getNetworkSettings().getPorts().getBindings();
            var binding = bindings.get(ExposedPort.tcp(containerPort));
            if (binding != null && binding.length > 0) {
                int hostPort = Integer.parseInt(binding[0].getHostPortSpec());
                return new EndpointInfo("localhost", hostPort);
            }
            // Fallback to container port
            return new EndpointInfo("localhost", containerPort);
        } else {
            // Container mode: use container IP on the docker network.
            // Prefer the configured network's IP — the container may be on multiple
            // networks (bridge + the configured network) when connectToNetworkCmd()
            // is used instead of withNetworkMode() during creation.
            String containerIp = resolveContainerIp(inspect, preferredNetwork);
            return new EndpointInfo(containerIp, containerPort);
        }
    }

    private String resolveContainerIp(InspectContainerResponse inspect, String preferredNetwork) {
        var networks = inspect.getNetworkSettings().getNetworks();
        if (networks != null) {
            // Prefer the configured network so that when the container is on both
            // bridge (default) and the service network, we return the right IP.
            if (preferredNetwork != null && networks.containsKey(preferredNetwork)) {
                String ip = networks.get(preferredNetwork).getIpAddress();
                if (ip != null && !ip.isBlank()) return ip;
            }
            // Fall back to any network
            for (Map.Entry<String, ContainerNetwork> entry : networks.entrySet()) {
                String ip = entry.getValue().getIpAddress();
                if (ip != null && !ip.isBlank()) return ip;
            }
        }
        // Fallback to the global IP
        return inspect.getNetworkSettings().getIpAddress();
    }

    /**
     * Information about a created or adopted container.
     *
     * @param containerId the Docker container ID
     * @param endpoints map of container port to resolved endpoint (host:port for connection)
     * @param publishedHostPorts map of container port to the host port it is published on;
     *                           a port without a binding is absent
     */
    public record ContainerInfo(
            String containerId,
            Map<Integer, EndpointInfo> endpoints,
            Map<Integer, Integer> publishedHostPorts
    ) {
        public ContainerInfo(String containerId, Map<Integer, EndpointInfo> endpoints) {
            this(containerId, endpoints, Map.of());
        }

        /**
         * Gets the endpoint for a specific container port.
         */
        public EndpointInfo getEndpoint(int containerPort) {
            return endpoints.get(containerPort);
        }

        /**
         * Gets the host port a container port is published on, regardless of whether
         * floci-az itself runs inside a container. Empty when the port has no binding.
         */
        public OptionalInt publishedHostPort(int containerPort) {
            Integer published = publishedHostPorts.get(containerPort);
            return published != null ? OptionalInt.of(published) : OptionalInt.empty();
        }
    }

    /**
     * Network endpoint information for connecting to a container.
     *
     * @param host the host to connect to (localhost in native mode, container IP in Docker mode)
     * @param port the port to connect to
     */
    public record EndpointInfo(String host, int port) {
        @Override
        public String toString() {
            return host + ":" + port;
        }
    }
}
