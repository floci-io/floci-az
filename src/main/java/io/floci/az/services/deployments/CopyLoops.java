package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Copy loops in values: an object holding a {@code copy} array of {@code {"name", "count", "input"}} entries gets,
 * for each entry, a property of that name whose value is {@code count} evaluations of {@code input}, the iteration
 * available to {@code copyIndex('name')}. This is property iteration in a resource's {@code properties}
 * ({@code copy-properties.md}) and in a variable's value ({@code copy-variables.md}); the {@code copy} array itself
 * is removed. A count is an integer from 0 to 800, as Azure limits it.
 *
 * <p>Azure limits one loop to 800 and a template, once its loops are expanded and its variables resolved, to 4 MB
 * ({@code best-practices.md}, "Template limits"), so nested loops cannot multiply without bound. floci-az counts
 * every iteration of one template's evaluation against {@link #MAX_EXPANSION}, 800 x 800: two fully nested loops of
 * the largest count fit, while a third level, or anything else that could not fit in 4 MB, is an
 * {@code InvalidTemplate} before its values are built.
 */
final class CopyLoops {

    static final int MAX_COUNT = 800;

    /** The copy-loop iterations one template's evaluation may build in all. */
    static final long MAX_EXPANSION = (long) MAX_COUNT * MAX_COUNT;

    private CopyLoops() {
    }

    /** {@code value} evaluated against {@code scope}, with its copy arrays expanded at any depth. */
    static JsonNode evaluate(JsonNode value, TemplateScope scope) {
        if (value instanceof ObjectNode object) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (field.getKey().equals("copy") && field.getValue().isArray()) {
                    for (JsonNode loop : field.getValue()) {
                        result.set(name(loop), loop(loop, scope));
                    }
                } else {
                    result.set(field.getKey(), evaluate(field.getValue(), scope));
                }
            }
            return result;
        }
        if (value instanceof ArrayNode array) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            array.forEach(item -> result.add(evaluate(item, scope)));
            return result;
        }
        return ExpressionEvaluator.evaluateValue(value, scope);
    }

    /** One named loop ({@code {"name", "count", "input"}}) as the array it builds. */
    static ArrayNode loop(JsonNode loop, TemplateScope scope) {
        String name = name(loop);
        int count = count(loop.get("count"), scope, name);
        scope.expand(count);
        ArrayNode items = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < count; i++) {
            items.add(evaluate(loop.get("input"), new CopyScope(scope, name, i, false)));
        }
        return items;
    }

    /** A copy loop's count, evaluated: an integer from 0 to 800. */
    static int count(JsonNode declared, TemplateScope scope, String loopName) {
        if (declared == null) {
            throw DeploymentTemplate.invalid("The copy loop '" + loopName + "' has no 'count'.");
        }
        JsonNode count = ExpressionEvaluator.evaluateValue(declared, scope);
        if (!count.isIntegralNumber() || count.asLong() < 0 || count.asLong() > MAX_COUNT) {
            throw DeploymentTemplate.invalid("The copy count of '" + loopName + "' must be an integer from 0 to "
                + MAX_COUNT + "; it is '" + count + "'.");
        }
        return count.asInt();
    }

    static String name(JsonNode loop) {
        if (!loop.path("name").isTextual() || !loop.has("input")) {
            throw DeploymentTemplate.invalid("A property copy loop needs a 'name', a 'count' and an 'input'.");
        }
        return loop.get("name").asText();
    }
}
