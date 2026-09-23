package io.floci.az.services.aci;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerStateInfo;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.ContainerStorageHelper;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.services.aci.AciModels.ContainerGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Manages the Docker lifecycle of the containers backing a container group in non-mocked mode
 * ({@code floci-az.services.aci.mocked=false}).
 *
 * <h2>Pod semantics</h2>
 * <p>The group's first container owns the network identity: it joins the service Docker network
 * and carries all published port bindings (host ports pre-allocated from the configured range,
 * preferring the container port itself so the published port matches the requested one).
 * Containers 2..n join the primary's network namespace via
 * {@code network_mode=container:&lt;primaryId&gt;}, so group members reach each other on
 * {@code localhost} like real ACI. Docker rejects creates combining that mode with port
 * bindings, DNS, or extra hosts, so secondaries carry none of those (they also cannot resolve
 * emulated hostnames — no embedded DNS). If the primary has already exited when a secondary is
 * created (a fast batch container), secondaries fall back to the shared Docker network.</p>
 *
 * <p>Ordering: the primary starts first and secondaries join it; a group {@code restart}
 * restarts the primary first, then the secondaries — a Docker restart of the netns owner tears
 * the shared namespace down, so joiners must bounce after it.</p>
 *
 * <p>Per the floci-az sidecar rules this never calls {@code dockerClient} directly — it goes
 * through {@link ContainerBuilder} and {@link ContainerLifecycleManager} — and Docker failures
 * surface as exceptions so the handler can report an honest {@code provisioningState=Failed}.</p>
 */
@ApplicationScoped
public class AciContainerGroupManager {

    private static final Logger LOG = Logger.getLogger(AciContainerGroupManager.class);

    private static final int STOP_TIMEOUT_SECONDS = 10;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final PortAllocator portAllocator;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;

    @Inject
    public AciContainerGroupManager(ContainerBuilder containerBuilder,
                                    ContainerLifecycleManager lifecycleManager,
                                    PortAllocator portAllocator,
                                    ContainerDetector containerDetector,
                                    EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.portAllocator = portAllocator;
        this.containerDetector = containerDetector;
        this.config = config;
    }

    /**
     * Launches the group's containers (primary first, then netns-joined secondaries) and records
     * container ids and allocated host ports on {@code group}. Throws on any Docker failure —
     * after cleaning up whatever was already started — so the handler reports {@code Failed}.
     */
    public void startGroup(ContainerGroup group) {
        List<Map<String, Object>> containers = containers(group);
        Map<String, String> containerIds = new LinkedHashMap<>();
        List<Integer> allocatedPorts = new ArrayList<>();
        try {
            Map<Integer, Integer> portBindings = allocatePublishedPorts(group, allocatedPorts);

            String primaryId = null;
            for (int i = 0; i < containers.size(); i++) {
                Map<String, Object> container = containers.get(i);
                String containerName = String.valueOf(container.get("name"));
                boolean primary = i == 0;

                boolean joinPrimary = !primary && primaryId != null
                        && lifecycleManager.isContainerRunning(primaryId);
                String containerId = launchContainer(group, container, containerName,
                        primary ? portBindings : Map.of(),
                        joinPrimary ? primaryId : null);
                containerIds.put(containerName, containerId);
                if (primary) {
                    primaryId = containerId;
                }
            }

            group.setContainerIds(containerIds);
            group.setAllocatedHostPorts(allocatedPorts.isEmpty() ? null : allocatedPorts);
            LOG.infov("Container group {0}: started {1} container(s), published ports {2}",
                    group.getName(), containerIds.size(), portBindings);
        } catch (Exception e) {
            // Roll back partial starts so a Failed group leaves nothing behind.
            containerIds.values().forEach(id -> lifecycleManager.stopAndRemove(id, null));
            allocatedPorts.forEach(portAllocator::release);
            group.setContainerIds(null);
            group.setAllocatedHostPorts(null);
            throw e;
        }
    }

    private String launchContainer(ContainerGroup group, Map<String, Object> container,
                                   String containerName, Map<Integer, Integer> portBindings,
                                   String joinNetnsOfContainerId) {
        Map<String, Object> props = asMap(container.get("properties"));
        String image = String.valueOf(props.get("image"));
        String dockerName = containerDockerName(group, containerName);

        lifecycleManager.removeIfExists(dockerName);

        ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                .withName(dockerName)
                .withLabel("floci_az_aci_group", group.storageKey())
                .withLogRotation();

        // ACI's `command` replaces the image's entrypoint AND cmd (unlike docker run's trailing
        // args, which only override CMD) — map it to the entrypoint so images that declare an
        // ENTRYPOINT (e.g. hashicorp/http-echo) don't get the binary duplicated in the argv.
        List<String> command = stringList(props.get("command"));
        if (!command.isEmpty()) {
            // Entrypoint alone is not enough: Docker appends the image's own CMD as arguments, so
            // the container would receive a different argv than the group asked for.
            builder.withEntrypoint(command).withCmd(List.of());
        }
        for (Map<String, Object> envVar : listOfMaps(props.get("environmentVariables"))) {
            String value = envVar.get("secureValue") != null
                    ? String.valueOf(envVar.get("secureValue"))
                    : String.valueOf(envVar.getOrDefault("value", ""));
            builder.withEnv(String.valueOf(envVar.get("name")), value);
        }
        memoryMb(props).ifPresent(builder::withMemoryMb);

        if (joinNetnsOfContainerId != null) {
            // Secondary in the pod: shares the primary's network namespace. Docker rejects
            // port bindings / DNS / extra hosts in this mode, so none are set here.
            builder.withNetworkMode("container:" + joinNetnsOfContainerId);
        } else {
            builder.withDockerNetwork(config.services().dockerNetwork());
            portBindings.forEach(builder::withPortBinding);
        }

        Map<String, Map<String, Object>> secretMounts = applyVolumeMounts(group, props, builder);

        ContainerSpec spec = builder.build();
        if (secretMounts.isEmpty()) {
            return lifecycleManager.createAndStart(spec).containerId();
        }
        // Secret volumes need content injected between create and start (docker-cp semantics).
        String containerId = lifecycleManager.create(spec);
        try {
            for (Map.Entry<String, Map<String, Object>> mount : secretMounts.entrySet()) {
                copySecretFiles(containerId, mount.getKey(), mount.getValue());
            }
            lifecycleManager.startCreated(containerId, spec);
            return containerId;
        } catch (Exception e) {
            lifecycleManager.removeIfExists(containerId);
            throw e;
        }
    }

    /**
     * Wires the container's volumeMounts against the group's volume definitions. emptyDir
     * volumes become named Docker volumes; secret volumes additionally return
     * {@code mountPath → secret entries} so the caller injects the decoded files pre-start.
     * (azureFile/gitRepo were rejected at the protocol boundary.)
     */
    private Map<String, Map<String, Object>> applyVolumeMounts(ContainerGroup group,
                                                               Map<String, Object> containerProps,
                                                               ContainerBuilder.Builder builder) {
        Map<String, Map<String, Object>> secretMounts = new LinkedHashMap<>();
        Map<String, Map<String, Object>> volumesByName = new LinkedHashMap<>();
        for (Map<String, Object> volume : listOfMaps(group.getProperties().get("volumes"))) {
            volumesByName.put(String.valueOf(volume.get("name")), volume);
        }
        for (Map<String, Object> mount : listOfMaps(containerProps.get("volumeMounts"))) {
            String volumeName = String.valueOf(mount.get("name"));
            String mountPath = String.valueOf(mount.get("mountPath"));
            boolean readOnly = Boolean.TRUE.equals(mount.get("readOnly"));
            Map<String, Object> volume = volumesByName.get(volumeName);
            if (volume == null) {
                throw new IllegalArgumentException("volumeMount '" + volumeName
                        + "' has no matching volume definition");
            }
            String dockerVolume = volumeDockerName(group, volumeName);
            lifecycleManager.ensureVolume(dockerVolume);
            builder.withNamedVolume(dockerVolume, mountPath, readOnly);
            if (volume.get("secret") instanceof Map<?, ?> secret && !secret.isEmpty()) {
                secretMounts.put(mountPath, asMap(secret));
            }
        }
        return secretMounts;
    }

    private void copySecretFiles(String containerId, String mountPath, Map<String, Object> entries) {
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            byte[] content;
            try {
                content = Base64.getDecoder().decode(String.valueOf(entry.getValue()));
            } catch (IllegalArgumentException e) {
                // Azure secret volume values are base64; tolerate raw text rather than failing the group.
                content = String.valueOf(entry.getValue()).getBytes(StandardCharsets.UTF_8);
            }
            lifecycleManager.copyBytesToContainer(containerId, content,
                    mountPath + "/" + entry.getKey());
        }
    }

    /**
     * Pre-allocates a host port for each published group port, preferring the container port
     * itself so `localhost:<port>` works natively, falling back to the configured range.
     */
    private Map<Integer, Integer> allocatePublishedPorts(ContainerGroup group, List<Integer> allocated) {
        Map<Integer, Integer> bindings = new LinkedHashMap<>();
        for (Map<String, Object> port : listOfMaps(ipAddress(group).get("ports"))) {
            int containerPort = ((Number) port.get("port")).intValue();
            int hostPort;
            try {
                hostPort = portAllocator.allocate(containerPort, containerPort);
            } catch (Exception e) {
                hostPort = portAllocator.allocate(config.services().aci().basePort(),
                        config.services().aci().maxPort());
            }
            bindings.put(containerPort, hostPort);
            allocated.add(hostPort);
        }
        return bindings;
    }

    /** True once every container of the group is running — the readiness signal. */
    public boolean isRunning(ContainerGroup group) {
        Map<String, String> ids = group.getContainerIds();
        return ids != null && !ids.isEmpty()
                && ids.values().stream().allMatch(lifecycleManager::isContainerRunning);
    }

    /** Live state of every backing container, empty when the group has none. */
    public List<ContainerStateInfo> containerStates(ContainerGroup group) {
        Map<String, String> ids = group.getContainerIds();
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.values().stream()
                .map(lifecycleManager::containerState)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Every container has been created and has either run or terminated. A container that exits is
     * routine, not a failure: a short-lived command, or a Never/OnFailure restart policy, ends that
     * way by design. It has still started, so the deployment is done and the group must leave
     * Creating rather than wait for a simultaneity that will never come.
     */
    public boolean allContainersStarted(ContainerGroup group) {
        Map<String, String> ids = group.getContainerIds();
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        List<ContainerStateInfo> states = containerStates(group);
        return states.size() == ids.size()
                && states.stream().allMatch(info -> info.isRunning() || info.exitCode() != null);
    }

    /** Per-container runtime state for instanceView; empty for unknown containers. */
    public Optional<ContainerStateInfo> containerState(ContainerGroup group, String containerName) {
        String containerId = containerId(group, containerName);
        return containerId == null ? Optional.empty() : lifecycleManager.containerState(containerId);
    }

    /** Docker log output for one container of the group. */
    public String logs(ContainerGroup group, String containerName, Integer tail, boolean timestamps) {
        String containerId = containerId(group, containerName);
        return containerId == null ? "" : lifecycleManager.logs(containerId, tail, timestamps);
    }

    /** The IP to report as {@code ipAddress.ip}: primary's network IP in Docker, 127.0.0.1 natively. */
    public String groupIp(ContainerGroup group) {
        if (containerDetector.isRunningInContainer()) {
            String primaryId = primaryContainerId(group);
            List<Map<String, Object>> ports = listOfMaps(ipAddress(group).get("ports"));
            if (primaryId != null && !ports.isEmpty()) {
                int firstPort = ((Number) ports.get(0).get("port")).intValue();
                try {
                    return lifecycleManager.resolveEndpoint(primaryId, firstPort).host();
                } catch (Exception e) {
                    LOG.debugv("Could not resolve group IP for {0}: {1}", group.getName(), e.getMessage());
                }
            }
        }
        return "127.0.0.1";
    }

    /** start action: primary first so the shared netns exists before joiners come up. */
    /**
     * The shared lifecycle helpers log and swallow Docker errors, so the only honest signal an
     * action succeeded is the state the containers actually reached. Each action reports that
     * observed post-condition rather than the absence of an exception.
     *
     * @return true when every container reached the expected state
     */
    public boolean startGroupContainers(ContainerGroup group) {
        forEachContainerId(group, false, lifecycleManager::start);
        return isRunning(group);
    }

    /** stop action: secondaries first, the netns owner last. */
    public boolean stopGroupContainers(ContainerGroup group) {
        forEachContainerId(group, true, id -> lifecycleManager.stop(id, STOP_TIMEOUT_SECONDS));
        return noneRunning(group);
    }

    /**
     * restart action: the primary restarts first (tearing down the shared netns), then each
     * secondary bounces so it rejoins the fresh namespace.
     */
    public boolean restartGroupContainers(ContainerGroup group) {
        forEachContainerId(group, false, id -> lifecycleManager.restart(id, STOP_TIMEOUT_SECONDS));
        return isRunning(group);
    }

    /** No container of the group is running: the post-condition of a successful stop. */
    private boolean noneRunning(ContainerGroup group) {
        Map<String, String> ids = group.getContainerIds();
        return ids == null || ids.isEmpty()
                || ids.values().stream().noneMatch(lifecycleManager::isContainerRunning);
    }

    /** Delete: remove containers (secondaries first), volumes, and release published ports. */
    public void removeGroup(ContainerGroup group) {
        forEachContainerId(group, true, id -> lifecycleManager.stopAndRemove(id, null));
        for (Map<String, Object> volume : listOfMaps(
                group.getProperties() == null ? null : group.getProperties().get("volumes"))) {
            lifecycleManager.removeVolume(volumeDockerName(group, String.valueOf(volume.get("name"))));
        }
        if (group.getAllocatedHostPorts() != null) {
            group.getAllocatedHostPorts().forEach(portAllocator::release);
        }
        group.setContainerIds(null);
        group.setAllocatedHostPorts(null);
    }

    private void forEachContainerId(ContainerGroup group, boolean reversed,
                                    Consumer<String> action) {
        Map<String, String> ids = group.getContainerIds();
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<String> ordered = new ArrayList<>(ids.values());
        if (reversed) {
            Collections.reverse(ordered);
        }
        ordered.forEach(action);
    }

    private static String containerId(ContainerGroup group, String containerName) {
        return group.getContainerIds() == null ? null : group.getContainerIds().get(containerName);
    }

    private static String primaryContainerId(ContainerGroup group) {
        Map<String, String> ids = group.getContainerIds();
        return ids == null || ids.isEmpty() ? null : ids.values().iterator().next();
    }

    // ── Naming ─────────────────────────────────────────────────────────────────

    String containerDockerName(ContainerGroup group, String containerName) {
        return ContainerStorageHelper.dockerName(config,
                "aci-" + groupSlug(group) + "-" + sanitize(containerName));
    }

    String volumeDockerName(ContainerGroup group, String volumeName) {
        return ContainerStorageHelper.dockerName(config,
                "aci-" + groupSlug(group) + "-vol-" + sanitize(volumeName));
    }

    /** Short, deterministic, collision-resistant slug: group name plus a hash of sub/rg/name. */
    private static String groupSlug(ContainerGroup group) {
        String name = sanitize(group.getName());
        if (name.length() > 20) {
            name = name.substring(0, 20);
        }
        return name + "-" + Integer.toHexString(group.storageKey().hashCode());
    }

    private static String sanitize(String name) {
        return name == null ? "unknown" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ── Body access helpers ────────────────────────────────────────────────────

    private static List<Map<String, Object>> containers(ContainerGroup group) {
        return listOfMaps(group.getProperties() == null ? null : group.getProperties().get("containers"));
    }

    private static Map<String, Object> ipAddress(ContainerGroup group) {
        if (group.getProperties() != null && group.getProperties().get("ipAddress") instanceof Map<?, ?> ip) {
            return asMap(ip);
        }
        return Map.of();
    }

    private static Optional<Integer> memoryMb(Map<String, Object> containerProps) {
        if (containerProps.get("resources") instanceof Map<?, ?> resources
                && asMap(resources).get("requests") instanceof Map<?, ?> requests
                && asMap(requests).get("memoryInGB") instanceof Number memoryGb) {
            return Optional.of((int) (memoryGb.doubleValue() * 1024));
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
