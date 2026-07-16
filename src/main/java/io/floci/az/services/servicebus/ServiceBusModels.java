package io.floci.az.services.servicebus;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public final class ServiceBusModels {

    /** Service Bus wire timestamp format, shared by all ATOM serialization in this package. */
    static final DateTimeFormatter ISO8601 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

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

    /**
     * A subscription rule: a named filter (plus optional SQL action) attached to a subscription.
     * {@code filterType} is one of the Azure filter type names:
     * {@code TrueFilter}, {@code FalseFilter}, {@code SqlFilter}, {@code CorrelationFilter}.
     * The correlation* fields and {@code correlationProperties} are only populated for
     * {@code CorrelationFilter}; {@code sqlExpression} only for {@code SqlFilter}.
     * {@code correlationPropertyTypes} carries the XML Schema type of each correlation
     * property value (e.g. {@code int}, {@code boolean}); properties without an entry
     * are strings.
     */
    @RegisterForReflection
    public record RuleEntity(
            String topicName,
            String subscriptionName,
            String name,
            String filterType,
            String sqlExpression,
            String correlationId,
            String messageId,
            String to,
            String replyTo,
            String label,
            String sessionId,
            String replyToSessionId,
            String contentType,
            Map<String, String> correlationProperties,
            Map<String, String> correlationPropertyTypes,
            String actionSqlExpression,
            Instant createdAt) {

        public RuleEntity {
            correlationProperties = correlationProperties == null ? Map.of() : correlationProperties;
            correlationPropertyTypes = correlationPropertyTypes == null ? Map.of() : correlationPropertyTypes;
        }

        public static RuleEntity trueFilter(String topicName, String subscriptionName, String name) {
            return new RuleEntity(topicName, subscriptionName, name, "TrueFilter",
                    null, null, null, null, null, null, null, null, null,
                    Map.of(), Map.of(), null, Instant.now());
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
