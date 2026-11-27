package io.floci.az.services.search;

import io.floci.az.services.search.SearchDocuments.Refused;

import java.math.BigDecimal;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What {@code $filter} and {@code $orderby} share: the tokens of Azure's OData expression syntax, a cursor over them,
 * Azure's error shape, and the Solr parameters a translation refers to.
 *
 * <p>Literals follow Learn's OData syntax reference: strings in single quotes with {@code ''} for a quote, dates
 * unquoted ({@code 2026-01-01T00:00:00Z}, an offset allowed), numbers with an optional fraction and exponent,
 * {@code NaN}, {@code INF} and {@code -INF}, and {@code geography'POINT(lon lat)'} or {@code geography'POLYGON(...)'}.
 */
final class SearchOData {

    enum Kind { IDENTIFIER, STRING, NUMBER, DATE, GEOGRAPHY, OPEN, CLOSE, COMMA, SLASH, COLON, END }

    /** A token, where it starts in the expression, and where it ends (exclusive). */
    record Token(Kind kind, String text, int position, int end) {
        boolean is(String word) {
            return kind == Kind.IDENTIFIER && text.equals(word);
        }
    }

    private static final Pattern DATE = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:\\d{2})");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");
    private static final Pattern WHOLE = Pattern.compile("[+-]?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?");

    private SearchOData() {
    }

    /**
     * A number a caller wrote: a whole number exactly, any other as the double it denotes, so an exponent such as
     * {@code 1e1000000000} cannot grow into a billion digits. Null for a number beyond a double's range.
     *
     * @throws NumberFormatException when the text is not a number
     */
    static BigDecimal decimal(String text) {
        if (WHOLE.matcher(text).matches()) {
            return new BigDecimal(text);
        }
        if (!DECIMAL.matcher(text).matches()) {
            throw new NumberFormatException(text);
        }
        double value = Double.parseDouble(text);
        return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
    }

    /**
     * A point's longitude and latitude, each a finite number on the globe: longitude -180 to 180, latitude -90 to
     * 90.
     *
     * @throws NumberFormatException when either is not
     */
    static double[] point(String longitude, String latitude) {
        double lon = Double.parseDouble(longitude);
        double lat = Double.parseDouble(latitude);
        if (!(Math.abs(lon) <= 180) || !(Math.abs(lat) <= 90)) {
            // Also false for NaN.
            throw new NumberFormatException(longitude + " " + latitude);
        }
        return new double[] {lon, lat};
    }

    /** A {@code 400} in the words of Azure's OData parser, naming the parameter. */
    static Refused invalid(String parameter, String message) {
        return new Refused(SearchErrors.error(400, SearchErrors.NO_CODE, "Invalid expression: " + message
                + "\r\nParameter name: " + parameter));
    }

    /** A syntax error at a position, as Azure's OData parser words it. */
    static Refused syntaxError(String parameter, String expression, int position) {
        return invalid(parameter, "Syntax error at position " + position + " in '" + expression + "'.");
    }

    /** The tokens of an expression, ending with {@link Kind#END}. */
    static List<Token> tokens(String expression, String parameter) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int length = expression.length();
        while (i < length) {
            char c = expression.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            Kind single = switch (c) {
                case '(' -> Kind.OPEN;
                case ')' -> Kind.CLOSE;
                case ',' -> Kind.COMMA;
                case '/' -> Kind.SLASH;
                case ':' -> Kind.COLON;
                default -> null;
            };
            if (single != null) {
                tokens.add(new Token(single, String.valueOf(c), i, i + 1));
                i++;
                continue;
            }
            if (c == '\'') {
                int end = quoted(expression, i, parameter);
                tokens.add(new Token(Kind.STRING, unquote(expression.substring(i, end)), i, end));
                i = end;
                continue;
            }
            if (expression.startsWith("-INF", i)) {
                tokens.add(new Token(Kind.NUMBER, "-INF", i, i + 4));
                i += 4;
                continue;
            }
            if (Character.isDigit(c) || c == '-') {
                Matcher date = DATE.matcher(expression).region(i, length);
                if (date.lookingAt()) {
                    tokens.add(new Token(Kind.DATE, date.group(), i, date.end()));
                    i = date.end();
                    continue;
                }
                Matcher number = NUMBER.matcher(expression).region(i, length);
                if (number.lookingAt()) {
                    tokens.add(new Token(Kind.NUMBER, number.group(), i, number.end()));
                    i = number.end();
                    continue;
                }
                throw syntaxError(parameter, expression, i);
            }
            Matcher identifier = IDENTIFIER.matcher(expression).region(i, length);
            if (identifier.lookingAt()) {
                String word = identifier.group();
                int end = identifier.end();
                if ("geography".equals(word) && end < length && expression.charAt(end) == '\'') {
                    int close = quoted(expression, end, parameter);
                    tokens.add(new Token(Kind.GEOGRAPHY, unquote(expression.substring(end, close)), i, close));
                    i = close;
                    continue;
                }
                tokens.add(new Token(Kind.IDENTIFIER, word, i, end));
                i = end;
                continue;
            }
            throw syntaxError(parameter, expression, i);
        }
        tokens.add(new Token(Kind.END, "", length, length));
        return tokens;
    }

    /** The index just past the string literal that starts at {@code start}. */
    private static int quoted(String expression, int start, String parameter) {
        int i = start + 1;
        while (i < expression.length()) {
            if (expression.charAt(i) == '\'') {
                if (i + 1 < expression.length() && expression.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw syntaxError(parameter, expression, start);
    }

    private static String unquote(String literal) {
        return literal.substring(1, literal.length() - 1).replace("''", "'");
    }

    /** A cursor over the tokens of one expression. */
    static final class Cursor {
        private final List<Token> tokens;
        private final String expression;
        private final String parameter;
        private int index;

        Cursor(String expression, String parameter) {
            this.tokens = tokens(expression, parameter);
            this.expression = expression;
            this.parameter = parameter;
        }

        Token peek() {
            return tokens.get(index);
        }

        Token peek(int ahead) {
            return tokens.get(Math.min(index + ahead, tokens.size() - 1));
        }

        Token next() {
            Token token = tokens.get(index);
            if (token.kind() != Kind.END) {
                index++;
            }
            return token;
        }

        boolean accept(Kind kind) {
            if (peek().kind() == kind) {
                next();
                return true;
            }
            return false;
        }

        boolean accept(String word) {
            if (peek().is(word)) {
                next();
                return true;
            }
            return false;
        }

        Token expect(Kind kind) {
            if (peek().kind() != kind) {
                throw error();
            }
            return next();
        }

        /**
         * A syntax error at the current token. Azure's OData parser names the position just past the token it could
         * not use (7 for {@code is} in {@code 'This is not a valid filter.'}, as the SDK recordings show).
         */
        Refused error() {
            return syntaxError(parameter, expression, peek().end());
        }

        /** A syntax error at a token already read, such as a literal that only looked valid. */
        Refused error(Token token) {
            return syntaxError(parameter, expression, token.end());
        }

        Refused invalid(String message) {
            return SearchOData.invalid(parameter, message);
        }

        String parameter() {
            return parameter;
        }
    }

    /**
     * The Solr parameters a translation refers to as {@code $name}, so a user's value is never read as Solr syntax.
     */
    static final class Params {
        private final List<Map.Entry<String, String>> entries = new ArrayList<>();

        /** Adds a parameter and answers the reference to it. */
        String ref(String value) {
            String name = "azp" + entries.size();
            entries.add(new AbstractMap.SimpleImmutableEntry<>(name, value));
            return "$" + name;
        }

        List<Map.Entry<String, String>> entries() {
            return List.copyOf(entries);
        }
    }
}
