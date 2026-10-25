package io.floci.az.services.graph;

import io.floci.az.services.entra.DirectoryObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The supported Graph group filters: string equality and membership on id or displayName. */
final class GroupFilter {

    private static final String STRING = "'(?:[^']|'')*'";
    private static final Pattern COMPARISON = Pattern.compile("\\s*(id|displayName)\\s+(eq|in)\\s+(.+?)\\s*");
    private static final Pattern LITERAL = Pattern.compile(STRING);
    private static final Pattern VALUES = Pattern.compile("\\(\\s*" + STRING + "(?:\\s*,\\s*" + STRING + ")*\\s*\\)");

    private GroupFilter() {
    }

    static Predicate<DirectoryObject> compile(String filter) {
        if (filter == null || filter.isBlank()) {
            return group -> true;
        }
        Matcher comparison = COMPARISON.matcher(filter);
        if (!comparison.matches()) {
            throw new IllegalArgumentException("Supported group filters use eq or in on id or displayName.");
        }
        String property = comparison.group(1);
        String operation = comparison.group(2);
        String argument = comparison.group(3);
        Pattern shape = "eq".equals(operation) ? LITERAL : VALUES;
        if (!shape.matcher(argument).matches()) {
            throw new IllegalArgumentException("Invalid string value in group filter.");
        }
        Set<String> values = new HashSet<>();
        Matcher literals = LITERAL.matcher(argument);
        while (literals.find()) {
            String literal = literals.group();
            values.add(literal.substring(1, literal.length() - 1).replace("''", "'").toLowerCase(Locale.ROOT));
        }
        return group -> {
            String value = "id".equals(property) ? group.id() : group.text(property);
            return value != null && values.contains(value.toLowerCase(Locale.ROOT));
        };
    }
}
