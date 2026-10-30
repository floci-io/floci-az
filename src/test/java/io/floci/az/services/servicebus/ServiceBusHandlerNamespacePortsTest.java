package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.servicebus.ServiceBusNamespaceManager.NamespaceState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * When floci-az runs in a container, it reaches the Artemis sidecar at the sidecar's container
 * ports, while a client on the host reaches it at the ports Docker published. The namespace API
 * reports both, so a client on either side can find the broker, including when the configured
 * host port is 0 and Docker picked it.
 */
class ServiceBusHandlerNamespacePortsTest {

    private static final NamespaceState IN_CONTAINER =
            new NamespaceState("c1", 5672, 5671, 49175, 49176, "", "172.17.0.6", 8161, false);

    private final ServiceBusNamespaceManager namespaceManager = mock(ServiceBusNamespaceManager.class);

    @Test
    void getNamespaceReportsThePublishedHostPortsBesideTheContainerPorts() {
        when(namespaceManager.getNamespace("default")).thenReturn(Optional.of(IN_CONTAINER));

        String body = handle("namespaces/default");

        assertThat(body, containsString("\"amqpPort\":5672,\"amqpsPort\":5671"));
        assertThat(body, containsString("\"amqpHostPort\":49175,\"amqpsHostPort\":49176"));
    }

    @Test
    void listNamespacesReportsThePublishedHostPorts() {
        when(namespaceManager.listNamespaces()).thenReturn(Map.of("default", IN_CONTAINER));

        assertThat(handle("namespaces"),
                containsString("\"amqpHostPort\":49175,\"amqpsHostPort\":49176"));
    }

    private String handle(String path) {
        ServiceBusHandler handler = new ServiceBusHandler(
                mock(EmulatorConfig.class), namespaceManager, mock(StorageFactory.class));
        return handler.handle(new AzureRequest("GET", "devstoreaccount1", "servicebus", path,
                null, null, Map.of(), null, false)).getEntity().toString();
    }
}
