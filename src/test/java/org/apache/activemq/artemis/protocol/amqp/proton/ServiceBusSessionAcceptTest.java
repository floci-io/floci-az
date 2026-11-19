package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.Consumer;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.protocol.amqp.exceptions.ActiveMQAMQPException;
import org.apache.activemq.artemis.utils.collections.LinkedListIterator;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.UnsignedInteger;
import org.apache.qpid.proton.amqp.messaging.Source;
import org.apache.qpid.proton.engine.Connection;
import org.apache.qpid.proton.engine.EndpointState;
import org.apache.qpid.proton.engine.Link;
import org.apache.qpid.proton.engine.Sender;
import org.apache.qpid.proton.engine.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Accept-next-session waits for a session instead of failing at once: the attach stays unanswered
 * until a session can be locked, and fails with {@code com.microsoft:timeout} once the wait the
 * client sent in the {@code com.microsoft:timeout} link property runs out.
 *
 * The broker is mocked. Each "attach" calls the patch's {@code configure} the way Artemis's
 * {@code DefaultSenderController} does, and the mocked connection's {@code onRemoteOpen} replays it,
 * which is what the real {@code AMQPConnectionContext} does when the poll completes a waiter.
 */
class ServiceBusSessionAcceptTest {

    private static final String SUPPORT = "org.apache.activemq.artemis.protocol.amqp.proton.ServiceBusSessionSupport";
    private static final String ACCEPT = "org.apache.activemq.artemis.protocol.amqp.proton.ServiceBusSessionAcceptSupport";
    private static final Symbol SESSION_FILTER = Symbol.valueOf("com.microsoft:session-filter");
    private static final Symbol LINK_TIMEOUT = Symbol.valueOf("com.microsoft:timeout");
    private static final SimpleString QUEUE = SimpleString.of("orders");

    private URLClassLoader loader;
    private Method configure;
    private Method isPending;
    private Map<?, ?> pending;

    private ActiveMQServer server;
    private Queue queue;
    private AMQPConnectionContext connection;
    private final List<MessageReference> messages = new CopyOnWriteArrayList<>();
    private final List<Scheduled> scheduled = new CopyOnWriteArrayList<>();
    private final Map<Sender, Attach> attaches = new HashMap<>();

    private record Scheduled(Runnable task, long delayMillis) {
    }

    /** One accept-next-session link and the outcome of its latest attach. */
    private static final class Attach {
        private final Sender sender;
        private String selector;
        private Map<Symbol, Object> filters;
        private ActiveMQAMQPException failure;

        private Attach(Sender sender) {
            this.sender = sender;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        loader = PatchJarLoader.open();
        Class<?> support = Class.forName(SUPPORT, true, loader);
        Class<?> accept = Class.forName(ACCEPT, true, loader);
        configure = support.getMethod("configure", ActiveMQServer.class, SimpleString.class, Source.class,
                Map.class, Sender.class, ProtonServerSenderContext.class, String.class);
        isPending = accept.getMethod("isPending", ActiveMQAMQPException.class);
        Field pendingField = accept.getDeclaredField("PENDING");
        pendingField.setAccessible(true);
        pending = (Map<?, ?>) pendingField.get(null);

        server = mock(ActiveMQServer.class);
        queue = mock(Queue.class);
        connection = mock(AMQPConnectionContext.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(server.getScheduledPool()).thenReturn(scheduler);
        when(server.locateQueue(QUEUE)).thenReturn(queue);
        when(queue.getUser()).thenReturn(SimpleString.of("floci-az:servicebus-session:30"));
        when(queue.getGroups()).thenReturn(new HashMap<>());
        when(queue.browserIterator()).thenAnswer(invocation -> iterator(new ArrayList<>(messages)));
        doAnswer(invocation -> {
            scheduled.add(new Scheduled(invocation.getArgument(0), invocation.getArgument(1)));
            return mock(ScheduledFuture.class);
        }).when(scheduler).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(connection).runLater(any(Runnable.class));
        doAnswer(invocation -> {
            Link link = invocation.getArgument(0);
            attach(attaches.get((Sender) link));
            return null;
        }).when(connection).onRemoteOpen(any(Link.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        loader.close();
    }

    @Test
    @DisplayName("a waiting accept takes the session of a message sent after it started")
    void waitingAcceptIsCompletedByALaterMessage() throws Exception {
        Attach waiter = newAttach("waiter", 30_000L);

        attach(waiter);
        assertTrue(isPending(waiter.failure), "no session yet: the attach must stay unanswered");
        assertEquals(1, pending.size());

        runPolls();
        assertTrue(isPending(waiter.failure), "still no session: keep waiting");
        verify(connection, never()).onRemoteOpen(any(Link.class));

        messages.add(message("s1"));
        runPolls();

        assertNull(waiter.failure);
        assertEquals("s1", waiter.filters.get(SESSION_FILTER));
        assertEquals("JMSXGroupID = 's1'", waiter.selector);
        assertTrue(pending.isEmpty());
        assertTrue(polls().isEmpty(), "a completed accept polls no more");
    }

    @Test
    @DisplayName("a waiting accept fails with com.microsoft:timeout once the client's wait runs out")
    void waitingAcceptTimesOut() throws Exception {
        Attach waiter = newAttach("waiter", 30L);

        attach(waiter);
        assertTrue(isPending(waiter.failure));
        assertTrue(polls().stream().allMatch(poll -> poll.delayMillis() <= 30L),
                "the poll must not overshoot the client's wait");

        Thread.sleep(40);
        runPolls();

        assertNotNull(waiter.failure);
        assertFalse(isPending(waiter.failure));
        assertEquals("com.microsoft:timeout", waiter.failure.getAmqpError().toString());
        assertTrue(pending.isEmpty());
        assertTrue(polls().isEmpty());
    }

    @Test
    @DisplayName("an accept with no wait left fails with com.microsoft:timeout at once")
    void acceptWithoutWaitFailsAtOnce() throws Exception {
        Attach waiter = newAttach("waiter", 0L);

        attach(waiter);

        assertNotNull(waiter.failure);
        assertFalse(isPending(waiter.failure));
        assertEquals("com.microsoft:timeout", waiter.failure.getAmqpError().toString());
        assertTrue(pending.isEmpty());
        assertTrue(polls().isEmpty());
    }

    @Test
    @DisplayName("an accept whose link closes while it waits is dropped without replaying the attach")
    void closedLinkStopsWaiting() throws Exception {
        Attach waiter = newAttach("waiter", 30_000L);
        attach(waiter);
        assertTrue(isPending(waiter.failure));

        when(waiter.sender.getLocalState()).thenReturn(EndpointState.CLOSED);
        messages.add(message("s1"));
        runPolls();

        verify(connection, never()).onRemoteOpen(any(Link.class));
        assertTrue(pending.isEmpty());
        assertTrue(polls().isEmpty());
    }

    @Test
    @DisplayName("two waiting accepts each lock a different session")
    void twoWaitersGetDistinctSessions() throws Exception {
        Attach first = newAttach("first", 30_000L);
        Attach second = newAttach("second", 30_000L);
        attach(first);
        attach(second);
        assertEquals(2, pending.size());

        messages.add(message("s1"));
        runPolls();

        Attach winner = first.failure == null ? first : second;
        Attach loser = winner == first ? second : first;
        assertNull(winner.failure);
        assertEquals("s1", winner.filters.get(SESSION_FILTER));
        assertTrue(isPending(loser.failure), "the only session is taken: the other accept keeps waiting");
        assertEquals(1, pending.size());

        messages.add(message("s2"));
        runPolls();

        assertNull(loser.failure);
        assertEquals("s2", loser.filters.get(SESSION_FILTER));
        assertTrue(pending.isEmpty());
    }

    @Test
    @DisplayName("waiters on one queue share one backlog scan per poll interval")
    void waitersShareOneScanPerInterval() throws Exception {
        messages.add(message("s1"));
        Map<SimpleString, Consumer> locked = new HashMap<>();
        locked.put(SimpleString.of("s1"), mock(Consumer.class));
        when(queue.getGroups()).thenReturn(locked);
        Attach first = newAttach("first", 30_000L);
        Attach second = newAttach("second", 30_000L);
        Attach third = newAttach("third", 30_000L);
        attach(first);
        attach(second);
        attach(third);
        assertEquals(3, pending.size());

        clearInvocations(queue);
        runPolls();

        verify(queue, times(1)).browserIterator();
        assertEquals(3, pending.size(), "every session is locked: all three keep waiting");
        assertEquals(3, polls().size());
        verify(connection, never()).onRemoteOpen(any(Link.class));
    }

    private Attach newAttach(String name, long waitMillis) {
        Sender sender = mock(Sender.class);
        Session session = mock(Session.class);
        Connection protonConnection = mock(Connection.class);
        when(sender.getName()).thenReturn(name);
        when(sender.getSession()).thenReturn(session);
        when(sender.getLocalState()).thenReturn(EndpointState.UNINITIALIZED);
        when(sender.getRemoteState()).thenReturn(EndpointState.ACTIVE);
        Map<Symbol, Object> properties = new HashMap<>();
        properties.put(LINK_TIMEOUT, UnsignedInteger.valueOf(waitMillis));
        when(sender.getRemoteProperties()).thenReturn(properties);
        when(session.getConnection()).thenReturn(protonConnection);
        when(session.getLocalState()).thenReturn(EndpointState.ACTIVE);
        when(session.getRemoteState()).thenReturn(EndpointState.ACTIVE);
        when(protonConnection.getLocalState()).thenReturn(EndpointState.ACTIVE);
        when(protonConnection.getRemoteState()).thenReturn(EndpointState.ACTIVE);
        Attach attach = new Attach(sender);
        attaches.put(sender, attach);
        return attach;
    }

    /** One attach of the link, as DefaultSenderController.init runs it. */
    private void attach(Attach attach) throws Exception {
        Source source = new Source();
        Map<Symbol, Object> requested = new HashMap<>();
        requested.put(SESSION_FILTER, null);
        source.setFilter(requested);
        Map<Symbol, Object> supported = new HashMap<>();
        attach.failure = null;
        attach.filters = null;
        try {
            attach.selector = (String) configure.invoke(null, server, QUEUE, source, supported, attach.sender,
                    senderContext(), null);
            attach.filters = supported;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof ActiveMQAMQPException amqp) {
                attach.failure = amqp;
            } else {
                throw e;
            }
        }
    }

    private ProtonServerSenderContext senderContext() {
        ProtonServerSenderContext context = mock(ProtonServerSenderContext.class);
        AMQPSessionContext session = mock(AMQPSessionContext.class);
        when(context.getSessionContext()).thenReturn(session);
        when(session.getAMQPConnectionContext()).thenReturn(connection);
        return context;
    }

    private boolean isPending(ActiveMQAMQPException failure) throws Exception {
        return failure != null && (boolean) isPending.invoke(null, failure);
    }

    /** The accept polls scheduled so far (session-lock expiry timers are far longer). */
    private List<Scheduled> polls() {
        return scheduled.stream().filter(task -> task.delayMillis() <= 50L).toList();
    }

    /** Runs the polls due one interval from now, as the scheduled pool would. */
    private void runPolls() throws InterruptedException {
        Thread.sleep(60);
        List<Scheduled> due = polls();
        scheduled.removeAll(due);
        due.forEach(task -> task.task().run());
    }

    private static MessageReference message(String sessionId) {
        MessageReference reference = mock(MessageReference.class);
        Message message = mock(Message.class);
        when(reference.getMessage()).thenReturn(message);
        when(message.getGroupID()).thenReturn(SimpleString.of(sessionId));
        return reference;
    }

    @SuppressWarnings("unchecked")
    private static LinkedListIterator<MessageReference> iterator(List<MessageReference> snapshot) {
        Iterator<MessageReference> delegate = snapshot.iterator();
        LinkedListIterator<MessageReference> iterator = mock(LinkedListIterator.class);
        when(iterator.hasNext()).thenAnswer(invocation -> delegate.hasNext());
        when(iterator.next()).thenAnswer(invocation -> delegate.next());
        return iterator;
    }
}
