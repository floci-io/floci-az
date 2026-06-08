package io.floci.az.services.appconfig;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Filtering, projection and time-travel helpers for App Configuration key-values.
 *
 * <p>All methods are stateless. Labels follow the Azure convention where {@code null}, empty and the
 * literal {@code "\0"} all mean "no label".</p>
 */
public final class KvFilters {

    private KvFilters() {
    }

    /** Returns "" for null/empty/{@code \0} labels (all meaning "no label"), otherwise the value. */
    public static String normalizeLabel(String label) {
        if (label == null || label.isEmpty() || "\0".equals(label)) {
            return "";
        }
        return label;
    }

    /** Supports exact match and trailing-wildcard ({@code *}) key/name patterns. */
    public static boolean matchesKeyFilter(String value, String filter) {
        if (filter == null || "*".equals(filter)) {
            return true;
        }
        if (filter.endsWith("*")) {
            return value.startsWith(filter.substring(0, filter.length() - 1));
        }
        return value.equals(filter);
    }

    /** Null/absent filter → match all. Empty or {@code \0} → match no-label items only. */
    public static boolean matchesLabelFilter(String label, String filter) {
        if (filter == null || "*".equals(filter)) {
            return true;
        }
        return label.equals(normalizeLabel(filter));
    }

    /** Parses a {@code $select} CSV into a field list, or {@code null} when no projection is requested. */
    public static List<String> parseSelect(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        for (String f : raw.split(",")) {
            String t = f.trim();
            if (!t.isEmpty()) {
                fields.add(t);
            }
        }
        return fields.isEmpty() ? null : fields;
    }

    /** Projects an item to the requested fields; returns the item unchanged when {@code fields} is null. */
    public static Map<String, Object> applySelect(Map<String, Object> item, List<String> fields) {
        if (fields == null) {
            return item;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String f : fields) {
            if (item.containsKey(f)) {
                out.put(f, item.get(f));
            }
        }
        return out;
    }

    /**
     * Matches an item's tags against repeated {@code tags=name=value} filters (AND semantics).
     * An empty/null filter list matches everything.
     */
    @SuppressWarnings("unchecked")
    public static boolean tagsMatch(Object itemTags, List<String> tagFilters) {
        if (tagFilters == null || tagFilters.isEmpty()) {
            return true;
        }
        Map<String, Object> tags = itemTags instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        for (String tf : tagFilters) {
            int eq = tf.indexOf('=');
            String k = eq >= 0 ? tf.substring(0, eq) : tf;
            String v = eq >= 0 ? tf.substring(eq + 1) : "";
            Object actual = tags.get(k);
            if (actual == null || !v.equals(String.valueOf(actual))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses an {@code Accept-Datetime} header to an Instant, or null when unparseable. Tolerates the
     * formats clients actually send: RFC1123 (HTTP-date), ISO-8601 with {@code Z}/offset, and Python's
     * {@code str(datetime)} form ({@code "2026-06-07 12:34:56.789+00:00"} — space separator, no {@code T}).
     */
    public static Instant parseAcceptDatetime(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String h = header.trim();
        try {
            return DateTimeFormatter.RFC_1123_DATE_TIME.parse(h, Instant::from);
        } catch (RuntimeException ignored) {
            // not an HTTP-date
        }
        String iso = h.contains("T") ? h : h.replaceFirst(" ", "T");
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (RuntimeException ignored) {
            // not an offset date-time
        }
        try {
            return Instant.parse(iso);
        } catch (RuntimeException ignored) {
            // not a zulu instant
        }
        try {
            return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
