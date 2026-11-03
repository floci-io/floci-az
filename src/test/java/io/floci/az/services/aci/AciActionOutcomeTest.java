package io.floci.az.services.aci;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerStateInfo;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.services.aci.AciModels.ContainerGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * What {@link AciContainerGroupManager} reports for a start or restart, read from the Docker state each action leaves.
 * A false here is a 409 to the client, so a container that ran and exited must not look like one Docker never started.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ACI: a start or restart reports whether Docker started the containers")
class AciActionOutcomeTest {

    private static final String BEFORE = "2026-11-01T10:00:00.000000000Z";
    private static final String AFTER = "2026-11-01T10:05:00.000000000Z";
    private static final String NEVER_STARTED = "0001-01-01T00:00:00Z";

    private static final ContainerStateInfo STOPPED = new ContainerStateInfo("exited", 137, BEFORE, BEFORE);
    private static final ContainerStateInfo RUNNING = new ContainerStateInfo("running", 0, AFTER, NEVER_STARTED);
    private static final ContainerStateInfo RAN_AND_EXITED = new ContainerStateInfo("exited", 0, AFTER, AFTER);
    private static final ContainerStateInfo RAN_AND_FAILED = new ContainerStateInfo("exited", 1, AFTER, AFTER);
    private static final ContainerStateInfo NEVER_RAN =
        new ContainerStateInfo("created", 0, NEVER_STARTED, NEVER_STARTED);

    @Mock
    ContainerBuilder containerBuilder;

    @Mock
    ContainerLifecycleManager lifecycleManager;

    @Mock
    PortAllocator portAllocator;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    EmulatorConfig config;

    /** The Docker state per container, which a start or restart replaces with {@code afterAction}. */
    private final Map<String, ContainerStateInfo> docker = new LinkedHashMap<>();

    private AciContainerGroupManager manager() {
        lenient().when(lifecycleManager.containerState(anyString()))
            .thenAnswer(call -> Optional.ofNullable(docker.get(call.<String>getArgument(0))));
        return new AciContainerGroupManager(containerBuilder, lifecycleManager, portAllocator, containerDetector,
            config);
    }

    private ContainerGroup groupOf(ContainerStateInfo... states) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (int i = 0; i < states.length; i++) {
            ids.put("container-" + i, "id-" + i);
            docker.put("id-" + i, states[i]);
        }
        ContainerGroup group = new ContainerGroup();
        group.setName("group");
        group.setContainerIds(ids);
        return group;
    }

    private void anActionLeaves(ContainerStateInfo... afterAction) {
        Answer<Void> action = call -> {
            String id = call.getArgument(0);
            docker.put(id, afterAction[Integer.parseInt(id.substring("id-".length()))]);
            return null;
        };
        lenient().doAnswer(action).when(lifecycleManager).start(anyString());
        lenient().doAnswer(action).when(lifecycleManager).restart(anyString(), anyInt());
    }

    @Test
    void aStartThatLeavesTheContainerRunningSucceeds() {
        AciContainerGroupManager manager = manager();
        ContainerGroup group = groupOf(STOPPED);
        anActionLeaves(RUNNING);

        assertTrue(manager.startGroupContainers(group));
    }

    @Test
    void aCommandThatExitsCleanlyBeforeTheCheckWasStillStarted() {
        AciContainerGroupManager manager = manager();
        ContainerGroup group = groupOf(STOPPED);
        anActionLeaves(RAN_AND_EXITED);

        assertTrue(manager.startGroupContainers(group));
    }

    @Test
    void aCommandThatExitsWithAnErrorWasStillStarted() {
        AciContainerGroupManager manager = manager();
        ContainerGroup group = groupOf(STOPPED);
        anActionLeaves(RAN_AND_FAILED);

        assertTrue(manager.startGroupContainers(group));
    }

    @Test
    void aStartDockerRefusedKeepsTheLastRunsExitCodeAndFails() {
        AciContainerGroupManager manager = manager();
        ContainerGroup group = groupOf(RAN_AND_EXITED);
        anActionLeaves(RAN_AND_EXITED);

        assertFalse(manager.startGroupContainers(group));
    }

    @Test
    void aContainerThatNeverRanFailsDespiteItsZeroExitCode() {
        AciContainerGroupManager manager = manager();
        ContainerGroup group = groupOf(NEVER_RAN);
        anActionLeaves(NEVER_RAN);

        assertFalse(manager.startGroupContainers(group));
    }

    @Test
    void oneContainerLeftBehindFailsTheGroup() {
        AciContainerGroupManager manager = manager();
        ContainerGroup group = groupOf(STOPPED, STOPPED);
        anActionLeaves(RUNNING, STOPPED);

        assertFalse(manager.startGroupContainers(group));
    }

    @Test
    void aRunningContainerAndOneThatRanAndExitedBothCount() {
        AciContainerGroupManager manager = manager();
        ContainerGroup group = groupOf(STOPPED, STOPPED);
        anActionLeaves(RUNNING, RAN_AND_EXITED);

        assertTrue(manager.startGroupContainers(group));
    }

    @Test
    void aRestartOfACommandThatExitsAgainSucceeds() {
        AciContainerGroupManager manager = manager();
        ContainerGroup group = groupOf(new ContainerStateInfo("exited", 0, BEFORE, BEFORE));
        anActionLeaves(RAN_AND_EXITED);

        assertTrue(manager.restartGroupContainers(group));
    }

    @Test
    void aRestartDockerRefusedFails() {
        AciContainerGroupManager manager = manager();
        ContainerGroup group = groupOf(STOPPED);
        anActionLeaves(STOPPED);

        assertFalse(manager.restartGroupContainers(group));
    }
}
