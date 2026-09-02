package io.floci.az.services.eventhub;

import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.Accepted;
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
import org.apache.qpid.proton.message.Message;
import org.apache.qpid.proton.reactor.Reactor;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Daemon thread that answers the AMQP {@code $management} READ operations for a namespace.
 *
 * <p>Every Event Hubs consumer starts by asking the management node which partitions the hub has,
 * and attaches its receiver per partition. Artemis has no notion of either, so nothing replies and
 * the SDK fails before it reads anything. This answers from the namespace's own configuration,
 * which is where the partition count already lives.
 *
 * <p>Built the same way as the CBS responder: the broker.xml divert routes requests to an intercept
 * queue this attaches to, and the reply goes back on {@code $management}, which is both the address
 * SDKs send to and the one they receive on.
 */
public class EventHubManagementResponder {

    private static final Logger LOG = Logger.getLogger(EventHubManagementResponder.class);
    private static final String MANAGEMENT_ADDRESS = "$management";
    private static final String MANAGEMENT_INTERCEPT_ADDRESS = "$management-intercept";
    private static final long RECONNECT_BACKOFF_MS = 2_000;

    /** The entity type the SDKs name when asking about a hub. */
    private static final String EVENTHUB_ENTITY_TYPE = "com.microsoft:eventhub";

    private final String host;
    private final int port;
    private final Map<String, ArtemisConfigGenerator.EntitySpec> entities;
    private final Date createdAt = new Date();

    private volatile boolean running;
    private volatile Reactor currentReactor;
    private Thread thread;

    public EventHubManagementResponder(String host, int port,
                                       Map<String, ArtemisConfigGenerator.EntitySpec> entities) {
        this.host = host;
        this.port = port;
        this.entities = Map.copyOf(entities);
    }

    public void start() {
        running = true;
        thread = new Thread(this::run, "eventhub-management-" + host + ":" + port);
        thread.setDaemon(true);
        thread.start();
        LOG.infov("Management responder started for {0}:{1}", host, port);
    }

    public void stop() {
        running = false;
        Reactor r = currentReactor;
        if (r != null) {
            r.stop();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void run() {
        while (running) {
            Reactor reactor = null;
            try {
                reactor = Proton.reactor(new ManagementHandler());
                currentReactor = reactor;
                reactor.run();
            } catch (Exception e) {
                if (running) {
                    LOG.debugv("Management responder reconnecting ({0}:{1}): {2}",
                            host, port, e.getMessage());
                }
            } finally {
                currentReactor = null;
                if (reactor != null) {
                    try {
                        reactor.free();
                    } catch (Exception e) {
                        LOG.debugv("Management responder failed to free reactor ({0}:{1}): {2}",
                                host, port, e.getMessage());
                    }
                }
            }

            // Outside the catch, for the reason spelled out in ServiceBusCbsResponder: the reactor
            // also returns normally when the broker is unreachable, so a backoff on the exception
            // path alone never fires and the loop burns file descriptors recreating reactors.
            if (running) {
                try {
                    Thread.sleep(RECONNECT_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private class ManagementHandler extends BaseHandler {

        private Sender responseSender;
        private int deliveryTag = 0;

        @Override
        public void onReactorInit(Event event) {
            Connection conn = event.getReactor().connectionToHost(host, port, this);
            conn.setContainer("floci-az-management-responder");
            conn.open();
            Session session = conn.session();
            session.setProperties(new HashMap<>());
            session.open();

            Receiver receiver = session.receiver("management-receiver");
            Source src = new Source();
            src.setAddress(MANAGEMENT_INTERCEPT_ADDRESS);
            receiver.setSource(src);
            receiver.open();
            receiver.flow(100);

            responseSender = session.sender("management-reply-sender");
            Target tgt = new Target();
            tgt.setAddress(MANAGEMENT_ADDRESS);
            responseSender.setTarget(tgt);
            responseSender.open();
        }

        @Override
        public void onDelivery(Event event) {
            Delivery delivery = event.getDelivery();
            if (!(delivery.getLink() instanceof Receiver receiver)) return;
            if (!delivery.isReadable() || delivery.isPartial()) return;

            int pending = delivery.pending();
            byte[] buf = new byte[pending];
            int n = receiver.recv(buf, 0, buf.length);
            receiver.advance();

            Message request = Message.Factory.create();
            request.decode(buf, 0, n);

            Map<String, Object> props = applicationProperties(request);
            String entityType = string(props.get("type"));
            String name = string(props.get("name"));
            LOG.debugv("Management request received: type={0}, name={1}", entityType, name);

            sendResponse(request.getMessageId(), entityType, name);

            delivery.disposition(Accepted.getInstance());
            delivery.settle();
            receiver.flow(1);
        }

        private void sendResponse(Object correlationId, String entityType, String name) {
            ArtemisConfigGenerator.EntitySpec spec = name == null ? null : entities.get(name);

            Message response = Message.Factory.create();
            response.setCorrelationId(correlationId);
            Map<String, Object> responseProps = new HashMap<>();

            if (!EVENTHUB_ENTITY_TYPE.equals(entityType)) {
                // Partition properties (com.microsoft:partition) would have to report sequence
                // numbers and offsets, which Artemis does not keep. Saying so is better than
                // inventing values a consumer may branch on.
                responseProps.put("status-code", 501);
                responseProps.put("status-description",
                        "floci-az emulates " + EVENTHUB_ENTITY_TYPE + " only, not " + entityType);
            } else if (spec == null) {
                responseProps.put("status-code", 404);
                responseProps.put("status-description", "Event hub '" + name + "' not found");
            } else {
                responseProps.put("status-code", 200);
                responseProps.put("status-description", "OK");
                response.setBody(new AmqpValue(hubProperties(name, spec)));
            }

            response.setApplicationProperties(new ApplicationProperties(responseProps));

            byte[] encoded = new byte[4096];
            int len = response.encode(encoded, 0, encoded.length);

            byte[] tag = Integer.toString(deliveryTag++).getBytes(StandardCharsets.UTF_8);
            Delivery resp = responseSender.delivery(tag);
            responseSender.send(encoded, 0, len);
            responseSender.advance();
            resp.settle();
        }

        /**
         * The attributes the SDKs read back. Every one is required — a missing field fails the
         * call outright rather than degrading.
         *
         * <p>{@code partition_ids} is a Java array so it encodes as an AMQP array; a List would
         * encode as an AMQP list, which the SDKs reject.
         */
        private Map<String, Object> hubProperties(String name, ArtemisConfigGenerator.EntitySpec spec) {
            String[] partitionIds = new String[spec.partitionCount()];
            for (int i = 0; i < partitionIds.length; i++) {
                partitionIds[i] = Integer.toString(i);
            }
            Map<String, Object> body = new HashMap<>();
            body.put("name", name);
            body.put("created_at", createdAt);
            body.put("partition_count", spec.partitionCount());
            body.put("partition_ids", partitionIds);
            return body;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> applicationProperties(Message message) {
        ApplicationProperties props = message.getApplicationProperties();
        return props == null ? Map.of() : (Map<String, Object>) props.getValue();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
