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
     * Minimal ARM resource entry for the resource-group {@code /resources} index —
     * {@code id}, {@code name}, {@code type}, {@code location}, and {@code tags} when non-empty.
     *
     * <p>Azure's generic resource listing returns identity fields only; {@code properties} arrives
     * solely under {@code $expand}. Contributors project their resource onto this shape rather than
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

    /** Copy of the resource without the internal routing keys. */
    public static Map<String, Object> stripInternal(Map<String, Object> resource) {
        Map<String, Object> copy = new LinkedHashMap<>(resource);
        copy.remove(SUB_KEY);
        copy.remove(RG_KEY);
        return copy;
    }
}
