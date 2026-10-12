package io.floci.az.services.resourcegraph.kql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Kusto Query Language subset Azure Resource Graph clients send, as a parser and a syntax tree.
 *
 * <p>Statements: a table or a parenthesised query, then pipe operators {@code where}, {@code extend},
 * {@code project}, {@code project-away}, {@code summarize ... by}, {@code order by} / {@code sort by},
 * {@code distinct}, {@code take} / {@code limit}, {@code union}, {@code join kind=... on} and
 * {@code count}. Expressions: literals (strings, numbers, booleans, {@code null}, {@code dynamic(...)},
 * {@code datetime(...)}), columns with dotted and indexed property paths, function calls, the comparison
 * operators including the case-insensitive {@code =~} / {@code !~} / {@code in~} / {@code !in~} family,
 * the string operators {@code has}, {@code contains}, {@code startswith}, {@code endswith} and their
 * negations, arithmetic, {@code and} / {@code or} / {@code not}.
 */
public final class Kql {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Kql() {
    }

    // ── Syntax tree ─────────────────────────────────────────────────────────────

    public record Query(Source source, List<Op> ops) {
    }

    public sealed interface Source permits TableSource, SubquerySource {
    }

    public record TableSource(String name) implements Source {
    }

    public record SubquerySource(Query query) implements Source {
    }

    public sealed interface Op permits Where, Extend, Project, ProjectAway, Summarize, OrderBy, Distinct, Take,
        Union, Join, Count {
    }

    public record Where(Expr predicate) implements Op {
    }

    public record Extend(List<Named> columns) implements Op {
    }

    public record Project(List<Named> columns) implements Op {
    }

    public record ProjectAway(List<String> columns) implements Op {
    }

    public record Summarize(List<Named> aggregates, List<Named> by) implements Op {
    }

    public record OrderBy(List<SortKey> keys) implements Op {
    }

    public record Distinct(List<String> columns) implements Op {
    }

    public record Take(long count) implements Op {
    }

    public record Union(List<Query> queries) implements Op {
    }

    public record Join(String kind, Query right, List<JoinKey> keys) implements Op {
    }

    public record Count() implements Op {
    }

    /** A column expression with the name it is bound to; {@code name} is null for an unnamed expression. */
    public record Named(String name, Expr expr) {
    }

    public record SortKey(Expr expr, boolean descending, Boolean nullsFirst) {
    }

    /** {@code $left.a == $right.b}, or a shared column name on both sides. */
    public record JoinKey(List<String> left, List<String> right) {
    }

    public sealed interface Expr permits Lit, Col, Call, Bin, Not, In, Member, Index, Neg {
    }

    public record Lit(Object value) implements Expr {
    }

    /** A column reference, with the property path that follows it. */
    public record Col(List<String> path) implements Expr {
        public String leaf() {
            return path.getLast();
        }
    }

    public record Call(String name, List<Expr> args) implements Expr {
    }

    public record Bin(String op, Expr left, Expr right) implements Expr {
    }

    public record Not(Expr expr) implements Expr {
    }

    public record In(Expr expr, List<Expr> options, boolean ignoreCase, boolean negated) implements Expr {
    }

    public record Member(Expr target, String name) implements Expr {
    }

    public record Index(Expr target, Expr index) implements Expr {
    }

    public record Neg(Expr expr) implements Expr {
    }

    // ── Lexer ───────────────────────────────────────────────────────────────────

    enum Kind { IDENT, NUMBER, STRING, RAW, PIPE, LPAREN, RPAREN, COMMA, DOT, LBRACKET, RBRACKET, OP, EOF }

    record Token(Kind kind, String text, int position) {
        @Override
        public String toString() {
            return kind == Kind.EOF ? "end of query" : "'" + text + "'";
        }
    }

    private static final Set<String> RAW_FUNCTIONS = Set.of("dynamic", "datetime", "timespan", "time", "guid", "real");

    static List<Token> tokenize(String source) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '|') {
                out.add(new Token(Kind.PIPE, "|", i++));
            } else if (c == '(') {
                out.add(new Token(Kind.LPAREN, "(", i++));
            } else if (c == ')') {
                out.add(new Token(Kind.RPAREN, ")", i++));
            } else if (c == ',') {
                out.add(new Token(Kind.COMMA, ",", i++));
            } else if (c == '.' && i + 1 < n && source.charAt(i + 1) == '.') {
                out.add(new Token(Kind.OP, "..", i));
                i += 2;
            } else if (c == '.') {
                out.add(new Token(Kind.DOT, ".", i++));
            } else if (c == '[') {
                out.add(new Token(Kind.LBRACKET, "[", i++));
            } else if (c == ']') {
                out.add(new Token(Kind.RBRACKET, "]", i++));
            } else if (c == '"' || c == '\'') {
                i = string(source, i, out);
            } else if (c == '@' && i + 1 < n && (source.charAt(i + 1) == '"' || source.charAt(i + 1) == '\'')) {
                char quote = source.charAt(i + 1);
                int end = source.indexOf(quote, i + 2);
                if (end < 0) {
                    throw KqlException.parse("Unterminated string literal at position " + i);
                }
                out.add(new Token(Kind.STRING, source.substring(i + 2, end), i));
                i = end + 1;
            } else if (c == 'h' && i + 1 < n && (source.charAt(i + 1) == '"' || source.charAt(i + 1) == '\'')) {
                i = string(source, i + 1, out);
            } else if (Character.isDigit(c)) {
                int start = i++;
                while (i < n) {
                    char d = source.charAt(i);
                    if (d == '.' && i + 1 < n && source.charAt(i + 1) == '.') {
                        break; // the range separator of between (a .. b)
                    }
                    if ((d == 'e' || d == 'E') && i + 2 < n && (source.charAt(i + 1) == '+' || source.charAt(i + 1) == '-')
                            && Character.isDigit(source.charAt(i + 2)) && Character.isDigit(source.charAt(i - 1))) {
                        i += 2; // a signed exponent
                        continue;
                    }
                    if (!Character.isLetterOrDigit(d) && d != '.') {
                        break;
                    }
                    i++;
                }
                out.add(new Token(Kind.NUMBER, source.substring(start, i), start));
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                int start = i++;
                while (i < n && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_'
                        || (source.charAt(i) == '-' && i + 1 < n && Character.isLetter(source.charAt(i + 1))
                            && isOperatorWord(source.substring(start, i))))) {
                    i++;
                }
                String word = source.substring(start, i);
                if (RAW_FUNCTIONS.contains(word.toLowerCase(Locale.ROOT)) && i < n && source.charAt(i) == '(') {
                    int depth = 0;
                    int j = i;
                    for (; j < n; j++) {
                        char d = source.charAt(j);
                        if (d == '(') {
                            depth++;
                        } else if (d == ')') {
                            depth--;
                            if (depth == 0) {
                                break;
                            }
                        } else if (d == '"' || d == '\'') {
                            j = skipString(source, j);
                        }
                    }
                    if (j >= n) {
                        throw KqlException.parse("Unterminated " + word + "(...) at position " + start);
                    }
                    out.add(new Token(Kind.RAW, word.toLowerCase(Locale.ROOT) + ":" + source.substring(i + 1, j).trim(), start));
                    i = j + 1;
                } else {
                    out.add(new Token(Kind.IDENT, word, start));
                }
            } else {
                String two = i + 1 < n ? source.substring(i, i + 2) : "";
                if (Set.of("==", "!=", "=~", "!~", "<=", ">=", "<>", "..").contains(two)) {
                    out.add(new Token(Kind.OP, two, i));
                    i += 2;
                } else if ("=<>+-*/%!~".indexOf(c) >= 0) {
                    out.add(new Token(Kind.OP, String.valueOf(c), i++));
                } else {
                    throw KqlException.parse("Unexpected character '" + c + "' at position " + i);
                }
            }
        }
        out.add(new Token(Kind.EOF, "", n));
        return out;
    }

    private static boolean isOperatorWord(String word) {
        return word.equalsIgnoreCase("project") || word.equalsIgnoreCase("mv");
    }

    private static int string(String source, int start, List<Token> out) {
        char quote = source.charAt(start);
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < source.length()) {
                char e = source.charAt(i + 1);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '0' -> sb.append('\0');
                    case 'a' -> sb.append('\u0007');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'v' -> sb.append('\u000B');
                    default -> sb.append(e);
                }
                i += 2;
            } else if (c == quote) {
                out.add(new Token(Kind.STRING, sb.toString(), start));
                return i + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        throw KqlException.parse("Unterminated string literal at position " + start);
    }

    private static int skipString(String source, int start) {
        char quote = source.charAt(start);
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                return i;
            } else {
                i++;
            }
        }
        return i;
    }

    // ── Parser ──────────────────────────────────────────────────────────────────

    public static Query parse(String source) {
        if (source == null || source.isBlank()) {
            throw KqlException.parse("The query is empty");
        }
        Parser parser = new Parser(tokenize(source));
        Query query = parser.query();
        parser.expect(Kind.EOF);
        return query;
    }

    private static final Set<String> COMPARISONS = Set.of("==", "!=", "=~", "!~", "<", "<=", ">", ">=", "<>");
    private static final Set<String> STRING_OPERATORS = Set.of("has", "contains", "startswith", "endswith",
        "has_cs", "contains_cs", "startswith_cs", "endswith_cs", "hasprefix", "hassuffix", "matches");

    private static final class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Query query() {
            Source source;
            if (peek().kind() == Kind.LPAREN) {
                next();
                source = new SubquerySource(query());
                expect(Kind.RPAREN);
            } else {
                Token table = expect(Kind.IDENT);
                source = new TableSource(table.text());
            }
            List<Op> ops = new ArrayList<>();
            while (peek().kind() == Kind.PIPE) {
                next();
                ops.add(op());
            }
            return new Query(source, List.copyOf(ops));
        }

        private Op op() {
            Token token = expect(Kind.IDENT);
            String word = token.text().toLowerCase(Locale.ROOT);
            switch (word) {
                case "where", "filter" -> {
                    return new Where(expr());
                }
                case "extend" -> {
                    return new Extend(namedList());
                }
                case "project" -> {
                    return new Project(namedList());
                }
                case "project-away" -> {
                    return new ProjectAway(identList());
                }
                case "summarize" -> {
                    List<Named> aggregates = new ArrayList<>();
                    if (!isWord("by")) {
                        aggregates.addAll(namedList());
                    }
                    List<Named> by = new ArrayList<>();
                    if (isWord("by")) {
                        next();
                        by.addAll(namedList());
                    }
                    return new Summarize(List.copyOf(aggregates), List.copyOf(by));
                }
                case "order", "sort" -> {
                    if (!isWord("by")) {
                        throw KqlException.parse("Expected 'by' after '" + word + "'");
                    }
                    next();
                    List<SortKey> keys = new ArrayList<>();
                    do {
                        Expr expr = expr();
                        boolean descending = true; // Kusto sorts descending unless asc is given
                        Boolean nullsFirst = null;
                        if (isWord("asc")) {
                            next();
                            descending = false;
                        } else if (isWord("desc")) {
                            next();
                        }
                        if (isWord("nulls")) {
                            next();
                            Token which = expect(Kind.IDENT);
                            nullsFirst = which.text().equalsIgnoreCase("first");
                        }
                        keys.add(new SortKey(expr, descending, nullsFirst));
                    } while (comma());
                    return new OrderBy(List.copyOf(keys));
                }
                case "distinct" -> {
                    if (peek().kind() == Kind.OP && peek().text().equals("*")) {
                        next();
                        return new Distinct(List.of());
                    }
                    return new Distinct(identList());
                }
                case "take", "limit" -> {
                    Token count = expect(Kind.NUMBER);
                    return new Take(Long.parseLong(count.text()));
                }
                case "union" -> {
                    if (isWord("kind")) {
                        next();
                        expectOp("=");
                        expect(Kind.IDENT);
                    }
                    List<Query> queries = new ArrayList<>();
                    do {
                        if (peek().kind() == Kind.LPAREN) {
                            next();
                            queries.add(query());
                            expect(Kind.RPAREN);
                        } else {
                            Token table = expect(Kind.IDENT);
                            queries.add(new Query(new TableSource(table.text()), List.of()));
                        }
                    } while (comma());
                    return new Union(List.copyOf(queries));
                }
                case "join" -> {
                    String kind = "innerunique";
                    while (isWord("kind") || isWord("hint")) {
                        String option = next().text().toLowerCase(Locale.ROOT);
                        if (peek().kind() == Kind.DOT) {
                            next();
                            expect(Kind.IDENT);
                        }
                        expectOp("=");
                        String value = expect(Kind.IDENT).text().toLowerCase(Locale.ROOT);
                        if (option.equals("kind")) {
                            kind = value;
                        }
                    }
                    expect(Kind.LPAREN);
                    Query right = query();
                    expect(Kind.RPAREN);
                    if (!isWord("on")) {
                        throw KqlException.parse("Expected 'on' after the join source");
                    }
                    next();
                    List<JoinKey> keys = new ArrayList<>();
                    do {
                        keys.add(joinKey());
                    } while (comma());
                    return new Join(kind, right, List.copyOf(keys));
                }
                case "count" -> {
                    return new Count();
                }
                default -> throw KqlException.parse("Unknown operator '" + token.text() + "' at position " + token.position());
            }
        }

        private JoinKey joinKey() {
            if (peek().kind() == Kind.IDENT && peek().text().equals("$left")) {
                List<String> left = sidePath("$left");
                expectOp("==");
                List<String> right = sidePath("$right");
                return new JoinKey(left, right);
            }
            if (peek().kind() == Kind.IDENT && peek().text().equals("$right")) {
                List<String> right = sidePath("$right");
                expectOp("==");
                List<String> left = sidePath("$left");
                return new JoinKey(left, right);
            }
            List<String> path = path();
            return new JoinKey(path, path);
        }

        private List<String> sidePath(String side) {
            Token token = expect(Kind.IDENT);
            if (!token.text().equals(side)) {
                throw KqlException.parse("Expected " + side + " at position " + token.position());
            }
            expect(Kind.DOT);
            return path();
        }

        private List<String> path() {
            List<String> path = new ArrayList<>();
            path.add(expect(Kind.IDENT).text());
            while (peek().kind() == Kind.DOT) {
                next();
                path.add(expect(Kind.IDENT).text());
            }
            return List.copyOf(path);
        }

        private List<Named> namedList() {
            List<Named> out = new ArrayList<>();
            do {
                out.add(named());
            } while (comma());
            return List.copyOf(out);
        }

        private Named named() {
            if (peek().kind() == Kind.IDENT && peekAt(1).kind() == Kind.OP && peekAt(1).text().equals("=")) {
                String name = next().text();
                next();
                return new Named(name, expr());
            }
            return new Named(null, expr());
        }

        private List<String> identList() {
            List<String> out = new ArrayList<>();
            do {
                out.add(expect(Kind.IDENT).text());
            } while (comma());
            return List.copyOf(out);
        }

        private boolean comma() {
            if (peek().kind() == Kind.COMMA) {
                next();
                return true;
            }
            return false;
        }

        // expressions

        Expr expr() {
            Expr left = andExpr();
            while (isWord("or")) {
                next();
                left = new Bin("or", left, andExpr());
            }
            return left;
        }

        private Expr andExpr() {
            Expr left = comparison();
            while (isWord("and")) {
                next();
                left = new Bin("and", left, comparison());
            }
            return left;
        }

        private Expr comparison() {
            Expr left = additive();
            Token token = peek();
            if (token.kind() == Kind.OP && COMPARISONS.contains(token.text())) {
                next();
                String op = token.text().equals("<>") ? "!=" : token.text();
                return new Bin(op, left, additive());
            }
            if (token.kind() == Kind.OP && token.text().equals("!") && peekAt(1).kind() == Kind.IDENT) {
                next();
                Token word = next();
                String name = word.text().toLowerCase(Locale.ROOT);
                if (name.equals("in")) {
                    return inList(left, true);
                }
                if (STRING_OPERATORS.contains(name)) {
                    return new Not(new Bin(name, left, additive()));
                }
                throw KqlException.parse("Unknown operator '!" + word.text() + "'");
            }
            if (token.kind() == Kind.IDENT) {
                String name = token.text().toLowerCase(Locale.ROOT);
                if (name.equals("in")) {
                    next();
                    return inList(left, false);
                }
                if (name.equals("between")) {
                    next();
                    expect(Kind.LPAREN);
                    Expr low = additive();
                    if (!(peek().kind() == Kind.OP && peek().text().equals(".."))) {
                        throw KqlException.parse("Expected '..' in between (low .. high)");
                    }
                    next();
                    Expr high = additive();
                    expect(Kind.RPAREN);
                    return new Bin("and", new Bin(">=", left, low), new Bin("<=", left, high));
                }
                if (STRING_OPERATORS.contains(name)) {
                    next();
                    if (name.equals("matches")) {
                        if (!isWord("regex")) {
                            throw KqlException.parse("Expected 'regex' after 'matches'");
                        }
                        next();
                        return new Bin("matches regex", left, additive());
                    }
                    return new Bin(name, left, additive());
                }
            }
            return left;
        }

        private Expr inList(Expr left, boolean negated) {
            boolean ignoreCase = false;
            if (peek().kind() == Kind.OP && peek().text().equals("~")) {
                next();
                ignoreCase = true;
            }
            expect(Kind.LPAREN);
            List<Expr> options = new ArrayList<>();
            if (peek().kind() != Kind.RPAREN) {
                if (peek().kind() == Kind.IDENT && peekAt(1).kind() == Kind.PIPE) {
                    throw KqlException.parse("Subqueries inside 'in' are not supported");
                }
                do {
                    options.add(expr());
                } while (comma());
            }
            expect(Kind.RPAREN);
            return new In(left, List.copyOf(options), ignoreCase, negated);
        }

        private Expr additive() {
            Expr left = multiplicative();
            while (peek().kind() == Kind.OP && (peek().text().equals("+") || peek().text().equals("-"))) {
                String op = next().text();
                left = new Bin(op, left, multiplicative());
            }
            return left;
        }

        private Expr multiplicative() {
            Expr left = unary();
            while (peek().kind() == Kind.OP && (peek().text().equals("*") || peek().text().equals("/") || peek().text().equals("%"))) {
                String op = next().text();
                left = new Bin(op, left, unary());
            }
            return left;
        }

        private Expr unary() {
            if (peek().kind() == Kind.OP && peek().text().equals("-")) {
                next();
                return new Neg(unary());
            }
            if (peek().kind() == Kind.OP && peek().text().equals("!")) {
                next();
                return new Not(unary());
            }
            return postfix(primary());
        }

        private Expr postfix(Expr target) {
            while (true) {
                if (peek().kind() == Kind.DOT) {
                    next();
                    String name = expect(Kind.IDENT).text();
                    if (target instanceof Col col) {
                        List<String> path = new ArrayList<>(col.path());
                        path.add(name);
                        target = new Col(List.copyOf(path));
                    } else {
                        target = new Member(target, name);
                    }
                } else if (peek().kind() == Kind.LBRACKET) {
                    next();
                    Expr index = expr();
                    expect(Kind.RBRACKET);
                    target = new Index(target, index);
                } else {
                    return target;
                }
            }
        }

        private Expr primary() {
            Token token = next();
            switch (token.kind()) {
                case NUMBER -> {
                    return new Lit(number(token.text()));
                }
                case STRING -> {
                    return new Lit(token.text());
                }
                case RAW -> {
                    return new Lit(raw(token.text()));
                }
                case LPAREN -> {
                    Expr inner = expr();
                    expect(Kind.RPAREN);
                    return inner;
                }
                case IDENT -> {
                    String lower = token.text().toLowerCase(Locale.ROOT);
                    if (peek().kind() == Kind.LPAREN) {
                        next();
                        List<Expr> args = new ArrayList<>();
                        if (peek().kind() != Kind.RPAREN) {
                            do {
                                if (peek().kind() == Kind.OP && peek().text().equals("*")) {
                                    next();
                                    args.add(new Col(List.of("*")));
                                } else {
                                    args.add(expr());
                                }
                            } while (comma());
                        }
                        expect(Kind.RPAREN);
                        if (lower.equals("not") && args.size() == 1) {
                            return new Not(args.getFirst());
                        }
                        return new Call(lower, List.copyOf(args));
                    }
                    return switch (lower) {
                        case "true" -> new Lit(Boolean.TRUE);
                        case "false" -> new Lit(Boolean.FALSE);
                        case "null" -> new Lit(null);
                        default -> new Col(List.of(token.text()));
                    };
                }
                default -> throw KqlException.parse("Unexpected " + token + " at position " + token.position());
            }
        }

        private static Object number(String text) {
            try {
                if (text.contains(".") || text.contains("e") || text.contains("E")) {
                    return Double.parseDouble(text);
                }
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw KqlException.parse("'" + text + "' is not a number");
            }
        }

        private static Object raw(String token) {
            int colon = token.indexOf(':');
            String kind = token.substring(0, colon);
            String body = token.substring(colon + 1);
            switch (kind) {
                case "dynamic" -> {
                    try {
                        return MAPPER.readValue(body, Object.class);
                    } catch (JsonProcessingException e) {
                        throw KqlException.parse("dynamic(...) holds invalid JSON: " + e.getOriginalMessage());
                    }
                }
                case "datetime" -> {
                    String text = body.replace("\"", "").replace("'", "").trim();
                    return KqlValues.toDateTime(text);
                }
                case "real" -> {
                    return Double.parseDouble(body.trim());
                }
                case "guid" -> {
                    return body.replace("\"", "").replace("'", "").trim();
                }
                default -> {
                    return KqlValues.toTimespan(body.replace("\"", "").replace("'", "").trim());
                }
            }
        }

        // tokens

        private Token peek() {
            return tokens.get(pos);
        }

        private Token peekAt(int offset) {
            int index = Math.min(pos + offset, tokens.size() - 1);
            return tokens.get(index);
        }

        private Token next() {
            Token token = tokens.get(pos);
            if (token.kind() != Kind.EOF) {
                pos++;
            }
            return token;
        }

        private boolean isWord(String word) {
            return peek().kind() == Kind.IDENT && peek().text().equalsIgnoreCase(word);
        }

        Token expect(Kind kind) {
            Token token = next();
            if (token.kind() != kind) {
                throw KqlException.parse("Expected " + kind.name().toLowerCase(Locale.ROOT) + " but found " + token
                    + " at position " + token.position());
            }
            return token;
        }

        private void expectOp(String op) {
            Token token = next();
            if (token.kind() != Kind.OP || !token.text().equals(op)) {
                throw KqlException.parse("Expected '" + op + "' but found " + token + " at position " + token.position());
            }
        }
    }
}
