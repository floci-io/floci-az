package io.floci.az.core.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerPresence;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.ContainerStorageHelper;
import io.floci.az.core.docker.DockerHostResolver;
import io.floci.az.core.docker.HostLiterals;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the floci-ui web console as a sidecar container, started on demand.
 *
 * <p>Nothing happens at boot. The first {@code GET /_floci/ui} calls {@link #ensureStartedAsync()}, which pulls
 * and starts the console on a background thread while the browser waits on a page polling {@link #status()}.
 * The console is configured entirely through its environment (Floci Console Contract v2, see
 * {@link ConsoleEnvironment}): it gets no volume, no Docker socket and no file. A start that fails is reported
 * through {@link #status()} and never affects the rest of floci-az, and a console whose container has gone is
 * started again the next time its status is read.
 */
@ApplicationScoped
public class FlociUiManager {

    private static final Logger LOG = Logger.getLogger(FlociUiManager.class);
    private static final String BASE_NAME = "ui";
    private static final int PROBE_TIMEOUT_MS = 800;
    private static final int MAX_PROBE_BODY_BYTES = 64 * 1024;

    /**
     * What {@code GET /_floci/ui/status} reports. {@code hostPort} is 0 until the console has started;
     * {@code landingPath} is where on the console the browser is sent.
     */
    public record UiStatus(boolean enabled, boolean started, boolean ready, int hostPort, String landingPath,
                           String error) {
    }

    /** One probe of the console: whether it answered at all, whether it is ready, and what it said if not. */
    record ProbeResult(boolean answered, boolean ready, String error) {
    }

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final DockerHostResolver dockerHostResolver;
    private final EmulatorConfig config;
    private final ObjectMapper mapper;
    private final Executor starter;

    private final AtomicBoolean kicked = new AtomicBoolean();
    private final AtomicBoolean publishedModeWarned = new AtomicBoolean();

    private volatile boolean started;
    private volatile String containerId;
    private volatile String probeUrl;
    private volatile ConsoleProbe probe;
    private volatile String lastError;

    @Inject
    public FlociUiManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                          ContainerDetector containerDetector, DockerHostResolver dockerHostResolver,
                          EmulatorConfig config, ObjectMapper mapper) {
        this(containerBuilder, lifecycleManager, containerDetector, dockerHostResolver, config, mapper,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "floci-az-ui-starter");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    /** For tests: {@code starter} runs the start, so a direct executor makes it synchronous. */
    FlociUiManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                   ContainerDetector containerDetector, DockerHostResolver dockerHostResolver,
                   EmulatorConfig config, ObjectMapper mapper, Executor starter) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.dockerHostResolver = dockerHostResolver;
        this.config = config;
        this.mapper = mapper;
        this.starter = starter;
    }

    /**
     * Starts the console in the background unless it is disabled, already started, or already starting. Returns at
     * once. A failed start re-arms, so the next call tries again.
     */
    public void ensureStartedAsync() {
        if (!config.ui().enabled() || started || !kicked.compareAndSet(false, true)) {
            return;
        }
        try {
            starter.execute(() -> {
                boolean ok = false;
                try {
                    ok = ensureStarted();
                } finally {
                    if (!ok) {
                        kicked.set(false);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            kicked.set(false);
            LOG.warnv("Web console start was not scheduled (floci-az is shutting down?): {0}", e.getMessage());
        }
    }

    /** Starts the console unless it already runs. Never throws: a failure is kept for {@link #status()}. */
    synchronized boolean ensureStarted() {
        if (!config.ui().enabled()) {
            return false;
        }
        if (started) {
            return true;
        }
        lastError = null;
        String name = containerName();
        String image = config.ui().image();
        try {
            lifecycleManager.ensureImageAvailable(image);
            lifecycleManager.removeIfExists(name);

            ConsoleProbe consoleProbe = ConsoleProbe.forImage(image);
            String endpoint = resolveEmulatorEndpoint();
            Map<String, String> env = ConsoleEnvironment.build(config, endpoint, consoleProbe, defaultLocation());

            ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                    .withName(name)
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels(BASE_NAME, null, null, null, null));
            for (Map.Entry<String, String> variable : env.entrySet()) {
                builder.withEnv(variable.getKey(), variable.getValue());
            }
            builder.withPortBinding(config.ui().internalPort(), config.ui().port())
                    .withDockerNetwork(config.services().dockerNetwork())
                    .withLogRotation();
            if (!containerDetector.isRunningInContainer()) {
                builder.withHostDockerInternalOnLinux();
            }
            ContainerSpec spec = builder.build();

            ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
            containerId = info.containerId();
            probe = consoleProbe;
            probeUrl = "http://" + probeAddress(spec, info) + consoleProbe.healthPath();
            started = true;
            LOG.infov("Started web console {0} ({1}) on host port {2}; it reaches floci-az at {3}",
                    name, image, String.valueOf(config.ui().port()), endpoint);
            return true;
        } catch (RuntimeException e) {
            lastError = describeStartFailure(e);
            LOG.errorv(e, "Could not start web console {0}: {1}", name, lastError);
            return false;
        }
    }

    /**
     * The console's current state, probing it when it has started. A console whose container has gone or stopped
     * is started again in the background; one Docker cannot inspect is left alone.
     */
    public UiStatus status() {
        if (!config.ui().enabled()) {
            return new UiStatus(false, false, false, 0, null,
                    "The web console is disabled (FLOCI_AZ_UI_ENABLED=false).");
        }
        String id = containerId;
        if (!started || id == null) {
            return new UiStatus(true, false, false, 0, null, lastError);
        }
        int hostPort = config.ui().port();
        ConsoleProbe consoleProbe = probe;
        String landingPath = consoleProbe != null ? consoleProbe.landingPath() : "/";
        ProbeResult result = probeConsole();
        if (result.ready()) {
            return new UiStatus(true, true, true, hostPort, landingPath, null);
        }
        if (!result.answered()) {
            ContainerPresence presence = lifecycleManager.presence(id);
            if (presence == ContainerPresence.ABSENT || presence == ContainerPresence.STOPPED) {
                rearm(id, presence);
                return new UiStatus(true, false, false, 0, null, null);
            }
        }
        return new UiStatus(true, true, false, hostPort, landingPath, result.error());
    }

    /**
     * Stops and removes the console unless it is configured to outlive floci-az. The starter is interrupted
     * first, so shutdown does not queue behind a start that is still pulling the image.
     */
    @PreDestroy
    void shutdown() {
        if (starter instanceof ExecutorService service) {
            service.shutdownNow();
        }
        synchronized (this) {
            try {
                if (containerId != null && !config.ui().keepRunningOnShutdown()) {
                    lifecycleManager.stopAndRemove(containerId, null);
                }
            } catch (RuntimeException e) {
                LOG.warnv("Could not stop web console {0}: {1}", containerName(), e.getMessage());
            } finally {
                started = false;
                containerId = null;
            }
        }
    }

    /** {@code floci-az-ui}, with the configured resource namespace. */
    String containerName() {
        return ContainerStorageHelper.dockerName(config, BASE_NAME);
    }

    /**
     * The URL the console uses to reach floci-az: the configured override; floci-az's hostname-based base URL
     * when it runs in a container with {@code hostname} set (its Compose alias, on the network the console
     * joins); otherwise floci-az's address as Docker sees it. Always plain {@code http} for a derived address:
     * floci-az detects TLS on the same port, and its certificate carries no SAN for a container IP.
     */
    String resolveEmulatorEndpoint() {
        Optional<String> override = config.ui().endpoint().map(String::trim).filter(value -> !value.isEmpty());
        if (override.isPresent()) {
            return override.get();
        }
        if (lifecycleManager.publishedEndpoints() && publishedModeWarned.compareAndSet(false, true)) {
            LOG.warnv("docker.endpoint-mode is published: a console on a remote Docker daemon may not reach "
                    + "floci-az at a derived address; set FLOCI_AZ_UI_ENDPOINT to the URL it should use");
        }
        if (containerDetector.isRunningInContainer() && config.hostname().isPresent()) {
            return config.effectiveBaseUrl();
        }
        return "http://" + HostLiterals.forUrl(dockerHostResolver.resolve()) + ":" + config.port();
    }

    /** Where floci-az itself reaches the console: as {@code AcrRegistryManager} reaches its registry. */
    private String probeAddress(ContainerSpec spec, ContainerLifecycleManager.ContainerInfo info) {
        int internalPort = config.ui().internalPort();
        if (containerDetector.isRunningInContainer() && !lifecycleManager.publishedEndpoints()) {
            return ContainerLifecycleManager.containerNetworkEndpoint(spec, info, internalPort).toString();
        }
        String host = lifecycleManager.publishedEndpoints() ? lifecycleManager.daemonHost() : "localhost";
        return HostLiterals.forUrl(host) + ":" + config.ui().port();
    }

    private synchronized void rearm(String id, ContainerPresence presence) {
        if (!id.equals(containerId)) {
            return;
        }
        LOG.warnv("Web console {0} is {1}; starting it again", containerName(),
                presence.name().toLowerCase(Locale.ROOT));
        started = false;
        containerId = null;
        kicked.set(false);
        ensureStartedAsync();
    }

    /** One GET of the console's health path, judged by the console's probe fields. */
    ProbeResult probeConsole() {
        String url = probeUrl;
        ConsoleProbe consoleProbe = probe;
        if (url == null || consoleProbe == null) {
            return new ProbeResult(false, false, null);
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(PROBE_TIMEOUT_MS);
            conn.setReadTimeout(PROBE_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                return new ProbeResult(true, false, null);
            }
            byte[] body;
            try (InputStream in = conn.getInputStream()) {
                body = in.readNBytes(MAX_PROBE_BODY_BYTES);
            }
            return judge(consoleProbe, mapper.readTree(body));
        } catch (IOException | IllegalArgumentException e) {
            LOG.debugv("Web console probe {0} failed: {1}", url, e.getMessage());
            return new ProbeResult(false, false, null);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** The verdict of a 200 response body: ready, unavailable (with the console's reason), or still starting. */
    static ProbeResult judge(ConsoleProbe consoleProbe, JsonNode body) {
        String verdict = body == null ? "" : body.path(consoleProbe.readyField()).asText("");
        if (consoleProbe.readyValue().equals(verdict)) {
            return new ProbeResult(true, true, null);
        }
        if (consoleProbe.unavailableValue().equals(verdict)) {
            JsonNode error = body.path("error");
            String reason = error.isTextual() && !error.asText().isBlank()
                    ? error.asText()
                    : "The console is running but cannot reach floci-az.";
            return new ProbeResult(true, false, reason);
        }
        return new ProbeResult(true, false, null);
    }

    private String defaultLocation() {
        return CloudCatalog.forNameOrPublic(config.cloud().defaultCloud()).effectiveDefaultLocation();
    }

    /** A start failure in terms the operator can act on. */
    String describeStartFailure(RuntimeException e) {
        String messages = causeMessages(e);
        String lower = messages.toLowerCase(Locale.ROOT);
        if (lower.contains("port is already allocated") || lower.contains("address already in use")) {
            return "Host port " + config.ui().port() + " is already in use, possibly by floci-aws's web console, "
                    + "which uses the same port. Set FLOCI_AZ_UI_PORT to another port.";
        }
        return "Could not start the web console (" + config.ui().image() + "): " + messages;
    }

    private static String causeMessages(Throwable e) {
        StringBuilder messages = new StringBuilder();
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 5) {
            String message = current.getMessage();
            if (message != null && !message.isBlank() && messages.indexOf(message) < 0) {
                if (messages.length() > 0) {
                    messages.append(": ");
                }
                messages.append(message);
            }
            current = current.getCause();
            depth++;
        }
        return messages.length() == 0 ? e.getClass().getSimpleName() : messages.toString();
    }
}
