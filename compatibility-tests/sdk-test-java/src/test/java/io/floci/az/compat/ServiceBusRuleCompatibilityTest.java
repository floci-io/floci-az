package io.floci.az.compat;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.ServiceBusReceiveMode;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Subscription rule / topic filter compatibility tests (CorrelationFilter, SqlFilter,
 * $Default TrueFilter semantics) against the Artemis sidecar.
 *
 * Rules compile to Artemis queue selectors, so these tests verify broker-side
 * evaluation end to end: send via the Azure SDK, assert which subscriptions the
 * message lands on. Rule management uses raw HTTP like the other compat tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.DisplayName.class)
@DisplayName("Service Bus Subscription Rules Compatibility")
class ServiceBusRuleCompatibilityTest {

    private static final Duration RECV_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration EMPTY_TIMEOUT = Duration.ofSeconds(3);

    private static final String SB_NS = "http://schemas.microsoft.com/netservices/2010/10/servicebus/connect";
    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    @BeforeAll
    void ensureNamespace() throws Exception {
        EmulatorConfig.ensureServiceBusNamespace();
        Assumptions.assumeFalse(EmulatorConfig.serviceBusMocked,
                "Service Bus is in mocked mode — no Artemis broker, AMQP tests skipped");
    }

    @Test
    @DisplayName("correlation filter on subject routes only matching messages")
    void correlationFilterOnSubject() throws Exception {
        String topic = uniqueName("rules-subj");
        EmulatorConfig.ensureServiceBusTopic(topic);
        EmulatorConfig.ensureServiceBusSubscription(topic, "red-sub");
        EmulatorConfig.ensureServiceBusSubscription(topic, "blue-sub");
        replaceDefaultRule(topic, "red-sub", "only-red", correlationRule("only-red", "<Label>red</Label>"));
        replaceDefaultRule(topic, "blue-sub", "only-blue", correlationRule("only-blue", "<Label>blue</Label>"));

        sendWithSubject(topic, "m-red", "red");
        sendWithSubject(topic, "m-blue", "blue");
        sendWithSubject(topic, "m-green", "green");

        List<String> red = drain(topic, "red-sub", 1);
        assertEquals(List.of("m-red"), red, "red-sub must receive exactly the red message");

        List<String> blue = drain(topic, "blue-sub", 1);
        assertEquals(List.of("m-blue"), blue, "blue-sub must receive exactly the blue message");
    }

    @Test
    @DisplayName("correlation filter on correlation id routes only matching messages")
    void correlationFilterOnCorrelationId() throws Exception {
        String topic = uniqueName("rules-cid");
        EmulatorConfig.ensureServiceBusTopic(topic);
        EmulatorConfig.ensureServiceBusSubscription(topic, "high-sub");
        replaceDefaultRule(topic, "high-sub", "only-high",
                correlationRule("only-high", "<CorrelationId>high</CorrelationId>"));

        try (ServiceBusSenderClient sender = topicSender(topic)) {
            sender.sendMessage(new ServiceBusMessage("m-high").setCorrelationId("high"));
            sender.sendMessage(new ServiceBusMessage("m-low").setCorrelationId("low"));
        }

        assertEquals(List.of("m-high"), drain(topic, "high-sub", 1));
    }

    @Test
    @DisplayName("correlation filter on application property routes only matching messages")
    void correlationFilterOnUserProperty() throws Exception {
        String topic = uniqueName("rules-prop");
        EmulatorConfig.ensureServiceBusTopic(topic);
        EmulatorConfig.ensureServiceBusSubscription(topic, "prop-sub");
        replaceDefaultRule(topic, "prop-sub", "only-eu", correlationRule("only-eu",
                "<Properties><KeyValueOfstringanyType><Key>region</Key>"
                        + "<Value i:type=\"d6p1:string\" xmlns:d6p1=\"http://www.w3.org/2001/XMLSchema\">eu</Value>"
                        + "</KeyValueOfstringanyType></Properties>"));

        try (ServiceBusSenderClient sender = topicSender(topic)) {
            ServiceBusMessage eu = new ServiceBusMessage("m-eu");
            eu.getApplicationProperties().put("region", "eu");
            sender.sendMessage(eu);
            ServiceBusMessage us = new ServiceBusMessage("m-us");
            us.getApplicationProperties().put("region", "us");
            sender.sendMessage(us);
        }

        assertEquals(List.of("m-eu"), drain(topic, "prop-sub", 1));
    }

    @Test
    @DisplayName("sql filter evaluates user properties and sys.Label")
    void sqlFilterOnPropertiesAndLabel() throws Exception {
        String topic = uniqueName("rules-sql");
        EmulatorConfig.ensureServiceBusTopic(topic);
        EmulatorConfig.ensureServiceBusSubscription(topic, "sql-sub");
        replaceDefaultRule(topic, "sql-sub", "big-blue", sqlRule("big-blue",
                "color='blue' AND quantity &gt; 5 AND sys.Label='order'"));

        try (ServiceBusSenderClient sender = topicSender(topic)) {
            sender.sendMessage(withProps("m-match", "order", "blue", 10));
            sender.sendMessage(withProps("m-small", "order", "blue", 3));
            sender.sendMessage(withProps("m-red", "order", "red", 10));
            sender.sendMessage(withProps("m-wrong-label", "invoice", "blue", 10));
        }

        assertEquals(List.of("m-match"), drain(topic, "sql-sub", 1));
    }

    @Test
    @DisplayName("$Default rule accepts every message until replaced")
    void defaultRuleAcceptsEverything() throws Exception {
        String topic = uniqueName("rules-def");
        EmulatorConfig.ensureServiceBusTopic(topic);
        EmulatorConfig.ensureServiceBusSubscription(topic, "all-sub");

        sendWithSubject(topic, "m-1", "red");
        sendWithSubject(topic, "m-2", "blue");

        List<String> got = drain(topic, "all-sub", 2);
        assertEquals(List.of("m-1", "m-2"), got, "$Default TrueFilter must accept everything");
    }

    @Test
    @DisplayName("subscription with no rules receives nothing")
    void noRulesDeliversNothing() throws Exception {
        String topic = uniqueName("rules-none");
        EmulatorConfig.ensureServiceBusTopic(topic);
        EmulatorConfig.ensureServiceBusSubscription(topic, "empty-sub");
        EmulatorConfig.deleteServiceBusRule(topic, "empty-sub", "$Default");

        sendWithSubject(topic, "m-lost", "red");

        assertEquals(List.of(), drain(topic, "empty-sub", 0),
                "a subscription without rules must not receive messages");
    }

    @Test
    @DisplayName("multiple rules combine as OR and deliver a single copy")
    void multipleRulesCombineAsOr() throws Exception {
        String topic = uniqueName("rules-or");
        EmulatorConfig.ensureServiceBusTopic(topic);
        EmulatorConfig.ensureServiceBusSubscription(topic, "or-sub");
        replaceDefaultRule(topic, "or-sub", "red", correlationRule("red", "<Label>red</Label>"));
        EmulatorConfig.ensureServiceBusRule(topic, "or-sub", "blue",
                correlationRule("blue", "<Label>blue</Label>"));

        sendWithSubject(topic, "m-red", "red");
        sendWithSubject(topic, "m-blue", "blue");
        sendWithSubject(topic, "m-green", "green");

        assertEquals(List.of("m-red", "m-blue"), drain(topic, "or-sub", 2));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Azure SDK flow: add the new rule first, then delete the implicit $Default. */
    private static void replaceDefaultRule(String topic, String sub, String ruleName,
                                            String ruleXml) throws Exception {
        EmulatorConfig.ensureServiceBusRule(topic, sub, ruleName, ruleXml);
        EmulatorConfig.deleteServiceBusRule(topic, sub, "$Default");
    }

    private static String correlationRule(String name, String filterChildren) {
        return "<RuleDescription xmlns:i=\"" + XSI_NS + "\" xmlns=\"" + SB_NS + "\">"
                + "<Filter i:type=\"CorrelationFilter\">" + filterChildren + "</Filter>"
                + "<Action i:type=\"EmptyRuleAction\"/>"
                + "<Name>" + name + "</Name></RuleDescription>";
    }

    private static String sqlRule(String name, String escapedExpression) {
        return "<RuleDescription xmlns:i=\"" + XSI_NS + "\" xmlns=\"" + SB_NS + "\">"
                + "<Filter i:type=\"SqlFilter\"><SqlExpression>" + escapedExpression + "</SqlExpression>"
                + "<CompatibilityLevel>20</CompatibilityLevel></Filter>"
                + "<Action i:type=\"EmptyRuleAction\"/>"
                + "<Name>" + name + "</Name></RuleDescription>";
    }

    private static ServiceBusMessage withProps(String body, String subject, String color, int quantity) {
        ServiceBusMessage msg = new ServiceBusMessage(body).setSubject(subject);
        msg.getApplicationProperties().put("color", color);
        msg.getApplicationProperties().put("quantity", quantity);
        return msg;
    }

    private void sendWithSubject(String topic, String body, String subject) {
        try (ServiceBusSenderClient sender = topicSender(topic)) {
            sender.sendMessage(new ServiceBusMessage(body).setSubject(subject));
        }
    }

    private ServiceBusSenderClient topicSender(String topic) {
        return EmulatorConfig.serviceBusClientBuilder().sender().topicName(topic).buildClient();
    }

    /**
     * Receives and completes everything on the subscription (send order preserved).
     * Requests the expected count first so the call returns as soon as those messages
     * arrive, then makes one short {@link #EMPTY_TIMEOUT} pass to catch misrouted strays.
     */
    private List<String> drain(String topic, String sub, int expectedCount) {
        List<String> bodies = new ArrayList<>();
        try (ServiceBusReceiverClient receiver = EmulatorConfig.serviceBusClientBuilder()
                .receiver()
                .topicName(topic)
                .subscriptionName(sub)
                .receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .buildClient()) {
            if (expectedCount > 0) {
                consume(receiver, expectedCount, RECV_TIMEOUT, bodies);
            }
            consume(receiver, 10, EMPTY_TIMEOUT, bodies);
        }
        return bodies;
    }

    private static void consume(ServiceBusReceiverClient receiver, int maxMessages,
                                 Duration timeout, List<String> bodies) {
        for (ServiceBusReceivedMessage msg : receiver.receiveMessages(maxMessages, timeout)) {
            bodies.add(msg.getBody().toString());
            receiver.complete(msg);
        }
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
