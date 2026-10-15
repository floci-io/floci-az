package io.floci.az.services.eventhub;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The partition plugin stamps each event with its partition and its position in the partition's
 * stream. Only events: the same broker routes the CBS and management requests and replies, and
 * those must reach their receiver with no partition stamp, as Azure sends them.
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

    /** Every form a producer addresses a hub by, the namespace-carrying ones included. */
    @ParameterizedTest
    @ValueSource(strings = {"eh1", "eh1/Partitions/2", "eh2", "emulatorNs1/eh1", "amqp://localhost/emulatorNs1/eh1"})
    @DisplayName("an event to a hub gets its partition and its stream position")
    void stampsEvents(String address) throws Exception {
        AMQPMessage event = send(address, Map.of("probe", "event"));

        assertTrue(applicationProperties(event).keySet().containsAll(Set.of(
                "floci_partition", "floci_offset", "floci_sequence_number", "floci_enqueued_time")),
                applicationProperties(event).toString());
        assertEquals(Set.of("x-opt-partition-id", "x-opt-offset", "x-opt-sequence-number", "x-opt-enqueued-time"),
                annotations(event));
    }

    /**
     * The CBS and management nodes and their intercept addresses, a Service Bus style entity
     * management node, and an address that names no hub at all.
     */
    @ParameterizedTest
    @ValueSource(strings = {"$cbs", "$management", "$cbs-intercept", "$management-intercept", "eh1/$management", "eh9"})
    @DisplayName("a message to any other address gets no partition stamp")
    void leavesEverythingElseAlone(String address) throws Exception {
        AMQPMessage reply = send(address, Map.of("status-code", 202, "status-description", "Accepted"));

        assertEquals(Map.of("status-code", 202, "status-description", "Accepted"), applicationProperties(reply));
        assertEquals(Set.of(), annotations(reply));
    }

    private static AMQPMessage send(String address, Map<String, Object> applicationProperties) throws Exception {
        // Proton's Message: Artemis has a Message type of its own.
        org.apache.qpid.proton.message.Message message = Proton.message();
        message.setMessageId("message-1");
        message.setApplicationProperties(new ApplicationProperties(applicationProperties));
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

    private static Set<String> annotations(AMQPMessage message) {
        MessageAnnotations annotations = onTheWire(message).getMessageAnnotations();
        return annotations == null ? Set.of()
                : annotations.getValue().keySet().stream().map(Symbol::toString).collect(Collectors.toSet());
    }
}
