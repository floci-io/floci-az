package io.floci.az.compat;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.ServiceBusSessionReceiverClient;
import com.azure.messaging.servicebus.models.AbandonOptions;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import com.azure.messaging.servicebus.models.ServiceBusReceiveMode;
import com.azure.messaging.servicebus.models.SubQueue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    @Test
    @Timeout(30)
    @DisplayName("message renewal returns an expiration timestamp and extends settlement")
    void renewMessageLockExtendsDeadline() throws Exception {
        String queue = uniqueName("renew");
        EmulatorConfig.ensureServiceBusQueue(queue, "<LockDuration>PT6S</LockDuration>");
        send(queue, "renew-me");
        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            ServiceBusReceivedMessage message = receiver.receiveMessages(1, RECV_TIMEOUT).stream().findFirst().orElseThrow();
            OffsetDateTime originalDeadline = message.getLockedUntil();
            Thread.sleep(3000);
            OffsetDateTime renewed = receiver.renewMessageLock(message);
            assertNotNull(renewed, "The SDK must deserialize the expirations timestamp array");
            assertTrue(renewed.isAfter(originalDeadline));
            Thread.sleep(Math.max(0, Duration.between(OffsetDateTime.now(), originalDeadline).toMillis() + 500));
            receiver.complete(message);
        }
    }

    @Test
    @Timeout(40)
    @DisplayName("closing a receiver keeps its unsettled message locked until the lock expires")
    void closedReceiverKeepsMessageLockedUntilDeadline() throws Exception {
        String queue = uniqueName("close-lock");
        EmulatorConfig.ensureServiceBusQueue(queue, "<LockDuration>PT6S</LockDuration>");
        send(queue, "lock-me");

        ServiceBusReceivedMessage first;
        // No automatic lock renewal, so the deadline the message carries is the one the broker keeps.
        try (ServiceBusReceiverClient owner = EmulatorConfig.serviceBusClientBuilder()
                .receiver()
                .queueName(queue)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .maxAutoLockRenewDuration(Duration.ZERO)
                .buildClient()) {
            first = owner.receiveMessages(1, RECV_TIMEOUT).stream().findFirst().orElseThrow();
        }

        try (ServiceBusReceiverClient competitor = peekLockReceiver(queue)) {
            assertEquals(0, competitor.receiveMessages(1, Duration.ofSeconds(2)).stream().count(),
                    "the closed receiver's message must stay locked until its deadline");
            Thread.sleep(Math.max(0, Duration.between(OffsetDateTime.now(), first.getLockedUntil()).toMillis() + 1000));
            ServiceBusReceivedMessage again = competitor.receiveMessages(1, RECV_TIMEOUT).stream().findFirst()
                    .orElseThrow(() -> new AssertionError("the message must return once its lock expires"));
            assertEquals(first.getSequenceNumber(), again.getSequenceNumber());
            assertEquals(first.getDeliveryCount() + 1, again.getDeliveryCount());
            competitor.complete(again);
        }
    }

    @BeforeAll
    void ensureNamespace() throws Exception {
        EmulatorConfig.ensureServiceBusNamespace();
        Assumptions.assumeFalse(EmulatorConfig.serviceBusMocked,
                "Service Bus is in mocked mode — no Artemis broker, AMQP tests skipped");
    }

    // ── Queue tests ───────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("received broker metadata matches peek for standard and large messages")
    void receivedMetadataMatchesPeek(boolean large) throws Exception {
        String queue = uniqueQueue();
        OffsetDateTime beforeSend = OffsetDateTime.now().minusSeconds(5);
        String body = large ? "x".repeat(200_000) : "metadata";
        send(queue, body + "1");
        send(queue, body + "2");
        OffsetDateTime afterSend = OffsetDateTime.now().plusSeconds(5);

        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            List<ServiceBusReceivedMessage> peeked = receiver.peekMessages(2).stream().toList();
            List<ServiceBusReceivedMessage> received = receiver.receiveMessages(2, RECV_TIMEOUT).stream().toList();
            assertEquals(2, peeked.size());
            assertEquals(2, received.size());
            assertNotEquals(received.get(0).getSequenceNumber(), received.get(1).getSequenceNumber());
            for (int i = 0; i < 2; i++) {
                ServiceBusReceivedMessage message = received.get(i);
                assertTrue(message.getSequenceNumber() > 0);
                assertEquals(peeked.get(i).getSequenceNumber(), message.getSequenceNumber());
                assertEquals(peeked.get(i).getEnqueuedTime(), message.getEnqueuedTime());
                assertFalse(message.getEnqueuedTime().isBefore(beforeSend));
                assertFalse(message.getEnqueuedTime().isAfter(afterSend));
                assertEquals(body + (i + 1), message.getBody().toString());
                receiver.complete(message);
            }
        }
    }

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
            // The Java SDK exposes the zero-based AMQP header directly; .NET adds one.
            assertEquals(0, msg.getDeliveryCount());
            receiver.abandon(msg);
        }

        // Message should be back in the queue with incremented delivery count
        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Message should be requeued after abandon");
            assertEquals(payload, msg.getBody().toString());
            assertEquals(1, msg.getDeliveryCount(),
                    "One abandon should increment the AMQP delivery count once");
            receiver.complete(msg);
        }
    }

    @Test
    @DisplayName("abandon applies properties to modify to the redelivered message")
    void abandonAppliesPropertiesToModify() throws Exception {
        String queue = uniqueQueue();
        ServiceBusMessage sent = new ServiceBusMessage("abandon-props-" + UUID.randomUUID());
        sent.getApplicationProperties().put("custom", "preserved");
        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().queueName(queue).buildClient()) {
            sender.sendMessage(sent);
        }

        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Expected a message");
            receiver.abandon(msg, new AbandonOptions().setPropertiesToModify(Map.of("attempt-note", "retry-1")));
        }

        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Message should be requeued after abandon");
            assertEquals("retry-1", msg.getApplicationProperties().get("attempt-note"));
            assertEquals("preserved", msg.getApplicationProperties().get("custom"));
            receiver.complete(msg);
        }
    }

    @Test
    @DisplayName("a deferred message is received by sequence number and completed")
    void deferredMessageIsReceivedBySequenceNumberAndCompleted() throws Exception {
        String queue = uniqueQueue();
        String payload = "deferred-" + UUID.randomUUID();
        send(queue, payload);

        long sequenceNumber;
        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Expected a message");
            sequenceNumber = msg.getSequenceNumber();
            receiver.defer(msg);
        }

        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            assertEquals(0, receiver.receiveMessages(1, Duration.ofSeconds(3)).stream().count(),
                    "A deferred message must not be delivered to receivers again");

            ServiceBusReceivedMessage deferred = receiver.receiveDeferredMessage(sequenceNumber);
            assertNotNull(deferred, "Expected the deferred message");
            assertEquals(payload, deferred.getBody().toString());
            assertEquals(sequenceNumber, deferred.getSequenceNumber());
            assertNotNull(deferred.getLockToken());
            assertTrue(deferred.getLockedUntil().isAfter(OffsetDateTime.now()));
            receiver.complete(deferred);

            assertDeferredMessageNotFound(receiver, sequenceNumber, "A completed deferred message is gone");
        }
    }

    @Test
    @DisplayName("a deferred message outlives its time to live until it is received, and is then dead-lettered")
    void deferredMessageExpiresOnlyWhenReceived() throws Exception {
        String queue = uniqueName("q");
        EmulatorConfig.ensureServiceBusQueue(queue,
                "<DeadLetteringOnMessageExpiration>true</DeadLetteringOnMessageExpiration>");
        String payload = "deferred-ttl-" + UUID.randomUUID();
        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().queueName(queue).buildClient()) {
            sender.sendMessage(new ServiceBusMessage(payload).setTimeToLive(Duration.ofSeconds(2)));
        }

        long sequenceNumber;
        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Expected a message");
            sequenceNumber = msg.getSequenceNumber();
            receiver.defer(msg);
        }

        Thread.sleep(4_000);
        try (ServiceBusReceiverClient deadLetters = deadLetterReceiver(queue)) {
            assertEquals(0, deadLetters.receiveMessages(1, Duration.ofSeconds(2)).stream().count(),
                    "A deferred message must not expire while it is deferred");
        }

        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            assertDeferredMessageNotFound(receiver, sequenceNumber,
                    "A deferred message past its time to live expires when it is received");
        }

        try (ServiceBusReceiverClient deadLetters = deadLetterReceiver(queue)) {
            ServiceBusReceivedMessage expired = deadLetters.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(expired, "The expired deferred message is dead-lettered");
            assertEquals(payload, expired.getBody().toString());
            deadLetters.complete(expired);
        }
    }

    @Test
    @DisplayName("a deferred message lock can be renewed and the message is not received twice while locked")
    void deferredMessageLockIsRenewedAndExclusive() throws Exception {
        String queue = uniqueQueue();
        send(queue, "deferred-lock-" + UUID.randomUUID());

        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue);
             ServiceBusReceiverClient other = peekLockReceiver(queue)) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Expected a message");
            receiver.defer(msg);

            ServiceBusReceivedMessage deferred = receiver.receiveDeferredMessage(msg.getSequenceNumber());
            assertDeferredMessageNotFound(other, msg.getSequenceNumber(),
                    "A locked deferred message must not be handed out again");

            OffsetDateTime firstDeadline = deferred.getLockedUntil();
            Thread.sleep(1_000);
            OffsetDateTime renewed = receiver.renewMessageLock(deferred);
            assertTrue(renewed.isAfter(firstDeadline), "Renewal should extend the lock");
            receiver.complete(deferred);
        }
    }

    @Test
    @DisplayName("a session receiver receives and completes a deferred session message")
    void sessionReceiverReceivesDeferredMessage() throws Exception {
        String queue = uniqueSessionQueue();
        String sessionId = "session-" + UUID.randomUUID();
        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().queueName(queue).buildClient()) {
            ServiceBusMessage message = new ServiceBusMessage("deferred-session");
            message.setSessionId(sessionId);
            sender.sendMessage(message);
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
            receiver.defer(msg);

            ServiceBusReceivedMessage deferred = receiver.receiveDeferredMessage(msg.getSequenceNumber());
            assertEquals("deferred-session", deferred.getBody().toString());
            assertEquals(sessionId, deferred.getSessionId());
            receiver.complete(deferred);
        }
    }

    @Test
    @DisplayName("receiving a message that was not deferred by sequence number fails")
    void receivingAMessageThatWasNotDeferredFails() throws Exception {
        String queue = uniqueQueue();
        send(queue, "not-deferred-" + UUID.randomUUID());

        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            ServiceBusReceivedMessage peeked = receiver.peekMessage();
            assertNotNull(peeked, "Expected a message to peek");

            assertDeferredMessageNotFound(receiver, peeked.getSequenceNumber(),
                    "A message that was not deferred is not found");

            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "The message is still delivered normally");
            receiver.complete(msg);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true",
            "true,false,false", "true,false,true", "true,true,false", "true,true,true"})
    @DisplayName("explicit dead-letter preserves standard and streamed payloads and metadata")
    void explicitDeadLetter(boolean large, boolean metadata, boolean properties) throws Exception {
        String queue = uniqueQueue();
        String payload = large ? "x".repeat(200_000) : "dlq-" + UUID.randomUUID();
        ServiceBusMessage sent = new ServiceBusMessage(payload);
        if (properties) {
            sent.getApplicationProperties().put("custom", "preserved");
        }
        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().queueName(queue).buildClient()) {
            sender.sendMessage(sent);
        }

        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Expected a message to dead-letter");
            if (metadata) {
                receiver.deadLetter(msg, new DeadLetterOptions()
                        .setDeadLetterReason("poison").setDeadLetterErrorDescription("invalid payload"));
            } else {
                receiver.deadLetter(msg);
            }
        }

        // Message should appear in the DLQ sub-queue
        try (ServiceBusReceiverClient dlqReceiver = EmulatorConfig.serviceBusClientBuilder()
                .receiver()
                .queueName(queue)
                .subQueue(SubQueue.DEAD_LETTER_QUEUE)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .buildClient()) {
            ServiceBusReceivedMessage dlqMsg = dlqReceiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(dlqMsg, "Message should be in DLQ");
            assertEquals(payload, dlqMsg.getBody().toString());
            if (properties) {
                assertEquals("preserved", dlqMsg.getApplicationProperties().get("custom"));
            }
            if (metadata) {
                assertEquals("poison", dlqMsg.getDeadLetterReason());
                assertEquals("invalid payload", dlqMsg.getDeadLetterErrorDescription());
            }
            dlqReceiver.complete(dlqMsg);
        }
    }

    @Test
    @DisplayName("entity MaxDeliveryCount dead-letters after the configured attempts")
    void maxDeliveryCountDeadLettersAfterEntityLimit() throws Exception {
        String queue = uniqueName("mdc");
        EmulatorConfig.ensureServiceBusQueue(queue, "<MaxDeliveryCount>2</MaxDeliveryCount>");
        String payload = "mdc-" + UUID.randomUUID();

        send(queue, payload);

        // Exhaust the entity's two delivery attempts by abandoning both. Only the relative
        // delivery count is asserted: this SDK exposes the zero-based AMQP header directly.
        long previousCount = -1;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
                ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                        .stream().findFirst().orElse(null);
                assertNotNull(msg, "Expected delivery attempt " + attempt);
                assertTrue(msg.getDeliveryCount() > previousCount,
                        "Delivery count should increase on each attempt");
                previousCount = msg.getDeliveryCount();
                receiver.abandon(msg);
            }
        }

        // The third attempt must yield nothing: the broker dead-lettered the message.
        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            long count = receiver.receiveMessages(1, Duration.ofSeconds(2)).stream().count();
            assertEquals(0, count, "Message should be gone after MaxDeliveryCount attempts");
        }

        try (ServiceBusReceiverClient dlqReceiver = EmulatorConfig.serviceBusClientBuilder()
                .receiver()
                .queueName(queue)
                .subQueue(SubQueue.DEAD_LETTER_QUEUE)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .buildClient()) {
            ServiceBusReceivedMessage dlqMsg = dlqReceiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(dlqMsg, "Message should be dead-lettered after exceeding MaxDeliveryCount");
            assertEquals(payload, dlqMsg.getBody().toString());
            assertEquals("MaxDeliveryCountExceeded", dlqMsg.getDeadLetterReason());
            assertEquals("Message could not be consumed after 2 delivery attempts.",
                    dlqMsg.getDeadLetterErrorDescription());
            dlqReceiver.complete(dlqMsg);
        }
    }

    @Test
    @DisplayName("a message past its time to live is dead-lettered with the expiry reason")
    void expiredMessageIsDeadLetteredWithTheExpiryReason() throws Exception {
        String queue = uniqueName("ttl");
        EmulatorConfig.ensureServiceBusQueue(queue,
                "<DeadLetteringOnMessageExpiration>true</DeadLetteringOnMessageExpiration>");
        String payload = "ttl-" + UUID.randomUUID();
        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().queueName(queue).buildClient()) {
            sender.sendMessage(new ServiceBusMessage(payload).setTimeToLive(Duration.ofSeconds(2)));
        }

        Thread.sleep(4_000);
        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            assertEquals(0, receiver.receiveMessages(1, Duration.ofSeconds(2)).stream().count(),
                    "An expired message is not delivered");
        }

        try (ServiceBusReceiverClient deadLetters = deadLetterReceiver(queue)) {
            ServiceBusReceivedMessage expired = deadLetters.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(expired, "The expired message is dead-lettered");
            assertEquals(payload, expired.getBody().toString());
            assertEquals("TTLExpiredException", expired.getDeadLetterReason());
            assertEquals("The message expired and was dead lettered.",
                    expired.getDeadLetterErrorDescription());
            deadLetters.complete(expired);
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
    @DisplayName("To and ReplyTo arrive as the sender set them, on a queue and a subscription")
    void toAndReplyToRoundTrip() throws Exception {
        String queue = uniqueQueue();
        String topic = uniqueName("topic");
        EmulatorConfig.ensureServiceBusTopic(topic);
        EmulatorConfig.ensureServiceBusSubscription(topic, "sub");

        ServiceBusMessage addressed = new ServiceBusMessage("addressed");
        addressed.setTo("billing");
        addressed.setReplyTo("replies");
        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().queueName(queue).buildClient()) {
            sender.sendMessage(addressed);
            sender.sendMessage(new ServiceBusMessage("unaddressed"));
        }
        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().topicName(topic).buildClient()) {
            sender.sendMessage(addressed);
        }

        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            List<ServiceBusReceivedMessage> received = new ArrayList<>();
            receiver.receiveMessages(2, RECV_TIMEOUT).forEach(received::add);
            assertEquals(2, received.size(), "Expected both messages");
            ServiceBusReceivedMessage first = received.get(0);
            assertEquals("billing", first.getTo());
            assertEquals("replies", first.getReplyTo());
            assertNull(received.get(1).getTo(), "the broker must not fill in a To the sender left out");
            received.forEach(receiver::complete);
        }

        try (ServiceBusReceiverClient receiver = subscriptionReceiver(topic, "sub")) {
            ServiceBusReceivedMessage msg = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(msg, "Expected a message on the subscription");
            assertEquals("billing", msg.getTo());
            assertEquals("replies", msg.getReplyTo());
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

    @Test
    @DisplayName("accept-next-session preserves FIFO ordering and isolates sessions")
    void acceptNextSessionPreservesOrder() throws Exception {
        String queue = uniqueSessionQueue();
        String sessionA = "next-a-" + UUID.randomUUID().toString().substring(0, 6);
        String sessionB = "next-b-" + UUID.randomUUID().toString().substring(0, 6);

        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().queueName(queue).buildClient()) {
            for (int i = 0; i < 2; i++) {
                ServiceBusMessage message = new ServiceBusMessage("a-" + i);
                message.setSessionId(sessionA);
                sender.sendMessage(message);
            }
            ServiceBusMessage message = new ServiceBusMessage("b-0");
            message.setSessionId(sessionB);
            sender.sendMessage(message);
        }

        try (ServiceBusSessionReceiverClient sessionReceiver = EmulatorConfig.serviceBusClientBuilder()
                .sessionReceiver()
                .queueName(queue)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .buildClient();
             ServiceBusReceiverClient receiver = sessionReceiver.acceptNextSession()) {
            assertEquals(sessionA, receiver.getSessionId());
            List<ServiceBusReceivedMessage> messages = receiver.receiveMessages(2, RECV_TIMEOUT)
                    .stream().toList();
            assertEquals(List.of("a-0", "a-1"), messages.stream()
                    .map(message -> message.getBody().toString()).toList());
            messages.forEach(receiver::complete);
        }

        try (ServiceBusSessionReceiverClient sessionReceiver = EmulatorConfig.serviceBusClientBuilder()
                .sessionReceiver()
                .queueName(queue)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .buildClient();
             ServiceBusReceiverClient receiver = sessionReceiver.acceptNextSession()) {
            assertEquals(sessionB, receiver.getSessionId());
            ServiceBusReceivedMessage message = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(message);
            assertEquals("b-0", message.getBody().toString());
            receiver.complete(message);
        }
    }

    @Test
    @DisplayName("session-enabled queues reject non-session receivers")
    void sessionQueueRejectsNonSessionReceiver() throws Exception {
        String queue = uniqueSessionQueue();

        try (ServiceBusReceiverClient receiver = peekLockReceiver(queue)) {
            assertReceiverRejected(
                    () -> receiver.receiveMessages(1, Duration.ofSeconds(2)).stream().toList(),
                    "A session receiver is required for this entity");
        }
    }

    @Test
    @DisplayName("non-session queues reject session receivers")
    void nonSessionQueueRejectsSessionReceiver() throws Exception {
        String queue = uniqueQueue();

        try (ServiceBusSessionReceiverClient receiver = EmulatorConfig.serviceBusClientBuilder()
                .sessionReceiver()
                .queueName(queue)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .buildClient()) {
            assertReceiverRejected(receiver::acceptNextSession,
                    "The entity is not configured to require sessions");
        }
    }

    @Test
    @DisplayName("session-enabled subscriptions require a session receiver")
    void sessionSubscriptionRequiresSessionReceiver() throws Exception {
        String topic = uniqueName("st");
        String subscription = "session-sub";
        String sessionId = "sub-session-" + UUID.randomUUID().toString().substring(0, 6);
        EmulatorConfig.ensureServiceBusTopic(topic);
        EmulatorConfig.ensureServiceBusSubscription(topic, subscription, true);

        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().topicName(topic).buildClient()) {
            ServiceBusMessage message = new ServiceBusMessage("subscription-session-message");
            message.setSessionId(sessionId);
            sender.sendMessage(message);
        }

        try (ServiceBusReceiverClient receiver = subscriptionReceiver(topic, subscription)) {
            assertReceiverRejected(
                    () -> receiver.receiveMessages(1, Duration.ofSeconds(2)).stream().toList(),
                    "A session receiver is required for this entity");
        }

        try (ServiceBusSessionReceiverClient sessionReceiver = EmulatorConfig.serviceBusClientBuilder()
                .sessionReceiver()
                .topicName(topic)
                .subscriptionName(subscription)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .buildClient();
             ServiceBusReceiverClient receiver = sessionReceiver.acceptSession(sessionId)) {
            ServiceBusReceivedMessage message = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(message);
            assertEquals(sessionId, message.getSessionId());
            receiver.complete(message);
        }
    }

    @Test
    @DisplayName("session lock expires using configured duration and can be reacquired")
    void sessionLockExpiresAndCanBeReacquired() throws Exception {
        String configuredDuration = System.getenv("SERVICEBUS_SESSION_LOCK_DURATION_SECONDS");
        Assumptions.assumeTrue(configuredDuration != null,
                "Session expiry test requires the Docker compatibility environment");
        long lockDurationSeconds = Long.parseLong(configuredDuration);
        String queue = uniqueSessionQueue();
        String sessionId = "expiring-" + UUID.randomUUID().toString().substring(0, 6);
        ServiceBusMessage sent = new ServiceBusMessage("session-lock-expiry");
        sent.setSessionId(sessionId);

        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder()
                .sender().queueName(queue).buildClient()) {
            sender.sendMessage(sent);
        }

        try (ServiceBusSessionReceiverClient sessionReceiver = EmulatorConfig.serviceBusClientBuilder()
                .sessionReceiver()
                .queueName(queue)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .maxAutoLockRenewDuration(Duration.ZERO)
                .buildClient();
            ServiceBusReceiverClient receiver = sessionReceiver.acceptSession(sessionId)) {
            assertEquals(1, receiver.receiveMessages(1, RECV_TIMEOUT).stream().count());
            Thread.sleep(Duration.ofSeconds(lockDurationSeconds + 1));
        }

        try (ServiceBusSessionReceiverClient sessionReceiver = EmulatorConfig.serviceBusClientBuilder()
                .sessionReceiver()
                .queueName(queue)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .maxAutoLockRenewDuration(Duration.ZERO)
                .buildClient();
             ServiceBusReceiverClient receiver = sessionReceiver.acceptSession(sessionId)) {
            ServiceBusReceivedMessage message = receiver.receiveMessages(1, RECV_TIMEOUT)
                    .stream().findFirst().orElse(null);
            assertNotNull(message, "Unsettled message should return after the session lock expires");
            assertEquals(sessionId, message.getSessionId());
            receiver.complete(message);
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

    /**
     * The SDK turns a 404 message-not-found reply into an empty result, so the batch call returns
     * nothing. Any other failure, such as a 501, throws instead.
     */
    private static void assertDeferredMessageNotFound(
            ServiceBusReceiverClient receiver, long sequenceNumber, String message) {
        assertEquals(0, receiver.receiveDeferredMessageBatch(List.of(sequenceNumber)).stream().count(), message);
    }

    private static void assertReceiverRejected(Executable operation, String expectedMessage) {
        RuntimeException error = assertThrows(RuntimeException.class, operation);
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertInstanceOf(UnsupportedOperationException.class, root);
        assertEquals(expectedMessage, root.getMessage());
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

    private ServiceBusReceiverClient deadLetterReceiver(String queue) {
        return EmulatorConfig.serviceBusClientBuilder()
                .receiver()
                .queueName(queue)
                .subQueue(SubQueue.DEAD_LETTER_QUEUE)
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
