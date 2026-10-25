package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Map;

/**
 * The comparisons a what-if runs between a resource's current state and what a deployment would send it.
 */
final class WhatIf {

    private WhatIf() {
    }

    /**
     * Adds to {@code delta} a {@code WhatIfPropertyChange} for every property {@code desired} sets whose value
     * {@code current} lacks ({@code Create}) or holds differently ({@code Modify}), recursing into objects. Property
     * names match case-insensitively, as ARM matches them, numbers by value, and a {@code location} by its canonical
     * form. An array that differs is one {@code Modify} of the whole array, where Azure lists its element changes as
     * {@code Array} children.
     */
    static void diff(String prefix, JsonNode current, JsonNode desired, ArrayNode delta) {
        for (Map.Entry<String, JsonNode> field : desired.properties()) {
            String path = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            JsonNode before = property(current, field.getKey());
            JsonNode after = field.getValue();
            if (before == null || before.isNull()) {
                if (!after.isNull()) {
                    ObjectNode change = delta.addObject();
                    change.put("path", path);
                    change.put("propertyChangeType", "Create");
                    change.set("after", after);
                }
            } else if (before.isObject() && after.isObject()) {
                diff(path, before, after, delta);
            } else if (!same(field.getKey(), before, after)) {
                ObjectNode change = delta.addObject();
                change.put("path", path);
                change.put("propertyChangeType", "Modify");
                change.set("before", before);
                change.set("after", after);
            }
        }
    }

    /** {@code before} with {@code desired}'s properties applied over it, objects merged and anything else replaced. */
    static ObjectNode applied(ObjectNode before, ObjectNode desired) {
        ObjectNode after = before.deepCopy();
        for (Map.Entry<String, JsonNode> field : desired.properties()) {
            String key = existingKey(after, field.getKey());
            JsonNode existing = after.get(key);
            if (existing instanceof ObjectNode existingObject && field.getValue() instanceof ObjectNode value) {
                after.set(key, applied(existingObject, value));
            } else {
                after.set(key, field.getValue());
            }
        }
        return after;
    }

    private static boolean same(String name, JsonNode before, JsonNode after) {
        if (before.isNumber() && after.isNumber()) {
            return before.decimalValue().compareTo(after.decimalValue()) == 0;
        }
        if (name.equalsIgnoreCase("location") && before.isTextual() && after.isTextual()) {
            return squash(before.asText()).equals(squash(after.asText()));
        }
        return before.equals(after);
    }

    private static JsonNode property(JsonNode object, String name) {
        if (object == null || !object.isObject()) {
            return null;
        }
        return object.get(existingKey((ObjectNode) object, name));
    }

    /** The spelling {@code object} already uses for {@code name}, matched case-insensitively, else {@code name}. */
    private static String existingKey(ObjectNode object, String name) {
        for (Map.Entry<String, JsonNode> field : object.properties()) {
            if (field.getKey().equalsIgnoreCase(name)) {
                return field.getKey();
            }
        }
        return name;
    }

    private static String squash(String location) {
        return location.toLowerCase(Locale.ROOT).replace(" ", "");
    }
}
