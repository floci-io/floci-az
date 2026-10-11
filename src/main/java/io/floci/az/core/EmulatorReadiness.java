package io.floci.az.core;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether the emulator has finished starting and is still accepting work.
 *
 * <p>Services do their startup work (sidecar reaping, Service Bus topology and start-on-boot,
 * eager Cosmos engines, the banner) in {@link StartupEvent} observers. This bean observes the
 * same event at a priority later than all of them, so it turns ready only once that work has
 * returned, and turns not ready again as soon as shutdown begins, before any service tears its
 * sidecars down. It is the single readiness flag: anything that reports readiness reads it.
 */
@ApplicationScoped
public class EmulatorReadiness {

    private static final Logger LOG = Logger.getLogger(EmulatorReadiness.class);

    private final AtomicBoolean ready = new AtomicBoolean(false);

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_AFTER + 1000) StartupEvent ev) {
        markReady();
        LOG.info("floci-az is ready");
    }

    void onShutdown(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) ShutdownEvent ev) {
        markNotReady();
    }

    public boolean isReady() {
        return ready.get();
    }

    void markReady() {
        ready.set(true);
    }

    void markNotReady() {
        ready.set(false);
    }
}
