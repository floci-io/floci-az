package io.floci.az.services.policy.evaluation;

import io.floci.az.services.policy.evaluation.ArmExpressions.EvaluationException;
import io.floci.az.services.policy.evaluation.ResourceFields.FieldValue;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates a policy rule's {@code if} condition: the logical operators, {@code field} and {@code value}
 * conditions with every documented operator, and {@code count} expressions over array aliases or value
 * arrays. Each field or value condition it evaluates is appended to a trace in the shape Azure reports as
 * {@code evaluationDetails.evaluatedExpressions}.
 */
final class PolicyRuleEvaluator {

    private static final Set<String> OPERATORS = Set.of("equals", "notequals", "like", "notlike", "match",
        "notmatch", "matchinsensitively", "notmatchinsensitively", "contains", "notcontains", "in", "notin",
        "containskey", "notcontainskey", "less", "lessorequals", "greater", "greaterorequals", "exists");

    private static final Map<String, String> OPERATOR_NAMES = Map.ofEntries(
        Map.entry("equals", "Equals"), Map.entry("notequals", "NotEquals"), Map.entry("like", "Like"),
        Map.entry("notlike", "NotLike"), Map.entry("match", "Match"), Map.entry("notmatch", "NotMatch"),
        Map.entry("matchinsensitively", "MatchInsensitively"), Map.entry("notmatchinsensitively", "NotMatchInsensitively"),
        Map.entry("contains", "Contains"), Map.entry("notcontains", "NotContains"), Map.entry("in", "In"),
        Map.entry("notin", "NotIn"), Map.entry("containskey", "ContainsKey"), Map.entry("notcontainskey", "NotContainsKey"),
        Map.entry("less", "Less"), Map.entry("lessorequals", "LessOrEquals"), Map.entry("greater", "Greater"),
        Map.entry("greaterorequals", "GreaterOrEquals"), Map.entry("exists", "Exists"));

    private PolicyRuleEvaluator() {
    }

    /** Evaluates {@code condition}, appending each leaf evaluation to {@code trace}. */
    static boolean evaluate(Map<String, Object> condition, EvaluationContext ctx, List<Map<String, Object>> trace) {
        Object allOf = ResourceFields.get(condition, "allOf");
        if (allOf instanceof List<?> conditions) {
            for (Object child : conditions) {
                if (!evaluate(asMap(child, "allOf"), ctx, trace)) {
                    return false;
                }
            }
            return true;
        }
        Object anyOf = ResourceFields.get(condition, "anyOf");
        if (anyOf instanceof List<?> conditions) {
            for (Object child : conditions) {
                if (evaluate(asMap(child, "anyOf"), ctx, trace)) {
                    return true;
                }
            }
            return false;
        }
        Object not = ResourceFields.get(condition, "not");
        if (not instanceof Map<?, ?> child) {
            return !evaluate(asMap(child, "not"), ctx, trace);
        }
        String operator = operatorOf(condition);
        Object target = ArmExpressions.evaluateDeep(ResourceFields.get(condition, operator), ctx);
        Object count = ResourceFields.get(condition, "count");
        if (count instanceof Map<?, ?> countExpression) {
            long counted = count(asMap(countExpression, "count"), ctx, trace);
            boolean result = compare(counted, operator, target);
            trace.add(entry(result, "Count", String.valueOf(count), null, counted, target, operator));
            return result;
        }
        Object fieldName = ResourceFields.get(condition, "field");
        if (fieldName != null) {
            String field = ArmExpressions.stringify(ArmExpressions.evaluate(fieldName, ctx));
            FieldValue value = ResourceFields.resolve(field, ctx);
            Object compared = field.equalsIgnoreCase("location") ? normalizeLocations(target) : target;
            boolean result;
            if (value.collection()) {
                result = true;
                for (Object member : value.members()) {
                    if (!compare(member, operator, compared)) {
                        result = false;
                        break;
                    }
                }
            } else {
                result = compare(value.value(), operator, compared);
            }
            trace.add(entry(result, "Field", field, value.path(), value.asValue(), target, operator));
            return result;
        }
        if (ResourceFields.containsKey(condition, "value")) {
            Object raw = ResourceFields.get(condition, "value");
            Object value = ArmExpressions.evaluateDeep(raw, ctx);
            boolean result = compare(value, operator, target);
            trace.add(entry(result, "Value", ArmExpressions.stringify(raw), null, value, target, operator));
            return result;
        }
        throw new EvaluationException("A condition must have a field, value or count");
    }

    private static final Pattern FIELD_CALL = Pattern.compile("field\\s*\\(", Pattern.CASE_INSENSITIVE);

    /**
     * True when {@code condition} inspects a tags field anywhere: a {@code field} (or {@code count.field}) naming
     * {@code tags}, {@code tags.name}, {@code tags['name']} or {@code tags[*]}, evaluated against the definition's
     * parameters when it is an expression, or a {@code field('tags...')} call inside any expression. A field
     * expression that cannot be evaluated counts when its text mentions tags.
     */
    static boolean inspectsTags(Object condition, EvaluationContext ctx) {
        if (condition instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("field".equalsIgnoreCase(String.valueOf(entry.getKey())) && entry.getValue() != null
                        && isTagsField(entry.getValue(), ctx)) {
                    return true;
                }
                if (inspectsTags(entry.getValue(), ctx)) {
                    return true;
                }
            }
            return false;
        }
        if (condition instanceof List<?> list) {
            for (Object member : list) {
                if (inspectsTags(member, ctx)) {
                    return true;
                }
            }
            return false;
        }
        return condition instanceof String text && text.trim().startsWith("[") && expressionInspectsTags(text, ctx);
    }

    /**
     * True when a value expression reads a tags field through {@code field()}: a literal name, or one an
     * expression names once its parameters resolve. An argument the context cannot settle keeps the rule
     * eligible, since skipping it could admit a write Azure would deny.
     */
    private static boolean expressionInspectsTags(String text, EvaluationContext ctx) {
        Matcher call = FIELD_CALL.matcher(text);
        while (call.find()) {
            String argument = argumentAt(text, call.end());
            if (argument == null) {
                return true;
            }
            String trimmed = argument.trim();
            if (trimmed.startsWith("'")) {
                if (isTagsName(trimmed.substring(1))) {
                    return true;
                }
                continue;
            }
            try {
                if (isTagsName(ArmExpressions.stringify(ArmExpressions.evaluate("[" + trimmed + "]", ctx)))) {
                    return true;
                }
            } catch (RuntimeException e) {
                return true;
            }
        }
        return false;
    }

    /** The text of a call's first argument starting at {@code from}, up to its closing parenthesis; null when unbalanced. */
    private static String argumentAt(String text, int from) {
        int depth = 0;
        boolean quoted = false;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    if (depth == 0) {
                        return text.substring(from, i);
                    }
                    depth--;
                }
            }
        }
        return null;
    }

    private static boolean isTagsName(String field) {
        String name = field == null ? "" : field.trim().toLowerCase(Locale.ROOT);
        return name.equals("tags") || name.startsWith("tags.") || name.startsWith("tags[") || name.startsWith("tags'");
    }

    private static boolean isTagsField(Object fieldName, EvaluationContext ctx) {
        String field;
        try {
            field = ArmExpressions.stringify(ArmExpressions.evaluate(fieldName, ctx));
        } catch (RuntimeException e) {
            return String.valueOf(fieldName).toLowerCase(Locale.ROOT).contains("tags");
        }
        return isTagsName(field);
    }

    /** The {@code location} field compares normalised on both sides: {@code East US 2} equals {@code eastus2}. */
    private static Object normalizeLocations(Object target) {
        if (target instanceof String s) {
            return ResourceFields.normalizeLocation(s);
        }
        if (target instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object member : list) {
                out.add(member instanceof String s ? ResourceFields.normalizeLocation(s) : member);
            }
            return out;
        }
        return target;
    }

    private static long count(Map<String, Object> count, EvaluationContext ctx, List<Map<String, Object>> trace) {
        Map<String, Object> where = ResourceFields.get(count, "where") instanceof Map<?, ?> w ? asMap(w, "where") : null;
        Object fieldName = ResourceFields.get(count, "field");
        if (fieldName != null) {
            String alias = ArmExpressions.stringify(ArmExpressions.evaluate(fieldName, ctx));
            List<ResourceFields.Member> members = ResourceFields.members(alias, ctx);
            if (where == null) {
                return members.size();
            }
            long matched = 0;
            for (ResourceFields.Member member : members) {
                ctx.push(new EvaluationContext.Binding(null, member.value(), member.focus()));
                try {
                    if (evaluate(where, ctx, trace)) {
                        matched++;
                    }
                } finally {
                    ctx.pop();
                }
            }
            return matched;
        }
        Object valueExpression = ResourceFields.get(count, "value");
        Object array = ArmExpressions.evaluateDeep(valueExpression, ctx);
        if (!(array instanceof List<?> values)) {
            throw new EvaluationException("count.value must be an array");
        }
        if (where == null) {
            return values.size();
        }
        String name = ResourceFields.get(count, "name") instanceof String n ? n : "default";
        long matched = 0;
        for (Object member : values) {
            ctx.push(new EvaluationContext.Binding(name, member, null));
            try {
                if (evaluate(where, ctx, trace)) {
                    matched++;
                }
            } finally {
                ctx.pop();
            }
        }
        return matched;
    }

    private static String operatorOf(Map<String, Object> condition) {
        String found = null;
        for (String key : condition.keySet()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (OPERATORS.contains(lower)) {
                if (found != null) {
                    throw new EvaluationException("A condition may carry only one operator");
                }
                found = lower;
            }
        }
        if (found == null) {
            throw new EvaluationException("A condition must carry an operator");
        }
        return found;
    }

    /** One documented operator applied to one value. */
    static boolean compare(Object actual, String operator, Object target) {
        return switch (operator) {
            case "equals" -> ArmExpressions.deepEquals(actual, target, true);
            case "notequals" -> !ArmExpressions.deepEquals(actual, target, true);
            case "like" -> like(actual, target);
            case "notlike" -> !like(actual, target);
            case "match" -> match(actual, target, false);
            case "notmatch" -> !match(actual, target, false);
            case "matchinsensitively" -> match(actual, target, true);
            case "notmatchinsensitively" -> !match(actual, target, true);
            case "contains" -> contains(actual, target);
            case "notcontains" -> !contains(actual, target);
            case "in" -> in(actual, target);
            case "notin" -> !in(actual, target);
            case "containskey" -> containsKey(actual, target);
            case "notcontainskey" -> !containsKey(actual, target);
            case "less" -> ordered(actual, target) < 0;
            case "lessorequals" -> ordered(actual, target) <= 0;
            case "greater" -> ordered(actual, target) > 0;
            case "greaterorequals" -> ordered(actual, target) >= 0;
            case "exists" -> (actual != null) == ArmExpressions.truthy(target);
            default -> throw new EvaluationException("Unknown operator '" + operator + "'");
        };
    }

    private static boolean like(Object actual, Object target) {
        if (!(actual instanceof String s)) {
            return false;
        }
        String pattern = ArmExpressions.stringify(target);
        int star = pattern.indexOf('*');
        String lower = s.toLowerCase(Locale.ROOT);
        String p = pattern.toLowerCase(Locale.ROOT);
        if (star < 0) {
            return lower.equals(p);
        }
        String prefix = p.substring(0, star);
        String suffix = p.substring(star + 1);
        return lower.length() >= prefix.length() + suffix.length() && lower.startsWith(prefix) && lower.endsWith(suffix);
    }

    private static boolean match(Object actual, Object target, boolean insensitive) {
        if (!(actual instanceof String s)) {
            return false;
        }
        String pattern = ArmExpressions.stringify(target);
        if (s.length() != pattern.length()) {
            return false;
        }
        for (int i = 0; i < pattern.length(); i++) {
            char p = pattern.charAt(i);
            char c = s.charAt(i);
            boolean ok = switch (p) {
                case '#' -> Character.isDigit(c);
                case '?' -> Character.isLetter(c);
                case '.' -> true;
                default -> insensitive ? Character.toLowerCase(p) == Character.toLowerCase(c) : p == c;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(Object actual, Object target) {
        if (actual instanceof String s) {
            return s.toLowerCase(Locale.ROOT).contains(ArmExpressions.stringify(target).toLowerCase(Locale.ROOT));
        }
        if (actual instanceof List<?> list) {
            for (Object member : list) {
                if (ArmExpressions.deepEquals(member, target, true)) {
                    return true;
                }
            }
            return false;
        }
        if (actual instanceof Map<?, ?> map) {
            return ResourceFields.containsKey(map, ArmExpressions.stringify(target));
        }
        return false;
    }

    private static boolean in(Object actual, Object target) {
        if (!(target instanceof List<?> options)) {
            throw new EvaluationException("The in and notIn conditions need an array");
        }
        for (Object option : options) {
            if (ArmExpressions.deepEquals(actual, option, true)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsKey(Object actual, Object target) {
        return actual instanceof Map<?, ?> map && ResourceFields.containsKey(map, ArmExpressions.stringify(target));
    }

    /** Ordering for the comparison conditions: numbers, then ISO date-times, then strings ignoring case. */
    private static int ordered(Object actual, Object target) {
        if (actual == null || target == null) {
            throw new EvaluationException("Cannot compare a missing value");
        }
        if (actual instanceof Number || target instanceof Number) {
            return ArmExpressions.compareNumbers(ArmExpressions.toNumber(actual), ArmExpressions.toNumber(target));
        }
        String a = ArmExpressions.stringify(actual);
        String b = ArmExpressions.stringify(target);
        if (looksNumeric(a) && looksNumeric(b)) {
            return ArmExpressions.compareNumbers(ArmExpressions.toNumber(a), ArmExpressions.toNumber(b));
        }
        if (looksLikeDate(a) && looksLikeDate(b)) {
            return ArmExpressions.parseDateTime(a).compareTo(ArmExpressions.parseDateTime(b));
        }
        return a.compareToIgnoreCase(b);
    }

    private static final Pattern NUMERIC = Pattern.compile("-?\\d+(\\.\\d+)?");

    private static boolean looksNumeric(String text) {
        return NUMERIC.matcher(text).matches();
    }

    private static boolean looksLikeDate(String text) {
        if (text.length() < 10 || !Character.isDigit(text.charAt(0)) || text.charAt(4) != '-') {
            return false;
        }
        try {
            OffsetDateTime.parse(text);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Map<String, Object> entry(boolean result, String kind, String expression, String path,
                                             Object value, Object target, String operator) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("result", result ? "True" : "False");
        entry.put("expressionKind", kind);
        entry.put("expression", expression);
        if (path != null) {
            entry.put("path", path);
        }
        if (value != null) {
            entry.put("expressionValue", value);
        }
        entry.put("targetValue", target);
        entry.put("operator", OPERATOR_NAMES.getOrDefault(operator, operator));
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String where) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new EvaluationException("'" + where + "' must hold condition objects");
    }
}
