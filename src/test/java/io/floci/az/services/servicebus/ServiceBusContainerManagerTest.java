package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceBusContainerManagerTest {

    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
    private final EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
    private final ServiceBusNamespaceManager namespaceManager = mock(ServiceBusNamespaceManager.class);
    private final ServiceBusTopologyLoader topologyLoader = mock(ServiceBusTopologyLoader.class);

    @BeforeEach
    void setUp() {
        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(serviceBus.enabled()).thenReturn(true);
    }

    @Test
    void reapsOrphanedContainersWhenBrokerModeStarts() {
        when(serviceBus.mocked()).thenReturn(false);

        new ServiceBusContainerManager(config, namespaceManager, topologyLoader).onStart(null);

        verify(namespaceManager).reapOrphanedContainers();
    }

    @Test
    void skipsCleanupInMockedMode() {
        when(serviceBus.mocked()).thenReturn(true);

        new ServiceBusContainerManager(config, namespaceManager, topologyLoader).onStart(null);

        verify(namespaceManager, never()).reapOrphanedContainers();
    }

    @Test
    void cleanupFailureDoesNotFailApplicationStartup() {
        when(serviceBus.mocked()).thenReturn(false);
        when(namespaceManager.reapOrphanedContainers())
                .thenThrow(new IllegalStateException("Docker unavailable"));

        assertDoesNotThrow(
                () -> new ServiceBusContainerManager(config, namespaceManager, topologyLoader).onStart(null));
    }

    @Test
    void loadsTopologyInBrokerMode() {
        when(serviceBus.mocked()).thenReturn(false);

        new ServiceBusContainerManager(config, namespaceManager, topologyLoader).onStart(null);

        verify(topologyLoader).load();
    }

    @Test
    void loadsTopologyInMockedMode() {
        when(serviceBus.mocked()).thenReturn(true);

        new ServiceBusContainerManager(config, namespaceManager, topologyLoader).onStart(null);

        verify(topologyLoader).load();
    }

    @Test
    void skipsTopologyWhenServiceDisabled() {
        when(serviceBus.enabled()).thenReturn(false);

        new ServiceBusContainerManager(config, namespaceManager, topologyLoader).onStart(null);

        verify(topologyLoader, never()).load();
    }

    @Test
    void topologyLoadFailureDoesNotFailApplicationStartup() {
        when(serviceBus.mocked()).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(topologyLoader).load();

        assertDoesNotThrow(
                () -> new ServiceBusContainerManager(config, namespaceManager, topologyLoader).onStart(null));
    }

    @Test
    void namespaceStartStaysLazyByDefault() {
        when(serviceBus.mocked()).thenReturn(false);

        new ServiceBusContainerManager(config, namespaceManager).onStart(null);

        verify(namespaceManager, never()).startNamespace(anyString(), anyInt(), anyInt());
        verify(namespaceManager, never()).startMockedNamespace(anyString());
    }

    @Test
    void startsDefaultNamespaceOnBootWhenConfigured() {
        when(serviceBus.mocked()).thenReturn(false);
        when(serviceBus.startOnBoot()).thenReturn(true);
        when(serviceBus.amqpPort()).thenReturn(5673);
        when(serviceBus.amqpTlsPort()).thenReturn(5674);

        new ServiceBusContainerManager(config, namespaceManager).onStart(null);

        verify(namespaceManager).startNamespace(
                ServiceBusNamespaceManager.DEFAULT_NAMESPACE, 5673, 5674);
    }

    @Test
    void registersMockedNamespaceOnBootWhenConfigured() {
        when(serviceBus.mocked()).thenReturn(true);
        when(serviceBus.startOnBoot()).thenReturn(true);

        new ServiceBusContainerManager(config, namespaceManager).onStart(null);

        verify(namespaceManager).startMockedNamespace(ServiceBusNamespaceManager.DEFAULT_NAMESPACE);
        verify(namespaceManager, never()).startNamespace(anyString(), anyInt(), anyInt());
    }

    @Test
    void bootStartFailureDoesNotFailApplicationStartup() {
        when(serviceBus.mocked()).thenReturn(false);
        when(serviceBus.startOnBoot()).thenReturn(true);
        when(serviceBus.amqpPort()).thenReturn(5673);
        when(serviceBus.amqpTlsPort()).thenReturn(5674);
        when(namespaceManager.startNamespace(
                ServiceBusNamespaceManager.DEFAULT_NAMESPACE, 5673, 5674))
                .thenThrow(new IllegalStateException("Docker unavailable"));

        assertDoesNotThrow(
                () -> new ServiceBusContainerManager(config, namespaceManager).onStart(null));
    }
}
