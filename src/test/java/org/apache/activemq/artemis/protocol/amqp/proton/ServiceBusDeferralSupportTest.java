package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.security.SecurityAuth;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPStandardMessage;
import org.apache.activemq.artemis.utils.collections.LinkedListIterator;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.UnsignedInteger;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Deferral as the Azure SDKs drive it: a receiver defers a delivery, the message is no longer
 * offered to receivers of that entity, and the client fetches it by sequence number and settles it
 * on the management node with the lock token that request returned.
 */
class ServiceBusDeferralSupportTest {

    private static final long SEQUENCE_NUMBER = 42L;

    private URLClassLoader loader;
    private Class<?> type;
    private Object plugin;
    private Queue queue;
    private MessageReference reference;

    @BeforeEach
    void openPatchJar() throws Exception {
        loader = PatchJarLoader.open();
        type = Class.forName(
                "org.apache.activemq.artemis.protocol.amqp.proton.ServiceBusDeferralSupport", true, loader);
        plugin = type.getConstructor().newInstance();
        queue = queue("orders", "floci-az:servicebus-peeklock:60");
        reference = reference(queue);
    }

    @AfterEach
    void closePatchJar() throws Exception {
        loader.close();
    }

    @Test
    void aDeferredMessageIsNoLongerOfferedToReceiversOfItsEntity() throws Exception {
        assertTrue(canAccept(consumer(queue), reference));

        defer(queue);

        assertFalse(canAccept(consumer(queue), reference));
    }

    @Test
    void deferringOnOneSubscriptionLeavesTheOtherSubscriptionsDelivering() throws Exception {
        Queue other = queue("topic/Subscriptions/other", "floci-az:servicebus-peeklock:60");

        defer(queue);

        assertTrue(canAccept(consumer(other), reference(other)));
    }

    @Test
    void aQueueThatIsNotAServiceBusEntityIsNeverDeferred() throws Exception {
        Queue plain = queue("plain", null);

        defer(plain);

        assertTrue(canAccept(consumer(plain), reference(plain)));
    }

    @Test
    void receivingASequenceNumberThatIsNotDeferredFailsWithMessageNotFound() throws Exception {
        Object reply = receive(Map.of("sequence-numbers", new long[]{SEQUENCE_NUMBER},
                "receiver-settle-mode", UnsignedInteger.ONE));

        assertEquals(404, status(reply));
        assertEquals(Symbol.valueOf("com.microsoft:message-not-found"), properties(reply).get("errorCondition"));
    }

    @Test
    void aPeekLockedDeferredMessageIsReturnedWithALockAndCompletedThroughUpdateDisposition() throws Exception {
        defer(queue);

        Object reply = receive(Map.of("sequence-numbers", new Long[]{SEQUENCE_NUMBER},
                "receiver-settle-mode", UnsignedInteger.ONE));

        assertEquals(200, status(reply));
        Map<?, ?> entry = onlyMessage(reply);
        UUID lockToken = (UUID) entry.get("lock-token");
        assertNotNull(lockToken);
        org.apache.qpid.proton.message.Message received = decode((Binary) entry.get("message"));
        assertEquals("deferred", ((AmqpValue) received.getBody()).getValue());
        assertEquals(SEQUENCE_NUMBER, received.getMessageAnnotations().getValue()
                .get(Symbol.valueOf("x-opt-sequence-number")));
        Date lockedUntil = (Date) received.getMessageAnnotations().getValue()
                .get(Symbol.valueOf("x-opt-locked-until"));
        assertTrue(lockedUntil.getTime() > System.currentTimeMillis());
        verify(reference).incrementDeliveryCount();
        verify(queue, never()).deleteReference(SEQUENCE_NUMBER);

        Object completed = updateDisposition(Map.of("disposition-status", "completed",
                "lock-tokens", new UUID[]{lockToken}));

        assertEquals(200, status(completed));
        verify(queue).deleteReference(SEQUENCE_NUMBER);
        assertTrue(canAccept(consumer(queue), reference));
    }

    @Test
    void aSettledLockCannotSettleTheMessageAgain() throws Exception {
        defer(queue);
        UUID lockToken = (UUID) onlyMessage(receive(Map.of("sequence-numbers", new long[]{SEQUENCE_NUMBER})))
                .get("lock-token");
        updateDisposition(Map.of("disposition-status", "completed", "lock-tokens", new UUID[]{lockToken}));

        Object reply = updateDisposition(Map.of("disposition-status", "completed",
                "lock-tokens", new UUID[]{lockToken}));

        assertEquals(410, status(reply));
        assertEquals(Symbol.valueOf("com.microsoft:message-lock-lost"), properties(reply).get("errorCondition"));
    }

    @Test
    void abandoningADeferredMessageReleasesTheLockAndKeepsItDeferred() throws Exception {
        defer(queue);
        UUID lockToken = (UUID) onlyMessage(receive(Map.of("sequence-numbers", new long[]{SEQUENCE_NUMBER})))
                .get("lock-token");

        Object reply = updateDisposition(Map.of("disposition-status", "abandoned",
                "lock-tokens", new UUID[]{lockToken}));

        assertEquals(200, status(reply));
        verify(queue, never()).deleteReference(SEQUENCE_NUMBER);
        assertFalse(canAccept(consumer(queue), reference));
        assertEquals(200, status(receive(Map.of("sequence-numbers", new long[]{SEQUENCE_NUMBER}))));
    }

    @Test
    void deadLetteringADeferredMessageMovesItWithItsReason() throws Exception {
        defer(queue);
        UUID lockToken = (UUID) onlyMessage(receive(Map.of("sequence-numbers", new long[]{SEQUENCE_NUMBER})))
                .get("lock-token");

        Object reply = updateDisposition(Map.of("disposition-status", "suspended",
                "lock-tokens", new UUID[]{lockToken}, "deadletter-reason", "poison",
                "deadletter-description", "bad payload"));

        assertEquals(200, status(reply));
        verify(queue).sendMessageToDeadLetterAddress(SEQUENCE_NUMBER);
        assertEquals("poison", reference.getMessage().getObjectProperty("DeadLetterReason"));
        assertEquals("bad payload", reference.getMessage().getObjectProperty("DeadLetterErrorDescription"));
    }

    @Test
    void receiveAndDeleteRemovesTheMessageAndReturnsNoLockToken() throws Exception {
        defer(queue);

        Object reply = receive(Map.of("sequence-numbers", new long[]{SEQUENCE_NUMBER},
                "receiver-settle-mode", UnsignedInteger.ZERO));

        assertEquals(200, status(reply));
        assertNull(onlyMessage(reply).get("lock-token"));
        verify(queue).deleteReference(SEQUENCE_NUMBER);
        assertTrue(canAccept(consumer(queue), reference));
    }

    @Test
    void aDeferredMessageWithAnActiveLockIsNotFoundAgain() throws Exception {
        defer(queue);
        receiveLocked();

        Object peekLock = receive(Map.of("sequence-numbers", new long[]{SEQUENCE_NUMBER}));
        Object receiveAndDelete = receive(Map.of("sequence-numbers", new long[]{SEQUENCE_NUMBER},
                "receiver-settle-mode", UnsignedInteger.ZERO));

        assertEquals(404, status(peekLock));
        assertEquals(Symbol.valueOf("com.microsoft:message-not-found"), properties(peekLock).get("errorCondition"));
        assertEquals(404, status(receiveAndDelete));
        verify(queue, never()).deleteReference(SEQUENCE_NUMBER);
    }

    @Test
    void renewingADeferredLockExtendsTheDeadlineSettlementChecks() throws Exception {
        defer(queue);
        UUID lockToken = receiveLocked();

        long renewedUntil = renew(lockToken);

        assertTrue(renewedUntil > System.currentTimeMillis());
        assertEquals(0L, renew(UUID.randomUUID()));
        assertEquals(200, status(updateDisposition(Map.of("disposition-status", "completed",
                "lock-tokens", new UUID[]{lockToken}))));
        assertEquals(0L, renew(lockToken));
    }

    @Test
    void aSessionEntityRequiresTheCallerToHoldTheSession() throws Exception {
        Queue sessions = queue("sessions", "floci-az:servicebus-session:60");
        reference(sessions);
        defer(sessions);

        Object reply = invokeStatic("receive", sessions, Map.of("sequence-numbers", new long[]{SEQUENCE_NUMBER},
                "session-id", "s1"));

        assertEquals(410, status(reply));
        assertEquals(Symbol.valueOf("com.microsoft:session-lock-lost"), properties(reply).get("errorCondition"));
        verify(sessions, never()).deleteReference(SEQUENCE_NUMBER);
    }

    @Test
    void aRepeatedLockTokenIsRejectedWithoutSettlingTheMessage() throws Exception {
        defer(queue);
        UUID lockToken = receiveLocked();

        Object reply = updateDisposition(Map.of("disposition-status", "completed",
                "lock-tokens", new UUID[]{lockToken, lockToken}));

        assertEquals(410, status(reply));
        assertEquals(Symbol.valueOf("com.microsoft:message-lock-lost"), properties(reply).get("errorCondition"));
        verify(queue, never()).deleteReference(SEQUENCE_NUMBER);
        assertEquals(200, status(updateDisposition(Map.of("disposition-status", "completed",
                "lock-tokens", new UUID[]{lockToken}))));
    }

    @Test
    void deletingTheEntityDropsItsDeferredMessagesAndLocks() throws Exception {
        defer(queue);
        UUID lockToken = receiveLocked();

        type.getMethod("afterDestroyQueue", Queue.class, SimpleString.class, SecurityAuth.class,
                boolean.class, boolean.class, boolean.class)
                .invoke(plugin, queue, SimpleString.of("orders"), null, false, false, false);

        assertTrue(canAccept(consumer(queue), reference));
        assertEquals(0L, renew(lockToken));
    }

    @Test
    void oneReplyCarriesAtMostTheMessagesAPeekReplyDoes() throws Exception {
        List<Long> sequenceNumbers = deferMany(251, 10);

        Object reply = receive(Map.of("sequence-numbers", sequenceNumbers.toArray(Long[]::new)));

        assertEquals(200, status(reply));
        assertEquals(250, messages(reply).size());
        assertEquals(200, status(receive(Map.of("sequence-numbers", new long[]{251L}))));
    }

    @Test
    void oneReplyStaysWithinAboutOneMebibyte() throws Exception {
        List<Long> sequenceNumbers = deferMany(2, 700_000);

        Object reply = receive(Map.of("sequence-numbers", sequenceNumbers.toArray(Long[]::new),
                "receiver-settle-mode", UnsignedInteger.ZERO));

        assertEquals(200, status(reply));
        assertEquals(1, messages(reply).size());
        verify(queue).deleteReference(1L);
        verify(queue, never()).deleteReference(2L);
    }

    private void defer(Queue target) throws Exception {
        type.getMethod("defer", ServerConsumer.class, long.class).invoke(null, consumer(target), SEQUENCE_NUMBER);
    }

    private boolean canAccept(ServerConsumer consumer, MessageReference candidate) throws Exception {
        return (boolean) type.getMethod("canAccept", ServerConsumer.class, MessageReference.class)
                .invoke(plugin, consumer, candidate);
    }

    private Object receive(Map<String, Object> body) throws Exception {
        return invokeStatic("receive", body);
    }

    private Object updateDisposition(Map<String, Object> body) throws Exception {
        return invokeStatic("updateDisposition", body);
    }

    private Object invokeStatic(String name, Map<String, Object> body) throws Exception {
        return invokeStatic(name, queue, body);
    }

    private Object invokeStatic(String name, Queue target, Map<String, Object> body) throws Exception {
        Method method = type.getDeclaredMethod(name, Queue.class, Map.class, AMQPConnectionContext.class,
                String.class);
        method.setAccessible(true);
        return method.invoke(null, target, body, null, null);
    }

    private long renew(UUID lockToken) throws Exception {
        Method method = type.getDeclaredMethod("renew", AMQPConnectionContext.class, String.class, String.class,
                UUID.class);
        method.setAccessible(true);
        return (long) method.invoke(null, null, "orders", null, lockToken);
    }

    private UUID receiveLocked() throws Exception {
        return (UUID) onlyMessage(receive(Map.of("sequence-numbers", new long[]{SEQUENCE_NUMBER})))
                .get("lock-token");
    }

    private static List<?> messages(Object reply) throws Exception {
        Method accessor = reply.getClass().getDeclaredMethod("body");
        accessor.setAccessible(true);
        return (List<?>) ((Map<?, ?>) accessor.invoke(reply)).get("messages");
    }

    private static Map<?, ?> properties(Object reply) throws Exception {
        Method accessor = reply.getClass().getDeclaredMethod("applicationProperties");
        accessor.setAccessible(true);
        return (Map<?, ?>) accessor.invoke(reply);
    }

    private static int status(Object reply) throws Exception {
        return (int) properties(reply).get("statusCode");
    }

    private static Map<?, ?> onlyMessage(Object reply) throws Exception {
        List<?> messages = messages(reply);
        assertEquals(1, messages.size());
        return (Map<?, ?>) messages.get(0);
    }

    // Qualified: the Artemis and Proton message types share a simple name.
    private static org.apache.qpid.proton.message.Message decode(Binary binary) {
        org.apache.qpid.proton.message.Message message = Proton.message();
        message.decode(binary.getArray(), binary.getArrayOffset(), binary.getLength());
        return message;
    }

    private static Queue queue(String name, String user) {
        Queue queue = mock(Queue.class);
        when(queue.getName()).thenReturn(SimpleString.of(name));
        when(queue.getUser()).thenReturn(user == null ? null : SimpleString.of(user));
        return queue;
    }

    private static ServerConsumer consumer(Queue queue) {
        ServerConsumer consumer = mock(ServerConsumer.class);
        when(consumer.getQueue()).thenReturn(queue);
        return consumer;
    }

    private static MessageReference reference(Queue queue) {
        MessageReference reference = reference(queue, SEQUENCE_NUMBER, "deferred");
        when(queue.browserIterator()).thenAnswer(call -> iterator(List.of(reference)));
        return reference;
    }

    private static MessageReference reference(Queue queue, long messageId, String body) {
        org.apache.qpid.proton.message.Message encoded = Proton.message();
        encoded.setBody(new AmqpValue(body));
        byte[] bytes = new byte[body.length() + 1024];
        int length = encoded.encode(bytes, 0, bytes.length);
        AMQPStandardMessage message = new AMQPStandardMessage(0, Arrays.copyOf(bytes, length), null);
        MessageReference reference = mock(MessageReference.class);
        when(reference.getMessageID()).thenReturn(messageId);
        when(reference.getMessage()).thenReturn(message);
        when(reference.getQueue()).thenReturn(queue);
        return reference;
    }

    /** Defers {@code count} messages of {@code size} characters on the queue, numbered from 1. */
    private List<Long> deferMany(int count, int size) throws Exception {
        List<MessageReference> references = new ArrayList<>();
        List<Long> sequenceNumbers = new ArrayList<>();
        for (long messageId = 1; messageId <= count; messageId++) {
            references.add(reference(queue, messageId, "x".repeat(size)));
            sequenceNumbers.add(messageId);
            type.getMethod("defer", ServerConsumer.class, long.class).invoke(null, consumer(queue), messageId);
        }
        when(queue.browserIterator()).thenAnswer(call -> iterator(references));
        return sequenceNumbers;
    }

    @SuppressWarnings("unchecked")
    private static LinkedListIterator<MessageReference> iterator(List<MessageReference> references) {
        Iterator<MessageReference> delegate = references.iterator();
        LinkedListIterator<MessageReference> iterator = mock(LinkedListIterator.class);
        when(iterator.hasNext()).thenAnswer(call -> delegate.hasNext());
        when(iterator.next()).thenAnswer(call -> delegate.next());
        return iterator;
    }
}
