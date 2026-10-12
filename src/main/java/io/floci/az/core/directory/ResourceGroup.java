package io.floci.az.core.directory;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A resource group: {@code Microsoft.Resources/resourceGroups} in the {@code ResourceGroup} shape of the
 * Resources spec. Names are case-insensitive; {@link #name()} is the most recent spelling a client created or
 * updated the group with, which is what ARM echoes.
 *
 * @param subscriptionId    the subscription, as the request that last wrote the group spelled it
 * @param name              the group name, as most recently spelled
 * @param location          the location the group was created in
 * @param tags              the group's tags, or null when the client never sent any
 * @param managedBy         the id of the resource that manages the group, or null
 * @param provisioningState {@code Succeeded} once the group exists
 */
@RegisterForReflection
public record ResourceGroup(String subscriptionId, String name, String location, Map<String, Object> tags,
                            String managedBy, String provisioningState) {

    public static final String TYPE = "Microsoft.Resources/resourceGroups";
    public static final String SUCCEEDED = "Succeeded";

    public ResourceGroup {
        tags = tags == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    /** The ARM id, spelled as the group's subscription and name are. */
    public String id() {
        return "/subscriptions/" + subscriptionId + "/resourceGroups/" + name;
    }

    /**
     * The wire body: {@code id}, {@code name}, {@code type}, {@code location}, {@code properties}, and
     * {@code tags} and {@code managedBy} when the group has them.
     */
    public Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id());
        body.put("name", name);
        body.put("type", TYPE);
        body.put("location", location);
        if (managedBy != null) {
            body.put("managedBy", managedBy);
        }
        if (tags != null) {
            body.put("tags", tags);
        }
        body.put("properties", Map.of("provisioningState", provisioningState));
        return body;
    }
}
