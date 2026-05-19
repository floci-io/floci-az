package io.floci.az.services.cosmos.table;

import java.util.Map;

/**
 * Evaluates OData $filter expressions for the in-memory Azure Cosmos DB Table API.
 *
 * <p>Supported operators: {@code eq}, {@code ne}, {@code gt}, {@code ge}, {@code lt}, {@code le}
 * <p>Logical combinators: {@code and}, {@code or}, {@code not}
 * <p>Literal types: string {@code 'value'}, integer {@code 42}, boolean {@code true}/{@code false},
 *   datetime {@code datetime'...'}, guid {@code guid'...'}.
 *
 * <p>This is intentionally focused on the OData subset used by Azure Table Storage — it does NOT
 * implement Cosmos SQL syntax (that lives in {@code CosmosQueryEngine}).
 */
public class CosmosTableODataFilter {

    /**
     * Returns true if {@code entity} satisfies the given OData filter.
     * A null or blank filter always matches.
     */
    public boolean matches(Map<String, Object> entity, String filter) {
        if (filter == null || filter.isBlank()) return true;
        return evalExpr(entity, filter.trim());
    }

    // -----------------------------------------------------------------------
    // Recursive evaluation
    // -----------------------------------------------------------------------

    private boolean evalExpr(Map<String, Object> entity, String expr) {
        expr = expr.trim();
        if (expr.isEmpty()) return true;

        // Strip balanced outer parens
        if (expr.startsWith("(") && matchingClose(expr, 0) == expr.length() - 1) {
            return evalExpr(entity, expr.substring(1, expr.length() - 1));
        }

        // NOT
        if (expr.toLowerCase().startsWith("not ")) {
            return !evalExpr(entity, expr.substring(4).trim());
        }

        // OR (lower precedence — split first)
        int orIdx = findTopLevelOp(expr, "or");
        if (orIdx >= 0) {
            return evalExpr(entity, expr.substring(0, orIdx).trim())
                    || evalExpr(entity, expr.substring(orIdx + 2).trim());
        }

        // AND
        int andIdx = findTopLevelOp(expr, "and");
        if (andIdx >= 0) {
            return evalExpr(entity, expr.substring(0, andIdx).trim())
                    && evalExpr(entity, expr.substring(andIdx + 3).trim());
        }

        return evalPredicate(entity, expr);
    }

    private boolean evalPredicate(Map<String, Object> entity, String pred) {
        // field op literal  (e.g.  PartitionKey eq 'pk'  or  age gt 25)
        int[] opBounds = findComparisonOp(pred);
        if (opBounds == null) return false;

        String field    = pred.substring(0, opBounds[0]).trim();
        String op       = pred.substring(opBounds[0], opBounds[1]).trim().toLowerCase();
        String rawVal   = pred.substring(opBounds[1]).trim();

        Object entityVal = entity.get(field);
        Object filterVal = parseLiteral(rawVal);

        return compare(entityVal, op, filterVal);
    }

    /** Locate the comparison operator (eq/ne/gt/ge/lt/le) at the top level. */
    private int[] findComparisonOp(String pred) {
        String[] ops = {"ge", "le", "gt", "lt", "eq", "ne"};
        for (String op : ops) {
            int idx = findTopLevelOp(pred, op);
            if (idx >= 0) return new int[]{idx, idx + op.length()};
        }
        return null;
    }

    private boolean compare(Object entityVal, String op, Object filterVal) {
        if (entityVal == null && filterVal == null) return "eq".equals(op);
        if (entityVal == null || filterVal == null)  return "ne".equals(op);
        int cmp = compareValues(entityVal, filterVal);
        return switch (op) {
            case "eq" -> cmp == 0;
            case "ne" -> cmp != 0;
            case "gt" -> cmp > 0;
            case "ge" -> cmp >= 0;
            case "lt" -> cmp < 0;
            case "le" -> cmp <= 0;
            default   -> false;
        };
    }

    private int compareValues(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb)
            return Double.compare(na.doubleValue(), nb.doubleValue());
        if (a instanceof String sa && b instanceof String sb) return sa.compareTo(sb);
        if (a instanceof Boolean ba && b instanceof Boolean bb) return Boolean.compare(ba, bb);
        try {
            return Double.compare(
                    Double.parseDouble(String.valueOf(a)),
                    Double.parseDouble(String.valueOf(b)));
        } catch (NumberFormatException ignored) {}
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    // -----------------------------------------------------------------------
    // OData literal parser
    // -----------------------------------------------------------------------

    Object parseLiteral(String s) {
        if (s == null || s.isEmpty()) return null;

        // String: 'value'  (single-quoted; '' is an escaped quote)
        if (s.startsWith("'") && s.endsWith("'"))
            return s.substring(1, s.length() - 1).replace("''", "'");

        // datetime'...' / guid'...' / binary'...' — extract the inner value as string
        for (String prefix : new String[]{"datetime", "guid", "binary", "X"}) {
            if (s.toLowerCase().startsWith(prefix.toLowerCase() + "'") && s.endsWith("'"))
                return s.substring(prefix.length() + 1, s.length() - 1);
        }

        // Boolean
        if ("true".equalsIgnoreCase(s))  return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;

        // 64-bit integer suffix L/l
        if ((s.endsWith("L") || s.endsWith("l"))) {
            try { return Long.parseLong(s.substring(0, s.length() - 1)); }
            catch (NumberFormatException ignored) {}
        }

        // Integer
        try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        // Floating-point
        try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}

        return s; // fallback: treat as opaque string
    }

    // -----------------------------------------------------------------------
    // Scanning helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the start index of the first occurrence of {@code op} as a whole word at paren-depth 0,
     * respecting single-quoted string literals.  Case-insensitive.
     */
    private int findTopLevelOp(String expr, String op) {
        boolean inStr  = false;
        int     depth  = 0;
        String  upper  = expr.toUpperCase();
        String  upperOp = op.toUpperCase();

        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (inStr) { if (c == '\'') inStr = false; continue; }
            if (c == '\'') { inStr = true; continue; }
            if (c == '(')  { depth++; continue; }
            if (c == ')')  { depth--; continue; }

            if (depth == 0 && upper.regionMatches(i, upperOp, 0, upperOp.length())) {
                int end = i + upperOp.length();
                boolean beforeOk = (i == 0)              || !Character.isLetterOrDigit(expr.charAt(i - 1));
                boolean afterOk  = (end >= expr.length()) || !Character.isLetterOrDigit(expr.charAt(end));
                if (beforeOk && afterOk) return i;
            }
        }
        return -1;
    }

    private int matchingClose(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            if      (s.charAt(i) == '(') depth++;
            else if (s.charAt(i) == ')') { if (--depth == 0) return i; }
        }
        return -1;
    }
}
