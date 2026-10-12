package io.floci.az.core.docker;

import io.floci.az.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DockerNetworkAdvisorTest {

    private EmulatorConfig config;
    private ContainerDetector containerDetector;
    private CurrentContainerNetworkResolver networkResolver;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().endpointMode()).thenReturn(EmulatorConfig.DockerEndpointMode.AUTO);
        when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        containerDetector = mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        networkResolver = mock(CurrentContainerNetworkResolver.class);
    }

    private Optional<String> advice() {
        return new DockerNetworkAdvisor(config, containerDetector, networkResolver)
                .sidecarNetworkWithoutNameResolution();
    }

    @Test
    void detectedDefaultBridgeIsReported() {
        when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("bridge"));
        assertEquals(Optional.of("bridge"), advice());
    }

    @Test
    void configuredDefaultBridgeIsReported() {
        when(config.services().dockerNetwork()).thenReturn(Optional.of("bridge"));
        assertEquals(Optional.of("bridge"), advice());
        verify(networkResolver, never()).resolveNetworkName();
    }

    @Test
    void detectedUserDefinedNetworkIsFine() {
        when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("app-net"));
        assertEquals(Optional.empty(), advice());
    }

    @Test
    void configuredUserDefinedNetworkIsFine() {
        when(config.services().dockerNetwork()).thenReturn(Optional.of("app-net"));
        assertEquals(Optional.empty(), advice());
    }

    @Test
    void flociOnTheHostIsNeverWarned() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        assertEquals(Optional.empty(), advice());
        verify(networkResolver, never()).resolveNetworkName();
    }

    @Test
    void publishedModeIsNeverWarned() {
        when(config.docker().endpointMode()).thenReturn(EmulatorConfig.DockerEndpointMode.PUBLISHED);
        when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("bridge"));
        assertEquals(Optional.empty(), advice());
    }

    @Test
    void anUndetectableNetworkIsNotWarned() {
        when(networkResolver.resolveNetworkName()).thenReturn(Optional.empty());
        assertEquals(Optional.empty(), advice());
    }
}
