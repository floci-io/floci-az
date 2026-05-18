package io.floci.az.compat;

import jakarta.jms.*;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Event Hubs AMQP compatibility tests against the Artemis sidecar.
 *
 * Uses Apache Qpid JMS (AMQP 1.0) to validate send/receive and durable
 * consumer-group semantics. Sends target the ANYCAST entity address;
 * exclusive Artemis diverts fan out messages to each consumer group's durable
 * queue. Receivers attach to the consumer-group queue address.
 *
 * Mirrors the Python uamqp-based tests in sdk-test-python/tests/eventhub/.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("Event Hubs AMQP Compatibility")
class EventHubCompatibilityTest {

    private static final String DEFAULT_CG = "$Default";
    private static final String SECONDARY_CG = "my-consumer-group";
    private static final int RECV_TIMEOUT_MS = 10_000;

    private ConnectionFactory factory;
    private String testId;

    @BeforeAll
    void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        factory = EmulatorConfig.buildAmqpConnectionFactory();
    }

    @BeforeEach
    void newTestId() {
        testId = UUID.randomUUID().toString().replace("-", "");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void sendMessages(List<String> texts) throws JMSException {
        try (Connection conn = factory.createConnection();
             Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            conn.start();
            Destination dest = session.createQueue(EmulatorConfig.amqpEntityAddress());
            MessageProducer producer = session.createProducer(dest);
            for (String text : texts) {
                TextMessage msg = session.createTextMessage(text);
                msg.setStringProperty("testId", testId);
                producer.send(msg);
            }
        }
    }

    private List<String> receiveMessages(String consumerGroup, int maxCount) throws JMSException {
        List<String> result = new ArrayList<>();
        String selector = "testId = '" + testId + "'";
        try (Connection conn = factory.createConnection();
             Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            conn.start();
            Destination dest = session.createQueue(EmulatorConfig.amqpCgAddress(consumerGroup));
            MessageConsumer consumer = session.createConsumer(dest, selector);
            for (int i = 0; i < maxCount; i++) {
                Message msg = consumer.receive(RECV_TIMEOUT_MS);
                if (msg == null) break;
                if (msg instanceof TextMessage) {
                    result.add(((TextMessage) msg).getText());
                }
            }
        }
        return result;
    }

    private static String tag(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("send and receive basic messages")
    void sendAndReceive() throws JMSException {
        List<String> payloads = List.of(
                tag("msg"), tag("msg"), tag("msg"),
                tag("msg"), tag("msg"), tag("msg"),
                tag("msg"), tag("msg"), tag("msg"), tag("msg"));

        sendMessages(payloads);
        List<String> received = receiveMessages(DEFAULT_CG, 20);

        for (String p : payloads) {
            assertTrue(received.contains(p), "Expected '" + p + "' in received messages");
        }
    }

    @Test
    @DisplayName("send single events")
    void sendSingleEvents() throws JMSException {
        String alpha = tag("alpha");
        String beta  = tag("beta");
        String gamma = tag("gamma");

        sendMessages(List.of(alpha, beta, gamma));
        List<String> received = receiveMessages(DEFAULT_CG, 10);

        assertTrue(received.contains(alpha));
        assertTrue(received.contains(beta));
        assertTrue(received.contains(gamma));
    }

    @Test
    @DisplayName("two consumer groups read independently")
    void twoConsumerGroupsReadIndependently() throws JMSException {
        List<String> payloads = List.of(
                tag("shared"), tag("shared"), tag("shared"),
                tag("shared"), tag("shared"));

        sendMessages(payloads);

        List<String> fromDefault   = receiveMessages(DEFAULT_CG, 20);
        List<String> fromSecondary = receiveMessages(SECONDARY_CG, 20);

        for (String p : payloads) {
            assertTrue(fromDefault.contains(p),   "Group $Default missing '" + p + "'");
            assertTrue(fromSecondary.contains(p), "Group my-consumer-group missing '" + p + "'");
        }
    }

    @Test
    @DisplayName("consumer group offsets are independent")
    void consumerGroupOffsetsAreIndependent() throws JMSException {
        List<String> payloads = List.of(tag("off"), tag("off"), tag("off"));

        sendMessages(payloads);

        // Consume from $Default — should not affect my-consumer-group
        List<String> fromDefault = receiveMessages(DEFAULT_CG, 10);
        // Then consume from secondary — must still see all messages
        List<String> fromSecondary = receiveMessages(SECONDARY_CG, 10);

        assertTrue(fromDefault.size() >= payloads.size(),
                "Expected at least " + payloads.size() + " messages in $Default, got " + fromDefault.size());
        assertTrue(fromSecondary.size() >= payloads.size(),
                "Expected at least " + payloads.size() + " messages in my-consumer-group, got " + fromSecondary.size());

        for (String p : payloads) {
            assertTrue(fromDefault.contains(p),   "$Default missing '" + p + "'");
            assertTrue(fromSecondary.contains(p), "my-consumer-group missing '" + p + "'");
        }
    }

    @Test
    @DisplayName("messages delivered without partition key")
    void messagesDeliveredWithoutPartitionKey() throws JMSException {
        List<String> payloads = List.of(tag("nokey"), tag("nokey"), tag("nokey"), tag("nokey"));

        sendMessages(payloads);
        List<String> received = receiveMessages(DEFAULT_CG, 10);

        for (String p : payloads) {
            assertTrue(received.contains(p), "Expected '" + p + "' in received messages");
        }
    }

    @Test
    @DisplayName("high throughput delivery")
    void highThroughputDelivery() throws JMSException {
        List<String> payloads = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            payloads.add(tag("bulk-" + i));
        }

        sendMessages(payloads);
        List<String> received = receiveMessages(DEFAULT_CG, 60);

        assertTrue(received.size() >= payloads.size(),
                "Expected at least " + payloads.size() + " messages, got " + received.size());
        for (String p : payloads) {
            assertTrue(received.contains(p), "Expected '" + p + "' in received messages");
        }
    }
}
