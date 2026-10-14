package io.floci.az.services.resourcegraph.kql;

import io.floci.az.services.resourcegraph.kql.Kql.Expr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** The scalar functions of the KQL subset; a conversion that fails answers null, as Kusto does. */
final class KqlFunctions {

    private KqlFunctions() {
    }

    /** Every scalar function the engine evaluates, checked before a query runs. */
    static final Set<String> NAMES = Set.of("abs", "ago", "array_concat", "array_length", "bag_keys", "bag_pack", "case",
        "ceiling", "coalesce", "extract", "floor", "format_datetime", "gettype", "iff", "iif", "indexof", "isempty",
        "isnotempty", "isnotnull", "isnull", "not", "now", "pack", "pack_array", "pack_dictionary", "parse_json",
        "replace_regex", "replace_string", "round", "split", "startofday", "strcat", "strcat_delim", "strcmp", "strlen",
        "substring", "tobool", "toboolean", "todatetime", "todecimal", "todouble", "todynamic", "toint", "tolong",
        "tolower", "toobject", "toreal", "tostring", "totimespan", "toupper", "trim", "trim_end", "trim_start", "typeof");

    static boolean isKnown(String name) {
        return NAMES.contains(name);
    }

    static Object call(String name, List<Expr> args, Map<String, Object> row) {
        switch (name) {
            case "iff", "iif" -> {
                arity(name, args, 3);
                Boolean condition = KqlValues.toBool(KqlEngine.eval(args.get(0), row));
                return KqlEngine.eval(Boolean.TRUE.equals(condition) ? args.get(1) : args.get(2), row);
            }
            case "case" -> {
                if (args.size() < 3 || args.size() % 2 == 0) {
                    throw KqlException.semantic("case() takes predicate/value pairs and a default");
                }
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    if (Boolean.TRUE.equals(KqlValues.toBool(KqlEngine.eval(args.get(i), row)))) {
                        return KqlEngine.eval(args.get(i + 1), row);
                    }
                }
                return KqlEngine.eval(args.getLast(), row);
            }
            case "coalesce" -> {
                for (Expr arg : args) {
                    Object value = KqlEngine.eval(arg, row);
                    if (!KqlValues.isEmpty(value)) {
                        return value;
                    }
                }
                return null;
            }
            default -> {
                // evaluated below
            }
        }
        List<Object> a = new ArrayList<>(args.size());
        for (Expr arg : args) {
            a.add(KqlEngine.eval(arg, row));
        }
        switch (name) {
            case "tolower" -> {
                return a.get(0) == null ? null : KqlValues.toString(a.get(0)).toLowerCase(Locale.ROOT);
            }
            case "toupper" -> {
                return a.get(0) == null ? null : KqlValues.toString(a.get(0)).toUpperCase(Locale.ROOT);
            }
            case "tostring" -> {
                return KqlValues.toString(a.get(0));
            }
            case "tolong" -> {
                return KqlValues.toLong(a.get(0));
            }
            case "toint" -> {
                Long l = KqlValues.toLong(a.get(0));
                return l == null || l < Integer.MIN_VALUE || l > Integer.MAX_VALUE ? null : l;
            }
            case "todouble", "toreal", "todecimal" -> {
                return KqlValues.toDouble(a.get(0));
            }
            case "tobool", "toboolean" -> {
                return KqlValues.toBool(a.get(0));
            }
            case "todatetime" -> {
                return KqlValues.toDateTime(a.get(0));
            }
            case "totimespan" -> {
                return KqlValues.toTimespan(a.get(0));
            }
            case "todynamic", "parse_json", "toobject" -> {
                return KqlValues.toDynamic(a.get(0));
            }
            case "isnull" -> {
                return a.get(0) == null;
            }
            case "isnotnull" -> {
                return a.get(0) != null;
            }
            case "isempty" -> {
                return KqlValues.isEmpty(a.get(0));
            }
            case "isnotempty" -> {
                return !KqlValues.isEmpty(a.get(0));
            }
            case "now" -> {
                Instant now = Instant.now();
                return a.isEmpty() || a.get(0) == null ? now : now.plus(KqlValues.toTimespan(a.get(0)));
            }
            case "ago" -> {
                Duration span = KqlValues.toTimespan(a.get(0));
                return span == null ? null : Instant.now().minus(span);
            }
            case "strcat" -> {
                StringBuilder sb = new StringBuilder();
                for (Object v : a) {
                    sb.append(KqlValues.toString(v));
                }
                return sb.toString();
            }
            case "strcat_delim" -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < a.size(); i++) {
                    if (i > 1) {
                        sb.append(KqlValues.toString(a.get(0)));
                    }
                    sb.append(KqlValues.toString(a.get(i)));
                }
                return sb.toString();
            }
            case "strlen" -> {
                return (long) KqlValues.toString(a.get(0)).length();
            }
            case "substring" -> {
                String s = KqlValues.toString(a.get(0));
                int start = (int) Math.max(0, Math.min(s.length(), lng(a, 1)));
                int length = a.size() > 2 ? (int) Math.max(0, Math.min(s.length() - start, lng(a, 2))) : s.length() - start;
                return s.substring(start, start + length);
            }
            case "split" -> {
                String s = KqlValues.toString(a.get(0));
                String delimiter = KqlValues.toString(a.get(1));
                List<Object> parts = new ArrayList<>();
                if (delimiter.isEmpty()) {
                    parts.add(s);
                } else {
                    int from = 0;
                    int at;
                    while ((at = s.indexOf(delimiter, from)) >= 0) {
                        parts.add(s.substring(from, at));
                        from = at + delimiter.length();
                    }
                    parts.add(s.substring(from));
                }
                if (a.size() > 2) {
                    int index = (int) lng(a, 2);
                    List<Object> single = new ArrayList<>();
                    if (index >= 0 && index < parts.size()) {
                        single.add(parts.get(index));
                    }
                    return single;
                }
                return parts;
            }
            case "trim" -> {
                return KqlValues.toString(a.get(1)).replaceAll("^(" + KqlValues.toString(a.get(0)) + ")+|(" + KqlValues.toString(a.get(0)) + ")+$", "");
            }
            case "trim_start" -> {
                return KqlValues.toString(a.get(1)).replaceAll("^(" + KqlValues.toString(a.get(0)) + ")+", "");
            }
            case "trim_end" -> {
                return KqlValues.toString(a.get(1)).replaceAll("(" + KqlValues.toString(a.get(0)) + ")+$", "");
            }
            case "replace_string" -> {
                return KqlValues.toString(a.get(0)).replace(KqlValues.toString(a.get(1)), KqlValues.toString(a.get(2)));
            }
            case "replace_regex" -> {
                try {
                    return KqlValues.toString(a.get(0)).replaceAll(KqlValues.toString(a.get(1)), KqlValues.toString(a.get(2)));
                } catch (PatternSyntaxException e) {
                    throw KqlException.semantic("Invalid regular expression: " + e.getDescription());
                }
            }
            case "indexof" -> {
                String source = KqlValues.toString(a.get(0));
                Long start = a.size() > 2 ? KqlValues.toLong(a.get(2)) : null;
                int from = start == null ? 0 : start < 0 ? Math.max(0, source.length() + start.intValue()) : start.intValue();
                return from > source.length() ? -1L : (long) source.indexOf(KqlValues.toString(a.get(1)), from);
            }
            case "strcmp" -> {
                return (long) Integer.signum(KqlValues.toString(a.get(0)).compareTo(KqlValues.toString(a.get(1))));
            }
            case "extract" -> {
                try {
                    Matcher m = Pattern.compile(KqlValues.toString(a.get(0))).matcher(KqlValues.toString(a.get(2)));
                    int group = (int) lng(a, 1);
                    return m.find() && group <= m.groupCount() ? m.group(group) : null;
                } catch (PatternSyntaxException e) {
                    throw KqlException.semantic("Invalid regular expression: " + e.getDescription());
                }
            }
            case "array_length" -> {
                return a.get(0) instanceof List<?> list ? (Object) (long) list.size() : null;
            }
            case "array_concat" -> {
                List<Object> out = new ArrayList<>();
                for (Object v : a) {
                    if (v instanceof List<?> list) {
                        out.addAll(list);
                    }
                }
                return out;
            }
            case "bag_keys" -> {
                if (!(a.get(0) instanceof Map<?, ?> map)) {
                    return null;
                }
                List<Object> keys = new ArrayList<>();
                map.keySet().forEach(k -> keys.add(String.valueOf(k)));
                return keys;
            }
            case "pack", "bag_pack", "pack_dictionary" -> {
                Map<String, Object> bag = new LinkedHashMap<>();
                for (int i = 0; i + 1 < a.size(); i += 2) {
                    bag.put(KqlValues.toString(a.get(i)), a.get(i + 1));
                }
                return bag;
            }
            case "pack_array" -> {
                return new ArrayList<>(a);
            }
            case "typeof", "gettype" -> {
                return KqlValues.typeName(a.get(0));
            }
            case "not" -> {
                Boolean b = KqlValues.toBool(a.get(0));
                return b == null ? null : !b;
            }
            case "abs" -> {
                Object v = a.get(0);
                return v instanceof Long l ? Math.abs(l) : v instanceof Double d ? Math.abs(d) : null;
            }
            case "round", "floor", "ceiling" -> {
                Double d = KqlValues.toDouble(a.get(0));
                if (d == null) {
                    return null;
                }
                return switch (name) {
                    case "floor" -> Math.floor(d);
                    case "ceiling" -> Math.ceil(d);
                    default -> {
                        Long digits = a.size() > 1 ? KqlValues.toLong(a.get(1)) : null;
                        if (digits == null || digits == 0) {
                            yield (double) Math.round(d);
                        }
                        yield new BigDecimal(d).setScale(digits.intValue(), RoundingMode.HALF_UP).doubleValue();
                    }
                };
            }
            case "startofday" -> {
                Instant instant = KqlValues.toDateTime(a.get(0));
                Long offset = a.size() > 1 ? KqlValues.toLong(a.get(1)) : null;
                return instant == null ? null
                    : instant.truncatedTo(ChronoUnit.DAYS).plus(offset == null ? 0 : offset, ChronoUnit.DAYS);
            }
            case "format_datetime" -> {
                Instant instant = KqlValues.toDateTime(a.get(0));
                return instant == null ? null : instant.toString();
            }
            default -> throw KqlException.semantic("Unknown function '" + name + "'");
        }
    }

    private static long lng(List<Object> a, int index) {
        if (index >= a.size()) {
            throw KqlException.semantic("Missing argument " + (index + 1));
        }
        Long value = KqlValues.toLong(a.get(index));
        if (value == null) {
            throw KqlException.semantic("Argument " + (index + 1) + " must be a number");
        }
        return value;
    }

    private static void arity(String name, List<Expr> args, int expected) {
        if (args.size() != expected) {
            throw KqlException.semantic(name + "() expects " + expected + " arguments");
        }
    }
}
