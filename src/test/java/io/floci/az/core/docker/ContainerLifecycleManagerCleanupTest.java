package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import io.floci.az.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerLifecycleManagerCleanupTest {

    @Test
    void removesOnlyMatchingContainersWhoseOwnerIsGone() {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);

        Container orphan = container(
                "orphan", new String[]{"/floci-az-run-one-servicebus-default"},
                ownedLabels("run-one", "gone-owner"));
        Container live = container(
                "live", new String[]{"/floci-az-run-one-servicebus-live"},
                ownedLabels("run-one", "live-owner"));
        Container ownerless = container(
                "ownerless", new String[]{"/floci-az-run-one-servicebus-legacy"},
                emulatorLabels("run-one"));
        Container foreignNamespace = container(
                "foreign-namespace", new String[]{"/floci-az-run-one-servicebus-other"},
                ownedLabels("run-two", "gone-owner"));
        Container unrelatedService = container(
                "unrelated-service", new String[]{"/floci-az-run-one-postgres-default"},
                ownedLabels("run-one", "gone-owner"));
        when(listCmd.exec()).thenReturn(
                List.of(orphan, live, ownerless, foreignNamespace, unrelatedService));

        InspectContainerCmd goneOwnerCmd = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("gone-owner")).thenReturn(goneOwnerCmd);
        when(goneOwnerCmd.exec()).thenThrow(new NotFoundException("gone"));
        InspectContainerCmd liveOwnerCmd = mock(InspectContainerCmd.class);
        InspectContainerResponse liveOwner = mock(InspectContainerResponse.class, RETURNS_SELF);
        when(dockerClient.inspectContainerCmd("live-owner")).thenReturn(liveOwnerCmd);
        when(liveOwnerCmd.exec()).thenReturn(liveOwner);
        when(liveOwner.getState()).thenReturn(mock(InspectContainerResponse.ContainerState.class));
        when(liveOwner.getState().getRunning()).thenReturn(true);

        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.removeContainerCmd("orphan")).thenReturn(removeCmd);

        int removed = manager(dockerClient).removeOrphanedContainers(
                "floci-az-run-one-servicebus-",
                emulatorLabels("run-one"), "floci_owner_container");

        assertEquals(1, removed);
        verify(removeCmd).withForce(true);
        verify(removeCmd).exec();
        verify(dockerClient, never()).removeContainerCmd("live");
        verify(dockerClient, never()).removeContainerCmd("ownerless");
        verify(dockerClient, never()).removeContainerCmd("foreign-namespace");
        verify(dockerClient, never()).removeContainerCmd("unrelated-service");
    }

    @Test
    void doesNotCountFailedRemovalAsReaped() {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        Container orphan = container(
                "orphan", new String[]{"/floci-az-servicebus-default"},
                ownedLabels(null, "gone-owner"));
        when(listCmd.exec()).thenReturn(List.of(orphan));
        InspectContainerCmd ownerCmd = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("gone-owner")).thenReturn(ownerCmd);
        when(ownerCmd.exec()).thenThrow(new NotFoundException("gone"));
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(dockerClient.removeContainerCmd("orphan")).thenReturn(removeCmd);
        when(removeCmd.exec()).thenThrow(new IllegalStateException("remove failed"));

        int removed = manager(dockerClient).removeOrphanedContainers(
                "floci-az-servicebus-", emulatorLabels(null), "floci_owner_container");

        assertEquals(0, removed);
    }

    private static Container container(String id, String[] names, Map<String, String> labels) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getNames()).thenReturn(names);
        when(container.getLabels()).thenReturn(labels);
        return container;
    }

    private static Map<String, String> ownedLabels(String namespace, String owner) {
        Map<String, String> labels = new LinkedHashMap<>(emulatorLabels(namespace));
        labels.put("floci_owner_container", owner);
        return labels;
    }

    private static Map<String, String> emulatorLabels(String namespace) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("floci", "true");
        labels.put("floci_emulator", "floci-az");
        if (namespace != null) {
            labels.put("floci_namespace", namespace);
        }
        return labels;
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
