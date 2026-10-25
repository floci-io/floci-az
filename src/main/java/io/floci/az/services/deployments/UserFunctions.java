package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The user-defined functions a template declares in its {@code functions} section ({@code user-defined-functions.md}),
 * called as {@code namespace.member(...)}. Each one is an output expression over its own parameters, with the
 * restrictions Azure documents, each refused with {@code InvalidTemplate}:
 *
 * <ul>
 *   <li>it reads only its own parameters, never the template's, and never {@code variables()};</li>
 *   <li>it calls no other user-defined function;</li>
 *   <li>it uses neither {@code reference()} nor a {@code list*} function;</li>
 *   <li>its parameters have no default values.</li>
 * </ul>
 *
 * <p>The scope functions ({@code resourceGroup()}, {@code subscription()}, {@code tenant()}, {@code deployment()},
 * {@code environment()}) answer for the deployment, as the documented example's {@code resourceGroup().id} relies on.
 */
final class UserFunctions {

    /** One declared member, {@code namespace.member}, with its parameters and its output. */
    record Function(String name, List<DeploymentTemplate.Parameter> parameters, String outputType,
                    JsonNode outputValue) {
    }

    static final UserFunctions NONE = new UserFunctions(Map.of());

    private final Map<String, Function> functions;

    private UserFunctions(Map<String, Function> functions) {
        this.functions = functions;
    }

    /** Reads the {@code functions} section; a missing or empty one declares nothing. */
    static UserFunctions parse(JsonNode declared) {
        if (declared == null || declared.isMissingNode() || declared.isNull()) {
            return NONE;
        }
        if (!declared.isArray()) {
            throw DeploymentTemplate.invalid("The template 'functions' must be an array.");
        }
        Map<String, Function> functions = new LinkedHashMap<>();
        for (JsonNode namespace : declared) {
            String prefix = namespace.path("namespace").asText("");
            if (prefix.isBlank()) {
                throw DeploymentTemplate.invalid("Each entry of the template 'functions' must name a 'namespace'.");
            }
            if (!namespace.path("members").isObject()) {
                throw DeploymentTemplate.invalid("The function namespace '" + prefix + "' must have 'members'.");
            }
            for (Map.Entry<String, JsonNode> member : namespace.get("members").properties()) {
                String name = prefix + "." + member.getKey();
                if (functions.put(name.toLowerCase(Locale.ROOT), function(name, member.getValue())) != null) {
                    throw DeploymentTemplate.invalid("The template function '" + name + "' is declared more than once.");
                }
            }
        }
        return new UserFunctions(functions);
    }

    /** Calls {@code name} with {@code arguments}, its scope functions answering as {@code outer} does. */
    JsonNode call(String name, List<JsonNode> arguments, TemplateScope outer) {
        Function function = functions.get(name.toLowerCase(Locale.ROOT));
        if (function == null) {
            throw TemplateException.invalid("The template function '" + name
                + "' is not found. Please see https://aka.ms/arm-functions for usage details.");
        }
        if (arguments.size() != function.parameters().size()) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + function.name()
                + "': it expects " + function.parameters().size() + " arguments but got " + arguments.size() + ".");
        }
        Map<String, JsonNode> values = new LinkedHashMap<>();
        for (int i = 0; i < arguments.size(); i++) {
            DeploymentTemplate.Parameter parameter = function.parameters().get(i);
            JsonNode value = arguments.get(i);
            if (!ParameterValues.fits(parameter.type(), value)) {
                throw TemplateException.invalid("Unable to evaluate template language function '" + function.name()
                    + "': the value for its parameter '" + parameter.name() + "' is not valid. Expected a value of "
                    + "type '" + ParameterValues.typeName(parameter) + "'.");
            }
            values.put(parameter.name().toLowerCase(Locale.ROOT), value);
        }
        JsonNode output = ExpressionEvaluator.evaluateValue(function.outputValue(),
            new FunctionScope(function, values, outer));
        if (!ParameterValues.fits(function.outputType(), output)) {
            throw TemplateException.invalid("The output of the template function '" + function.name()
                + "' is not valid. Expected a value of type '" + function.outputType() + "'.");
        }
        return output;
    }

    private static Function function(String name, JsonNode definition) {
        List<DeploymentTemplate.Parameter> parameters = new ArrayList<>();
        JsonNode declared = definition.path("parameters");
        if (!declared.isMissingNode() && !declared.isArray()) {
            throw DeploymentTemplate.invalid("The 'parameters' of the template function '" + name
                + "' must be an array.");
        }
        for (JsonNode parameter : declared) {
            String parameterName = parameter.path("name").asText("");
            String type = DeploymentTemplate.declaredType(parameter.path("type"));
            if (parameterName.isBlank() || type == null) {
                throw DeploymentTemplate.invalid("Each parameter of the template function '" + name
                    + "' must have a 'name' and a valid 'type'.");
            }
            if (parameter.has("defaultValue")) {
                throw DeploymentTemplate.invalid("The parameter '" + parameterName + "' of the template function '"
                    + name + "' has a default value; parameters of a user-defined function can't.");
            }
            parameters.add(new DeploymentTemplate.Parameter(parameterName, type, null, null));
        }
        JsonNode output = definition.path("output");
        String outputType = DeploymentTemplate.declaredType(output.path("type"));
        if (outputType == null || !output.has("value")) {
            throw DeploymentTemplate.invalid("The template function '" + name
                + "' must have an 'output' with a valid 'type' and a 'value'.");
        }
        return new Function(name, List.copyOf(parameters), outputType, output.get("value"));
    }

    /** What a user-defined function's output expression can read: its own parameters and the scope functions. */
    private record FunctionScope(Function function, Map<String, JsonNode> values, TemplateScope outer)
            implements TemplateScope {

        @Override
        public JsonNode parameter(String name) {
            JsonNode value = values.get(name.toLowerCase(Locale.ROOT));
            if (value == null) {
                throw TemplateException.invalid("The template function '" + function.name() + "' has no parameter '"
                    + name + "': a user-defined function can only use the parameters it defines.");
            }
            return value;
        }

        @Override
        public JsonNode variable(String name) {
            throw restricted("can't access variables");
        }

        @Override
        public JsonNode resourceGroup() {
            return outer.resourceGroup();
        }

        @Override
        public JsonNode subscription() {
            return outer.subscription();
        }

        @Override
        public JsonNode tenant() {
            return outer.tenant();
        }

        @Override
        public JsonNode deployment() {
            return outer.deployment();
        }

        @Override
        public JsonNode environment() {
            return outer.environment();
        }

        @Override
        public JsonNode reference(String resourceNameOrId, String apiVersion, boolean full) {
            throw restricted("can't use the 'reference' function");
        }

        @Override
        public JsonNode list(String resourceId, String action, String apiVersion, JsonNode body) {
            throw restricted("can't use the list functions");
        }

        @Override
        public JsonNode userFunction(String name, List<JsonNode> arguments) {
            throw restricted("can't call other user-defined functions");
        }

        @Override
        public Level level() {
            return outer.level();
        }

        private TemplateException restricted(String what) {
            return TemplateException.invalid("The template function '" + function.name() + "' " + what
                + ": a user-defined function may only use its own parameters and the scope functions.");
        }
    }
}
