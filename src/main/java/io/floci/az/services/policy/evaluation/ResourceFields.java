package io.floci.az.services.policy.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a policy {@code field} against the evaluated resource: the fixed fields ({@code name},
 * {@code type}, {@code location}, {@code tags[...]} and so on) and property aliases.
 *
 * <p>Azure resolves an alias through a per-api-version path table. The emulator has no table, so it
 * resolves an alias by convention: the alias must start with the resource's type, and the remainder is a
 * property path read from the request content, looking under {@code properties} when a segment is not a
 * top-level key. That is how the published aliases map for every resource the emulator serves, nested
 * children included ({@code securityRules[*].access} reads each rule's {@code properties.access}).
 *
 * <p>An alias ending in, or passing through, {@code [*]} selects a collection: one value per array member,
 * flattened across nested arrays. A missing array selects an empty collection. Inside a field-count
 * {@code where}, each enumerated array contributes only the member in focus, so the alias selects a
 * one-member collection, which is what {@code field()} returns in Azure.
 */
final class ResourceFields {

    private static final Pattern TAG_BRACKET = Pattern.compile("(?i)^tags\\[\\s*(['\"]?)(.*?)\\1\\s*]$");
    private static final Pattern INDEXED = Pattern.compile("^(.*?)\\[(\\d+)]$");

    private ResourceFields() {
    }

    /** A resolved field: one value, or the collection an array alias selects. */
    record FieldValue(Object value, List<Object> members, boolean collection, String path) {

        static FieldValue single(Object value, String path) {
            return new FieldValue(value, null, false, path);
        }

        static FieldValue collection(List<Object> members, String path) {
            return new FieldValue(null, Collections.unmodifiableList(new ArrayList<>(members)), true, path);
        }

        /** The value a template function sees: the value itself, or the selected members as an array. */
        Object asValue() {
            return collection ? members : value;
        }

        /** What {@code field()} returns: a missing single value reads as an empty string. */
        Object asFunctionValue() {
            if (collection) {
                return members;
            }
            return value == null ? "" : value;
        }
    }

    /** One member an array alias selects, with the enclosing array members that lead to it. */
    record Member(Object value, Map<String, Object> focus) {
    }

    /** The members an array alias selects, each with the focus a count iteration binds for it. */
    static List<Member> members(String alias, EvaluationContext ctx) {
        FieldValue resolved = resolve(alias, ctx);
        if (resolved.collection()) {
            return resolveNodes(alias, ctx).stream().map(node -> new Member(node.value(), node.focus())).toList();
        }
        if (resolved.value() instanceof List<?> list) {
            return list.stream().map(value -> new Member(value, Map.of())).toList();
        }
        return List.of();
    }

    static FieldValue resolve(String field, EvaluationContext ctx) {
        String lower = field.toLowerCase(Locale.ROOT);
        Map<String, Object> content = ctx.content;
        switch (lower) {
            case "name" -> {
                return FieldValue.single(ctx.resourceName, "name");
            }
            case "fullname" -> {
                return FieldValue.single(ctx.id == null ? ctx.resourceName : String.join("/", ctx.id.names()), "fullName");
            }
            case "type" -> {
                return FieldValue.single(ctx.resourceType, "type");
            }
            case "kind" -> {
                return FieldValue.single(content.get("kind"), "kind");
            }
            case "location" -> {
                return FieldValue.single(normalizeLocation(content.get("location")), "location");
            }
            case "id" -> {
                return FieldValue.single(ctx.id == null ? content.get("id") : ctx.id.id(), "id");
            }
            case "identity.type" -> {
                return FieldValue.single(lookup(content.get("identity"), "type"), "identity.type");
            }
            case "tags" -> {
                return FieldValue.single(content.get("tags"), "tags");
            }
            default -> {
                // fall through to tag and alias forms
            }
        }
        Matcher bracket = TAG_BRACKET.matcher(field);
        if (bracket.matches()) {
            String tag = bracket.group(2).replace("''", "'");
            return FieldValue.single(lookup(content.get("tags"), tag), "tags[" + tag + "]");
        }
        if (lower.startsWith("tags.")) {
            String tag = field.substring("tags.".length());
            return FieldValue.single(lookup(content.get("tags"), tag), "tags." + tag);
        }
        return resolveAlias(field, ctx);
    }

    private static FieldValue resolveAlias(String alias, EvaluationContext ctx) {
        String lower = alias.toLowerCase(Locale.ROOT);
        String typeLower = ctx.resourceType == null ? "" : ctx.resourceType.toLowerCase(Locale.ROOT);
        boolean selectsCollection = lower.contains("[*]");
        if (typeLower.isEmpty() || !lower.startsWith(typeLower + "/")) {
            return selectsCollection ? FieldValue.collection(List.of(), alias) : FieldValue.single(null, alias);
        }
        String remainder = alias.substring(typeLower.length() + 1);
        List<Node> nodes = resolveNodes(alias, ctx);
        if (selectsCollection) {
            return FieldValue.collection(nodes.stream().map(Node::value).toList(), remainder);
        }
        return FieldValue.single(nodes.isEmpty() ? null : nodes.getFirst().value(), remainder);
    }

    /** A node reached while walking an alias, with the array members in focus on the way to it. */
    private record Node(Object value, Map<String, Object> focus) {
    }

    private static List<Node> resolveNodes(String alias, EvaluationContext ctx) {
        String lower = alias.toLowerCase(Locale.ROOT);
        String typeLower = ctx.resourceType == null ? "" : ctx.resourceType.toLowerCase(Locale.ROOT);
        if (typeLower.isEmpty() || !lower.startsWith(typeLower + "/")) {
            return List.of();
        }
        String remainder = alias.substring(typeLower.length() + 1);
        List<Node> nodes = new ArrayList<>();
        nodes.add(new Node(ctx.content, Map.of()));
        StringBuilder aliasSoFar = new StringBuilder(typeLower);
        String[] tokens = remainder.split("\\.");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            aliasSoFar.append(i == 0 ? '/' : '.');
            boolean star = token.endsWith("[*]");
            String key = star ? token.substring(0, token.length() - 3) : token;
            Integer index = null;
            Matcher indexed = INDEXED.matcher(key);
            if (indexed.matches()) {
                key = indexed.group(1);
                index = Integer.parseInt(indexed.group(2));
            }
            aliasSoFar.append(key.toLowerCase(Locale.ROOT));
            if (!key.isEmpty()) {
                List<Node> next = new ArrayList<>(nodes.size());
                for (Node node : nodes) {
                    next.add(new Node(lookup(node.value(), key), node.focus()));
                }
                nodes = next;
            }
            if (index != null) {
                List<Node> next = new ArrayList<>(nodes.size());
                for (Node node : nodes) {
                    Object value = node.value() instanceof List<?> list && index < list.size() ? list.get(index) : null;
                    next.add(new Node(value, node.focus()));
                }
                nodes = next;
            }
            if (star) {
                aliasSoFar.append("[*]");
                String focusKey = aliasSoFar.toString();
                List<Node> expanded = new ArrayList<>();
                if (ctx.hasFocus(focusKey)) {
                    Object focused = ctx.focusFor(focusKey);
                    expanded.add(new Node(focused, nodes.isEmpty() ? Map.of() : nodes.getFirst().focus()));
                } else {
                    for (Node node : nodes) {
                        if (node.value() instanceof List<?> list) {
                            for (Object member : list) {
                                Map<String, Object> focus = new LinkedHashMap<>(node.focus());
                                focus.put(focusKey, member);
                                expanded.add(new Node(member, focus));
                            }
                        }
                    }
                }
                nodes = expanded;
            }
        }
        return nodes;
    }

    /**
     * A property of {@code node} by case-insensitive key, read from the node itself or, when the node is an
     * ARM envelope, from its {@code properties}.
     */
    static Object lookup(Object node, String key) {
        if (!(node instanceof Map<?, ?> map)) {
            return null;
        }
        Object direct = get(map, key);
        if (direct != null || containsKey(map, key)) {
            return direct;
        }
        Object properties = get(map, "properties");
        return properties instanceof Map<?, ?> nested ? get(nested, key) : null;
    }

    static Object get(Map<?, ?> map, String key) {
        Object exact = map.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String k && k.equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    static boolean containsKey(Map<?, ?> map, String key) {
        if (map.containsKey(key)) {
            return true;
        }
        for (Object k : map.keySet()) {
            if (k instanceof String s && s.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    /** Azure compares locations without spaces and case: {@code East US 2} equals {@code eastus2}. */
    static String normalizeLocation(Object location) {
        return location instanceof String s ? s.replace(" ", "").toLowerCase(Locale.ROOT) : null;
    }
}
