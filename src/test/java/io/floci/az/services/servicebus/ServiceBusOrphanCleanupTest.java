package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.CurrentContainerNetworkResolver;
import io.floci.az.services.eventhub.ArtemisTlsGenerator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceBusOrphanCleanupTest {

    @Test
    void scopesCleanupToConfiguredResourceNamespaceAndServicePrefix() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(docker);
        when(docker.resourceNamespace()).thenReturn(Optional.of("run-one"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.removeOrphanedContainers(
                "floci-az-run-one-servicebus-",
                Map.of(
                        "floci", "true",
                        "floci_emulator", "floci-az",
                        "floci_namespace", "run-one"),
                "floci_owner_container"))
                .thenReturn(2);

        ServiceBusNamespaceManager manager = new ServiceBusNamespaceManager(
                config,
                mock(ContainerBuilder.class),
                lifecycleManager,
                mock(CurrentContainerNetworkResolver.class),
                mock(ServiceBusConfigGenerator.class),
                mock(ArtemisTlsGenerator.class));

        assertEquals(2, manager.reapOrphanedContainers());
        verify(lifecycleManager).removeOrphanedContainers(
                "floci-az-run-one-servicebus-",
                Map.of(
                        "floci", "true",
                        "floci_emulator", "floci-az",
                        "floci_namespace", "run-one"),
                "floci_owner_container");
    }

    @Test
    void labelsSidecarWithVerifiedOwnerContainer() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        CurrentContainerNetworkResolver currentContainer =
                mock(CurrentContainerNetworkResolver.class);
        when(currentContainer.resolveContainerId()).thenReturn(Optional.of("owner-id"));
        ServiceBusNamespaceManager manager = new ServiceBusNamespaceManager(
                config,
                mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class),
                currentContainer,
                mock(ServiceBusConfigGenerator.class),
                mock(ArtemisTlsGenerator.class));

        assertEquals(Map.of(
                "floci_service", "servicebus",
                "floci_owner_container", "owner-id"), manager.serviceContainerLabels());
    }

    @Test
    void existingFiveArgumentConstructionRemainsSupported() {
        ServiceBusNamespaceManager manager = new ServiceBusNamespaceManager(
                mock(EmulatorConfig.class),
                mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class),
                mock(ServiceBusConfigGenerator.class),
                mock(ArtemisTlsGenerator.class));

        assertEquals(Map.of("floci_service", "servicebus"), manager.serviceContainerLabels());
    }
}
