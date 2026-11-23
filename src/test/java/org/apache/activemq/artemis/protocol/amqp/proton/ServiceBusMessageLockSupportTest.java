package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.core.server.impl.AckReason;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPSessionCallback;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.Received;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.engine.Delivery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ServiceBusMessageLockSupportTest {
    @Test
    void renewalReplacesDeadlineAndOldTimerCannotExpireIt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.track(false);
            Runnable oldTimer = fixture.expiry.get();
            long renewedUntil = (long) fixture.type.getMethod("renew", Delivery.class)
                    .invoke(fixture.support, fixture.delivery);
            assertTrue(renewedUntil > System.currentTimeMillis());
            oldTimer.run();
            verifyNoInteractions(fixture.session);
            fixture.expiry.get().run();
            verify(fixture.session).cancel(fixture.consumer, fixture.reference.getMessage(), true);
            assertEquals(0L, fixture.type.getMethod("renew", Delivery.class)
                    .invoke(fixture.support, fixture.delivery));
        }
    }

    @Test
    void completedOrUnknownDeliveryCannotBeRenewed() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertEquals(0L, fixture.type.getMethod("renew", Delivery.class)
                    .invoke(fixture.support, fixture.delivery));
            fixture.track(false);
            when(fixture.delivery.getRemoteState()).thenReturn(Accepted.getInstance());
            fixture.settle();
            assertEquals(0L, fixture.type.getMethod("renew", Delivery.class)
                    .invoke(fixture.support, fixture.delivery));
        }
    }

    @Test
    void failedCancellationClosesConnectionAndRejectsStaleSettlement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var message = fixture.reference.getMessage();
            doThrow(new RuntimeException("temporary broker failure"))
                    .when(fixture.session).cancel(fixture.consumer, message, true);
            fixture.track(false);
            fixture.expiry.get().run();
            assertFalse(fixture.settle());
            verify(fixture.connection).close(any(ErrorCondition.class));
            verify(fixture.connection).destroy();
        }
    }

    @Test
    void intermediateReceivedStateDoesNotCancelExpiry() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var message = fixture.reference.getMessage();
            fixture.track(false);
            when(fixture.delivery.getRemoteState()).thenReturn(new Received());
            assertTrue(fixture.settle());
            fixture.expiry.get().run();
            verify(fixture.session).cancel(fixture.consumer, message, true);
        }
    }
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
    void closingWithoutParkingCancelsExpiry() throws Exception {
        // close() alone is the queue-delete and session-expiry path, close(ErrorCondition), which stays stock.
        try (Fixture fixture = new Fixture()) {
            fixture.track(false);
            fixture.type.getMethod("close").invoke(fixture.support);
            fixture.expiry.get().run();
            verify(fixture.timer).cancel(false);
            verifyNoInteractions(fixture.session);
        }
    }

    @Test
    void closingReceiverParksLockedReferenceUntilDeadline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.track(false);
            Runnable lockTimer = fixture.expiry.get();
            Queue queue = fixture.parkable();

            fixture.park();

            verify(fixture.timer).cancel(false);
            verify(fixture.consumer).removeReferenceByID(fixture.reference.getMessageID());
            verify(fixture.reference, never()).decrementDeliveryCount();
            verifyNoInteractions(fixture.session);
            Runnable release = fixture.expiry.get();
            assertNotSame(lockTimer, release);
            assertTrue(fixture.lastDelay.get() > 0 && fixture.lastDelay.get() <= 60_000, "delay " + fixture.lastDelay);
            verify(queue, never()).cancel(any(MessageReference.class), anyLong());

            release.run();
            verify(queue).cancel(eq(fixture.reference), anyLong());
            verify(queue).deliverAsync();
            verify(fixture.connection, never()).runLater(any(Runnable.class));
        }
    }

    @Test
    void aDeliveryFreedWithItsSessionIsStillParked() throws Exception {
        // A client that ends its session or connection without detaching the link: Artemis frees the proton
        // session before it closes the sender, and freeing settles every delivery locally.
        try (Fixture fixture = new Fixture()) {
            fixture.track(false);
            Queue queue = fixture.parkable();
            when(fixture.delivery.isSettled()).thenReturn(true);

            fixture.park();

            verify(fixture.consumer).removeReferenceByID(fixture.reference.getMessageID());
            verify(queue, never()).cancel(any(MessageReference.class), anyLong());
            fixture.expiry.get().run();
            verify(queue).cancel(eq(fixture.reference), anyLong());
        }
    }

    @Test
    void parkSkipsADeliveryThePeerSettled() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.track(false);
            when(fixture.delivery.remotelySettled()).thenReturn(true);

            fixture.park();

            verify(fixture.consumer, never()).removeReferenceByID(anyLong());
        }
    }

    @Test
    void parkSkipsAReferenceTheConsumerNoLongerHolds() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.track(false);
            Runnable lockTimer = fixture.expiry.get();
            when(fixture.consumer.removeReferenceByID(anyLong())).thenReturn(null);

            fixture.park();

            assertSame(lockTimer, fixture.expiry.get());
            verifyNoInteractions(fixture.session);
        }
    }

    @Test
    void parkReleasesAtOnceWhenTheSchedulerRefusesTheJob() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.track(false);
            Queue queue = fixture.parkable();
            fixture.rejectNextSchedule.set(true);

            fixture.park();

            verify(queue).cancel(eq(fixture.reference), anyLong());
        }
    }

    @Test
    void aFailedReleaseIsRetriedUntilTheReferenceIsBackOnItsQueue() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.track(false);
            Queue queue = fixture.parkable();
            doThrow(new IllegalStateException("journal unavailable")).doNothing()
                    .when(queue).cancel(any(MessageReference.class), anyLong());

            fixture.park();
            Runnable release = fixture.expiry.get();
            release.run();

            Runnable retry = fixture.expiry.get();
            assertNotSame(release, retry, "a failed release schedules a retry");
            verify(queue, never()).deliverAsync();
            retry.run();
            verify(queue, times(2)).cancel(eq(fixture.reference), anyLong());
            verify(queue).deliverAsync();
        }
    }

    @Test
    void releaseRetriesStopAfterTheLastAttempt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.track(false);
            Queue queue = fixture.parkable();
            doThrow(new IllegalStateException("journal unavailable"))
                    .when(queue).cancel(any(MessageReference.class), anyLong());
            Field attemptsField = fixture.type.getDeclaredField("RELEASE_ATTEMPTS");
            attemptsField.setAccessible(true);
            int attempts = attemptsField.getInt(null);

            fixture.park();
            for (int i = 0; i < attempts; i++) {
                fixture.expiry.getAndSet(null).run();
            }

            verify(queue, times(attempts)).cancel(eq(fixture.reference), anyLong());
            assertNull(fixture.expiry.get(), "no retry after the last attempt");
        }
    }

    @Test
    void aParkedReferenceOfADeletedQueueIsDropped() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.track(false);
            Queue queue = fixture.parkable();
            when(fixture.server().locateQueue(nullable(SimpleString.class))).thenReturn(null);

            fixture.park();
            fixture.expiry.get().run();

            verify(queue).acknowledge(isNull(), eq(fixture.reference), eq(AckReason.KILLED), isNull(), eq(true));
            verify(queue, never()).cancel(any(MessageReference.class), anyLong());
        }
    }

    @Test
    void aParkedLockCannotBeRenewed() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.track(false);
            fixture.parkable();

            fixture.park();

            assertEquals(0L, fixture.type.getMethod("renew", Delivery.class)
                    .invoke(fixture.support, fixture.delivery));
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
        final AtomicLong lastDelay = new AtomicLong();
        final AtomicBoolean rejectNextSchedule = new AtomicBoolean();
        final Object support;

        Fixture() throws Exception {
            support = type.getConstructor(AMQPConnectionContext.class, AMQPSessionCallback.class)
                    .newInstance(connection, session);
            when(consumer.getQueue().getUser()).thenReturn(SimpleString.of("floci-az:servicebus-peeklock:60"));
            var scheduler = connection.getProtocolManager().getServer().getScheduledPool();
            doAnswer(call -> {
                if (rejectNextSchedule.getAndSet(false)) {
                    throw new RejectedExecutionException("scheduler stopped");
                }
                expiry.set(call.getArgument(0));
                lastDelay.set(call.getArgument(1));
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

        ActiveMQServer server() {
            return connection.getProtocolManager().getServer();
        }

        /** The consumer hands the reference over, and its queue is still the one the server knows by name. */
        Queue parkable() throws Exception {
            when(consumer.removeReferenceByID(anyLong())).thenReturn(reference);
            Queue queue = reference.getQueue();
            when(server().locateQueue(nullable(SimpleString.class))).thenReturn(queue);
            return queue;
        }

        void park() throws Exception {
            type.getMethod("park").invoke(support);
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
