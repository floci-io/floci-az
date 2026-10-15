package io.floci.az.services.eventhub;

import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.message.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The divert sends every request on {@code $management} to this responder, so it answers every
 * one: a READ of a hub as Azure does, and anything floci-az does not implement with 501. Each
 * reply is correlated to its request and carries the stamp of the requesting connection.
 */
class EventHubManagementResponderTest {

    private static final String STAMP = "floci_connection_id";

    private final EventHubManagementResponder responder = new EventHubManagementResponder(
            "localhost", 5672, Map.of("eh1", new ArtemisConfigGenerator.EntitySpec(4, List.of("$Default"))));

    @Test
    @DisplayName("a READ of a hub gets its properties")
    void readsAHub() {
        Message reply = responder.replyTo(request("READ", "com.microsoft:eventhub", "eh1"));

        assertEquals("request-1", reply.getCorrelationId());
        assertEquals(Map.of("status-code", 200, "status-description", "OK", STAMP, "requesting-connection"),
                properties(reply));
        assertEquals(4, ((Map<?, ?>) ((AmqpValue) reply.getBody()).getValue()).get("partition_count"));
    }

    @Test
    @DisplayName("a READ of a hub the namespace does not have gets 404")
    void readsAMissingHub() {
        Message reply = responder.replyTo(request("READ", "com.microsoft:eventhub", "eh9"));

        assertEquals(404, properties(reply).get("status-code"));
        assertNull(reply.getBody());
    }

    /** Partition properties would report sequence numbers and offsets, which Artemis does not keep. */
    @Test
    @DisplayName("a READ of partition properties gets 501, built like any other reply")
    void partitionReadIsNotImplemented() {
        Message reply = responder.replyTo(request("READ", "com.microsoft:partition", "eh1"));

        assertEquals("request-1", reply.getCorrelationId());
        assertEquals(Map.of("status-code", 501,
                "status-description", "floci-az emulates com.microsoft:eventhub only, not com.microsoft:partition",
                STAMP, "requesting-connection"), properties(reply));
    }

    @Test
    @DisplayName("any other operation gets 501, which names the operation")
    void otherOperationsAreNotImplemented() {
        Message reply = responder.replyTo(request("UPDATE", "com.microsoft:eventhub", "eh1"));

        assertEquals("request-1", reply.getCorrelationId());
        assertEquals(Map.of("status-code", 501,
                "status-description", "floci-az does not implement the operation 'UPDATE'",
                STAMP, "requesting-connection"), properties(reply));
        assertNull(reply.getBody());
    }

    /** A request as the responder receives it: stamped with the id of the connection that sent it. */
    private static Message request(String operation, String type, String name) {
        Message request = Message.Factory.create();
        request.setMessageId("request-1");
        request.setApplicationProperties(new ApplicationProperties(Map.of(
                "operation", operation, "type", type, "name", name, STAMP, "requesting-connection")));
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Message reply) {
        return reply.getApplicationProperties().getValue();
    }
}
