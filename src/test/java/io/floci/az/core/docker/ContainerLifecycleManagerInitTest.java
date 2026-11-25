package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import io.floci.az.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A spec asking for Docker's init process reaches the container's host config; one that does not leaves it unset. */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerInitTest {

    @Mock
    DockerClient dockerClient;

    @Mock
    ImageCacheService imageCacheService;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    EmulatorConfig config;

    @Mock
    EmulatorConfig.DockerConfig dockerConfig;

    @BeforeEach
    void setUp() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
    }

    @Test
    void aSpecWithInitRunsDockersInitProcess() {
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(spec(true));

        assertEquals(Boolean.TRUE, capturedHostConfig(createCmd).getInit());
    }

    @Test
    void aSpecWithoutInitLeavesTheDaemonDefault() {
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(spec(false));

        assertNotEquals(Boolean.TRUE, capturedHostConfig(createCmd).getInit());
    }

    @Test
    void theSpecConstructorsThatPredateInitLeaveItOff() {
        assertEquals(false, new ContainerSpec("busybox:stable").init());
    }

    private static ContainerSpec spec(boolean init) {
        return new ContainerSpec("busybox:stable", null, List.of(), List.of("tail", "-f", "/dev/null"), null, null,
                Map.of(), List.of(), null, List.of(), List.of(), List.of(), Map.of(), null, false, null, List.of(),
                null, null, List.of(), List.of(), init);
    }

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector, config);
    }

    private CreateContainerCmd stubCreateContainer() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("container-id");
        when(createCmd.exec()).thenReturn(response);
        return createCmd;
    }

    private static HostConfig capturedHostConfig(CreateContainerCmd createCmd) {
        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfig.capture());
        return hostConfig.getValue();
    }
}
