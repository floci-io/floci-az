package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.Ports;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.config.EmulatorConfig.DockerEndpointMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetAddress;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * How floci-az addresses a sidecar. {@code auto} must behave exactly as before; {@code published}
 * uses the Docker daemon's host and the published port, which is what a remote daemon
 * (docker-in-docker, kubedock) needs — its container IPs aren't reachable from floci-az.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContainerLifecycleManager — sidecar endpoint mode")
class ContainerLifecycleManagerEndpointModeTest {

    private static final String CONTAINER_ID = "c1";
    private static final String CONTAINER_IP = "172.18.0.5";
    private static final int CONTAINER_PORT = 5672;
    private static final int HOST_PORT = 5673;

    @Mock
    DockerClient dockerClient;

    @Mock
    ImageCacheService imageCacheService;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    PortAllocator portAllocator;

    @Mock
    EmulatorConfig config;

    @Mock
    EmulatorConfig.DockerConfig dockerConfig;

    @Mock
    InspectContainerCmd inspectCmd;

    @Mock
    InspectContainerResponse inspect;

    @Mock
    NetworkSettings networkSettings;

    @BeforeEach
    void setUp() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(dockerClient.inspectContainerCmd(CONTAINER_ID)).thenReturn(inspectCmd);
        lenient().when(inspectCmd.exec()).thenReturn(inspect);
        lenient().when(inspect.getNetworkSettings()).thenReturn(networkSettings);
        ContainerNetwork network = mock(ContainerNetwork.class);
        lenient().when(network.getIpAddress()).thenReturn(CONTAINER_IP);
        lenient().when(networkSettings.getNetworks()).thenReturn(Map.of("bridge", network));
        publishPort(true);
    }

    private void publishPort(boolean published) {
        Ports ports = new Ports();
        if (published) {
            ports.bind(ExposedPort.tcp(CONTAINER_PORT), Ports.Binding.bindPort(HOST_PORT));
        }
        lenient().when(networkSettings.getPorts()).thenReturn(ports);
    }

    private void mode(DockerEndpointMode mode, String dockerHost) {
        lenient().when(dockerConfig.endpointMode()).thenReturn(mode);
        lenient().when(dockerConfig.dockerHost()).thenReturn(dockerHost);
    }

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(
                dockerClient, imageCacheService, containerDetector, portAllocator, config);
    }

    private ContainerLifecycleManager.EndpointInfo resolve() {
        return manager().resolveEndpoint(CONTAINER_ID, CONTAINER_PORT);
    }

    // ------------------------------------------------------------------- auto (unchanged)

    @Test
    @DisplayName("auto, floci-az on the host: localhost and the published port")
    void autoOnHost() {
        mode(DockerEndpointMode.AUTO, "unix:///var/run/docker.sock");
        lenient().when(containerDetector.isRunningInContainer()).thenReturn(false);
        assertEquals(new ContainerLifecycleManager.EndpointInfo("localhost", HOST_PORT), resolve());
    }

    @Test
    @DisplayName("auto, floci-az in a container: the container IP and the internal port")
    void autoInContainer() {
        mode(DockerEndpointMode.AUTO, "unix:///var/run/docker.sock");
        lenient().when(containerDetector.isRunningInContainer()).thenReturn(true);
        assertEquals(new ContainerLifecycleManager.EndpointInfo(CONTAINER_IP, CONTAINER_PORT), resolve());
    }

    @Test
    @DisplayName("an unset endpoint mode behaves as auto")
    void unsetModeIsAuto() {
        mode(null, "unix:///var/run/docker.sock");
        lenient().when(containerDetector.isRunningInContainer()).thenReturn(true);
        assertFalse(manager().publishedEndpoints());
        assertEquals(new ContainerLifecycleManager.EndpointInfo(CONTAINER_IP, CONTAINER_PORT), resolve());
    }

    // ------------------------------------------------------------------------- published

    @Test
    @DisplayName("published, docker-in-docker: the daemon's host and the published port")
    void publishedUsesDaemonHost() {
        mode(DockerEndpointMode.PUBLISHED, "tcp://docker:2375");
        lenient().when(containerDetector.isRunningInContainer()).thenReturn(true);
        assertTrue(manager().publishedEndpoints());
        assertEquals(new ContainerLifecycleManager.EndpointInfo("docker", HOST_PORT), resolve());
    }

    /** kubedock in the same pod: it reports 127.0.0.1 as every container IP and serves published ports. */
    @Test
    @DisplayName("published, daemon on localhost (kubedock): localhost and the published port")
    void publishedWithLocalDaemon() {
        mode(DockerEndpointMode.PUBLISHED, "tcp://localhost:2475");
        lenient().when(containerDetector.isRunningInContainer()).thenReturn(true);
        assertEquals(new ContainerLifecycleManager.EndpointInfo("localhost", HOST_PORT), resolve());
    }

    @Test
    @DisplayName("published applies when floci-az runs on the host too")
    void publishedOnHost() {
        mode(DockerEndpointMode.PUBLISHED, "tcp://build-host.internal:2376");
        lenient().when(containerDetector.isRunningInContainer()).thenReturn(false);
        assertEquals(new ContainerLifecycleManager.EndpointInfo("build-host.internal", HOST_PORT), resolve());
    }

    @Test
    @DisplayName("published, port without a binding: falls back to the auto address")
    void publishedWithoutBindingFallsBack() {
        mode(DockerEndpointMode.PUBLISHED, "tcp://docker:2375");
        publishPort(false);
        lenient().when(containerDetector.isRunningInContainer()).thenReturn(true);
        assertEquals(new ContainerLifecycleManager.EndpointInfo(CONTAINER_IP, CONTAINER_PORT), resolve());
    }

    // ------------------------------------------------------------------- daemon hostname

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "tcp://docker:2375, docker",
            "tcp://localhost:2475, localhost",
            "tcp://10.0.0.7:2376, 10.0.0.7",
            "TCP://Build-Host:2376, Build-Host",
            "http://docker:2375, docker",
            "https://docker:2376, docker",
            "'tcp://[::1]:2375', [::1]",
            "unix:///var/run/docker.sock, localhost",
            "npipe:////./pipe/docker_engine, localhost",
            "'', localhost",
            "not a uri, localhost"
    })
    @DisplayName("the daemon hostname comes from the Docker host URI")
    void daemonHostname(String dockerHost, String expected) {
        assertEquals(expected, ContainerLifecycleManager.daemonHostname(dockerHost));
    }

    /** Keeping the brackets makes the host usable in URLs (AKS, ACR) and still valid for sockets. */
    @Test
    @DisplayName("published, IPv6 daemon: the bracketed host, usable in URLs and sockets")
    void publishedIpv6Daemon() throws Exception {
        mode(DockerEndpointMode.PUBLISHED, "tcp://[::1]:2375");
        lenient().when(containerDetector.isRunningInContainer()).thenReturn(true);
        ContainerLifecycleManager.EndpointInfo ep = resolve();
        assertEquals(new ContainerLifecycleManager.EndpointInfo("[::1]", HOST_PORT), ep);
        assertEquals("https://[::1]:" + HOST_PORT, java.net.URI.create("https://" + ep.host() + ":" + ep.port()).toString());
        assertTrue(InetAddress.getByName(ep.host()).isLoopbackAddress());
    }

    @Test
    @DisplayName("resolveAutoEndpoint ignores published mode")
    void autoEndpointIgnoresPublishedMode() {
        mode(DockerEndpointMode.PUBLISHED, "tcp://docker:2375");
        lenient().when(containerDetector.isRunningInContainer()).thenReturn(true);
        assertEquals(new ContainerLifecycleManager.EndpointInfo(CONTAINER_IP, CONTAINER_PORT),
                manager().resolveAutoEndpoint(CONTAINER_ID, CONTAINER_PORT));
    }

    @Test
    @DisplayName("a missing Docker host resolves to localhost")
    void daemonHostnameNull() {
        assertEquals("localhost", ContainerLifecycleManager.daemonHostname(null));
    }
}
