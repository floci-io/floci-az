package io.floci.az.core;

import io.floci.az.services.servicebus.ArtemisPatchVersions;
import io.floci.az.services.servicebus.ServiceBusNamespaceManager;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.message.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The broker stamps each CBS and management request with the id of the connection that sent it,
 * and a client receives only the replies that carry its own id back. A reply that drops the stamp
 * reaches nobody. Every reply is correlated to its request and carries the status as the AMQP
 * management specification names it.
 */
class AmqpManagementReplyTest {

    @Test
    @DisplayName("a reply is correlated to its request and carries the id of the requesting connection back")
    void copiesTheConnectionId() {
        Message reply = AmqpManagementReply.to(request(Map.of(
                "operation", "put-token",
                AmqpManagementReply.CONNECTION_ID_PROPERTY, "requesting-connection")), 202, "Accepted");

        assertEquals("request-1", reply.getCorrelationId());
        assertEquals(Map.of("status-code", 202, "status-description", "Accepted",
                AmqpManagementReply.CONNECTION_ID_PROPERTY, "requesting-connection"), properties(reply));
    }

    @Test
    @DisplayName("a request without a stamp adds nothing to the reply")
    void copiesNothingWithoutAStamp() {
        Message withoutProperties = Message.Factory.create();
        Message withoutStamp = request(Map.of("operation", "put-token"));

        for (Message request : new Message[]{withoutProperties, withoutStamp}) {
            assertEquals(Map.of("status-code", 202, "status-description", "Accepted"),
                    properties(AmqpManagementReply.to(request, 202, "Accepted")));
        }
    }

    @Test
    @DisplayName("an operation floci-az does not implement gets 501, which names the operation")
    void namesTheOperationItDoesNotImplement() {
        Message request = request(Map.of(
                "operation", "delete-token", AmqpManagementReply.CONNECTION_ID_PROPERTY, "requesting-connection"));

        Message reply = AmqpManagementReply.notImplemented(request, AmqpManagementReply.operation(request));

        assertEquals("request-1", reply.getCorrelationId());
        assertEquals(Map.of("status-code", 501,
                "status-description", "floci-az does not implement the operation 'delete-token'",
                AmqpManagementReply.CONNECTION_ID_PROPERTY, "requesting-connection"), properties(reply));
    }

    /** The reply names the operation the client chose, so it can outgrow any fixed buffer. */
    @Test
    @DisplayName("a reply that names a long operation still encodes whole")
    void encodesALongReply() {
        String operation = "x".repeat(5_000);

        byte[] encoded = AmqpManagementReply.encode(
                AmqpManagementReply.notImplemented(request(Map.of("operation", operation)), operation));
        Message decoded = Message.Factory.create();
        decoded.decode(encoded, 0, encoded.length);

        assertEquals("floci-az does not implement the operation '" + operation + "'",
                properties(decoded).get("status-description"));
    }

    /**
     * The property is set and filtered on in the patched AMQP jar and copied here and in the
     * broker plugin, so its name is spelled in three jars. A spelling that drifts breaks no build:
     * the reply is delivered to no one, and the client waits until it times out.
     */
    @Test
    @DisplayName("the broker and every responder name the connection id property alike")
    void everyJarNamesThePropertyAlike() throws Exception {
        try (URLClassLoader patch = jarLoader(ArtemisPatchVersions.ARTEMIS_AMQP_PATCH_RESOURCE);
             URLClassLoader plugin = jarLoader(ServiceBusNamespaceManager.ARTEMIS_EXTENSION_RESOURCE)) {
            assertEquals(AmqpManagementReply.CONNECTION_ID_PROPERTY, constant(patch,
                    "org.apache.activemq.artemis.protocol.amqp.proton.AmqpReplyRouting",
                    "CONNECTION_ID_PROPERTY"));
            assertEquals(AmqpManagementReply.CONNECTION_ID_PROPERTY, constant(plugin,
                    "io.floci.az.artemis.ServiceBusManagementPlugin", "CONNECTION_ID_PROPERTY"));
        }
    }

    private static Message request(Map<String, Object> applicationProperties) {
        Message request = Message.Factory.create();
        request.setMessageId("request-1");
        request.setApplicationProperties(new ApplicationProperties(applicationProperties));
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Message reply) {
        return reply.getApplicationProperties().getValue();
    }

    private URLClassLoader jarLoader(String resource) {
        URL jar = getClass().getResource(resource);
        assertNotNull(jar, "Jar not found: " + resource + "; the build writes it, so run the build");
        return new URLClassLoader(new URL[]{jar}, getClass().getClassLoader());
    }

    private static Object constant(ClassLoader loader, String className, String fieldName) throws Exception {
        Field field = Class.forName(className, true, loader).getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }
}
