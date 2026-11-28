package io.floci.az.services.eventgrid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

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

    /** A topic's {@code publicNetworkAccess} when the write sent none, the spec's default. */
    public static final String PUBLIC_NETWORK_ACCESS_ENABLED = "Enabled";

    /**
     * A custom topic as its writes left it. A topic stored before {@code tags} and the network and auth settings were
     * kept reads them as null, which answers as the spec's defaults.
     *
     * @param tags                the tags the last write sent, or null for none
     * @param identity            the managed identity, as {@code ArmIdentities} resolved it, or null for none
     * @param publicNetworkAccess {@code Enabled}, {@code Disabled} or {@code SecuredByPerimeter}; null for the default
     * @param disableLocalAuth    whether access keys and SAS tokens are refused; null for the default, false
     * @param inboundIpRules      the {@code InboundIpRule}s, as sent; null for none
     * @param inputSchemaMapping  the {@code InputSchemaMapping}, as sent; null for none
     */
    public record Topic(
            String subscriptionId,
            String resourceGroup,
            String name,
            String location,
            String inputSchema,
            String key1,
            String key2,
            Map<String, Object> tags,
            Map<String, Object> identity,
            String publicNetworkAccess,
            Boolean disableLocalAuth,
            List<Object> inboundIpRules,
            Map<String, Object> inputSchemaMapping) {

        public String resourceId() {
            return "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup
                    + "/providers/Microsoft.EventGrid/topics/" + name;
        }

        /** The same topic with these access keys. */
        public Topic withKeys(String newKey1, String newKey2) {
            return new Topic(subscriptionId, resourceGroup, name, location, inputSchema, newKey1, newKey2, tags,
                    identity, publicNetworkAccess, disableLocalAuth, inboundIpRules, inputSchemaMapping);
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
