package io.floci.az.services.blob;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A blob index tag expression: the {@code where} filter of Find Blobs by Tags and the
 * {@code x-ms-if-tags} conditional header.
 *
 * <p>The grammar and its validation are Azurite's {@code QueryParser}:
 * <pre>
 * EXPRESSION := AND ("or" AND)*            ("or" only in x-ms-if-tags)
 * AND        := GROUP ("and" GROUP)*
 * GROUP      := "(" EXPRESSION ")" | KEY OPERATOR VALUE
 * OPERATOR   := "=" | "&gt;" | "&gt;=" | "&lt;" | "&lt;=" | "&lt;&gt;"   ("&lt;&gt;" only in x-ms-if-tags)
 * KEY        := identifier | '"' identifier '"'   (or @container, only in where)
 * VALUE      := "'" text "'"                     ('' escapes a quote)
 * </pre>
 * A comparison holds only when the blob has the tag; values compare lexicographically. A
 * {@code where} filter may reference at most 10 distinct tags, and repeats a tag only to bound a
 * range.
 */
final class BlobTagQuery {

    static final String CONTAINER_KEY = "@container";
    static final String IF_TAGS_HEADER = "x-ms-if-tags";

    private static final int MAX_FILTER_TAGS = 10;

    private final Node root;
    private final Set<String> referencedKeys;

    private BlobTagQuery(Node root, Set<String> referencedKeys) {
        this.root = root;
        this.referencedKeys = referencedKeys;
    }

    /** Parses a Find Blobs by Tags {@code where} expression; failures are InvalidQueryParameterValue. */
    static BlobTagQuery where(String expression) {
        return new Parser(expression, false).parse();
    }

    /** Parses an {@code x-ms-if-tags} condition; failures are InvalidHeaderValue. */
    static BlobTagQuery condition(String expression) {
        return new Parser(expression, true).parse();
    }

    boolean matches(Map<String, String> tags, String containerName) {
        Map<String, String> context = new LinkedHashMap<>(tags);
        context.put(CONTAINER_KEY, containerName);
        return root.evaluate(context);
    }

    /**
     * The blob's values for the tags the expression names, which is what Find Blobs by Tags
     * returns in each result's {@code Tags} (the {@code @container} pseudo-tag is not a tag).
     */
    Map<String, String> matchedTags(Map<String, String> tags) {
        Map<String, String> matched = new LinkedHashMap<>();
        for (String key : referencedKeys) {
            if (!CONTAINER_KEY.equals(key) && tags.containsKey(key)) {
                matched.put(key, tags.get(key));
            }
        }
        return matched;
    }

    private sealed interface Node permits And, Or, Comparison {
        boolean evaluate(Map<String, String> context);
    }

    private record And(Node left, Node right) implements Node {
        @Override
        public boolean evaluate(Map<String, String> context) {
            return left.evaluate(context) && right.evaluate(context);
        }
    }

    private record Or(Node left, Node right) implements Node {
        @Override
        public boolean evaluate(Map<String, String> context) {
            return left.evaluate(context) || right.evaluate(context);
        }
    }

    private record Comparison(String key, Operator operator, String value) implements Node {
        @Override
        public boolean evaluate(Map<String, String> context) {
            String actual = context.get(key);
            return actual != null && operator.holds(actual.compareTo(value));
        }
    }

    private enum Operator {
        // Longer symbols first, so ">=" is not read as ">" followed by "=".
        GREATER_OR_EQUAL(">=", Bound.LOWER),
        LESS_OR_EQUAL("<=", Bound.UPPER),
        NOT_EQUAL("<>", Bound.NONE),
        EQUAL("=", Bound.EXACT),
        GREATER(">", Bound.LOWER),
        LESS("<", Bound.UPPER);

        private final String symbol;
        private final Bound bound;

        Operator(String symbol, Bound bound) {
            this.symbol = symbol;
            this.bound = bound;
        }

        boolean holds(int comparison) {
            return switch (this) {
                case EQUAL -> comparison == 0;
                case NOT_EQUAL -> comparison != 0;
                case GREATER -> comparison > 0;
                case GREATER_OR_EQUAL -> comparison >= 0;
                case LESS -> comparison < 0;
                case LESS_OR_EQUAL -> comparison <= 0;
            };
        }
    }

    /** Which side of a range a comparison constrains, for the "only a range may repeat a tag" rule. */
    private enum Bound {
        EXACT, LOWER, UPPER, NONE
    }

    private static final class Parser {

        private final String text;
        private final boolean condition;
        private final Map<String, Set<Bound>> boundsByKey = new LinkedHashMap<>();
        private int position;

        Parser(String text, boolean condition) {
            this.text = text;
            this.condition = condition;
        }

        BlobTagQuery parse() {
            Node root = expression();
            skipWhitespace();
            if (position < text.length()) {
                throw error("Unexpected token '" + text.charAt(position) + "'.");
            }
            return new BlobTagQuery(root, new LinkedHashSet<>(boundsByKey.keySet()));
        }

        private Node expression() {
            Node left = and();
            skipWhitespace();
            if (consumeKeyword("or")) {
                if (!condition) {
                    throw error("unexpected or");
                }
                return new Or(left, expression());
            }
            return left;
        }

        private Node and() {
            Node left = group();
            skipWhitespace();
            if (consumeKeyword("and")) {
                return new And(left, and());
            }
            return left;
        }

        private Node group() {
            skipWhitespace();
            if (consume("(")) {
                Node child = expression();
                skipWhitespace();
                if (!consume(")")) {
                    throw error("Expected a ')' to close the expression group, but found '" + peekText()
                            + "' instead.");
                }
                return child;
            }
            return comparison();
        }

        private Node comparison() {
            String key = key();
            skipWhitespace();
            Operator operator = operator();
            if (operator == null) {
                throw error("expected an operator");
            }
            if (operator == Operator.NOT_EQUAL && !condition) {
                throw error("unexpected <>");
            }
            String value = value();
            recordComparison(key, operator.bound);
            return new Comparison(key, operator, value);
        }

        private Operator operator() {
            for (Operator operator : Operator.values()) {
                if (consume(operator.symbol)) {
                    return operator;
                }
            }
            return null;
        }

        private String key() {
            skipWhitespace();
            String key;
            if (peek() == '"') {
                key = quoted('"');
            } else {
                int start = position;
                while (position < text.length() && !Character.isWhitespace(text.charAt(position))
                        && "=<>".indexOf(text.charAt(position)) < 0) {
                    position++;
                }
                if (start == position) {
                    throw error("Expected a valid identifier, but found '" + peekText() + "' instead.");
                }
                key = text.substring(start, position);
            }
            validateKey(key);
            return key;
        }

        private String value() {
            skipWhitespace();
            if (peek() != '\'') {
                throw error("expecting tag value");
            }
            String value = quoted('\'');
            if (!condition && value.length() > 256) {
                throw error("tag value must be between 0 and 256 characters in length");
            }
            for (int i = 0; i < value.length(); i++) {
                if (!BlobTags.isValidTagText(String.valueOf(value.charAt(i)))) {
                    throw error("'" + value.charAt(i) + "' not permitted in tag name or value");
                }
            }
            return value;
        }

        /** A string opened by {@code quote} and closed by the next single one; a doubled quote is literal. */
        private String quoted(char quote) {
            position++;
            StringBuilder content = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c == quote) {
                    if (position + 1 < text.length() && text.charAt(position + 1) == quote) {
                        content.append(quote);
                        position += 2;
                        continue;
                    }
                    position++;
                    return content.toString();
                }
                content.append(c);
                position++;
            }
            throw error("Expected a `" + quote + "` to close the string, but found end of query instead.");
        }

        private void validateKey(String key) {
            if (key.startsWith("@")) {
                if (condition || !CONTAINER_KEY.equals(key)) {
                    throw error("unsupported parameter '" + key + "'");
                }
                return;
            }
            if (!condition && (key.isEmpty() || key.length() > 128)) {
                throw error("tag must be between 1 and 128 characters in length");
            }
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                        || c == '_';
                if (!allowed) {
                    throw error("unexpected '" + key + "'");
                }
            }
        }

        private void recordComparison(String key, Bound bound) {
            Set<Bound> previous = boundsByKey.computeIfAbsent(key, ignored -> EnumSet.noneOf(Bound.class));
            if (!condition && bound != Bound.NONE && !previous.isEmpty()) {
                boolean range = (bound == Bound.LOWER && !previous.contains(Bound.LOWER)
                        && !previous.contains(Bound.EXACT))
                        || (bound == Bound.UPPER && !previous.contains(Bound.UPPER)
                        && !previous.contains(Bound.EXACT));
                if (!range) {
                    throw error("can't have multiple conditions for a single tag unless they define a range");
                }
            }
            previous.add(bound);
            if (!condition && distinctTagCount() > MAX_FILTER_TAGS) {
                throw BlobTagException.invalidQuery(text,
                        "Error parsing query: there can be at most 10 unique tags in a query");
            }
        }

        private int distinctTagCount() {
            return boundsByKey.size() - (boundsByKey.containsKey(CONTAINER_KEY) ? 1 : 0);
        }

        private boolean consumeKeyword(String keyword) {
            if (text.regionMatches(true, position, keyword, 0, keyword.length())) {
                position += keyword.length();
                return true;
            }
            return false;
        }

        private boolean consume(String sequence) {
            if (text.startsWith(sequence, position)) {
                position += sequence.length();
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private char peek() {
            return position < text.length() ? text.charAt(position) : '\0';
        }

        private String peekText() {
            return position < text.length() ? String.valueOf(text.charAt(position)) : "";
        }

        private BlobTagException error(String message) {
            if (condition) {
                return BlobTagException.invalidHeader(IF_TAGS_HEADER, text);
            }
            return BlobTagException.invalidQuery(text,
                    "Error parsing query at or near character position " + position + ": " + message);
        }
    }
}
