package io.floci.az.services.eventhub;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.filter.Filter;
import org.apache.activemq.artemis.core.persistence.impl.nullpm.NullStorageManager;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.core.server.impl.AckReason;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;
import org.apache.activemq.artemis.core.transaction.Transaction;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPStandardMessage;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.message.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A read never removes an event; its hub's retention does. The retention plugin deletes, from each
 * consumer group queue, the events enqueued longer ago than that queue's retention, and no other.
 * A queue holds its events in enqueued order, so a sweep deletes from the head and stops at the
 * first event still in retention.
 */
class EventHubRetentionPluginTest {

    private static final String QUEUE = "eh1/ConsumerGroups/$Default/Partitions/0";

    @Test
    @DisplayName("a sweep deletes the expired events at the head of the queue, and stops at the first one still in retention")
    void deletesTheExpiredHeadAndStopsAtTheFirstEventInRetention() throws Exception {
        long now = System.currentTimeMillis();
        MessageReference oldest = event(1, now - 180_000);
        MessageReference old = event(2, now - 120_000);
        MessageReference fresh = event(3, now - 1_000);
        Queue queue = mock(Queue.class);
        when(queue.peekFirstMessage()).thenReturn(oldest, old, fresh);
        when(queue.removeReferenceWithID(1)).thenReturn(oldest);
        when(queue.removeReferenceWithID(2)).thenReturn(old);
        ActiveMQServer server = server();
        when(server.locateQueue(SimpleString.of(QUEUE))).thenReturn(queue);

        sweep(Map.of("retention", QUEUE + "=60000"), server);

        ArgumentCaptor<Transaction> tx = ArgumentCaptor.forClass(Transaction.class);
        verify(queue).acknowledge(tx.capture(), eq(oldest), eq(AckReason.KILLED), isNull(), eq(false));
        verify(queue).acknowledge(tx.getValue(), old, AckReason.KILLED, null, false);
        assertEquals(Transaction.State.COMMITTED, tx.getValue().getState(), "the deletes were not committed");
        verify(queue, never()).removeReferenceWithID(3);
        verify(queue, times(3)).peekFirstMessage();
        verify(queue, never()).iterator();
        verify(queue, never()).deleteMatchingReferences(any(Filter.class));
    }

    @Test
    @DisplayName("a sweep deletes nothing from a queue whose oldest event is in retention, and leaves an unlisted queue alone")
    void keepsAQueueWhoseOldestEventIsInRetention() throws Exception {
        MessageReference fresh = event(1, System.currentTimeMillis() - 1_000);
        Queue queue = mock(Queue.class);
        when(queue.peekFirstMessage()).thenReturn(fresh);
        Queue unlisted = mock(Queue.class);
        ActiveMQServer server = server();
        when(server.locateQueue(SimpleString.of(QUEUE))).thenReturn(queue);
        when(server.locateQueue(SimpleString.of("unlisted"))).thenReturn(unlisted);

        sweep(Map.of("retention", QUEUE + "=60000"), server);

        verify(queue, never()).removeReferenceWithID(anyLong());
        verify(queue, never()).acknowledge(any(Transaction.class), any(MessageReference.class),
                any(AckReason.class), any(ServerConsumer.class), anyBoolean());
        verify(unlisted, never()).peekFirstMessage();
    }

    private static ActiveMQServer server() {
        ActiveMQServer server = mock(ActiveMQServer.class);
        when(server.getStorageManager()).thenReturn(new NullStorageManager());
        return server;
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

    /** A queued event stamped with its enqueued time, which the filter reads like any other property. */
    private static MessageReference event(long messageId, long enqueuedTime) {
        Message message = Proton.message();
        message.setApplicationProperties(new ApplicationProperties(Map.of("floci_enqueued_time", enqueuedTime)));
        byte[] buffer = new byte[1024];
        int size = message.encode(buffer, 0, buffer.length);
        AMQPStandardMessage event = new AMQPStandardMessage(0, Arrays.copyOf(buffer, size), null);
        event.setMessageID(messageId);
        MessageReference reference = mock(MessageReference.class);
        when(reference.getMessage()).thenReturn(event);
        return reference;
    }
}
