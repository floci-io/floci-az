package io.floci.az.services.servicebus;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.postoffice.RoutingStatus;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.RoutingContext;
import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.core.server.impl.RoutingContextImpl;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPStandardMessage;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.message.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.management.MBeanServerFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A deferred message does not expire on its schedule. Deferral hands its deadline to the plugin,
 * which applies it only when the message is received by sequence number, as Azure does.
 */
class ServiceBusExpiryPluginTest {

    private static final long MESSAGE_ID = 42L;
    private static final String DEAD_LETTER_ADDRESS = "orders/$deadletterqueue";

    private URLClassLoader loader;
    private Class<?> type;
    private ActiveMQServerPlugin plugin;
    private final Queue queue = mock(Queue.class);
    private final ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
    private final ScheduledExecutorService pool = mock(ScheduledExecutorService.class);

    @BeforeEach
    void loadPlugin() throws Exception {
        URL jar = getClass().getResource(ServiceBusNamespaceManager.ARTEMIS_EXTENSION_RESOURCE);
        assertNotNull(jar, "Jar not found: " + ServiceBusNamespaceManager.ARTEMIS_EXTENSION_RESOURCE
                + "; the build writes it, so run the build");
        loader = new URLClassLoader(new URL[]{jar}, getClass().getClassLoader());
        type = Class.forName("io.floci.az.artemis.ServiceBusExpiryPlugin", true, loader);
        plugin = (ActiveMQServerPlugin) type.getDeclaredConstructor().newInstance();

        when(queue.getName()).thenReturn(SimpleString.of("orders"));
        when(queue.moveReference(anyLong(), any(), any(), anyBoolean())).thenReturn(true);
        doReturn(scheduled).when(pool).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        ActiveMQServer server = mock(ActiveMQServer.class);
        when(server.getMBeanServer()).thenReturn(MBeanServerFactory.newMBeanServer());
        when(server.getScheduledPool()).thenReturn(pool);
        when(server.locateQueue(SimpleString.of("orders"))).thenReturn(queue);
        plugin.registered(server);
    }

    @AfterEach
    void closeLoader() throws Exception {
        loader.close();
    }

    @Test
    void aDeferredMessagePastItsTimeToLiveIsExpiredOnlyWhenAskedTo() throws Exception {
        configure(60_000);
        AMQPStandardMessage message = message();
        message.setExpiration(System.currentTimeMillis() - 1_000);
        route(message);

        invoke("defer", "orders", MESSAGE_ID);

        verify(scheduled).cancel(false);
        assertTrue(plugin.canAccept(mock(ServerConsumer.class), reference(message)),
                "a deferred message is not expired when a receiver is offered it");
        verify(queue, never()).moveReference(anyLong(), any(), any(), anyBoolean());

        assertTrue((boolean) invoke("expireIfDue", "orders", MESSAGE_ID));
        verify(queue).moveReference(MESSAGE_ID, SimpleString.of(DEAD_LETTER_ADDRESS), null, false);
        assertFalse((boolean) invoke("expireIfDue", "orders", MESSAGE_ID), "the deadline is applied once");
    }

    @Test
    void aDeferredMessageWithinItsTimeToLiveIsNotExpired() throws Exception {
        configure(60_000);
        route(message());

        invoke("defer", "orders", MESSAGE_ID);

        assertFalse((boolean) invoke("expireIfDue", "orders", MESSAGE_ID));
        verify(queue, never()).moveReference(anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void anExpiryTaskAlreadyRunningWhenTheMessageIsDeferredLeavesItAlone() throws Exception {
        configure(60_000);
        route(message());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(pool).schedule(task.capture(), anyLong(), any(TimeUnit.class));

        invoke("defer", "orders", MESSAGE_ID);
        task.getValue().run();

        verify(queue, never()).moveReference(anyLong(), any(), any(), anyBoolean());
        verify(queue, never()).expireReference(anyLong());
    }

    @Test
    void aMessageThatWasNeverDeferredIsLeftToItsSchedule() throws Exception {
        configure(60_000);
        route(message());

        assertFalse((boolean) invoke("expireIfDue", "orders", MESSAGE_ID));
        verify(scheduled, never()).cancel(anyBoolean());
    }

    @Test
    void aMessageExpiredToTheDeadLetterQueueCarriesTTLExpiredException() throws Exception {
        configure(60_000);
        route(message());
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(pool).schedule(task.capture(), anyLong(), any(TimeUnit.class));
        AMQPStandardMessage copy = deadLetterCopy();
        // Artemis routes the moved copy on the thread that asked for the move.
        doAnswer(call -> {
            plugin.beforeMessageRoute(copy, mock(RoutingContext.class), false, false);
            return true;
        }).when(queue).moveReference(anyLong(), any(), any(), anyBoolean());

        task.getValue().run();

        verify(queue).moveReference(MESSAGE_ID, SimpleString.of(DEAD_LETTER_ADDRESS), null, false);
        assertEquals("TTLExpiredException", copy.getObjectProperty("DeadLetterReason"));
        assertEquals("The message expired and was dead lettered.",
                copy.getObjectProperty("DeadLetterErrorDescription"));
    }

    @Test
    void aMessageRoutedToTheDeadLetterQueueOutsideAnExpiryIsLeftAlone() throws Exception {
        configure(60_000);
        AMQPStandardMessage copy = deadLetterCopy();

        plugin.beforeMessageRoute(copy, mock(RoutingContext.class), false, false);

        assertNull(copy.getObjectProperty("DeadLetterReason"));
        assertNull(copy.getObjectProperty("DeadLetterErrorDescription"));
    }

    @Test
    void divertedMessagesUseTheirOwnAddressForSubscriptionExpiry() throws Exception {
        configure(2_000);
        AMQPStandardMessage message = message();
        message.setAddress("orders");
        when(queue.isDurable()).thenReturn(true);
        RoutingContext context = new RoutingContextImpl(null);
        context.addQueue(SimpleString.of("orders"), queue);
        assertNull(context.getAddress(), "A divert does not set an explicit context address");

        plugin.afterMessageRoute(message, context, false, false, RoutingStatus.OK);

        verify(pool).schedule(any(Runnable.class), eq(2_000L), eq(TimeUnit.MILLISECONDS));
    }

    private void configure(long ttlMillis) throws Exception {
        type.getMethod("configure", String.class, long.class, String.class)
                .invoke(plugin, "orders", ttlMillis, DEAD_LETTER_ADDRESS);
    }

    private Object invoke(String operation, String queueName, long messageId) throws Exception {
        return type.getMethod(operation, String.class, long.class).invoke(plugin, queueName, messageId);
    }

    private void route(AMQPStandardMessage message) throws Exception {
        RoutingContext context = mock(RoutingContext.class);
        when(context.getAddress(message)).thenReturn(SimpleString.of("orders"));
        when(context.getDurableQueues(any())).thenReturn(List.of(queue));
        when(context.getNonDurableQueues(any())).thenReturn(List.of());
        plugin.afterMessageRoute(message, context, false, false, RoutingStatus.OK);
    }

    private MessageReference reference(AMQPStandardMessage message) {
        MessageReference reference = mock(MessageReference.class);
        when(reference.getQueue()).thenReturn(queue);
        when(reference.getMessageID()).thenReturn(MESSAGE_ID);
        when(reference.getMessage()).thenReturn(message);
        return reference;
    }

    private static AMQPStandardMessage deadLetterCopy() {
        AMQPStandardMessage copy = message();
        copy.setAddress(DEAD_LETTER_ADDRESS);
        return copy;
    }

    private static AMQPStandardMessage message() {
        Message encoded = Proton.message();
        encoded.setBody(new AmqpValue("deferred"));
        byte[] buffer = new byte[1024];
        int size = encoded.encode(buffer, 0, buffer.length);
        AMQPStandardMessage message = new AMQPStandardMessage(0, Arrays.copyOf(buffer, size), null);
        message.setMessageID(MESSAGE_ID);
        return message;
    }
}
