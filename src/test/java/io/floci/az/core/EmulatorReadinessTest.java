package io.floci.az.core;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmulatorReadinessTest {

    @Test
    void readinessStartsDownTurnsUpOnStartupAndDownAgainOnShutdown() {
        EmulatorReadiness readiness = new EmulatorReadiness();
        assertFalse(readiness.isReady());

        readiness.onStart(null);
        assertTrue(readiness.isReady());

        readiness.onShutdown(null);
        assertFalse(readiness.isReady());
    }

    @Test
    @SuppressWarnings("unchecked")
    void readyAnswers503BeforeStartupCompletesAnd200After() {
        EmulatorReadiness readiness = new EmulatorReadiness();
        Instance<ServiceHealth> noServiceHealth = mock(Instance.class);
        when(noServiceHealth.iterator()).thenAnswer(invocation -> Collections.emptyIterator());
        HealthController controller = new HealthController(null, noServiceHealth, readiness);

        try (Response before = controller.ready()) {
            assertEquals(503, before.getStatus());
        }
        try (Response health = controller.health()) {
            assertEquals(200, health.getStatus());
        }

        readiness.onStart(null);
        try (Response after = controller.ready()) {
            assertEquals(200, after.getStatus());
        }
    }
}
