package io.floci.az.core;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * The readiness flag is only as good as its observer order: it must turn on after every service
 * startup observer and off before any service shutdown observer.
 */
@QuarkusTest
class EmulatorReadinessOrderTest {

    @Inject
    ReadinessOrderProbe probe;

    @Inject
    EmulatorReadiness readiness;

    @Test
    void aServiceStartupObserverRunsBeforeTheEmulatorTurnsReady() {
        assertThat(probe.readyDuringStartup(), is(Boolean.FALSE));
        assertThat(readiness.isReady(), is(true));
    }

    @Test
    void readinessTurnsOffAheadOfDefaultPriorityShutdownObservers() throws NoSuchMethodException {
        // Firing a real ShutdownEvent would stop the shared test application, so this checks the
        // declared priority against the one service observers run at.
        Priority declared = EmulatorReadiness.class.getDeclaredMethod("onShutdown", ShutdownEvent.class)
                .getParameters()[0].getAnnotation(Priority.class);

        assertThat(declared.value(), lessThan(ObserverMethod.DEFAULT_PRIORITY));
    }
}
