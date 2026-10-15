package io.floci.az.services.servicebus;

import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.message.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The divert sends every request on {@code $cbs} to the CBS responder, so it answers every one: a
 * put-token with 202, and any operation floci-az does not implement with 501. Each reply is
 * correlated to its request and carries the stamp of the requesting connection back.
 */
class ServiceBusCbsResponderTest {

    private static final String STAMP = "floci_connection_id";

    @Test
    @DisplayName("a put-token gets 202, correlated to its request and carrying its stamp")
    void acceptsAPutToken() {
        Message reply = ServiceBusCbsResponder.replyTo(request("put-token"));

        assertEquals("request-1", reply.getCorrelationId());
        assertEquals(Map.of("status-code", 202, "status-description", "Accepted", STAMP, "requesting-connection"),
                properties(reply));
    }

    @Test
    @DisplayName("any other CBS operation gets 501, which names the operation")
    void answersOtherOperationsWithNotImplemented() {
        Message reply = ServiceBusCbsResponder.replyTo(request("delete-token"));

        assertEquals("request-1", reply.getCorrelationId());
        assertEquals(Map.of("status-code", 501,
                "status-description", "floci-az does not implement the operation 'delete-token'",
                STAMP, "requesting-connection"), properties(reply));
    }

    /** A request as the responder receives it: stamped with the id of the connection that sent it. */
    private static Message request(String operation) {
        Message request = Message.Factory.create();
        request.setMessageId("request-1");
        request.setApplicationProperties(new ApplicationProperties(Map.of(
                "operation", operation, STAMP, "requesting-connection")));
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Message reply) {
        return reply.getApplicationProperties().getValue();
    }
}
