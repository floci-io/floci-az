package io.floci.az.services.eventhub;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.filter.Filter;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPStandardMessage;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A read never removes an event; its hub's retention does. The retention plugin deletes, from each
 * consumer group queue, the events enqueued longer ago than that queue's retention, and no other.
 */
class EventHubRetentionPluginTest {

    @Test
    @DisplayName("a sweep deletes the events older than the queue's retention, and keeps the newer ones")
    void deletesEventsOlderThanTheRetention() throws Exception {
        Queue queue = mock(Queue.class);
        Queue other = mock(Queue.class);
        ActiveMQServer server = mock(ActiveMQServer.class);
        when(server.locateQueue(SimpleString.of("eh1/ConsumerGroups/$Default/Partitions/0"))).thenReturn(queue);
        when(server.locateQueue(SimpleString.of("unlisted"))).thenReturn(other);

        sweep(Map.of("retention", "eh1/ConsumerGroups/$Default/Partitions/0=60000"), server);

        ArgumentCaptor<Filter> deleted = ArgumentCaptor.forClass(Filter.class);
        verify(queue).deleteMatchingReferences(deleted.capture());
        verify(other, never()).deleteMatchingReferences(any(Filter.class));
        long now = System.currentTimeMillis();
        assertTrue(deleted.getValue().match(event(now - 120_000)), "an event two minutes old was kept");
        assertFalse(deleted.getValue().match(event(now - 1_000)), "an event one second old was deleted");
    }

    /** Runs one sweep of a plugin loaded from the broker extension jar, as the broker loads it. */
    private void sweep(Map<String, String> properties, ActiveMQServer server) throws Exception {
        URL jar = getClass().getResource(EventHubNamespaceManager.ARTEMIS_EXTENSION_RESOURCE);
        assertNotNull(jar, "Jar not found: " + EventHubNamespaceManager.ARTEMIS_EXTENSION_RESOURCE
                + "; the build writes it, so run the build");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar}, getClass().getClassLoader())) {
            Class<?> type = Class.forName("io.floci.az.artemis.EventHubRetentionPlugin", true, loader);
            ActiveMQServerPlugin plugin = (ActiveMQServerPlugin) type.getDeclaredConstructor().newInstance();
            plugin.init(properties);
            Method sweep = type.getDeclaredMethod("sweep", ActiveMQServer.class);
            sweep.setAccessible(true);
            sweep.invoke(plugin, server);
        }
    }

    /** An event stamped with its enqueued time, which the filter reads like any other property. */
    private static AMQPStandardMessage event(long enqueuedTime) {
        // Proton's Message: Artemis has a Message type of its own.
        org.apache.qpid.proton.message.Message message = Proton.message();
        message.setApplicationProperties(new ApplicationProperties(Map.of("floci_enqueued_time", enqueuedTime)));
        byte[] buffer = new byte[1024];
        int size = message.encode(buffer, 0, buffer.length);
        return new AMQPStandardMessage(0, Arrays.copyOf(buffer, size), null);
    }
}
