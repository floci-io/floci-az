package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CurrentContainerNetworkResolverOwnerTest {

    @Test
    void resolvesVerifiedDockerContainerId() {
        DockerClient dockerClient = mock(DockerClient.class);
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(true);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        InspectContainerResponse inspect = mock(InspectContainerResponse.class);
        when(dockerClient.inspectContainerCmd("hostname-id")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspect);
        when(inspect.getId()).thenReturn("full-container-id");

        assertEquals(Optional.of("full-container-id"),
                resolver(dockerClient, detector).resolveContainerId());
    }

    @Test
    void returnsEmptyWhenCurrentContainerCannotBeVerified() {
        DockerClient dockerClient = mock(DockerClient.class);
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(true);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("hostname-id")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenThrow(new IllegalStateException("inspect failed"));

        assertEquals(Optional.empty(), resolver(dockerClient, detector).resolveContainerId());
    }

    @Test
    void skipsDockerLookupOutsideAContainer() {
        DockerClient dockerClient = mock(DockerClient.class);
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(false);

        assertEquals(Optional.empty(), resolver(dockerClient, detector).resolveContainerId());
        verify(dockerClient, never()).inspectContainerCmd("hostname-id");
    }

    private static CurrentContainerNetworkResolver resolver(
            DockerClient dockerClient, ContainerDetector detector) {
        return new CurrentContainerNetworkResolver(dockerClient, detector) {
            @Override
            String currentContainerId() {
                return "hostname-id";
            }
        };
    }
}
