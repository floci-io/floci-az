package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two floci-az instances on one Docker host, each in its own container and so its own network
 * namespace, must not both settle on the same host port for their sidecars (ACR registry, Redis
 * cache) just because each one's in-namespace {@link PortAllocator#isPortFree} probe sees nothing
 * bound.
 *
 * <p>A single process cannot start a second floci-az instance, but it can reproduce what causes the
 * collision: a port another container on the same Docker host already publishes, which only the
 * Docker API reveals. A mocked {@link DockerClient} stands in for that sibling's port binding.
 */
class PortAllocatorDockerAwareTest {

    @Test
    void allocateSkipsAPortAnotherContainerPublishes() {
        int base = freeBasePort();
        PortAllocator allocator = new PortAllocator(dockerReporting(base));

        // base is free from this process's own socket point of view, but the Docker API reports a
        // sibling publishing it. Remove the published check from allocate() and this picks base.
        int allocated = allocator.allocate(base, base + 50);

        assertNotEquals(base, allocated,
                "a port another container publishes must not be allocated, even though the local probe sees it free");
        allocator.release(allocated);
    }

    @Test
    void allocateQueriesDockerOncePerCall() {
        int base = freeBasePort();
        DockerClient dockerClient = dockerReporting(base, base + 1, base + 2);
        PortAllocator allocator = new PortAllocator(dockerClient);

        int allocated = allocator.allocate(base, base + 50);

        assertNotEquals(base, allocated);
        assertNotEquals(base + 1, allocated);
        assertNotEquals(base + 2, allocated);
        verify(dockerClient, times(1)).listContainersCmd();
        allocator.release(allocated);
    }

    @Test
    void dockerFailureDoesNotBlockAllocation() {
        int base = freeBasePort();
        DockerClient dockerClient = mock(DockerClient.class);
        when(dockerClient.listContainersCmd()).thenThrow(new RuntimeException("docker daemon unreachable"));
        PortAllocator allocator = new PortAllocator(dockerClient);

        int allocated = allocator.allocate(base, base + 50);

        assertEquals(base, allocated, "a Docker API error falls back to the local probe instead of failing");
        allocator.release(allocated);
    }

    @Test
    void noDockerClientKeepsTheLocalProbeOnly() {
        int base = freeBasePort();
        PortAllocator allocator = new PortAllocator();

        int allocated = allocator.allocate(base, base + 50);

        assertEquals(base, allocated, "with no DockerClient the first locally free port is still chosen");
        allocator.release(allocated);
    }

    @Test
    void claimOrZeroFallsBackForAPortAnotherContainerPublishes() {
        int port = freeBasePort();
        DockerClient dockerClient = dockerReporting(port);
        PortAllocator allocator = new PortAllocator(dockerClient);

        assertEquals(0, allocator.claimOrZero(port),
                "a configured port a sibling container publishes must fall back to an OS-assigned one");
        verify(dockerClient, times(1)).listContainersCmd();
    }

    @Test
    void claimOrZeroDoesNotAskDockerWithoutAPreference() {
        DockerClient dockerClient = mock(DockerClient.class);
        PortAllocator allocator = new PortAllocator(dockerClient);

        assertEquals(0, allocator.claimOrZero(0));
        verify(dockerClient, never()).listContainersCmd();
    }

    /** A port that is free locally right now, used as the bottom of a test range. */
    private static int freeBasePort() {
        PortAllocator probe = new PortAllocator();
        return probe.allocateAny();
    }

    private static DockerClient dockerReporting(int... publishedPorts) {
        DockerClient dockerClient = mock(DockerClient.class);
        ListContainersCmd listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withShowAll(anyBoolean())).thenReturn(listCmd);

        ContainerPort[] ports = new ContainerPort[publishedPorts.length];
        for (int i = 0; i < publishedPorts.length; i++) {
            ports[i] = new ContainerPort().withPublicPort(publishedPorts[i]);
        }
        Container sibling = mock(Container.class);
        when(sibling.getPorts()).thenReturn(ports);
        when(listCmd.exec()).thenReturn(List.of(sibling));
        return dockerClient;
    }
}
