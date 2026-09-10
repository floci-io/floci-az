package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPSessionCallback;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.engine.Delivery;
import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ServiceBusMessageLockSupportTest {
    @Test
    void expiryReleasesMessageAndRejectsStaleSettlement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.track(false);
            fixture.expiry.get().run();
            verify(fixture.session).cancel(fixture.consumer, fixture.reference.getMessage(), true);
            assertFalse(fixture.settle());
            verify(fixture.delivery).disposition(argThat(state -> state instanceof Rejected rejected
                    && "com.microsoft:message-lock-lost".equals(rejected.getError().getCondition().toString())));
            verify(fixture.delivery).settle();
        }
    }

    @Test
    void successfulSettlementCancelsExpiry() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.track(false);
            when(fixture.delivery.getRemoteState()).thenReturn(Accepted.getInstance());
            assertTrue(fixture.settle());
            fixture.expiry.get().run();
            verify(fixture.timer).cancel(false);
            verifyNoInteractions(fixture.session);
        }
    }

    @Test
    void closingReceiverCancelsExpiry() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.track(false);
            fixture.type.getMethod("close").invoke(fixture.support);
            fixture.expiry.get().run();
            verify(fixture.timer).cancel(false);
            verifyNoInteractions(fixture.session);
        }
    }

    @Test
    void receiveAndDeleteAndSessionReceiversHaveNoMessageTimer() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.track(true);
            assertNull(fixture.expiry.get());
            when(fixture.consumer.getQueue().getUser()).thenReturn(SimpleString.of("floci-az:servicebus-session:60"));
            fixture.track(false);
            assertNull(fixture.expiry.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final URLClassLoader loader = PatchJarLoader.open();
        final Class<?> type = Class.forName(
                "org.apache.activemq.artemis.protocol.amqp.proton.ServiceBusMessageLockSupport", true, loader);
        final AMQPConnectionContext connection = mock(AMQPConnectionContext.class, RETURNS_DEEP_STUBS);
        final AMQPSessionCallback session = mock(AMQPSessionCallback.class);
        final ServerConsumer consumer = mock(ServerConsumer.class, RETURNS_DEEP_STUBS);
        final MessageReference reference = mock(MessageReference.class, RETURNS_DEEP_STUBS);
        final Delivery delivery = mock(Delivery.class);
        final ScheduledFuture<?> timer = mock(ScheduledFuture.class);
        final AtomicReference<Runnable> expiry = new AtomicReference<>();
        final Object support;

        Fixture() throws Exception {
            support = type.getConstructor(AMQPConnectionContext.class, AMQPSessionCallback.class)
                    .newInstance(connection, session);
            when(consumer.getQueue().getUser()).thenReturn(SimpleString.of("floci-az:servicebus-peeklock:60"));
            var scheduler = connection.getProtocolManager().getServer().getScheduledPool();
            doAnswer(call -> {
                expiry.set(call.getArgument(0));
                return timer;
            }).when(scheduler)
                    .schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
            doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                    .when(connection).runLater(any(Runnable.class));
            AtomicReference<Object> context = new AtomicReference<>(reference);
            when(delivery.getContext()).thenAnswer(call -> context.get());
            doAnswer(call -> { context.set(call.getArgument(0)); return null; })
                    .when(delivery).setContext(any());
        }

        void track(boolean preSettled) throws Exception {
            type.getMethod("track", Delivery.class, MessageReference.class, ServerConsumer.class, boolean.class)
                    .invoke(support, delivery, reference, consumer, preSettled);
        }

        boolean settle() throws Exception {
            return (boolean) type.getMethod("beforeSettlement", Delivery.class).invoke(support, delivery);
        }

        @Override
        public void close() throws Exception {
            loader.close();
        }
    }
}
