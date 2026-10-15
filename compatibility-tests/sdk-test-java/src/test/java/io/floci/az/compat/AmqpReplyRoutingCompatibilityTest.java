package io.floci.az.compat;

import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.amqp.messaging.Source;
import org.apache.qpid.proton.amqp.messaging.Target;
import org.apache.qpid.proton.engine.BaseHandler;
import org.apache.qpid.proton.engine.Connection;
import org.apache.qpid.proton.engine.Delivery;
import org.apache.qpid.proton.engine.Event;
import org.apache.qpid.proton.engine.Receiver;
import org.apache.qpid.proton.engine.Sender;
import org.apache.qpid.proton.engine.Session;
import org.apache.qpid.proton.engine.SslDomain;
import org.apache.qpid.proton.message.Message;
import org.apache.qpid.proton.reactor.Reactor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.nio.BufferOverflowException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A CBS or management reply reaches only the connection that sent the request, as in Azure.
 *
 * <p>Every client receives its replies on the same address ({@code $cbs}, {@code $management} or
 * {@code {entity}/$management}). A broker that sends each reply to every receiver on that address
 * hands each client the replies of all the others. A client that takes the next reply as its own,
 * as the Rust SDK does, then reads another client's reply, or gets one on a link it has already
 * detached and loses the connection.
 *
 * <p>The Azure SDKs hide what arrives on the reply link, so these tests speak AMQP through
 * proton-j, the library the Java SDK is built on, and send their requests the way the SDKs do.
 * Several connections each attach a receiver. When all of them are attached, each connection
 * sends one request, so every reply has a receiver on every connection it could go to. Each
 * connection must then hold exactly one reply: the one to its own request.
 *
 * <p>The broker routes each reply by a property of its own, which Azure never sends. Each reply
 * must reach the client without it, so that the client sees the reply as Azure sends it. That
 * shape is pinned exactly on both services: the status properties Azure sends on the node,
 * correlated to the request, with no other application property and no message annotation.
 *
 * <p>An operation floci-az does not implement gets a reply too: 501 (Not Implemented), on the
 * requesting connection only. The request itself never comes back to a client.
 */
@DisplayName("CBS and management replies")
class AmqpReplyRoutingCompatibilityTest {

    private static final int CONNECTIONS = 3;
    /** How long the replies, and any stray reply, get to arrive after the last request. */
    private static final long REPLY_WINDOW_MS = 3_000;
    private static final long EXCHANGE_TIMEOUT_MS = 60_000;
    /** How long the reactor thread gets to end once it has been told to stop. */
    private static final long STOP_TIMEOUT_MS = 10_000;
    /** The prefix of the properties the broker keeps for itself. Azure sends none of them. */
    private static final String INTERNAL_PROPERTY_PREFIX = "floci_";
    /** {@code $cbs} and the Event Hubs {@code $management}: the AMQP management specification's names. */
    private static final ReplyShape AMQP_MANAGEMENT_REPLY =
            new ReplyShape("status-code", Set.of("status-code", "status-description"));
    /** A Service Bus entity management node. */
    private static final ReplyShape ENTITY_MANAGEMENT_REPLY =
            new ReplyShape("statusCode", Set.of("statusCode", "statusDescription"));
    /** A Service Bus entity management node, for an operation that failed with an error condition. */
    private static final ReplyShape ENTITY_MANAGEMENT_ERROR =
            new ReplyShape("statusCode", Set.of("statusCode", "statusDescription", "errorCondition"));

    @Test
    @DisplayName("Event Hubs: each connection receives only the reply to its own put-token")
    void eventHubPutToken() throws Exception {
        assertEachConnectionGetsOnlyItsOwnReply(eventHubBroker(), "$cbs",
                AmqpReplyRoutingCompatibilityTest::putToken, AMQP_MANAGEMENT_REPLY, 202);
    }

    @Test
    @DisplayName("Event Hubs: each connection receives only the reply to its own management READ")
    void eventHubManagementRead() throws Exception {
        assertEachConnectionGetsOnlyItsOwnReply(eventHubBroker(), "$management",
                requestId -> request(requestId, Map.of(
                        "operation", "READ",
                        "type", "com.microsoft:eventhub",
                        "name", EmulatorConfig.EVENTHUB_NAME), null),
                AMQP_MANAGEMENT_REPLY, 200);
    }

    @Test
    @DisplayName("Service Bus: each connection receives only the reply to its own put-token")
    void serviceBusPutToken() throws Exception {
        assertEachConnectionGetsOnlyItsOwnReply(serviceBusBroker(), "$cbs",
                AmqpReplyRoutingCompatibilityTest::putToken, AMQP_MANAGEMENT_REPLY, 202);
    }

    /** Peek is answered inside the broker rather than by a responder, so it gets a case of its own. */
    @Test
    @DisplayName("Service Bus: each connection receives only the reply to its own peek")
    void serviceBusPeek() throws Exception {
        Broker broker = serviceBusBroker();
        String queue = "replies-" + UUID.randomUUID().toString().substring(0, 8);
        EmulatorConfig.ensureServiceBusQueue(queue, false);

        assertEachConnectionGetsOnlyItsOwnReply(broker, queue + "/$management",
                requestId -> request(requestId,
                        Map.of("operation", "com.microsoft:peek-message"),
                        Map.of("from-sequence-number", 0L, "message-count", 1)),
                ENTITY_MANAGEMENT_REPLY, 204);
    }

    /**
     * Lock renewal is answered inside the broker too, on a path of its own. A lock token that
     * matches no lock gets the error reply, 410 with {@code com.microsoft:message-lock-lost},
     * which is a reply like any other and needs no message to be locked first.
     */
    @Test
    @DisplayName("Service Bus: each connection receives only the reply to its own lock renewal")
    void serviceBusLockRenewal() throws Exception {
        Broker broker = serviceBusBroker();
        String queue = "replies-" + UUID.randomUUID().toString().substring(0, 8);
        EmulatorConfig.ensureServiceBusQueue(queue, false);

        assertEachConnectionGetsOnlyItsOwnReply(broker, queue + "/$management",
                requestId -> request(requestId,
                        Map.of("operation", "com.microsoft:renew-lock"),
                        Map.of("lock-tokens", new UUID[]{UUID.randomUUID()})),
                ENTITY_MANAGEMENT_ERROR, 410);
    }

    @Test
    @DisplayName("Event Hubs: each connection receives only the 501 to its own CBS operation other than put-token")
    void eventHubUnimplementedCbsOperation() throws Exception {
        assertEachConnectionGetsOnlyItsOwnReply(eventHubBroker(), "$cbs",
                requestId -> request(requestId, Map.of(
                        "operation", "delete-token",
                        "type", "servicebus.windows.net:sastoken",
                        "name", "amqp://" + EmulatorConfig.EVENTHUB_HOST + "/" + EmulatorConfig.EVENTHUB_NAME), null),
                AMQP_MANAGEMENT_REPLY, 501);
    }

    @Test
    @DisplayName("Event Hubs: each connection receives only the 501 to its own management operation other than READ")
    void eventHubUnimplementedManagementOperation() throws Exception {
        assertEachConnectionGetsOnlyItsOwnReply(eventHubBroker(), "$management",
                requestId -> request(requestId, Map.of(
                        "operation", "UPDATE",
                        "type", "com.microsoft:eventhub",
                        "name", EmulatorConfig.EVENTHUB_NAME), null),
                AMQP_MANAGEMENT_REPLY, 501);
    }

    @Test
    @DisplayName("Service Bus: each connection receives only the 501 to its own CBS operation other than put-token")
    void serviceBusUnimplementedCbsOperation() throws Exception {
        assertEachConnectionGetsOnlyItsOwnReply(serviceBusBroker(), "$cbs",
                requestId -> request(requestId, Map.of(
                        "operation", "delete-token",
                        "type", "servicebus.windows.net:sastoken",
                        "name", "amqp://" + EmulatorConfig.SERVICEBUS_HOST + "/replies"), null),
                AMQP_MANAGEMENT_REPLY, 501);
    }

    /** Scheduling a message is a management operation floci-az does not implement. */
    @Test
    @DisplayName("Service Bus: each connection receives only the 501 to its own schedule-message")
    void serviceBusUnimplementedOperation() throws Exception {
        Broker broker = serviceBusBroker();
        String queue = "replies-" + UUID.randomUUID().toString().substring(0, 8);
        EmulatorConfig.ensureServiceBusQueue(queue, false);

        assertEachConnectionGetsOnlyItsOwnReply(broker, queue + "/$management",
                requestId -> request(requestId,
                        Map.of("operation", "com.microsoft:schedule-message"),
                        Map.of("messages", List.of())),
                ENTITY_MANAGEMENT_REPLY, 501);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Where to reach a broker, and the TLS settings for it if it is reached over TLS. */
    private record Broker(String host, int port, SslDomain tls) {}

    /**
     * A reply as Azure sends it on a node: its application properties, and the one of them that
     * holds the status. Azure sends no other application property and no message annotation.
     */
    private record ReplyShape(String statusProperty, Set<String> properties) {}

    private static Broker eventHubBroker() throws Exception {
        EmulatorConfig.assumeEmulatorRunning();
        EmulatorConfig.ensureEventHubNamespace();
        Assumptions.assumeTrue(!EmulatorConfig.eventHubMocked,
                "Event Hubs is in mocked mode (no Artemis broker), AMQP tests skipped");
        return new Broker(EmulatorConfig.EVENTHUB_HOST, EmulatorConfig.EVENTHUB_AMQP_PORT, null);
    }

    /**
     * The Service Bus broker over AMQPS, the port the suite reaches it on. The broker certificate
     * is self-signed: {@link EmulatorConfig#ensureServiceBusNamespace()} installs a default
     * SSLContext that trusts it, and the host name is not checked.
     */
    private static Broker serviceBusBroker() throws Exception {
        EmulatorConfig.assumeEmulatorRunning();
        EmulatorConfig.ensureServiceBusNamespace();
        Assumptions.assumeFalse(EmulatorConfig.serviceBusMocked,
                "Service Bus is in mocked mode (no Artemis broker), AMQP tests skipped");
        SslDomain tls = Proton.sslDomain();
        tls.init(SslDomain.Mode.CLIENT);
        tls.setSslContext(SSLContext.getDefault());
        tls.setPeerAuthentication(SslDomain.VerifyMode.VERIFY_PEER);
        return new Broker(EmulatorConfig.SERVICEBUS_HOST, EmulatorConfig.serviceBusAmqpsPort, tls);
    }

    /** A put-token request as the Azure SDKs send it. The responder reads none of the values. */
    private static Message putToken(String requestId) {
        String audience = "amqp://" + EmulatorConfig.EVENTHUB_HOST + "/" + EmulatorConfig.EVENTHUB_NAME;
        return request(requestId, Map.of(
                "operation", "put-token",
                "type", "servicebus.windows.net:sastoken",
                "name", audience), "SharedAccessSignature sr=" + audience);
    }

    private static Message request(String requestId, Map<String, Object> properties, Object body) {
        Message request = Proton.message();
        request.setMessageId(requestId);
        request.setApplicationProperties(new ApplicationProperties(properties));
        if (body != null) {
            request.setBody(new AmqpValue(body));
        }
        return request;
    }

    private static void assertEachConnectionGetsOnlyItsOwnReply(
            Broker broker, String address, Function<String, Message> request,
            ReplyShape shape, int expectedStatus) throws Exception {
        Exchange exchange = new Exchange(broker, address, request);
        Reactor reactor = Proton.reactor(exchange);
        Thread thread = new Thread(reactor::run, "reply-routing-test");
        thread.setDaemon(true);
        thread.start();
        try {
            // The exchange stops the reactor itself once the reply window has passed, or at its
            // own timeout, so this join normally returns long before its limit.
            thread.join(EXCHANGE_TIMEOUT_MS + STOP_TIMEOUT_MS);
        } finally {
            stop(reactor, thread);
        }

        // The reactor thread has ended, so nothing changes the exchange's state any more.
        assertNull(exchange.failure, exchange.failure);
        assertTrue(exchange.sent == CONNECTIONS,
                "only " + exchange.sent + " of " + CONNECTIONS + " requests were sent");
        for (int i = 0; i < CONNECTIONS; i++) {
            List<Object> correlationIds = new ArrayList<>();
            for (Message reply : exchange.replies.get(i)) {
                Map<?, ?> properties = reply.getApplicationProperties() == null
                        ? Map.of() : reply.getApplicationProperties().getValue();
                assertFalse(properties.containsKey("operation"),
                        "connection " + i + " received a request, not a reply: " + properties);
                Object status = properties.get(shape.statusProperty());
                assertEquals(expectedStatus, status instanceof Number number ? number.intValue() : -1,
                        "connection " + i + " received an unexpected reply: " + properties);
                List<?> internal = properties.keySet().stream()
                        .filter(key -> key.toString().startsWith(INTERNAL_PROPERTY_PREFIX)).toList();
                assertTrue(internal.isEmpty(), "connection " + i + " received a reply with " + internal
                        + ", which Azure never sends: " + properties);
                assertEquals(shape.properties(), properties.keySet(),
                        "connection " + i + " received a reply with properties Azure does not send: " + properties);
                Map<?, ?> annotations = reply.getMessageAnnotations() == null
                        ? Map.of() : reply.getMessageAnnotations().getValue();
                assertTrue(annotations.isEmpty(), "connection " + i
                        + " received a reply with message annotations Azure does not send: " + annotations);
                correlationIds.add(reply.getCorrelationId());
            }
            assertEquals(List.of(exchange.requestIds.get(i)), correlationIds,
                    "connection " + i + " must receive the reply to its own request and no other");
        }
    }

    /**
     * Stops the reactor, waits for its thread to end, and only then frees the reactor.
     *
     * <p>In proton-j, {@link Reactor#stop()} only sets a flag, which the reactor reads when no
     * event is waiting. {@link Reactor#wakeup()} writes to the reactor's wake-up pipe, which is
     * safe from any thread, and ends a blocked select, so the flag is read at once rather than
     * when the select times out. A reactor whose thread still runs is not freed: that thread may
     * still use the connections.
     */
    private static void stop(Reactor reactor, Thread thread) throws InterruptedException {
        reactor.stop();
        reactor.wakeup();
        thread.join(STOP_TIMEOUT_MS);
        assertFalse(thread.isAlive(),
                "the AMQP reactor thread did not stop within " + STOP_TIMEOUT_MS + " ms");
        reactor.free();
    }

    /**
     * One request and its replies on each of {@link #CONNECTIONS} connections, driven by a
     * proton-j reactor. Every callback runs on the reactor thread, so the state needs no lock;
     * the test thread reads it only after the reactor has stopped.
     */
    private static final class Exchange extends BaseHandler {

        private final Broker broker;
        private final String address;
        private final Function<String, Message> request;
        private final Map<Connection, Integer> index = new HashMap<>();
        private final List<Sender> senders = new ArrayList<>();
        private final List<String> requestIds = new ArrayList<>();
        private final List<List<Message>> replies = new ArrayList<>();
        private final boolean[] requestSent = new boolean[CONNECTIONS];
        private int attachedReceivers;
        private int sent;
        private String failure;

        Exchange(Broker broker, String address, Function<String, Message> request) {
            this.broker = broker;
            this.address = address;
            this.request = request;
        }

        @Override
        public void onReactorInit(Event event) {
            for (int i = 0; i < CONNECTIONS; i++) {
                Connection connection = event.getReactor().connectionToHost(broker.host(), broker.port(), this);
                connection.setContainer("reply-routing-" + i + "-" + UUID.randomUUID());
                connection.open();
                index.put(connection, i);
                Session session = connection.session();
                session.open();

                Receiver receiver = session.receiver("replies-" + i);
                Source source = new Source();
                source.setAddress(address);
                receiver.setSource(source);
                Target target = new Target();
                target.setAddress("reply-routing-" + i);
                receiver.setTarget(target);
                receiver.open();
                receiver.flow(CONNECTIONS * 2);

                Sender sender = session.sender("requests-" + i);
                Target requests = new Target();
                requests.setAddress(address);
                sender.setTarget(requests);
                sender.open();
                senders.add(sender);
                requestIds.add("request-" + i + "-" + UUID.randomUUID());
                replies.add(new ArrayList<>());
            }
            event.getReactor().schedule((int) EXCHANGE_TIMEOUT_MS, new BaseHandler() {
                @Override
                public void onTimerTask(Event timeout) {
                    if (failure == null && sent < CONNECTIONS) {
                        failure = "the exchange did not complete within " + EXCHANGE_TIMEOUT_MS + " ms";
                    }
                    timeout.getReactor().stop();
                }
            });
        }

        @Override
        public void onConnectionBound(Event event) {
            if (broker.tls() != null) {
                event.getTransport().ssl(broker.tls());
            }
        }

        @Override
        public void onLinkRemoteOpen(Event event) {
            if (event.getLink() instanceof Receiver) {
                attachedReceivers++;
            }
            sendWhenAllAttached(event.getReactor());
        }

        @Override
        public void onLinkFlow(Event event) {
            sendWhenAllAttached(event.getReactor());
        }

        /** Sends nothing until every receiver is attached, then one request per connection. */
        private void sendWhenAllAttached(Reactor reactor) {
            if (attachedReceivers < CONNECTIONS || sent == CONNECTIONS) {
                return;
            }
            for (int i = 0; i < CONNECTIONS; i++) {
                Sender sender = senders.get(i);
                if (requestSent[i] || sender.getCredit() <= 0) {
                    continue;
                }
                byte[] encoded = encode(request.apply(requestIds.get(i)));
                Delivery delivery = sender.delivery(requestIds.get(i).getBytes());
                sender.send(encoded, 0, encoded.length);
                sender.advance();
                delivery.settle();
                requestSent[i] = true;
                sent++;
            }
            if (sent == CONNECTIONS) {
                reactor.schedule((int) REPLY_WINDOW_MS, new BaseHandler() {
                    @Override
                    public void onTimerTask(Event window) {
                        for (Connection connection : index.keySet()) {
                            connection.close();
                        }
                        window.getReactor().stop();
                    }
                });
            }
        }

        @Override
        public void onDelivery(Event event) {
            Delivery delivery = event.getDelivery();
            if (!(delivery.getLink() instanceof Receiver receiver)
                    || !delivery.isReadable() || delivery.isPartial()) {
                return;
            }
            byte[] buffer = new byte[delivery.pending()];
            int read = receiver.recv(buffer, 0, buffer.length);
            receiver.advance();
            Message reply = Proton.message();
            reply.decode(buffer, 0, read);
            replies.get(index.get(receiver.getSession().getConnection())).add(reply);
            delivery.settle();
            receiver.flow(1);
        }

        @Override
        public void onTransportError(Event event) {
            failure = "transport error: " + event.getTransport().getCondition();
            event.getReactor().stop();
        }

        @Override
        public void onLinkRemoteClose(Event event) {
            if (event.getLink().getRemoteCondition() != null
                    && event.getLink().getRemoteCondition().getCondition() != null) {
                failure = "the broker closed link " + event.getLink().getName() + ": "
                        + event.getLink().getRemoteCondition();
                event.getReactor().stop();
            }
        }

        private static byte[] encode(Message message) {
            int capacity = 1_024;
            while (true) {
                byte[] buffer = new byte[capacity];
                try {
                    int length = message.encode(buffer, 0, buffer.length);
                    byte[] exact = new byte[length];
                    System.arraycopy(buffer, 0, exact, 0, length);
                    return exact;
                } catch (BufferOverflowException e) {
                    capacity *= 2;
                }
            }
        }
    }
}
