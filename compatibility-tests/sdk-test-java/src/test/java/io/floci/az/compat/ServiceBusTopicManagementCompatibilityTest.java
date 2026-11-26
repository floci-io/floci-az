package io.floci.az.compat;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusFailureReason;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A topic has its own management node, {@code {topic}/$management}, exactly as a queue does. Senders attach it for
 * request-response operations; the Go SDK attaches it beside every sender, so a topic without one cannot be sent to
 * (floci-io/floci-az#729). The Java SDK attaches it for {@code scheduleMessage}, which is what this test drives.
 *
 * <p>The test compares a topic with a queue rather than asserting what the scheduling call answers, so it does not
 * depend on whether floci-az implements scheduling over the management node: both entities must answer the same way,
 * and neither may answer that its management node does not exist.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Service Bus topic management node")
class ServiceBusTopicManagementCompatibilityTest {

    private static final AmqpRetryOptions NO_RETRY = new AmqpRetryOptions()
            .setMaxRetries(0)
            .setTryTimeout(Duration.ofSeconds(15));

    @BeforeAll
    void ensureNamespace() throws Exception {
        EmulatorConfig.ensureServiceBusNamespace();
        Assumptions.assumeFalse(EmulatorConfig.serviceBusMocked,
                "Service Bus is in mocked mode: no Artemis broker, AMQP tests skipped");
    }

    @Test
    @DisplayName("a topic sender's management request answers as a queue sender's does")
    void topicManagementNodeAnswersLikeAQueues() throws Exception {
        String queue = uniqueName("mgmt-q");
        String topic = uniqueName("mgmt-t");
        EmulatorConfig.ensureServiceBusQueue(queue, false);
        EmulatorConfig.ensureServiceBusTopic(topic);

        String queueOutcome;
        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder().retryOptions(NO_RETRY)
                .sender().queueName(queue).buildClient()) {
            queueOutcome = scheduleOutcome(sender);
        }
        String topicOutcome;
        try (ServiceBusSenderClient sender = EmulatorConfig.serviceBusClientBuilder().retryOptions(NO_RETRY)
                .sender().topicName(topic).buildClient()) {
            topicOutcome = scheduleOutcome(sender);
        }

        assertEquals(queueOutcome, topicOutcome, "a topic's management node must answer like a queue's");
    }

    /** "scheduled", or the failure reason; a missing management node is a test failure on its own. */
    private static String scheduleOutcome(ServiceBusSenderClient sender) {
        try {
            sender.scheduleMessage(new ServiceBusMessage("later"), OffsetDateTime.now().plusMinutes(5));
            return "scheduled";
        } catch (ServiceBusException e) {
            assertNotEquals(ServiceBusFailureReason.MESSAGING_ENTITY_NOT_FOUND, e.getReason(),
                    "the management node must exist: " + e.getMessage());
            assertFalse(String.valueOf(e.getMessage()).contains("does not exist"),
                    "the management node must exist: " + e.getMessage());
            return e.getReason().toString();
        }
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
