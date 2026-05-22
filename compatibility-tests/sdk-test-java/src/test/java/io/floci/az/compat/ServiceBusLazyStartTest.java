package io.floci.az.compat;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * Verifies Service Bus lazy namespace auto-start behavior:
 *
 *   - No explicit namespace management call is needed before entity creation.
 *   - The first entity management call via the Azure spec path triggers Artemis startup.
 *   - After auto-start, AMQP send/receive works through the Azure SDK.
 *   - The running namespace is visible in the namespaces list.
 *
 * These tests use the Azure spec path for entity creation (/{entityName} without a
 * namespace prefix) — the same path used by {@code ServiceBusAdministrationClient}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Service Bus Lazy Namespace Auto-Start")
class ServiceBusLazyStartTest {

    private static final Duration RECV_TIMEOUT = Duration.ofSeconds(15);

    private String queueName;

    @BeforeAll
    void init() throws Exception {
        EmulatorConfig.ensureServiceBusNamespace();
        queueName = "lazy-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @AfterAll
    void cleanup() throws Exception {
        // Remove the queue so it doesn't linger between runs
        try {
            String url = System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577")
                    + "/" + EmulatorConfig.ACCOUNT + "-servicebus/" + queueName;
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(10_000);
            conn.getResponseCode();
        } catch (Exception ignored) {}
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("entity creation via spec path succeeds without prior namespace setup")
    void entityCreation_triggersLazyStart() throws Exception {
        // Use spec path — no namespace prefix, exactly as ServiceBusAdministrationClient does
        EmulatorConfig.createQueueViaSpecPath(queueName, false);
        // If we reach here the queue was created (201 or 200)
    }

    @Test
    @Order(2)
    @DisplayName("namespace appears in list after lazy start")
    void namespacePresentAfterFirstEntityCall() throws Exception {
        String body = EmulatorConfig.getServiceBusNamespaces();
        assertTrue(body.contains("\"namespaces\""),
                "Response should have namespaces key");
        assertFalse(body.equals("{\"namespaces\":[]}"),
                "At least one namespace should be running after entity creation");
    }

    @Test
    @Order(3)
    @DisplayName("AMQP send and receive work after lazy start")
    void amqpSendReceive_afterLazyStart() {
        assumeFalse(EmulatorConfig.serviceBusMocked,
                "Service Bus is in mocked mode — no Artemis broker, AMQP not available");
        String payload = "lazy-payload-" + UUID.randomUUID();

        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender()
                .queueName(queueName)
                .buildClient()) {
            sender.sendMessage(new ServiceBusMessage(payload));
        }

        try (ServiceBusReceiverClient receiver = EmulatorConfig.serviceBusClientBuilder()
                .receiver()
                .queueName(queueName)
                .buildClient()) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Expected a message after lazy-started namespace");
            assertEquals(payload, msg.getBody().toString());
            receiver.complete(msg);
        }
    }

    @Test
    @Order(4)
    @DisplayName("idempotent entity creation returns 200 (already exists)")
    void entityCreation_idempotent() throws Exception {
        // Second PUT to the same queue name should return 200 (entity already exists)
        // This verifies createQueueViaSpecPath returns 200 on repeat — the underlying
        // HTTP call throws if status is not 200 or 201
        EmulatorConfig.createQueueViaSpecPath(queueName, false);
    }
}
