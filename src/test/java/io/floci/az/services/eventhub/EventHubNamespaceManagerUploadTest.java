package io.floci.az.services.eventhub;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.services.servicebus.ArtemisPatchVersions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Event Hubs broker's files go into the image's instance volume, so they must travel in one
 * archive upload: Podman hides a file uploaded there from the second upload on (#472).
 */
class EventHubNamespaceManagerUploadTest {

    @Test
    void everyBrokerFileIsUploadedInOneArchiveBeforeStart() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.EventHubConfig eventHub = mock(EmulatorConfig.EventHubConfig.class);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        ArtemisConfigGenerator configGenerator = mock(ArtemisConfigGenerator.class);
        ArtemisTlsGenerator tlsGenerator = mock(ArtemisTlsGenerator.class);
        ContainerSpec spec = new ContainerSpec("artemis");

        when(config.services()).thenReturn(services);
        when(services.eventHub()).thenReturn(eventHub);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(eventHub.artemisImage()).thenReturn("artemis");
        when(configGenerator.generate(eq("upload"), anyMap(), any())).thenReturn("<configuration/>");
        when(tlsGenerator.generate(any(String[].class)))
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
                .thenThrow(new IllegalStateException("stop after the upload"));

        EventHubNamespaceManager manager = new EventHubNamespaceManager(
                config, containerBuilder, lifecycleManager, configGenerator, tlsGenerator);
        assertThrows(IllegalStateException.class,
                () -> manager.startNamespace("upload", Map.of(), 0, 0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, byte[]>> files = ArgumentCaptor.forClass(Map.class);
        InOrder order = inOrder(lifecycleManager);
        order.verify(lifecycleManager).copyFilesToContainer(eq("container-id"), files.capture());
        order.verify(lifecycleManager).startCreated("container-id", spec);
        verify(lifecycleManager, never()).copyBytesToContainer(anyString(), any(), anyString());
        verify(lifecycleManager, never()).copyFileToContainer(anyString(), anyString(), anyString());
        assertEquals(Set.of(
                "/var/lib/artemis-instance/etc-override/broker.xml",
                "/var/lib/artemis-instance/etc-override/artemis.p12",
                "/var/lib/artemis-instance/lib/floci-az-artemis-extension.jar",
                ArtemisPatchVersions.PROTON_J_CONTAINER_PATH,
                ArtemisPatchVersions.ARTEMIS_AMQP_CONTAINER_PATH),
                files.getValue().keySet());
    }
}
