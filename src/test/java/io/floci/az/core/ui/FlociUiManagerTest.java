package io.floci.az.core.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerPresence;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.DockerHostResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FlociUiManagerTest {

    private static final String IMAGE = "floci/floci-ui:0.7.0";
    /** Nothing listens on port 1, so a probe there is refused at once. */
    private static final int REFUSED_PORT = 1;

    private EmulatorConfig config;
    private ContainerBuilder containerBuilder;
    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private ContainerDetector containerDetector;
    private DockerHostResolver dockerHostResolver;
    private HttpServer console;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.ui().enabled()).thenReturn(true);
        when(config.ui().image()).thenReturn(IMAGE);
        when(config.ui().port()).thenReturn(REFUSED_PORT);
        when(config.ui().internalPort()).thenReturn(4500);
        when(config.ui().endpoint()).thenReturn(Optional.empty());
        when(config.ui().keepRunningOnShutdown()).thenReturn(false);
        when(config.hostname()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4577);
        when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        when(config.services().arm().defaultSubscriptionId()).thenReturn("00000000-0000-0000-0000-000000000001");
        when(config.services().entra().defaultTenantId()).thenReturn("00000000-0000-0000-0000-000000000002");
        when(config.cloud().defaultCloud()).thenReturn("AzureCloud");
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());

        containerBuilder = mock(ContainerBuilder.class);
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec(IMAGE));

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("console-id", Map.of(), Map.of()));
        when(lifecycleManager.presence("console-id")).thenReturn(ContainerPresence.RUNNING);

        containerDetector = mock(ContainerDetector.class);
        dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");
    }

    @AfterEach
    void tearDown() {
        if (console != null) {
            console.stop(0);
        }
    }

    private FlociUiManager manager(Executor executor) {
        return new FlociUiManager(containerBuilder, lifecycleManager, containerDetector, dockerHostResolver,
                config, new ObjectMapper(), executor);
    }

    private FlociUiManager manager() {
        return manager(Runnable::run);
    }

    @Test
    void theStaleContainerIsRemovedBeforeTheConsoleIsCreated() {
        assertTrue(manager().ensureStarted());

        InOrder order = inOrder(lifecycleManager);
        order.verify(lifecycleManager).ensureImageAvailable(IMAGE);
        order.verify(lifecycleManager).removeIfExists("floci-az-ui");
        order.verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void theInternalPortIsPublishedOnTheConfiguredHostPortOnFlociAzsNetwork() {
        when(config.ui().port()).thenReturn(4510);

        manager().ensureStarted();

        verify(builder).withName("floci-az-ui");
        verify(builder).withPortBinding(4500, 4510);
        verify(builder).withDockerNetwork(Optional.empty());
        verify(builder).withLogRotation();
    }

    @Test
    void onTheHostTheConsoleReachesFlociAzThroughDockersHostAlias() {
        manager().ensureStarted();

        verify(builder).withEnv("FLOCI_AZURE_ENDPOINT", "http://host.docker.internal:4577");
        verify(builder).withEnv("FLOCI_INFO_URL", "http://host.docker.internal:4577/_floci/info");
        verify(builder).withEnv("FLOCI_AZURE_SUBSCRIPTION_ID", "00000000-0000-0000-0000-000000000001");
        verify(builder).withEnv("PORT", "4500");
        verify(builder).withHostDockerInternalOnLinux();
    }

    @Test
    void inAContainerWithAHostnameTheConsoleUsesTheBaseUrl() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.hostname()).thenReturn(Optional.of("floci-az"));
        when(config.effectiveBaseUrl()).thenReturn("http://floci-az:4577");

        manager().ensureStarted();

        verify(builder).withEnv("FLOCI_AZURE_ENDPOINT", "http://floci-az:4577");
        verify(builder, never()).withHostDockerInternalOnLinux();
    }

    @Test
    void inAContainerWithoutAHostnameTheConsoleUsesFlociAzsAddress() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(dockerHostResolver.resolve()).thenReturn("172.18.0.5");

        manager().ensureStarted();

        verify(builder).withEnv("FLOCI_AZURE_ENDPOINT", "http://172.18.0.5:4577");
    }

    @Test
    void theConfiguredEndpointWins() {
        when(config.ui().endpoint()).thenReturn(Optional.of(" http://emulator.example:4577 "));

        manager().ensureStarted();

        verify(builder).withEnv("FLOCI_AZURE_ENDPOINT", "http://emulator.example:4577");
    }

    @Test
    void flociEndpointIsNotGivenToTheFlociUiImage() {
        manager().ensureStarted();

        verify(builder, never()).withEnv(eq("FLOCI_ENDPOINT"), anyString());
        verify(builder).withEnv("FLOCI_CLOUD", "az");
    }

    @Test
    void aContractConsoleIsGivenFlociEndpoint() {
        when(config.ui().image()).thenReturn("acme/console:1.0");

        manager().ensureStarted();

        verify(builder).withEnv("FLOCI_ENDPOINT", "http://host.docker.internal:4577");
    }

    @Test
    void aFailedStartIsNonFatalAndReportedInTheStatus() {
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("image pull denied"));
        FlociUiManager manager = manager();

        assertFalse(manager.ensureStarted());

        FlociUiManager.UiStatus status = manager.status();
        assertTrue(status.enabled());
        assertFalse(status.started());
        assertEquals(0, status.hostPort());
        assertNotNull(status.error());
        assertTrue(status.error().contains("image pull denied"), status.error());
    }

    @Test
    void aPortConflictNamesTheSettingThatMovesTheConsole() {
        when(config.ui().port()).thenReturn(4500);
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException(
                "Bind for 0.0.0.0:4500 failed: port is already allocated"));
        FlociUiManager manager = manager();

        manager.ensureStarted();

        String error = manager.status().error();
        assertTrue(error.contains("FLOCI_AZ_UI_PORT"), error);
        assertTrue(error.contains("4500"), error);
    }

    @Test
    void startsRequestedWhileOneIsPendingAreDeduplicated() {
        List<Runnable> queued = new ArrayList<>();
        FlociUiManager manager = manager(queued::add);

        manager.ensureStartedAsync();
        manager.ensureStartedAsync();

        assertEquals(1, queued.size());
        queued.get(0).run();
        verify(lifecycleManager, times(1)).createAndStart(any());
    }

    @Test
    void aFailedStartReArmsSoTheNextRequestTriesAgain() {
        when(lifecycleManager.createAndStart(any()))
                .thenThrow(new RuntimeException("daemon unreachable"))
                .thenReturn(new ContainerInfo("console-id", Map.of(), Map.of()));
        FlociUiManager manager = manager();

        manager.ensureStartedAsync();
        manager.ensureStartedAsync();

        verify(lifecycleManager, times(2)).createAndStart(any());
        assertTrue(manager.status().started());
    }

    @Test
    void aStartedConsoleIsNotStartedAgain() {
        FlociUiManager manager = manager();

        manager.ensureStartedAsync();
        manager.ensureStartedAsync();

        verify(lifecycleManager, times(1)).createAndStart(any());
    }

    @Test
    void aDisabledConsoleNeverTouchesDocker() {
        when(config.ui().enabled()).thenReturn(false);
        FlociUiManager manager = manager();

        manager.ensureStartedAsync();
        FlociUiManager.UiStatus status = manager.status();

        assertFalse(status.enabled());
        assertTrue(status.error().contains("FLOCI_AZ_UI_ENABLED"), status.error());
        verifyNoInteractions(lifecycleManager, containerBuilder);
    }

    @Test
    void beforeAnyStartTheStatusIsNotReadyWithoutAnError() {
        FlociUiManager.UiStatus status = manager().status();

        assertTrue(status.enabled());
        assertFalse(status.started());
        assertFalse(status.ready());
        assertNull(status.error());
    }

    @Test
    void aConsoleWhoseContainerIsGoneIsStartedAgain() {
        when(lifecycleManager.presence("console-id")).thenReturn(ContainerPresence.ABSENT);
        FlociUiManager manager = manager();
        manager.ensureStarted();

        FlociUiManager.UiStatus status = manager.status();

        assertFalse(status.ready());
        verify(lifecycleManager, times(2)).createAndStart(any());
    }

    @Test
    void aStoppedConsoleIsStartedAgain() {
        when(lifecycleManager.presence("console-id")).thenReturn(ContainerPresence.STOPPED);
        FlociUiManager manager = manager();
        manager.ensureStarted();

        manager.status();

        verify(lifecycleManager, times(2)).createAndStart(any());
    }

    @Test
    void aConsoleDockerCannotInspectIsLeftAlone() {
        when(lifecycleManager.presence("console-id")).thenReturn(ContainerPresence.UNKNOWN);
        FlociUiManager manager = manager();
        manager.ensureStarted();

        FlociUiManager.UiStatus status = manager.status();

        assertTrue(status.started());
        assertFalse(status.ready());
        assertEquals(REFUSED_PORT, status.hostPort());
        verify(lifecycleManager, times(1)).createAndStart(any());
    }

    @Test
    void aConsoleThatReachesFlociAzIsReady() throws IOException {
        serveConsole("/api/clouds/azure/status", "{\"runtime\":\"reachable\",\"error\":null}");
        FlociUiManager manager = manager();
        manager.ensureStarted();

        FlociUiManager.UiStatus status = manager.status();

        assertTrue(status.ready(), String.valueOf(status));
        assertNull(status.error());
    }

    @Test
    void aConsoleThatCannotReachFlociAzSaysWhy() throws IOException {
        serveConsole("/api/clouds/azure/status", "{\"runtime\":\"unavailable\",\"error\":\"connect refused\"}");
        FlociUiManager manager = manager();
        manager.ensureStarted();

        FlociUiManager.UiStatus status = manager.status();

        assertFalse(status.ready());
        assertTrue(status.started());
        assertEquals("connect refused", status.error());
        assertEquals(console.getAddress().getPort(), status.hostPort());
        assertEquals("/console/azure", status.landingPath());
    }

    @Test
    void aConsoleStillComingUpIsNotReadyAndNotAnError() throws IOException {
        serveConsole("/api/clouds/azure/status", "{\"runtime\":\"checking\"}");
        FlociUiManager manager = manager();
        manager.ensureStarted();

        FlociUiManager.UiStatus status = manager.status();

        assertFalse(status.ready());
        assertNull(status.error());
        verify(lifecycleManager, never()).presence(anyString());
    }

    @Test
    void aContractConsoleIsProbedOnApiHealth() throws IOException {
        when(config.ui().image()).thenReturn("acme/console:1.0");
        serveConsole("/api/health", "{\"status\":\"ok\"}");
        FlociUiManager manager = manager();
        manager.ensureStarted();

        assertTrue(manager.status().ready());
    }

    @Test
    void shutdownStopsTheConsole() {
        FlociUiManager manager = manager();
        manager.ensureStarted();

        manager.shutdown();

        verify(lifecycleManager).stopAndRemove("console-id", null);
    }

    @Test
    void shutdownLeavesAConsoleConfiguredToOutliveFlociAz() {
        when(config.ui().keepRunningOnShutdown()).thenReturn(true);
        FlociUiManager manager = manager();
        manager.ensureStarted();

        manager.shutdown();

        verify(lifecycleManager, never()).stopAndRemove(anyString(), any());
    }

    /** A console on an ephemeral port on every interface (the probe dials localhost), published as the host port. */
    private void serveConsole(String path, String body) throws IOException {
        console = HttpServer.create(new InetSocketAddress(0), 0);
        console.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        console.start();
        when(config.ui().port()).thenReturn(console.getAddress().getPort());
    }
}
