package io.floci.az.services.cosmos;

import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * In-process query engine for the Cosmos DB SQL dialect.
 *
 * Supported:
 *   SELECT * / SELECT c.field1, c.field2 / SELECT VALUE c.field
 *   SELECT TOP n / SELECT VALUE COUNT(1)
 *   WHERE with =, !=, <>, >, >=, <, <=, IN, BETWEEN, NOT, AND, OR, parentheses
 *   WHERE functions: IS_DEFINED, IS_NULL, IS_STRING, IS_NUMBER, IS_BOOL, IS_ARRAY, IS_OBJECT
 *                    CONTAINS, STARTSWITH, ENDSWITH, ARRAY_CONTAINS
 *   ORDER BY field [ASC|DESC], multiple fields
 *   OFFSET n LIMIT m
 *   Named parameters (@name)
 */
public class CosmosQueryEngine {

    public record OrderByField(String path, boolean asc) {}

    public record ParsedQuery(
            boolean countQuery,
            boolean selectValue,
            List<String> selectFields,   // null = SELECT *
            String whereClause,          // null = no filter
            List<OrderByField> orderBy,
            int top,                     // -1 = none
            int offset,
            int limit                    // -1 = none
    ) {}

    public record QueryResult(List<Object> items, int count) {}

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public QueryResult execute(String sql, List<Map<String, Object>> params, List<Map<String, Object>> documents) {
        sql = normalizeWhitespace(substituteParams(sql, params));

        ParsedQuery q = parse(sql);

        List<Map<String, Object>> filtered = documents.stream()
                .filter(doc -> q.whereClause() == null || evalExpr(doc, q.whereClause()))
                .collect(Collectors.toCollection(ArrayList::new));

        if (q.countQuery()) {
            return new QueryResult(List.of((long) filtered.size()), 1);
        }

        if (!q.orderBy().isEmpty()) {
            filtered.sort(buildComparator(q.orderBy()));
        }

        if (q.top() >= 0) {
            filtered = filtered.stream().limit(q.top()).collect(Collectors.toCollection(ArrayList::new));
        }

        if (q.offset() > 0 || q.limit() >= 0) {
            Stream<Map<String, Object>> stream = filtered.stream().skip(q.offset());
            if (q.limit() >= 0) stream = stream.limit(q.limit());
            filtered = stream.collect(Collectors.toCollection(ArrayList::new));
        }

        List<Object> results;
        if (q.selectValue() && q.selectFields() != null && q.selectFields().size() == 1) {
            String path = q.selectFields().get(0);
            results = filtered.stream().map(doc -> resolve(doc, path)).collect(Collectors.toCollection(ArrayList::new));
        } else if (q.selectFields() == null) {
            results = new ArrayList<>(filtered);
        } else {
            results = filtered.stream()
                    .map(doc -> (Object) projectDoc(doc, q.selectFields()))
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        return new QueryResult(results, results.size());
    }

    // -----------------------------------------------------------------------
    // SQL parsing
    // -----------------------------------------------------------------------

    ParsedQuery parse(String sql) {
        String upper = sql.toUpperCase();

        boolean countQuery = upper.contains("COUNT(1)") || upper.contains("COUNT(*)");

        // TOP
        int top = -1;
        Matcher topM = Pattern.compile("(?i)\\bSELECT\\s+TOP\\s+(\\d+)").matcher(sql);
        if (topM.find()) top = Integer.parseInt(topM.group(1));

        // OFFSET … LIMIT …
        int offset = 0, limit = -1;
        Matcher olM = Pattern.compile("(?i)\\bOFFSET\\s+(\\d+)\\s+LIMIT\\s+(\\d+)").matcher(sql);
        if (olM.find()) {
            offset = Integer.parseInt(olM.group(1));
            limit  = Integer.parseInt(olM.group(2));
        }

        // Locate primary clause boundaries (all positions in the uppercased string)
        int fromIdx   = indexOfKeyword(upper, "FROM",     0);
        int whereIdx  = fromIdx   >= 0 ? indexOfKeyword(upper, "WHERE",    fromIdx)   : -1;
        int orderIdx  = indexOfKeyword(upper, "ORDER BY", whereIdx >= 0 ? whereIdx : Math.max(fromIdx, 0));
        int offsetIdx = indexOfKeyword(upper, "OFFSET",   orderIdx >= 0 ? orderIdx : Math.max(whereIdx, Math.max(fromIdx, 0)));

        // WHERE clause text
        String where = null;
        if (whereIdx >= 0) {
            int end = firstNonNeg(orderIdx, offsetIdx, sql.length());
            where = sql.substring(whereIdx + 6, end).trim();
        }

        // ORDER BY fields
        List<OrderByField> orderBy = new ArrayList<>();
        if (orderIdx >= 0) {
            int end = offsetIdx >= 0 ? offsetIdx : sql.length();
            String orderClause = sql.substring(orderIdx + 8, end).trim();
            for (String part : splitTopLevel(orderClause, ',')) {
                part = part.trim();
                if (part.isEmpty()) continue;
                boolean asc = !part.toUpperCase().endsWith(" DESC");
                String path = part.replaceAll("(?i)\\s+(ASC|DESC)$", "").trim();
                orderBy.add(new OrderByField(path, asc));
            }
        }

        // SELECT fields
        boolean selectValue = false;
        List<String> selectFields = null;
        if (!countQuery) {
            int selectKw  = indexOfKeyword(upper, "SELECT", 0);
            int selectEnd = fromIdx >= 0 ? fromIdx : sql.length();
            if (selectKw >= 0) {
                String selectClause = sql.substring(selectKw + 6, selectEnd).trim();
                // Strip TOP n
                selectClause = selectClause.replaceFirst("(?i)^TOP\\s+\\d+\\s+", "");
                if (selectClause.toUpperCase().startsWith("VALUE ")) {
                    selectValue = true;
                    selectClause = selectClause.substring(6).trim();
                }
                if (!"*".equals(selectClause)) {
                    selectFields = Arrays.stream(selectClause.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();
                }
            }
        }

        return new ParsedQuery(countQuery, selectValue, selectFields, where, orderBy, top, offset, limit);
    }

    // -----------------------------------------------------------------------
    // WHERE evaluation — recursive descent
    // -----------------------------------------------------------------------

    boolean evalExpr(Map<String, Object> doc, String expr) {
        expr = expr.trim();
        if (expr.isEmpty()) return true;

        // Strip balanced outer parens
        if (expr.startsWith("(") && matchingCloseParen(expr, 0) == expr.length() - 1) {
            return evalExpr(doc, expr.substring(1, expr.length() - 1));
        }

        // NOT
        if (expr.toUpperCase().startsWith("NOT ")) {
            return !evalExpr(doc, expr.substring(4));
        }

        // OR  (lower precedence → split first)
        int orIdx = findTopLevelKeyword(expr, "OR");
        if (orIdx >= 0) {
            return evalExpr(doc, expr.substring(0, orIdx).trim())
                    || evalExpr(doc, expr.substring(orIdx + 2).trim());
        }

        // AND
        int andIdx = findTopLevelKeyword(expr, "AND");
        if (andIdx >= 0) {
            return evalExpr(doc, expr.substring(0, andIdx).trim())
                    && evalExpr(doc, expr.substring(andIdx + 3).trim());
        }

        return evalPredicate(doc, expr);
    }

    private boolean evalPredicate(Map<String, Object> doc, String pred) {
        pred = pred.trim();
        Matcher m;

        // IS_DEFINED(c.field)
        m = Pattern.compile("(?i)IS_DEFINED\\s*\\(([^)]+)\\)").matcher(pred);
        if (m.matches()) return resolve(doc, m.group(1).trim()) != null;

        // IS_NULL(c.field)
        m = Pattern.compile("(?i)IS_NULL\\s*\\(([^)]+)\\)").matcher(pred);
        if (m.matches()) return resolve(doc, m.group(1).trim()) == null;

        // IS_STRING / IS_NUMBER / IS_BOOL / IS_ARRAY / IS_OBJECT
        m = Pattern.compile("(?i)(IS_STRING|IS_NUMBER|IS_BOOL|IS_ARRAY|IS_OBJECT)\\s*\\(([^)]+)\\)").matcher(pred);
        if (m.matches()) {
            Object val = resolve(doc, m.group(2).trim());
            return switch (m.group(1).toUpperCase()) {
                case "IS_STRING" -> val instanceof String;
                case "IS_NUMBER" -> val instanceof Number;
                case "IS_BOOL"   -> val instanceof Boolean;
                case "IS_ARRAY"  -> val instanceof List;
                case "IS_OBJECT" -> val instanceof Map;
                default          -> false;
            };
        }

        // CONTAINS(field, str [, ignoreCase])
        m = Pattern.compile("(?i)CONTAINS\\s*\\(([^,]+),\\s*(.+?)(?:,\\s*(true|false))?\\s*\\)").matcher(pred);
        if (m.matches()) {
            Object val  = resolve(doc, m.group(1).trim());
            String srch = stripQuotes(m.group(2).trim());
            boolean ci  = "true".equalsIgnoreCase(m.group(3));
            if (!(val instanceof String s)) return false;
            return ci ? s.toLowerCase().contains(srch.toLowerCase()) : s.contains(srch);
        }

        // STARTSWITH(field, str)
        m = Pattern.compile("(?i)STARTSWITH\\s*\\(([^,]+),\\s*(.+?)\\s*\\)").matcher(pred);
        if (m.matches()) {
            Object val = resolve(doc, m.group(1).trim());
            return val instanceof String s && s.startsWith(stripQuotes(m.group(2).trim()));
        }

        // ENDSWITH(field, str)
        m = Pattern.compile("(?i)ENDSWITH\\s*\\(([^,]+),\\s*(.+?)\\s*\\)").matcher(pred);
        if (m.matches()) {
            Object val = resolve(doc, m.group(1).trim());
            return val instanceof String s && s.endsWith(stripQuotes(m.group(2).trim()));
        }

        // ARRAY_CONTAINS(field, value)
        m = Pattern.compile("(?i)ARRAY_CONTAINS\\s*\\(([^,]+),\\s*(.+?)\\s*\\)").matcher(pred);
        if (m.matches()) {
            Object arr  = resolve(doc, m.group(1).trim());
            Object srch = parseLiteral(m.group(2).trim());
            return arr instanceof List<?> list && list.stream().anyMatch(item -> objectEquals(item, srch));
        }

        // field IN (val1, val2, …)
        m = Pattern.compile("(?i)(.+?)\\s+IN\\s*\\((.+)\\)").matcher(pred);
        if (m.matches()) {
            Object val = resolve(doc, m.group(1).trim());
            for (String raw : splitTopLevel(m.group(2), ',')) {
                if (objectEquals(val, parseLiteral(raw.trim()))) return true;
            }
            return false;
        }

        // field BETWEEN low AND high
        m = Pattern.compile("(?i)(.+?)\\s+BETWEEN\\s+(.+?)\\s+AND\\s+(.+)").matcher(pred);
        if (m.matches()) {
            Object val  = resolve(doc, m.group(1).trim());
            Object low  = parseLiteral(m.group(2).trim());
            Object high = parseLiteral(m.group(3).trim());
            return compareValues(val, low) >= 0 && compareValues(val, high) <= 0;
        }

        // field OP literal
        m = Pattern.compile("(.+?)\\s*(=|!=|<>|>=|<=|>|<)\\s*(.+)").matcher(pred);
        if (m.matches()) {
            Object docVal = resolve(doc, m.group(1).trim());
            Object lit    = parseLiteral(m.group(3).trim());
            return compare(docVal, m.group(2).trim(), lit);
        }

        return false;
    }

    // -----------------------------------------------------------------------
    // Field resolution
    // -----------------------------------------------------------------------

    Object resolve(Map<String, Object> doc, String path) {
        // Strip FROM alias prefix: "c.field" → "field"
        if (path.contains(".")) {
            String[] parts = path.split("\\.", 2);
            // If first part looks like an alias (1–10 chars, no special chars), strip it
            if (parts[0].matches("[a-zA-Z_][a-zA-Z0-9_]{0,9}")) {
                path = parts[1];
            }
        }
        // Navigate nested path
        Object current = doc;
        for (String seg : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(seg);
            } else {
                return null;
            }
        }
        return current;
    }

    // -----------------------------------------------------------------------
    // Projection
    // -----------------------------------------------------------------------

    private Map<String, Object> projectDoc(Map<String, Object> doc, List<String> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : fields) {
            // "c.name" → key "name"; "c.address.city" → nested key "address.city"
            String key = field.contains(".") ? field.substring(field.indexOf('.') + 1) : field;
            Object val = resolve(doc, field);
            setNested(result, key, val);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void setNested(Map<String, Object> target, String path, Object value) {
        String[] parts = path.split("\\.", 2);
        if (parts.length == 1) {
            target.put(parts[0], value);
        } else {
            Map<String, Object> child = (Map<String, Object>)
                    target.computeIfAbsent(parts[0], k -> new LinkedHashMap<>());
            setNested(child, parts[1], value);
        }
    }

    // -----------------------------------------------------------------------
    // Comparison
    // -----------------------------------------------------------------------

    private boolean compare(Object docVal, String op, Object lit) {
        if (docVal == null && lit == null) return "=".equals(op);
        if (docVal == null || lit == null)  return "!=".equals(op) || "<>".equals(op);
        int cmp = compareValues(docVal, lit);
        return switch (op) {
            case "="        -> cmp == 0;
            case "!=", "<>" -> cmp != 0;
            case ">"        -> cmp > 0;
            case ">="       -> cmp >= 0;
            case "<"        -> cmp < 0;
            case "<="       -> cmp <= 0;
            default         -> false;
        };
    }

    int compareValues(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof String sa && b instanceof String sb) return sa.compareTo(sb);
        if (a instanceof Boolean ba && b instanceof Boolean bb) return Boolean.compare(ba, bb);
        // Cross-type: coerce numbers
        try {
            double da = Double.parseDouble(String.valueOf(a));
            double db = Double.parseDouble(String.valueOf(b));
            return Double.compare(da, db);
        } catch (NumberFormatException ignored) {}
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private boolean objectEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number na && b instanceof Number nb)
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        return a.equals(b);
    }

    // -----------------------------------------------------------------------
    // Sorting
    // -----------------------------------------------------------------------

    private Comparator<Map<String, Object>> buildComparator(List<OrderByField> orderBy) {
        Comparator<Map<String, Object>> comp = null;
        for (OrderByField ob : orderBy) {
            final String path = ob.path();
            Comparator<Map<String, Object>> single = (a, b) -> {
                Object va = resolve(a, path);
                Object vb = resolve(b, path);
                if (va == null && vb == null) return 0;
                if (va == null) return -1;
                if (vb == null) return 1;
                return compareValues(va, vb);
            };
            if (!ob.asc()) single = single.reversed();
            comp = comp == null ? single : comp.thenComparing(single);
        }
        return comp != null ? comp : (a, b) -> 0;
    }

    // -----------------------------------------------------------------------
    // Parsing utilities
    // -----------------------------------------------------------------------

    private String substituteParams(String sql, List<Map<String, Object>> params) {
        if (params == null || params.isEmpty()) return sql;
        for (Map<String, Object> p : params) {
            String name  = (String) p.get("name");
            Object value = p.get("value");
            if (name == null) continue;
            sql = sql.replace(name, toLiteral(value));
        }
        return sql;
    }

    private String toLiteral(Object value) {
        if (value == null)          return "null";
        if (value instanceof String s) return "'" + s.replace("'", "\\'") + "'";
        if (value instanceof Boolean b) return b.toString();
        return String.valueOf(value);
    }

    Object parseLiteral(String s) {
        if (s == null || s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        if ("true".equalsIgnoreCase(s))  return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))
            return s.substring(1, s.length() - 1).replace("\\'", "'").replace("\\\"", "\"");
        try { return Long.parseLong(s); }   catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        return s;
    }

    private String stripQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        char f = s.charAt(0), l = s.charAt(s.length() - 1);
        return (f == '\'' && l == '\'') || (f == '"' && l == '"')
                ? s.substring(1, s.length() - 1) : s;
    }

    private String normalizeWhitespace(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    // -----------------------------------------------------------------------
    // String scanning helpers
    // -----------------------------------------------------------------------

    /** Find the first occurrence of {@code keyword} (whole-word, case-insensitive) at depth 0. */
    int findTopLevelKeyword(String expr, String keyword) {
        int depth = 0;
        boolean inStr = false;
        char strCh = 0;
        String upper = expr.toUpperCase();

        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (inStr) {
                if (c == strCh) inStr = false;
                continue;
            }
            if (c == '\'' || c == '"') { inStr = true; strCh = c; continue; }
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }

            if (depth == 0 && upper.regionMatches(i, keyword, 0, keyword.length())) {
                int end = i + keyword.length();
                boolean beforeOk = i == 0 || !Character.isLetterOrDigit(expr.charAt(i - 1));
                boolean afterOk  = end >= expr.length() || !Character.isLetterOrDigit(expr.charAt(end));
                if (beforeOk && afterOk) return i;
            }
        }
        return -1;
    }

    /** Find the first occurrence of {@code keyword} (whole-word) in the already-uppercased SQL from {@code from}. */
    private int indexOfKeyword(String upperSql, String keyword, int from) {
        int idx = upperSql.indexOf(keyword, from);
        while (idx >= 0) {
            int end = idx + keyword.length();
            boolean beforeOk = idx == 0 || !Character.isLetterOrDigit(upperSql.charAt(idx - 1));
            boolean afterOk  = end >= upperSql.length() || !Character.isLetterOrDigit(upperSql.charAt(end));
            if (beforeOk && afterOk) return idx;
            idx = upperSql.indexOf(keyword, idx + 1);
        }
        return -1;
    }

    /** Return the index of the ')' that closes the '(' at {@code start}. */
    private int matchingCloseParen(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '(') depth++;
            else if (s.charAt(i) == ')') { if (--depth == 0) return i; }
        }
        return -1;
    }

    /** Split {@code s} by {@code delim} ignoring delimiters inside parens or string literals. */
    List<String> splitTopLevel(String s, char delim) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        boolean inStr = false;
        char strCh = 0;
        int start = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == strCh) inStr = false;
            } else if (c == '\'' || c == '"') {
                inStr = true; strCh = c;
            } else if (c == '(') { depth++;
            } else if (c == ')') { depth--;
            } else if (c == delim && depth == 0) {
                result.add(s.substring(start, i));
                start = i + 1;
            }
        }
        result.add(s.substring(start));
        return result;
    }

    // Return the first value ≥ 0 among the candidates, or fallback.
    private int firstNonNeg(int... candidates) {
        for (int c : candidates) if (c >= 0) return c;
        return candidates[candidates.length - 1];
    }
}
