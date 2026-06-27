package io.floci.az.services.acr;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.PortAllocator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Manages the lifecycle of the single shared {@code registry:2} container that backs every emulated
 * Azure Container Registry. There is one container per floci-az instance, started lazily on first use
 * and reused across all registries — mirroring the AWS ECR design in the sibling emulator.
 *
 * <p>Registries are isolated within the shared registry by an internal repository prefix
 * ({@code {registryName}/{repo}}), so {@code loginServer} carries the registry name as a path segment:
 * {@code localhost:{port}/{registryName}}. The backing registry runs <b>anonymous</b> — admin
 * credentials are returned by the management plane but not enforced at the data plane.</p>
 */
@ApplicationScoped
public class AcrRegistryManager {

    private static final Logger LOG = Logger.getLogger(AcrRegistryManager.class);
    private static final int REGISTRY_PORT = 5000;
    private static final String SHARED_NAME = "floci-az-acr-registry";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;

    private volatile boolean started;
    private volatile int hostPort;
    private volatile String containerId;
    private volatile String internalEndpoint;

    @Inject
    public AcrRegistryManager(ContainerBuilder containerBuilder,
                              ContainerLifecycleManager lifecycleManager,
                              ContainerDetector containerDetector,
                              PortAllocator portAllocator,
                              EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.config = config;
    }

    /** Lazily starts (once) the shared {@code registry:2} container. Idempotent and thread-safe. */
    public synchronized void ensureStarted() {
        if (started) {
            return;
        }
        EmulatorConfig.AcrConfig acrConfig = config.services().acr();
        lifecycleManager.removeIfExists(SHARED_NAME);

        int chosenPort = portAllocator.allocate(acrConfig.basePort(), acrConfig.maxPort());
        try {
            ContainerSpec spec = containerBuilder.newContainer(acrConfig.defaultImage())
                    .withName(SHARED_NAME)
                    .withEnv("REGISTRY_STORAGE_DELETE_ENABLED", "true")
                    .withEnv("REGISTRY_HTTP_ADDR", "0.0.0.0:" + REGISTRY_PORT)
                    .withPortBinding(REGISTRY_PORT, chosenPort)
                    .withDockerNetwork(config.services().dockerNetwork())
                    .withLogRotation()
                    .build();

            ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
            this.containerId = info.containerId();
            this.hostPort = chosenPort;

            ContainerLifecycleManager.EndpointInfo ep = info.getEndpoint(REGISTRY_PORT);
            if (containerDetector.isRunningInContainer()) {
                this.internalEndpoint = SHARED_NAME + ":" + REGISTRY_PORT;
            } else {
                this.internalEndpoint = ep != null ? ep.host() + ":" + ep.port() : "localhost:" + chosenPort;
            }
            this.started = true;
            LOG.infov("Started shared ACR registry {0} on host port {1}", SHARED_NAME, String.valueOf(chosenPort));
        } catch (Exception e) {
            throw new RuntimeException("Failed to start shared ACR registry container: " + e.getMessage(), e);
        }
    }

    /** The path-prefixed {@code loginServer} for a registry (host[:port]/{registryName}). */
    public String loginServer(String registryName) {
        String host = containerDetector.isRunningInContainer()
                ? SHARED_NAME + ":" + REGISTRY_PORT
                : "localhost:" + hostPort;
        return host + "/" + registryName;
    }

    /** Polls the shared registry's V2 base endpoint to detect readiness. */
    public boolean isReady() {
        if (!started || internalEndpoint == null) {
            return false;
        }
        try {
            HttpURLConnection conn =
                    (HttpURLConnection) URI.create("http://" + internalEndpoint + "/v2/").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            return code == 200 || code == 401;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isStarted() {
        return started;
    }

    /** Stops and removes the shared registry container (emulator shutdown). */
    public synchronized void shutdown() {
        if (started && containerId != null) {
            lifecycleManager.stopAndRemove(containerId, null);
            started = false;
            containerId = null;
        }
    }
}
