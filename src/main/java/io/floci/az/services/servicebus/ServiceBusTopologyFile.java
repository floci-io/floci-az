package io.floci.az.services.servicebus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * Jackson model of the official Service Bus emulator's declarative {@code Config.json}
 * (<a href="https://learn.microsoft.com/azure/service-bus-messaging/test-locally-with-service-bus-emulator">
 * format reference</a>), also written by the .NET Aspire Service Bus hosting integration.
 * Property names are the file's PascalCase keys; unknown keys are ignored so future
 * additions to the official format don't break loading.
 */
final class ServiceBusTopologyFile {

    private ServiceBusTopologyFile() {}

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Root(@JsonProperty("UserConfig") UserConfig userConfig) {}

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserConfig(@JsonProperty("Namespaces") List<Namespace> namespaces) {}

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Namespace(
            @JsonProperty("Name") String name,
            @JsonProperty("Queues") List<Queue> queues,
            @JsonProperty("Topics") List<Topic> topics) {}

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Queue(
            @JsonProperty("Name") String name,
            @JsonProperty("Properties") EntityProperties properties) {}

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Topic(
            @JsonProperty("Name") String name,
            @JsonProperty("Properties") EntityProperties properties,
            @JsonProperty("Subscriptions") List<Subscription> subscriptions) {}

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Subscription(
            @JsonProperty("Name") String name,
            @JsonProperty("Properties") EntityProperties properties,
            @JsonProperty("Rules") List<Rule> rules) {}

    /** Shared property bag — queues, topics, and subscriptions each honor their subset. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EntityProperties(
            @JsonProperty("DeadLetteringOnMessageExpiration") Boolean deadLetteringOnMessageExpiration,
            @JsonProperty("DefaultMessageTimeToLive") String defaultMessageTimeToLive,
            @JsonProperty("DuplicateDetectionHistoryTimeWindow") String duplicateDetectionHistoryTimeWindow,
            @JsonProperty("ForwardDeadLetteredMessagesTo") String forwardDeadLetteredMessagesTo,
            @JsonProperty("ForwardTo") String forwardTo,
            @JsonProperty("LockDuration") String lockDuration,
            @JsonProperty("MaxDeliveryCount") Integer maxDeliveryCount,
            @JsonProperty("RequiresDuplicateDetection") Boolean requiresDuplicateDetection,
            @JsonProperty("RequiresSession") Boolean requiresSession) {

        static final EntityProperties EMPTY =
                new EntityProperties(null, null, null, null, null, null, null, null, null);
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Rule(
            @JsonProperty("Name") String name,
            @JsonProperty("Properties") RuleProperties properties) {}

    /** {@code FilterType} is {@code Correlation} or {@code Sql} in the official format. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RuleProperties(
            @JsonProperty("FilterType") String filterType,
            @JsonProperty("CorrelationFilter") CorrelationFilter correlationFilter,
            @JsonProperty("SqlFilter") SqlExpressionHolder sqlFilter,
            @JsonProperty("Action") SqlExpressionHolder action) {}

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CorrelationFilter(
            @JsonProperty("Properties") Map<String, Object> properties,
            @JsonProperty("CorrelationId") String correlationId,
            @JsonProperty("MessageId") String messageId,
            @JsonProperty("To") String to,
            @JsonProperty("ReplyTo") String replyTo,
            @JsonProperty("Label") String label,
            @JsonProperty("SessionId") String sessionId,
            @JsonProperty("ReplyToSessionId") String replyToSessionId,
            @JsonProperty("ContentType") String contentType) {

        static final CorrelationFilter EMPTY =
                new CorrelationFilter(null, null, null, null, null, null, null, null, null);
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SqlExpressionHolder(@JsonProperty("SqlExpression") String sqlExpression) {}
}
