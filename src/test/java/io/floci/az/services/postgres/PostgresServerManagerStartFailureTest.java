package io.floci.az.services.postgres;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.PortAllocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A server whose container starts but never accepts a connection: the create fails, and the
 * container it started is removed rather than left running with nothing tracking it.
 */
class PostgresServerManagerStartFailureTest {

    private static final String CONTAINER_ID = "cid-start-failure";

    private ContainerLifecycleManager containers;
    private PostgresServerManager manager;

    @BeforeEach
    void setUp() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().postgres().image()).thenReturn("postgres:test");
        when(config.services().postgres().defaultPort()).thenReturn(0);
        when(config.services().postgres().startupTimeoutSeconds()).thenReturn(1);

        containers = mock(ContainerLifecycleManager.class);
        when(containers.createAndStart(any(ContainerSpec.class))).thenReturn(
                new ContainerLifecycleManager.ContainerInfo(CONTAINER_ID,
                        Map.of(5432, new ContainerLifecycleManager.EndpointInfo("127.0.0.1", closedPort))));

        PortAllocator ports = mock(PortAllocator.class);
        when(ports.claimOrZero(anyInt())).thenReturn(0);

        manager = new PostgresServerManager();
        manager.config = config;
        manager.containerManager = containers;
        manager.containerBuilder = mock(ContainerBuilder.class, RETURNS_DEEP_STUBS);
        manager.containerDetector = mock(ContainerDetector.class);
        manager.portAllocator = ports;
    }

    @Test
    void aServerThatNeverBecomesReadyHasItsContainerRemoved() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> manager.startServer(entry()));

        assertThat(failure.getMessage(), containsString("did not accept a connection"));
        verify(containers, times(1)).stopAndRemove(eq(CONTAINER_ID), isNull());
    }

    @Test
    void aFailedCleanupStillSurfacesTheStartupFailure() {
        doThrow(new IllegalStateException("daemon gone")).when(containers).stopAndRemove(eq(CONTAINER_ID), isNull());

        RuntimeException failure = assertThrows(RuntimeException.class, () -> manager.startServer(entry()));

        assertThat(failure.getMessage(), containsString("did not accept a connection"));
        assertThat(failure.getMessage(), not(containsString("daemon gone")));
    }

    private static PostgresState.ServerEntry entry() {
        return new PostgresState.ServerEntry(
                "pg-start-failure", "sub", "rg", "eastus", "16", "pgadmin", "Original_Strong123!",
                "Standard_B1ms", "Burstable", 32, null, 0, null,
                Map.of(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                Instant.now());
    }
}
