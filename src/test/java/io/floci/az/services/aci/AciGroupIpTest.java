package io.floci.az.services.aci;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.services.aci.AciModels.ContainerGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;

/**
 * An ACI group reports one IP, and clients combine it with the group's container ports. In
 * published endpoint mode the Docker daemon's host only pairs with those ports when each is
 * published on the same number; otherwise the group must keep reporting the auto address.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AciContainerGroupManager — reported group IP")
class AciGroupIpTest {

    private static final String CONTAINER_ID = "c1";
    private static final String CONTAINER_IP = "172.18.0.9";

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

    private AciContainerGroupManager manager() {
        return new AciContainerGroupManager(containerBuilder, lifecycleManager, portAllocator, containerDetector, config);
    }

    private static ContainerGroup group(List<Integer> containerPorts, List<Integer> hostPorts) {
        ContainerGroup group = new ContainerGroup();
        group.setName("web");
        group.setProperties(Map.of("ipAddress", Map.of(
                "ports", containerPorts.stream().map(p -> Map.<String, Object>of("port", p)).toList())));
        group.setContainerIds(Map.of("web", CONTAINER_ID));
        group.setAllocatedHostPorts(hostPorts);
        return group;
    }

    private void inContainer(boolean published) {
        lenient().when(lifecycleManager.publishedEndpoints()).thenReturn(published);
        lenient().when(lifecycleManager.daemonHost()).thenReturn("docker");
        lenient().when(containerDetector.isRunningInContainer()).thenReturn(true);
        lenient().when(lifecycleManager.resolveAutoEndpoint(CONTAINER_ID, 80))
                .thenReturn(new ContainerLifecycleManager.EndpointInfo(CONTAINER_IP, 80));
    }

    @Test
    @DisplayName("auto mode: the container's network IP, as before")
    void autoMode() {
        inContainer(false);
        assertEquals(CONTAINER_IP, manager().groupIp(group(List.of(80), List.of(80))));
    }

    @Test
    @DisplayName("published mode, ports published on their own numbers: the daemon's host")
    void publishedSamePorts() {
        inContainer(true);
        assertEquals("docker", manager().groupIp(group(List.of(80, 443), List.of(80, 443))));
    }

    @Test
    @DisplayName("published mode, a port remapped: the auto address, which pairs with the container ports")
    void publishedRemappedPort() {
        inContainer(true);
        assertEquals(CONTAINER_IP, manager().groupIp(group(List.of(80, 443), List.of(80, 30443))));
    }

    @Test
    @DisplayName("published mode, floci-az on the host with a remapped port: 127.0.0.1, as before")
    void publishedRemappedOnHost() {
        lenient().when(lifecycleManager.publishedEndpoints()).thenReturn(true);
        lenient().when(containerDetector.isRunningInContainer()).thenReturn(false);
        assertEquals("127.0.0.1", manager().groupIp(group(List.of(80), List.of(30080))));
    }
}
