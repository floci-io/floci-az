package io.floci.az.services.iothub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What floci-az stores for Azure IoT Hub. */
public final class IotHubModels {

    /** The resource type of a hub, spelled as Azure spells it in {@code id} and {@code type}. */
    public static final String IOT_HUBS = "Microsoft.Devices/IotHubs";
    /** The resource type of a consumer group, spelled as Azure answers it in {@code type}. */
    public static final String CONSUMER_GROUPS = "Microsoft.Devices/IotHubs/EventHubEndpoints/ConsumerGroups";
    /** The built-in Event Hub-compatible endpoint: the only one a hub has. */
    public static final String EVENTS_ENDPOINT = "events";
    /** The consumer group every endpoint has from the hub's creation, and which cannot be deleted. */
    public static final String DEFAULT_CONSUMER_GROUP = "$Default";

    private IotHubModels() {
    }

    /**
     * A shared access policy and its keys ({@code SharedAccessSignatureAuthorizationRule}).
     *
     * @param keyName      the policy name
     * @param primaryKey   the primary key, base64
     * @param secondaryKey the secondary key, base64
     * @param rights       the rights, in the spec's {@code AccessRights} spelling
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccessPolicy(String keyName, String primaryKey, String secondaryKey, String rights) {

        /** The policy's wire body, keys included: only the key operations answer it. */
        public Map<String, Object> toArm() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("keyName", keyName);
            body.put("primaryKey", primaryKey);
            body.put("secondaryKey", secondaryKey);
            body.put("rights", rights);
            return body;
        }
    }

    /**
     * A consumer group of an Event Hub-compatible endpoint ({@code EventHubConsumerGroupInfo}).
     *
     * @param endpoint  the endpoint it reads, {@code events}
     * @param name      the group name, as the PUT that created it spelt it
     * @param createdAt when it was created, ISO-8601; answered in RFC 1123 as Azure does
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConsumerGroup(String endpoint, String name, String createdAt) {
    }

    /**
     * A {@code Microsoft.Devices/IotHubs} resource. The response body is built from it by {@code IotHubArmService},
     * which adds what the hub derives rather than stores: the host name, the state, and the read-only fields of the
     * built-in Event Hub-compatible endpoint.
     *
     * @param id                the resource id, in the casing of the PUT that created it
     * @param name              the hub name
     * @param location          the location in canonical form ({@code westus2}), which is also how a hub answers it
     * @param tags              the tags; Azure answers {@code {}} when none were sent
     * @param sku               the SKU name ({@code S1})
     * @param capacity          the number of units
     * @param identity          the managed identity, or null for {@code {"type": "None"}}
     * @param properties        the writable properties as the last PUT left them, with Azure's defaults filled in
     *                          and without {@code authorizationPolicies}, which are kept in {@code policies}
     * @param policies          the shared access policies with their keys
     * @param incarnation       a random value chosen when the hub is created and kept until it is deleted, so a
     *                          hub created again under the same id never answers an ETag the deleted one did
     * @param version           the write counter the ETag is built from
     * @param createdAt         when the hub was created, for {@code systemData}
     * @param eventHubPath      the name of the built-in Event Hub-compatible endpoint, fixed at creation
     * @param eventHubNamespace the namespace label of that endpoint, fixed at creation
     * @param consumerGroups    the consumer groups of the endpoints, in creation order; a hub stored before they were
     *                          served loads with the {@code $Default} group of {@code events}, created with the hub
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IotHubResource(
            String id,
            String name,
            String location,
            Map<String, Object> tags,
            String sku,
            int capacity,
            Map<String, Object> identity,
            Map<String, Object> properties,
            List<AccessPolicy> policies,
            int incarnation,
            long version,
            String createdAt,
            String eventHubPath,
            String eventHubNamespace,
            List<ConsumerGroup> consumerGroups
    ) {

        public IotHubResource {
            policies = policies == null ? List.of() : List.copyOf(policies);
            consumerGroups = consumerGroups == null ? defaultConsumerGroups(createdAt) : List.copyOf(consumerGroups);
        }

        /** The groups of a new hub: {@code $Default} on {@code events}, created with it. */
        public static List<ConsumerGroup> defaultConsumerGroups(String createdAt) {
            return List.of(new ConsumerGroup(EVENTS_ENDPOINT, DEFAULT_CONSUMER_GROUP, createdAt));
        }

        /**
         * The hub's ETag. Azure answers an opaque base64 value of eight bytes that changes on every write
         * ({@code AAAAAAYAAAA=}); this one is the incarnation and the write counter in the same shape.
         */
        public String etag() {
            ByteBuffer bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            bytes.putInt(incarnation).putInt((int) version);
            return Base64.getEncoder().encodeToString(bytes.array());
        }

        /** This hub with other tags, one write later. */
        public IotHubResource withTags(Map<String, Object> newTags) {
            return new IotHubResource(id, name, location, newTags, sku, capacity, identity, properties, policies,
                    incarnation, version + 1, createdAt, eventHubPath, eventHubNamespace, consumerGroups);
        }

        /**
         * This hub with other consumer groups. The version stays: a group is a sub-resource with no ETag of its own,
         * and a hub's body does not list its groups, so its ETag does not change.
         */
        public IotHubResource withConsumerGroups(List<ConsumerGroup> groups) {
            return new IotHubResource(id, name, location, tags, sku, capacity, identity, properties, policies,
                    incarnation, version, createdAt, eventHubPath, eventHubNamespace, groups);
        }
    }

    /**
     * A device identity in a hub's identity registry, as the service API's {@code Device} carries it. What a device
     * reports at run time (its connection state, its last activity) is not stored: nothing connects yet.
     *
     * @param deviceId            the device ID, case-sensitive, as the PUT that created it spelt it
     * @param generationId        fixed when the identity is created, so a device created again under the same ID
     *                            is told apart from the deleted one
     * @param version             the write counter the ETag is built from
     * @param status              {@code enabled} or {@code disabled}
     * @param statusReason        the reason given for the status, or null
     * @param statusUpdatedTime   when the status last changed, ISO-8601
     * @param authType            {@code sas}, {@code selfSigned}, {@code certificateAuthority} or {@code none}
     * @param primaryKey          the primary symmetric key, base64, on {@code sas}
     * @param secondaryKey        the secondary symmetric key, base64, on {@code sas}
     * @param primaryThumbprint   the primary X.509 thumbprint, on {@code selfSigned}
     * @param secondaryThumbprint the secondary X.509 thumbprint, on {@code selfSigned}
     * @param iotEdge             whether the device is an IoT Edge device
     * @param deviceScope         the device scope, or null
     * @param parentScopes        the scopes of the device's parents
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Device(
            String deviceId,
            String generationId,
            long version,
            String status,
            String statusReason,
            String statusUpdatedTime,
            String authType,
            String primaryKey,
            String secondaryKey,
            String primaryThumbprint,
            String secondaryThumbprint,
            boolean iotEdge,
            String deviceScope,
            List<String> parentScopes
    ) {

        public Device {
            parentScopes = parentScopes == null ? List.of() : List.copyOf(parentScopes);
        }

        /**
         * The device's ETag. Azure answers base64 of a decimal counter ({@code MjQ0NjU2NTE0} is {@code 244656514}),
         * which changes on every write; this one is the generation and the write counter in the same shape.
         */
        public String etag() {
            return Base64.getEncoder().encodeToString((generationId + version).getBytes(StandardCharsets.US_ASCII));
        }

        /** Whether the device may connect. */
        public boolean enabled() {
            return "enabled".equals(status);
        }
    }
}
