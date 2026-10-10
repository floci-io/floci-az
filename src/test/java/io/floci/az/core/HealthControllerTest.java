package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void serviceProblemReportsDown() {
        HealthController controller = controllerWith(
                () -> Map.of("servicebus/default", "Service Bus namespace 'default' failed to start"));

        for (Response response : List.of(controller.health(), controller.ready())) {
            assertEquals(503, response.getStatus());
            Map<?, ?> body = (Map<?, ?>) response.getEntity();
            assertEquals("DOWN", body.get("status"));
            assertEquals(Map.of("servicebus/default", "Service Bus namespace 'default' failed to start"),
                    body.get("problems"));
        }
    }

    @Test
    void noServiceProblemsReportsUp() {
        HealthController controller = controllerWith(Map::of);

        for (Response response : List.of(controller.health(), controller.ready())) {
            assertEquals(200, response.getStatus());
            assertEquals("UP", ((Map<?, ?>) response.getEntity()).get("status"));
        }
    }

    @SuppressWarnings("unchecked")
    private static HealthController controllerWith(ServiceHealth health) {
        Instance<ServiceHealth> instance = mock(Instance.class);
        when(instance.iterator()).thenAnswer(invocation -> List.of(health).iterator());
        return new HealthController(mock(EmulatorConfig.class), instance);
    }
}
