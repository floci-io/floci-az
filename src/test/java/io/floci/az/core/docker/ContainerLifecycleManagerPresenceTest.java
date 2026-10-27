package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerPresence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ContainerLifecycleManager#presence} tells a missing container apart from one Docker could not inspect,
 * so a caller that recreates gone containers leaves a healthy one alone during a daemon hiccup.
 */
class ContainerLifecycleManagerPresenceTest {

    private DockerClient dockerClient;
    private InspectContainerCmd inspect;
    private ContainerLifecycleManager manager;

    @BeforeEach
    void setUp() {
        dockerClient = mock(DockerClient.class);
        inspect = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("c1")).thenReturn(inspect);
        manager = new ContainerLifecycleManager(dockerClient, mock(ImageCacheService.class),
                mock(ContainerDetector.class), mock(EmulatorConfig.class));
    }

    private void stateRunning(Boolean running) {
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(state.getRunning()).thenReturn(running);
        when(response.getState()).thenReturn(state);
        when(inspect.exec()).thenReturn(response);
    }

    @Test
    void aRunningContainerIsRunning() {
        stateRunning(true);
        assertEquals(ContainerPresence.RUNNING, manager.presence("c1"));
    }

    @Test
    void anExitedContainerIsStopped() {
        stateRunning(false);
        assertEquals(ContainerPresence.STOPPED, manager.presence("c1"));
    }

    @Test
    void aMissingContainerIsAbsent() {
        when(inspect.exec()).thenThrow(new NotFoundException("No such container: c1"));
        assertEquals(ContainerPresence.ABSENT, manager.presence("c1"));
    }

    @Test
    void anInspectThatFailsOtherwiseIsUnknown() {
        when(inspect.exec()).thenThrow(new RuntimeException("connection reset"));
        assertEquals(ContainerPresence.UNKNOWN, manager.presence("c1"));
    }
}
