package io.floci.az.core;

import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.message.Message;

import java.nio.BufferOverflowException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds the replies to the AMQP CBS and management requests of Event Hubs and Service Bus.
 *
 * <p>Azure answers a request on {@code $cbs} or {@code $management} with a message correlated to
 * the request by its message id, whose application properties carry {@code status-code} and
 * {@code status-description}. Every responder builds its replies here, so they all take that
 * shape. Each reply also carries the stamp of the requesting connection: the broker routes the
 * reply to that connection only, and keeps the stamp to itself, so the client receives the reply
 * without it.
 */
public final class AmqpManagementReply {

    /**
     * Application property that carries the id of the connection that sent a request. The
     * broker's AMQP layer ({@code AmqpReplyRouting}) sets it and filters on it. That class is built
     * into the patched AMQP protocol jar, so the name is duplicated here.
     */
    static final String CONNECTION_ID_PROPERTY = "floci_connection_id";

    /** Every CBS and management request names its operation. A reply never does. */
    private static final String OPERATION = "operation";

    /** The AMQP management status for an operation a node does not implement. */
    public static final int NOT_IMPLEMENTED = 501;

    /**
     * The filter of the divert that sends requests to a responder: every request, since a request
     * names its operation and a reply never does. The responder answers each one, so no request
     * goes unanswered, and its own replies, which go back to the same address, do not loop back.
     */
    public static final String REQUEST_FILTER = OPERATION + " IS NOT NULL";

    private AmqpManagementReply() {
    }

    /**
     * The reply to {@code request}: correlated to it by its message id, with the status in the
     * properties the AMQP management specification names, and with the stamp of the requesting
     * connection.
     */
    public static Message to(Message request, int statusCode, String statusDescription) {
        Message reply = Message.Factory.create();
        reply.setCorrelationId(request.getMessageId());
        Map<String, Object> properties = new HashMap<>();
        properties.put("status-code", statusCode);
        properties.put("status-description", statusDescription);
        copyConnectionId(request, properties);
        reply.setApplicationProperties(new ApplicationProperties(properties));
        return reply;
    }

    /** The reply to an operation floci-az does not implement: 501, naming the operation. */
    public static Message notImplemented(Message request, String operation) {
        return to(request, NOT_IMPLEMENTED, "floci-az does not implement the operation '" + operation + "'");
    }

    /** The operation a request names, or null if it names none. */
    public static String operation(Message request) {
        ApplicationProperties props = request.getApplicationProperties();
        Object operation = props == null ? null : props.getValue().get(OPERATION);
        return operation == null ? null : operation.toString();
    }

    /**
     * Encodes a reply. The buffer grows until the reply fits: a reply names the operation and the
     * correlation id the client chose, so its size is not fixed.
     */
    public static byte[] encode(Message message) {
        int capacity = 1_024;
        while (true) {
            byte[] buffer = new byte[capacity];
            try {
                int length = message.encode(buffer, 0, buffer.length);
                return Arrays.copyOf(buffer, length);
            } catch (BufferOverflowException tooSmall) {
                capacity *= 2;
            }
        }
    }

    /**
     * Copies the id of the requesting connection onto the application properties of a reply. A
     * reply without it reaches no receiver.
     */
    private static void copyConnectionId(Message request, Map<String, Object> replyProperties) {
        ApplicationProperties props = request.getApplicationProperties();
        Object connectionId = props == null ? null : props.getValue().get(CONNECTION_ID_PROPERTY);
        if (connectionId != null) {
            replyProperties.put(CONNECTION_ID_PROPERTY, connectionId);
        }
    }
}
