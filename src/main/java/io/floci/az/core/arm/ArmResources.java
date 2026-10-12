package io.floci.az.core.arm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conventions for ARM resources held as maps: services smuggle their storage keys as
 * underscore-prefixed entries and strip them before returning the resource on the wire.
 */
public final class ArmResources {

    public static final String SUB_KEY = "_sub";
    public static final String RG_KEY = "_rg";

    private ArmResources() {
    }

    /**
     * Minimal ARM resource entry for the generic {@code /resources} listings, at either scope —
     * {@code id}, {@code name}, {@code type}, {@code location}, and {@code tags} when non-empty.
     *
     * <p>Azure's generic resource listing returns identity fields only and never {@code properties};
     * its {@code $expand} adds only {@code createdTime}, {@code changedTime} and
     * {@code provisioningState}. Contributors project their resource onto this shape rather than
     * echoing the body their {@code GET} returns.</p>
     */
    public static Map<String, Object> indexEntry(String id, String name, String type, String location,
                                                 Map<String, String> tags) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("name", name);
        entry.put("type", type);
        entry.put("location", location);
        if (tags != null && !tags.isEmpty()) {
            entry.put("tags", tags);
        }
        return entry;
    }

    /** {@link #indexEntry(String, String, String, String, Map)} of a resource held as its wire body. */
    public static Map<String, Object> indexEntry(Map<String, Object> resource) {
        Map<String, String> tags = null;
        if (resource.get("tags") instanceof Map<?, ?> raw) {
            tags = new LinkedHashMap<>();
            for (Map.Entry<?, ?> tag : raw.entrySet()) {
                tags.put(String.valueOf(tag.getKey()), String.valueOf(tag.getValue()));
            }
        }
        return indexEntry(stringOrNull(resource.get("id")), stringOrNull(resource.get("name")),
            stringOrNull(resource.get("type")), stringOrNull(resource.get("location")), tags);
    }

    /**
     * Whether the resource {@code id} lies in subscription {@code subscriptionId} and, unless
     * {@code resourceGroup} is null, in that resource group; both compared case-insensitively, as ARM does.
     */
    public static boolean inScope(Object id, String subscriptionId, String resourceGroup) {
        if (!(id instanceof String text)) {
            return false;
        }
        return ArmResourceId.parse(text)
            .filter(parsed -> subscriptionId.equalsIgnoreCase(parsed.subscriptionId()))
            .filter(parsed -> resourceGroup == null || resourceGroup.equalsIgnoreCase(parsed.resourceGroup()))
            .isPresent();
    }

    private static String stringOrNull(Object value) {
        return value instanceof String text ? text : null;
    }

    /** Copy of the resource without the internal routing keys. */
    public static Map<String, Object> stripInternal(Map<String, Object> resource) {
        Map<String, Object> copy = new LinkedHashMap<>(resource);
        copy.remove(SUB_KEY);
        copy.remove(RG_KEY);
        return copy;
    }
}
