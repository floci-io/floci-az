package io.floci.az.services.resourcegraph.kql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Value semantics of the engine: the scalar types are {@link String}, {@link Long}, {@link Double},
 * {@link Boolean}, {@link Instant} (datetime), {@link Duration} (timespan) and null; dynamic values are
 * {@link Map} and {@link List}. Conversions follow Kusto: a failed conversion is null, not an error.
 */
public final class KqlValues {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TIMESPAN = Pattern.compile("(?i)^(\\d+(?:\\.\\d+)?)(d|h|m|s|ms|microsecond|tick)$");

    private KqlValues() {
    }

    public static Object normalize(Object value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger bi) {
            return bi.longValue();
        }
        if (value instanceof BigDecimal bd) {
            return bd.doubleValue();
        }
        if (value instanceof Float f) {
            return f.doubleValue();
        }
        return value;
    }

    public static boolean isNull(Object value) {
        return value == null;
    }

    public static boolean isEmpty(Object value) {
        return value == null || (value instanceof String s && s.isEmpty());
    }

    /** {@code tostring()}: dynamic values serialise as JSON, datetimes as ISO 8601, null as the empty string. */
    public static String toString(Object value) {
        value = normalize(value);
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof Duration duration) {
            return duration.toString();
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    public static Long toLong(Object value) {
        value = normalize(value);
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Double d) {
            return d.longValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                try {
                    return (long) Double.parseDouble(s.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public static Double toDouble(Object value) {
        value = normalize(value);
        if (value instanceof Long l) {
            return l.doubleValue();
        }
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static Boolean toBool(Object value) {
        value = normalize(value);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Long l) {
            return l != 0;
        }
        if (value instanceof Double d) {
            return d != 0;
        }
        if (value instanceof String s) {
            if (s.equalsIgnoreCase("true") || s.equals("1")) {
                return true;
            }
            if (s.equalsIgnoreCase("false") || s.equals("0")) {
                return false;
            }
        }
        return null;
    }

    public static Instant toDateTime(Object value) {
        value = normalize(value);
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Long l) {
            return Instant.ofEpochMilli(l);
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        String s = text.trim();
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public static Duration toTimespan(Object value) {
        value = normalize(value);
        if (value instanceof Duration d) {
            return d;
        }
        if (!(value instanceof String text)) {
            return null;
        }
        Matcher m = TIMESPAN.matcher(text.trim());
        if (m.matches()) {
            double amount = Double.parseDouble(m.group(1));
            return switch (m.group(2).toLowerCase(Locale.ROOT)) {
                case "d" -> Duration.ofMillis((long) (amount * 86_400_000));
                case "h" -> Duration.ofMillis((long) (amount * 3_600_000));
                case "m" -> Duration.ofMillis((long) (amount * 60_000));
                case "s" -> Duration.ofMillis((long) (amount * 1_000));
                case "ms" -> Duration.ofMillis((long) amount);
                case "microsecond" -> Duration.ofNanos((long) (amount * 1_000));
                default -> Duration.ofNanos((long) (amount * 100));
            };
        }
        try {
            return Duration.parse(text.trim());
        } catch (DateTimeParseException e) {
            String[] parts = text.trim().split(":");
            if (parts.length == 3) {
                try {
                    return Duration.ofHours(Long.parseLong(parts[0])).plusMinutes(Long.parseLong(parts[1]))
                        .plusMillis((long) (Double.parseDouble(parts[2]) * 1000));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }
    }

    public static Object toDynamic(Object value) {
        value = normalize(value);
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"")
                    || trimmed.equals("null") || trimmed.equals("true") || trimmed.equals("false")
                    || trimmed.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")) {
                try {
                    return normalize(MAPPER.readValue(trimmed, Object.class));
                } catch (JsonProcessingException e) {
                    return s;
                }
            }
            return s;
        }
        return value;
    }

    /** A property of a dynamic object by name, exact first, then ignoring case. */
    public static Object property(Object container, String name) {
        if (container instanceof Map<?, ?> map) {
            Object exact = map.get(name);
            if (exact != null || map.containsKey(name)) {
                return normalize(exact);
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key && key.equalsIgnoreCase(name)) {
                    return normalize(entry.getValue());
                }
            }
        }
        return null;
    }

    public static Object element(Object container, Object index) {
        if (container instanceof List<?> list && index instanceof Number n) {
            int i = n.intValue();
            if (i < 0) {
                i += list.size();
            }
            return i >= 0 && i < list.size() ? normalize(list.get(i)) : null;
        }
        if (container instanceof Map<?, ?> && index instanceof String key) {
            return property(container, key);
        }
        return null;
    }

    /** Kusto {@code ==}: same type and value; strings case-sensitive unless {@code ignoreCase}; null never equal. */
    public static boolean equal(Object x, Object y, boolean ignoreCase) {
        x = normalize(x);
        y = normalize(y);
        if (x == null || y == null) {
            return false;
        }
        if (x instanceof Long a && y instanceof Long b) {
            return a.longValue() == b.longValue();
        }
        if (x instanceof Number || y instanceof Number) {
            Double a = toDouble(x);
            Double b = toDouble(y);
            return a != null && b != null && a.doubleValue() == b.doubleValue();
        }
        if (x instanceof Instant || y instanceof Instant) {
            Instant a = toDateTime(x);
            Instant b = toDateTime(y);
            return a != null && a.equals(b);
        }
        if (x instanceof Boolean || y instanceof Boolean) {
            Boolean a = toBool(x);
            Boolean b = toBool(y);
            return a != null && a.equals(b);
        }
        if (x instanceof String a && y instanceof String b) {
            return ignoreCase ? a.equalsIgnoreCase(b) : a.equals(b);
        }
        if (x instanceof String || y instanceof String) {
            return ignoreCase ? toString(x).equalsIgnoreCase(toString(y)) : toString(x).equals(toString(y));
        }
        return x.equals(y);
    }

    /**
     * Ordering for comparisons and sorting: numbers, datetimes, timespans, booleans and strings each by
     * their own order, null below everything. Returns null when the two cannot be ordered.
     */
    public static Integer compare(Object x, Object y) {
        x = normalize(x);
        y = normalize(y);
        if (x == null || y == null) {
            return x == null && y == null ? 0 : (x == null ? -1 : 1);
        }
        if (x instanceof Long a && y instanceof Long b) {
            return Long.compare(a, b);
        }
        if (x instanceof Number || y instanceof Number) {
            Double a = toDouble(x);
            Double b = toDouble(y);
            return a == null || b == null ? null : Double.compare(a, b);
        }
        if (x instanceof Instant || y instanceof Instant) {
            Instant a = toDateTime(x);
            Instant b = toDateTime(y);
            return a == null || b == null ? null : a.compareTo(b);
        }
        if (x instanceof Duration a && y instanceof Duration b) {
            return a.compareTo(b);
        }
        if (x instanceof Boolean a && y instanceof Boolean b) {
            return a.compareTo(b);
        }
        return toString(x).compareTo(toString(y));
    }

    /** The Resource Graph column type name of a value, for the {@code table} result format. */
    public static String typeName(Object value) {
        value = normalize(value);
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Long) {
            return "integer";
        }
        if (value instanceof Double) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Instant) {
            return "datetime";
        }
        return "object";
    }

    /** A value in the shape the JSON response carries: datetimes as ISO strings, timespans as text. */
    public static Object toJson(Object value) {
        value = normalize(value);
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof Duration duration) {
            return duration.toString();
        }
        return value;
    }
}
