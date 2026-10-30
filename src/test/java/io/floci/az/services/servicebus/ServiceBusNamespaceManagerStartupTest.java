package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.services.eventhub.ArtemisTlsGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceBusNamespaceManagerStartupTest {

    @ParameterizedTest
    @ValueSource(strings = {"output", "empty", "failure"})
    void failedDockerStartPreservesDiagnosticsBeforeCleanup(String diagnostics) throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        ServiceBusConfigGenerator configGenerator = mock(ServiceBusConfigGenerator.class);
        ArtemisTlsGenerator tlsGenerator = mock(ArtemisTlsGenerator.class);
        ContainerSpec spec = new ContainerSpec("artemis");

        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(serviceBus.artemisImage()).thenReturn("artemis");
        when(configGenerator.generate("failed")).thenReturn("<configuration/>");
        when(tlsGenerator.generate("floci-az-servicebus-failed"))
                .thenReturn(new ArtemisTlsGenerator.TlsBundle(new byte[0], "certificate"));
        when(containerBuilder.newContainer("artemis")).thenReturn(builder);
        when(builder.withName(anyString())).thenReturn(builder);
        when(builder.withEnv(anyString(), anyString())).thenReturn(builder);
        when(builder.withLabels(anyMap())).thenReturn(builder);
        when(builder.withPortBinding(anyInt(), anyInt())).thenReturn(builder);
        when(builder.withDynamicPort(anyInt())).thenReturn(builder);
        when(builder.withDockerNetwork(any())).thenReturn(builder);
        when(builder.withLogRotation()).thenReturn(builder);
        when(builder.build()).thenReturn(spec);
        when(lifecycleManager.create(spec)).thenReturn("container-id");
        IllegalStateException startupFailure = new IllegalStateException("readiness failed");
        when(lifecycleManager.startCreated("container-id", spec)).thenThrow(startupFailure);
        String brokerOutput = "2026-10-27T12:00:00Z FATAL journal unavailable";
        if (diagnostics.equals("failure")) {
            when(lifecycleManager.logs("container-id", 200, true))
                    .thenThrow(new IllegalStateException("logs unavailable"));
        } else {
            when(lifecycleManager.logs("container-id", 200, true))
                    .thenReturn(diagnostics.equals("empty") ? "" : brokerOutput);
        }
        when(lifecycleManager.removeIfExistsAndConfirm("floci-az-servicebus-failed"))
                .thenReturn(true);
        when(lifecycleManager.removeIfExistsAndConfirm("container-id")).thenReturn(true);

        ServiceBusNamespaceManager manager = new ServiceBusNamespaceManager(
                config, containerBuilder, lifecycleManager, configGenerator, tlsGenerator);

        Logger logger = Logger.getLogger(ServiceBusNamespaceManager.class.getName());
        CapturedLogs logs = new CapturedLogs();
        logger.addHandler(logs);
        try {
            ServiceBusNamespaceManager.NamespaceStartException error = assertThrows(
                    ServiceBusNamespaceManager.NamespaceStartException.class,
                    () -> manager.startNamespace("failed", 5672, 5671));

            String expected = switch (diagnostics) {
                case "failure" -> "logs unavailable";
                case "empty" -> "(no output available)";
                default -> brokerOutput;
            };
            assertTrue(logs.messages.stream().anyMatch(message -> message.contains(expected)));
            InOrder cleanup = inOrder(lifecycleManager);
            cleanup.verify(lifecycleManager).logs("container-id", 200, true);
            cleanup.verify(lifecycleManager).stopAndRemove("container-id", null);
            cleanup.verify(lifecycleManager).removeIfExistsAndConfirm("container-id");
            assertSame(startupFailure, error.getCause());
            assertFalse(error.portsReleased());
            assertTrue(manager.listNamespaces().isEmpty());
        } finally {
            logger.removeHandler(logs);
        }
    }

    @Test
    void cleanedPreStartFailureReportsPortsReleased() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        ArtemisTlsGenerator tlsGenerator = mock(ArtemisTlsGenerator.class);
        String containerName = "floci-az-servicebus-failed";
        when(lifecycleManager.removeIfExistsAndConfirm(containerName)).thenReturn(true);
        when(tlsGenerator.generate(containerName))
                .thenThrow(new IllegalStateException("certificate unavailable"));
        ServiceBusNamespaceManager manager = new ServiceBusNamespaceManager(
                config, mock(ContainerBuilder.class), lifecycleManager,
                mock(ServiceBusConfigGenerator.class), tlsGenerator);

        ServiceBusNamespaceManager.NamespaceStartException error = assertThrows(
                ServiceBusNamespaceManager.NamespaceStartException.class,
                () -> manager.startNamespace("failed", 5672, 5671));

        assertTrue(error.portsReleased());
        verify(lifecycleManager, times(2)).removeIfExistsAndConfirm(containerName);
    }

    @Test
    void failedContainerCreationDoesNotReportConfiguredPortsReleased() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        ServiceBusConfigGenerator configGenerator = mock(ServiceBusConfigGenerator.class);
        ArtemisTlsGenerator tlsGenerator = mock(ArtemisTlsGenerator.class);
        ContainerSpec spec = new ContainerSpec("artemis");
        String containerName = "floci-az-servicebus-failed";

        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(serviceBus.artemisImage()).thenReturn("artemis");
        when(configGenerator.generate("failed")).thenReturn("<configuration/>");
        when(tlsGenerator.generate(containerName))
                .thenReturn(new ArtemisTlsGenerator.TlsBundle(new byte[0], "certificate"));
        when(containerBuilder.newContainer("artemis")).thenReturn(builder);
        when(builder.withName(anyString())).thenReturn(builder);
        when(builder.withEnv(anyString(), anyString())).thenReturn(builder);
        when(builder.withLabels(anyMap())).thenReturn(builder);
        when(builder.withPortBinding(anyInt(), anyInt())).thenReturn(builder);
        when(builder.withDynamicPort(anyInt())).thenReturn(builder);
        when(builder.withDockerNetwork(any())).thenReturn(builder);
        when(builder.withLogRotation()).thenReturn(builder);
        when(builder.build()).thenReturn(spec);
        when(lifecycleManager.create(spec))
                .thenThrow(new IllegalStateException("configured port is already allocated"));
        when(lifecycleManager.removeIfExistsAndConfirm(containerName)).thenReturn(true);

        ServiceBusNamespaceManager manager = new ServiceBusNamespaceManager(
                config, containerBuilder, lifecycleManager, configGenerator, tlsGenerator);

        ServiceBusNamespaceManager.NamespaceStartException error = assertThrows(
                ServiceBusNamespaceManager.NamespaceStartException.class,
                () -> manager.startNamespace("failed", 5672, 5671));

        assertFalse(error.portsReleased());
        verify(lifecycleManager, times(2)).removeIfExistsAndConfirm(containerName);
    }

    @Test
    void endpointResolutionFailureReportsReleasedPortsAfterCleanup() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        ServiceBusConfigGenerator configGenerator = mock(ServiceBusConfigGenerator.class);
        ArtemisTlsGenerator tlsGenerator = mock(ArtemisTlsGenerator.class);
        ContainerSpec spec = new ContainerSpec("artemis");

        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(serviceBus.artemisImage()).thenReturn("artemis");
        when(configGenerator.generate("failed")).thenReturn("<configuration/>");
        when(tlsGenerator.generate("floci-az-servicebus-failed"))
                .thenReturn(new ArtemisTlsGenerator.TlsBundle(new byte[0], "certificate"));
        when(containerBuilder.newContainer("artemis")).thenReturn(builder);
        when(builder.withName(anyString())).thenReturn(builder);
        when(builder.withEnv(anyString(), anyString())).thenReturn(builder);
        when(builder.withLabels(anyMap())).thenReturn(builder);
        when(builder.withPortBinding(anyInt(), anyInt())).thenReturn(builder);
        when(builder.withDynamicPort(anyInt())).thenReturn(builder);
        when(builder.withDockerNetwork(any())).thenReturn(builder);
        when(builder.withLogRotation()).thenReturn(builder);
        when(builder.build()).thenReturn(spec);
        when(lifecycleManager.create(spec)).thenReturn("container-id");
        when(lifecycleManager.logs("container-id", 200, true)).thenReturn("");
        when(lifecycleManager.startCreated("container-id", spec))
                .thenThrow(new IllegalStateException("endpoint resolution failed"));
        when(lifecycleManager.isContainerRunning("container-id")).thenReturn(true);
        when(lifecycleManager.removeIfExistsAndConfirm("floci-az-servicebus-failed"))
                .thenReturn(true);
        when(lifecycleManager.removeIfExistsAndConfirm("container-id")).thenReturn(true);

        ServiceBusNamespaceManager manager = new ServiceBusNamespaceManager(
                config, containerBuilder, lifecycleManager, configGenerator, tlsGenerator);

        ServiceBusNamespaceManager.NamespaceStartException error = assertThrows(
                ServiceBusNamespaceManager.NamespaceStartException.class,
                () -> manager.startNamespace("failed", 5672, 5671));

        assertTrue(error.portsReleased());
        verify(lifecycleManager).stopAndRemove("container-id", null);
    }

    @Test
    void aStartedNamespaceKeepsTheConnectPortsApartFromThePublishedHostPorts() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        ServiceBusConfigGenerator configGenerator = mock(ServiceBusConfigGenerator.class);
        ArtemisTlsGenerator tlsGenerator = mock(ArtemisTlsGenerator.class);
        ContainerSpec spec = new ContainerSpec("artemis");

        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(serviceBus.artemisImage()).thenReturn("artemis");
        when(configGenerator.generate("default")).thenReturn("<configuration/>");
        when(tlsGenerator.generate("floci-az-servicebus-default"))
                .thenReturn(new ArtemisTlsGenerator.TlsBundle(new byte[0], "certificate"));
        when(containerBuilder.newContainer("artemis")).thenReturn(builder);
        when(builder.withName(anyString())).thenReturn(builder);
        when(builder.withEnv(anyString(), anyString())).thenReturn(builder);
        when(builder.withLabels(anyMap())).thenReturn(builder);
        when(builder.withPortBinding(anyInt(), anyInt())).thenReturn(builder);
        when(builder.withDynamicPort(anyInt())).thenReturn(builder);
        when(builder.withDockerNetwork(any())).thenReturn(builder);
        when(builder.withLogRotation()).thenReturn(builder);
        when(builder.build()).thenReturn(spec);
        when(lifecycleManager.create(spec)).thenReturn("container-id");
        when(lifecycleManager.removeIfExistsAndConfirm("floci-az-servicebus-default"))
                .thenReturn(true);

        try (ServerSocket broker = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            // As floci-az resolves the sidecar when it runs in a container: the endpoint carries
            // the container port, and Docker published the broker on other host ports.
            ContainerLifecycleManager.EndpointInfo resolved =
                    new ContainerLifecycleManager.EndpointInfo("127.0.0.1", broker.getLocalPort());
            when(lifecycleManager.startCreated("container-id", spec))
                    .thenReturn(new ContainerLifecycleManager.ContainerInfo("container-id",
                            Map.of(5672, resolved, 5671, resolved, 8161, resolved),
                            Map.of(5672, 49175, 5671, 49176, 8161, 49177)));
            ServiceBusNamespaceManager manager = new ServiceBusNamespaceManager(
                    config, containerBuilder, lifecycleManager, configGenerator, tlsGenerator);

            try {
                ServiceBusNamespaceManager.NamespaceState state = manager.startNamespace("default", 0, 0);

                assertEquals(broker.getLocalPort(), state.amqpPort());
                assertEquals(broker.getLocalPort(), state.amqpsPort());
                assertEquals(49175, state.amqpHostPort());
                assertEquals(49176, state.amqpsHostPort());
            } finally {
                manager.stopNamespace("default");
            }
        }
    }

    @Test
    void recordedStartFailureIsAProblemUntilTheNamespaceStarts() {
        ServiceBusNamespaceManager manager = new ServiceBusNamespaceManager(
                mock(EmulatorConfig.class), mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class), mock(ServiceBusConfigGenerator.class),
                mock(ArtemisTlsGenerator.class));

        manager.recordStartFailure("default", new IllegalStateException("outer",
                new IllegalStateException("Bind for 0.0.0.0:5673 failed: port is already allocated")));

        assertEquals(Map.of("servicebus/default",
                        "Service Bus namespace 'default' failed to start: IllegalStateException: "
                                + "Bind for 0.0.0.0:5673 failed: port is already allocated"),
                manager.problems());

        manager.startMockedNamespace("default");

        assertTrue(manager.problems().isEmpty());
    }

    @Test
    void everyBrokerFileIsUploadedInOneArchiveBeforeStart() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServiceBusConfig serviceBus = mock(EmulatorConfig.ServiceBusConfig.class);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        ServiceBusConfigGenerator configGenerator = mock(ServiceBusConfigGenerator.class);
        ArtemisTlsGenerator tlsGenerator = mock(ArtemisTlsGenerator.class);
        ContainerSpec spec = new ContainerSpec("artemis");

        when(config.services()).thenReturn(services);
        when(services.serviceBus()).thenReturn(serviceBus);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(serviceBus.artemisImage()).thenReturn("artemis");
        when(configGenerator.generate("upload")).thenReturn("<configuration/>");
        when(tlsGenerator.generate("floci-az-servicebus-upload"))
                .thenReturn(new ArtemisTlsGenerator.TlsBundle(new byte[0], "certificate"));
        when(containerBuilder.newContainer("artemis")).thenReturn(builder);
        when(builder.withName(anyString())).thenReturn(builder);
        when(builder.withEnv(anyString(), anyString())).thenReturn(builder);
        when(builder.withLabels(anyMap())).thenReturn(builder);
        when(builder.withPortBinding(anyInt(), anyInt())).thenReturn(builder);
        when(builder.withDynamicPort(anyInt())).thenReturn(builder);
        when(builder.withDockerNetwork(any())).thenReturn(builder);
        when(builder.withLogRotation()).thenReturn(builder);
        when(builder.build()).thenReturn(spec);
        when(lifecycleManager.create(spec)).thenReturn("container-id");
        when(lifecycleManager.startCreated("container-id", spec))
                .thenThrow(new IllegalStateException("stop after the upload"));
        when(lifecycleManager.logs("container-id", 200, true)).thenReturn("");
        when(lifecycleManager.removeIfExistsAndConfirm(anyString())).thenReturn(true);

        ServiceBusNamespaceManager manager = new ServiceBusNamespaceManager(
                config, containerBuilder, lifecycleManager, configGenerator, tlsGenerator);
        assertThrows(ServiceBusNamespaceManager.NamespaceStartException.class,
                () -> manager.startNamespace("upload", 5672, 5671));

        // Podman hides a file uploaded into the instance volume from the second upload on (#472).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, byte[]>> files = ArgumentCaptor.forClass(Map.class);
        InOrder order = inOrder(lifecycleManager);
        order.verify(lifecycleManager).copyFilesToContainer(eq("container-id"), files.capture());
        order.verify(lifecycleManager).startCreated("container-id", spec);
        verify(lifecycleManager, never()).copyBytesToContainer(anyString(), any(), anyString());
        verify(lifecycleManager, never()).copyFileToContainer(anyString(), anyString(), anyString());
        assertEquals(Set.of(
                "/var/lib/artemis-instance/etc-override/broker.xml",
                "/var/lib/artemis-instance/etc-override/management.xml",
                "/var/lib/artemis-instance/etc-override/artemis.p12",
                "/var/lib/artemis-instance/lib/floci-az-servicebus-extension.jar",
                ArtemisPatchVersions.PROTON_J_CONTAINER_PATH,
                ArtemisPatchVersions.ARTEMIS_AMQP_CONTAINER_PATH),
                files.getValue().keySet());
    }

    private static final class CapturedLogs extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            messages.add(new SimpleFormatter().formatMessage(record));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
