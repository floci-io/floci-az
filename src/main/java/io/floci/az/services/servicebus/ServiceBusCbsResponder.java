package io.floci.az.services.servicebus;

import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.Accepted;
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
import java.util.HashMap;
import java.util.Map;

/**
 * Daemon thread that handles AMQP CBS (Claims Based Security) for a Service Bus namespace.
 *
 * The Azure Service Bus SDK always sends a PUT TOKEN request to the {@code $cbs} address
 * before opening any entity links. This responder receives those requests and replies with
 * status-code 200 on the {@code cbs-client-reply-to} address, unblocking the SDK.
 *
 * Uses proton-j directly since it is the same low-level AMQP library the SDK uses internally.
 */
public class ServiceBusCbsResponder {

    private static final Logger LOG = Logger.getLogger(ServiceBusCbsResponder.class);
    private static final String CBS_ADDRESS = "$cbs";
    private static final String CBS_INTERCEPT_ADDRESS = "$cbs-intercept";

    private final String host;
    private final int port;
    private volatile boolean running;
    private volatile Reactor currentReactor;
    private Thread thread;

    public ServiceBusCbsResponder(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void start() {
        running = true;
        thread = new Thread(this::run, "servicebus-cbs-" + host + ":" + port);
        thread.setDaemon(true);
        thread.start();
        LOG.infov("CBS responder started for {0}:{1}", host, port);
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
            try {
                Reactor reactor = Proton.reactor(new CbsHandler());
                currentReactor = reactor;
                reactor.run();
            } catch (Exception e) {
                if (running) {
                    LOG.debugv("CBS responder reconnecting ({0}:{1}): {2}", host, port, e.getMessage());
                    try {
                        Thread.sleep(2_000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                currentReactor = null;
            }
        }
    }

    private class CbsHandler extends BaseHandler {

        private Sender responseSender;
        private int deliveryTag = 0;

        @Override
        public void onReactorInit(Event event) {
            Connection conn = event.getReactor().connectionToHost(host, port, this);
            conn.setContainer("floci-az-cbs-responder");
            conn.open();
            Session session = conn.session();
            session.setProperties(new java.util.HashMap<>());
            session.open();

            Receiver receiver = session.receiver("cbs-receiver");
            Source src = new Source();
            src.setAddress(CBS_INTERCEPT_ADDRESS);
            receiver.setSource(src);
            receiver.open();
            receiver.flow(100);

            responseSender = session.sender("cbs-reply-sender");
            Target tgt = new Target();
            tgt.setAddress(CBS_ADDRESS);
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

            Object messageId = request.getMessageId();
            LOG.debugv("CBS put-token received, messageId={0}", messageId);

            sendOkResponse(messageId);

            delivery.disposition(Accepted.getInstance());
            delivery.settle();
            receiver.flow(1);
        }

        private void sendOkResponse(Object correlationId) {
            Message response = Message.Factory.create();
            response.setCorrelationId(correlationId);
            Map<String, Object> props = new HashMap<>();
            props.put("status-code", 200);
            props.put("status-description", "OK");
            response.setApplicationProperties(new ApplicationProperties(props));

            byte[] encoded = new byte[512];
            int len = response.encode(encoded, 0, encoded.length);

            byte[] tag = Integer.toString(deliveryTag++).getBytes(StandardCharsets.UTF_8);
            Delivery resp = responseSender.delivery(tag);
            responseSender.send(encoded, 0, len);
            responseSender.advance();
            resp.settle();
        }

        @Override
        public void onTransportError(Event event) {
            LOG.debugv("CBS responder transport error for {0}:{1}", host, port);
            event.getConnection().close();
            event.getReactor().stop();
        }

        @Override
        public void onConnectionRemoteClose(Event event) {
            event.getConnection().close();
            event.getConnection().free();
            event.getReactor().stop();
        }

        @Override
        public void onSessionRemoteClose(Event event) {
            event.getSession().close();
        }

        @Override
        public void onLinkRemoteClose(Event event) {
            event.getLink().close();
        }
    }
}
