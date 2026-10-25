package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An ARM template (the {@code properties.template} of a deployment), read and checked once before anything is
 * evaluated: its parameters, variables, resources and outputs.
 *
 * <p>What floci-az does not serve is refused at validation with {@code InvalidTemplate}, never skipped: nested
 * {@code resources}, user-defined {@code functions}, symbolic resource names ({@code languageVersion} 2.0, whose
 * {@code resources} is an object) and nested deployments ({@code Microsoft.Resources/deployments}, which Bicep
 * modules compile to). A resource {@code copy} loop needs a {@code name} and a {@code count}; its {@code mode} is
 * {@code parallel} or {@code serial}, and a {@code batchSize} is at least 1.
 *
 * @param template   the template as sent, for {@code exportTemplate}
 * @param parameters the declared parameters, by name in declaration order; names compare case-insensitively
 * @param variables  the {@code variables} object, unevaluated
 * @param resources  the resources, in template order
 * @param outputs    the {@code outputs} object, unevaluated
 */
public record DeploymentTemplate(ObjectNode template, Map<String, Parameter> parameters, ObjectNode variables,
                                 List<Resource> resources, ObjectNode outputs) {

    /** Azure's code for every template validation and evaluation error. */
    public static final String INVALID_TEMPLATE = "InvalidTemplate";

    /** The prefix Azure gives a template validation message ("Resolve invalid template errors"). */
    public static final String VALIDATION_FAILED = "Deployment template validation failed: ";

    private static final Set<String> PARAMETER_TYPES =
        Set.of("string", "securestring", "int", "bool", "object", "secureobject", "array");

    private static final String NESTED_DEPLOYMENT = "Microsoft.Resources/deployments";

    /**
     * One declared parameter.
     *
     * @param name          the name as declared
     * @param type          the declared type, lowercase ({@code string}, {@code securestring}, {@code int}, ...)
     * @param defaultValue  the {@code defaultValue}, unevaluated, or null
     * @param allowedValues the {@code allowedValues}, or null
     */
    public record Parameter(String name, String type, JsonNode defaultValue, ArrayNode allowedValues) {

        /** A {@code securestring} or {@code secureObject}, whose value is never echoed back. */
        public boolean secure() {
            return type.startsWith("secure");
        }
    }

    /**
     * One resource, as the template declares it, unevaluated.
     *
     * @param index      its position in {@code resources}
     * @param definition the resource object
     */
    public record Resource(int index, ObjectNode definition) {
    }

    public DeploymentTemplate {
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        resources = List.copyOf(resources);
    }

    /** The declared parameter named {@code name}, compared case-insensitively, or null. */
    public Parameter parameter(String name) {
        for (Parameter parameter : parameters.values()) {
            if (parameter.name().equalsIgnoreCase(name)) {
                return parameter;
            }
        }
        return null;
    }

    /** Reads and checks {@code template}; everything Phase 1 does not serve is an {@code InvalidTemplate}. */
    public static DeploymentTemplate parse(JsonNode template) {
        if (!(template instanceof ObjectNode object)) {
            throw invalid("The template must be a JSON object.");
        }
        JsonNode functions = object.path("functions");
        if (functions.isArray() && !functions.isEmpty()) {
            throw unsupported("user-defined 'functions'");
        }
        JsonNode resources = object.path("resources");
        if (resources.isObject()) {
            throw unsupported("symbolic resource names (languageVersion 2.0)");
        }
        if (!resources.isMissingNode() && !resources.isArray()) {
            throw invalid("The template 'resources' must be an array.");
        }
        return new DeploymentTemplate(object, parameters(object.path("parameters")),
            objectOrEmpty(object.path("variables"), "variables"), resources(resources),
            objectOrEmpty(object.path("outputs"), "outputs"));
    }

    private static Map<String, Parameter> parameters(JsonNode declared) {
        Map<String, Parameter> parameters = new LinkedHashMap<>();
        if (declared.isMissingNode() || declared.isNull()) {
            return parameters;
        }
        if (!declared.isObject()) {
            throw invalid("The template 'parameters' must be an object.");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = declared.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode definition = field.getValue();
            String type = definition.path("type").asText("").toLowerCase(Locale.ROOT);
            if (!PARAMETER_TYPES.contains(type)) {
                throw invalid("The template parameter '" + field.getKey() + "' has an invalid type '"
                    + definition.path("type").asText("") + "'.");
            }
            JsonNode allowed = definition.path("allowedValues");
            parameters.put(field.getKey().toLowerCase(Locale.ROOT), new Parameter(field.getKey(), type,
                definition.has("defaultValue") ? definition.get("defaultValue") : null,
                allowed.isArray() ? (ArrayNode) allowed : null));
        }
        return parameters;
    }

    private static List<Resource> resources(JsonNode declared) {
        List<Resource> resources = new ArrayList<>();
        if (declared.isMissingNode()) {
            return resources;
        }
        for (int i = 0; i < declared.size(); i++) {
            if (!(declared.get(i) instanceof ObjectNode resource)) {
                throw invalid("The template resource at index " + i + " must be an object.");
            }
            for (String required : List.of("type", "apiVersion", "name")) {
                if (!resource.path(required).isTextual()) {
                    throw invalid("The template resource at index " + i + " is missing its '" + required + "'.");
                }
            }
            if (resource.has("copy")) {
                checkCopy(resource);
            }
            if (ArmResourcePath.segments(resource.get("type").asText()).size() > 2
                    && resource.path("properties").path("copy").isArray()) {
                throw invalid("The resource '" + resource.get("name").asText() + "' uses a property copy loop, "
                    + "which is only allowed on a top-level resource.");
            }
            JsonNode nested = resource.path("resources");
            if (nested.isArray() && !nested.isEmpty()) {
                throw unsupported("nested 'resources' (resource '" + resource.get("name").asText() + "')");
            }
            if (ArmResourcePath.normalizedType(resource.get("type").asText()).equalsIgnoreCase(NESTED_DEPLOYMENT)) {
                throw unsupported("nested deployments (resource '" + resource.get("name").asText() + "')");
            }
            resources.add(new Resource(i, resource));
        }
        return resources;
    }

    /** A resource copy loop's shape; its count is checked once it is evaluated. */
    private static void checkCopy(ObjectNode resource) {
        JsonNode copy = resource.get("copy");
        String name = resource.get("name").asText();
        if (!copy.isObject() || !copy.path("name").isTextual() || !copy.has("count")) {
            throw invalid("The copy loop of resource '" + name + "' needs a 'name' and a 'count'.");
        }
        String mode = copy.path("mode").asText("parallel");
        if (!mode.equalsIgnoreCase("parallel") && !mode.equalsIgnoreCase("serial")) {
            throw invalid("The copy loop '" + copy.get("name").asText() + "' has an invalid mode '" + mode
                + "': it is 'parallel' or 'serial'.");
        }
        if (copy.has("batchSize") && (!copy.get("batchSize").isIntegralNumber() || copy.get("batchSize").asInt() < 1)) {
            throw invalid("The copy loop '" + copy.get("name").asText() + "' needs a 'batchSize' of at least 1.");
        }
    }

    private static ObjectNode objectOrEmpty(JsonNode value, String property) {
        if (value.isMissingNode() || value.isNull()) {
            return JsonNodeFactory.instance.objectNode();
        }
        if (!(value instanceof ObjectNode object)) {
            throw invalid("The template '" + property + "' must be an object.");
        }
        return object;
    }

    /** An {@code InvalidTemplate} with Azure's validation prefix and {@code message} quoted, as Azure words it. */
    static TemplateException invalid(String message) {
        return new TemplateException(INVALID_TEMPLATE, VALIDATION_FAILED + "'" + message + "'");
    }

    private static TemplateException unsupported(String feature) {
        return invalid("The template uses " + feature + ", which floci-az does not support yet.");
    }
}
