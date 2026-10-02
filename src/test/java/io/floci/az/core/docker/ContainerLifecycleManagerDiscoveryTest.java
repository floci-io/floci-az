package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import io.floci.az.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Label-driven discovery reads {@code io.floci.service} new-first and falls back to its legacy
 * alias {@code floci_service}, so containers created before the new keys keep matching, and a
 * container whose two keys disagree is never matched.
 */
class ContainerLifecycleManagerDiscoveryTest {

    private static final String ENVIRONMENT_LABEL = "floci_containerapps_environment";
    private static final Map<String, String> REQUIRED = Map.of(
            "io.floci.service", "containerapps",
            ENVIRONMENT_LABEL, "env-one");

    @Test
    void runningContainerAddressesMatchesNewOnlyLegacyOnlyAndAgreeingContainers() {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        List<Container> containers = List.of(
                container("new-only", Map.of(
                        "io.floci.service", "containerapps", ENVIRONMENT_LABEL, "env-one")),
                container("legacy-only", Map.of(
                        "floci_service", "containerapps", ENVIRONMENT_LABEL, "env-one")),
                container("both-agree", Map.of(
                        "io.floci.service", "containerapps", "floci_service", "containerapps",
                        ENVIRONMENT_LABEL, "env-one")),
                container("both-disagree", Map.of(
                        "io.floci.service", "containerapps", "floci_service", "servicebus",
                        ENVIRONMENT_LABEL, "env-one")),
                container("other-service", Map.of(
                        "io.floci.service", "servicebus", ENVIRONMENT_LABEL, "env-one")),
                container("other-environment", Map.of(
                        "io.floci.service", "containerapps", ENVIRONMENT_LABEL, "env-two")));
        when(listCmd.exec()).thenReturn(containers);

        ContainerLifecycleManager manager = spy(manager(dockerClient));
        doReturn(List.of("10.0.0.1")).when(manager).containerAddresses("new-only");
        doReturn(List.of("10.0.0.2")).when(manager).containerAddresses("legacy-only");
        doReturn(List.of("10.0.0.3")).when(manager).containerAddresses("both-agree");

        assertEquals(List.of("10.0.0.1", "10.0.0.2", "10.0.0.3"),
                manager.runningContainerAddresses(REQUIRED));
        verify(manager, never()).containerAddresses("both-disagree");
        verify(manager, never()).containerAddresses("other-service");
        verify(manager, never()).containerAddresses("other-environment");
    }

    private static Container container(String id, Map<String, String> labels) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getLabels()).thenReturn(labels);
        return container;
    }

    private static ContainerLifecycleManager manager(DockerClient dockerClient) {
        return new ContainerLifecycleManager(
                dockerClient,
                mock(ImageCacheService.class),
                mock(ContainerDetector.class),
                mock(PortAllocator.class),
                mock(EmulatorConfig.class));
    }
}
