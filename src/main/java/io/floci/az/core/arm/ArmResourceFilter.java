package io.floci.az.core.arm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The OData {@code $filter} accepted by ARM's generic resource listings —
 * {@code GET /subscriptions/{sub}/resources} and {@code .../resourceGroups/{rg}/resources} —
 * compiled to a predicate over index entries.
 *
 * <p>Ignoring the parameter is not a safe default. The azurerm provider fills its Key Vault cache
 * from {@code $filter=resourceType eq 'Microsoft.KeyVault/vaults'} and parses every returned id as
 * a vault id, so an unfiltered listing that also carries a storage account fails
 * {@code terraform destroy} outright. A filter this class does not understand is therefore
 * rejected with {@link InvalidFilterException} — answered as a {@code 400} — rather than
 * silently returning more than was asked for.</p>
 *
 * <p>Supported, per the 2021-04-01 contract: {@code eq} / {@code ne} on {@code resourceType},
 * {@code name}, {@code location}, {@code resourceGroup}, {@code tagName} and {@code tagValue};
 * {@code substringof('v', name|resourceGroup)}; {@code startswith(tagName, 'v')}; and
 * {@code and} / {@code or} between clauses, with {@code and} binding tighter. A
 * {@code tagName ... and tagValue ...} pair matches one tag carrying both, as Azure does.
 * Comparisons ignore case except on tag values. Not supported: {@code identity}, {@code plan},
 * parentheses, and Azure's habit of dropping {@code tags} from entries matched by tag.</p>
 */
public final class ArmResourceFilter {

    /** A {@code $filter} this class does not implement, or cannot parse. */
    public static final class InvalidFilterException extends RuntimeException {
        InvalidFilterException(String message) {
            super(message);
        }
    }

    private static final Pattern COMPARISON =
            Pattern.compile("^(\\w+(?:/\\w+)?)\\s+(eq|ne)\\s+'([^']*)'$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBSTRING_OF =
            Pattern.compile("^substringof\\('([^']*)'\\s*,\\s*(name|resourceGroup)\\)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STARTS_WITH_TAG =
            Pattern.compile("^startswith\\(tagName\\s*,\\s*'([^']*)'\\)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OR = Pattern.compile("\\s+or\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern AND = Pattern.compile("\\s+and\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOURCE_GROUP_IN_ID =
            Pattern.compile("/resourceGroups/([^/]+)/", Pattern.CASE_INSENSITIVE);

    private ArmResourceFilter() {
    }

    /** The predicate for a {@code $filter}; a missing or blank filter matches everything. */
    public static Predicate<Map<String, Object>> parse(String filter) {
        if (filter == null || filter.isBlank()) {
            return entry -> true;
        }
        Predicate<Map<String, Object>> any = entry -> false;
        for (String disjunct : OR.split(filter.trim())) {
            any = any.or(conjunction(disjunct));
        }
        return any;
    }

    private static Predicate<Map<String, Object>> conjunction(String group) {
        Predicate<Map<String, Object>> all = entry -> true;
        String tagName = null;
        String tagValue = null;
        for (String clause : AND.split(group.trim())) {
            Matcher m = COMPARISON.matcher(clause.trim());
            if (m.matches() && m.group(1).equalsIgnoreCase("tagName") && m.group(2).equalsIgnoreCase("eq")) {
                tagName = m.group(3);
                continue;
            }
            if (m.matches() && m.group(1).equalsIgnoreCase("tagValue") && m.group(2).equalsIgnoreCase("eq")) {
                tagValue = m.group(3);
                continue;
            }
            all = all.and(clause(clause.trim()));
        }
        // Azure reads a tagName/tagValue pair as one tag carrying both, not as two independent tests.
        if (tagName != null && tagValue != null) {
            String name = tagName;
            String value = tagValue;
            all = all.and(entry -> tags(entry).entrySet().stream()
                    .anyMatch(t -> t.getKey().equalsIgnoreCase(name) && Objects.equals(t.getValue(), value)));
        } else if (tagName != null) {
            String name = tagName;
            all = all.and(entry -> tags(entry).keySet().stream().anyMatch(k -> k.equalsIgnoreCase(name)));
        } else if (tagValue != null) {
            String value = tagValue;
            all = all.and(entry -> tags(entry).containsValue(value));
        }
        return all;
    }

    private static Predicate<Map<String, Object>> clause(String clause) {
        Matcher cmp = COMPARISON.matcher(clause);
        if (cmp.matches()) {
            String property = cmp.group(1);
            boolean negate = cmp.group(2).equalsIgnoreCase("ne");
            String expected = cmp.group(3);
            Predicate<Map<String, Object>> equalsExpected = switch (property.toLowerCase(Locale.ROOT)) {
                case "resourcetype"  -> entry -> expected.equalsIgnoreCase(text(entry.get("type")));
                case "name"          -> entry -> expected.equalsIgnoreCase(text(entry.get("name")));
                case "location"      -> entry -> expected.equalsIgnoreCase(text(entry.get("location")));
                case "resourcegroup" -> entry -> expected.equalsIgnoreCase(resourceGroupOf(entry));
                case "tagname"       -> entry -> tags(entry).keySet().stream().anyMatch(k -> k.equalsIgnoreCase(expected));
                case "tagvalue"      -> entry -> tags(entry).containsValue(expected);
                default -> throw new InvalidFilterException(
                        "The property '" + property + "' is not supported in $filter.");
            };
            return negate ? equalsExpected.negate() : equalsExpected;
        }
        Matcher sub = SUBSTRING_OF.matcher(clause);
        if (sub.matches()) {
            String needle = sub.group(1).toLowerCase(Locale.ROOT);
            boolean onName = sub.group(2).equalsIgnoreCase("name");
            return entry -> {
                String haystack = onName ? text(entry.get("name")) : resourceGroupOf(entry);
                return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
            };
        }
        Matcher prefix = STARTS_WITH_TAG.matcher(clause);
        if (prefix.matches()) {
            String needle = prefix.group(1).toLowerCase(Locale.ROOT);
            return entry -> tags(entry).keySet().stream()
                    .anyMatch(k -> k.toLowerCase(Locale.ROOT).startsWith(needle));
        }
        throw new InvalidFilterException("The filter clause '" + clause + "' is not valid.");
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    /** The resource group segment of the entry's {@code id}, or null when it has none. */
    private static String resourceGroupOf(Map<String, Object> entry) {
        String id = text(entry.get("id"));
        if (id == null) {
            return null;
        }
        Matcher m = RESOURCE_GROUP_IN_ID.matcher(id);
        return m.find() ? m.group(1) : null;
    }

    private static Map<String, String> tags(Map<String, Object> entry) {
        if (!(entry.get("tags") instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        raw.forEach((k, v) -> pairs.add(Map.entry(String.valueOf(k), v == null ? "" : String.valueOf(v))));
        return Map.ofEntries(pairs.toArray(Map.Entry[]::new));
    }
}
