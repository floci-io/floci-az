package io.floci.az.services.acr;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerStorageHelper;
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
 * and reused across all registries, mirroring the AWS ECR design in the sibling emulator.
 *
 * <p>Registries are isolated within the shared registry by an internal repository prefix
 * ({@code {registryName}/{repo}}), which {@link AcrRegistryProxy} applies when it forwards requests
 * from {@code {name}.azurecr.io}. The container's own port stays published, so
 * {@code localhost:{port}/{registryName}/{repo}} keeps addressing the same storage directly. The
 * backing registry runs <b>anonymous</b>: admin credentials are returned by the management plane
 * but not enforced at the data plane.</p>
 */
@ApplicationScoped
public class AcrRegistryManager {

    private static final Logger LOG = Logger.getLogger(AcrRegistryManager.class);
    private static final int REGISTRY_PORT = 5000;
    private String sharedName() {
        return ContainerStorageHelper.dockerName(config, "acr-registry");
    }

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;

    private volatile boolean started;
    private volatile String containerId;
    private volatile String internalEndpoint;
    private volatile int publishedPort;

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

    /**
     * Lazily starts the shared {@code registry:2} container. Idempotent and thread-safe.
     * Self-healing: if the container died or was removed externally after a successful
     * start, it is started again instead of handing out a dead endpoint forever.
     */
    public synchronized void ensureStarted() {
        if (started) {
            if (lifecycleManager.isContainerRunning(containerId)) {
                return;
            }
            LOG.warnv("Shared ACR registry {0} is no longer running; restarting it", sharedName());
            started = false;
            containerId = null;
        }
        EmulatorConfig.AcrConfig acrConfig = config.services().acr();
        lifecycleManager.removeIfExists(sharedName());

        int chosenPort = portAllocator.allocate(acrConfig.basePort(), acrConfig.maxPort());
        try {
            ContainerSpec spec = containerBuilder.newContainer(acrConfig.defaultImage())
                    .withName(sharedName())
                    .withEnv("REGISTRY_STORAGE_DELETE_ENABLED", "true")
                    .withEnv("REGISTRY_HTTP_ADDR", "0.0.0.0:" + REGISTRY_PORT)
                    .withPortBinding(REGISTRY_PORT, chosenPort)
                    .withDockerNetwork(config.services().dockerNetwork())
                    .withLogRotation()
                    .build();

            ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
            this.containerId = info.containerId();
            this.publishedPort = chosenPort;

            ContainerLifecycleManager.EndpointInfo ep = info.getEndpoint(REGISTRY_PORT);
            if (containerDetector.isRunningInContainer()) {
                this.internalEndpoint = sharedName() + ":" + REGISTRY_PORT;
            } else {
                this.internalEndpoint = ep != null ? ep.host() + ":" + ep.port() : "localhost:" + chosenPort;
            }
            this.started = true;
            LOG.infov("Started shared ACR registry {0} on host port {1}", sharedName(), String.valueOf(chosenPort));
        } catch (Exception e) {
            throw new RuntimeException("Failed to start shared ACR registry container: " + e.getMessage(), e);
        }
    }

    /**
     * The shared registry's {@code host:port} as reachable from floci-az itself, or {@code null}
     * before it has started. This is what the {@code /v2/} proxy forwards to.
     */
    public String dataPlaneEndpoint() {
        return started ? internalEndpoint : null;
    }

    /**
     * The host port the shared container publishes {@code registry:5000} on, or {@code 0} before it
     * has started. Reported to clients as the registry resource's {@code localPort}, the way
     * PostgreSQL and MySQL report theirs: {@code loginServer} names the Azure host, so it is the only
     * thing that says which port serves the same storage anonymously.
     */
    public int publishedPort() {
        return started ? publishedPort : 0;
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

    /** Stops and removes the shared registry container (emulator shutdown). */
    public synchronized void shutdown() {
        if (started && containerId != null) {
            lifecycleManager.stopAndRemove(containerId, null);
            started = false;
            containerId = null;
        }
    }
}
