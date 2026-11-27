package io.floci.az.services.search;

import io.floci.az.services.search.SearchDocuments.Refused;
import io.floci.az.services.search.SearchOData.Params;
import io.floci.az.services.search.SearchSolrSchema.Layout;
import io.floci.az.services.search.SearchSolrSchema.ValueField;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The functions of a scoring profile, as a Solr function that multiplies the relevance score.
 *
 * <p>Learn gives no formula, so this one is floci-az's: each function maps its field's value to a closeness {@code x}
 * between 0 (outside its range, or no value) and 1 (the most boosted end), shapes it by the interpolation (linear
 * {@code x}, constant {@code 1} inside the range, quadratic {@code 1-(1-x)^2}, which falls slowly at first,
 * logarithmic {@code 1-log10(1+9(1-x))}, which falls fast at first), and contributes {@code (boost-1)} times that. The
 * contributions are combined by {@code functionAggregation} (sum, average, minimum, maximum, or the first function
 * whose field has a value), and the score is multiplied by one plus the result, never below zero. So a document no
 * function applies to keeps its score.
 *
 * <ul>
 *   <li>freshness: 1 now, falling to 0 at {@code boostingDuration} from now, in the past or the future;</li>
 *   <li>magnitude: 0 at {@code boostingRangeStart}, 1 at {@code boostingRangeEnd} (either may be the larger), and
 *       beyond the end 1 with {@code constantBoostBeyondRange}, else 0;</li>
 *   <li>distance: 1 at the reference point of the {@code referencePointParameter}, falling to 0 at
 *       {@code boostingDistance} kilometres;</li>
 *   <li>tag: 1 when the field holds one of the tags of the {@code tagsParameter}.</li>
 * </ul>
 */
final class SearchScoring {

    private SearchScoring() {
    }

    /** The boost function of the request's scoring profile, or empty when it has no functions. */
    static Optional<String> boost(Map<String, Object> profile, List<?> scoringParameters, Layout layout,
                                  Params params) {
        if (profile == null || !(profile.get("functions") instanceof List<?> functions) || functions.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> parameters = parameters(scoringParameters);
        List<String> contributions = new ArrayList<>();
        List<String> applies = new ArrayList<>();
        for (Object candidate : functions) {
            if (!(candidate instanceof Map<?, ?> function)) {
                continue;
            }
            Optional<ValueField> field = layout.valueField(String.valueOf(function.get("fieldName")))
                    .filter(ValueField::singleValued);
            String type = String.valueOf(function.get("type")).toLowerCase(Locale.ROOT);
            Optional<ValueField> target = "tag".equals(type)
                    ? layout.valueField(String.valueOf(function.get("fieldName"))) : field;
            if (target.isEmpty()) {
                // Learn: a function's field must be filterable; one Solr has no values of adds nothing.
                continue;
            }
            String closeness = closeness(type, function, target.get(), parameters, params);
            if (closeness == null) {
                continue;
            }
            double boost = function.get("boost") instanceof Number number ? number.doubleValue() : 1;
            String present = present(target.get(), params);
            String shaped = "if(" + present + "," + interpolate(String.valueOf(function.get("interpolation")),
                    closeness) + ",0)";
            contributions.add("product(" + (boost - 1) + "," + shaped + ")");
            applies.add(present);
        }
        if (contributions.isEmpty()) {
            return Optional.empty();
        }
        String aggregated = aggregate(String.valueOf(profile.get("functionAggregation")), contributions, applies);
        return Optional.of("max(0,sum(1," + aggregated + "))");
    }

    /** {@code name-value} pairs; a name has no dash, so the first one ends it. */
    private static Map<String, String> parameters(List<?> scoringParameters) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (scoringParameters != null) {
            for (Object parameter : scoringParameters) {
                String text = String.valueOf(parameter);
                int dash = text.indexOf('-');
                if (dash <= 0) {
                    throw refuse("The scoring parameter '" + text + "' is not of the form 'name-value'.");
                }
                parameters.put(text.substring(0, dash), text.substring(dash + 1));
            }
        }
        return parameters;
    }

    /** The function's closeness, between 0 and 1, as a Solr function, or null when it cannot apply. */
    private static String closeness(String type, Map<?, ?> function, ValueField field, Map<String, String> parameters,
                                    Params params) {
        String name = field.solrName();
        Map<?, ?> settings = function.get(type) instanceof Map<?, ?> map ? map : Map.of();
        return switch (type) {
            case "freshness" -> {
                long millis = duration(settings.get("boostingDuration"));
                yield clamp("sub(1,div(abs(ms(NOW," + name + "))," + millis + "))");
            }
            case "magnitude" -> {
                double start = number(settings.get("boostingRangeStart"));
                double end = number(settings.get("boostingRangeEnd"));
                if (start == end) {
                    yield null;
                }
                String raw = "div(sub(" + name + "," + start + ")," + (end - start) + ")";
                String beyond = Boolean.TRUE.equals(settings.get("constantBoostBeyondRange")) ? "1" : "0";
                yield "if(gt(" + raw + ",1)," + beyond + "," + clamp(raw) + ")";
            }
            case "distance" -> {
                String reference = required(parameters, settings.get("referencePointParameter"));
                String[] coordinates = reference.split(",");
                double kilometres = number(settings.get("boostingDistance"));
                if (coordinates.length != 2 || kilometres <= 0) {
                    throw refuse("The scoring parameter '" + settings.get("referencePointParameter") + "' must be a"
                            + " longitude and a latitude, separated by a comma.");
                }
                try {
                    double longitude = Double.parseDouble(coordinates[0].trim());
                    double latitude = Double.parseDouble(coordinates[1].trim());
                    yield clamp("sub(1,div(geodist(" + name + "," + latitude + "," + longitude + ")," + kilometres
                            + "))");
                } catch (NumberFormatException e) {
                    throw refuse("The scoring parameter '" + settings.get("referencePointParameter") + "' must be a"
                            + " longitude and a latitude, separated by a comma.");
                }
            }
            case "tag" -> {
                String tags = required(parameters, settings.get("tagsParameter"));
                List<String> frequencies = new ArrayList<>();
                for (String tag : tags.split(",")) {
                    if (!tag.isBlank()) {
                        frequencies.add("termfreq(" + name + "," + params.ref(field.normalizer().apply(tag.trim()))
                                + ")");
                    }
                }
                yield frequencies.isEmpty() ? null
                        : "if(gt(" + (frequencies.size() == 1 ? frequencies.getFirst()
                        : "sum(" + String.join(",", frequencies) + ")") + ",0),1,0)";
            }
            default -> null;
        };
    }

    private static String interpolate(String interpolation, String x) {
        return switch (interpolation.toLowerCase(Locale.ROOT)) {
            case "constant" -> "if(gt(" + x + ",0),1,0)";
            case "quadratic" -> "sub(1,pow(sub(1," + x + "),2))";
            case "logarithmic" -> "sub(1,log(sum(1,product(9,sub(1," + x + ")))))";
            default -> x;
        };
    }

    /**
     * Whether a document has a value of the field, as a Solr function: a point field or a collection has no value
     * source of its own, so it is asked as a query.
     */
    private static String present(ValueField field, Params params) {
        return "Edm.GeographyPoint".equals(field.type()) || field.multiValued()
                ? "exists(query(" + params.ref(field.solrName() + ":[* TO *]") + "))"
                : "exists(" + field.solrName() + ")";
    }

    /**
     * @param present for each contribution, whether its function applies, as a Solr function
     */
    private static String aggregate(String aggregation, List<String> contributions, List<String> present) {
        if (contributions.size() == 1) {
            return contributions.getFirst();
        }
        String all = String.join(",", contributions);
        return switch (aggregation.toLowerCase(Locale.ROOT)) {
            case "average" -> "div(sum(" + all + ")," + contributions.size() + ")";
            case "minimum" -> "min(" + all + ")";
            case "maximum" -> "max(" + all + ")";
            case "firstmatching" -> {
                String chosen = "0";
                for (int i = contributions.size() - 1; i >= 0; i--) {
                    chosen = "if(" + present.get(i) + "," + contributions.get(i) + "," + chosen + ")";
                }
                yield chosen;
            }
            default -> "sum(" + all + ")";
        };
    }

    /** Between 0 and 1; 0 when the field has no value. */
    private static String clamp(String raw) {
        return "max(0,min(1," + raw + "))";
    }

    private static String required(Map<String, String> parameters, Object name) {
        String value = parameters.get(String.valueOf(name));
        if (value == null || value.isBlank()) {
            throw refuse("Expected a value for the scoring parameter '" + name + "' of the scoring profile.");
        }
        return value;
    }

    private static long duration(Object text) {
        try {
            return Duration.parse(String.valueOf(text)).toMillis();
        } catch (DateTimeParseException e) {
            throw refuse("The boostingDuration '" + text + "' is not an XSD dayTimeDuration such as P1D.");
        }
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static Refused refuse(String detail) {
        return new Refused(SearchErrors.invalid(detail));
    }
}
