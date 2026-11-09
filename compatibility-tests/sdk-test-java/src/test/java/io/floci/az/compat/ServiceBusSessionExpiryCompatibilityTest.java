package io.floci.az.compat;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusFailureReason;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusSessionReceiverClient;
import com.azure.messaging.servicebus.models.SubQueue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises broker-owned expiry, rather than closing an unsettled receiver from the client. */
class ServiceBusSessionExpiryCompatibilityTest {
    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(10);

    @BeforeAll
    static void startNamespace() throws Exception {
        EmulatorConfig.ensureServiceBusNamespace();
        Assumptions.assumeFalse(EmulatorConfig.serviceBusMocked,
                "Service Bus is in mocked mode: no Artemis broker, AMQP tests skipped");
    }

    @Test
    @Timeout(40)
    void repeatedExpiryCountsEachDeliveryOnceAndDeadLettersOnlyTheUnsettledMessage() throws Exception {
        String queue = queue();
        send(queue, "completed", "unsettled");
        try (var sessions = sessions(queue)) {
            for (int count = 1; count <= 3; count++) {
                try (var receiver = sessions.acceptSession("session")) {
                    var messages = receiver.receiveMessages(count == 1 ? 2 : 1, RECEIVE_TIMEOUT).stream().toList();
                    assertEquals(count == 1 ? List.of("completed", "unsettled") : List.of("unsettled"),
                            messages.stream().map(message -> message.getBody().toString()).toList());
                    for (var message : messages) {
                        assertEquals(count - 1, message.getDeliveryCount(), "Each expired delivery must count exactly once");
                    }
                    if (count == 1) {
                        receiver.complete(messages.getFirst());
                    }
                    // No more sends: the pending receive completes only when the broker closes
                    // the expired session link. Keep the receiver alive until that signal.
                    var expired = assertThrows(RuntimeException.class,
                            () -> receiver.receiveMessages(1, RECEIVE_TIMEOUT).stream().toList());
                    assertTrue(expired instanceof ServiceBusException serviceBus
                                    && serviceBus.getReason() == ServiceBusFailureReason.SESSION_LOCK_LOST
                                    || expired.getMessage().contains("receiver client is terminated"),
                            "Expected session lock loss or the SDK terminal-receiver signal: " + expired);
                }
            }
        }
        try (var deadLetters = EmulatorConfig.serviceBusClientBuilder().receiver().queueName(queue)
                .subQueue(SubQueue.DEAD_LETTER_QUEUE).buildClient()) {
            var messages = deadLetters.receiveMessages(2, Duration.ofSeconds(2)).stream().toList();
            assertEquals(1, messages.size());
            assertEquals("unsettled", messages.getFirst().getBody().toString());
            // Automatic DLQ reason metadata is tracked independently in upstream issue #596.
            deadLetters.complete(messages.getFirst());
        }
    }

    @Test
    @Timeout(30)
    void gracefulClosePreservesDeliveryCountAndFifoAndRenewalStillSettles() throws Exception {
        String queue = queue();
        send(queue, "first", "second");
        try (var sessions = sessions(queue)) {
            try (var receiver = sessions.acceptSession("session")) {
                var messages = receiver.receiveMessages(2, RECEIVE_TIMEOUT).stream().toList();
                assertEquals(2, messages.size());
                assertEquals(0, messages.getFirst().getDeliveryCount());
            }
            try (var receiver = sessions.acceptSession("session")) {
                var messages = receiver.receiveMessages(2, RECEIVE_TIMEOUT).stream().toList();
                assertEquals(List.of("first", "second"),
                        messages.stream().map(message -> message.getBody().toString()).toList());
                for (ServiceBusReceivedMessage message : messages) {
                    assertEquals(0, message.getDeliveryCount());
                }
                assertTrue(receiver.renewSessionLock().isAfter(java.time.OffsetDateTime.now()));
                messages.forEach(receiver::complete);
            }
        }
    }

    private static String queue() throws Exception {
        String queue = "expiry-" + UUID.randomUUID().toString().replace("-", "");
        EmulatorConfig.ensureServiceBusQueue(queue,
                "<RequiresSession>true</RequiresSession><LockDuration>PT2S</LockDuration><MaxDeliveryCount>3</MaxDeliveryCount>");
        return queue;
    }

    private static ServiceBusSessionReceiverClient sessions(String queue) {
        return EmulatorConfig.serviceBusClientBuilder().retryOptions(new AmqpRetryOptions().setMaxRetries(0))
                .sessionReceiver().queueName(queue).maxAutoLockRenewDuration(Duration.ZERO).buildClient();
    }

    private static void send(String queue, String... bodies) {
        try (var sender = EmulatorConfig.serviceBusClientBuilder().sender().queueName(queue).buildClient()) {
            for (String body : bodies) {
                sender.sendMessage(new ServiceBusMessage(body).setSessionId("session"));
            }
        }
    }
}
