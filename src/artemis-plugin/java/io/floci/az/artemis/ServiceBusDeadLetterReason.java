package io.floci.az.artemis;

import org.apache.activemq.artemis.api.core.Message;

/**
 * The reason and description Azure Service Bus gives a message that the broker, not a client,
 * moves to the dead-letter queue. Receivers read them as the {@code DeadLetterReason} and
 * {@code DeadLetterErrorDescription} application properties.
 */
final class ServiceBusDeadLetterReason {

    static final String REASON_PROPERTY = "DeadLetterReason";
    static final String DESCRIPTION_PROPERTY = "DeadLetterErrorDescription";

    static final String MAX_DELIVERY_COUNT_EXCEEDED = "MaxDeliveryCountExceeded";
    static final String TTL_EXPIRED = "TTLExpiredException";
    static final String TTL_EXPIRED_DESCRIPTION = "The message expired and was dead lettered.";

    private static final System.Logger LOG =
            System.getLogger(ServiceBusDeadLetterReason.class.getName());

    private ServiceBusDeadLetterReason() {
    }

    static String maxDeliveryCountDescription(int maxDeliveryCount) {
        return "Message could not be consumed after " + maxDeliveryCount + " delivery attempts.";
    }

    /**
     * Stamps the dead-letter copy before it is routed. A reason the message already carries is
     * kept. A failure is logged and leaves the copy as it was, so the move itself still happens.
     */
    static void stamp(Message deadLetter, String reason, String description) {
        try {
            if (deadLetter.getObjectProperty(REASON_PROPERTY) != null) {
                return;
            }
            deadLetter.putStringProperty(REASON_PROPERTY, reason);
            deadLetter.putStringProperty(DESCRIPTION_PROPERTY, description);
            deadLetter.reencode();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Could not set the dead-letter reason " + reason + " on message "
                            + deadLetter.getMessageID(), e);
        }
    }
}
