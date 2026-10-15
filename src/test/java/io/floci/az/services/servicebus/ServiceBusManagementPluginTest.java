package io.floci.az.services.servicebus;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.filter.impl.FilterImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.RoutingContext;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPMessage;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPStandardMessage;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.Properties;
import org.apache.qpid.proton.codec.ReadableBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The broker answers the requests on a Service Bus entity management node itself, in a plugin:
 * peek as Azure does, and any operation floci-az does not implement with 501. The response
 * carries the stamp of the requesting connection, which keeps it to that connection, but Azure
 * sends no stamp, so the client must receive the response without it.
 */
class ServiceBusManagementPluginTest {

    private static final String PROPERTY = "floci_connection_id";

    @Test
    @DisplayName("a peek response reaches only the requesting connection, and without the stamp")
    void peekResponseKeepsTheStampOffTheWire() throws Exception {
        // No queue has that name, so the plugin answers 404, which is a response like any other.
        AMQPMessage response = answer(request("com.microsoft:peek-message",
                Map.of("from-sequence-number", 0L, "message-count", 1)), mock(RoutingContext.class));

        assertEquals(Map.of("statusCode", 404, "statusDescription", "Not Found"), propertiesOnTheWire(response));
        assertTrue(FilterImpl.createFilter(PROPERTY + " = 'requesting-connection'").match(response));
        assertFalse(FilterImpl.createFilter(PROPERTY + " = 'another-connection'").match(response));
    }

    /**
     * An operation nobody answers would leave the client waiting for a reply until it timed out.
     * The request itself must not reach the client either.
     */
    @Test
    @DisplayName("any other operation gets 501, which names the operation, and only the requesting connection receives it")
    void otherOperationsAreNotImplemented() throws Exception {
        RoutingContext context = mock(RoutingContext.class);

        AMQPMessage response = answer(request("com.microsoft:schedule-message", Map.of()), context);

        verify(context).clear();
        assertEquals("request-1", response.getProperties().getCorrelationId());
        assertEquals(Map.of("statusCode", 501,
                "statusDescription", "floci-az does not implement the operation 'com.microsoft:schedule-message'"),
                propertiesOnTheWire(response));
        assertTrue(FilterImpl.createFilter(PROPERTY + " = 'requesting-connection'").match(response));
        assertFalse(FilterImpl.createFilter(PROPERTY + " = 'another-connection'").match(response));
    }

    /** Runs the plugin on one request and returns the response it routes. */
    private AMQPMessage answer(AMQPMessage request, RoutingContext context) throws Exception {
        ActiveMQServer server = mock(ActiveMQServer.class, RETURNS_DEEP_STUBS);
        when(server.locateQueue(any(SimpleString.class))).thenReturn(null);

        try (URLClassLoader loader = jarLoader()) {
            ActiveMQServerPlugin plugin = (ActiveMQServerPlugin) Class.forName(
                    "io.floci.az.artemis.ServiceBusManagementPlugin", true, loader)
                    .getDeclaredConstructor().newInstance();
            plugin.registered(server);

            plugin.beforeMessageRoute(request, context, false, false);
        }

        ArgumentCaptor<Message> routed = ArgumentCaptor.forClass(Message.class);
        verify(server.getPostOffice()).route(routed.capture(), eq(false));
        return (AMQPMessage) routed.getValue();
    }

    /** A request as the broker holds it: the AMQP layer has stamped it already. */
    private static AMQPMessage request(String operation, Map<String, Object> body) {
        Properties properties = new Properties();
        properties.setMessageId("request-1");
        AMQPStandardMessage request = AMQPStandardMessage.createMessage(0, 0, null, null, properties, null, null,
                Map.of("operation", operation, PROPERTY, "requesting-connection"), null, new AmqpValue(body));
        request.setAddress("orders/$management");
        return request;
    }

    /** The application properties as the client reads them: from the bytes the broker sends. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertiesOnTheWire(AMQPMessage message) {
        ReadableBuffer data = message.getSendBuffer(1, null);
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        // Proton's Message: the Artemis Message this file imports is a different type.
        org.apache.qpid.proton.message.Message decoded = Proton.message();
        decoded.decode(bytes, 0, bytes.length);
        return decoded.getApplicationProperties().getValue();
    }

    private URLClassLoader jarLoader() {
        URL jar = getClass().getResource(ServiceBusNamespaceManager.ARTEMIS_EXTENSION_RESOURCE);
        assertNotNull(jar, "Jar not found: " + ServiceBusNamespaceManager.ARTEMIS_EXTENSION_RESOURCE
                + "; the build writes it, so run the build");
        return new URLClassLoader(new URL[]{jar}, getClass().getClassLoader());
    }
}
