package io.floci.az.artemis;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.RoutingContext;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;

/**
 * Gives a message that exceeded its entity's {@code MaxDeliveryCount} the dead-letter reason
 * Azure Service Bus gives it.
 *
 * <p>Artemis dead-letters such a message itself ({@code max-delivery-attempts}), in its queue's
 * redelivery check, by copying it to the dead-letter address. That copy is routed on the same
 * thread before it is stored, so it is stamped here as it enters routing. A client's explicit
 * dead-letter moves the message without a redelivery check, and keeps whatever reason the client
 * gave or did not give.
 */
public final class ServiceBusDeadLetterPlugin implements ActiveMQServerPlugin {

    /** {@link Queue#checkRedelivery}, where Artemis moves a message past its delivery attempts. */
    private static final String REDELIVERY_CHECK = "checkRedelivery";

    private static final StackWalker STACK =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private volatile ActiveMQServer server;

    @Override
    public void registered(ActiveMQServer registeredServer) {
        server = registeredServer;
    }

    @Override
    public void unregistered(ActiveMQServer unregisteredServer) {
        server = null;
    }

    @Override
    public void beforeMessageRoute(
            Message message, RoutingContext context, boolean direct, boolean rejectDuplicates) {
        ActiveMQServer activeServer = server;
        // Only a broker-made copy (a move or a divert) names the address it came from.
        Object originalAddress = message.getBrokerProperty(Message.HDR_ORIGINAL_ADDRESS);
        if (activeServer == null || originalAddress == null) {
            return;
        }
        AddressSettings settings =
                activeServer.getAddressSettingsRepository().getMatch(originalAddress.toString());
        SimpleString deadLetterAddress = settings.getDeadLetterAddress();
        if (deadLetterAddress == null
                || !deadLetterAddress.equals(message.getAddressSimpleString())
                || !inRedeliveryCheck()) {
            return;
        }
        ServiceBusDeadLetterReason.stamp(message,
                ServiceBusDeadLetterReason.MAX_DELIVERY_COUNT_EXCEEDED,
                ServiceBusDeadLetterReason.maxDeliveryCountDescription(
                        settings.getMaxDeliveryAttempts()));
    }

    private static boolean inRedeliveryCheck() {
        return STACK.walk(frames -> frames.anyMatch(frame ->
                REDELIVERY_CHECK.equals(frame.getMethodName())
                        && Queue.class.isAssignableFrom(frame.getDeclaringClass())));
    }
}
