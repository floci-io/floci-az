package io.floci.az.services.vm;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerPresence;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerStateInfo;
import io.floci.az.services.vm.VmModels.VirtualMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The VM power actions read their outcome back from Docker. {@link ContainerLifecycleManager} logs and swallows a
 * Docker error, so these stub what Docker reports after the action, the way a daemon that refused it would leave it.
 */
class VmContainerManagerPowerTest {

    private static final String CONTAINER = "vm-container";

    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private VmContainerManager manager;
    private VirtualMachine vm;

    @BeforeEach
    void setUp() {
        manager = new VmContainerManager(mock(ContainerBuilder.class), lifecycle, mock(VmImageResolver.class),
                mock(EmulatorConfig.class));
        vm = new VirtualMachine();
        vm.setName("vm1");
        vm.setContainerId(CONTAINER);
    }

    @Test
    void aStartIsReachedOnlyWhenDockerReportsTheContainerRunning() {
        when(lifecycle.isContainerRunning(CONTAINER)).thenReturn(true);
        assertTrue(manager.startContainer(vm));

        when(lifecycle.isContainerRunning(CONTAINER)).thenReturn(false);
        assertFalse(manager.startContainer(vm));
        verify(lifecycle, times(2)).start(CONTAINER);
    }

    @Test
    void aStopIsReachedOnlyWhenDockerConfirmsTheContainerIsNotRunning() {
        when(lifecycle.presence(CONTAINER)).thenReturn(ContainerPresence.STOPPED);
        assertTrue(manager.stopContainer(vm));
        when(lifecycle.presence(CONTAINER)).thenReturn(ContainerPresence.ABSENT);
        assertTrue(manager.stopContainer(vm));

        when(lifecycle.presence(CONTAINER)).thenReturn(ContainerPresence.RUNNING);
        assertFalse(manager.stopContainer(vm));
        when(lifecycle.presence(CONTAINER)).thenReturn(ContainerPresence.UNKNOWN);
        assertFalse(manager.stopContainer(vm));
    }

    @Test
    void aRestartIsReachedOnlyWhenTheContainerRunsWithANewStartTime() {
        when(lifecycle.containerState(CONTAINER)).thenReturn(
                state("running", "2026-11-11T10:00:00Z"), state("running", "2026-11-11T10:05:00Z"));
        assertTrue(manager.restartContainer(vm));

        when(lifecycle.containerState(CONTAINER)).thenReturn(
                state("running", "2026-11-11T10:00:00Z"), state("running", "2026-11-11T10:00:00Z"));
        assertFalse(manager.restartContainer(vm), "still running from before: the restart did not happen");

        when(lifecycle.containerState(CONTAINER)).thenReturn(
                state("running", "2026-11-11T10:00:00Z"), state("exited", "2026-11-11T10:05:00Z"));
        assertFalse(manager.restartContainer(vm));
    }

    @Test
    void aVmWithNoContainerCannotBeStartedOrRestartedAndHasNothingToStop() {
        vm.setContainerId(null);
        assertFalse(manager.startContainer(vm));
        assertFalse(manager.restartContainer(vm));
        assertTrue(manager.stopContainer(vm));
        verifyNoInteractions(lifecycle);
    }

    private static Optional<ContainerStateInfo> state(String status, String startedAt) {
        return Optional.of(new ContainerStateInfo(status, 0, startedAt, null));
    }
}
