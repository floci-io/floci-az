package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A {@code 0} host port in a spec means "any port". It must reach Docker as an empty binding so the
 * daemon assigns a free host port when it binds, rather than floci-az probing for one in its own
 * network namespace, which says nothing about the Docker host and reserves nothing before the bind.
 */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerPortBindingTest {

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
    void aZeroHostPortLeavesTheChoiceToDocker() {
        Ports.Binding binding = bindingFor(Map.of(8080, 0), 8080);

        assertTrue(binding.getHostPortSpec() == null || binding.getHostPortSpec().isEmpty(),
                "a dynamic port must be sent without a host port, got " + binding.getHostPortSpec());
    }

    @Test
    void aFixedHostPortIsSentAsIs() {
        Ports.Binding binding = bindingFor(Map.of(6379, 6380), 6379);

        assertEquals("6380", binding.getHostPortSpec());
    }

    private Ports.Binding bindingFor(Map<Integer, Integer> portBindings, int containerPort) {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("container-id");
        when(createCmd.exec()).thenReturn(response);

        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", null, List.of(), null, null, null, portBindings,
                List.copyOf(portBindings.keySet()), null, List.of(), List.of(), List.of(), Map.of(), null,
                false, null, List.of(), null, null, List.of());
        new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector, config).create(spec);

        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfig.capture());
        Ports.Binding[] bindings = hostConfig.getValue().getPortBindings().getBindings()
                .get(ExposedPort.tcp(containerPort));
        assertEquals(1, bindings.length);
        return bindings[0];
    }
}
