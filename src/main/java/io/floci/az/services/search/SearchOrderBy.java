package io.floci.az.services.search;

import io.floci.az.services.search.SearchOData.Cursor;
import io.floci.az.services.search.SearchOData.Kind;
import io.floci.az.services.search.SearchOData.Token;
import io.floci.az.services.search.SearchSolrSchema.Layout;
import io.floci.az.services.search.SearchSolrSchema.ValueField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An OData {@code $orderby} of Azure AI Search as a Solr {@code sort}: up to 32 clauses, each a sortable field,
 * {@code search.score()} or {@code geo.distance(field, point)}, ascending unless {@code desc} says otherwise. Ties are
 * broken by score, descending, as Learn says. The whole expression is parsed before any field is looked up, so a
 * syntax error is reported first, as Azure does. A null sorts first ascending and last descending, as the SDK
 * recordings show for a descending sort. Solr would sort a missing number as 0, so every field clause is preceded by
 * one on whether the field has a value, in the same direction. The distance of a document without a point is the
 * largest there is.
 */
final class SearchOrderBy {

    static final String PARAMETER = "$orderby";
    static final int MAX_CLAUSES = 32;

    private static final Pattern POINT = Pattern.compile("(?i)\\s*POINT\\s*\\(\\s*(\\S+)\\s+(\\S+)\\s*\\)\\s*");

    /** One parsed clause: a field path, {@code search.score()}, or {@code geo.distance} of a path and a point. */
    private record Clause(Target target, String path, String point, boolean descending) {

        enum Target { FIELD, SCORE, DISTANCE }
    }

    private SearchOrderBy() {
    }

    /** The Solr sort of an {@code $orderby}, or empty for none (by score, descending). */
    static Optional<String> sort(String orderBy, Map<String, Object> definition, Layout layout) {
        if (orderBy == null || orderBy.isBlank()) {
            return Optional.empty();
        }
        Cursor cursor = new Cursor(orderBy, PARAMETER);
        List<Clause> parsed = parse(cursor);
        List<String> clauses = new ArrayList<>();
        boolean byScore = false;
        for (Clause clause : parsed) {
            String direction = clause.descending() ? "desc" : "asc";
            switch (clause.target()) {
                case SCORE -> {
                    clauses.add("score " + direction);
                    byScore = true;
                }
                case DISTANCE -> clauses.add(distance(clause, definition, layout, cursor) + " " + direction);
                case FIELD -> {
                    ValueField field = sortable(clause.path(), definition, layout, cursor);
                    if ("Edm.GeographyPoint".equals(field.type())) {
                        // Solr cannot sort on a point itself; the words are floci-az's.
                        throw cursor.invalid("'" + clause.path() + "' is of type Edm.GeographyPoint, which can only"
                                + " be sorted by geo.distance(" + clause.path() + ", geography'POINT(lon lat)').");
                    }
                    String key = field.solrName();
                    clauses.add("if(exists(" + key + "),1,0) " + direction);
                    clauses.add(key + " " + direction);
                }
            }
        }
        if (!byScore) {
            clauses.add("score desc");
        }
        return Optional.of(String.join(",", clauses));
    }

    /** The clauses of the whole expression, checked for syntax only. */
    private static List<Clause> parse(Cursor cursor) {
        List<Clause> clauses = new ArrayList<>();
        do {
            if (clauses.size() == MAX_CLAUSES) {
                throw cursor.invalid("The $orderby expression has more than " + MAX_CLAUSES + " clauses.");
            }
            Token first = cursor.expect(Kind.IDENTIFIER);
            Clause.Target target;
            String path = null;
            String point = null;
            if (first.is("search.score")) {
                cursor.expect(Kind.OPEN);
                cursor.expect(Kind.CLOSE);
                target = Clause.Target.SCORE;
            } else if (first.is("geo.distance")) {
                target = Clause.Target.DISTANCE;
                cursor.expect(Kind.OPEN);
                for (int i = 0; i < 2; i++) {
                    if (i == 1) {
                        cursor.expect(Kind.COMMA);
                    }
                    if (cursor.peek().kind() == Kind.GEOGRAPHY) {
                        point = cursor.next().text();
                    } else {
                        path = path(cursor, cursor.expect(Kind.IDENTIFIER));
                    }
                }
                cursor.expect(Kind.CLOSE);
            } else {
                target = Clause.Target.FIELD;
                path = path(cursor, first);
            }
            boolean descending = cursor.accept("desc");
            if (!descending) {
                cursor.accept("asc");
            }
            clauses.add(new Clause(target, path, point, descending));
        } while (cursor.accept(Kind.COMMA));
        if (cursor.peek().kind() != Kind.END) {
            throw cursor.error();
        }
        return clauses;
    }

    private static String path(Cursor cursor, Token first) {
        StringBuilder path = new StringBuilder(first.text());
        while (cursor.accept(Kind.SLASH)) {
            path.append('/').append(cursor.expect(Kind.IDENTIFIER).text());
        }
        return path.toString();
    }

    private static String distance(Clause clause, Map<String, Object> definition, Layout layout, Cursor cursor) {
        String point = clause.point();
        Matcher coordinates = point == null ? null : POINT.matcher(point);
        if (clause.path() == null || coordinates == null || !coordinates.matches()) {
            throw cursor.invalid("geo.distance needs a field and a geography'POINT(lon lat)' constant.");
        }
        ValueField field = sortable(clause.path(), definition, layout, cursor);
        if (!"Edm.GeographyPoint".equals(field.type())) {
            throw cursor.invalid("geo.distance can only be used with fields of type Edm.GeographyPoint.");
        }
        try {
            double longitude = Double.parseDouble(coordinates.group(1));
            double latitude = Double.parseDouble(coordinates.group(2));
            return "geodist(" + field.solrName() + "," + latitude + "," + longitude + ")";
        } catch (NumberFormatException e) {
            throw cursor.invalid("The geography literal '" + point + "' is not a valid point.");
        }
    }

    private static ValueField sortable(String path, Map<String, Object> definition, Layout layout, Cursor cursor) {
        if (SearchSolrSchema.definitionField(definition, path) == null) {
            throw cursor.invalid("Could not find a property named '" + path + "' on type 'search.document'.");
        }
        return layout.valueField(path).filter(ValueField::sortable).filter(ValueField::singleValued)
                .orElseThrow(() -> cursor.invalid("'" + path + "' is not a sortable field. Only sortable fields can"
                        + " be used in $orderby expressions."));
    }
}
