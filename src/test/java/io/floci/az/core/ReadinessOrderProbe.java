package io.floci.az.core;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Stands in for a service: it observes startup at the default priority, as the sidecar managers
 * do, and records what {@link EmulatorReadiness} reported while it ran.
 */
@ApplicationScoped
public class ReadinessOrderProbe {

    private final EmulatorReadiness readiness;
    private final AtomicReference<Boolean> readyDuringStartup = new AtomicReference<>();

    @Inject
    public ReadinessOrderProbe(EmulatorReadiness readiness) {
        this.readiness = readiness;
    }

    void onStart(@Observes StartupEvent ev) {
        readyDuringStartup.set(readiness.isReady());
    }

    Boolean readyDuringStartup() {
        return readyDuringStartup.get();
    }
}
