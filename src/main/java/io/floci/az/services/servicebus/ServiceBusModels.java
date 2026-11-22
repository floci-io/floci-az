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
            boolean requiresDuplicateDetection,
            long duplicateDetectionHistorySeconds,
            long defaultMessageTtlMillis,
            boolean deadLetteringOnMessageExpiration,
            Instant createdAt,
            Instant updatedAt,
            String autoDeleteOnIdle,
            Boolean enableBatchedOperations,
            String userMetadata) {

        public QueueEntity {
            if (maxSizeInMegabytes == 0) {
                maxSizeInMegabytes = ServiceBusEntityXml.EntityMetadata.DEFAULT_MAX_SIZE_IN_MEGABYTES;
            }
            if (duplicateDetectionHistorySeconds == 0) {
                duplicateDetectionHistorySeconds =
                        ServiceBusEntityXml.DEFAULT_DUPLICATE_DETECTION_HISTORY_SECONDS;
            }
            if (defaultMessageTtlMillis == 0) {
                defaultMessageTtlMillis = ServiceBusEntityXml.DEFAULT_MESSAGE_TTL_MILLIS;
            }
        }

        static QueueEntity defaults(String name, int maxDeliveryCount,
                                     long lockDurationSeconds, boolean requiresSession,
                                     ServiceBusEntityXml.DuplicateDetectionSettings duplicateDetection,
                                     ServiceBusEntityXml.MessageLifetimeSettings lifetime) {
            return defaults(name, maxDeliveryCount, lockDurationSeconds, requiresSession,
                    duplicateDetection, lifetime, ServiceBusEntityXml.EntityMetadata.DEFAULTS);
        }

        static QueueEntity defaults(String name, int maxDeliveryCount,
                                     long lockDurationSeconds, boolean requiresSession,
                                     ServiceBusEntityXml.DuplicateDetectionSettings duplicateDetection,
                                     ServiceBusEntityXml.MessageLifetimeSettings lifetime,
                                     ServiceBusEntityXml.EntityMetadata metadata) {
            Instant now = Instant.now();
            return new QueueEntity(name, maxDeliveryCount, lockDurationSeconds,
                    metadata.maxSizeInMegabytes(), requiresSession, duplicateDetection.enabled(),
                    duplicateDetection.historySeconds(), lifetime.ttlMillis(),
                    lifetime.deadLetterOnExpiration(), now, now, metadata.autoDeleteOnIdle(),
                    metadata.enableBatchedOperations(), metadata.userMetadata());
        }

        /**
         * This description as an update of {@code existing}: keeps its creation time and the
         * settings Azure does not allow to change after creation.
         */
        QueueEntity updating(QueueEntity existing) {
            return new QueueEntity(name, maxDeliveryCount, lockDurationSeconds, maxSizeInMegabytes,
                    existing.requiresSession(), existing.requiresDuplicateDetection(),
                    duplicateDetectionHistorySeconds, defaultMessageTtlMillis,
                    deadLetteringOnMessageExpiration, existing.createdAt(), Instant.now(),
                    autoDeleteOnIdle, enableBatchedOperations, userMetadata);
        }
    }

    @RegisterForReflection
    public record TopicEntity(
            String name,
            long maxSizeInMegabytes,
            boolean requiresDuplicateDetection,
            long duplicateDetectionHistorySeconds,
            long defaultMessageTtlMillis,
            Instant createdAt,
            Instant updatedAt,
            String autoDeleteOnIdle,
            Boolean enableBatchedOperations,
            String userMetadata,
            boolean supportOrdering) {

        public TopicEntity {
            if (maxSizeInMegabytes == 0) {
                maxSizeInMegabytes = ServiceBusEntityXml.EntityMetadata.DEFAULT_MAX_SIZE_IN_MEGABYTES;
            }
            if (duplicateDetectionHistorySeconds == 0) {
                duplicateDetectionHistorySeconds =
                        ServiceBusEntityXml.DEFAULT_DUPLICATE_DETECTION_HISTORY_SECONDS;
            }
            if (defaultMessageTtlMillis == 0) {
                defaultMessageTtlMillis = ServiceBusEntityXml.DEFAULT_MESSAGE_TTL_MILLIS;
            }
        }

        static TopicEntity defaults(
                String name,
                ServiceBusEntityXml.DuplicateDetectionSettings duplicateDetection,
                ServiceBusEntityXml.MessageLifetimeSettings lifetime) {
            return defaults(name, duplicateDetection, lifetime, ServiceBusEntityXml.EntityMetadata.DEFAULTS);
        }

        static TopicEntity defaults(
                String name,
                ServiceBusEntityXml.DuplicateDetectionSettings duplicateDetection,
                ServiceBusEntityXml.MessageLifetimeSettings lifetime,
                ServiceBusEntityXml.EntityMetadata metadata) {
            Instant now = Instant.now();
            return new TopicEntity(name, metadata.maxSizeInMegabytes(), duplicateDetection.enabled(),
                    duplicateDetection.historySeconds(), lifetime.ttlMillis(), now, now,
                    metadata.autoDeleteOnIdle(), metadata.enableBatchedOperations(),
                    metadata.userMetadata(), metadata.supportOrdering());
        }

        /** As {@link QueueEntity#updating}: keeps the creation time and immutable settings. */
        TopicEntity updating(TopicEntity existing) {
            return new TopicEntity(name, maxSizeInMegabytes, existing.requiresDuplicateDetection(),
                    duplicateDetectionHistorySeconds, defaultMessageTtlMillis, existing.createdAt(),
                    Instant.now(), autoDeleteOnIdle, enableBatchedOperations, userMetadata,
                    supportOrdering);
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
            long defaultMessageTtlMillis,
            boolean deadLetteringOnMessageExpiration,
            Instant createdAt,
            Instant updatedAt,
            String autoDeleteOnIdle,
            Boolean enableBatchedOperations,
            String userMetadata) {

        public SubscriptionEntity {
            if (defaultMessageTtlMillis == 0) {
                defaultMessageTtlMillis = ServiceBusEntityXml.DEFAULT_MESSAGE_TTL_MILLIS;
            }
        }

        static SubscriptionEntity defaults(String topicName, String name,
                                            int maxDeliveryCount, long lockDurationSeconds,
                                            boolean requiresSession,
                                            ServiceBusEntityXml.MessageLifetimeSettings lifetime) {
            return defaults(topicName, name, maxDeliveryCount, lockDurationSeconds, requiresSession,
                    lifetime, ServiceBusEntityXml.EntityMetadata.DEFAULTS);
        }

        static SubscriptionEntity defaults(String topicName, String name,
                                            int maxDeliveryCount, long lockDurationSeconds,
                                            boolean requiresSession,
                                            ServiceBusEntityXml.MessageLifetimeSettings lifetime,
                                            ServiceBusEntityXml.EntityMetadata metadata) {
            Instant now = Instant.now();
            return new SubscriptionEntity(topicName, name, maxDeliveryCount, lockDurationSeconds,
                    requiresSession, lifetime.ttlMillis(),
                    lifetime.deadLetterOnExpiration(), now, now, metadata.autoDeleteOnIdle(),
                    metadata.enableBatchedOperations(), metadata.userMetadata());
        }

        /** As {@link QueueEntity#updating}: keeps the creation time and immutable settings. */
        SubscriptionEntity updating(SubscriptionEntity existing) {
            return new SubscriptionEntity(topicName, name, maxDeliveryCount, lockDurationSeconds,
                    existing.requiresSession(), defaultMessageTtlMillis,
                    deadLetteringOnMessageExpiration, existing.createdAt(), Instant.now(),
                    autoDeleteOnIdle, enableBatchedOperations, userMetadata);
        }
    }
}
