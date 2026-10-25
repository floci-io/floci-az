package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The parameter values of a deployment request ({@code properties.parameters}, each {@code {"value": ...}}) and
 * the checks a value must pass against its declaration: the declared type and the {@code allowedValues}.
 */
final class ParameterValues {

    /** Azure's spelling of each declared type in the deployment's echoed {@code parameters}. */
    private static final Map<String, String> TYPE_NAMES = Map.of("string", "String", "securestring", "SecureString",
        "int", "Int", "bool", "Bool", "object", "Object", "secureobject", "SecureObject", "array", "Array");

    private ParameterValues() {
    }

    /**
     * The supplied values keyed by lowercase name. A supplied parameter the template does not declare, and a Key
     * Vault {@code reference} (not supported yet), are each an {@code InvalidTemplate}.
     */
    static Map<String, JsonNode> supplied(DeploymentTemplate template, JsonNode parameters) {
        Map<String, JsonNode> values = new HashMap<>();
        if (parameters == null || parameters.isMissingNode() || parameters.isNull()) {
            return values;
        }
        if (!parameters.isObject()) {
            throw DeploymentTemplate.invalid("The deployment 'parameters' must be an object.");
        }
        for (Map.Entry<String, JsonNode> field : parameters.properties()) {
            if (template.parameter(field.getKey()) == null) {
                throw DeploymentTemplate.invalid("The template parameter '" + field.getKey()
                    + "' is not declared in the template, so it cannot be provided.");
            }
            JsonNode entry = field.getValue();
            if (entry.has("reference")) {
                throw DeploymentTemplate.invalid("The template parameter '" + field.getKey() + "' is a Key Vault "
                    + "reference, which floci-az does not support yet.");
            }
            if (!entry.has("value")) {
                throw DeploymentTemplate.invalid("The template parameter '" + field.getKey()
                    + "' must have a 'value'.");
            }
            values.put(field.getKey().toLowerCase(Locale.ROOT), entry.get("value"));
        }
        return values;
    }

    /** Fails unless {@code value} fits {@code declared}'s type and is one of its allowed values. */
    static void check(DeploymentTemplate.Parameter declared, JsonNode value) {
        if (!fits(declared.type(), value)) {
            throw DeploymentTemplate.invalid("The provided value for the template parameter '" + declared.name()
                + "' is not valid. Expected a value of type '" + typeName(declared) + "'.");
        }
        if (declared.allowedValues() != null && !contains(declared, value)) {
            throw DeploymentTemplate.invalid("The provided value '" + display(value) + "' for the template "
                + "parameter '" + declared.name() + "' is not valid. The parameter value is not part of the allowed "
                + "values: '" + display(declared.allowedValues()) + "'.");
        }
    }

    /** Whether {@code value} is of the declared {@code type} (lowercase, as {@link DeploymentTemplate} keeps it). */
    static boolean fits(String type, JsonNode value) {
        if (value == null) {
            return false;
        }
        return switch (type) {
            case "string", "securestring" -> value.isTextual();
            case "int" -> value.isIntegralNumber();
            case "bool" -> value.isBoolean();
            case "object", "secureobject" -> value.isObject();
            case "array" -> value.isArray();
            default -> false;
        };
    }

    /**
     * The {@code parameters} a deployment echoes: each declared parameter as {@code {"type": ..., "value": ...}},
     * a secure one with its type alone, as Azure never returns a secure value.
     */
    static ObjectNode echo(DeploymentTemplate template, Map<String, JsonNode> resolved) {
        ObjectNode echo = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, JsonNode> entry : resolved.entrySet()) {
            DeploymentTemplate.Parameter declared = template.parameter(entry.getKey());
            ObjectNode parameter = echo.putObject(declared.name());
            parameter.put("type", typeName(declared));
            if (!declared.secure()) {
                parameter.set("value", entry.getValue());
            }
        }
        return echo;
    }

    static String typeName(DeploymentTemplate.Parameter declared) {
        return TYPE_NAMES.getOrDefault(declared.type(), declared.type());
    }

    private static boolean contains(DeploymentTemplate.Parameter declared, JsonNode value) {
        for (JsonNode allowed : declared.allowedValues()) {
            if (allowed.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static String display(JsonNode value) {
        return value.isTextual() ? value.asText() : value.toString();
    }
}
