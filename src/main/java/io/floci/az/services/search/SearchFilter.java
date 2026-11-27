package io.floci.az.services.search;

import io.floci.az.services.search.SearchDocuments.Refused;
import io.floci.az.services.search.SearchOData.Cursor;
import io.floci.az.services.search.SearchOData.Kind;
import io.floci.az.services.search.SearchOData.Params;
import io.floci.az.services.search.SearchOData.Token;
import io.floci.az.services.search.SearchSolrSchema.Layout;
import io.floci.az.services.search.SearchSolrSchema.Scope;
import io.floci.az.services.search.SearchSolrSchema.ValueField;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An OData {@code $filter} of Azure AI Search, parsed and translated to Solr's JSON query DSL over the value fields
 * of {@link SearchSolrSchema}.
 *
 * <p>The grammar and its meaning follow Learn's OData pages: {@code not} binds tighter than the comparisons, which
 * bind tighter than {@code and}, then {@code or}; a constant may stand on either side of a comparison; a boolean field
 * is a predicate by itself; {@code null} is equal only to itself and neither less nor greater than anything; strings
 * compare exactly and case-sensitively, after the field's normalizer. A lambda over a complex collection is a Solr
 * block join on the elements' child documents, so its conditions hold for one element; one over a collection of
 * strings, numbers, dates, booleans or points is a test on the field's values, in the forms Learn allows and with its
 * error messages for the others. {@code geo.intersects} needs a polygon library the Solr image lacks, so it is
 * refused. {@code search.ismatchscoring} adds to the score, so a filter that uses it runs as part of the query.
 */
final class SearchFilter {

    static final String PARAMETER = "$filter";

    /** How {@code search.ismatch}'s arguments become a Solr query: {@link SearchQueries} knows the text fields. */
    interface TextQueries {
        Object query(String search, String searchFields, String queryType, String searchMode, Params params);
    }

    /** A filter as a Solr JSON query, and whether it adds to the score. */
    record Translated(Object query, boolean scoring) {
    }

    private static final Object ALL = "*:*";
    private static final Object NONE = Map.of("bool", Map.of("must_not", List.of("*:*")));
    private static final BigDecimal INFINITY = new BigDecimal("1e400");
    private static final Pattern POINT = Pattern.compile("(?i)\\s*POINT\\s*\\(\\s*(\\S+)\\s+(\\S+)\\s*\\)\\s*");
    private static final List<String> COMPARISONS = List.of("eq", "ne", "gt", "lt", "ge", "le");

    // Learn's messages for lambda expressions it does not allow (search-query-troubleshoot-collection-filters).
    private static final String STRING_EQUALITY = "Invalid lambda expression. Found a test for equality or inequality"
            + " where the opposite was expected in a lambda expression that iterates over a field of type"
            + " Collection(Edm.String). For 'any', use expressions of the form 'x eq y' or 'search.in(...)'. For"
            + " 'all', use expressions of the form 'x ne y', 'not (x eq y)', or 'not search.in(...)'.";
    private static final String NORMAL_FORM = "Invalid lambda expression. Found an unsupported form of complex"
            + " Boolean expression. For 'any', use expressions that are 'ORs of ANDs', also known as Disjunctive"
            + " Normal Form. For example: (a and b) or (c and d) where a, b, c, and d are comparison or equality"
            + " subexpressions. For 'all', use expressions that are 'ANDs of ORs', also known as Conjunctive Normal"
            + " Form. For example: (a or b) and (c or d) where a, b, c, and d are comparison or inequality"
            + " subexpressions. Examples of comparison expressions: 'x gt 5', 'x le 2'. Example of an equality"
            + " expression: 'x eq 5'. Example of an inequality expression: 'x ne 5'.";
    private static final String GEO_FORM = "Invalid lambda expression. Found an unsupported use of geo.distance() or"
            + " geo.intersects() in a lambda expression that iterates over a field of type"
            + " Collection(Edm.GeographyPoint). For 'any', make sure you compare geo.distance() using the 'lt' or 'le'"
            + " operators and make sure that any usage of geo.intersects() isn't negated. For 'all', make sure you"
            + " compare geo.distance() using the 'gt' or 'ge' operators and make sure that any usage of"
            + " geo.intersects() is negated.";
    private static final String GEO_COMBINATION = "Invalid lambda expression. Complex Boolean expressions aren't"
            + " supported in lambda expressions that iterate over fields of type Collection(Edm.GeographyPoint). For"
            + " 'any', join subexpressions with 'or'; 'and' isn't supported. For 'all', join subexpressions with"
            + " 'and'; 'or' isn't supported.";
    private static final String STRING_RANGE = "Invalid lambda expression. Found a comparison operator (one of 'lt',"
            + " 'le', 'gt', or 'ge'). Only equality operators are allowed in lambda expressions that iterate over"
            + " fields of type Collection(Edm.String). For 'any', use expressions of the form 'x eq y'. For 'all', use"
            + " expressions of the form 'x ne y' or 'not (x eq y)'.";

    // ── The syntax tree ──────────────────────────────────────────────────────

    sealed interface Node permits And, Or, Not, Compare, Path, Literal, SearchIn, IsMatch, Distance, Intersects,
            Lambda {
        int position();
    }

    record And(Node left, Node right, int position) implements Node {
    }

    record Or(Node left, Node right, int position) implements Node {
    }

    record Not(Node operand, int position) implements Node {
    }

    record Compare(String operator, Node left, Node right, int position) implements Node {
    }

    record Path(List<String> segments, int position) implements Node {
        String text() {
            return String.join("/", segments);
        }
    }

    enum LiteralKind { STRING, INTEGER, DOUBLE, BOOLEAN, NULL, DATE, POINT, POLYGON }

    /**
     * A constant: a {@code String}, a {@code BigDecimal} (an infinity beyond any double, {@code NaN} as null), a
     * {@code Boolean}, an {@code Instant}, or the longitude and latitude of a point.
     */
    record Literal(LiteralKind kind, Object value, String text, int position) implements Node {
        String edmType() {
            return switch (kind) {
                case STRING -> "Edm.String";
                case INTEGER -> ((BigDecimal) value).abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0
                        ? "Edm.Int64" : "Edm.Int32";
                case DOUBLE -> "Edm.Double";
                case BOOLEAN -> "Edm.Boolean";
                case NULL -> "null";
                case DATE -> "Edm.DateTimeOffset";
                case POINT, POLYGON -> "Edm.GeographyPoint";
            };
        }

        boolean isNaN() {
            return kind == LiteralKind.DOUBLE && value == null;
        }
    }

    record SearchIn(Node variable, String values, String delimiters, int position) implements Node {
    }

    record IsMatch(boolean scoring, List<String> arguments, int position) implements Node {
    }

    record Distance(Node variable, double longitude, double latitude, int position) implements Node {
    }

    record Intersects(Node variable, int position) implements Node {
    }

    /** {@code path/any(v: body)}, {@code path/all(v: body)}, or {@code path/any()} with a null body. */
    record Lambda(Path collection, boolean all, String variable, Node body, int position) implements Node {
    }

    private SearchFilter() {
    }

    /** Parses and translates a filter. */
    static Translated translate(String filter, Map<String, Object> definition, Layout layout, TextQueries text,
                                Params params) {
        Node tree = parse(filter);
        Translator translator = new Translator(definition, layout, text, params);
        Q translated = translator.translate(tree, Frame.ROOT);
        return new Translated(translated.dsl(), translated.scoring());
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    static Node parse(String filter) {
        Parser parser = new Parser(new Cursor(filter, PARAMETER));
        Node tree = parser.or();
        if (parser.cursor.peek().kind() != Kind.END) {
            throw parser.cursor.error();
        }
        return tree;
    }

    private static final class Parser {
        private final Cursor cursor;

        Parser(Cursor cursor) {
            this.cursor = cursor;
        }

        Node or() {
            Node left = and();
            while (cursor.peek().is("or")) {
                int position = cursor.next().position();
                left = new Or(left, and(), position);
            }
            return left;
        }

        Node and() {
            Node left = comparison();
            while (cursor.peek().is("and")) {
                int position = cursor.next().position();
                left = new And(left, comparison(), position);
            }
            return left;
        }

        Node comparison() {
            Node left = unary();
            Token operator = cursor.peek();
            if (operator.kind() == Kind.IDENTIFIER && COMPARISONS.contains(operator.text())) {
                cursor.next();
                return new Compare(operator.text(), left, unary(), operator.position());
            }
            return left;
        }

        Node unary() {
            if (cursor.peek().is("not")) {
                int position = cursor.next().position();
                return new Not(unary(), position);
            }
            return primary();
        }

        Node primary() {
            Token token = cursor.peek();
            switch (token.kind()) {
                case OPEN -> {
                    cursor.next();
                    Node inner = or();
                    cursor.expect(Kind.CLOSE);
                    return inner;
                }
                case STRING -> {
                    cursor.next();
                    return new Literal(LiteralKind.STRING, token.text(), token.text(), token.position());
                }
                case NUMBER -> {
                    cursor.next();
                    return number(token);
                }
                case DATE -> {
                    cursor.next();
                    return date(token);
                }
                case GEOGRAPHY -> {
                    cursor.next();
                    return geography(token);
                }
                case IDENTIFIER -> {
                    return identifier();
                }
                default -> throw cursor.error();
            }
        }

        private Node identifier() {
            Token token = cursor.next();
            switch (token.text()) {
                case "true", "false" -> {
                    return new Literal(LiteralKind.BOOLEAN, Boolean.valueOf(token.text()), token.text(),
                            token.position());
                }
                case "null" -> {
                    return new Literal(LiteralKind.NULL, null, token.text(), token.position());
                }
                case "NaN", "INF" -> {
                    return number(token);
                }
                default -> {
                    // A dotted name is a function; field names have no dots.
                }
            }
            if (token.text().contains(".")) {
                return function(token);
            }
            List<String> segments = new ArrayList<>();
            segments.add(token.text());
            while (cursor.peek().kind() == Kind.SLASH) {
                cursor.next();
                Token segment = cursor.expect(Kind.IDENTIFIER);
                if ((segment.is("any") || segment.is("all")) && cursor.peek().kind() == Kind.OPEN) {
                    return lambda(new Path(List.copyOf(segments), token.position()), segment);
                }
                segments.add(segment.text());
            }
            return new Path(List.copyOf(segments), token.position());
        }

        private Node lambda(Path collection, Token kind) {
            cursor.expect(Kind.OPEN);
            boolean all = kind.is("all");
            if (cursor.accept(Kind.CLOSE)) {
                if (all) {
                    throw cursor.invalid("The 'all' operator needs a lambda expression.");
                }
                return new Lambda(collection, false, null, null, kind.position());
            }
            String variable = cursor.expect(Kind.IDENTIFIER).text();
            cursor.expect(Kind.COLON);
            Node body = or();
            cursor.expect(Kind.CLOSE);
            return new Lambda(collection, all, variable, body, kind.position());
        }

        private Node function(Token name) {
            cursor.expect(Kind.OPEN);
            switch (name.text()) {
                case "search.in" -> {
                    Node variable = primary();
                    cursor.expect(Kind.COMMA);
                    String values = cursor.expect(Kind.STRING).text();
                    String delimiters = " ,";
                    if (cursor.accept(Kind.COMMA)) {
                        delimiters = cursor.expect(Kind.STRING).text();
                    }
                    cursor.expect(Kind.CLOSE);
                    return new SearchIn(variable, values, delimiters, name.position());
                }
                case "search.ismatch", "search.ismatchscoring" -> {
                    List<String> arguments = new ArrayList<>();
                    arguments.add(cursor.expect(Kind.STRING).text());
                    while (cursor.accept(Kind.COMMA)) {
                        arguments.add(cursor.expect(Kind.STRING).text());
                    }
                    cursor.expect(Kind.CLOSE);
                    if (arguments.size() == 3 || arguments.size() > 4) {
                        throw cursor.invalid("No function signature for the function with name '" + name.text()
                                + "' matches the specified arguments.");
                    }
                    return new IsMatch(name.text().endsWith("scoring"), List.copyOf(arguments), name.position());
                }
                case "geo.distance" -> {
                    Node first = primary();
                    cursor.expect(Kind.COMMA);
                    Node second = primary();
                    cursor.expect(Kind.CLOSE);
                    Literal point = first instanceof Literal literal ? literal
                            : second instanceof Literal literal ? literal : null;
                    Node variable = point == first ? second : first;
                    if (point == null || point.kind() != LiteralKind.POINT) {
                        throw cursor.invalid("geo.distance needs a field and a geography'POINT(lon lat)' constant.");
                    }
                    double[] coordinates = (double[]) point.value();
                    return new Distance(variable, coordinates[0], coordinates[1], name.position());
                }
                case "geo.intersects" -> {
                    Node variable = primary();
                    cursor.expect(Kind.COMMA);
                    Token polygon = cursor.expect(Kind.GEOGRAPHY);
                    cursor.expect(Kind.CLOSE);
                    if (!polygon.text().trim().toUpperCase(Locale.ROOT).startsWith("POLYGON")) {
                        throw cursor.invalid("geo.intersects needs a geography'POLYGON((...))' constant.");
                    }
                    return new Intersects(variable, name.position());
                }
                default -> throw cursor.invalid("Unknown function '" + name.text() + "'.");
            }
        }

        private Literal number(Token token) {
            String text = token.text();
            return switch (text) {
                case "NaN" -> new Literal(LiteralKind.DOUBLE, null, text, token.position());
                case "INF" -> new Literal(LiteralKind.DOUBLE, INFINITY, text, token.position());
                case "-INF" -> new Literal(LiteralKind.DOUBLE, INFINITY.negate(), text, token.position());
                default -> new Literal(text.matches("-?\\d+") ? LiteralKind.INTEGER : LiteralKind.DOUBLE,
                        new BigDecimal(text), text, token.position());
            };
        }

        private Literal date(Token token) {
            try {
                Instant instant = OffsetDateTime.parse(token.text()).toInstant().truncatedTo(ChronoUnit.MILLIS);
                return new Literal(LiteralKind.DATE, instant, token.text(), token.position());
            } catch (DateTimeParseException e) {
                throw cursor.error(token);
            }
        }

        private Literal geography(Token token) {
            Matcher point = POINT.matcher(token.text());
            if (point.matches()) {
                try {
                    double[] coordinates = {Double.parseDouble(point.group(1)), Double.parseDouble(point.group(2))};
                    return new Literal(LiteralKind.POINT, coordinates, token.text(), token.position());
                } catch (NumberFormatException e) {
                    throw cursor.invalid("The geography literal '" + token.text() + "' is not a valid point.");
                }
            }
            return new Literal(LiteralKind.POLYGON, token.text(), token.text(), token.position());
        }
    }

    // ── Translation ──────────────────────────────────────────────────────────

    /** A Solr query and whether it adds to the score. */
    private record Q(Object dsl, boolean scoring) {
        static Q filter(Object dsl) {
            return new Q(dsl, false);
        }
    }

    /**
     * Where an expression is evaluated: the documents themselves, or the elements of a complex collection, bound to
     * a range variable.
     */
    private record Frame(String scope, int depth, String variable) {
        static final Frame ROOT = new Frame("", 0, null);
    }

    /** A field of the index definition at a path. */
    private record Field(String path, String type, Map<String, Object> definition) {
        boolean collection() {
            return type.startsWith("Collection(");
        }

        String element() {
            return collection() ? type.substring("Collection(".length(), type.length() - 1) : type;
        }
    }

    private static final class Translator {
        private final Map<String, Object> definition;
        private final Layout layout;
        private final TextQueries text;
        private final Params params;

        Translator(Map<String, Object> definition, Layout layout, TextQueries text, Params params) {
            this.definition = definition;
            this.layout = layout;
            this.text = text;
            this.params = params;
        }

        Q translate(Node node, Frame frame) {
            return switch (node) {
                case And and -> and(translate(and.left(), frame), translate(and.right(), frame));
                case Or or -> or(translate(or.left(), frame), translate(or.right(), frame));
                case Not not -> {
                    requireBoolean(not.operand(), frame);
                    yield Q.filter(not(translate(not.operand(), frame).dsl()));
                }
                case Compare compare -> Q.filter(compare(compare, frame));
                case Path path -> {
                    ValueField field = valueField(path, frame);
                    if (!"Edm.Boolean".equals(field.type())) {
                        throw notBoolean();
                    }
                    yield Q.filter(term(field.solrName(), "true"));
                }
                case Literal literal -> {
                    if (literal.kind() != LiteralKind.BOOLEAN) {
                        throw notBoolean();
                    }
                    yield Q.filter(Boolean.TRUE.equals(literal.value()) ? ALL : NONE);
                }
                case SearchIn in -> {
                    if (!(in.variable() instanceof Path path)) {
                        throw invalid("The first argument of search.in must be a field.");
                    }
                    ValueField field = valueField(path, frame);
                    if (!"Edm.String".equals(field.type())) {
                        throw invalid("search.in can only be used with fields of type Edm.String.");
                    }
                    yield Q.filter(terms(field.solrName(), split(in.values(), in.delimiters(), field)));
                }
                case IsMatch match -> {
                    if (frame.variable() != null) {
                        throw invalid("The function `" + (match.scoring() ? "ismatchscoring" : "ismatch") + "` has"
                                + " no parameters bound to the range variable '" + frame.variable() + "'. Only"
                                + " bound field references are supported inside lambda expressions ('any' or"
                                + " 'all'). However, you can change your filter so that the `ismatch` function is"
                                + " outside the lambda expression and try again.");
                    }
                    List<String> arguments = match.arguments();
                    Object query = text.query(arguments.getFirst(), arguments.size() > 1 ? arguments.get(1) : null,
                            arguments.size() > 2 ? arguments.get(2) : null,
                            arguments.size() > 3 ? arguments.get(3) : null, params);
                    yield new Q(query, match.scoring());
                }
                case Intersects intersects -> {
                    if (intersects.variable() instanceof Path path) {
                        valueField(path, frame);
                    }
                    throw intersectsUnserved();
                }
                case Distance distance -> throw invalid("geo.distance must be compared with a distance.");
                case Lambda lambda -> Q.filter(lambda(lambda, frame));
            };
        }

        private void requireBoolean(Node operand, Frame frame) {
            String type = switch (operand) {
                case Path path -> field(resolve(path, frame)).type();
                case Literal literal -> literal.edmType();
                default -> "Edm.Boolean";
            };
            if (!"Edm.Boolean".equals(type)) {
                throw invalid("A unary operator with an incompatible type was detected. Found operand type '" + type
                        + "' for operator kind 'Not'.");
            }
        }

        // ── Comparisons ──────────────────────────────────────────────────

        private Object compare(Compare compare, Frame frame) {
            Node left = compare.left();
            Node right = compare.right();
            String operator = compare.operator();
            if (left instanceof Literal && !(right instanceof Literal)) {
                Node swap = left;
                left = right;
                right = swap;
                operator = flip(operator);
            }
            if (left instanceof Not not) {
                // not binds tighter than a comparison: "not Rating gt 5" negates Rating.
                requireBoolean(not.operand(), frame);
            }
            if (!(right instanceof Literal literal) || left instanceof Literal) {
                throw invalid("A comparison needs a field or range variable on one side and a constant on the"
                        + " other.");
            }
            if (left instanceof Distance distance) {
                if (!(distance.variable() instanceof Path path)) {
                    throw invalid("The first argument of geo.distance must be a field.");
                }
                ValueField field = valueField(path, frame);
                requireGeo(field);
                return distance(field, distance, operator, literal);
            }
            if (!(left instanceof Path path)) {
                throw invalid("A comparison needs a field or range variable on one side and a constant on the"
                        + " other.");
            }
            return compareValue(valueField(path, frame), operator, literal);
        }

        private Object distance(ValueField field, Distance distance, String operator, Literal limit) {
            if (limit.kind() != LiteralKind.INTEGER && limit.kind() != LiteralKind.DOUBLE || limit.isNaN()) {
                throw incompatible("Edm.Double", limit.edmType(), operator);
            }
            boolean close = "lt".equals(operator) || "le".equals(operator);
            if (!close && !"gt".equals(operator) && !"ge".equals(operator)) {
                throw invalid("The operators 'eq' and 'ne' are not supported when comparing distances.");
            }
            double kilometres = ((BigDecimal) limit.value()).doubleValue();
            Optional<Boolean> settled = settled(operator, kilometres);
            if (settled.isPresent()) {
                return settled.get() ? exists(field.solrName()) : NONE;
            }
            Object within = geofilt(field, distance, kilometres);
            return close ? within : and(Q.filter(exists(field.solrName())), Q.filter(not(within))).dsl();
        }

        /**
         * Whether a distance comparison holds for every distance, a distance being zero or more: {@code lt 0} holds
         * for none and {@code ge 0} for all, so neither depends on where the point is. Empty when it depends.
         */
        private static Optional<Boolean> settled(String operator, double kilometres) {
            return switch (operator) {
                case "lt" -> kilometres <= 0 ? Optional.of(false) : Optional.empty();
                case "le" -> kilometres < 0 ? Optional.of(false) : Optional.empty();
                case "gt" -> kilometres < 0 ? Optional.of(true) : Optional.empty();
                case "ge" -> kilometres <= 0 ? Optional.of(true) : Optional.empty();
                default -> Optional.empty();
            };
        }

        private Object compareValue(ValueField field, String operator, Literal literal) {
            if (literal.kind() == LiteralKind.NULL) {
                return switch (operator) {
                    case "eq" -> not(exists(field.solrName()));
                    case "ne" -> exists(field.solrName());
                    default -> throw invalid("The '" + operator + "' operator cannot compare with null; only 'eq'"
                            + " and 'ne' can.");
                };
            }
            String type = field.type();
            return switch (type) {
                case "Edm.String" -> {
                    if (literal.kind() != LiteralKind.STRING) {
                        throw incompatible(type, literal.edmType(), operator);
                    }
                    String value = field.normalizer().apply((String) literal.value());
                    yield switch (operator) {
                        case "eq" -> term(field.solrName(), value);
                        case "ne" -> not(term(field.solrName(), value));
                        default -> stringRange(field.solrName(), operator, value);
                    };
                }
                case "Edm.Boolean" -> {
                    if (literal.kind() != LiteralKind.BOOLEAN) {
                        throw incompatible(type, literal.edmType(), operator);
                    }
                    if (!"eq".equals(operator) && !"ne".equals(operator)) {
                        throw invalid("The type Edm.Boolean supports only the eq and ne operators.");
                    }
                    Object test = term(field.solrName(), String.valueOf(literal.value()));
                    yield "eq".equals(operator) ? test : not(test);
                }
                case "Edm.GeographyPoint" -> throw invalid("A field of type Edm.GeographyPoint cannot be compared"
                        + " with a constant; use geo.distance or geo.intersects.");
                default -> {
                    BigDecimal value = ordinal(field, literal, operator);
                    if (literal.isNaN()) {
                        // NaN equals nothing, itself included, and is neither less nor greater than anything.
                        yield "ne".equals(operator) ? ALL : NONE;
                    }
                    if ("ne".equals(operator)) {
                        yield not(ranges(field, Intervals.atom("eq", value)));
                    }
                    yield ranges(field, Intervals.atom(operator, value));
                }
            };
        }

        /** A number or date as a point on the line intervals are drawn on: a date as its epoch millisecond. */
        private BigDecimal ordinal(ValueField field, Literal literal, String operator) {
            String type = field.type();
            if ("Edm.DateTimeOffset".equals(type)) {
                if (literal.kind() != LiteralKind.DATE) {
                    throw incompatible(type, literal.edmType(), operator);
                }
                return BigDecimal.valueOf(((Instant) literal.value()).toEpochMilli());
            }
            if (literal.kind() != LiteralKind.INTEGER && literal.kind() != LiteralKind.DOUBLE) {
                throw incompatible(type, literal.edmType(), operator);
            }
            boolean special = literal.isNaN() || INFINITY.equals(((BigDecimal) literal.value()).abs());
            if (special && !"Edm.Double".equals(type)) {
                throw invalid("Comparisons to NaN, -INF, or INF are not allowed for fields of type " + type + ".");
            }
            return (BigDecimal) literal.value();
        }

        private Object stringRange(String solrName, String operator, String value) {
            String quoted = quote(value);
            return switch (operator) {
                case "gt" -> solrName + ":{" + quoted + " TO *]";
                case "ge" -> solrName + ":[" + quoted + " TO *]";
                case "lt" -> solrName + ":[* TO " + quoted + "}";
                default -> solrName + ":[* TO " + quoted + "]";
            };
        }

        // ── Lambdas ──────────────────────────────────────────────────────

        private Object lambda(Lambda lambda, Frame frame) {
            String path = resolve(lambda.collection(), frame);
            Field field = field(path);
            if (!field.collection()) {
                throw invalid("The 'any' and 'all' operators can only be used on collections; '" + path
                        + "' is not one.");
            }
            if ("Edm.ComplexType".equals(field.element())) {
                Scope scope = layout.scope(path).orElseThrow();
                Frame inner = new Frame(path, scope.depth(), lambda.variable());
                Object inScope = term(SearchSolrSchema.SCOPE, path);
                if (lambda.body() == null) {
                    return parent(scope, inScope);
                }
                Object body = translate(lambda.body(), inner).dsl();
                if (lambda.all()) {
                    return not(parent(scope, and(Q.filter(inScope), Q.filter(not(body))).dsl()));
                }
                return parent(scope, and(Q.filter(inScope), Q.filter(body)).dsl());
            }
            ValueField values = valueField(lambda.collection(), frame, true);
            if (lambda.body() == null) {
                return exists(values.solrName());
            }
            String variable = lambda.variable();
            return switch (values.type()) {
                case "Edm.String" -> stringLambda(values, lambda, variable);
                case "Edm.Boolean" -> booleanLambda(values, lambda, variable);
                case "Edm.GeographyPoint" -> geoLambda(values, lambda, variable);
                default -> rangeLambda(values, lambda, variable);
            };
        }

        private Object parent(Scope scope, Object child) {
            Map<String, Object> join = new LinkedHashMap<>();
            join.put("which", SearchSolrSchema.DEPTH + ":[0 TO " + (scope.depth() - 1) + "]");
            join.put("query", child);
            return Map.of("parent", join);
        }

        private Object stringLambda(ValueField field, Lambda lambda, String variable) {
            Set<String> values = new LinkedHashSet<>();
            if (lambda.all()) {
                allStrings(lambda.body(), variable, field, values);
                return not(terms(field.solrName(), List.copyOf(values)));
            }
            anyStrings(lambda.body(), variable, field, values);
            return terms(field.solrName(), List.copyOf(values));
        }

        private void anyStrings(Node node, String variable, ValueField field, Set<String> values) {
            switch (node) {
                case Or or -> {
                    anyStrings(or.left(), variable, field, values);
                    anyStrings(or.right(), variable, field, values);
                }
                case SearchIn in when bound(in.variable(), variable) ->
                        values.addAll(split(in.values(), in.delimiters(), field));
                case Compare compare -> values.add(stringOperand(compare, variable, field, "eq"));
                case And and -> throw invalid(NORMAL_FORM);
                default -> throw invalid(STRING_EQUALITY);
            }
        }

        private void allStrings(Node node, String variable, ValueField field, Set<String> values) {
            switch (node) {
                case And and -> {
                    allStrings(and.left(), variable, field, values);
                    allStrings(and.right(), variable, field, values);
                }
                case Not not when not.operand() instanceof SearchIn in && bound(in.variable(), variable) ->
                        values.addAll(split(in.values(), in.delimiters(), field));
                case Not not when not.operand() instanceof Compare compare ->
                        values.add(stringOperand(compare, variable, field, "eq"));
                case Compare compare -> values.add(stringOperand(compare, variable, field, "ne"));
                case Or or -> throw invalid(NORMAL_FORM);
                default -> throw invalid(STRING_EQUALITY);
            }
        }

        /** The string a comparison of the range variable tests, when its operator is the one expected. */
        private String stringOperand(Compare compare, String variable, ValueField field, String expected) {
            Comparison comparison = comparison(compare, variable);
            if (List.of("gt", "ge", "lt", "le").contains(comparison.operator())) {
                throw invalid(STRING_RANGE);
            }
            if (!expected.equals(comparison.operator())) {
                throw invalid(STRING_EQUALITY);
            }
            if (comparison.literal().kind() != LiteralKind.STRING) {
                throw incompatible("Edm.String", comparison.literal().edmType(), comparison.operator());
            }
            return field.normalizer().apply((String) comparison.literal().value());
        }

        private Object booleanLambda(ValueField field, Lambda lambda, String variable) {
            if (lambda.body() instanceof And || lambda.body() instanceof Or) {
                throw invalid(NORMAL_FORM);
            }
            List<Boolean> matching = new ArrayList<>();
            for (Boolean value : List.of(Boolean.TRUE, Boolean.FALSE)) {
                if (evaluateBoolean(lambda.body(), variable, value) != lambda.all()) {
                    matching.add(value);
                }
            }
            // any: a value that passes; all: no value that fails.
            Object found = switch (matching.size()) {
                case 0 -> NONE;
                case 1 -> term(field.solrName(), String.valueOf(matching.getFirst()));
                default -> exists(field.solrName());
            };
            return lambda.all() ? not(found) : found;
        }

        private boolean evaluateBoolean(Node node, String variable, boolean value) {
            return switch (node) {
                case Not not -> !evaluateBoolean(not.operand(), variable, value);
                case Path path when bound(path, variable) -> value;
                case Compare compare -> {
                    Comparison comparison = comparison(compare, variable);
                    if (comparison.literal().kind() != LiteralKind.BOOLEAN) {
                        throw incompatible("Edm.Boolean", comparison.literal().edmType(), comparison.operator());
                    }
                    boolean equal = comparison.literal().value().equals(value);
                    yield switch (comparison.operator()) {
                        case "eq" -> equal;
                        case "ne" -> !equal;
                        default -> throw invalid("The type Edm.Boolean supports only the eq and ne operators.");
                    };
                }
                default -> throw invalid(NORMAL_FORM);
            };
        }

        private Object geoLambda(ValueField field, Lambda lambda, String variable) {
            List<Object> within = new ArrayList<>();
            geoClauses(lambda.body(), variable, field, lambda.all(), within);
            Object any = within.isEmpty() ? NONE
                    : within.size() == 1 ? within.getFirst() : Map.of("bool", Map.of("should", within));
            return lambda.all() ? not(any) : any;
        }

        /** The {@code geofilt} of each clause, for {@code any} its matches, for {@code all} its failures. */
        private void geoClauses(Node node, String variable, ValueField field, boolean all, List<Object> within) {
            switch (node) {
                case Or or when !all -> {
                    geoClauses(or.left(), variable, field, false, within);
                    geoClauses(or.right(), variable, field, false, within);
                }
                case And and when all -> {
                    geoClauses(and.left(), variable, field, true, within);
                    geoClauses(and.right(), variable, field, true, within);
                }
                case And and -> throw invalid(GEO_COMBINATION);
                case Or or -> throw invalid(GEO_COMBINATION);
                case Intersects intersects -> throw all ? invalid(GEO_FORM) : intersectsUnserved();
                case Not not when not.operand() instanceof Intersects -> throw all ? intersectsUnserved()
                        : invalid(GEO_FORM);
                case Compare compare -> {
                    Node left = compare.left();
                    Node right = compare.right();
                    String operator = compare.operator();
                    if (left instanceof Literal) {
                        left = compare.right();
                        right = compare.left();
                        operator = flip(operator);
                    }
                    if (!(left instanceof Distance distance) || !bound(distance.variable(), variable)
                            || !(right instanceof Literal limit)
                            || limit.kind() != LiteralKind.INTEGER && limit.kind() != LiteralKind.DOUBLE
                            || limit.isNaN()) {
                        throw invalid(GEO_FORM);
                    }
                    boolean close = "lt".equals(operator) || "le".equals(operator);
                    boolean far = "gt".equals(operator) || "ge".equals(operator);
                    if (all ? !far : !close) {
                        throw invalid(GEO_FORM);
                    }
                    double kilometres = ((BigDecimal) limit.value()).doubleValue();
                    if (settled(operator, kilometres).isEmpty()) {
                        // Settled, an any clause matches no element and an all clause fails none: it adds nothing.
                        within.add(geofilt(field, distance, kilometres));
                    }
                }
                default -> throw invalid(GEO_FORM);
            }
        }

        private Object rangeLambda(ValueField field, Lambda lambda, String variable) {
            requireNormalForm(lambda.body(), lambda.all(), false, true);
            List<Intervals.Interval> accepted = intervals(lambda.body(), variable, field);
            if (lambda.all()) {
                return not(ranges(field, Intervals.complement(accepted)));
            }
            return ranges(field, accepted);
        }

        /**
         * Refuses what Learn does not allow in a lambda over numbers or dates: {@code any} takes ORs of ANDs and a
         * {@code ne} only alone, {@code all} ANDs of ORs and an {@code eq} only alone.
         *
         * @param split whether an {@code and} (for {@code any}) or an {@code or} (for {@code all}) is above
         * @param whole whether the node is the whole body
         */
        private void requireNormalForm(Node node, boolean all, boolean split, boolean whole) {
            switch (node) {
                case Or or -> {
                    if (!all && split) {
                        throw invalid(NORMAL_FORM);
                    }
                    requireNormalForm(or.left(), all, all, false);
                    requireNormalForm(or.right(), all, all, false);
                }
                case And and -> {
                    if (all && split) {
                        throw invalid(NORMAL_FORM);
                    }
                    requireNormalForm(and.left(), all, !all, false);
                    requireNormalForm(and.right(), all, !all, false);
                }
                case Compare compare -> requireLone(compare.operator(), all, whole);
                case Not not when not.operand() instanceof Compare compare ->
                        requireLone(negated(compare.operator()), all, whole);
                default -> throw invalid(NORMAL_FORM);
            }
        }

        private void requireLone(String operator, boolean all, boolean whole) {
            if (operator.equals(all ? "eq" : "ne") && !whole) {
                throw invalid(NORMAL_FORM);
            }
        }

        private List<Intervals.Interval> intervals(Node node, String variable, ValueField field) {
            return switch (node) {
                case Or or -> Intervals.union(intervals(or.left(), variable, field),
                        intervals(or.right(), variable, field));
                case And and -> Intervals.intersection(intervals(and.left(), variable, field),
                        intervals(and.right(), variable, field));
                case Not not -> Intervals.complement(intervals(not.operand(), variable, field));
                case Compare compare -> {
                    Comparison comparison = comparison(compare, variable);
                    if (comparison.literal().isNaN()) {
                        yield "ne".equals(comparison.operator()) ? List.of(Intervals.EVERYTHING) : List.of();
                    }
                    yield Intervals.atom(comparison.operator(),
                            ordinal(field, comparison.literal(), comparison.operator()));
                }
                default -> throw invalid(NORMAL_FORM);
            };
        }

        /** A comparison of the range variable with a constant, the variable on the left. */
        private record Comparison(String operator, Literal literal) {
        }

        private Comparison comparison(Compare compare, String variable) {
            if (bound(compare.left(), variable) && compare.right() instanceof Literal literal) {
                return new Comparison(compare.operator(), literal);
            }
            if (bound(compare.right(), variable) && compare.left() instanceof Literal literal) {
                return new Comparison(flip(compare.operator()), literal);
            }
            throw invalid("Invalid lambda expression. A comparison in it must test the range variable '" + variable
                    + "' against a constant.");
        }

        private boolean bound(Node node, String variable) {
            if (node instanceof Path path && path.segments().size() == 1 && path.segments().getFirst()
                    .equals(variable)) {
                return true;
            }
            if (node instanceof Path path) {
                throw unbound(path.text(), variable);
            }
            return false;
        }

        // ── Fields ───────────────────────────────────────────────────────

        /** The Azure path of a field path as written in a frame: inside a lambda, it starts with the variable. */
        private String resolve(Path path, Frame frame) {
            List<String> segments = path.segments();
            if (frame.variable() == null) {
                return path.text();
            }
            if (!segments.getFirst().equals(frame.variable())) {
                throw unbound(path.text(), frame.variable());
            }
            if (segments.size() == 1) {
                throw invalid("The range variable '" + frame.variable() + "' stands for an element of a complex"
                        + " collection; compare one of its fields instead.");
            }
            return frame.scope() + "/" + String.join("/", segments.subList(1, segments.size()));
        }

        private Field field(String path) {
            Map<String, Object> found = SearchSolrSchema.definitionField(definition, path);
            if (found == null) {
                throw invalid("Could not find a property named '" + path + "' on type 'search.document'.");
            }
            return new Field(path, String.valueOf(found.get("type")), found);
        }

        private ValueField valueField(Path path, Frame frame) {
            return valueField(path, frame, false);
        }

        /** The value field a path names in a frame, refused when it cannot be filtered on there. */
        private ValueField valueField(Path path, Frame frame, boolean collection) {
            String azurePath = resolve(path, frame);
            Field field = field(azurePath);
            if (field.element().equals("Edm.ComplexType")) {
                throw invalid("The complex field '" + azurePath + "' cannot be compared; filter on its sub-fields.");
            }
            if (!Boolean.TRUE.equals(field.definition().get("filterable"))) {
                throw invalid("'" + azurePath + "' is not a filterable field. Only filterable fields can be used in"
                        + " filter expressions.");
            }
            ValueField value = layout.valueField(azurePath).orElseThrow(() -> invalid("'" + azurePath
                    + "' is not a filterable field. Only filterable fields can be used in filter expressions."));
            if (!value.scope().equals(frame.scope())) {
                throw invalid("'" + azurePath + "' is a field of the elements of '" + value.scope() + "'. Filter on"
                        + " it inside a lambda expression ('any' or 'all') over '" + value.scope() + "'.");
            }
            if (value.collection() != collection) {
                throw invalid(collection ? "The 'any' and 'all' operators can only be used on collections; '"
                        + azurePath + "' is not one." : "'" + azurePath + "' is a collection. Filter on its values"
                        + " with a lambda expression ('any' or 'all').");
            }
            return value;
        }

        private void requireGeo(ValueField field) {
            if (!"Edm.GeographyPoint".equals(field.type())) {
                throw invalid("geo.distance can only be used with fields of type Edm.GeographyPoint.");
            }
        }

        // ── Solr queries ─────────────────────────────────────────────────

        private Object term(String solrName, String value) {
            return "{!term f=" + solrName + " v=" + params.ref(value) + "}";
        }

        private Object terms(String solrName, List<String> values) {
            if (values.isEmpty()) {
                return NONE;
            }
            String separator = values.stream().anyMatch(value -> value.contains(",")) ? "\u001f" : ",";
            return "{!terms f=" + solrName + " separator=" + params.ref(separator) + " v="
                    + params.ref(String.join(separator, values)) + "}";
        }

        private Object geofilt(ValueField field, Distance distance, double kilometres) {
            return "{!geofilt sfield=" + field.solrName() + " pt=" + distance.latitude() + "," + distance.longitude()
                    + " d=" + kilometres + "}";
        }

        private Object ranges(ValueField field, List<Intervals.Interval> intervals) {
            List<Object> queries = new ArrayList<>();
            for (Intervals.Interval interval : intervals) {
                Object query = range(field, interval);
                if (query != null) {
                    queries.add(query);
                }
            }
            if (queries.isEmpty()) {
                return NONE;
            }
            return queries.size() == 1 ? queries.getFirst() : Map.of("bool", Map.of("should", queries));
        }

        /** A Solr range query over an interval, or null when the field's type has no value in it. */
        private Object range(ValueField field, Intervals.Interval interval) {
            Intervals.Bound low = interval.low();
            Intervals.Bound high = interval.high();
            String type = field.type();
            if ("Edm.Int32".equals(type) || "Edm.Int64".equals(type)) {
                BigDecimal min = "Edm.Int32".equals(type) ? BigDecimal.valueOf(Integer.MIN_VALUE)
                        : BigDecimal.valueOf(Long.MIN_VALUE);
                BigDecimal max = "Edm.Int32".equals(type) ? BigDecimal.valueOf(Integer.MAX_VALUE)
                        : BigDecimal.valueOf(Long.MAX_VALUE);
                BigDecimal from = low.value() == null ? min : low.inclusive()
                        ? low.value().setScale(0, RoundingMode.CEILING)
                        : low.value().setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE);
                BigDecimal to = high.value() == null ? max : high.inclusive()
                        ? high.value().setScale(0, RoundingMode.FLOOR)
                        : high.value().setScale(0, RoundingMode.CEILING).subtract(BigDecimal.ONE);
                from = from.max(min);
                to = to.min(max);
                if (from.compareTo(to) > 0) {
                    return null;
                }
                return field.solrName() + ":[" + (from.equals(min) ? "*" : from.toPlainString()) + " TO "
                        + (to.equals(max) ? "*" : to.toPlainString()) + "]";
            }
            return field.solrName() + ":" + (low.inclusive() || low.value() == null ? "[" : "{")
                    + bound(type, low.value()) + " TO " + bound(type, high.value())
                    + (high.inclusive() || high.value() == null ? "]" : "}");
        }

        private static String bound(String type, BigDecimal value) {
            if (value == null) {
                return "*";
            }
            if ("Edm.DateTimeOffset".equals(type)) {
                return quote(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(value.longValueExact())));
            }
            if (value.abs().compareTo(INFINITY) >= 0) {
                return quote(value.signum() > 0 ? "Infinity" : "-Infinity");
            }
            return quote(value.toPlainString());
        }

        private static Object exists(String solrName) {
            return solrName + ":[* TO *]";
        }

        // ── Errors ───────────────────────────────────────────────────────

        private static Refused invalid(String message) {
            return SearchOData.invalid(PARAMETER, message);
        }

        private static Refused notBoolean() {
            return invalid("The $filter expression must be of type 'Edm.Boolean'.");
        }

        private static Refused unbound(String path, String variable) {
            return invalid("Invalid lambda expression. The field '" + path + "' isn't bound to the range variable '"
                    + variable + "'. Only bound field references are supported inside lambda expressions ('any' or"
                    + " 'all').");
        }

        private static Refused incompatible(String fieldType, String literalType, String operator) {
            return invalid("A binary operator with incompatible types was detected. Found operand types '"
                    + fieldType + "' and '" + literalType + "' for operator kind '" + kind(operator) + "'.");
        }

        private static Refused intersectsUnserved() {
            return new Refused(SearchErrors.error(400, "FeatureNotSupportedInService", "The filter uses"
                    + " geo.intersects, which floci-az does not serve: its Solr sidecar has no polygon support."));
        }
    }

    // ── Combinators ──────────────────────────────────────────────────────────

    private static Q and(Q left, Q right) {
        List<Object> must = new ArrayList<>();
        List<Object> filter = new ArrayList<>();
        for (Q q : List.of(left, right)) {
            (q.scoring() ? must : filter).add(q.dsl());
        }
        Map<String, Object> bool = new LinkedHashMap<>();
        if (!must.isEmpty()) {
            bool.put("must", must);
        }
        if (!filter.isEmpty()) {
            bool.put("filter", filter);
        }
        return new Q(Map.of("bool", bool), !must.isEmpty());
    }

    private static Q or(Q left, Q right) {
        boolean scoring = left.scoring() || right.scoring();
        List<Object> should = new ArrayList<>();
        for (Q q : List.of(left, right)) {
            // Under a scoring query, a filter matches with a score of zero.
            should.add(scoring && !q.scoring() ? Map.of("bool", Map.of("filter", List.of(q.dsl()))) : q.dsl());
        }
        return new Q(Map.of("bool", Map.of("should", should)), scoring);
    }

    private static Object not(Object query) {
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", List.of(ALL));
        bool.put("must_not", List.of(query));
        return Map.of("bool", bool);
    }

    private static List<String> split(String values, String delimiters, ValueField field) {
        List<String> found = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char c : values.toCharArray()) {
            if (delimiters.indexOf(c) >= 0) {
                if (!current.isEmpty()) {
                    found.add(field.normalizer().apply(current.toString()));
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            found.add(field.normalizer().apply(current.toString()));
        }
        return found;
    }

    private static String negated(String operator) {
        return switch (operator) {
            case "eq" -> "ne";
            case "ne" -> "eq";
            case "gt" -> "le";
            case "le" -> "gt";
            case "lt" -> "ge";
            default -> "lt";
        };
    }

    private static String flip(String operator) {
        return switch (operator) {
            case "gt" -> "lt";
            case "lt" -> "gt";
            case "ge" -> "le";
            case "le" -> "ge";
            default -> operator;
        };
    }

    private static String kind(String operator) {
        return switch (operator) {
            case "eq" -> "Equal";
            case "ne" -> "NotEqual";
            case "gt" -> "GreaterThan";
            case "lt" -> "LessThan";
            case "ge" -> "GreaterThanOrEqual";
            default -> "LessThanOrEqual";
        };
    }

    /** A value as a quoted term of the Lucene syntax. */
    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ── Intervals of numbers and dates ───────────────────────────────────────

    /**
     * Unions of intervals on the line of numbers (dates as epoch milliseconds): what a comparison, or a Boolean
     * combination of comparisons of one value, accepts.
     */
    static final class Intervals {

        /** A bound: its value, or null for none (an infinity), and whether it belongs to the interval. */
        record Bound(BigDecimal value, boolean inclusive) {
        }

        record Interval(Bound low, Bound high) {
            boolean empty() {
                if (low.value() == null || high.value() == null) {
                    return false;
                }
                int order = low.value().compareTo(high.value());
                return order > 0 || order == 0 && !(low.inclusive() && high.inclusive());
            }
        }

        static final Interval EVERYTHING = new Interval(new Bound(null, false), new Bound(null, false));

        private Intervals() {
        }

        static List<Interval> atom(String operator, BigDecimal value) {
            Bound none = new Bound(null, false);
            return switch (operator) {
                case "eq" -> List.of(new Interval(new Bound(value, true), new Bound(value, true)));
                case "ne" -> List.of(new Interval(none, new Bound(value, false)),
                        new Interval(new Bound(value, false), none));
                case "gt" -> List.of(new Interval(new Bound(value, false), none));
                case "ge" -> List.of(new Interval(new Bound(value, true), none));
                case "lt" -> List.of(new Interval(none, new Bound(value, false)));
                default -> List.of(new Interval(none, new Bound(value, true)));
            };
        }

        static List<Interval> union(List<Interval> left, List<Interval> right) {
            List<Interval> all = new ArrayList<>(left);
            all.addAll(right);
            return normalized(all);
        }

        static List<Interval> intersection(List<Interval> left, List<Interval> right) {
            List<Interval> found = new ArrayList<>();
            for (Interval a : left) {
                for (Interval b : right) {
                    Interval both = new Interval(maxLow(a.low(), b.low()), minHigh(a.high(), b.high()));
                    if (!both.empty()) {
                        found.add(both);
                    }
                }
            }
            return normalized(found);
        }

        static List<Interval> complement(List<Interval> intervals) {
            List<Interval> gaps = new ArrayList<>();
            Bound start = new Bound(null, false);
            for (Interval interval : normalized(intervals)) {
                if (interval.low().value() != null) {
                    Interval gap = new Interval(start, new Bound(interval.low().value(), !interval.low().inclusive()));
                    if (!gap.empty()) {
                        gaps.add(gap);
                    }
                }
                if (interval.high().value() == null) {
                    return gaps;
                }
                start = new Bound(interval.high().value(), !interval.high().inclusive());
            }
            Interval rest = new Interval(start, new Bound(null, false));
            if (!rest.empty()) {
                gaps.add(rest);
            }
            return gaps;
        }

        /** Sorted, with overlapping or touching intervals merged. */
        private static List<Interval> normalized(List<Interval> intervals) {
            List<Interval> sorted = new ArrayList<>(intervals.stream().filter(i -> !i.empty()).toList());
            sorted.sort(Comparator.comparing(Interval::low, Intervals::compareLows));
            List<Interval> merged = new ArrayList<>();
            for (Interval interval : sorted) {
                if (!merged.isEmpty() && touches(merged.getLast(), interval)) {
                    Interval last = merged.removeLast();
                    merged.add(new Interval(last.low(), maxHigh(last.high(), interval.high())));
                } else {
                    merged.add(interval);
                }
            }
            return merged;
        }

        private static boolean touches(Interval earlier, Interval later) {
            if (earlier.high().value() == null || later.low().value() == null) {
                return true;
            }
            int order = earlier.high().value().compareTo(later.low().value());
            return order > 0 || order == 0 && (earlier.high().inclusive() || later.low().inclusive());
        }

        private static int compareLows(Bound a, Bound b) {
            if (a.value() == null || b.value() == null) {
                return a.value() == null ? (b.value() == null ? 0 : -1) : 1;
            }
            int order = a.value().compareTo(b.value());
            if (order != 0) {
                return order;
            }
            return Boolean.compare(!a.inclusive(), !b.inclusive());
        }

        private static int compareHighs(Bound a, Bound b) {
            if (a.value() == null || b.value() == null) {
                return a.value() == null ? (b.value() == null ? 0 : 1) : -1;
            }
            int order = a.value().compareTo(b.value());
            if (order != 0) {
                return order;
            }
            return Boolean.compare(a.inclusive(), b.inclusive());
        }

        private static Bound maxLow(Bound a, Bound b) {
            return compareLows(a, b) >= 0 ? a : b;
        }

        private static Bound minHigh(Bound a, Bound b) {
            return compareHighs(a, b) <= 0 ? a : b;
        }

        private static Bound maxHigh(Bound a, Bound b) {
            return compareHighs(a, b) >= 0 ? a : b;
        }
    }
}
