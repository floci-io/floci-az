package io.floci.az.services.deployments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/** The runtime value rules of the template language, over Jackson nodes. */
final class TemplateValues {

    static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    static final ObjectMapper MAPPER = new ObjectMapper();

    private TemplateValues() {
    }

    /** An integer as the smallest node that holds it, so equal integers serialize alike. */
    static JsonNode number(long value) {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? IntNode.valueOf((int) value)
            : LongNode.valueOf(value);
    }

    /** The template type name of a value, as error messages spell it. */
    static String typeName(JsonNode value) {
        if (value == null || value.isNull()) {
            return "Null";
        }
        if (value.isTextual()) {
            return "String";
        }
        if (value.isIntegralNumber()) {
            return "Integer";
        }
        if (value.isNumber()) {
            return "Float";
        }
        if (value.isBoolean()) {
            return "Boolean";
        }
        if (value.isArray()) {
            return "Array";
        }
        return "Object";
    }

    /**
     * Deep equality as {@code equals()} defines it: integers compare by value, strings case-sensitively
     * ({@code template-functions-comparison.md}), objects by property names case-insensitively and values deeply,
     * arrays element by element.
     */
    static boolean deepEquals(JsonNode a, JsonNode b) {
        if (isNull(a) || isNull(b)) {
            return isNull(a) && isNull(b);
        }
        if (a.isNumber() && b.isNumber()) {
            return a.isIntegralNumber() && b.isIntegralNumber() ? a.longValue() == b.longValue()
                : a.doubleValue() == b.doubleValue();
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!deepEquals(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = a.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode other = property(b, field.getKey());
                if (other == null || !deepEquals(field.getValue(), other)) {
                    return false;
                }
            }
            return true;
        }
        return a.getNodeType() == b.getNodeType() && a.equals(b);
    }

    /** A property of an object, its name compared case-insensitively; null when there is none. */
    static JsonNode property(JsonNode object, String name) {
        JsonNode exact = object.get(name);
        if (exact != null) {
            return exact;
        }
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String candidate = names.next();
            if (candidate.equalsIgnoreCase(name)) {
                return object.get(candidate);
            }
        }
        return null;
    }

    static boolean isNull(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode();
    }

    /**
     * The text {@code string()} and {@code concat()} produce: a string as is, a number or boolean in its JSON
     * form, an object or array as compact JSON ({@code template-functions-string.md}, string examples).
     */
    static String text(JsonNode value) {
        if (isNull(value)) {
            return "";
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isValueNode()) {
            return value.asText();
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw TemplateException.invalid("The value cannot be converted to a string: " + e.getOriginalMessage());
        }
    }

    static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
