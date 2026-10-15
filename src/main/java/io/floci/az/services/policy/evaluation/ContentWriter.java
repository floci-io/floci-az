package io.floci.az.services.policy.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes an {@code append} effect's value into the request content at the path an alias names. A plain
 * alias sets the value when the property is absent, leaves an equal value alone and reports a conflict
 * for a different one, which Azure turns into a deny. An alias ending in {@code [*]} appends the value to
 * the array, creating it when missing.
 */
final class ContentWriter {

    /** Top-level ARM envelope keys; every other first segment of an alias lives under {@code properties}. */
    private static final Set<String> ENVELOPE_KEYS = Set.of("tags", "location", "sku", "identity", "kind", "zones",
        "plan", "extendedlocation", "managedby", "properties", "name", "type", "id", "etag");

    private ContentWriter() {
    }

    static String append(Map<String, Object> content, String resourceType, String field, Object value) {
        String lower = field.toLowerCase(Locale.ROOT);
        List<String> path;
        boolean appendMember = lower.endsWith("[*]");
        String target = appendMember ? field.substring(0, field.length() - 3) : field;
        if (lower.startsWith("tags[") || lower.startsWith("tags.")) {
            String tag = lower.startsWith("tags[") ? target.substring(5, target.length() - 1).replace("'", "").replace("\"", "")
                : target.substring(5);
            path = List.of("tags", tag);
        } else if (resourceType != null && lower.startsWith(resourceType.toLowerCase(Locale.ROOT) + "/")) {
            String remainder = target.substring(resourceType.length() + 1);
            List<String> segments = new ArrayList<>(List.of(remainder.split("\\.")));
            if (!ENVELOPE_KEYS.contains(segments.getFirst().toLowerCase(Locale.ROOT))) {
                segments.addFirst("properties");
            }
            path = segments;
        } else {
            path = List.of(target.split("\\."));
        }
        Map<String, Object> node = content;
        for (int i = 0; i < path.size() - 1; i++) {
            String segment = path.get(i);
            Object child = ResourceFields.get(node, segment);
            if (child == null) {
                child = new LinkedHashMap<String, Object>();
                node.put(segment, child);
            } else if (!(child instanceof Map<?, ?>)) {
                return "The append effect cannot write '" + field + "' because '" + segment + "' is not an object";
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) child;
            node = typed;
        }
        String leaf = path.getLast();
        String existingKey = null;
        for (String key : node.keySet()) {
            if (key.equalsIgnoreCase(leaf)) {
                existingKey = key;
            }
        }
        if (appendMember) {
            Object existing = existingKey == null ? null : node.get(existingKey);
            List<Object> array = existing instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
            array.add(value);
            node.put(existingKey == null ? leaf : existingKey, array);
            return null;
        }
        if (existingKey == null) {
            node.put(leaf, value);
            return null;
        }
        Object existing = node.get(existingKey);
        if (ArmExpressions.deepEquals(existing, value, true)) {
            return null;
        }
        return "The append effect would override '" + field + "' with a different value";
    }
}
