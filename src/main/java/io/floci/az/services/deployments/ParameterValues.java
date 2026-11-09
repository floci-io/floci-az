package io.floci.az.services.deployments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The parameter values of a deployment request ({@code properties.parameters}, each {@code {"value": ...}}) and
 * the checks a value must pass against its declaration: the declared type and the {@code allowedValues}.
 */
final class ParameterValues {

    private static final Logger LOG = Logger.getLogger(ParameterValues.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Azure's spelling of each declared type in the deployment's echoed {@code parameters}. */
    private static final Map<String, String> TYPE_NAMES = Map.of("string", "String", "securestring", "SecureString",
        "int", "Int", "bool", "Bool", "object", "Object", "secureobject", "SecureObject", "array", "Array");

    private ParameterValues() {
    }

    /** Reads the secret a Key Vault parameter reference names. */
    interface SecretReader {
        /** @param secretVersion the version the reference pins, or null for the current one */
        JsonNode read(String vaultId, String secretName, String secretVersion);
    }

    /**
     * The supplied values keyed by lowercase name, a Key Vault {@code reference} resolved through {@code secrets}
     * ({@code key-vault-parameter.md}). A supplied parameter the template does not declare is an
     * {@code InvalidTemplate}, and so is a reference to a parameter that is not secure, which the deployment would echo.
     */
    static Map<String, JsonNode> supplied(DeploymentTemplate template, JsonNode parameters, SecretReader secrets) {
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
            // azd sends every parameter as {"value": ..., "reference": null}, so a null reference is no reference.
            if (entry.path("reference").isObject()) {
                values.put(field.getKey().toLowerCase(Locale.ROOT),
                    secretReference(template.parameter(field.getKey()), entry.get("reference"), secrets));
                continue;
            }
            if (!entry.has("value")) {
                throw DeploymentTemplate.invalid("The template parameter '" + field.getKey()
                    + "' must have a 'value'.");
            }
            values.put(field.getKey().toLowerCase(Locale.ROOT), entry.get("value"));
        }
        return values;
    }

    private static JsonNode secretReference(DeploymentTemplate.Parameter declared, JsonNode reference,
                                            SecretReader secrets) {
        if (!declared.secure()) {
            throw DeploymentTemplate.invalid("The template parameter '" + declared.name() + "' is a Key Vault "
                + "reference, so it must be declared 'securestring' or 'secureObject'.");
        }
        JsonNode vaultId = reference.path("keyVault").path("id");
        JsonNode secretName = reference.path("secretName");
        if (!vaultId.isTextual() || !secretName.isTextual()) {
            throw DeploymentTemplate.invalid("The Key Vault reference of the template parameter '" + declared.name()
                + "' needs a 'keyVault' id and a 'secretName'.");
        }
        JsonNode version = reference.path("secretVersion");
        JsonNode secret = secrets.read(vaultId.asText(), secretName.asText(), version.isTextual() ? version.asText() : null);
        if (!declared.type().equals("secureobject")) {
            return secret;
        }
        // A secret is text; a secureObject parameter reads it as the JSON object it holds.
        try {
            JsonNode parsed = MAPPER.readTree(secret.asText());
            if (parsed != null && parsed.isObject()) {
                return parsed;
            }
        } catch (JsonProcessingException e) {
            // The parser's message quotes the text it failed on, which here is the secret, so it is never logged.
            LOG.debugv("The secret for parameter {0} is not JSON", declared.name());
        }
        throw DeploymentTemplate.invalid("The Key Vault secret for the template parameter '" + declared.name()
            + "' is not a JSON object, which a 'secureObject' parameter needs.");
    }

    /** Fails unless {@code value} fits {@code declared}'s type and is one of its allowed values. */
    static void check(DeploymentTemplate.Parameter declared, JsonNode value) {
        if (declared.nullable() && value != null && value.isNull()) {
            return;
        }
        if (!fits(declared.type(), value)) {
            throw DeploymentTemplate.invalid("The provided value for the template parameter '" + declared.name()
                + "' is not valid. Expected a value of type '" + typeName(declared) + "'.");
        }
        if (declared.allowedValues() != null && !contains(declared, value)) {
            // A secure value never appears in an answer, here neither: it may come from a Key Vault, not the request.
            String shown = declared.secure() ? "" : " '" + display(value) + "'";
            throw DeploymentTemplate.invalid("The provided value" + shown + " for the template "
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
