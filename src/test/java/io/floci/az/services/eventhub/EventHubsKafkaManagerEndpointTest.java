package io.floci.az.services.eventhub;

import com.sun.net.httpserver.HttpServer;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka clients connect to the address Redpanda advertises, so in published endpoint mode it must
 * be the Docker daemon's host; and floci-az's readiness poll must use the admin endpoint it
 * resolved for the sidecar rather than assuming localhost.
 */
class EventHubsKafkaManagerEndpointTest {

    private static final int KAFKA_HOST_PORT = 9093;

    private HttpServer admin;
    private ContainerBuilder.Builder builder;
    private ContainerBuilder containerBuilder;
    private ContainerLifecycleManager lifecycleManager;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() throws IOException {
        admin = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        admin.createContext("/ready", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        admin.start();

        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(new ContainerSpec("redpanda:test"));
        containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(any())).thenReturn(builder);

        lifecycleManager = mock(ContainerLifecycleManager.class);
        // The admin API as resolved for the sidecar: not localhost, as in a container or published mode.
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                "redpanda-id", Map.of(9644, new ContainerLifecycleManager.EndpointInfo(
                        "127.0.0.1", admin.getAddress().getPort()))));

        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().eventHub().kafkaPort()).thenReturn(KAFKA_HOST_PORT);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        admin.stop(0);
    }

    @Test
    void publishedModeAdvertisesTheDaemonHost() {
        when(lifecycleManager.publishedEndpoints()).thenReturn(true);
        when(lifecycleManager.daemonHost()).thenReturn("docker");

        assertEquals("docker:" + KAFKA_HOST_PORT, advertisedAddressAfterStart());
    }

    @Test
    void autoModeKeepsAdvertisingLocalhost() {
        when(lifecycleManager.publishedEndpoints()).thenReturn(false);

        assertEquals("localhost:" + KAFKA_HOST_PORT, advertisedAddressAfterStart());
    }

    @SuppressWarnings("unchecked")
    private String advertisedAddressAfterStart() {
        new EventHubsKafkaManager(config, containerBuilder, lifecycleManager).start();

        ArgumentCaptor<List<String>> cmd = ArgumentCaptor.forClass(List.class);
        verify(builder).withCmd(cmd.capture());
        List<String> args = cmd.getValue();
        return args.get(args.indexOf("--advertise-kafka-addr") + 1);
    }
}
