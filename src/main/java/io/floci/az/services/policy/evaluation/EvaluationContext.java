package io.floci.az.services.policy.evaluation;

import io.floci.az.core.arm.ArmResourceId;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Everything a policy rule can look at while one definition is evaluated against one request: the resource
 * content, the resolved parameters, the request context, the scope objects and the {@code policy()} facts,
 * plus the {@code count} iteration bindings the rule is currently inside.
 */
final class EvaluationContext {

    /**
     * A {@code count} iteration: a value-count member bound to a name, or a field-count member whose
     * enclosing array members are in focus, keyed by the lower-cased alias of each {@code [*]} level.
     */
    record Binding(String name, Object value, Map<String, Object> focus) {
    }

    final Map<String, Object> content;
    final ArmResourceId id;
    final String resourceType;
    final String resourceName;
    final Map<String, Object> requestContext;
    final Map<String, Object> resourceGroup;
    final Map<String, Object> subscription;
    Map<String, Object> parameters = Map.of();
    Map<String, Object> policy = Map.of();

    private final Deque<Binding> bindings = new ArrayDeque<>();

    EvaluationContext(Map<String, Object> content, ArmResourceId id, String resourceType, String resourceName,
                      Map<String, Object> requestContext, Map<String, Object> resourceGroup,
                      Map<String, Object> subscription) {
        this.content = content;
        this.id = id;
        this.resourceType = resourceType;
        this.resourceName = resourceName;
        this.requestContext = requestContext;
        this.resourceGroup = resourceGroup;
        this.subscription = subscription;
    }

    /** A copy sharing the content and request facts, for another definition's parameters and policy facts. */
    EvaluationContext forDefinition(Map<String, Object> parameters, Map<String, Object> policy) {
        EvaluationContext copy = new EvaluationContext(content, id, resourceType, resourceName, requestContext,
            resourceGroup, subscription);
        copy.parameters = parameters;
        copy.policy = policy;
        return copy;
    }

    Object parameter(String name) {
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        throw new ArmExpressions.EvaluationException("The parameter '" + name + "' is not defined");
    }

    void push(Binding binding) {
        bindings.push(binding);
    }

    void pop() {
        bindings.pop();
    }

    /** The member in focus for a field-count alias, or null when no enclosing count enumerates it. */
    Object focusFor(String aliasLower) {
        for (Binding binding : bindings) {
            if (binding.focus() != null && binding.focus().containsKey(aliasLower)) {
                return binding.focus().get(aliasLower);
            }
        }
        return null;
    }

    boolean hasFocus(String aliasLower) {
        for (Binding binding : bindings) {
            if (binding.focus() != null && binding.focus().containsKey(aliasLower)) {
                return true;
            }
        }
        return false;
    }

    /** {@code current()} without a name: the innermost enumerated member. */
    Object current() {
        Iterator<Binding> inner = bindings.iterator();
        if (!inner.hasNext()) {
            throw new ArmExpressions.EvaluationException("current() is only valid inside a count expression");
        }
        return inner.next().value();
    }

    /** {@code current(name)}: a value-count member by its index name, else the alias of a field-count member. */
    Object current(String name) {
        for (Binding binding : bindings) {
            if (binding.name() != null && binding.name().equalsIgnoreCase(name)) {
                return binding.value();
            }
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("[*]") || name.contains("/")) {
            ResourceFields.FieldValue resolved = ResourceFields.resolve(name, this);
            // Inside a count over the alias's array, current() is the member's value itself, not a one-member array.
            if (resolved.collection() && hasFocus(lower.substring(0, lower.lastIndexOf("[*]") + 3))) {
                return resolved.members().isEmpty() ? null : resolved.members().getFirst();
            }
            return resolved.asValue();
        }
        throw new ArmExpressions.EvaluationException("current('" + name + "') does not name an enclosing count");
    }

    /** The {@code policy()} object: assignment, definition, set definition and reference ids. */
    static Map<String, Object> policyFacts(String assignmentId, String definitionId, String setDefinitionId,
                                           String referenceId) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("assignmentId", assignmentId);
        facts.put("definitionId", definitionId);
        facts.put("setDefinitionId", setDefinitionId);
        facts.put("definitionReferenceId", referenceId);
        return facts;
    }
}
