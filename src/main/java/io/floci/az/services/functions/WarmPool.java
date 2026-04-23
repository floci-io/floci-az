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
 * Default mode: containers are reused across invocations and evicted after
 * {@code idleTimeoutMs} of inactivity.
 *
 * Ephemeral mode (config): fresh container per invocation, destroyed immediately.
 */
@ApplicationScoped
public class WarmPool {

    private static final Logger LOG = Logger.getLogger(WarmPool.class);
    private static final int MAX_POOL_SIZE = Math.max(4, Runtime.getRuntime().availableProcessors());

    private final ContainerLauncher launcher;
    private final EmulatorConfig config;
    private final ConcurrentHashMap<String, ArrayDeque<ContainerHandle>> pool = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fn-pool-evictor");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public WarmPool(ContainerLauncher launcher, EmulatorConfig config) {
        this.launcher = launcher;
        this.config   = config;
    }

    @PostConstruct
    void init() {
        if (!config.services().functions().ephemeral()) {
            long idleMs       = config.services().functions().idleTimeoutMs();
            long intervalSecs = Math.min(30, idleMs / 2000 + 1);
            evictor.scheduleAtFixedRate(this::evictIdle, intervalSecs, intervalSecs, TimeUnit.SECONDS);
            LOG.infov("Warm pool eviction enabled: idleTimeout={0}ms, checkInterval={1}s", idleMs, intervalSecs);
        }
    }

    @PreDestroy
    void shutdown() {
        evictor.shutdownNow();
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
            returned = q.size() < MAX_POOL_SIZE;
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
        long idleMs = config.services().functions().idleTimeoutMs();
        long now    = System.currentTimeMillis();

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
