package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.deployments.Expression.FunctionCall;
import io.floci.az.services.deployments.Expression.IndexAccess;
import io.floci.az.services.deployments.Expression.Literal;
import io.floci.az.services.deployments.Expression.PropertyAccess;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static io.floci.az.services.deployments.TemplateValues.NODES;
import static io.floci.az.services.deployments.TemplateValues.lower;
import static io.floci.az.services.deployments.TemplateValues.typeName;

/**
 * Evaluates ARM template language expressions against a {@link TemplateScope}.
 *
 * <p>Function names are case-insensitive. {@code if} evaluates only the branch its condition selects, and
 * {@code and} and {@code or} stop at the first argument that decides the result, so a branch that would fail
 * (a {@code reference()} to a resource the condition excludes) is never evaluated. Property access is
 * case-insensitive, as ARM matches property names.
 *
 * <p>Functions floci-az does not implement are an {@code InvalidTemplate} error, never a silent value:
 * {@code providers}, {@code references}, {@code pickZones},
 * {@code managementGroup}, {@code managementGroupResourceId}, {@code deployer}, {@code roleDefinitions}, the lambda
 * functions ({@code lambda}, {@code filter}, {@code map}, {@code reduce}, {@code sort}, {@code toObject},
 * {@code groupBy}, {@code mapValues}), the CIDR functions ({@code parseCidr}, {@code cidrSubnet}, {@code cidrHost}),
 * user-defined functions ({@code namespace.member()}), and the scope functions with arguments
 * ({@code resourceGroup(...)}, {@code subscription(...)}).
 *
 * <p>Values that differ from Azure's: {@code uniqueString()} has Azure's length and alphabet but not its hash,
 * and {@code guid()} follows the documented RFC 4122 version 5 algorithm, which Azure says it modified without saying
 * how. Both are deterministic, so a redeployment computes the same names.
 */
public final class ExpressionEvaluator {

    private ExpressionEvaluator() {
    }

    /** Evaluates one parsed expression. */
    public static JsonNode evaluate(Expression expression, TemplateScope scope) {
        return switch (expression) {
            case Literal literal -> literal.value();
            case PropertyAccess access -> property(evaluate(access.target(), scope), access.property());
            case IndexAccess access -> index(evaluate(access.target(), scope), evaluate(access.index(), scope));
            case FunctionCall call -> call(call, scope);
        };
    }

    /**
     * Evaluates every expression inside a template value: each string that is an expression is replaced by its
     * value, an escaped {@code [[...} string loses its escape, and objects and arrays are walked recursively.
     * Returns a new node; {@code value} is never changed.
     */
    public static JsonNode evaluateValue(JsonNode value, TemplateScope scope) {
        if (value == null) {
            return NODES.nullNode();
        }
        if (value.isTextual()) {
            String text = value.asText();
            if (ExpressionParser.isExpression(text)) {
                JsonNode result = evaluate(ExpressionParser.parse(text), scope);
                return result == null ? NODES.nullNode() : result.deepCopy();
            }
            return NODES.textNode(ExpressionParser.unescape(text));
        }
        if (value.isObject()) {
            ObjectNode result = NODES.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), evaluateValue(field.getValue(), scope));
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = NODES.arrayNode();
            for (JsonNode element : value) {
                result.add(evaluateValue(element, scope));
            }
            return result;
        }
        return value.deepCopy();
    }

    private static JsonNode call(FunctionCall call, TemplateScope scope) {
        String name = lower(call.name());
        return switch (name) {
            case "if" -> conditional(call, scope);
            case "and" -> logical(call, scope, true);
            case "or" -> logical(call, scope, false);
            default -> {
                List<JsonNode> arguments = new ArrayList<>();
                for (Expression argument : call.arguments()) {
                    arguments.add(evaluate(argument, scope));
                }
                yield TemplateFunctions.call(call.name(), arguments, scope);
            }
        };
    }

    private static JsonNode conditional(FunctionCall call, TemplateScope scope) {
        if (call.arguments().size() != 3) {
            throw TemplateException.invalid("Unable to evaluate template language function 'if': it expects 3 "
                + "arguments but got " + call.arguments().size() + ".");
        }
        boolean condition = booleanArgument("if", evaluate(call.arguments().get(0), scope));
        return evaluate(call.arguments().get(condition ? 1 : 2), scope);
    }

    /** {@code and} stops at the first false, {@code or} at the first true. */
    private static JsonNode logical(FunctionCall call, TemplateScope scope, boolean and) {
        if (call.arguments().size() < 2) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + call.name()
                + "': it expects at least 2 arguments but got " + call.arguments().size() + ".");
        }
        for (Expression argument : call.arguments()) {
            boolean value = booleanArgument(call.name(), evaluate(argument, scope));
            if (value != and) {
                return NODES.booleanNode(value);
            }
        }
        return NODES.booleanNode(and);
    }

    private static boolean booleanArgument(String function, JsonNode value) {
        if (value == null || !value.isBoolean()) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + function
                + "': the argument of type '" + typeName(value) + "' is not valid; expected a boolean.");
        }
        return value.booleanValue();
    }

    private static JsonNode property(JsonNode target, String name) {
        if (target == null || !target.isObject()) {
            throw TemplateException.invalid("The language expression property '" + name
                + "' can't be evaluated, property name must be accessed on an object, not a '" + typeName(target)
                + "'.");
        }
        JsonNode value = TemplateValues.property(target, name);
        if (value == null) {
            List<String> available = new ArrayList<>();
            target.fieldNames().forEachRemaining(available::add);
            throw TemplateException.invalid("The language expression property '" + name
                + "' doesn't exist, available properties are '" + String.join(", ", available) + "'.");
        }
        return value;
    }

    private static JsonNode index(JsonNode target, JsonNode index) {
        if (target != null && target.isArray() && index != null && index.isIntegralNumber()) {
            long position = index.longValue();
            if (position < 0 || position >= target.size()) {
                throw TemplateException.invalid("The language expression property array index '" + position
                    + "' is out of bounds.");
            }
            return target.get((int) position);
        }
        if (target != null && target.isObject() && index != null && index.isTextual()) {
            return property(target, index.asText());
        }
        throw TemplateException.invalid("The language expression index of type '" + typeName(index)
            + "' can't be applied to a value of type '" + typeName(target) + "'.");
    }
}
