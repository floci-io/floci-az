package io.floci.az.compat;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.ServiceBusSessionReceiverClient;
import com.azure.messaging.servicebus.models.ServiceBusReceiveMode;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Service Bus AMQP compatibility tests against the Artemis sidecar.
 *
 * Uses the Azure Service Bus Java SDK (azure-messaging-servicebus) for all send/receive.
 * Entity management (create queue/topic/subscription) uses raw HTTP to the floci-az
 * management API, since ServiceBusAdministrationClient requires routing to the
 * standard Azure management port which differs from our emulator setup.
 *
 * Each test gets its own queue or topic to avoid cross-test interference in the
 * durable Artemis queues.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("Service Bus AMQP Compatibility")
class ServiceBusCompatibilityTest {

    private static final Duration RECV_TIMEOUT = Duration.ofSeconds(10);

    @BeforeAll
    void ensureNamespace() throws Exception {
        EmulatorConfig.ensureServiceBusNamespace();
        Assumptions.assumeFalse(EmulatorConfig.serviceBusMocked,
                "Service Bus is in mocked mode — no Artemis broker, AMQP tests skipped");
    }

    // ── Queue tests ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("send and complete removes message from queue")
    void sendAndComplete() throws Exception {
        String queue = uniqueQueue();
        String payload = "hello-" + UUID.randomUUID();

        send(queue, payload);

        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Expected one message in queue");
            assertEquals(payload, msg.getBody().toString());
            receiver.complete(msg);
        }

        // Queue should now be empty
        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            long count = receiver.receiveMessages(1, Duration.ofSeconds(2)).stream().count();
            assertEquals(0, count, "Queue should be empty after complete");
        }
    }

    @Test
    @DisplayName("abandon increments delivery count and requeues message")
    void abandonRequeues() throws Exception {
        String queue = uniqueQueue();
        String payload = "abandon-" + UUID.randomUUID();

        send(queue, payload);

        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Expected a message");
            assertEquals(payload, msg.getBody().toString());
            assertEquals(1, msg.getDeliveryCount());
            receiver.abandon(msg);
        }

        // Message should be back in the queue with incremented delivery count
        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Message should be requeued after abandon");
            assertEquals(payload, msg.getBody().toString());
            assertTrue(msg.getDeliveryCount() >= 2,
                    "Delivery count should be at least 2 after abandon");
            receiver.complete(msg);
        }
    }

    @Test
    @DisplayName("explicit dead-letter moves message to DLQ")
    void explicitDeadLetter() throws Exception {
        String queue = uniqueQueue();
        String payload = "dlq-" + UUID.randomUUID();

        send(queue, payload);

        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Expected a message to dead-letter");
            receiver.deadLetter(msg);
        }

        // Message should appear in the DLQ sub-queue
        try (ServiceBusReceiverClient dlqReceiver = EmulatorConfig.serviceBusClientBuilder()
                .receiver()
                .queueName(queue)
                .subQueue(com.azure.messaging.servicebus.models.SubQueue.DEAD_LETTER_QUEUE)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .buildClient()) {
            ServiceBusReceivedMessage dlqMsg = dlqReceiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(dlqMsg, "Message should be in DLQ");
            assertEquals(payload, dlqMsg.getBody().toString());
            dlqReceiver.complete(dlqMsg);
        }
    }

    @Test
    @DisplayName("message properties round-trip correctly")
    void messageProperties() throws Exception {
        String queue = uniqueQueue();
        String payload = "props-" + UUID.randomUUID();

        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().queueName(queue).buildClient()) {
            ServiceBusMessage msg = new ServiceBusMessage(payload);
            msg.getApplicationProperties().put("key1", "value1");
            msg.getApplicationProperties().put("num", 42);
            msg.setContentType("text/plain");
            sender.sendMessage(msg);
        }

        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Expected a message");
            assertEquals(payload, msg.getBody().toString());
            assertEquals("value1", msg.getApplicationProperties().get("key1"));
            assertEquals(42, msg.getApplicationProperties().get("num"));
            assertEquals("text/plain", msg.getContentType());
            receiver.complete(msg);
        }
    }

    @Test
    @DisplayName("batch send and receive multiple messages")
    void batchSendAndReceive() throws Exception {
        String queue = uniqueQueue();
        int count = 5;

        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().queueName(queue).buildClient()) {
            for (int i = 0; i < count; i++) {
                sender.sendMessage(new ServiceBusMessage("msg-" + i));
            }
        }

        List<String> received = new ArrayList<>();
        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            for (ServiceBusReceivedMessage msg : receiver.receiveMessages(count, RECV_TIMEOUT)) {
                received.add(msg.getBody().toString());
                receiver.complete(msg);
            }
        }

        assertEquals(count, received.size(), "Expected all " + count + " messages");
    }

    // ── Topic / subscription tests ────────────────────────────────────────────

    @Test
    @DisplayName("topic fan-out delivers to all subscriptions independently")
    void topicFanout() throws Exception {
        String topic = uniqueName("topic");
        String sub1 = "sub-a";
        String sub2 = "sub-b";
        EmulatorConfig.ensureServiceBusTopic(topic);
        EmulatorConfig.ensureServiceBusSubscription(topic, sub1);
        EmulatorConfig.ensureServiceBusSubscription(topic, sub2);

        String payload = "fanout-" + UUID.randomUUID();

        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().topicName(topic).buildClient()) {
            sender.sendMessage(new ServiceBusMessage(payload));
        }

        // Both subscriptions should independently receive the message
        try (ServiceBusReceiverClient r1 = subscriptionReceiver(topic, sub1)) {
            ServiceBusReceivedMessage msg = r1.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Subscription '" + sub1 + "' should receive the message");
            assertEquals(payload, msg.getBody().toString());
            r1.complete(msg);
        }

        try (ServiceBusReceiverClient r2 = subscriptionReceiver(topic, sub2)) {
            ServiceBusReceivedMessage msg = r2.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Subscription '" + sub2 + "' should receive the message independently");
            assertEquals(payload, msg.getBody().toString());
            r2.complete(msg);
        }
    }

    @Test
    @DisplayName("subscription isolation — consuming from one does not affect the other")
    void subscriptionIsolation() throws Exception {
        String topic = uniqueName("topic");
        String sub1 = "iso-a";
        String sub2 = "iso-b";
        EmulatorConfig.ensureServiceBusTopic(topic);
        EmulatorConfig.ensureServiceBusSubscription(topic, sub1);
        EmulatorConfig.ensureServiceBusSubscription(topic, sub2);

        String payload = "iso-" + UUID.randomUUID();

        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().topicName(topic).buildClient()) {
            sender.sendMessage(new ServiceBusMessage(payload));
        }

        // Drain sub1 — sub2 should still have the message
        try (ServiceBusReceiverClient r1 = subscriptionReceiver(topic, sub1)) {
            ServiceBusReceivedMessage msg = r1.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, sub1 + " should have message");
            r1.complete(msg);
        }

        try (ServiceBusReceiverClient r2 = subscriptionReceiver(topic, sub2)) {
            ServiceBusReceivedMessage msg = r2.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, sub2 + " should still have the message after " + sub1 + " consumed");
            assertEquals(payload, msg.getBody().toString());
            r2.complete(msg);
        }
    }

    // ── Scheduled delivery tests ──────────────────────────────────────────────

    @Test
    @DisplayName("scheduled message is not delivered before its scheduled time")
    void scheduledMessageNotDeliveredEarly() throws Exception {
        String queue = uniqueQueue();
        String payload = "sched-" + UUID.randomUUID();

        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().queueName(queue).buildClient()) {
            ServiceBusMessage msg = new ServiceBusMessage(payload);
            msg.setScheduledEnqueueTime(OffsetDateTime.now().plusSeconds(30));
            sender.sendMessage(msg);
        }

        // Message should not be available yet
        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            long count = receiver.receiveMessages(1, Duration.ofSeconds(3)).stream().count();
            assertEquals(0, count, "Scheduled message should not be delivered before its time");
        }
    }

    @Test
    @DisplayName("scheduled message is delivered after its scheduled time")
    void scheduledMessageDeliveredAfterDelay() throws Exception {
        String queue = uniqueQueue();
        String payload = "sched-delivery-" + UUID.randomUUID();

        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().queueName(queue).buildClient()) {
            ServiceBusMessage msg = new ServiceBusMessage(payload);
            msg.setScheduledEnqueueTime(OffsetDateTime.now().plusSeconds(4));
            sender.sendMessage(msg);
        }

        // Wait for the schedule to fire
        Thread.sleep(6_000);

        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, Duration.ofSeconds(5))
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Scheduled message should be available after its scheduled time");
            assertEquals(payload, msg.getBody().toString());
            receiver.complete(msg);
        }
    }

    // ── Session tests ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("session-tagged messages are sent and received with correct session ID")
    void sessionTaggedSendReceive() throws Exception {
        String queue = uniqueSessionQueue();
        String sessionId = "session-" + UUID.randomUUID().toString().substring(0, 8);
        String payload = "session-msg-" + UUID.randomUUID();

        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().queueName(queue).buildClient()) {
            ServiceBusMessage msg = new ServiceBusMessage(payload);
            msg.setSessionId(sessionId);
            sender.sendMessage(msg);
        }

        try (ServiceBusSessionReceiverClient sessionReceiver = EmulatorConfig.serviceBusClientBuilder()
                .sessionReceiver()
                .queueName(queue)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .buildClient();
             ServiceBusReceiverClient receiver = sessionReceiver.acceptSession(sessionId)) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Session message should be received");
            assertEquals(payload, msg.getBody().toString());
            assertEquals(sessionId, msg.getSessionId());
            receiver.complete(msg);
        }
    }

    @Test
    @DisplayName("messages from different sessions are isolated")
    void sessionIsolation() throws Exception {
        String queue = uniqueSessionQueue();
        String sessionA = "sess-a-" + UUID.randomUUID().toString().substring(0, 6);
        String sessionB = "sess-b-" + UUID.randomUUID().toString().substring(0, 6);

        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().queueName(queue).buildClient()) {
            ServiceBusMessage msgA = new ServiceBusMessage("msg-for-" + sessionA);
            msgA.setSessionId(sessionA);
            ServiceBusMessage msgB = new ServiceBusMessage("msg-for-" + sessionB);
            msgB.setSessionId(sessionB);
            sender.sendMessage(msgA);
            sender.sendMessage(msgB);
        }

        // Receive from session A only — session B should be unaffected
        try (ServiceBusSessionReceiverClient sessionRecvA = EmulatorConfig.serviceBusClientBuilder()
                .sessionReceiver()
                .queueName(queue)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .buildClient();
             ServiceBusReceiverClient receiverA = sessionRecvA.acceptSession(sessionA)) {
            ServiceBusReceivedMessage msg = receiverA.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Session A should receive its message");
            assertEquals("msg-for-" + sessionA, msg.getBody().toString());
            assertEquals(sessionA, msg.getSessionId());
            receiverA.complete(msg);
        }

        // Session B message should still be there
        try (ServiceBusSessionReceiverClient sessionRecvB = EmulatorConfig.serviceBusClientBuilder()
                .sessionReceiver()
                .queueName(queue)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .buildClient();
             ServiceBusReceiverClient receiverB = sessionRecvB.acceptSession(sessionB)) {
            ServiceBusReceivedMessage msg = receiverB.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Session B message should be unaffected by session A consumption");
            assertEquals("msg-for-" + sessionB, msg.getBody().toString());
            receiverB.complete(msg);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String uniqueQueue() throws Exception {
        String name = uniqueName("q");
        EmulatorConfig.ensureServiceBusQueue(name, false);
        return name;
    }

    private String uniqueSessionQueue() throws Exception {
        String name = uniqueName("sq");
        EmulatorConfig.ensureServiceBusQueue(name, true);
        return name;
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private void send(String queue, String payload) {
        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().queueName(queue).buildClient()) {
            sender.sendMessage(new ServiceBusMessage(payload));
        }
    }

    private ServiceBusReceiverClient peekLockReceiver(String queue) {
        return EmulatorConfig.serviceBusClientBuilder()
                .receiver()
                .queueName(queue)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .buildClient();
    }

    private ServiceBusReceiverClient subscriptionReceiver(String topic, String sub) {
        return EmulatorConfig.serviceBusClientBuilder()
                .receiver()
                .topicName(topic)
                .subscriptionName(sub)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .buildClient();
    }
}
