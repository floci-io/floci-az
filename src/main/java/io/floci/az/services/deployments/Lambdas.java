package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.deployments.Expression.FunctionCall;
import io.floci.az.services.deployments.Expression.Literal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.floci.az.services.deployments.TemplateValues.NODES;
import static io.floci.az.services.deployments.TemplateValues.lower;
import static io.floci.az.services.deployments.TemplateValues.typeName;

/**
 * The functions that take a {@code lambda(...)} argument ({@code template-functions-lambda.md}): {@code filter},
 * {@code groupBy}, {@code map}, {@code mapValues}, {@code reduce}, {@code sort} and {@code toObject}. The evaluator
 * sends them here unevaluated, since a lambda's body runs once per element with its variables bound, and a lambda
 * anywhere else is an error, as Azure documents. {@code filter}, {@code map} and {@code reduce} accept an optional
 * index variable; the docs give the others none, so one is refused.
 */
final class Lambdas {

    private Lambdas() {
    }

    /** True for a function this class evaluates, by its lowercase name. */
    static boolean handles(String name) {
        return switch (name) {
            case "filter", "groupby", "map", "mapvalues", "reduce", "sort", "toobject" -> true;
            default -> false;
        };
    }

    static JsonNode call(FunctionCall call, TemplateScope scope) {
        return switch (lower(call.name())) {
            case "filter" -> filter(call, scope);
            case "groupby" -> groupBy(call, scope);
            case "map" -> map(call, scope);
            case "mapvalues" -> mapValues(call, scope);
            case "reduce" -> reduce(call, scope);
            case "sort" -> sort(call, scope);
            case "toobject" -> toObject(call, scope);
            default -> throw new IllegalArgumentException(call.name());
        };
    }

    private static JsonNode filter(FunctionCall call, TemplateScope scope) {
        checkArguments(call, 2, 2);
        ArrayNode input = inputArray(call, scope);
        scope.expand(input.size());
        Lambda predicate = lambda(call, 1, 1, 2, scope);
        ArrayNode kept = NODES.arrayNode();
        for (int i = 0; i < input.size(); i++) {
            if (booleanResult(call, predicate.applyWithIndex(i, input.get(i)))) {
                kept.add(input.get(i));
            }
        }
        return kept;
    }

    private static JsonNode map(FunctionCall call, TemplateScope scope) {
        checkArguments(call, 2, 2);
        ArrayNode input = inputArray(call, scope);
        scope.expand(input.size());
        Lambda mapper = lambda(call, 1, 1, 2, scope);
        ArrayNode mapped = NODES.arrayNode();
        for (int i = 0; i < input.size(); i++) {
            mapped.add(mapper.applyWithIndex(i, input.get(i)));
        }
        return mapped;
    }

    private static JsonNode reduce(FunctionCall call, TemplateScope scope) {
        checkArguments(call, 3, 3);
        ArrayNode input = inputArray(call, scope);
        scope.expand(input.size());
        JsonNode current = ExpressionEvaluator.evaluate(call.arguments().get(1), scope);
        Lambda reducer = lambda(call, 2, 2, 3, scope);
        for (int i = 0; i < input.size(); i++) {
            current = reducer.applyWithIndex(i, current, input.get(i));
        }
        return current;
    }

    /** A stable merge sort: a predicate need not be a total order, which the JDK's sort would reject. */
    private static JsonNode sort(FunctionCall call, TemplateScope scope) {
        checkArguments(call, 2, 2);
        ArrayNode input = inputArray(call, scope);
        scope.expand(input.size());
        Lambda before = lambda(call, 1, 2, 2, scope);
        List<JsonNode> sorted = new ArrayList<>();
        input.forEach(sorted::add);
        sorted = mergeSort(sorted, call, before);
        ArrayNode result = NODES.arrayNode();
        sorted.forEach(result::add);
        return result;
    }

    private static List<JsonNode> mergeSort(List<JsonNode> values, FunctionCall call, Lambda before) {
        if (values.size() < 2) {
            return values;
        }
        int middle = values.size() / 2;
        List<JsonNode> left = mergeSort(values.subList(0, middle), call, before);
        List<JsonNode> right = mergeSort(values.subList(middle, values.size()), call, before);
        List<JsonNode> merged = new ArrayList<>(values.size());
        int l = 0;
        int r = 0;
        while (l < left.size() && r < right.size()) {
            boolean rightFirst = booleanResult(call, before.apply(right.get(r), left.get(l)))
                && !booleanResult(call, before.apply(left.get(l), right.get(r)));
            merged.add(rightFirst ? right.get(r++) : left.get(l++));
        }
        merged.addAll(left.subList(l, left.size()));
        merged.addAll(right.subList(r, right.size()));
        return merged;
    }

    private static JsonNode groupBy(FunctionCall call, TemplateScope scope) {
        checkArguments(call, 2, 2);
        ArrayNode input = inputArray(call, scope);
        scope.expand(input.size());
        Lambda key = lambda(call, 1, 1, 1, scope);
        ObjectNode groups = NODES.objectNode();
        for (JsonNode element : input) {
            String group = stringResult(call, key.apply(element));
            if (!groups.has(group)) {
                groups.putArray(group);
            }
            ((ArrayNode) groups.get(group)).add(element);
        }
        return groups;
    }

    private static JsonNode mapValues(FunctionCall call, TemplateScope scope) {
        checkArguments(call, 2, 2);
        JsonNode input = ExpressionEvaluator.evaluate(call.arguments().get(0), scope);
        if (input == null || !input.isObject()) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + call.name()
                + "': the first argument of type '" + typeName(input) + "' is not valid; expected an object.");
        }
        scope.expand(input.size());
        Lambda mapper = lambda(call, 1, 1, 1, scope);
        ObjectNode mapped = NODES.objectNode();
        input.properties().forEach(field -> mapped.set(field.getKey(), mapper.apply(field.getValue())));
        return mapped;
    }

    private static JsonNode toObject(FunctionCall call, TemplateScope scope) {
        checkArguments(call, 2, 3);
        ArrayNode input = inputArray(call, scope);
        scope.expand(input.size());
        Lambda key = lambda(call, 1, 1, 1, scope);
        Lambda value = call.arguments().size() == 3 ? lambda(call, 2, 1, 1, scope) : null;
        ObjectNode object = NODES.objectNode();
        for (JsonNode element : input) {
            object.set(stringResult(call, key.apply(element)), value == null ? element : value.apply(element));
        }
        return object;
    }

    private static void checkArguments(FunctionCall call, int min, int max) {
        int count = call.arguments().size();
        if (count < min || count > max) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + call.name()
                + "': it expects " + (min == max ? String.valueOf(min) : min + " to " + max) + " arguments but got "
                + count + ".");
        }
    }

    private static ArrayNode inputArray(FunctionCall call, TemplateScope scope) {
        JsonNode input = ExpressionEvaluator.evaluate(call.arguments().get(0), scope);
        if (!(input instanceof ArrayNode array)) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + call.name()
                + "': the first argument of type '" + typeName(input) + "' is not valid; expected an array.");
        }
        return array;
    }

    private static boolean booleanResult(FunctionCall call, JsonNode result) {
        if (result == null || !result.isBoolean()) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + call.name()
                + "': its lambda must return a boolean, not a '" + typeName(result) + "'.");
        }
        return result.booleanValue();
    }

    private static String stringResult(FunctionCall call, JsonNode result) {
        if (result == null || !result.isTextual()) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + call.name()
                + "': its lambda must return a string key, not a '" + typeName(result) + "'.");
        }
        return result.asText();
    }

    /** The {@code lambda(...)} at {@code position}, with between {@code min} and {@code max} variables. */
    private static Lambda lambda(FunctionCall call, int position, int min, int max, TemplateScope scope) {
        if (!(call.arguments().get(position) instanceof FunctionCall lambda)
                || !lambda.name().toLowerCase(Locale.ROOT).equals("lambda") || lambda.arguments().size() < 2) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + call.name()
                + "': argument " + (position + 1) + " must be a lambda function with its variables and an expression.");
        }
        List<Expression> arguments = lambda.arguments();
        List<String> variables = new ArrayList<>();
        for (Expression variable : arguments.subList(0, arguments.size() - 1)) {
            if (!(variable instanceof Literal literal) || !literal.value().isTextual()) {
                throw TemplateException.invalid("Unable to evaluate template language function 'lambda': its "
                    + "variable names must be string literals.");
            }
            variables.add(literal.value().asText());
        }
        if (variables.size() < min || variables.size() > max) {
            throw TemplateException.invalid("Unable to evaluate template language function '" + call.name()
                + "': its lambda takes " + (min == max ? String.valueOf(min) : min + " to " + max)
                + " variables but declares " + variables.size() + ".");
        }
        refuseLiveCallsOnVariables(call, arguments.getLast());
        return new Lambda(variables, arguments.getLast(), min, scope);
    }

    /**
     * A lambda variable can't be used inside {@code reference()} or a {@code list*} function
     * ({@code template-functions-lambda.md}, "Limitations"), so a body that passes one to them is refused.
     */
    private static void refuseLiveCallsOnVariables(FunctionCall call, Expression body) {
        if (body instanceof FunctionCall inner) {
            String name = lower(inner.name());
            if ((name.equals("reference") || name.equals("references") || name.startsWith("list"))
                    && inner.arguments().stream().anyMatch(Lambdas::readsLambdaVariable)) {
                throw TemplateException.invalid("Unable to evaluate template language function '" + call.name()
                    + "': a lambda variable can't be used inside the function '" + inner.name() + "'.");
            }
            inner.arguments().forEach(argument -> refuseLiveCallsOnVariables(call, argument));
        } else if (body instanceof Expression.PropertyAccess access) {
            refuseLiveCallsOnVariables(call, access.target());
        } else if (body instanceof Expression.IndexAccess access) {
            refuseLiveCallsOnVariables(call, access.target());
            refuseLiveCallsOnVariables(call, access.index());
        }
    }

    private static boolean readsLambdaVariable(Expression expression) {
        return switch (expression) {
            case FunctionCall inner -> lower(inner.name()).equals("lambdavariables")
                || inner.arguments().stream().anyMatch(Lambdas::readsLambdaVariable);
            case Expression.PropertyAccess access -> readsLambdaVariable(access.target());
            case Expression.IndexAccess access -> readsLambdaVariable(access.target())
                || readsLambdaVariable(access.index());
            case Literal ignored -> false;
        };
    }

    /** One parsed lambda; {@code required} is how many variables it has before its optional index. */
    private record Lambda(List<String> variables, Expression body, int required, TemplateScope scope) {

        JsonNode apply(JsonNode... values) {
            Map<String, JsonNode> bound = new LinkedHashMap<>();
            for (int i = 0; i < values.length; i++) {
                bound.put(variables.get(i), values[i]);
            }
            return ExpressionEvaluator.evaluate(body, new LambdaScope(scope, bound));
        }

        JsonNode applyWithIndex(int index, JsonNode... values) {
            if (variables.size() == required) {
                return apply(values);
            }
            JsonNode[] withIndex = new JsonNode[values.length + 1];
            System.arraycopy(values, 0, withIndex, 0, values.length);
            withIndex[values.length] = NODES.numberNode(index);
            return apply(withIndex);
        }
    }

    /** Everything is the enclosing scope's, except the variables this lambda binds. */
    private record LambdaScope(TemplateScope parent, Map<String, JsonNode> variables) implements TemplateScope {

        @Override
        public JsonNode deployer() {
            return parent.deployer();
        }

        @Override
        public JsonNode lambdaVariable(String name) {
            JsonNode value = variables.get(name);
            return value != null || variables.containsKey(name) ? value : parent.lambdaVariable(name);
        }

        @Override
        public long copyIndex(String loopName) {
            return parent.copyIndex(loopName);
        }

        @Override
        public void expand(int iterations) {
            parent.expand(iterations);
        }

        @Override
        public JsonNode parameter(String name) {
            return parent.parameter(name);
        }

        @Override
        public JsonNode variable(String name) {
            return parent.variable(name);
        }

        @Override
        public JsonNode userFunction(String name, List<JsonNode> arguments) {
            return parent.userFunction(name, arguments);
        }

        @Override
        public Level level() {
            return parent.level();
        }

        @Override
        public JsonNode resourceGroup() {
            return parent.resourceGroup();
        }

        @Override
        public JsonNode subscription() {
            return parent.subscription();
        }

        @Override
        public JsonNode tenant() {
            return parent.tenant();
        }

        @Override
        public JsonNode deployment() {
            return parent.deployment();
        }

        @Override
        public JsonNode environment() {
            return parent.environment();
        }

        @Override
        public JsonNode reference(String resourceNameOrId, String apiVersion, boolean full) {
            return parent.reference(resourceNameOrId, apiVersion, full);
        }

        @Override
        public JsonNode references(String symbol, boolean full) {
            return parent.references(symbol, full);
        }

        @Override
        public JsonNode list(String resourceId, String action, String apiVersion, JsonNode body) {
            return parent.list(resourceId, action, apiVersion, body);
        }
    }
}
