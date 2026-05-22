package io.floci.az.services.servicebus;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

public final class ServiceBusModels {

    private ServiceBusModels() {}

    @RegisterForReflection
    public record QueueEntity(
            String name,
            int maxDeliveryCount,
            long lockDurationSeconds,
            long maxSizeInMegabytes,
            boolean requiresSession,
            Instant createdAt,
            Instant updatedAt) {

        static QueueEntity defaults(String name, int maxDeliveryCount,
                                     long lockDurationSeconds, boolean requiresSession) {
            Instant now = Instant.now();
            return new QueueEntity(name, maxDeliveryCount, lockDurationSeconds,
                    1024, requiresSession, now, now);
        }
    }

    @RegisterForReflection
    public record TopicEntity(
            String name,
            long maxSizeInMegabytes,
            Instant createdAt,
            Instant updatedAt) {

        static TopicEntity defaults(String name) {
            Instant now = Instant.now();
            return new TopicEntity(name, 1024, now, now);
        }
    }

    @RegisterForReflection
    public record SubscriptionEntity(
            String topicName,
            String name,
            int maxDeliveryCount,
            long lockDurationSeconds,
            boolean requiresSession,
            Instant createdAt,
            Instant updatedAt) {

        static SubscriptionEntity defaults(String topicName, String name,
                                            int maxDeliveryCount, long lockDurationSeconds,
                                            boolean requiresSession) {
            Instant now = Instant.now();
            return new SubscriptionEntity(topicName, name, maxDeliveryCount, lockDurationSeconds,
                    requiresSession, now, now);
        }
    }
}
