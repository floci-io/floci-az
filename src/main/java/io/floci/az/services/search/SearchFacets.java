package io.floci.az.services.search;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.search.SearchDocuments.Refused;
import io.floci.az.services.search.SearchSolrSchema.Layout;
import io.floci.az.services.search.SearchSolrSchema.ValueField;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The {@code facets} of a search, run by Solr's JSON Facet API over the documents the search finds.
 *
 * <p>An expression is a facetable field's path, then {@code name:value} pairs, as Learn describes them:
 * <ul>
 *   <li>by value: {@code count} (default 10, 0 for every value) and {@code sort} ({@code count}, the default, or
 *       {@code -count}, {@code value}, {@code -value}); the most frequent values are chosen, then ordered by
 *       {@code sort}. A bucket is {@code {count, value}}.</li>
 *   <li>{@code values:a|b|c}: ranges of numbers or dates, from inclusive to exclusive, the first without a lower and
 *       the last without an upper bound. A bucket is {@code {from, to, count}}.</li>
 *   <li>{@code interval}: a number, or for dates {@code minute}, {@code hour}, {@code day}, {@code week} (from Monday),
 *       {@code month}, {@code quarter} or {@code year}, in UTC unless {@code timeoffset} names another offset. A
 *       bucket is {@code {count, value}}, the value its start, for each interval that holds a value. Solr first
 *       answers the field's lowest and highest value, and the intervals between them are counted; when there would
 *       be more than {@link #MAX_BUCKETS}, Solr answers the distinct values and only their intervals are.</li>
 * </ul>
 * Counts are of documents: one whose collection holds two values in a bucket counts once. A facet on a field of the
 * elements of a complex collection counts the documents, not the elements.
 */
final class SearchFacets {

    /** A range boundary's date, with milliseconds; {@code XXX} writes a zero offset as {@code Z}. */
    private static final DateTimeFormatter RANGE_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX");

    private static final List<String> SORTS = List.of("count", "-count", "value", "-value");
    private static final List<String> DATE_INTERVALS = List.of("minute", "hour", "day", "week", "month", "quarter",
            "year");
    private static final Pattern OFFSET = Pattern.compile("[+-]\\d{2}(:?\\d{2})?");
    private static final int DEFAULT_COUNT = 10;
    /**
     * The most buckets an interval facet counts between its field's lowest and highest value. Past it, the values are
     * sparse over a wide range, and the buckets are found from the distinct values instead.
     */
    static final int MAX_BUCKETS = 1000;
    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

    /** One facet expression, read and checked. */
    record Facet(String path, ValueField field, int count, String sort, List<Object> boundaries, String interval,
                 ZoneOffset offset) {
        boolean byInterval() {
            return interval != null;
        }
    }

    /** The facets of a search. */
    record Plan(List<Facet> facets) {

        /** Whether an interval facet needs its buckets found first. */
        boolean needsIntervals() {
            return facets.stream().anyMatch(Facet::byInterval);
        }

        /** The facets of the request that answers each interval facet field's lowest and highest finite value. */
        Map<String, Object> boundsRequest() {
            Map<String, Object> json = new LinkedHashMap<>();
            for (int i = 0; i < facets.size(); i++) {
                Facet facet = facets.get(i);
                if (facet.byInterval()) {
                    String name = facet.field().solrName();
                    // INF and -INF fall in no interval: an interval of infinite width has no start.
                    String finite = "Edm.Double".equals(facet.field().type())
                            ? name + ":{\"-Infinity\" TO \"Infinity\"}" : name + ":[* TO *]";
                    Map<String, Object> range = new LinkedHashMap<>();
                    range.put("type", "query");
                    range.put("q", finite);
                    range.put("facet", Map.of("lo", "min(" + name + ")", "hi", "max(" + name + ")"));
                    json.put("b" + i, range);
                }
            }
            return json;
        }

        /**
         * The facets of the request that answers the distinct values of each interval facet whose range holds more
         * than {@link #MAX_BUCKETS} buckets, given the answer to {@link #boundsRequest()}; empty when none does.
         */
        Map<String, Object> distinctRequest(JsonNode bounds) {
            Map<String, Object> json = new LinkedHashMap<>();
            for (int i = 0; i < facets.size(); i++) {
                Facet facet = facets.get(i);
                if (facet.byInterval() && spanned(facet, bounds.path("b" + i)) == null) {
                    json.put("d" + i, terms(facet.field().solrName(), -1));
                }
            }
            return json;
        }

        /** The intervals of each interval facet, by its position, from the two answers above. */
        Map<Integer, List<Object[]>> intervals(JsonNode bounds, JsonNode distinct) {
            Map<Integer, List<Object[]>> intervals = new LinkedHashMap<>();
            for (int i = 0; i < facets.size(); i++) {
                Facet facet = facets.get(i);
                if (facet.byInterval()) {
                    List<Object[]> spanned = spanned(facet, bounds.path("b" + i));
                    intervals.put(i, spanned != null ? spanned : SearchFacets.intervals(facet, distinct.path("d" + i)));
                }
            }
            return intervals;
        }

        /** The JSON facets of the search, given the {@link #intervals} of its interval facets. */
        Map<String, Object> solr(Map<Integer, List<Object[]>> intervals) {
            Map<String, Object> json = new LinkedHashMap<>();
            for (int i = 0; i < facets.size(); i++) {
                Facet facet = facets.get(i);
                String name = facet.field().solrName();
                if (facet.byInterval()) {
                    List<Object[]> buckets = intervals.get(i);
                    for (int j = 0; j < buckets.size(); j++) {
                        json.put("f" + i + "_" + j, query(name + ":[" + literal(facet, buckets.get(j)[0]) + " TO "
                                + literal(facet, buckets.get(j)[1]) + "}"));
                    }
                } else if (facet.boundaries() != null) {
                    List<Object> bounds = facet.boundaries();
                    for (int j = 0; j <= bounds.size(); j++) {
                        String from = j == 0 ? "*" : literal(facet, bounds.get(j - 1));
                        String to = j == bounds.size() ? "*" : literal(facet, bounds.get(j));
                        json.put("f" + i + "_" + j, query(name + ":[" + from + " TO " + to + "}"));
                    }
                } else {
                    json.put("f" + i, terms(name, facet.count() == 0 ? -1 : facet.count()));
                }
            }
            return json;
        }

        /** {@code @search.facets}, from Solr's facets and the {@link #intervals} of the interval facets. */
        Map<String, Object> read(JsonNode answer, Map<Integer, List<Object[]>> intervalsByFacet) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (int i = 0; i < facets.size(); i++) {
                Facet facet = facets.get(i);
                List<Map<String, Object>> buckets = new ArrayList<>();
                if (facet.byInterval()) {
                    List<Object[]> intervals = intervalsByFacet.getOrDefault(i, List.of());
                    for (int j = 0; j < intervals.size(); j++) {
                        long count = answer.path("f" + i + "_" + j).path("count").asLong();
                        if (count > 0) {
                            buckets.add(bucket(count, value(facet, intervals.get(j)[0])));
                        }
                    }
                } else if (facet.boundaries() != null) {
                    List<Object> bounds = facet.boundaries();
                    for (int j = 0; j <= bounds.size(); j++) {
                        Map<String, Object> bucket = new LinkedHashMap<>();
                        if (j > 0) {
                            bucket.put("from", bound(facet, bounds.get(j - 1)));
                        }
                        if (j < bounds.size()) {
                            bucket.put("to", bound(facet, bounds.get(j)));
                        }
                        bucket.put("count", answer.path("f" + i + "_" + j).path("count").asLong());
                        buckets.add(bucket);
                    }
                } else {
                    for (JsonNode found : answer.path("f" + i).path("buckets")) {
                        buckets.add(bucket(found.path("count").asLong(), solrValue(facet.field(), found.path("val"))));
                    }
                    order(buckets, facet.sort());
                }
                result.put(facet.path(), buckets);
            }
            return result;
        }
    }

    private SearchFacets() {
    }

    /** Reads and checks the facet expressions of a search. */
    static Plan parse(List<?> expressions, Map<String, Object> definition, Layout layout) {
        List<Facet> facets = new ArrayList<>();
        for (Object expression : expressions) {
            facets.add(facet(String.valueOf(expression), definition, layout));
        }
        return new Plan(List.copyOf(facets));
    }

    private static Facet facet(String expression, Map<String, Object> definition, Layout layout) {
        String[] parts = expression.split(",");
        String path = parts[0].trim();
        ValueField field = facetable(path, definition, layout);
        Integer count = null;
        String sort = null;
        List<Object> boundaries = null;
        String interval = null;
        ZoneOffset offset = null;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            int colon = part.indexOf(':');
            if (colon < 0) {
                throw refuse("The facet parameter '" + part + "' of '" + expression + "' is not of the form"
                        + " 'name:value'.");
            }
            String name = part.substring(0, colon).trim();
            String value = part.substring(colon + 1).trim();
            switch (name) {
                case "count" -> count = count(value, expression);
                case "sort" -> {
                    if (!SORTS.contains(value)) {
                        throw refuse("The facet sort '" + value + "' is not one of count, -count, value or -value.");
                    }
                    sort = value;
                }
                case "values" -> boundaries = boundaries(value, field, expression);
                case "interval" -> interval = interval(value, field, expression);
                case "timeoffset" -> offset = offset(value, field, expression);
                default -> throw refuse("The facet parameter '" + name + "' of '" + expression + "' is not one of"
                        + " count, sort, values, interval or timeoffset.");
            }
        }
        if ((count != null || sort != null) && (boundaries != null || interval != null)) {
            throw refuse("The facet '" + expression + "' cannot combine count or sort with values or interval.");
        }
        if (boundaries != null && interval != null) {
            throw refuse("The facet '" + expression + "' cannot combine values with interval.");
        }
        if (offset != null && interval == null) {
            throw refuse("The facet '" + expression + "' sets timeoffset without an interval.");
        }
        return new Facet(path, field, count == null ? DEFAULT_COUNT : count, sort == null ? "count" : sort,
                boundaries, interval, offset == null ? ZoneOffset.UTC : offset);
    }

    private static ValueField facetable(String path, Map<String, Object> definition, Layout layout) {
        if (SearchSolrSchema.definitionField(definition, path) == null) {
            throw refuse("Could not find a property named '" + path + "' on type 'search.document'.");
        }
        return layout.valueField(path).filter(ValueField::facetable)
                .orElseThrow(() -> refuse("'" + path + "' is not a facetable field. Only facetable fields can be"
                        + " used in facets."));
    }

    private static int count(String value, String expression) {
        try {
            int count = Integer.parseInt(value);
            if (count >= 0) {
                return count;
            }
        } catch (NumberFormatException ignored) {
            // Refused below, as a negative count is.
        }
        throw refuse("The facet count of '" + expression + "' must be a whole number, zero or greater.");
    }

    private static List<Object> boundaries(String value, ValueField field, String expression) {
        List<Object> bounds = new ArrayList<>();
        for (String text : value.split("\\|")) {
            bounds.add(ordinal(text.trim(), field, expression));
        }
        for (int i = 1; i < bounds.size(); i++) {
            if (compare(bounds.get(i - 1), bounds.get(i)) >= 0) {
                throw refuse("The facet values of '" + expression + "' must be in ascending order.");
            }
        }
        return List.copyOf(bounds);
    }

    /** A boundary or interval start as a value of the field: a number, or a date as an instant. */
    private static Object ordinal(String text, ValueField field, String expression) {
        try {
            return switch (field.type()) {
                case "Edm.DateTimeOffset" -> OffsetDateTime.parse(text).toInstant().truncatedTo(ChronoUnit.MILLIS);
                case "Edm.Int32", "Edm.Int64", "Edm.Double" -> {
                    BigDecimal number = SearchOData.decimal(text);
                    if (number == null) {
                        throw new NumberFormatException(text);
                    }
                    yield number;
                }
                default -> throw refuse("The facet '" + expression + "' uses values or interval, which only fields"
                        + " of numbers and dates take.");
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw refuse("The facet value '" + text + "' of '" + expression + "' is not a value of type "
                    + field.type() + ".");
        }
    }

    private static String interval(String value, ValueField field, String expression) {
        if ("Edm.DateTimeOffset".equals(field.type())) {
            if (!DATE_INTERVALS.contains(value)) {
                throw refuse("The facet interval of '" + expression + "' must be one of minute, hour, day, week,"
                        + " month, quarter or year.");
            }
            return value;
        }
        Object interval = ordinal(value, field, expression);
        BigDecimal width = (BigDecimal) interval;
        if (width.signum() <= 0 || width.stripTrailingZeros().scale() > 0) {
            // Learn: "An integer interval greater than zero for numbers"; the words are floci-az's.
            throw refuse("The facet interval of '" + expression + "' must be an integer greater than zero.");
        }
        return width.stripTrailingZeros().toPlainString();
    }

    private static ZoneOffset offset(String value, ValueField field, String expression) {
        if (!"Edm.DateTimeOffset".equals(field.type()) || !OFFSET.matcher(value).matches()) {
            throw refuse("The facet timeoffset of '" + expression + "' must be [+-]hh:mm, [+-]hhmm or [+-]hh, on a"
                    + " field of type Edm.DateTimeOffset.");
        }
        try {
            return ZoneOffset.of(value.length() == 5 && value.indexOf(':') < 0
                    ? value.substring(0, 3) + ":" + value.substring(3) : value);
        } catch (DateTimeException e) {
            throw refuse("The facet timeoffset of '" + expression + "' is outside -18:00 to +18:00.");
        }
    }

    // ── Buckets ──────────────────────────────────────────────────────────────

    /**
     * Every interval from the one holding the field's lowest value to the one holding its highest, each as its start
     * and end, in order: none when no document has a finite value, and null when there would be more than
     * {@link #MAX_BUCKETS}.
     */
    private static List<Object[]> spanned(Facet facet, JsonNode bounds) {
        JsonNode lo = bounds.path("lo");
        JsonNode hi = bounds.path("hi");
        if (bounds.path("count").asLong() == 0 || lo.isMissingNode() || hi.isMissingNode()) {
            return List.of();
        }
        boolean date = "Edm.DateTimeOffset".equals(facet.field().type());
        Object last = date ? dateStart(Instant.parse(hi.asText()), facet) : numberStart(hi.decimalValue(), facet);
        Object start = date ? dateStart(Instant.parse(lo.asText()), facet) : numberStart(lo.decimalValue(), facet);
        if (!date) {
            BigDecimal count = ((BigDecimal) last).subtract((BigDecimal) start)
                    .divide(new BigDecimal(facet.interval()), 0, RoundingMode.FLOOR).add(BigDecimal.ONE);
            if (count.compareTo(BigDecimal.valueOf(MAX_BUCKETS)) > 0) {
                return null;
            }
        }
        List<Object[]> intervals = new ArrayList<>();
        while (compare(start, last) <= 0) {
            if (intervals.size() == MAX_BUCKETS) {
                return null;
            }
            Object next = next(start, facet);
            intervals.add(new Object[] {start, next});
            start = next;
        }
        return intervals;
    }

    /** The intervals that hold the distinct values Solr answered, each as its start and end, in order. */
    private static List<Object[]> intervals(Facet facet, JsonNode distinct) {
        TreeSet<Object> starts = new TreeSet<>(SearchFacets::compare);
        boolean date = "Edm.DateTimeOffset".equals(facet.field().type());
        for (JsonNode found : distinct.path("buckets")) {
            JsonNode value = found.path("val");
            if (!date && (!value.isNumber() || !Double.isFinite(value.doubleValue()))) {
                // INF and -INF fall in no interval: an interval of infinite width has no start.
                continue;
            }
            starts.add(date ? dateStart(Instant.parse(value.asText()), facet) : numberStart(value.decimalValue(), facet));
        }
        List<Object[]> intervals = new ArrayList<>();
        for (Object start : starts) {
            intervals.add(new Object[] {start, next(start, facet)});
        }
        return intervals;
    }

    private static BigDecimal numberStart(BigDecimal value, Facet facet) {
        BigDecimal size = new BigDecimal(facet.interval());
        return value.divide(size, 0, RoundingMode.FLOOR).multiply(size);
    }

    private static Instant dateStart(Instant value, Facet facet) {
        OffsetDateTime local = value.atOffset(facet.offset());
        OffsetDateTime start = switch (facet.interval()) {
            case "minute" -> local.truncatedTo(ChronoUnit.MINUTES);
            case "hour" -> local.truncatedTo(ChronoUnit.HOURS);
            case "day" -> local.truncatedTo(ChronoUnit.DAYS);
            case "week" -> local.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> local.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
            case "quarter" -> local.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1)
                    .withMonth((local.getMonthValue() - 1) / 3 * 3 + 1);
            default -> local.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1);
        };
        return start.toInstant();
    }

    private static Object next(Object start, Facet facet) {
        if (start instanceof BigDecimal number) {
            return number.add(new BigDecimal(facet.interval()));
        }
        OffsetDateTime local = ((Instant) start).atOffset(facet.offset());
        OffsetDateTime next = switch (facet.interval()) {
            case "minute" -> local.plusMinutes(1);
            case "hour" -> local.plusHours(1);
            case "day" -> local.plusDays(1);
            case "week" -> local.plusWeeks(1);
            case "month" -> local.plusMonths(1);
            case "quarter" -> local.plusMonths(3);
            default -> local.plusYears(1);
        };
        return next.toInstant();
    }

    /** A boundary as a quoted Lucene term. */
    private static String literal(Facet facet, Object value) {
        return SearchFilter.quote(value instanceof Instant instant ? DateTimeFormatter.ISO_INSTANT.format(instant)
                : ((BigDecimal) value).toPlainString());
    }

    /**
     * A range boundary as Azure writes it (net {@code RangeFacets} in the SDK recordings): a date with milliseconds,
     * {@code 2000-01-01T00:00:00.000Z}, and a number of the field's type, so a double field's {@code 5} is
     * {@code 5.0}.
     */
    private static Object bound(Facet facet, Object value) {
        if (value instanceof Instant instant) {
            return instant.atOffset(facet.offset()).format(RANGE_DATE);
        }
        BigDecimal number = (BigDecimal) value;
        return "Edm.Double".equals(facet.field().type()) ? (Object) number.doubleValue() : value(facet, value);
    }

    /** A boundary as the answer writes it: a number, or a date in the facet's offset. */
    private static Object value(Facet facet, Object value) {
        if (value instanceof Instant instant) {
            return ZoneOffset.UTC.equals(facet.offset()) ? DateTimeFormatter.ISO_INSTANT.format(instant)
                    : instant.atOffset(facet.offset()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        BigDecimal number = (BigDecimal) value;
        BigDecimal whole = number.stripTrailingZeros();
        // A whole number past a long (a double field's 1e20) stays a double rather than wrapping.
        boolean fitsALong = whole.scale() <= 0 && whole.compareTo(LONG_MIN) >= 0 && whole.compareTo(LONG_MAX) <= 0;
        return fitsALong ? (Object) number.longValue() : (Object) number.doubleValue();
    }

    /** A value of a field as Solr answered it, as Azure answers it. */
    private static Object solrValue(ValueField field, JsonNode value) {
        return switch (field.type()) {
            case "Edm.Boolean" -> value.asBoolean();
            case "Edm.Int32", "Edm.Int64" -> value.asLong();
            case "Edm.Double" -> value.asDouble();
            default -> value.asText();
        };
    }

    private static void order(List<Map<String, Object>> buckets, String sort) {
        Comparator<Map<String, Object>> byCount = Comparator.comparingLong(bucket -> (Long) bucket.get("count"));
        Comparator<Map<String, Object>> byValue = (a, b) -> compareValues(a.get("value"), b.get("value"));
        switch (sort) {
            case "-count" -> buckets.sort(byCount);
            case "value" -> buckets.sort(byValue);
            case "-value" -> buckets.sort(byValue.reversed());
            default -> buckets.sort(byCount.reversed());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        return ((Comparable) a).compareTo(b);
    }

    private static int compare(Object a, Object b) {
        if (a instanceof Instant x && b instanceof Instant y) {
            return x.compareTo(y);
        }
        return ((BigDecimal) a).compareTo((BigDecimal) b);
    }

    private static Map<String, Object> bucket(long count, Object value) {
        Map<String, Object> bucket = new LinkedHashMap<>();
        bucket.put("count", count);
        bucket.put("value", value);
        return bucket;
    }

    private static Map<String, Object> terms(String field, int limit) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("type", "terms");
        terms.put("field", field);
        terms.put("limit", limit);
        terms.put("sort", "count desc");
        return terms;
    }

    private static Map<String, Object> query(String query) {
        return Map.of("type", "query", "q", query);
    }

    private static Refused refuse(String detail) {
        return new Refused(SearchErrors.invalid(detail));
    }
}
