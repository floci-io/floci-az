package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ServiceBusTopologyLoaderUnitTest {

    @TempDir
    Path tempDir;

    @Test
    void failedNamespaceIsSkippedWithoutReusingConfiguredPorts() throws Exception {
        Path topologyFile = tempDir.resolve("Config.json");
        Files.writeString(topologyFile, """
                {
                  "UserConfig": {
                    "Namespaces": [
                      { "Name": "failed", "Queues": [{ "Name": "orphan" }] },
                      { "Name": "working" }
                    ]
                  }
                }
                """);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        ServiceBusHandler handler = mock(ServiceBusHandler.class);
        ServiceBusNamespaceManager namespaceManager = mock(ServiceBusNamespaceManager.class);
        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(serviceBus.topologyFile()).thenReturn(Optional.of(topologyFile.toString()));
        when(serviceBus.mocked()).thenReturn(false);
        when(serviceBus.amqpPort()).thenReturn(5672);
        when(serviceBus.amqpTlsPort()).thenReturn(5671);
        when(namespaceManager.getNamespace(anyString())).thenReturn(Optional.empty());
        when(namespaceManager.startNamespace("failed", 5672, 5671))
                .thenThrow(new IllegalStateException("Docker unavailable"));

        new ServiceBusTopologyLoader(config, handler, namespaceManager).load();

        verify(namespaceManager).startNamespace("failed", 5672, 5671);
        verify(namespaceManager).startNamespace("working", 0, 0);
        verify(namespaceManager, never()).startNamespace("working", 5672, 5671);
        verifyNoInteractions(handler);
    }
}
