package io.floci.az.services.containerapps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.services.containerapps.ContainerAppsModels.ContainerAppState;
import io.floci.az.services.containerapps.ContainerAppsModels.RevisionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerAppRuntimeManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContainerBuilder containerBuilder;
    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private ContainerAppRuntimeManager runtimeManager;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dockerNetwork()).thenReturn(Optional.of("test-network"));
        when(config.services().containerApps().ingressTimeoutSeconds()).thenReturn(1);
        containerBuilder = mock(ContainerBuilder.class);
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(containerBuilder.newContainer(any())).thenReturn(builder);
        runtimeManager = new ContainerAppRuntimeManager(containerBuilder, lifecycleManager, config);
    }

    @Test
    void replicaContainersShareLeaderNetworkNamespace() throws Exception {
        ContainerSpec leaderSpec = new ContainerSpec("leader-image");
        ContainerSpec sidecarSpec = new ContainerSpec("sidecar-image");
        when(builder.build()).thenReturn(leaderSpec, sidecarSpec);
        when(lifecycleManager.createAndStart(leaderSpec))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("leader-id", Map.of()));
        when(lifecycleManager.createAndStart(sidecarSpec))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("sidecar-id", Map.of()));
        when(lifecycleManager.containerAddresses("leader-id")).thenReturn(List.of("172.18.0.9"));

        ContainerAppState app = app();
        RevisionState revision = revision("""
                {"containers":[
                  {"name":"main","image":"leader-image"},
                  {"name":"metrics","image":"sidecar-image"}
                ]}
                """);
        runtimeManager.startRevision(app, revision, MAPPER.readTree("{}"), 1, 0);

        verify(builder).withDockerNetwork(Optional.of("test-network"));
        verify(builder).withNetworkMode("container:leader-id");
        assertTrue(runtimeManager.isInternalCaller("172.18.0.9"));
        assertFalse(runtimeManager.isInternalCaller("172.18.0.10"));
        assertFalse(runtimeManager.isInternalCaller("192.168.1.9"));
    }

    @Test
    void rejectsCallerBeforeAnyManagedRuntimeStarts() {
        assertFalse(runtimeManager.isInternalCaller("172.18.0.4"));
    }

    @Test
    void endpointsRoundRobinAndSkipUnreachableReplicas() throws Exception {
        try (ServerSocket firstServer = new ServerSocket(0);
             ServerSocket secondServer = new ServerSocket(0)) {
            ContainerSpec firstSpec = new ContainerSpec("image");
            ContainerSpec secondSpec = new ContainerSpec("image-replica-2");
            when(builder.build()).thenReturn(firstSpec, secondSpec);
            var firstEndpoint = new ContainerLifecycleManager.EndpointInfo("localhost", firstServer.getLocalPort());
            var secondEndpoint = new ContainerLifecycleManager.EndpointInfo("localhost", secondServer.getLocalPort());
            when(lifecycleManager.createAndStart(firstSpec)).thenReturn(
                    new ContainerLifecycleManager.ContainerInfo("first-id", Map.of(8080, firstEndpoint)));
            when(lifecycleManager.createAndStart(secondSpec)).thenReturn(
                    new ContainerLifecycleManager.ContainerInfo("second-id", Map.of(8080, secondEndpoint)));

            ContainerAppState app = app();
            RevisionState revision = revision("{\"containers\":[{\"name\":\"web\",\"image\":\"image\"}]}");
            runtimeManager.startRevision(app, revision, MAPPER.readTree("{}"), 2, 8080);

            assertEquals(firstEndpoint, runtimeManager.endpoint(app, revision.getName()).orElseThrow());
            assertEquals(secondEndpoint, runtimeManager.endpoint(app, revision.getName()).orElseThrow());

            firstServer.close();
            assertEquals(secondEndpoint, runtimeManager.endpoint(app, revision.getName()).orElseThrow());
        }
    }

    private static ContainerAppState app() throws Exception {
        JsonNode document = MAPPER.readTree("{\"properties\":{}}");
        return new ContainerAppState("sub", "rg", "app", document, Instant.now());
    }

    private static RevisionState revision(String template) throws Exception {
        return new RevisionState("app--v1", MAPPER.readTree(template), false, 0, "app--v1.example");
    }
}
