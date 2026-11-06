package io.floci.az.services.servicebus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.floci.az.core.StoredObject;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Starts a namespace through the real CDI wiring, with only Docker mocked and a fake Jolokia in
 * place of the broker, so a stored entity reaches the broker with no direct call to the observer.
 */
@QuarkusTest
@TestProfile(ServiceBusBrokerRestoreWiringTest.RealModeProfile.class)
@DisplayName("Service Bus: starting a broker restores stored entities")
class ServiceBusBrokerRestoreWiringTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.service-bus.mocked", "false");
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String NAMESPACE = "restore-wiring";
    private static final String QUEUE = "stored-queue";
    private static final String KEY = "sb/devstoreaccount1/" + NAMESPACE + "/queues/" + QUEUE;

    @InjectMock
    ContainerLifecycleManager lifecycleManager;

    @Inject
    ServiceBusNamespaceManager namespaceManager;

    @Inject
    StorageFactory storageFactory;

    @Test
    void aStoredQueueReachesTheBrokerWhenItsNamespaceStarts() throws Exception {
        StorageBackend<String, StoredObject> store = storageFactory.create("servicebus");
        store.put(KEY, new StoredObject(KEY, MAPPER.writeValueAsBytes(queue()), Map.of(), Instant.now(), KEY));
        List<String> jolokiaRequests = new CopyOnWriteArrayList<>();
        HttpServer jolokia = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        jolokia.createContext("/console/jolokia", exchange -> answer(exchange, jolokiaRequests));
        jolokia.start();

        // The listener only accepts connections; it is not a broker, so readiness is not under test.
        try (ServerSocket broker = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
             MockedStatic<ArtemisReadinessProbe> readiness = mockStatic(ArtemisReadinessProbe.class)) {
            ContainerLifecycleManager.EndpointInfo amqp =
                    new ContainerLifecycleManager.EndpointInfo("127.0.0.1", broker.getLocalPort());
            ContainerLifecycleManager.EndpointInfo management = new ContainerLifecycleManager.EndpointInfo(
                    "127.0.0.1", jolokia.getAddress().getPort());
            when(lifecycleManager.removeIfExistsAndConfirm(anyString())).thenReturn(true);
            when(lifecycleManager.create(any())).thenReturn("container-id");
            when(lifecycleManager.startCreated(anyString(), any()))
                    .thenReturn(new ContainerLifecycleManager.ContainerInfo("container-id",
                            Map.of(5672, amqp, 5671, amqp, 8161, management), Map.of()));

            namespaceManager.startNamespace(NAMESPACE, 0, 0);

            assertTrue(jolokiaRequests.stream().anyMatch(request -> request.contains(QUEUE)),
                    "No Jolokia request created the stored queue: " + jolokiaRequests);
        } finally {
            namespaceManager.stopNamespace(NAMESPACE);
            jolokia.stop(0);
            store.delete(KEY);
        }
    }

    private static ServiceBusModels.QueueEntity queue() {
        return ServiceBusModels.QueueEntity.defaults(
                QUEUE, 10, 60, false,
                new ServiceBusEntityXml.DuplicateDetectionSettings(false, 0),
                new ServiceBusEntityXml.MessageLifetimeSettings(
                        ServiceBusEntityXml.DEFAULT_MESSAGE_TTL_MILLIS, false));
    }

    private static void answer(HttpExchange exchange, List<String> requests) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!request.isEmpty()) {
            requests.add(request);
        }
        byte[] response = "{\"status\":200,\"value\":\"\"}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
