package io.floci.az.services.search;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.ContainerStorageHelper;
import io.floci.az.core.docker.HostLiterals;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Solr sidecar behind Azure AI Search queries: one shared container, {@code floci-az-search-default}, with no
 * volume. Its collections are derived from floci-az's storage (see {@link SearchIndexSync}), so a new container
 * starts empty and is filled again on demand.
 *
 * <p>Nothing is pulled or started until a search service exists: the ARM create of a service, a data-plane request
 * to an undeclared one, or a restart that finds services in storage calls {@link #prepare()}, which starts the
 * container in the background. A query waits for it with {@link #await()}, at most
 * {@code floci-az.services.search.engine.start-timeout-seconds}. When the last service is deleted, {@link #stop()}
 * removes the container after a grace of 30 seconds: a service created meanwhile keeps it, so a suite that creates
 * and deletes a service per test starts Solr once. Start and removal run in order on one background thread.
 *
 * <p>A failed start is logged and not fatal: queries answer 503, and the next request tries again after a pause.
 */
@ApplicationScoped
public class SearchSolrManager {

    private static final Logger LOG = Logger.getLogger(SearchSolrManager.class);

    static final int SOLR_PORT = 8983;
    private static final String CONTAINER = "search-default";
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofSeconds(30);
    static final Duration REMOVAL_GRACE = Duration.ofSeconds(30);
    private static final long POLL_MILLIS = 250;

    /**
     * A running Solr container.
     *
     * @param containerId the Docker container
     * @param baseUrl     where floci-az reaches it
     * @param generation  counts the starts, so what was built in an earlier container is known to be gone
     */
    record Engine(String containerId, URI baseUrl, long generation) {
    }

    private final EmulatorConfig config;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager containers;
    private final SearchSolrClient client;
    private final Duration removalGrace;
    private final ScheduledExecutorService lifecycle = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "floci-az-search-solr");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generations = new AtomicLong();

    /** The start under way or done; null when no container is wanted. Guarded by this. */
    private CompletableFuture<Engine> current;
    /** When the last start failed, so a request does not retry at once. Guarded by this. */
    private Instant failedAt;
    /** Stands for the removal {@link #stop()} scheduled, until it runs or a request cancels it. Guarded by this. */
    private Object pendingRemoval;
    /** Whether a start was ever asked for, so shutdown removes a container even after {@link #lost}. Guarded by this. */
    private boolean everStarted;

    @Inject
    public SearchSolrManager(EmulatorConfig config, ContainerBuilder containerBuilder,
                             ContainerLifecycleManager containers, SearchSolrClient client) {
        this(config, containerBuilder, containers, client, REMOVAL_GRACE);
    }

    SearchSolrManager(EmulatorConfig config, ContainerBuilder containerBuilder, ContainerLifecycleManager containers,
                      SearchSolrClient client, Duration removalGrace) {
        this.config = config;
        this.containerBuilder = containerBuilder;
        this.containers = containers;
        this.client = client;
        this.removalGrace = removalGrace;
    }

    /** Whether Solr may be pulled and started at all. */
    public boolean enabled() {
        return config.services().search().enabled() && config.services().search().engine().enabled();
    }

    /**
     * Starts the container in the background, unless it runs, is starting, or failed a moment ago. Does nothing
     * when the engine is disabled.
     */
    public synchronized void prepare() {
        if (!enabled()) {
            return;
        }
        // Wanted again before the removal ran: keep the container.
        pendingRemoval = null;
        if (current != null && !current.isCompletedExceptionally()) {
            return;
        }
        if (failedAt != null && Instant.now().isBefore(failedAt.plus(RETRY_AFTER_FAILURE))) {
            return;
        }
        CompletableFuture<Engine> start = new CompletableFuture<>();
        current = start;
        everStarted = true;
        failedAt = null;
        lifecycle.execute(() -> {
            try {
                start.complete(start());
            } catch (RuntimeException e) {
                LOG.errorv(e, "Search: the Solr sidecar did not start; queries answer 503 until it does");
                failed(start);
                start.completeExceptionally(e);
            }
        });
    }

    /**
     * The running engine, starting it if need be and waiting for a start under way. Empty when the engine is
     * disabled, its start failed, or it is not up within the start timeout.
     */
    public Optional<Engine> await() {
        CompletableFuture<Engine> start;
        synchronized (this) {
            prepare();
            start = current;
        }
        if (start == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(start.get(config.services().search().engine().startTimeoutSeconds(),
                    TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            LOG.warnv("Search: the Solr sidecar is still starting after {0} s",
                    config.services().search().engine().startTimeoutSeconds());
            return Optional.empty();
        } catch (ExecutionException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** The engine when it is up, without starting it or waiting for a start. */
    public synchronized Optional<Engine> running() {
        return current != null && current.isDone() && !current.isCompletedExceptionally()
                ? Optional.of(current.join()) : Optional.empty();
    }

    /** Solr stopped answering: forget the container, so the next query starts a new one. */
    public synchronized void lost(Engine engine) {
        if (current != null && current.isDone() && !current.isCompletedExceptionally()
                && current.join().generation() == engine.generation()) {
            LOG.warnv("Search: the Solr sidecar {0} stopped answering; it starts again on the next query",
                    engine.containerId());
            current = null;
        }
    }

    /**
     * Removes the container after the grace, unless a request wants it again meanwhile: no search service is left.
     */
    public synchronized void stop() {
        if (current == null || pendingRemoval != null) {
            return;
        }
        Object removal = new Object();
        pendingRemoval = removal;
        lifecycle.schedule(() -> removeUnlessWanted(removal), removalGrace.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        boolean started;
        synchronized (this) {
            started = everStarted;
            current = null;
        }
        // A removal still waiting out its grace is not needed: the container goes now.
        lifecycle.shutdownNow();
        try {
            if (!lifecycle.awaitTermination(30, TimeUnit.SECONDS)) {
                LOG.warn("Search: the Solr sidecar's start did not finish before shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (started) {
            remove();
        }
    }

    // ── On the lifecycle thread ──────────────────────────────────────────────

    private Engine start() {
        EmulatorConfig.SearchEngineConfig engine = config.services().search().engine();
        String name = containerName();
        LOG.infov("Search: starting the Solr sidecar {0} ({1})", name, engine.image());
        containers.removeIfExists(name);
        ContainerSpec spec = containerBuilder.newContainer(engine.image())
                .withName(name)
                .withLabels(ContainerStorageHelper.resourceIdentityLabels("search", null, null, null, null))
                .withEnv("SOLR_HEAP", engine.heap())
                .withDynamicPort(SOLR_PORT)
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation()
                .build();
        ContainerLifecycleManager.ContainerInfo info = containers.createAndStart(spec);
        ContainerLifecycleManager.EndpointInfo endpoint = info.getEndpoint(SOLR_PORT);
        if (endpoint == null) {
            containers.removeIfExists(name);
            throw new IllegalStateException("Docker reported no address for the Solr sidecar");
        }
        URI base = URI.create("http://" + HostLiterals.forUrl(endpoint.host()) + ":" + endpoint.port());
        awaitReady(base, name, engine.startTimeoutSeconds());
        client.uploadConfigSet(base, SearchSolrSchema.BASE_CONFIG_SET, SearchSolrSchema.baseConfigSet());
        LOG.infov("Search: the Solr sidecar is ready at {0}", base);
        return new Engine(info.containerId(), base, generations.incrementAndGet());
    }

    private void awaitReady(URI base, String name, int timeoutSeconds) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        while (!client.ready(base)) {
            if (Instant.now().isAfter(deadline)) {
                containers.removeIfExists(name);
                throw new IllegalStateException("The Solr sidecar was not ready within " + timeoutSeconds + " s");
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the Solr sidecar", e);
            }
        }
    }

    private void removeUnlessWanted(Object removal) {
        synchronized (this) {
            if (pendingRemoval != removal) {
                return;
            }
            pendingRemoval = null;
            current = null;
            failedAt = null;
        }
        remove();
    }

    private void remove() {
        try {
            containers.removeIfExists(containerName());
            LOG.infov("Search: removed the Solr sidecar {0}", containerName());
        } catch (RuntimeException e) {
            LOG.warnv(e, "Search: the Solr sidecar {0} could not be removed", containerName());
        }
    }

    private synchronized void failed(CompletableFuture<Engine> start) {
        if (current == start) {
            failedAt = Instant.now();
        }
    }

    private String containerName() {
        return ContainerStorageHelper.dockerName(config, CONTAINER);
    }
}
