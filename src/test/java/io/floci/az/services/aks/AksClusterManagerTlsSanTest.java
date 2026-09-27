package io.floci.az.services.aks;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.PortAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;

/**
 * The k3s API certificate must be valid for every host the cluster is addressed by. In published
 * endpoint mode that includes the Docker daemon's host, or kubectl fails TLS verification.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AksClusterManager — k3s API certificate names")
class AksClusterManagerTlsSanTest {

    @Mock
    ContainerBuilder containerBuilder;

    @Mock
    ContainerLifecycleManager lifecycleManager;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    PortAllocator portAllocator;

    @Mock
    EmulatorConfig config;

    private AksClusterManager manager() {
        return new AksClusterManager(containerBuilder, lifecycleManager, containerDetector, portAllocator, config);
    }

    @Test
    @DisplayName("auto mode: localhost only, as before")
    void autoMode() {
        lenient().when(lifecycleManager.publishedEndpoints()).thenReturn(false);
        assertEquals(List.of("server", "--disable=traefik", "--tls-san=localhost"), manager().k3sServerArgs());
    }

    @Test
    @DisplayName("published mode: the daemon's host is added")
    void publishedMode() {
        lenient().when(lifecycleManager.publishedEndpoints()).thenReturn(true);
        lenient().when(lifecycleManager.daemonHost()).thenReturn("docker");
        assertEquals(List.of("server", "--disable=traefik", "--tls-san=localhost", "--tls-san=docker"),
                manager().k3sServerArgs());
    }

    @Test
    @DisplayName("published mode with a local daemon: no duplicate localhost")
    void publishedLocalDaemon() {
        lenient().when(lifecycleManager.publishedEndpoints()).thenReturn(true);
        lenient().when(lifecycleManager.daemonHost()).thenReturn("localhost");
        assertEquals(List.of("server", "--disable=traefik", "--tls-san=localhost"), manager().k3sServerArgs());
    }

    @Test
    @DisplayName("published mode, IPv6 daemon: the SAN is the bare address")
    void publishedIpv6() {
        lenient().when(lifecycleManager.publishedEndpoints()).thenReturn(true);
        lenient().when(lifecycleManager.daemonHost()).thenReturn("[::1]");
        assertEquals(List.of("server", "--disable=traefik", "--tls-san=localhost", "--tls-san=::1"),
                manager().k3sServerArgs());
    }
}
