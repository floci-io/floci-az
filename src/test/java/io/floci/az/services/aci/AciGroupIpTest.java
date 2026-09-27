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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        lenient().when(lifecycleManager.daemonAddress()).thenReturn("docker");
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

    @Test
    @DisplayName("published mode, IPv6 daemon: ipAddress.ip is the bare address")
    void publishedIpv6() {
        inContainer(true);
        lenient().when(lifecycleManager.daemonAddress()).thenReturn("::1");
        assertEquals("::1", manager().groupIp(group(List.of(80), List.of(80))));
    }

    // ---------------------------------------------------------- published port allocation

    private void portTaken(int port) {
        lenient().when(portAllocator.allocate(port, port)).thenThrow(new IllegalStateException("port in use"));
    }

    @Test
    @DisplayName("published mode: each port is published on its own number")
    void publishedAllocatesSameNumbers() {
        lenient().when(lifecycleManager.publishedEndpoints()).thenReturn(true);
        lenient().when(portAllocator.allocate(80, 80)).thenReturn(80);
        lenient().when(portAllocator.allocate(443, 443)).thenReturn(443);
        List<Integer> allocated = new ArrayList<>();
        assertEquals(Map.of(80, 80, 443, 443),
                manager().allocatePublishedPorts(group(List.of(80, 443), null), allocated));
        assertEquals(List.of(80, 443), allocated);
    }

    @Test
    @DisplayName("published mode: a port already in use fails the start, naming the port")
    void publishedConflictFails() {
        lenient().when(lifecycleManager.publishedEndpoints()).thenReturn(true);
        lenient().when(portAllocator.allocate(80, 80)).thenReturn(80);
        portTaken(443);
        List<Integer> allocated = new ArrayList<>();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> manager().allocatePublishedPorts(group(List.of(80, 443), null), allocated));
        assertTrue(e.getMessage().contains("port 443 is already in use"), e.getMessage());
        // only the port that was allocated is recorded, so startGroup releases exactly that one
        assertEquals(List.of(80), allocated);
    }

    @Test
    @DisplayName("auto mode: a port already in use falls back to the configured range, as before")
    void autoConflictFallsBack() {
        EmulatorConfig.ServicesConfig services = org.mockito.Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.AciConfig aci = org.mockito.Mockito.mock(EmulatorConfig.AciConfig.class);
        lenient().when(config.services()).thenReturn(services);
        lenient().when(services.aci()).thenReturn(aci);
        lenient().when(aci.basePort()).thenReturn(30000);
        lenient().when(aci.maxPort()).thenReturn(30099);
        lenient().when(lifecycleManager.publishedEndpoints()).thenReturn(false);
        portTaken(80);
        lenient().when(portAllocator.allocate(30000, 30099)).thenReturn(30000);
        assertEquals(Map.of(80, 30000),
                manager().allocatePublishedPorts(group(List.of(80), null), new ArrayList<>()));
    }
}
