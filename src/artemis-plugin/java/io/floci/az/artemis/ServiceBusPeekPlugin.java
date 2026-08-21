package io.floci.az.artemis;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.RoutingContext;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPMessage;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPStandardMessage;
import org.apache.activemq.artemis.utils.collections.LinkedListIterator;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.UnsignedInteger;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.amqp.messaging.Header;
import org.apache.qpid.proton.amqp.messaging.MessageAnnotations;
import org.apache.qpid.proton.amqp.messaging.Properties;

import java.nio.BufferOverflowException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Implements Azure Service Bus non-destructive peek requests on entity management nodes. */
public final class ServiceBusPeekPlugin implements ActiveMQServerPlugin {

    private static final System.Logger LOG =
            System.getLogger(ServiceBusPeekPlugin.class.getName());
    private static final String MANAGEMENT_SUFFIX = "/$management";
    private static final String PEEK_OPERATION = "com.microsoft:peek-message";
    private static final String OPERATION = "operation";
    private static final String FROM_SEQUENCE_NUMBER = "from-sequence-number";
    private static final String MESSAGE_COUNT = "message-count";
    private static final String SESSION_ID = "session-id";
    private static final String OMIT_MESSAGE_BODY = "omit-message-body";
    private static final String MESSAGES = "messages";
    private static final String MESSAGE = "message";
    private static final String STATUS_CODE = "statusCode";
    private static final String STATUS_DESCRIPTION = "statusDescription";
    private static final int MAX_MESSAGE_COUNT = 250;
    private static final int MAX_RESPONSE_PAYLOAD_BYTES = 1_048_576;
    private static final Symbol SEQUENCE_NUMBER = Symbol.valueOf("x-opt-sequence-number");
    private static final Symbol ENQUEUED_TIME = Symbol.valueOf("x-opt-enqueued-time");

    private volatile ActiveMQServer server;

    @Override
    public void registered(ActiveMQServer registeredServer) {
        server = registeredServer;
    }

    @Override
    public void unregistered(ActiveMQServer unregisteredServer) {
        server = null;
    }

    @Override
    public void beforeMessageRoute(
            Message message, RoutingContext context, boolean direct, boolean rejectDuplicates) {
        if (!(message instanceof AMQPMessage request) || !isPeekRequest(request)) {
            return;
        }

        // The multicast management address lets Artemis create one response subscription per
        // client. Peek requests are handled here and must never reach those subscriptions.
        context.clear();
        try {
            sendPeekResponse(request);
        } catch (IllegalArgumentException e) {
            LOG.log(System.Logger.Level.WARNING, "Invalid Service Bus peek request", e);
            sendErrorResponse(request, 400, "Bad Request");
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "Could not process Service Bus peek request", e);
            sendErrorResponse(request, 500, "Internal Server Error");
        }
    }

    private void sendErrorResponse(AMQPMessage request, int statusCode, String description) {
        try {
            sendResponse(request, statusCode, description, Map.of());
        } catch (Exception responseError) {
            LOG.log(System.Logger.Level.ERROR,
                    "Could not send Service Bus peek error response", responseError);
        }
    }

    private static boolean isPeekRequest(AMQPMessage request) {
        String address = request.getAddress();
        if (address == null || !address.endsWith(MANAGEMENT_SUFFIX)) {
            return false;
        }
        ApplicationProperties applicationProperties = request.getApplicationProperties();
        return applicationProperties != null
                && PEEK_OPERATION.equals(applicationProperties.getValue().get(OPERATION));
    }

    private void sendPeekResponse(AMQPMessage request) throws Exception {
        Map<?, ?> body = requestBody(request);
        long fromSequenceNumber = number(body, FROM_SEQUENCE_NUMBER).longValue();
        int requestedMessageCount = number(body, MESSAGE_COUNT).intValue();
        if (fromSequenceNumber < 0 || requestedMessageCount <= 0) {
            sendResponse(request, 400, "Bad Request", Map.of());
            return;
        }
        int messageCount = Math.min(requestedMessageCount, MAX_MESSAGE_COUNT);

        String entityPath = normalizeEntityPath(
                request.getAddress().substring(0,
                        request.getAddress().length() - MANAGEMENT_SUFFIX.length()));
        ActiveMQServer activeServer = requireServer();
        Queue queue = activeServer.locateQueue(SimpleString.of(entityPath));
        if (queue == null) {
            sendResponse(request, 404, "Not Found", Map.of());
            return;
        }

        String sessionId = body.get(SESSION_ID) instanceof String value ? value : null;
        boolean omitMessageBody = Boolean.TRUE.equals(body.get(OMIT_MESSAGE_BODY));
        List<Map<String, Object>> messages = browse(
                queue, fromSequenceNumber, messageCount, sessionId, omitMessageBody);
        if (messages.isEmpty()) {
            sendResponse(request, 204, "No Content", Map.of());
            return;
        }
        sendResponse(request, 200, "OK", Map.of(MESSAGES, messages));
    }

    private static Map<?, ?> requestBody(AMQPMessage request) {
        if (request.getBody() instanceof AmqpValue value && value.getValue() instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException("Peek request body must be an AMQP map");
    }

    private static Number number(Map<?, ?> body, String key) {
        Object value = body.get(key);
        if (value instanceof Number number) {
            return number;
        }
        throw new IllegalArgumentException("Peek request is missing numeric '" + key + "'");
    }

    private static List<Map<String, Object>> browse(
            Queue queue,
            long fromSequenceNumber,
            int messageCount,
            String sessionId,
            boolean omitMessageBody) {
        List<Map<String, Object>> messages = new ArrayList<>(messageCount);
        int responsePayloadBytes = 0;
        try (LinkedListIterator<MessageReference> iterator = queue.browserIterator()) {
            while (iterator.hasNext() && messages.size() < messageCount) {
                MessageReference reference = iterator.next();
                if (reference.getMessageID() < fromSequenceNumber
                        || !(reference.getMessage() instanceof AMQPMessage source)
                        || !matchesSession(source, sessionId)) {
                    continue;
                }
                byte[] encodedMessage = encodePeekedMessage(source, reference, omitMessageBody);
                if (!messages.isEmpty()
                        && encodedMessage.length > MAX_RESPONSE_PAYLOAD_BYTES - responsePayloadBytes) {
                    break;
                }
                messages.add(Map.of(MESSAGE, new Binary(encodedMessage)));
                responsePayloadBytes += encodedMessage.length;
            }
        }
        return messages;
    }

    private static boolean matchesSession(AMQPMessage source, String sessionId) {
        if (sessionId == null) {
            return true;
        }
        Properties properties = source.getProperties();
        return properties != null && sessionId.equals(properties.getGroupId());
    }

    private static byte[] encodePeekedMessage(
            AMQPMessage source, MessageReference reference, boolean omitMessageBody) {
        org.apache.qpid.proton.message.Message peeked = Proton.message();
        peeked.setHeader(peekHeader(source, reference));
        peeked.setProperties(source.getProperties());
        peeked.setApplicationProperties(source.getApplicationProperties());
        peeked.setFooter(source.getFooter());
        if (!omitMessageBody) {
            peeked.setBody(source.getBody());
        }

        MessageAnnotations sourceAnnotations = source.getMessageAnnotations();
        Map<Symbol, Object> annotations = sourceAnnotations == null
                ? new HashMap<>()
                : new HashMap<>(sourceAnnotations.getValue());
        annotations.put(SEQUENCE_NUMBER, reference.getMessageID());
        annotations.put(ENQUEUED_TIME, new Date(enqueuedTime(source)));
        peeked.setMessageAnnotations(new MessageAnnotations(annotations));

        int capacity = Math.max(1_024, source.getEncodeSize() + 256);
        while (true) {
            byte[] encoded = new byte[capacity];
            try {
                int length = peeked.encode(encoded, 0, encoded.length);
                if (length == encoded.length) {
                    return encoded;
                }
                byte[] exact = new byte[length];
                System.arraycopy(encoded, 0, exact, 0, length);
                return exact;
            } catch (BufferOverflowException e) {
                capacity = Math.multiplyExact(capacity, 2);
            }
        }
    }

    private static Header peekHeader(AMQPMessage source, MessageReference reference) {
        Header sourceHeader = source.getHeader();
        Header header = sourceHeader == null ? new Header() : new Header(sourceHeader);
        header.setDeliveryCount(UnsignedInteger.valueOf(reference.getDeliveryCount()));
        return header;
    }

    private static long enqueuedTime(AMQPMessage source) {
        Long ingressTimestamp = source.getIngressTimestamp();
        if (ingressTimestamp != null) {
            return ingressTimestamp;
        }
        long creationTime = source.getTimestamp();
        if (creationTime > 0) {
            return creationTime;
        }
        throw new IllegalStateException("Service Bus message is missing an ingress timestamp");
    }

    private void sendResponse(
            AMQPMessage request, int statusCode, String description, Map<String, Object> body)
            throws Exception {
        Properties requestProperties = request.getProperties();
        if (requestProperties == null || requestProperties.getMessageId() == null) {
            throw new IllegalArgumentException("Peek request is missing message-id");
        }

        Properties responseProperties = new Properties();
        responseProperties.setCorrelationId(requestProperties.getMessageId());
        Map<String, Object> applicationProperties = new HashMap<>();
        applicationProperties.put(STATUS_CODE, statusCode);
        applicationProperties.put(STATUS_DESCRIPTION, description);

        ActiveMQServer activeServer = requireServer();
        AMQPStandardMessage response = AMQPStandardMessage.createMessage(
                activeServer.getStorageManager().generateID(),
                0,
                null,
                null,
                responseProperties,
                null,
                null,
                applicationProperties,
                null,
                body.isEmpty() ? null : new AmqpValue(body));
        // Every client receives the response; correlation-id selects the requesting client.
        response.setAddress(request.getAddress());
        activeServer.getPostOffice().route(response, false);
    }

    private ActiveMQServer requireServer() {
        ActiveMQServer activeServer = server;
        if (activeServer == null) {
            throw new IllegalStateException("Service Bus peek plugin is not registered");
        }
        return activeServer;
    }

    private static String normalizeEntityPath(String entityPath) {
        String lowerCasePath = entityPath.toLowerCase(Locale.ROOT);
        String subscriptionSegment = "/subscriptions/";
        int subscriptionIndex = lowerCasePath.indexOf(subscriptionSegment);
        if (subscriptionIndex < 0) {
            return entityPath;
        }
        return entityPath.substring(0, subscriptionIndex)
                + "/Subscriptions/"
                + entityPath.substring(subscriptionIndex + subscriptionSegment.length());
    }
}
