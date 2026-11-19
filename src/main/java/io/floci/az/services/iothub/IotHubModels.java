package io.floci.az.services.iothub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What floci-az stores for Azure IoT Hub. */
public final class IotHubModels {

    /** The resource type of a hub, spelled as Azure spells it in {@code id} and {@code type}. */
    public static final String IOT_HUBS = "Microsoft.Devices/IotHubs";

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
            String eventHubNamespace
    ) {

        public IotHubResource {
            policies = policies == null ? List.of() : List.copyOf(policies);
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
                    incarnation, version + 1, createdAt, eventHubPath, eventHubNamespace);
        }
    }
}
