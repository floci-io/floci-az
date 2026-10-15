package io.floci.az.services.policy.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates the ARM template expressions a policy rule may carry: a string of the form {@code [expr]} where
 * {@code expr} is a function call with literal, nested-call, property-access and index arguments. A string
 * starting with {@code [[} is the literal string with one bracket removed; anything else is itself.
 *
 * <p>The functions are the Resource Manager template functions Azure Policy allows plus the policy-only
 * ones ({@code field}, {@code current}, {@code policy}, {@code requestContext}, {@code addDays},
 * {@code ipRangeContains}). A function that fails raises {@link EvaluationException}; Azure treats a failed
 * evaluation as an implicit deny, and so does the engine.
 */
final class ArmExpressions {

    static final class EvaluationException extends RuntimeException {
        EvaluationException(String message) {
            super(message);
        }

        EvaluationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter UTC_NOW = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'");

    private ArmExpressions() {
    }

    /** Evaluates {@code raw} when it is an expression string; anything else is returned as is. */
    static Object evaluate(Object raw, EvaluationContext ctx) {
        if (!(raw instanceof String text)) {
            return raw;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("[[")) {
            return text.substring(text.indexOf('[') + 1);
        }
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return text;
        }
        Node node;
        try {
            node = new Parser(trimmed.substring(1, trimmed.length() - 1)).parse();
        } catch (EvaluationException e) {
            throw new EvaluationException("Unable to parse '" + text + "': " + e.getMessage(), e);
        }
        return node.eval(ctx);
    }

    /** Evaluates every string in {@code value}, descending into maps and lists. */
    @SuppressWarnings("unchecked")
    static Object evaluateDeep(Object value, EvaluationContext ctx) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), evaluateDeep(entry.getValue(), ctx));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                out.add(evaluateDeep(element, ctx));
            }
            return out;
        }
        return evaluate(value, ctx);
    }

    // ── Parser ──────────────────────────────────────────────────────────────────

    private enum Kind { IDENT, NUMBER, STRING, LPAREN, RPAREN, COMMA, DOT, LBRACKET, RBRACKET, EOF }

    private record Token(Kind kind, String text) {
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(String source) {
            this.tokens = tokenize(source);
        }

        Node parse() {
            Node node = expression();
            expect(Kind.EOF);
            return node;
        }

        private Node expression() {
            Node node = primary();
            while (true) {
                if (peek().kind() == Kind.DOT) {
                    pos++;
                    Token name = expect(Kind.IDENT);
                    node = new Member(node, name.text());
                } else if (peek().kind() == Kind.LBRACKET) {
                    pos++;
                    Node index = expression();
                    expect(Kind.RBRACKET);
                    node = new Index(node, index);
                } else {
                    return node;
                }
            }
        }

        private Node primary() {
            Token token = next();
            switch (token.kind()) {
                case NUMBER -> {
                    return new Literal(parseNumber(token.text()));
                }
                case STRING -> {
                    return new Literal(token.text());
                }
                case IDENT -> {
                    if (peek().kind() != Kind.LPAREN) {
                        return switch (token.text().toLowerCase(Locale.ROOT)) {
                            case "true" -> new Literal(Boolean.TRUE);
                            case "false" -> new Literal(Boolean.FALSE);
                            case "null" -> new Literal(null);
                            default -> throw new EvaluationException("Unexpected identifier '" + token.text() + "'");
                        };
                    }
                    pos++;
                    List<Node> args = new ArrayList<>();
                    if (peek().kind() != Kind.RPAREN) {
                        args.add(expression());
                        while (peek().kind() == Kind.COMMA) {
                            pos++;
                            args.add(expression());
                        }
                    }
                    expect(Kind.RPAREN);
                    return new Call(token.text(), args);
                }
                default -> throw new EvaluationException("Unexpected token '" + token.text() + "'");
            }
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            return tokens.get(pos++);
        }

        private Token expect(Kind kind) {
            Token token = next();
            if (token.kind() != kind) {
                throw new EvaluationException("Expected " + kind + " but found '" + token.text() + "'");
            }
            return token;
        }

        private static List<Token> tokenize(String source) {
            List<Token> out = new ArrayList<>();
            int i = 0;
            while (i < source.length()) {
                char c = source.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '(') {
                    out.add(new Token(Kind.LPAREN, "("));
                    i++;
                } else if (c == ')') {
                    out.add(new Token(Kind.RPAREN, ")"));
                    i++;
                } else if (c == ',') {
                    out.add(new Token(Kind.COMMA, ","));
                    i++;
                } else if (c == '.') {
                    out.add(new Token(Kind.DOT, "."));
                    i++;
                } else if (c == '[') {
                    out.add(new Token(Kind.LBRACKET, "["));
                    i++;
                } else if (c == ']') {
                    out.add(new Token(Kind.RBRACKET, "]"));
                    i++;
                } else if (c == '\'') {
                    StringBuilder sb = new StringBuilder();
                    i++;
                    while (true) {
                        if (i >= source.length()) {
                            throw new EvaluationException("Unterminated string literal");
                        }
                        char d = source.charAt(i);
                        if (d == '\'') {
                            if (i + 1 < source.length() && source.charAt(i + 1) == '\'') {
                                sb.append('\'');
                                i += 2;
                                continue;
                            }
                            i++;
                            break;
                        }
                        sb.append(d);
                        i++;
                    }
                    out.add(new Token(Kind.STRING, sb.toString()));
                } else if (Character.isDigit(c) || (c == '-' && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1)))) {
                    int start = i++;
                    while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '.')) {
                        i++;
                    }
                    out.add(new Token(Kind.NUMBER, source.substring(start, i)));
                } else if (Character.isLetter(c) || c == '_') {
                    int start = i++;
                    while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                        i++;
                    }
                    out.add(new Token(Kind.IDENT, source.substring(start, i)));
                } else {
                    throw new EvaluationException("Unexpected character '" + c + "'");
                }
            }
            out.add(new Token(Kind.EOF, "<end>"));
            return out;
        }
    }

    // ── Nodes ───────────────────────────────────────────────────────────────────

    private interface Node {
        Object eval(EvaluationContext ctx);
    }

    private record Literal(Object value) implements Node {
        @Override
        public Object eval(EvaluationContext ctx) {
            return value;
        }
    }

    private record Member(Node target, String name) implements Node {
        @Override
        public Object eval(EvaluationContext ctx) {
            Object value = target.eval(ctx);
            if (value instanceof Map<?, ?> map) {
                if (!ResourceFields.containsKey(map, name)) {
                    throw new EvaluationException("The property '" + name + "' does not exist");
                }
                return ResourceFields.get(map, name);
            }
            throw new EvaluationException("Cannot read property '" + name + "' of a non-object value");
        }
    }

    private record Index(Node target, Node index) implements Node {
        @Override
        public Object eval(EvaluationContext ctx) {
            Object value = target.eval(ctx);
            Object key = index.eval(ctx);
            if (value instanceof List<?> list && key instanceof Number n) {
                int i = n.intValue();
                if (i < 0 || i >= list.size()) {
                    throw new EvaluationException("Index " + i + " is out of range");
                }
                return list.get(i);
            }
            if (value instanceof Map<?, ?> map && key instanceof String s) {
                if (!ResourceFields.containsKey(map, s)) {
                    throw new EvaluationException("The property '" + s + "' does not exist");
                }
                return ResourceFields.get(map, s);
            }
            throw new EvaluationException("Cannot index a " + typeName(value) + " with a " + typeName(key));
        }
    }

    private record Call(String name, List<Node> args) implements Node {
        @Override
        public Object eval(EvaluationContext ctx) {
            String fn = name.toLowerCase(Locale.ROOT);
            // Lazy functions first: they must not evaluate the branch they do not pick.
            switch (fn) {
                case "if" -> {
                    arity(3);
                    return truthy(args.get(0).eval(ctx)) ? args.get(1).eval(ctx) : args.get(2).eval(ctx);
                }
                case "coalesce" -> {
                    for (Node arg : args) {
                        Object value = arg.eval(ctx);
                        if (value != null) {
                            return value;
                        }
                    }
                    return null;
                }
                case "and" -> {
                    for (Node arg : args) {
                        if (!truthy(arg.eval(ctx))) {
                            return false;
                        }
                    }
                    return true;
                }
                case "or" -> {
                    for (Node arg : args) {
                        if (truthy(arg.eval(ctx))) {
                            return true;
                        }
                    }
                    return false;
                }
                default -> {
                    // evaluated below
                }
            }
            List<Object> a = new ArrayList<>(args.size());
            for (Node arg : args) {
                a.add(arg.eval(ctx));
            }
            return Functions.call(fn, a, ctx, this);
        }

        void arity(int expected) {
            if (args.size() != expected) {
                throw new EvaluationException("The function '" + name + "' expects " + expected + " argument(s)");
            }
        }
    }

    // ── Functions ───────────────────────────────────────────────────────────────

    private static final class Functions {

        static Object call(String fn, List<Object> a, EvaluationContext ctx, Call call) {
            switch (fn) {
                // policy and scope
                case "parameters" -> {
                    return ctx.parameter(str(a, 0));
                }
                case "field" -> {
                    return ResourceFields.resolve(str(a, 0), ctx).asFunctionValue();
                }
                case "current" -> {
                    return a.isEmpty() ? ctx.current() : ctx.current(str(a, 0));
                }
                case "requestcontext" -> {
                    return ctx.requestContext;
                }
                case "policy" -> {
                    return ctx.policy;
                }
                case "resourcegroup" -> {
                    if (ctx.resourceGroup == null) {
                        throw new EvaluationException("resourceGroup() is not available at this scope");
                    }
                    return ctx.resourceGroup;
                }
                case "subscription" -> {
                    return ctx.subscription;
                }
                // logical
                case "not" -> {
                    return !truthy(a.get(0));
                }
                case "equals" -> {
                    return deepEquals(a.get(0), a.get(1), false);
                }
                case "less" -> {
                    return compare(a.get(0), a.get(1)) < 0;
                }
                case "lessorequals" -> {
                    return compare(a.get(0), a.get(1)) <= 0;
                }
                case "greater" -> {
                    return compare(a.get(0), a.get(1)) > 0;
                }
                case "greaterorequals" -> {
                    return compare(a.get(0), a.get(1)) >= 0;
                }
                case "true" -> {
                    return Boolean.TRUE;
                }
                case "false" -> {
                    return Boolean.FALSE;
                }
                case "null" -> {
                    return null;
                }
                case "bool" -> {
                    return truthy(a.get(0));
                }
                // numeric
                case "int" -> {
                    return toLong(a.get(0));
                }
                case "float" -> {
                    return toDouble(a.get(0));
                }
                case "add" -> {
                    return arith(a.get(0), a.get(1), '+');
                }
                case "sub" -> {
                    return arith(a.get(0), a.get(1), '-');
                }
                case "mul" -> {
                    return arith(a.get(0), a.get(1), '*');
                }
                case "div" -> {
                    return arith(a.get(0), a.get(1), '/');
                }
                case "mod" -> {
                    return arith(a.get(0), a.get(1), '%');
                }
                case "min", "max" -> {
                    List<Object> values = a.size() == 1 && a.get(0) instanceof List<?> l ? new ArrayList<>(l) : a;
                    Number best = null;
                    for (Object v : values) {
                        Number n = toNumber(v);
                        if (best == null || (fn.equals("min") ? compareNumbers(n, best) < 0 : compareNumbers(n, best) > 0)) {
                            best = n;
                        }
                    }
                    return best;
                }
                case "range" -> {
                    long start = toLong(a.get(0));
                    long count = toLong(a.get(1));
                    List<Object> out = new ArrayList<>();
                    for (long i = 0; i < count; i++) {
                        out.add(start + i);
                    }
                    return out;
                }
                // strings and collections
                case "string" -> {
                    return stringify(a.get(0));
                }
                case "concat" -> {
                    if (!a.isEmpty() && a.get(0) instanceof List<?>) {
                        List<Object> out = new ArrayList<>();
                        for (Object v : a) {
                            if (v instanceof List<?> l) {
                                out.addAll(l);
                            } else {
                                out.add(v);
                            }
                        }
                        return out;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (Object v : a) {
                        sb.append(stringify(v));
                    }
                    return sb.toString();
                }
                case "contains" -> {
                    Object container = a.get(0);
                    Object item = a.get(1);
                    if (container instanceof String s) {
                        return s.contains(stringify(item));
                    }
                    if (container instanceof List<?> l) {
                        for (Object v : l) {
                            if (deepEquals(v, item, false)) {
                                return true;
                            }
                        }
                        return false;
                    }
                    if (container instanceof Map<?, ?> m) {
                        return ResourceFields.containsKey(m, stringify(item));
                    }
                    return false;
                }
                case "empty" -> {
                    Object v = a.get(0);
                    return v == null || (v instanceof String s && s.isEmpty()) || (v instanceof List<?> l && l.isEmpty())
                        || (v instanceof Map<?, ?> m && m.isEmpty());
                }
                case "length" -> {
                    Object v = a.get(0);
                    if (v instanceof String s) {
                        return (long) s.length();
                    }
                    if (v instanceof List<?> l) {
                        return (long) l.size();
                    }
                    if (v instanceof Map<?, ?> m) {
                        return (long) m.size();
                    }
                    throw new EvaluationException("length() expects a string, array or object");
                }
                case "first" -> {
                    Object v = a.get(0);
                    if (v instanceof String s) {
                        return s.isEmpty() ? "" : s.substring(0, 1);
                    }
                    if (v instanceof List<?> l) {
                        return l.isEmpty() ? null : l.getFirst();
                    }
                    throw new EvaluationException("first() expects a string or array");
                }
                case "last" -> {
                    Object v = a.get(0);
                    if (v instanceof String s) {
                        return s.isEmpty() ? "" : s.substring(s.length() - 1);
                    }
                    if (v instanceof List<?> l) {
                        return l.isEmpty() ? null : l.getLast();
                    }
                    throw new EvaluationException("last() expects a string or array");
                }
                case "take" -> {
                    int n = (int) toLong(a.get(1));
                    Object v = a.get(0);
                    if (v instanceof String s) {
                        return s.substring(0, Math.max(0, Math.min(n, s.length())));
                    }
                    if (v instanceof List<?> l) {
                        return new ArrayList<>(l.subList(0, Math.max(0, Math.min(n, l.size()))));
                    }
                    throw new EvaluationException("take() expects a string or array");
                }
                case "skip" -> {
                    int n = (int) toLong(a.get(1));
                    Object v = a.get(0);
                    if (v instanceof String s) {
                        return s.substring(Math.max(0, Math.min(n, s.length())));
                    }
                    if (v instanceof List<?> l) {
                        return new ArrayList<>(l.subList(Math.max(0, Math.min(n, l.size())), l.size()));
                    }
                    throw new EvaluationException("skip() expects a string or array");
                }
                case "indexof" -> {
                    Object v = a.get(0);
                    if (v instanceof String s) {
                        return (long) s.toLowerCase(Locale.ROOT).indexOf(stringify(a.get(1)).toLowerCase(Locale.ROOT));
                    }
                    if (v instanceof List<?> l) {
                        for (int i = 0; i < l.size(); i++) {
                            if (deepEquals(l.get(i), a.get(1), false)) {
                                return (long) i;
                            }
                        }
                        return -1L;
                    }
                    throw new EvaluationException("indexOf() expects a string or array");
                }
                case "lastindexof" -> {
                    Object v = a.get(0);
                    if (v instanceof String s) {
                        return (long) s.toLowerCase(Locale.ROOT).lastIndexOf(stringify(a.get(1)).toLowerCase(Locale.ROOT));
                    }
                    if (v instanceof List<?> l) {
                        for (int i = l.size() - 1; i >= 0; i--) {
                            if (deepEquals(l.get(i), a.get(1), false)) {
                                return (long) i;
                            }
                        }
                        return -1L;
                    }
                    throw new EvaluationException("lastIndexOf() expects a string or array");
                }
                case "startswith" -> {
                    return str(a, 0).toLowerCase(Locale.ROOT).startsWith(str(a, 1).toLowerCase(Locale.ROOT));
                }
                case "endswith" -> {
                    return str(a, 0).toLowerCase(Locale.ROOT).endsWith(str(a, 1).toLowerCase(Locale.ROOT));
                }
                case "tolower" -> {
                    return str(a, 0).toLowerCase(Locale.ROOT);
                }
                case "toupper" -> {
                    return str(a, 0).toUpperCase(Locale.ROOT);
                }
                case "trim" -> {
                    return str(a, 0).trim();
                }
                case "split" -> {
                    String s = str(a, 0);
                    List<String> delimiters = new ArrayList<>();
                    if (a.get(1) instanceof List<?> l) {
                        for (Object d : l) {
                            delimiters.add(stringify(d));
                        }
                    } else {
                        delimiters.add(stringify(a.get(1)));
                    }
                    List<Object> out = new ArrayList<>();
                    StringBuilder current = new StringBuilder();
                    int i = 0;
                    outer:
                    while (i < s.length()) {
                        for (String d : delimiters) {
                            if (!d.isEmpty() && s.startsWith(d, i)) {
                                out.add(current.toString());
                                current.setLength(0);
                                i += d.length();
                                continue outer;
                            }
                        }
                        current.append(s.charAt(i++));
                    }
                    out.add(current.toString());
                    return out;
                }
                case "join" -> {
                    List<?> l = list(a.get(0), "join");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < l.size(); i++) {
                        if (i > 0) {
                            sb.append(stringify(a.get(1)));
                        }
                        sb.append(stringify(l.get(i)));
                    }
                    return sb.toString();
                }
                case "replace" -> {
                    return str(a, 0).replace(str(a, 1), str(a, 2));
                }
                case "substring" -> {
                    String s = str(a, 0);
                    int start = a.size() > 1 ? (int) toLong(a.get(1)) : 0;
                    int len = a.size() > 2 ? (int) toLong(a.get(2)) : s.length() - start;
                    if (start < 0 || len < 0 || start + len > s.length()) {
                        throw new EvaluationException("substring() range is out of bounds");
                    }
                    return s.substring(start, start + len);
                }
                case "padleft" -> {
                    String s = str(a, 0);
                    int width = (int) toLong(a.get(1));
                    String pad = a.size() > 2 ? str(a, 2) : " ";
                    if (pad.isEmpty()) {
                        throw new EvaluationException("padLeft() padding character must not be empty");
                    }
                    StringBuilder sb = new StringBuilder();
                    while (sb.length() + s.length() < width) {
                        sb.append(pad);
                    }
                    return sb.append(s).toString();
                }
                case "format" -> {
                    String fmt = str(a, 0);
                    for (int i = 1; i < a.size(); i++) {
                        fmt = fmt.replace("{" + (i - 1) + "}", stringify(a.get(i)));
                    }
                    return fmt;
                }
                case "json" -> {
                    try {
                        return MAPPER.readValue(str(a, 0), Object.class);
                    } catch (JsonProcessingException e) {
                        throw new EvaluationException("json() argument is not valid JSON", e);
                    }
                }
                case "base64" -> {
                    return Base64.getEncoder().encodeToString(str(a, 0).getBytes(StandardCharsets.UTF_8));
                }
                case "base64tostring" -> {
                    return new String(Base64.getDecoder().decode(str(a, 0)), StandardCharsets.UTF_8);
                }
                case "uniquestring" -> {
                    return uniqueString(a);
                }
                case "array" -> {
                    Object v = a.get(0);
                    if (v instanceof List<?> l) {
                        return new ArrayList<>(l);
                    }
                    List<Object> single = new ArrayList<>();
                    single.add(v);
                    return single;
                }
                case "createarray" -> {
                    return new ArrayList<>(a);
                }
                case "createobject" -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    for (int i = 0; i + 1 < a.size(); i += 2) {
                        out.put(stringify(a.get(i)), a.get(i + 1));
                    }
                    return out;
                }
                case "union" -> {
                    if (!a.isEmpty() && a.get(0) instanceof Map<?, ?>) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        for (Object v : a) {
                            if (v instanceof Map<?, ?> m) {
                                m.forEach((k, val) -> out.put(String.valueOf(k), val));
                            }
                        }
                        return out;
                    }
                    Set<Object> out = new LinkedHashSet<>();
                    for (Object v : a) {
                        out.addAll(list(v, "union"));
                    }
                    return new ArrayList<>(out);
                }
                case "intersection" -> {
                    if (!a.isEmpty() && a.get(0) instanceof Map<?, ?> first) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        first.forEach((k, val) -> {
                            boolean everywhere = true;
                            for (int i = 1; i < a.size(); i++) {
                                if (!(a.get(i) instanceof Map<?, ?> m) || !deepEquals(ResourceFields.get(m, String.valueOf(k)), val, false)) {
                                    everywhere = false;
                                }
                            }
                            if (everywhere) {
                                out.put(String.valueOf(k), val);
                            }
                        });
                        return out;
                    }
                    List<Object> out = new ArrayList<>();
                    for (Object candidate : list(a.get(0), "intersection")) {
                        boolean everywhere = true;
                        for (int i = 1; i < a.size(); i++) {
                            boolean found = false;
                            for (Object v : list(a.get(i), "intersection")) {
                                if (deepEquals(v, candidate, false)) {
                                    found = true;
                                    break;
                                }
                            }
                            everywhere &= found;
                        }
                        if (everywhere && !out.contains(candidate)) {
                            out.add(candidate);
                        }
                    }
                    return out;
                }
                case "items" -> {
                    List<Object> out = new ArrayList<>();
                    if (a.get(0) instanceof Map<?, ?> m) {
                        m.forEach((k, val) -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("key", String.valueOf(k));
                            item.put("value", val);
                            out.add(item);
                        });
                    }
                    return out;
                }
                case "objectkeys" -> {
                    List<Object> out = new ArrayList<>();
                    if (a.get(0) instanceof Map<?, ?> m) {
                        m.keySet().forEach(k -> out.add(String.valueOf(k)));
                    }
                    return out;
                }
                case "tryget" -> {
                    Object container = a.get(0);
                    Object key = a.get(1);
                    if (container instanceof Map<?, ?> m && key instanceof String s) {
                        return ResourceFields.get(m, s);
                    }
                    if (container instanceof List<?> l && key instanceof Number n) {
                        int i = n.intValue();
                        return i >= 0 && i < l.size() ? l.get(i) : null;
                    }
                    return null;
                }
                // dates
                case "utcnow" -> {
                    return UTC_NOW.format(OffsetDateTime.now(ZoneOffset.UTC));
                }
                case "adddays" -> {
                    return UTC_NOW.format(parseDateTime(str(a, 0)).plusDays(toLong(a.get(1))));
                }
                case "datetimeadd" -> {
                    OffsetDateTime base = parseDateTime(str(a, 0));
                    String duration = str(a, 1).toUpperCase(Locale.ROOT);
                    OffsetDateTime result = duration.matches("P\\d+[YMW](\\d+[MWD])*")
                        ? base.plus(Period.parse(duration))
                        : base.plus(Duration.parse(duration));
                    return UTC_NOW.format(result);
                }
                case "iprangecontains" -> {
                    return ipRangeContains(str(a, 0), str(a, 1));
                }
                default -> throw new EvaluationException("The function '" + call.name() + "' is not supported");
            }
        }

        private static String str(List<Object> a, int i) {
            if (i >= a.size()) {
                throw new EvaluationException("Missing argument " + (i + 1));
            }
            Object v = a.get(i);
            if (v == null) {
                throw new EvaluationException("Argument " + (i + 1) + " is null where a string was expected");
            }
            return v instanceof String s ? s : stringify(v);
        }

        private static List<?> list(Object v, String fn) {
            if (v instanceof List<?> l) {
                return l;
            }
            throw new EvaluationException(fn + "() expects an array");
        }
    }

    // ── Value helpers ───────────────────────────────────────────────────────────

    static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            if (s.equalsIgnoreCase("true")) {
                return true;
            }
            if (s.equalsIgnoreCase("false")) {
                return false;
            }
        }
        if (value instanceof Number n) {
            return n.longValue() != 0;
        }
        throw new EvaluationException("Expected a boolean but found " + typeName(value));
    }

    static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(normalizeNumber(value));
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new EvaluationException("Cannot convert value to a string", e);
        }
    }

    static Object normalizeNumber(Object value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger bi) {
            return bi.longValue();
        }
        if (value instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().scale() <= 0 ? bd.longValue() : bd.doubleValue();
        }
        if (value instanceof Float f) {
            return f.doubleValue();
        }
        return value;
    }

    static Number toNumber(Object value) {
        if (value instanceof Number n) {
            return (Number) normalizeNumber(n);
        }
        if (value instanceof String s) {
            try {
                return (Number) normalizeNumber(parseNumber(s.trim()));
            } catch (NumberFormatException e) {
                throw new EvaluationException("'" + s + "' is not a number");
            }
        }
        if (value instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        throw new EvaluationException("Expected a number but found " + typeName(value));
    }

    static Object parseNumber(String text) {
        if (text.contains(".") || text.contains("e") || text.contains("E")) {
            return Double.parseDouble(text);
        }
        return Long.parseLong(text);
    }

    static long toLong(Object value) {
        return toNumber(value).longValue();
    }

    static double toDouble(Object value) {
        return toNumber(value).doubleValue();
    }

    private static Object arith(Object x, Object y, char op) {
        Number a = toNumber(x);
        Number b = toNumber(y);
        if (a instanceof Long la && b instanceof Long lb) {
            return switch (op) {
                case '+' -> la + lb;
                case '-' -> la - lb;
                case '*' -> la * lb;
                case '/' -> {
                    if (lb == 0) {
                        throw new EvaluationException("Division by zero");
                    }
                    yield la / lb;
                }
                default -> {
                    if (lb == 0) {
                        throw new EvaluationException("Division by zero");
                    }
                    yield la % lb;
                }
            };
        }
        double da = a.doubleValue();
        double db = b.doubleValue();
        return switch (op) {
            case '+' -> da + db;
            case '-' -> da - db;
            case '*' -> da * db;
            case '/' -> da / db;
            default -> da % db;
        };
    }

    static int compareNumbers(Number a, Number b) {
        if (a instanceof Long la && b instanceof Long lb) {
            return Long.compare(la, lb);
        }
        return Double.compare(a.doubleValue(), b.doubleValue());
    }

    /** Ordering for the comparison functions: numbers numerically, strings ordinally. */
    static int compare(Object x, Object y) {
        if (x instanceof Number || y instanceof Number) {
            return compareNumbers(toNumber(x), toNumber(y));
        }
        if (x instanceof String a && y instanceof String b) {
            return a.compareTo(b);
        }
        throw new EvaluationException("Cannot compare " + typeName(x) + " with " + typeName(y));
    }

    /** Structural equality; strings compare case-insensitively when {@code ignoreCase} is set. */
    static boolean deepEquals(Object x, Object y, boolean ignoreCase) {
        if (x == null || y == null) {
            return x == null && y == null;
        }
        if (x instanceof Number || y instanceof Number) {
            if (x instanceof Number && y instanceof Number) {
                return compareNumbers(toNumber(x), toNumber(y)) == 0;
            }
            if (ignoreCase) {
                return stringify(x).equalsIgnoreCase(stringify(y));
            }
            return false;
        }
        if (x instanceof Boolean || y instanceof Boolean) {
            if (x instanceof Boolean && y instanceof Boolean) {
                return x.equals(y);
            }
            return ignoreCase && stringify(x).equalsIgnoreCase(stringify(y));
        }
        if (x instanceof String a && y instanceof String b) {
            return ignoreCase ? a.equalsIgnoreCase(b) : a.equals(b);
        }
        if (x instanceof List<?> a && y instanceof List<?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!deepEquals(a.get(i), b.get(i), ignoreCase)) {
                    return false;
                }
            }
            return true;
        }
        if (x instanceof Map<?, ?> a && y instanceof Map<?, ?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : a.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!ResourceFields.containsKey(b, key) || !deepEquals(entry.getValue(), ResourceFields.get(b, key), ignoreCase)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        return value.getClass().getSimpleName();
    }

    static OffsetDateTime parseDateTime(String text) {
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(text).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                throw new EvaluationException("'" + text + "' is not an ISO 8601 date-time");
            }
        }
    }

    private static String uniqueString(List<Object> parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder joined = new StringBuilder();
            for (Object part : parts) {
                joined.append(stringify(part)).append('-');
            }
            byte[] hash = digest.digest(joined.toString().getBytes(StandardCharsets.UTF_8));
            String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 13; i++) {
                out.append(alphabet.charAt((hash[i] & 0xff) % alphabet.length()));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new EvaluationException("SHA-256 is unavailable", e);
        }
    }

    /** Whether the {@code range} (an address, a CIDR or a start-end pair) contains every address of {@code target}. */
    static boolean ipRangeContains(String range, String target) {
        BigInteger[] outer = bounds(range);
        BigInteger[] inner = bounds(target);
        if (outer[2].compareTo(inner[2]) != 0) {
            throw new EvaluationException("ipRangeContains() cannot mix IPv4 and IPv6");
        }
        return outer[0].compareTo(inner[0]) <= 0 && outer[1].compareTo(inner[1]) >= 0;
    }

    /** Lowest address, highest address and address family (4 or 6) of a range expression. */
    private static BigInteger[] bounds(String text) {
        String value = text.trim();
        if (value.isEmpty()) {
            throw new EvaluationException("ipRangeContains() does not accept an empty range");
        }
        try {
            if (value.contains("-")) {
                String[] parts = value.split("-", 2);
                byte[] start = InetAddress.getByName(parts[0].trim()).getAddress();
                byte[] end = InetAddress.getByName(parts[1].trim()).getAddress();
                if (start.length != end.length) {
                    throw new EvaluationException("ipRangeContains() range mixes address families");
                }
                return new BigInteger[] {new BigInteger(1, start), new BigInteger(1, end), BigInteger.valueOf(start.length == 4 ? 4 : 6)};
            }
            String address = value;
            int prefix = -1;
            if (value.contains("/")) {
                String[] parts = value.split("/", 2);
                address = parts[0];
                prefix = Integer.parseInt(parts[1]);
            }
            byte[] bytes = InetAddress.getByName(address).getAddress();
            int bits = bytes.length * 8;
            if (prefix < 0) {
                prefix = bits;
            }
            BigInteger base = new BigInteger(1, bytes);
            BigInteger mask = BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE);
            BigInteger low = base.andNot(mask);
            return new BigInteger[] {low, low.add(mask), BigInteger.valueOf(bytes.length == 4 ? 4 : 6)};
        } catch (UnknownHostException | NumberFormatException e) {
            throw new EvaluationException("'" + text + "' is not an IP address or range", e);
        }
    }
}
