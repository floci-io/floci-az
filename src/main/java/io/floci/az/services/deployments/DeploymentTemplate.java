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
 * <p>A child declared inside its parent's {@code resources} is read as a top-level resource carrying its parent's type
 * and name ({@link #flatten}). What floci-az does not serve is refused at validation with {@code InvalidTemplate},
 * never skipped: user-defined {@code functions}, symbolic resource names ({@code languageVersion} 2.0, whose
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
        List<ObjectNode> flat = new ArrayList<>();
        if (declared.isMissingNode()) {
            return List.of();
        }
        for (int i = 0; i < declared.size(); i++) {
            flatten(declared.get(i), null, "at index " + i, flat);
        }
        List<Resource> resources = new ArrayList<>();
        for (int i = 0; i < flat.size(); i++) {
            resources.add(new Resource(i, flat.get(i)));
        }
        return resources;
    }

    /**
     * Checks one declared resource and appends it, then its nested children, to {@code flat}. A child declared in
     * its parent's {@code resources} ({@code child-resource-name-type.md}, "Within parent resource") writes its type
     * and name as one segment each; it is appended as a top-level resource whose type and name carry the parent's,
     * right after the parent, so template order still creates the parent first. As Azure, a nested child does not
     * depend on its parent unless its {@code dependsOn} says so.
     */
    private static void flatten(JsonNode declared, ObjectNode parent, String where, List<ObjectNode> flat) {
        if (!(declared instanceof ObjectNode resource)) {
            throw invalid("The template resource " + where + " must be an object.");
        }
        for (String required : List.of("type", "apiVersion", "name")) {
            if (!resource.path(required).isTextual()) {
                throw invalid("The template resource " + where + " is missing its '" + required + "'.");
            }
        }
        ObjectNode flattened = parent == null ? resource.deepCopy() : asTopLevel(resource, parent);
        JsonNode nested = flattened.remove("resources");
        String name = flattened.get("name").asText();
        if (flattened.has("copy")) {
            checkCopy(flattened);
        }
        if (ArmResourcePath.segments(flattened.get("type").asText()).size() > 2
                && flattened.path("properties").path("copy").isArray()) {
            throw invalid("The resource '" + name + "' uses a property copy loop, which is only allowed on a "
                + "top-level resource.");
        }
        if (ArmResourcePath.normalizedType(flattened.get("type").asText()).equalsIgnoreCase(NESTED_DEPLOYMENT)) {
            throw unsupported("nested deployments (resource '" + name + "')");
        }
        flat.add(flattened);
        if (nested == null || nested.isEmpty()) {
            return;
        }
        if (!nested.isArray()) {
            throw invalid("The 'resources' of resource '" + name + "' must be an array.");
        }
        if (flattened.has("copy")) {
            throw invalid("The resource '" + name + "' has a copy loop and nested child resources; declare the "
                + "children outside the parent, with the parent's name in theirs.");
        }
        for (int i = 0; i < nested.size(); i++) {
            flatten(nested.get(i), flattened, "nested at index " + i + " of '" + name + "'", flat);
        }
    }

    /** A nested child as a top-level resource: its type and name prefixed with its parent's. */
    private static ObjectNode asTopLevel(ObjectNode child, ObjectNode parent) {
        if (child.has("copy")) {
            throw invalid("The nested resource '" + child.get("name").asText() + "' has a copy loop; declare it "
                + "outside its parent to repeat it.");
        }
        ObjectNode flattened = child.deepCopy();
        String type = child.get("type").asText();
        if (ArmResourcePath.segments(type).size() == 1) {
            flattened.put("type", ArmResourcePath.normalizedType(parent.get("type").asText()) + "/" + type);
            flattened.put("name", joined(parent.get("name").asText(), child.get("name").asText()));
        }
        return flattened;
    }

    /**
     * {@code parent + "/" + child} as a template value: plain text when both are, else one {@code concat}
     * expression, so a name built from {@code parameters()} still evaluates.
     */
    static String joined(String parent, String child) {
        if (!ExpressionParser.isExpression(parent) && !ExpressionParser.isExpression(child)) {
            return ExpressionParser.unescape(parent) + "/" + ExpressionParser.unescape(child);
        }
        return "[concat(" + argument(parent) + ", '/', " + argument(child) + ")]";
    }

    /** A template value as an argument of an expression: an expression's body, or a quoted literal. */
    private static String argument(String value) {
        if (ExpressionParser.isExpression(value)) {
            return value.substring(1, value.length() - 1);
        }
        return "'" + ExpressionParser.unescape(value).replace("'", "''") + "'";
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
