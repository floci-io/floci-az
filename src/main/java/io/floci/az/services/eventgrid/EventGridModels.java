package io.floci.az.services.eventgrid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Domain objects for Azure Event Grid Custom Topics and webhook event subscriptions.
 *
 * <p>These records are the persisted source of truth; the ARM resource JSON returned to clients
 * is projected from them in {@link EventGridService}. The nested records are registered for
 * reflection so Jackson can (de)serialize them in the GraalVM native image.
 */
@RegisterForReflection(targets = {
        EventGridModels.Topic.class,
        EventGridModels.EventSubscription.class,
        EventGridModels.Filter.class,
        EventGridModels.RetryPolicy.class,
        EventGridModels.DeadLetterDestination.class,
        EventGridModels.DeadLetterIdentity.class,
        EventGridModels.DeadLetterWithResourceIdentity.class})
public final class EventGridModels {

    private EventGridModels() {
    }

    /** Input event schema a topic accepts and that delivery defaults to. */
    public static final String SCHEMA_EVENT_GRID = "EventGridSchema";
    public static final String SCHEMA_CLOUD_EVENT = "CloudEventSchemaV1_0";
    public static final String SCHEMA_CUSTOM = "CustomEventSchema";

    public record Topic(
            String subscriptionId,
            String resourceGroup,
            String name,
            String location,
            String inputSchema,
            String key1,
            String key2) {

        public String resourceId() {
            return "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup
                    + "/providers/Microsoft.EventGrid/topics/" + name;
        }
    }

    /**
     * Subscriptions persisted before dead-letter support carry a {@code deadLetterEndpointUrl}
     * field; ignoring unknown properties keeps them readable.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventSubscription(
            String name,
            String topicResourceId,
            String endpointUrl,
            String eventDeliverySchema,
            Filter filter,
            RetryPolicy retryPolicy,
            DeadLetterDestination deadLetterDestination,
            DeadLetterWithResourceIdentity deadLetterWithResourceIdentity) {

        public String resourceId() {
            return topicResourceId + "/providers/Microsoft.EventGrid/eventSubscriptions/" + name;
        }

        /**
         * The dead-letter destination delivery uses: {@code deadLetterDestination}, or failing that
         * the destination inside {@code deadLetterWithResourceIdentity}. {@code null} when neither is
         * set, in which case undeliverable events are dropped.
         */
        public DeadLetterDestination resolveDeadLetterDestination() {
            if (deadLetterDestination != null) {
                return deadLetterDestination;
            }
            return deadLetterWithResourceIdentity != null ? deadLetterWithResourceIdentity.destination() : null;
        }
    }

    /**
     * A {@code DeadLetterDestination}. {@code StorageBlob} is the only type the service defines; its
     * properties name the storage account (ARM id) and the container dead-lettered events go to.
     */
    public record DeadLetterDestination(String endpointType, String resourceId, String blobContainerName) {
    }

    /** The {@code EventSubscriptionIdentity} of a {@code deadLetterWithResourceIdentity}. */
    public record DeadLetterIdentity(String type, String userAssignedIdentity) {
    }

    public record DeadLetterWithResourceIdentity(DeadLetterIdentity identity, DeadLetterDestination destination) {
    }

    public record Filter(
            String subjectBeginsWith,
            String subjectEndsWith,
            List<String> includedEventTypes,
            boolean isSubjectCaseSensitive) {

        public static Filter empty() {
            return new Filter(null, null, List.of(), false);
        }
    }

    public record RetryPolicy(int maxDeliveryAttempts, int eventTimeToLiveInMinutes) {
    }
}
