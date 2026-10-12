package io.floci.az.services.cosmos.engine;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.services.cosmos.engine.impl.MongoDbCosmosEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The connection info a Cosmos engine reports must point at the Docker daemon's host in published
 * endpoint mode, where a remote daemon's engine is not on localhost, and stay on localhost in auto mode.
 */
class CosmosLifecycleManagerEndpointTest {

    private static final int PUBLISHED_PORT = 32768;

    private EmulatorConfig config;
    private CosmosEngineRegistry registry;
    private ContainerLifecycleManager containerManager;
    private ContainerBuilder containerBuilder;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cosmos().mocked()).thenReturn(false);
        when(config.services().cosmos().engines().mongodb().enabled()).thenReturn(true);
        when(config.services().cosmos().engines().mongodb().image()).thenReturn(Optional.empty());
        when(config.services().cosmos().engines().mongodb().port()).thenReturn(Optional.empty());
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());

        registry = mock(CosmosEngineRegistry.class);
        when(registry.resolve(CosmosApi.MONGODB)).thenReturn(Optional.of(new MongoDbCosmosEngine()));

        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(new ContainerSpec("mongo:test"));
        containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(any())).thenReturn(builder);

        containerManager = mock(ContainerLifecycleManager.class);
    }

    @Test
    void publishedModeReportsTheDaemonHostAndPublishedPort() {
        when(containerManager.publishedEndpoints()).thenReturn(true);
        startedAt("docker", PUBLISHED_PORT);

        CosmosConnectionInfo info = start();

        assertEquals("docker", info.host());
        assertEquals(PUBLISHED_PORT, info.port());
        assertEquals("mongodb://docker:" + PUBLISHED_PORT + "/", info.connectionString());
    }

    @Test
    void autoModeKeepsReportingLocalhost() {
        when(containerManager.publishedEndpoints()).thenReturn(false);
        startedAt("localhost", 27017);

        CosmosConnectionInfo info = start();

        assertEquals("localhost", info.host());
        assertEquals(27017, info.port());
    }

    private void startedAt(String host, int port) {
        when(containerManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                "mongo-id", Map.of(27017, new ContainerLifecycleManager.EndpointInfo(host, port))));
    }

    private CosmosConnectionInfo start() {
        return new CosmosLifecycleManager(config, registry, containerManager, containerBuilder)
                .getOrStart(CosmosApi.MONGODB).orElseThrow();
    }
}
