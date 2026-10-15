package io.floci.az.services.eventhub;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.filter.impl.FilterImpl;
import org.apache.activemq.artemis.core.server.ServerSession;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPMessage;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPStandardMessage;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.amqp.messaging.MessageAnnotations;
import org.apache.qpid.proton.codec.ReadableBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The partition plugin stamps each event with its partition and its position in the partition's
 * stream. The partition diverts and the start-position selectors filter on those values, so the
 * broker keeps them; Azure sends none of them as application properties, so the client receives
 * them only as the {@code x-opt-*} annotations Azure sends.
 */
class EventHubPartitionPluginTest {

    private static URLClassLoader loader;
    private static ActiveMQServerPlugin plugin;

    @BeforeAll
    static void loadPlugin() throws Exception {
        URL jar = EventHubPartitionPluginTest.class.getResource(EventHubNamespaceManager.ARTEMIS_EXTENSION_RESOURCE);
        assertNotNull(jar, "Jar not found: " + EventHubNamespaceManager.ARTEMIS_EXTENSION_RESOURCE
                + "; the build writes it, so run the build");
        loader = new URLClassLoader(new URL[]{jar}, EventHubPartitionPluginTest.class.getClassLoader());
        plugin = (ActiveMQServerPlugin) Class.forName("io.floci.az.artemis.EventHubPartitionPlugin", true, loader)
                .getDeclaredConstructor().newInstance();
        plugin.init(Map.of("entities", "eh1:4,eh2:1"));
    }

    @AfterAll
    static void close() throws Exception {
        loader.close();
    }

    @Test
    @DisplayName("an event reaches the client with its stream position as annotations and no floci property")
    void keepsTheStampsOffTheWire() throws Exception {
        long sentAt = System.currentTimeMillis();

        AMQPMessage event = send("eh1/Partitions/2", Map.of("testId", "abc"));

        assertEquals(Map.of("testId", "abc"), applicationProperties(event));
        Map<String, Object> annotations = annotations(event);
        long sequence = (Long) annotations.get("x-opt-sequence-number");
        assertEquals("2", annotations.get("x-opt-partition-id"));
        assertEquals(Long.toString(sequence), annotations.get("x-opt-offset"));
        Date enqueuedTime = assertInstanceOf(Date.class, annotations.get("x-opt-enqueued-time"));
        assertTrue(enqueuedTime.getTime() >= sentAt, "enqueued before it was sent: " + enqueuedTime);

        assertEquals("2", brokerProperty(event, "floci_partition"));
        assertEquals(sequence, brokerProperty(event, "floci_offset"));
        assertEquals(sequence, brokerProperty(event, "floci_sequence_number"));
        assertEquals(enqueuedTime.getTime(), brokerProperty(event, "floci_enqueued_time"));
    }

    /**
     * A selector reads the application properties first. A client property of the same name would
     * otherwise steer the event to a partition the plugin did not choose.
     */
    @Test
    @DisplayName("a client property named like a stamp is dropped, so the broker's value decides")
    void dropsAClientPropertyOfTheSameName() throws Exception {
        AMQPMessage event = send("eh1/Partitions/1", Map.of("testId", "abc", "floci_partition", "3"));

        assertEquals(Map.of("testId", "abc"), applicationProperties(event));
        assertTrue(FilterImpl.createFilter("floci_partition = '1'").match(event));
        assertFalse(FilterImpl.createFilter("floci_partition = '3'").match(event));
    }

    private static AMQPMessage send(String address, Map<String, Object> applicationProperties) throws Exception {
        // Proton's Message: Artemis has a Message type of its own.
        org.apache.qpid.proton.message.Message message = Proton.message();
        message.setMessageId("message-1");
        message.setApplicationProperties(new ApplicationProperties(new HashMap<>(applicationProperties)));
        byte[] buffer = new byte[1024];
        int size = message.encode(buffer, 0, buffer.length);
        AMQPStandardMessage amqp = new AMQPStandardMessage(0, Arrays.copyOf(buffer, size), null);
        amqp.setAddress(address);
        plugin.beforeSend(mock(ServerSession.class), null, amqp, false, false);
        return amqp;
    }

    /** The message as its receiver decodes it: from the bytes the broker sends. */
    private static org.apache.qpid.proton.message.Message onTheWire(AMQPMessage message) {
        ReadableBuffer data = message.getSendBuffer(1, null);
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        org.apache.qpid.proton.message.Message decoded = Proton.message();
        decoded.decode(bytes, 0, bytes.length);
        return decoded;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> applicationProperties(AMQPMessage message) {
        return onTheWire(message).getApplicationProperties().getValue();
    }

    private static Map<String, Object> annotations(AMQPMessage message) {
        MessageAnnotations annotations = onTheWire(message).getMessageAnnotations();
        Map<String, Object> byName = new HashMap<>();
        if (annotations != null) {
            annotations.getValue().forEach((Symbol key, Object value) -> byName.put(key.toString(), value));
        }
        return byName;
    }

    /** A property the broker keeps and does not send: a SimpleString value reads as a String. */
    private static Object brokerProperty(AMQPMessage message, String name) {
        Object value = message.getExtraProperties().getProperty(SimpleString.of(name));
        return value instanceof SimpleString string ? string.toString() : value;
    }
}
