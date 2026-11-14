package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import io.floci.az.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A sidecar answers to extra names (Redis: {@code <name>.redis.cache.windows.net}) on a user-defined network. The
 * daemon refuses aliases on a built-in one, so they must never be sent there.
 */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerNetworkAliasTest {

    private static final List<String> ALIASES = List.of("cache.redis.cache.windows.net");

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
    void aliasesAreSentWithTheCreateOnAUserDefinedNetwork() {
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(spec("app-net", ALIASES));

        verify(createCmd).withAliases(ALIASES);
    }

    @Test
    void aliasesAreNeverSentOnTheDefaultBridge() {
        CreateContainerCmd createCmd = stubCreateContainer();

        manager().create(spec("bridge", ALIASES));

        verify(createCmd, never()).withAliases(anyList());
    }

    @Test
    void aHostRunContainerPublishingPortsGetsItsAliasesOnlyWhenItJoinsTheNetwork() {
        // Created on the default bridge and connected after start: docker-java would file create-time aliases
        // under an unset network mode.
        CreateContainerCmd createCmd = stubCreateContainer();
        when(containerDetector.isRunningInContainer()).thenReturn(false);

        manager().create(spec("app-net", ALIASES, Map.of(6379, 16379)));

        verify(createCmd, never()).withAliases(anyList());
    }

    @Test
    void onlyAUserDefinedNetworkTakesAliases() {
        assertTrue(ContainerLifecycleManager.takesNetworkAliases(spec("app-net", ALIASES)));
        assertFalse(ContainerLifecycleManager.takesNetworkAliases(spec("bridge", ALIASES)));
        assertFalse(ContainerLifecycleManager.takesNetworkAliases(spec("host", ALIASES)));
        assertFalse(ContainerLifecycleManager.takesNetworkAliases(spec(null, ALIASES)));
        assertFalse(ContainerLifecycleManager.takesNetworkAliases(spec("app-net", List.of())));
    }

    private static ContainerSpec spec(String network, List<String> aliases) {
        return spec(network, aliases, Map.of());
    }

    private static ContainerSpec spec(String network, List<String> aliases, Map<Integer, Integer> portBindings) {
        return new ContainerSpec("busybox:stable", null, List.of(), null, null, null, portBindings, List.of(), network,
                List.of(), List.of(), List.of(), Map.of(), null, false, null, List.of(), null, null, List.of(),
                aliases);
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
}
