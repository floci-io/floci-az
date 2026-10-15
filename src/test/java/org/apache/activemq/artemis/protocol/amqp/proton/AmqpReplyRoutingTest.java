package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.filter.Filter;
import org.apache.activemq.artemis.core.filter.impl.FilterImpl;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPMessage;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPStandardMessage;
import org.apache.activemq.artemis.utils.collections.TypedProperties;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.codec.ReadableBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A reply on {@code $cbs} or {@code $management} goes back to an address every client receives
 * on. These cover both halves of keeping it to the client that asked: the broker stamps a request
 * with the connection that sent it, and a receiver on a reply address gets a selector that lets
 * through its own connection's replies and nothing else, never a request. Azure sends no stamp, so
 * they also cover that a reply reaches the client without it.
 */
class AmqpReplyRoutingTest {

    private static final String PROPERTY = "floci_connection_id";

    private static URLClassLoader loader;
    private static Method onReceive;
    private static Method replySelector;
    private static Method copyStamp;
    private static Method connectionId;

    @BeforeAll
    static void loadFromPatchJar() throws Exception {
        loader = PatchJarLoader.open();
        Class<?> routing = Class.forName(
                "org.apache.activemq.artemis.protocol.amqp.proton.AmqpReplyRouting", true, loader);
        onReceive = routing.getMethod(
                "onReceive", AMQPConnectionContext.class, SimpleString.class, Message.class);
        replySelector = routing.getMethod(
                "replySelector", AMQPConnectionContext.class, SimpleString.class, String.class);
        copyStamp = routing.getMethod("copyStamp", Message.class, AMQPMessage.class);
        connectionId = routing.getDeclaredMethod("connectionId", AMQPConnectionContext.class);
        connectionId.setAccessible(true);
    }

    @AfterAll
    static void close() throws Exception {
        loader.close();
    }

    // ── the stamp on a request ───────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"$cbs", "$management", "orders/$management", "orders/Subscriptions/audit/$management"})
    @DisplayName("a request to a reply address carries the id of its connection to the responder")
    void stampsRequests(String address) throws Exception {
        AMQPConnectionContext connection = connection();
        AMQPMessage request = message(Map.of("operation", "put-token"));

        receive(connection, address, request);

        assertEquals(idOf(connection), stampOnTheWire(request));
    }

    /** The responders read their requests from intercept addresses, which no client receives on. */
    @ParameterizedTest
    @ValueSource(strings = {"$cbs-intercept", "$management-intercept", "eh1", "orders", "orders/$DeadLetterQueue"})
    @DisplayName("a message to any other address is left alone")
    void leavesOtherAddressesAlone(String address) throws Exception {
        AMQPMessage message = message(Map.of("operation", "put-token"));

        receive(connection(), address, message);

        assertNull(stampOnTheWire(message));
    }

    @Test
    @DisplayName("a stamp a client set itself is replaced by its real connection id")
    void overwritesForgedStamps() throws Exception {
        AMQPConnectionContext connection = connection();
        AMQPMessage request = message(Map.of("operation", "READ", PROPERTY, "another-connection"));

        receive(connection, "$management", request);

        assertEquals(idOf(connection), stampOnTheWire(request));
    }

    @Test
    @DisplayName("on an anonymous relay link the address the message names decides")
    void readsTheMessageAddressOnAnAnonymousLink() throws Exception {
        AMQPConnectionContext connection = connection();
        AMQPMessage request = message(Map.of("operation", "put-token"));
        request.setAddress("$cbs");

        onReceive.invoke(null, connection, null, request);

        assertEquals(idOf(connection), stampOnTheWire(request));
    }

    @Test
    @DisplayName("a connection keeps one id, and no two connections share one")
    void connectionIdsAreStableAndUnique() throws Exception {
        AMQPConnectionContext first = connection();
        AMQPConnectionContext second = connection();

        assertEquals(idOf(first), idOf(first));
        assertNotEquals(idOf(first), idOf(second));
    }

    // ── the stamp on a reply ─────────────────────────────────────────────────

    /**
     * The responder sends its reply to the same address as the request, over a connection of its
     * own, with the stamp it copied. The broker keeps the stamp and does not stamp the reply again,
     * which would address it to the responder.
     */
    @ParameterizedTest
    @ValueSource(strings = {"$cbs", "$management", "orders/$management"})
    @DisplayName("a reply reaches the client with the responder's properties and without the stamp")
    void takesTheStampOffAReply(String address) throws Exception {
        AMQPMessage reply = message(Map.of(
                "status-code", 202, "status-description", "Accepted", PROPERTY, "requesting-connection"));

        receive(connection(), address, reply);

        assertEquals(Map.of("status-code", 202, "status-description", "Accepted"), propertiesOnTheWire(reply));
        assertEquals("requesting-connection", brokerStamp(reply));
    }

    @Test
    @DisplayName("a reply without a stamp is left as the responder sent it")
    void leavesUnstampedRepliesAlone() throws Exception {
        AMQPMessage reply = message(Map.of("status-code", 202));

        receive(connection(), "$cbs", reply);

        assertEquals(Map.of("status-code", 202), propertiesOnTheWire(reply));
        assertNull(reply.getExtraProperties());
    }

    /** Peek and lock renewal are answered inside the broker, which builds the reply itself. */
    @Test
    @DisplayName("a reply the broker builds gets the stamp of its request, and only when there is one")
    void copiesTheStampOntoBrokerReplies() throws Exception {
        AMQPMessage reply = message(Map.of("statusCode", 200));
        copy(message(Map.of("operation", "com.microsoft:renew-lock", PROPERTY, "requesting-connection")), reply);

        assertEquals(Map.of("statusCode", 200), propertiesOnTheWire(reply));
        assertEquals("requesting-connection", brokerStamp(reply));

        AMQPMessage unstamped = message(Map.of("statusCode", 200));
        copy(message(Map.of("operation", "com.microsoft:renew-lock")), unstamped);

        assertNull(unstamped.getExtraProperties());
    }

    // ── the selector of a receiver ───────────────────────────────────────────

    /**
     * The point of the whole change, checked with the broker's own filter: two connections
     * receive on the same address, and each receiver lets through only the replies to its own
     * requests, from a responder or from the broker. A reply with no stamp at all reaches nobody.
     */
    @ParameterizedTest
    @ValueSource(strings = {"$cbs", "$management", "orders/$management"})
    @DisplayName("a receiver on a reply address takes its own connection's replies and no other")
    void receiverTakesOnlyItsOwnReplies(String address) throws Exception {
        AMQPConnectionContext mine = connection();
        AMQPConnectionContext theirs = connection();

        Filter filter = FilterImpl.createFilter(selector(mine, address, null));

        assertTrue(filter.match(responderReply(address, Map.of("status-code", 202, PROPERTY, idOf(mine)))));
        assertTrue(filter.match(brokerReply(mine)));
        assertFalse(filter.match(responderReply(address, Map.of("status-code", 202, PROPERTY, idOf(theirs)))));
        assertFalse(filter.match(brokerReply(theirs)));
        assertFalse(filter.match(responderReply(address, Map.of("status-code", 202))));
    }

    @Test
    @DisplayName("a client's own selector is kept, and an OR in it cannot widen the match")
    void keepsTheClientSelector() throws Exception {
        AMQPConnectionContext mine = connection();
        AMQPConnectionContext theirs = connection();

        String selector = selector(mine, "$cbs", "kind = 'a' OR kind = 'b'");
        Filter filter = FilterImpl.createFilter(selector);

        assertEquals("(kind = 'a' OR kind = 'b') AND " + PROPERTY + " = '" + idOf(mine) + "' AND operation IS NULL",
                selector);
        assertTrue(filter.match(responderReply("$cbs", Map.of("kind", "b", PROPERTY, idOf(mine)))));
        assertFalse(filter.match(responderReply("$cbs", Map.of("kind", "a", PROPERTY, idOf(theirs)))));
        assertFalse(filter.match(responderReply("$cbs", Map.of("kind", "c", PROPERTY, idOf(mine)))));
        assertFalse(filter.match(stampedRequest(mine, "$cbs", Map.of("operation", "put-token", "kind", "a"))),
                "an OR in the client's selector let a request through");
    }

    /**
     * A request that no responder took would otherwise reach the subscription of the connection
     * that sent it, which matches its stamp, and come back to its sender as if it were the reply.
     */
    @ParameterizedTest
    @ValueSource(strings = {"$cbs", "$management", "orders/$management"})
    @DisplayName("a receiver on a reply address never takes a request, not even its own connection's")
    void receiverNeverTakesARequest(String address) throws Exception {
        AMQPConnectionContext mine = connection();

        Filter filter = FilterImpl.createFilter(selector(mine, address, null));

        assertFalse(filter.match(stampedRequest(mine, address, Map.of("operation", "com.microsoft:schedule-message"))));
        assertTrue(filter.match(responderReply(address, Map.of("status-code", 501, PROPERTY, idOf(mine)))));
    }

    @Test
    @DisplayName("a receiver anywhere else keeps the selector the client sent")
    void leavesOtherSelectorsAlone() throws Exception {
        AMQPConnectionContext connection = connection();

        assertEquals("floci_offset > -1",
                selector(connection, "eh1/ConsumerGroups/$Default/Partitions/0", "floci_offset > -1"));
        assertNull(selector(connection, "orders", null));
        assertNull(selector(connection, "$cbs-intercept", null));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A broker-side connection. The patch keeps its id with the proton connection under it. */
    private static AMQPConnectionContext connection() {
        AMQPConnectionContext connection = mock(AMQPConnectionContext.class, RETURNS_DEEP_STUBS);
        when(connection.getHandler().getConnection()).thenReturn(Proton.connection());
        return connection;
    }

    private static String idOf(AMQPConnectionContext connection) throws Exception {
        return (String) invoke(connectionId, connection);
    }

    private static void receive(AMQPConnectionContext connection, String address, Message message)
            throws Exception {
        invoke(onReceive, connection, SimpleString.of(address), message);
    }

    private static void copy(Message request, AMQPMessage reply) throws Exception {
        invoke(copyStamp, request, reply);
    }

    private static String selector(AMQPConnectionContext connection, String address, String selector)
            throws Exception {
        return (String) invoke(replySelector, connection, SimpleString.of(address), selector);
    }

    private static Object invoke(Method method, Object... args) throws Exception {
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    /** A reply from a responder, as the broker holds it once the reply has arrived. */
    private static AMQPMessage responderReply(String address, Map<String, Object> applicationProperties)
            throws Exception {
        AMQPMessage reply = message(applicationProperties);
        receive(connection(), address, reply);
        return reply;
    }

    /** A request from {@code requester} as the broker holds it once it has arrived: stamped. */
    private static AMQPMessage stampedRequest(AMQPConnectionContext requester, String address,
                                              Map<String, Object> applicationProperties) throws Exception {
        AMQPMessage request = message(applicationProperties);
        receive(requester, address, request);
        return request;
    }

    /** A reply the broker builds itself, to a request from {@code requester}. */
    private static AMQPMessage brokerReply(AMQPConnectionContext requester) throws Exception {
        AMQPMessage request = stampedRequest(requester, "orders/$management",
                Map.of("operation", "com.microsoft:peek-message"));
        AMQPMessage reply = message(Map.of("statusCode", 204));
        copy(request, reply);
        return reply;
    }

    private static AMQPStandardMessage message(Map<String, Object> applicationProperties) {
        // Proton's Message: the Artemis Message this file imports is a different type.
        org.apache.qpid.proton.message.Message message = Proton.message();
        message.setMessageId("request-1");
        message.setApplicationProperties(new ApplicationProperties(applicationProperties));
        byte[] buffer = new byte[1024];
        int size = message.encode(buffer, 0, buffer.length);
        return new AMQPStandardMessage(0, Arrays.copyOf(buffer, size), null);
    }

    /** The application properties as the client reads them: from the bytes the broker sends. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertiesOnTheWire(AMQPMessage message) {
        ReadableBuffer data = message.getSendBuffer(1, null);
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        // Proton's Message: the Artemis Message this file imports is a different type.
        org.apache.qpid.proton.message.Message decoded = Proton.message();
        decoded.decode(bytes, 0, bytes.length);
        return decoded.getApplicationProperties().getValue();
    }

    /** The stamp as the responder reads it from a request: from the bytes the broker sends. */
    private static Object stampOnTheWire(AMQPMessage message) {
        return propertiesOnTheWire(message).get(PROPERTY);
    }

    /** The stamp in the broker properties, which the broker filters on but does not send. */
    private static String brokerStamp(AMQPMessage message) {
        TypedProperties brokerProperties = message.getExtraProperties();
        Object stamp = brokerProperties == null ? null : brokerProperties.getProperty(SimpleString.of(PROPERTY));
        return stamp == null ? null : stamp.toString();
    }
}
