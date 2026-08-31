package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.services.eventhub.ArtemisTlsGenerator;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceBusNamespaceManagerStartupTest {

    @Test
    void failedStartRemovesPartiallyCreatedContainer() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        ServiceBusConfigGenerator configGenerator = mock(ServiceBusConfigGenerator.class);
        ArtemisTlsGenerator tlsGenerator = mock(ArtemisTlsGenerator.class);
        ContainerSpec spec = new ContainerSpec("artemis");

        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(serviceBus.artemisImage()).thenReturn("artemis");
        when(configGenerator.generate("failed")).thenReturn("<configuration/>");
        when(tlsGenerator.generate("floci-az-servicebus-failed"))
                .thenReturn(new ArtemisTlsGenerator.TlsBundle(new byte[0], "certificate"));
        when(containerBuilder.newContainer("artemis")).thenReturn(builder);
        when(builder.withName(anyString())).thenReturn(builder);
        when(builder.withEnv(anyString(), anyString())).thenReturn(builder);
        when(builder.withLabels(anyMap())).thenReturn(builder);
        when(builder.withPortBinding(anyInt(), anyInt())).thenReturn(builder);
        when(builder.withDynamicPort(anyInt())).thenReturn(builder);
        when(builder.withDockerNetwork(any())).thenReturn(builder);
        when(builder.withLogRotation()).thenReturn(builder);
        when(builder.build()).thenReturn(spec);
        when(lifecycleManager.create(spec)).thenReturn("container-id");
        when(lifecycleManager.startCreated("container-id", spec))
                .thenThrow(new IllegalStateException("readiness failed"));
        when(lifecycleManager.removeIfExistsAndConfirm("container-id")).thenReturn(true);

        ServiceBusNamespaceManager manager = new ServiceBusNamespaceManager(
                config, containerBuilder, lifecycleManager, configGenerator, tlsGenerator);

        ServiceBusNamespaceManager.NamespaceStartException error = assertThrows(
                ServiceBusNamespaceManager.NamespaceStartException.class,
                () -> manager.startNamespace("failed", 5672, 5671));

        verify(lifecycleManager).stopAndRemove("container-id", null);
        verify(lifecycleManager).removeIfExistsAndConfirm("container-id");
        assertTrue(error.portsReleased());
        assertTrue(manager.listNamespaces().isEmpty());
    }
}
