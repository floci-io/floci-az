package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The {@link TemplateScope} of one deployment: its parameter values, its variables, the objects
 * {@code resourceGroup()}, {@code subscription()}, {@code tenant()}, {@code deployment()} and {@code environment()}
 * return, and the live calls {@code reference()} and {@code list*()} make, which the engine answers by sending an
 * internal ARM request.
 *
 * <p>Parameter defaults and variables are evaluated on first use and kept, so a variable used by ten resources is
 * evaluated once; one that refers back to itself, directly or through others, is an {@code InvalidTemplate}.
 */
final class DeploymentScope implements TemplateScope {

    /** What the engine answers for a live call. */
    interface LiveCalls {
        JsonNode reference(String resourceNameOrId, String apiVersion, boolean full);

        JsonNode list(String resourceId, String action, String apiVersion, JsonNode body);
    }

    private final DeploymentTemplate template;
    private final Map<String, JsonNode> supplied;
    private final ObjectNode resourceGroup;
    private final ObjectNode subscription;
    private final ObjectNode tenant;
    private final ObjectNode environment;
    private final Function<DeploymentScope, ObjectNode> deployment;
    private final LiveCalls live;

    private final Map<String, JsonNode> parameterValues = new HashMap<>();
    private final Map<String, JsonNode> variableValues = new HashMap<>();
    private final Set<String> evaluating = new HashSet<>();

    /**
     * @param template      the parsed template
     * @param supplied      the parameter values the request supplied, keyed by lowercase name
     * @param resourceGroup what {@code resourceGroup()} returns
     * @param subscription  what {@code subscription()} returns
     * @param tenant        what {@code tenant()} returns
     * @param environment   what {@code environment()} returns
     * @param deployment    builds what {@code deployment()} returns; it reads the resolved parameters
     * @param live          answers {@code reference()} and {@code list*()}
     */
    DeploymentScope(DeploymentTemplate template, Map<String, JsonNode> supplied, ObjectNode resourceGroup,
                    ObjectNode subscription, ObjectNode tenant, ObjectNode environment,
                    Function<DeploymentScope, ObjectNode> deployment, LiveCalls live) {
        this.template = template;
        this.supplied = supplied;
        this.resourceGroup = resourceGroup;
        this.subscription = subscription;
        this.tenant = tenant;
        this.environment = environment;
        this.deployment = deployment;
        this.live = live;
    }

    DeploymentTemplate template() {
        return template;
    }

    @Override
    public JsonNode parameter(String name) {
        DeploymentTemplate.Parameter declared = template.parameter(name);
        if (declared == null) {
            throw DeploymentTemplate.invalid("The template parameter '" + name + "' is not found.");
        }
        String key = declared.name().toLowerCase(Locale.ROOT);
        JsonNode known = parameterValues.get(key);
        if (known != null) {
            return known;
        }
        JsonNode value = supplied.containsKey(key) ? supplied.get(key) : defaultValue(declared);
        ParameterValues.check(declared, value);
        parameterValues.put(key, value);
        return value;
    }

    @Override
    public JsonNode variable(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        JsonNode known = variableValues.get(key);
        if (known != null) {
            return known;
        }
        JsonNode declared = null;
        String declaredName = name;
        for (Map.Entry<String, JsonNode> field : template.variables().properties()) {
            if (field.getKey().equalsIgnoreCase(name)) {
                declared = field.getValue();
                declaredName = field.getKey();
            }
        }
        if (declared == null) {
            throw DeploymentTemplate.invalid("The template variable '" + name + "' is not found.");
        }
        JsonNode value = guarded("variable '" + declaredName + "'", declared);
        variableValues.put(key, value);
        return value;
    }

    @Override
    public JsonNode resourceGroup() {
        return resourceGroup.deepCopy();
    }

    @Override
    public JsonNode subscription() {
        return subscription.deepCopy();
    }

    @Override
    public JsonNode tenant() {
        return tenant.deepCopy();
    }

    @Override
    public JsonNode deployment() {
        return deployment.apply(this);
    }

    @Override
    public JsonNode environment() {
        return environment.deepCopy();
    }

    @Override
    public JsonNode reference(String resourceNameOrId, String apiVersion, boolean full) {
        return live.reference(resourceNameOrId, apiVersion, full);
    }

    @Override
    public JsonNode list(String resourceId, String action, String apiVersion, JsonNode body) {
        return live.list(resourceId, action, apiVersion, body);
    }

    /** Every declared parameter's value, in declaration order: supplied, else its default. */
    Map<String, JsonNode> resolvedParameters() {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        for (DeploymentTemplate.Parameter declared : template.parameters().values()) {
            values.put(declared.name(), parameter(declared.name()));
        }
        return values;
    }

    private JsonNode defaultValue(DeploymentTemplate.Parameter declared) {
        if (declared.defaultValue() == null) {
            throw DeploymentTemplate.invalid("The value for the template parameter '" + declared.name()
                + "' is not provided.");
        }
        return guarded("parameter '" + declared.name() + "'", declared.defaultValue());
    }

    /** Evaluates {@code value}, failing on a parameter default or variable that needs itself. */
    private JsonNode guarded(String what, JsonNode value) {
        if (!evaluating.add(what.toLowerCase(Locale.ROOT))) {
            throw DeploymentTemplate.invalid("The template " + what + " refers to itself.");
        }
        try {
            return ExpressionEvaluator.evaluateValue(value, this);
        } finally {
            evaluating.remove(what.toLowerCase(Locale.ROOT));
        }
    }

    /** An empty object, for scope objects a caller has nothing to put in. */
    static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }
}
