package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.Consumer;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.protocol.amqp.exceptions.ActiveMQAMQPException;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.engine.EndpointState;
import org.apache.qpid.proton.engine.Sender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Azure's rules for the receivers of one partition in one consumer group. The receiver with the
 * highest epoch owns the partition: it disconnects the receivers with a lower or equal epoch and
 * those without one, and refuses the ones that come after it with a lower epoch or none, all with
 * {@code amqp:link:stolen}. Without an owner, five receivers read the partition and the sixth is
 * refused with {@code amqp:resource-limit-exceeded}. A Service Bus queue keeps no such rules.
 */
class EventHubReceiversTest {

    private static final SimpleString PARTITION = SimpleString.of("eh1/ConsumerGroups/$Default/Partitions/0");
    private static final Symbol EPOCH = Symbol.valueOf("com.microsoft:epoch");

    private static URLClassLoader loader;
    private static Method attach;

    @BeforeAll
    static void loadFromPatchJar() throws Exception {
        loader = PatchJarLoader.open();
        Class<?> receivers = Class.forName(
                "org.apache.activemq.artemis.protocol.amqp.proton.EventHubReceivers", true, loader);
        attach = receivers.getMethod("attach", ActiveMQServer.class, SimpleString.class, Sender.class, Callable.class);
    }

    @AfterAll
    static void close() throws Exception {
        loader.close();
    }

    @Test
    @DisplayName("a partition of a consumer group takes five receivers and refuses the sixth")
    void refusesTheSixthReceiver() throws Exception {
        Partition partition = new Partition(true);
        for (int i = 0; i < 5; i++) {
            partition.attach("receiver-" + i, null);
        }

        ActiveMQAMQPException refused = assertThrows(ActiveMQAMQPException.class,
                () -> partition.attach("receiver-5", null));

        assertEquals("amqp:resource-limit-exceeded", refused.getAmqpError().toString());
        assertEquals(5, partition.consumers.size(), "the refused receiver got a consumer");
    }

    @Test
    @DisplayName("a receiver whose link closed no longer takes a place")
    void aClosedReceiverFreesItsPlace() throws Exception {
        Partition partition = new Partition(true);
        Receiver first = partition.attach("receiver-0", null);
        for (int i = 1; i < 5; i++) {
            partition.attach("receiver-" + i, null);
        }

        when(first.link.getRemoteState()).thenReturn(EndpointState.CLOSED);

        partition.attach("receiver-5", null);
    }

    @Test
    @DisplayName("a receiver with a higher epoch disconnects the current one")
    void aHigherEpochDisconnectsTheLowerOne() throws Exception {
        Partition partition = new Partition(true);
        Receiver current = partition.attach("current", 1L);

        Receiver owner = partition.attach("owner", 2L);

        assertEquals("amqp:link:stolen", current.disconnectedWith.getCondition().toString());
        assertEquals("New receiver 'owner' with higher epoch of '2' is created hence current receiver 'current'"
                + " with epoch '1' is getting disconnected. If you are recreating the receiver, make sure a higher"
                + " epoch is used.", current.disconnectedWith.getDescription());
        assertNull(owner.disconnectedWith);
    }

    @Test
    @DisplayName("a receiver with an equal epoch disconnects the current one")
    void anEqualEpochDisconnectsTheCurrentOne() throws Exception {
        Partition partition = new Partition(true);
        Receiver current = partition.attach("current", 3L);

        partition.attach("owner", 3L);

        assertEquals("amqp:link:stolen", current.disconnectedWith.getCondition().toString());
    }

    @Test
    @DisplayName("a receiver with an epoch disconnects the receivers without one")
    void anEpochDisconnectsTheReceiversWithoutOne() throws Exception {
        Partition partition = new Partition(true);
        List<Receiver> others = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            others.add(partition.attach("receiver-" + i, null));
        }

        partition.attach("owner", 0L);

        for (Receiver other : others) {
            assertEquals("amqp:link:stolen", other.disconnectedWith.getCondition().toString(), other.link.getName());
        }
    }

    @Test
    @DisplayName("a receiver with a lower epoch than the owner's is refused")
    void aLowerEpochIsRefused() throws Exception {
        Partition partition = new Partition(true);
        Receiver owner = partition.attach("owner", 2L);

        ActiveMQAMQPException refused = assertThrows(ActiveMQAMQPException.class, () -> partition.attach("late", 1L));

        assertEquals("amqp:link:stolen", refused.getAmqpError().toString());
        assertEquals("Receiver 'owner' with a higher epoch '2' already exists. Receiver 'late' with epoch 1 cannot be"
                + " created. Make sure you are creating receiver with increasing epoch value to ensure connectivity,"
                + " or ensure all old epoch receivers are closed or disconnected.", refused.getMessage());
        assertNull(owner.disconnectedWith, "the owner was disconnected");
        assertEquals(1, partition.consumers.size(), "the refused receiver got a consumer");
    }

    @Test
    @DisplayName("a receiver without an epoch is refused while the partition has an owner")
    void aReceiverWithoutEpochIsRefusedWhileAnOwnerHolds() throws Exception {
        Partition partition = new Partition(true);
        Receiver owner = partition.attach("owner", 7L);

        ActiveMQAMQPException refused = assertThrows(ActiveMQAMQPException.class,
                () -> partition.attach("plain", null));

        assertEquals("amqp:link:stolen", refused.getAmqpError().toString());
        assertEquals("At least one receiver for the endpoint is created with epoch of '7', and so non-epoch receiver"
                + " is not allowed. Either reconnect with a higher epoch, or make sure all epoch receivers are"
                + " closed or disconnected.", refused.getMessage());
        assertNull(owner.disconnectedWith, "the owner was disconnected");
    }

    @Test
    @DisplayName("a destructive queue, as Service Bus has, keeps no rules")
    void leavesOtherQueuesAlone() throws Exception {
        Partition queue = new Partition(false);
        Receiver first = queue.attach("receiver-0", 1L);

        for (int i = 1; i < 10; i++) {
            queue.attach("receiver-" + i, null);
        }
        queue.attach("receiver-10", 0L);

        assertNull(first.disconnectedWith);
    }

    /** An attached receiver: its link, and the error it was disconnected with, if any. */
    private static final class Receiver {

        final Sender link;
        ErrorCondition disconnectedWith;

        Receiver(Sender link) {
            this.link = link;
        }
    }

    /** A queue on a broker of its own, whose consumers are the receivers it admitted. */
    private static final class Partition {

        final List<Consumer> consumers = new ArrayList<>();
        final ActiveMQServer server = mock(ActiveMQServer.class);

        Partition(boolean nonDestructive) {
            Queue queue = mock(Queue.class);
            when(queue.isNonDestructive()).thenReturn(nonDestructive);
            when(queue.getConsumers()).thenAnswer(call -> List.copyOf(consumers));
            when(server.locateQueue(PARTITION)).thenReturn(queue);
        }

        /** Attaches a receiver with {@code epoch}, or none, which becomes a consumer once admitted. */
        Receiver attach(String name, Long epoch) throws Exception {
            Sender link = mock(Sender.class);
            when(link.getName()).thenReturn(name);
            when(link.getLocalState()).thenReturn(EndpointState.ACTIVE);
            when(link.getRemoteState()).thenReturn(EndpointState.ACTIVE);
            Map<Symbol, Object> properties = new HashMap<>();
            if (epoch != null) {
                properties.put(EPOCH, epoch);
            }
            when(link.getRemoteProperties()).thenReturn(properties);
            Receiver receiver = new Receiver(link);

            // A disconnect runs on the receiver's connection, here at once, and closes its link.
            AMQPConnectionContext connection = mock(AMQPConnectionContext.class);
            doAnswer(call -> {
                call.<Runnable>getArgument(0).run();
                return null;
            }).when(connection).runNow(any(Runnable.class));
            AMQPSessionContext session = mock(AMQPSessionContext.class);
            when(session.getAMQPConnectionContext()).thenReturn(connection);
            ProtonServerSenderContext context = mock(ProtonServerSenderContext.class);
            when(context.getSender()).thenReturn(link);
            when(context.getSessionContext()).thenReturn(session);
            doAnswer(call -> {
                receiver.disconnectedWith = call.getArgument(0);
                when(link.getLocalState()).thenReturn(EndpointState.CLOSED);
                return null;
            }).when(context).close(any(ErrorCondition.class));

            ServerConsumer consumer = mock(ServerConsumer.class);
            when(consumer.getProtocolContext()).thenReturn(context);
            Callable<ServerConsumer> create = () -> {
                consumers.add(consumer);
                return consumer;
            };
            try {
                attach.invoke(null, server, PARTITION, link, create);
            } catch (InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
            return receiver;
        }
    }
}
