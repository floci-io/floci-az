package io.floci.az.services.functions;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.services.functions.FunctionModels.FunctionDefinition;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * LIFO warm-container pool for Azure Functions.
 *
 * Two modes controlled by {@code floci-az.services.functions.ephemeral}:
 *  - {@code false} (default): containers are reused across invocations and evicted
 *    after {@code container-idle-timeout-seconds} of inactivity.
 *  - {@code true}: each invocation gets a fresh container that is stopped immediately
 *    after the invocation completes.
 */
@ApplicationScoped
public class WarmPool {

    private static final Logger LOG = Logger.getLogger(WarmPool.class);
    private static final int DEFAULT_MAX_POOL_SIZE = Math.max(4, Runtime.getRuntime().availableProcessors());

    private final ContainerLauncher launcher;
    private final EmulatorConfig config;
    private final int maxPoolSizePerFunction;
    private final ConcurrentHashMap<String, ArrayDeque<ContainerHandle>> pool = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fn-pool-evictor");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public WarmPool(ContainerLauncher launcher, EmulatorConfig config) {
        this.launcher = launcher;
        this.config = config;
        this.maxPoolSizePerFunction = DEFAULT_MAX_POOL_SIZE;
    }

    /** Package-private constructor for testing (empty pool, no containers to drain). */
    WarmPool() {
        this.launcher = null;
        this.config = null;
        this.maxPoolSizePerFunction = DEFAULT_MAX_POOL_SIZE;
    }

    @PostConstruct
    void init() {
        if (config == null) {
            return;
        }

        int idleTimeout = config.services().functions().containerIdleTimeoutSeconds();
        if (!config.services().functions().ephemeral() && idleTimeout > 0) {
            long checkInterval = Math.min(30, idleTimeout / 2 + 1);
            evictionScheduler.scheduleAtFixedRate(this::evictIdle, checkInterval, checkInterval, TimeUnit.SECONDS);
            LOG.infov("Warm pool eviction enabled: idleTimeout={0}s, checkInterval={1}s", idleTimeout, checkInterval);
        }
    }

    @PreDestroy
    void shutdown() {
        evictionScheduler.shutdownNow();
        drainAll();
    }

    /**
     * Returns a warm container for the function, cold-starting one if necessary.
     */
    public ContainerHandle acquire(FunctionDefinition def) {
        boolean ephemeral = config.services().functions().ephemeral();
        ContainerHandle handle = null;

        if (!ephemeral) {
            ArrayDeque<ContainerHandle> q = pool.computeIfAbsent(def.functionKey(), k -> new ArrayDeque<>());
            synchronized (q) {
                handle = q.pollFirst();
            }
        }

        if (handle == null) {
            LOG.debugv(ephemeral ? "Ephemeral start: {0}" : "Cold start: {0}", def.functionKey());
            handle = launcher.launch(def);
        } else {
            LOG.debugv("Warm container reused: {0}", def.functionKey());
        }

        handle.setState(ContainerHandle.State.BUSY);
        return handle;
    }

    /**
     * Returns the container to the pool (or destroys it in ephemeral mode).
     */
    public void release(ContainerHandle handle) {
        if (config.services().functions().ephemeral()) {
            stopQuietly(handle);
            return;
        }

        handle.setState(ContainerHandle.State.WARM);
        handle.touchLastUsed();

        ArrayDeque<ContainerHandle> q = pool.computeIfAbsent(handle.functionKey(), k -> new ArrayDeque<>());
        boolean returned;
        synchronized (q) {
            returned = q.size() < maxPoolSizePerFunction;
            if (returned) q.addFirst(handle);
        }
        if (returned) {
            LOG.debugv("Container returned to pool: {0}", handle.functionKey());
        } else {
            LOG.debugv("Pool full for {0}, discarding container", handle.functionKey());
            stopQuietly(handle);
        }
    }

    /** Drains and stops all warm containers for the given function key. */
    public void drain(String functionKey) {
        ArrayDeque<ContainerHandle> q = pool.remove(functionKey);
        if (q == null) return;
        List<ContainerHandle> toStop;
        synchronized (q) {
            toStop = new ArrayList<>(q);
            q.clear();
        }
        LOG.infov("Draining {0} container(s) for: {1}", toStop.size(), functionKey);
        toStop.forEach(this::stopQuietly);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void drainAll() {
        new ArrayList<>(pool.keySet()).forEach(this::drain);
    }

    private void evictIdle() {
        long idleMs = config.services().functions().containerIdleTimeoutSeconds() * 1000L;
        long now = System.currentTimeMillis();

        for (var entry : pool.entrySet()) {
            String key = entry.getKey();
            ArrayDeque<ContainerHandle> q = entry.getValue();
            List<ContainerHandle> toEvict = new ArrayList<>();

            synchronized (q) {
                q.removeIf(h -> {
                    if (h.state() == ContainerHandle.State.WARM
                            && (now - h.lastUsedMs()) >= idleMs) {
                        toEvict.add(h);
                        return true;
                    }
                    return false;
                });
            }

            if (!toEvict.isEmpty()) {
                LOG.infov("Evicting {0} idle container(s) for: {1}", toEvict.size(), key);
                toEvict.forEach(this::stopQuietly);
            }
        }
    }

    private void stopQuietly(ContainerHandle handle) {
        try {
            launcher.stop(handle);
        } catch (Exception e) {
            LOG.warnv("Error stopping container {0}: {1}", handle.containerId(), e.getMessage());
        }
    }
}
