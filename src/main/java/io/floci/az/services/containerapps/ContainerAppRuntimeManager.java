package io.floci.az.services.containerapps;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.ContainerStorageHelper;
import io.floci.az.services.containerapps.ContainerAppsModels.ContainerAppState;
import io.floci.az.services.containerapps.ContainerAppsModels.RevisionState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs Container App revision replicas through shared Docker lifecycle infrastructure. */
@ApplicationScoped
public class ContainerAppRuntimeManager {

    private static final Logger LOG = Logger.getLogger(ContainerAppRuntimeManager.class);

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final Map<String, RevisionRuntime> runtimes = new ConcurrentHashMap<>();

    @Inject
    public ContainerAppRuntimeManager(ContainerBuilder containerBuilder,
                                      ContainerLifecycleManager lifecycleManager,
                                      EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
    }

    public synchronized void startRevision(ContainerAppState app, RevisionState revision,
                                           JsonNode configuration, int replicaCount, int targetPort) {
        stopRevision(app, revision.getName());

        List<ReplicaRuntime> replicas = new ArrayList<>();
        try {
            for (int replica = 0; replica < replicaCount; replica++) {
                replicas.add(startReplica(app, revision, configuration, replica, targetPort));
            }
            runtimes.put(runtimeKey(app, revision.getName()), new RevisionRuntime(replicas));
            LOG.infov("Started Container App revision {0} with {1} replicas",
                    revision.getName(), replicaCount);
        } catch (RuntimeException e) {
            replicas.forEach(this::stopReplica);
            throw e;
        }
    }

    public synchronized void stopRevision(ContainerAppState app, String revisionName) {
        RevisionRuntime runtime = runtimes.remove(runtimeKey(app, revisionName));
        if (runtime != null) {
            runtime.replicas().forEach(this::stopReplica);
            LOG.infov("Stopped Container App revision {0}", revisionName);
        }
    }

    public synchronized void stopApp(ContainerAppState app) {
        app.getRevisions().forEach(revision -> stopRevision(app, revision.getName()));
    }

    public synchronized void stopAll() {
        runtimes.values().forEach(runtime -> runtime.replicas().forEach(this::stopReplica));
        runtimes.clear();
    }

    public Optional<ContainerLifecycleManager.EndpointInfo> endpoint(ContainerAppState app,
                                                                     String revisionName) {
        RevisionRuntime runtime = runtimes.get(runtimeKey(app, revisionName));
        if (runtime == null || runtime.replicas().isEmpty()) {
            return Optional.empty();
        }
        int start = Math.floorMod(runtime.nextReplica().getAndIncrement(), runtime.replicas().size());
        for (int offset = 0; offset < runtime.replicas().size(); offset++) {
            ReplicaRuntime replica = runtime.replicas().get((start + offset) % runtime.replicas().size());
            if (replica.ingressEndpoint() != null && isReachable(replica.ingressEndpoint(), 250)) {
                return Optional.of(replica.ingressEndpoint());
            }
        }
        return Optional.empty();
    }

    public boolean isInternalCaller(String remoteAddress) {
        return runtimes.values().stream()
                .flatMap(runtime -> runtime.replicas().stream())
                .anyMatch(replica -> ContainerLifecycleManager.matchesAnyAddress(
                        remoteAddress, replica.networkAddresses()));
    }

    private ReplicaRuntime startReplica(ContainerAppState app, RevisionState revision,
                                        JsonNode configuration, int replicaIndex, int targetPort) {
        JsonNode containers = revision.getTemplate().path("containers");
        if (!containers.isArray() || containers.isEmpty()) {
            throw new IllegalArgumentException("properties.template.containers must contain at least one container");
        }

        Map<String, String> secrets = secrets(configuration);
        List<String> containerIds = new ArrayList<>();
        ContainerLifecycleManager.EndpointInfo ingressEndpoint = null;
        String networkNamespace = null;
        List<String> networkAddresses = List.of();

        try {
            for (int containerIndex = 0; containerIndex < containers.size(); containerIndex++) {
                JsonNode container = containers.get(containerIndex);
                String image = requiredText(container, "image");
                String containerName = containerName(app, revision, replicaIndex, containerIndex);
                lifecycleManager.removeIfExists(containerName);

                ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                        .withName(containerName)
                        .withEnv(environment(container.path("env"), secrets))
                        .withLogRotation();
                if (networkNamespace == null) {
                    builder.withDockerNetwork(config.services().dockerNetwork());
                } else {
                    builder.withNetworkMode("container:" + networkNamespace);
                }

                List<String> command = stringList(container.path("command"));
                List<String> args = stringList(container.path("args"));
                if (!command.isEmpty()) {
                    builder.withEntrypoint(command);
                }
                if (!args.isEmpty()) {
                    builder.withCmd(args);
                }
                if (containerIndex == 0 && targetPort > 0) {
                    builder.withDynamicPort(targetPort);
                }

                ContainerSpec spec = builder.build();
                ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
                containerIds.add(info.containerId());
                if (networkNamespace == null) {
                    networkNamespace = info.containerId();
                    networkAddresses = lifecycleManager.containerAddresses(networkNamespace);
                }
                if (containerIndex == 0 && targetPort > 0) {
                    ingressEndpoint = info.getEndpoint(targetPort);
                }
            }
            if (ingressEndpoint != null) {
                waitUntilReady(ingressEndpoint, revision.getName(), replicaIndex);
            }
            return new ReplicaRuntime(List.copyOf(containerIds), ingressEndpoint, networkAddresses);
        } catch (RuntimeException e) {
            stopReplica(new ReplicaRuntime(List.copyOf(containerIds), ingressEndpoint, networkAddresses));
            throw e;
        }
    }

    private void stopReplica(ReplicaRuntime replica) {
        for (int index = replica.containerIds().size() - 1; index >= 0; index--) {
            lifecycleManager.stopAndRemove(replica.containerIds().get(index), null);
        }
    }

    private void waitUntilReady(ContainerLifecycleManager.EndpointInfo endpoint,
                                String revisionName, int replicaIndex) {
        Duration timeout = Duration.ofSeconds(config.services().containerApps().ingressTimeoutSeconds());
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (isReachable(endpoint, 500)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Container App readiness", e);
            }
        }
        throw new IllegalStateException("Container App revision '" + revisionName + "' replica "
                + replicaIndex + " did not become ready on targetPort " + endpoint.port()
                + " within " + timeout.toSeconds() + " seconds");
    }

    private static boolean isReachable(ContainerLifecycleManager.EndpointInfo endpoint, int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), timeoutMillis);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Map<String, String> secrets(JsonNode configuration) {
        Map<String, String> result = new HashMap<>();
        for (JsonNode secret : configuration.path("secrets")) {
            if (secret.hasNonNull("name") && secret.has("value")) {
                result.put(secret.path("name").asText(), secret.path("value").asText());
            }
        }
        return result;
    }

    private static List<String> environment(JsonNode envNode, Map<String, String> secrets) {
        List<String> result = new ArrayList<>();
        for (JsonNode variable : envNode) {
            String name = variable.path("name").asText();
            if (name.isBlank()) {
                continue;
            }
            if (variable.hasNonNull("secretRef")) {
                String secretName = variable.path("secretRef").asText();
                if (!secrets.containsKey(secretName)) {
                    throw new IllegalArgumentException("Secret '" + secretName + "' was not found");
                }
                result.add(name + "=" + secrets.get(secretName));
            } else {
                result.add(name + "=" + variable.path("value").asText(""));
            }
        }
        return result;
    }

    private String containerName(ContainerAppState app, RevisionState revision,
                                 int replicaIndex, int containerIndex) {
        String raw = "ca-" + app.getName() + "-" + revision.getName()
                + "-" + replicaIndex + "-" + containerIndex;
        String sanitized = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
        if (sanitized.length() > 55) {
            sanitized = sanitized.substring(0, 46) + "-" + Integer.toHexString(raw.hashCode());
        }
        return ContainerStorageHelper.dockerName(config, sanitized);
    }

    private static String runtimeKey(ContainerAppState app, String revisionName) {
        return app.storageKey() + "/" + revisionName.toLowerCase(Locale.ROOT);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Container " + field + " is required");
        }
        return value;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(value -> values.add(value.asText()));
        }
        return values;
    }

    private record RevisionRuntime(List<ReplicaRuntime> replicas, AtomicInteger nextReplica) {
        private RevisionRuntime(List<ReplicaRuntime> replicas) {
            this(List.copyOf(replicas), new AtomicInteger());
        }
    }

    private record ReplicaRuntime(List<String> containerIds,
                                  ContainerLifecycleManager.EndpointInfo ingressEndpoint,
                                  List<String> networkAddresses) {
    }
}
