package io.floci.az.services.table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Compiles an OData $filter string into a {@link Predicate} over entity maps.
 * Package-private — used only within the table service.
 */
class ODataFilter {

    // -------------------------------------------------------------------------
    // Token type enum
    // -------------------------------------------------------------------------

    enum TT {
        LPAREN, RPAREN, COMMA,
        AND, OR, NOT,
        EQ, NE, GT, GE, LT, LE,
        IDENT, STR, INT, DBL, BOOL, NIL, DT, GUID,
        EOF
    }

    // -------------------------------------------------------------------------
    // Token record
    // -------------------------------------------------------------------------

    record Tok(TT t, Object v) {}

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    static Predicate<Map<String, Object>> compile(String filter) {
        if (filter == null || filter.isBlank()) {
            return e -> true;
        }
        try {
            List<Tok> toks = tokenize(filter);
            int[] pos = {0};
            Predicate<Map<String, Object>> pred = parseOr(toks, pos);
            return pred;
        } catch (Exception ex) {
            return e -> true;
        }
    }

    // -------------------------------------------------------------------------
    // Tokenizer
    // -------------------------------------------------------------------------

    static List<Tok> tokenize(String s) {
        List<Tok> toks = new ArrayList<>();
        int i = 0;
        int len = s.length();

        while (i < len) {
            char c = s.charAt(i);

            // Skip whitespace
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            // String literal: 'value' with '' as escaped single quote
            if (c == '\'') {
                i++; // consume opening quote
                StringBuilder sb = new StringBuilder();
                while (i < len) {
                    char ch = s.charAt(i);
                    if (ch == '\'') {
                        if (i + 1 < len && s.charAt(i + 1) == '\'') {
                            sb.append('\'');
                            i += 2;
                        } else {
                            i++; // consume closing quote
                            break;
                        }
                    } else {
                        sb.append(ch);
                        i++;
                    }
                }
                toks.add(new Tok(TT.STR, sb.toString()));
                continue;
            }

            // Number: digit or minus followed by digit
            if (Character.isDigit(c) || (c == '-' && i + 1 < len && Character.isDigit(s.charAt(i + 1)))) {
                StringBuilder sb = new StringBuilder();
                if (c == '-') {
                    sb.append(c);
                    i++;
                }
                boolean hasDot = false;
                while (i < len && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    if (s.charAt(i) == '.') {
                        hasDot = true;
                    }
                    sb.append(s.charAt(i));
                    i++;
                }
                String num = sb.toString();
                if (hasDot) {
                    toks.add(new Tok(TT.DBL, Double.parseDouble(num)));
                } else {
                    toks.add(new Tok(TT.INT, Long.parseLong(num)));
                }
                continue;
            }

            // Identifier / keyword / typed literal
            if (Character.isLetter(c) || c == '_') {
                StringBuilder sb = new StringBuilder();
                while (i < len && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) {
                    sb.append(s.charAt(i));
                    i++;
                }
                String word = sb.toString();

                // Check for typed literals: datetime'...' and guid'...'
                if (word.equalsIgnoreCase("datetime") && i < len && s.charAt(i) == '\'') {
                    i++; // consume opening quote
                    StringBuilder val = new StringBuilder();
                    while (i < len && s.charAt(i) != '\'') {
                        val.append(s.charAt(i));
                        i++;
                    }
                    if (i < len) i++; // consume closing quote
                    String dtStr = val.toString();
                    try {
                        Instant instant = Instant.parse(dtStr);
                        toks.add(new Tok(TT.DT, instant));
                    } catch (Exception ex) {
                        toks.add(new Tok(TT.STR, dtStr));
                    }
                    continue;
                }

                if (word.equalsIgnoreCase("guid") && i < len && s.charAt(i) == '\'') {
                    i++; // consume opening quote
                    StringBuilder val = new StringBuilder();
                    while (i < len && s.charAt(i) != '\'') {
                        val.append(s.charAt(i));
                        i++;
                    }
                    if (i < len) i++; // consume closing quote
                    toks.add(new Tok(TT.GUID, val.toString()));
                    continue;
                }

                // Keywords (case-insensitive)
                switch (word.toLowerCase()) {
                    case "and"   -> toks.add(new Tok(TT.AND,  null));
                    case "or"    -> toks.add(new Tok(TT.OR,   null));
                    case "not"   -> toks.add(new Tok(TT.NOT,  null));
                    case "eq"    -> toks.add(new Tok(TT.EQ,   null));
                    case "ne"    -> toks.add(new Tok(TT.NE,   null));
                    case "gt"    -> toks.add(new Tok(TT.GT,   null));
                    case "ge"    -> toks.add(new Tok(TT.GE,   null));
                    case "lt"    -> toks.add(new Tok(TT.LT,   null));
                    case "le"    -> toks.add(new Tok(TT.LE,   null));
                    case "true"  -> toks.add(new Tok(TT.BOOL, Boolean.TRUE));
                    case "false" -> toks.add(new Tok(TT.BOOL, Boolean.FALSE));
                    case "null"  -> toks.add(new Tok(TT.NIL,  null));
                    default      -> toks.add(new Tok(TT.IDENT, word));
                }
                continue;
            }

            // Single-character tokens
            switch (c) {
                case '(' -> toks.add(new Tok(TT.LPAREN, null));
                case ')' -> toks.add(new Tok(TT.RPAREN, null));
                case ',' -> toks.add(new Tok(TT.COMMA,  null));
                default  -> { /* skip unknown */ }
            }
            i++;
        }

        toks.add(new Tok(TT.EOF, null));
        return toks;
    }

    // -------------------------------------------------------------------------
    // Parser — recursive descent
    // -------------------------------------------------------------------------

    private static Predicate<Map<String, Object>> parseOr(List<Tok> toks, int[] p) {
        Predicate<Map<String, Object>> left = parseAnd(toks, p);
        while (peek(toks, p) == TT.OR) {
            p[0]++; // consume OR
            Predicate<Map<String, Object>> right = parseAnd(toks, p);
            left = left.or(right);
        }
        return left;
    }

    private static Predicate<Map<String, Object>> parseAnd(List<Tok> toks, int[] p) {
        Predicate<Map<String, Object>> left = parseNot(toks, p);
        while (peek(toks, p) == TT.AND) {
            p[0]++; // consume AND
            Predicate<Map<String, Object>> right = parseNot(toks, p);
            left = left.and(right);
        }
        return left;
    }

    private static Predicate<Map<String, Object>> parseNot(List<Tok> toks, int[] p) {
        if (peek(toks, p) == TT.NOT) {
            p[0]++; // consume NOT
            return parseNot(toks, p).negate();
        }
        return parsePrimary(toks, p);
    }

    private static Predicate<Map<String, Object>> parsePrimary(List<Tok> toks, int[] p) {
        TT cur = peek(toks, p);

        // Grouped expression: ( expr )
        if (cur == TT.LPAREN) {
            p[0]++; // consume (
            Predicate<Map<String, Object>> inner = parseOr(toks, p);
            if (peek(toks, p) == TT.RPAREN) {
                p[0]++; // consume )
            }
            return inner;
        }

        // Function call: IDENT(args...)
        if (cur == TT.IDENT && p[0] + 1 < toks.size() && toks.get(p[0] + 1).t() == TT.LPAREN) {
            String fname = (String) toks.get(p[0]).v();
            p[0] += 2; // consume name + (

            List<Tok> args = new ArrayList<>();
            while (peek(toks, p) != TT.RPAREN && peek(toks, p) != TT.EOF) {
                if (peek(toks, p) == TT.COMMA) {
                    p[0]++; // skip comma
                } else {
                    args.add(toks.get(p[0]++));
                }
            }
            if (peek(toks, p) == TT.RPAREN) {
                p[0]++; // consume )
            }
            return buildFuncPredicate(fname.toLowerCase(), args);
        }

        // Comparison: lhs op rhs
        if (p[0] < toks.size()) {
            Tok lhsTok = toks.get(p[0]++);
            TT op = peek(toks, p);
            if (op == TT.EQ || op == TT.NE || op == TT.GT || op == TT.GE || op == TT.LT || op == TT.LE) {
                p[0]++; // consume op
                Tok rhsTok = toks.get(p[0]++);
                return entity -> {
                    Object lhs = resolveArg(lhsTok, entity);
                    Object rhs = resolveArg(rhsTok, entity);
                    return compare(lhs, rhs, op);
                };
            }
        }

        return e -> true;
    }

    // -------------------------------------------------------------------------
    // Function predicates
    // -------------------------------------------------------------------------

    private static Predicate<Map<String, Object>> buildFuncPredicate(String func, List<Tok> args) {
        if (args == null || args.size() < 2) {
            return e -> false;
        }
        return switch (func) {
            case "startswith" -> {
                Tok propTok = args.get(0);
                Tok litTok  = args.get(1);
                yield entity -> {
                    Object propVal = resolveArg(propTok, entity);
                    Object literal = resolveArg(litTok, entity);
                    if (propVal == null || literal == null) return false;
                    return propVal.toString().startsWith(literal.toString());
                };
            }
            case "endswith" -> {
                Tok propTok = args.get(0);
                Tok litTok  = args.get(1);
                yield entity -> {
                    Object propVal = resolveArg(propTok, entity);
                    Object literal = resolveArg(litTok, entity);
                    if (propVal == null || literal == null) return false;
                    return propVal.toString().endsWith(literal.toString());
                };
            }
            case "substringof" -> {
                // substringof(literal, prop) — reversed argument order
                Tok litTok  = args.get(0);
                Tok propTok = args.get(1);
                yield entity -> {
                    Object literal = resolveArg(litTok, entity);
                    Object propVal = resolveArg(propTok, entity);
                    if (propVal == null || literal == null) return false;
                    return propVal.toString().contains(literal.toString());
                };
            }
            default -> e -> true;
        };
    }

    // -------------------------------------------------------------------------
    // Argument resolution
    // -------------------------------------------------------------------------

    private static Object resolveArg(Tok t, Map<String, Object> entity) {
        return switch (t.t()) {
            case IDENT -> resolveProperty(entity, (String) t.v());
            case STR, INT, DBL, BOOL -> t.v();
            case NIL  -> null;
            case DT   -> t.v(); // already an Instant
            case GUID -> t.v(); // UUID string
            default   -> null;
        };
    }

    private static Object resolveProperty(Map<String, Object> entity, String name) {
        Object val = entity.get(name);
        if (val == null) {
            return null;
        }
        Object annotation = entity.get(name + "@odata.type");
        if (annotation == null) {
            return val;
        }
        String type = annotation.toString();
        try {
            return switch (type) {
                case "Edm.Int64", "Edm.Int32" -> Long.parseLong(val.toString());
                case "Edm.Double"              -> Double.parseDouble(val.toString());
                case "Edm.Boolean"             -> Boolean.parseBoolean(val.toString());
                case "Edm.DateTime"            -> Instant.parse(val.toString());
                case "Edm.Guid"                -> val.toString();
                default                        -> val;
            };
        } catch (Exception ex) {
            return val;
        }
    }

    // -------------------------------------------------------------------------
    // Comparison helpers
    // -------------------------------------------------------------------------

    private static boolean compare(Object lhs, Object rhs, TT op) {
        if (lhs == null && rhs == null) {
            return op == TT.EQ;
        }
        if (lhs == null || rhs == null) {
            return op == TT.NE;
        }
        if (lhs instanceof Number l && rhs instanceof Number r) {
            int cmp = Double.compare(l.doubleValue(), r.doubleValue());
            return evalOp(cmp, op);
        }
        if (lhs instanceof Instant li && rhs instanceof Instant ri) {
            int cmp = li.compareTo(ri);
            return evalOp(cmp, op);
        }
        int cmp = lhs.toString().compareTo(rhs.toString());
        return evalOp(cmp, op);
    }

    private static boolean evalOp(int cmp, TT op) {
        return switch (op) {
            case EQ -> cmp == 0;
            case NE -> cmp != 0;
            case GT -> cmp >  0;
            case GE -> cmp >= 0;
            case LT -> cmp <  0;
            case LE -> cmp <= 0;
            default -> false;
        };
    }

    // -------------------------------------------------------------------------
    // Peek helper
    // -------------------------------------------------------------------------

    private static TT peek(List<Tok> toks, int[] p) {
        if (p[0] >= toks.size()) {
            return TT.EOF;
        }
        return toks.get(p[0]).t();
    }
}
