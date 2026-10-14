package io.floci.az.services.monitor.activitylog;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code $filter} of the Activity Logs list operation: {@code eventTimestamp ge '...'} is required,
 * every other clause narrows the result, and clauses join with {@code and}. Beyond the clauses the stable
 * api-version documents ({@code eventTimestamp le}, {@code resourceGroupName}, {@code resourceUri} /
 * {@code resourceId}, {@code resourceProvider}, {@code correlationId}) the preview grammar's
 * {@code eventChannels}, {@code levels}, {@code operations}, {@code status}, {@code subStatus},
 * {@code caller}, {@code category} and {@code searchText} are accepted, each with a comma-separated
 * list of values where the service allows one.
 */
public final class ActivityLogFilter {

    /** A filter the grammar does not allow, answered as ARM's {@code BadRequest}. */
    public static final class InvalidFilterException extends RuntimeException {
        InvalidFilterException(String message) {
            super(message);
        }
    }

    private static final Pattern CLAUSE = Pattern.compile("(?i)^\\s*(\\w+)\\s+(ge|gt|le|lt|eq)\\s+'((?:[^']|'')*)'\\s*$");
    private static final Set<String> LIST_FIELDS = Set.of("eventchannels", "levels", "operations", "status", "substatus", "category");

    private ActivityLogFilter() {
    }

    public static Predicate<ActivityLogEvent> parse(String filter) {
        if (filter == null || filter.isBlank()) {
            throw new InvalidFilterException("The $filter argument is required and must include 'eventTimestamp ge <datetime>'.");
        }
        List<Predicate<ActivityLogEvent>> predicates = new ArrayList<>();
        boolean hasStart = false;
        for (String clause : splitOnAnd(filter)) {
            Matcher m = CLAUSE.matcher(clause);
            if (!m.matches()) {
                throw new InvalidFilterException("The $filter clause '" + clause.trim() + "' is not supported.");
            }
            String field = m.group(1).toLowerCase(Locale.ROOT);
            String op = m.group(2).toLowerCase(Locale.ROOT);
            String value = m.group(3).replace("''", "'");
            if (field.equals("eventtimestamp")) {
                Instant instant = instant(value);
                switch (op) {
                    case "ge" -> {
                        hasStart = true;
                        predicates.add(e -> !e.eventTimestamp().isBefore(instant));
                    }
                    case "gt" -> {
                        hasStart = true;
                        predicates.add(e -> e.eventTimestamp().isAfter(instant));
                    }
                    case "le" -> predicates.add(e -> !e.eventTimestamp().isAfter(instant));
                    case "lt" -> predicates.add(e -> e.eventTimestamp().isBefore(instant));
                    default -> throw new InvalidFilterException("eventTimestamp supports ge, gt, le and lt.");
                }
                continue;
            }
            if (!op.equals("eq")) {
                throw new InvalidFilterException("The field '" + m.group(1) + "' only supports 'eq'.");
            }
            predicates.add(equality(field, m.group(1), value));
        }
        if (!hasStart) {
            throw new InvalidFilterException("The $filter must include 'eventTimestamp ge <datetime>'.");
        }
        return predicates.stream().reduce(e -> true, Predicate::and);
    }

    private static Predicate<ActivityLogEvent> equality(String field, String given, String value) {
        List<String> values = LIST_FIELDS.contains(field)
            ? List.of(value.split("\\s*,\\s*")) : List.of(value);
        return switch (field) {
            case "resourcegroupname" -> e -> value.equalsIgnoreCase(e.resourceGroupName());
            case "resourceuri", "resourceid" -> e -> value.equalsIgnoreCase(e.resourceId());
            case "resourceprovider", "resourceprovidername" -> e -> value.equalsIgnoreCase(e.resourceProviderName());
            case "correlationid" -> e -> value.equalsIgnoreCase(e.correlationId());
            case "caller" -> e -> value.equalsIgnoreCase(e.caller());
            case "eventchannels" -> e -> containsIgnoreCase(values, "Operation") || containsIgnoreCase(values, "Admin, Operation");
            case "levels" -> e -> containsIgnoreCase(values, e.level());
            case "operations" -> e -> containsIgnoreCase(values, e.operationName());
            case "status" -> e -> containsIgnoreCase(values, e.status());
            case "substatus" -> e -> containsIgnoreCase(values, e.subStatus());
            case "category", "eventcategory" -> e -> containsIgnoreCase(values, e.category());
            case "searchtext" -> {
                String needle = value.toLowerCase(Locale.ROOT);
                yield e -> ActivityLog.json(e.toJson(Set.of())).toLowerCase(Locale.ROOT).contains(needle);
            }
            default -> throw new InvalidFilterException("The field '" + given + "' is not supported in $filter.");
        };
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        if (candidate == null) {
            return false;
        }
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Splits on {@code and} outside quotes. */
    static List<String> splitOnAnd(String filter) {
        List<String> clauses = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        while (i < filter.length()) {
            char c = filter.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
                current.append(c);
                i++;
            } else if (!quoted && i + 5 <= filter.length() && filter.regionMatches(true, i, " and ", 0, 5)) {
                clauses.add(current.toString());
                current.setLength(0);
                i += 5;
            } else {
                current.append(c);
                i++;
            }
        }
        clauses.add(current.toString());
        return clauses;
    }

    static Instant instant(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ignored) {
                throw new InvalidFilterException("'" + text + "' is not an ISO 8601 date-time.");
            }
        }
    }
}
