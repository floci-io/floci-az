package io.floci.az.services.resourcegraph.kql;

import io.floci.az.services.resourcegraph.kql.Kql.Bin;
import io.floci.az.services.resourcegraph.kql.Kql.Call;
import io.floci.az.services.resourcegraph.kql.Kql.Col;
import io.floci.az.services.resourcegraph.kql.Kql.Count;
import io.floci.az.services.resourcegraph.kql.Kql.Distinct;
import io.floci.az.services.resourcegraph.kql.Kql.Expr;
import io.floci.az.services.resourcegraph.kql.Kql.Extend;
import io.floci.az.services.resourcegraph.kql.Kql.In;
import io.floci.az.services.resourcegraph.kql.Kql.Index;
import io.floci.az.services.resourcegraph.kql.Kql.Join;
import io.floci.az.services.resourcegraph.kql.Kql.JoinKey;
import io.floci.az.services.resourcegraph.kql.Kql.Lit;
import io.floci.az.services.resourcegraph.kql.Kql.Member;
import io.floci.az.services.resourcegraph.kql.Kql.Named;
import io.floci.az.services.resourcegraph.kql.Kql.Neg;
import io.floci.az.services.resourcegraph.kql.Kql.Not;
import io.floci.az.services.resourcegraph.kql.Kql.Op;
import io.floci.az.services.resourcegraph.kql.Kql.OrderBy;
import io.floci.az.services.resourcegraph.kql.Kql.Project;
import io.floci.az.services.resourcegraph.kql.Kql.ProjectAway;
import io.floci.az.services.resourcegraph.kql.Kql.Query;
import io.floci.az.services.resourcegraph.kql.Kql.SortKey;
import io.floci.az.services.resourcegraph.kql.Kql.SubquerySource;
import io.floci.az.services.resourcegraph.kql.Kql.Summarize;
import io.floci.az.services.resourcegraph.kql.Kql.TableSource;
import io.floci.az.services.resourcegraph.kql.Kql.Take;
import io.floci.az.services.resourcegraph.kql.Kql.Union;
import io.floci.az.services.resourcegraph.kql.Kql.Where;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Runs a parsed {@link Kql.Query} over in-memory tables. Rows are ordered maps of column name to value;
 * a column holds a scalar or a dynamic (map or list) value. Every operator produces a new row list, so
 * the source tables are never modified.
 */
public final class KqlEngine {

    /** Where the engine reads tables from. {@link #rows} answers null for a table it does not have. */
    public interface Tables {
        List<Map<String, Object>> rows(String table);

        /** The published columns of a table, so names are checked whatever the data; null when unknown. */
        default List<String> columns(String table) {
            return null;
        }
    }

    /** The aggregation functions {@code summarize} accepts, checked before a query runs. */
    static final Set<String> AGGREGATES = Set.of("any", "arg_max", "arg_min", "avg", "count", "countif", "dcount",
        "make_list", "make_set", "max", "min", "sum", "take_any");

    private KqlEngine() {
    }

    public static List<Map<String, Object>> run(Query query, Tables tables) {
        List<Map<String, Object>> rows;
        Set<String> schema;
        switch (query.source()) {
            case TableSource table -> {
                List<Map<String, Object>> source = tables.rows(table.name());
                if (source == null) {
                    throw KqlException.semantic("Failed to resolve table or column expression named '" + table.name() + "'");
                }
                rows = new ArrayList<>(source);
                List<String> published = tables.columns(table.name());
                schema = published != null ? lowerCased(published) : rows.isEmpty() ? null : lowerCased(columns(rows));
            }
            case SubquerySource sub -> {
                rows = run(sub.query(), tables);
                schema = rows.isEmpty() ? null : lowerCased(columns(rows));
            }
        }
        for (Op op : query.ops()) {
            schema = checkNames(op, schema);
            rows = apply(op, rows, tables);
        }
        return rows;
    }

    // ── Column checks ───────────────────────────────────────────────────────────

    /**
     * Rejects a function the engine does not know and, when the schema is known, a reference to a top-level
     * column it does not have, as Kusto does before running the query; returns the schema the operator leaves
     * behind, or null when its output columns depend on the data (a join, a union, a summary), after which
     * only function names are checked.
     */
    private static Set<String> checkNames(Op op, Set<String> schema) {
        switch (op) {
            case Where where -> {
                checkExpr(where.predicate(), schema);
                return schema;
            }
            case Extend extend -> {
                Set<String> out = schema == null ? null : new LinkedHashSet<>(schema);
                int position = 0;
                for (Named column : extend.columns()) {
                    checkExpr(column.expr(), schema);
                    if (out != null) {
                        out.add(columnName(column, position).toLowerCase(Locale.ROOT));
                    }
                    position++;
                }
                return out;
            }
            case Project project -> {
                Set<String> out = new LinkedHashSet<>();
                int position = 0;
                for (Named column : project.columns()) {
                    checkExpr(column.expr(), schema);
                    out.add(columnName(column, position++).toLowerCase(Locale.ROOT));
                }
                return out;
            }
            case ProjectAway away -> {
                if (schema == null) {
                    return null;
                }
                Set<String> out = new LinkedHashSet<>(schema);
                for (String column : away.columns()) {
                    out.remove(column.toLowerCase(Locale.ROOT));
                }
                return out;
            }
            case Summarize summarize -> {
                for (Named by : summarize.by()) {
                    checkExpr(by.expr(), schema);
                }
                for (Named aggregate : summarize.aggregates()) {
                    if (aggregate.expr() instanceof Call call && !AGGREGATES.contains(call.name())) {
                        throw KqlException.semantic("Unknown aggregation function '" + call.name() + "'");
                    }
                    for (Expr arg : aggregate.expr() instanceof Call call ? call.args() : List.<Expr>of()) {
                        if (!(arg instanceof Col col && col.leaf().equals("*"))) {
                            checkExpr(arg, schema);
                        }
                    }
                }
                return null;
            }
            case OrderBy orderBy -> {
                for (SortKey key : orderBy.keys()) {
                    checkExpr(key.expr(), schema);
                }
                return schema;
            }
            case Distinct distinct -> {
                if (distinct.columns().isEmpty() || distinct.columns().contains("*")) {
                    return schema;
                }
                Set<String> out = new LinkedHashSet<>();
                for (String column : distinct.columns()) {
                    checkColumn(column, schema);
                    out.add(column.toLowerCase(Locale.ROOT));
                }
                return out;
            }
            case Take take -> {
                return schema;
            }
            case Count count -> {
                return lowerCased(List.of("Count"));
            }
            case Union union -> {
                return null;
            }
            case Join join -> {
                for (JoinKey key : join.keys()) {
                    if (key.left().size() == 1) {
                        checkColumn(key.left().getFirst(), schema);
                    }
                }
                return null;
            }
        }
    }

    private static void checkExpr(Expr expr, Set<String> schema) {
        switch (expr) {
            case Col col -> {
                if (!col.path().isEmpty() && !col.leaf().equals("*")) {
                    checkColumn(col.path().getFirst(), schema);
                }
            }
            case Call call -> {
                if (!KqlFunctions.isKnown(call.name())) {
                    throw KqlException.semantic("Unknown function '" + call.name() + "'");
                }
                call.args().forEach(arg -> checkExpr(arg, schema));
            }
            case Bin bin -> {
                checkExpr(bin.left(), schema);
                checkExpr(bin.right(), schema);
            }
            case Not not -> checkExpr(not.expr(), schema);
            case Neg neg -> checkExpr(neg.expr(), schema);
            case In in -> {
                checkExpr(in.expr(), schema);
                in.options().forEach(option -> checkExpr(option, schema));
            }
            case Member member -> checkExpr(member.target(), schema);
            case Index index -> {
                checkExpr(index.target(), schema);
                checkExpr(index.index(), schema);
            }
            case Lit lit -> {
                // nothing to check
            }
        }
    }

    private static void checkColumn(String name, Set<String> schema) {
        if (schema != null && !schema.contains(name.toLowerCase(Locale.ROOT))) {
            throw KqlException.semantic("Failed to resolve table or column expression named '" + name + "'");
        }
    }

    private static Set<String> lowerCased(List<String> names) {
        Set<String> out = new LinkedHashSet<>();
        for (String name : names) {
            out.add(name.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static List<Map<String, Object>> apply(Op op, List<Map<String, Object>> rows, Tables tables) {
        return switch (op) {
            case Where where -> {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    if (Boolean.TRUE.equals(KqlValues.toBool(eval(where.predicate(), row)))) {
                        out.add(row);
                    }
                }
                yield out;
            }
            case Extend extend -> {
                List<Map<String, Object>> out = new ArrayList<>(rows.size());
                for (Map<String, Object> row : rows) {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    int position = 0;
                    for (Named column : extend.columns()) {
                        copy.put(columnName(column, position++), eval(column.expr(), row));
                    }
                    out.add(copy);
                }
                yield out;
            }
            case Project project -> {
                List<Map<String, Object>> out = new ArrayList<>(rows.size());
                for (Map<String, Object> row : rows) {
                    Map<String, Object> projected = new LinkedHashMap<>();
                    int position = 0;
                    for (Named column : project.columns()) {
                        projected.put(columnName(column, position++), eval(column.expr(), row));
                    }
                    out.add(projected);
                }
                yield out;
            }
            case ProjectAway away -> {
                List<Map<String, Object>> out = new ArrayList<>(rows.size());
                for (Map<String, Object> row : rows) {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    for (String column : away.columns()) {
                        copy.keySet().removeIf(key -> key.equalsIgnoreCase(column));
                    }
                    out.add(copy);
                }
                yield out;
            }
            case Summarize summarize -> summarize(summarize, rows);
            case OrderBy orderBy -> {
                List<Map<String, Object>> out = new ArrayList<>(rows);
                out.sort((a, b) -> {
                    for (SortKey key : orderBy.keys()) {
                        Object x = eval(key.expr(), a);
                        Object y = eval(key.expr(), b);
                        boolean nullsFirst = key.nullsFirst() != null ? key.nullsFirst() : !key.descending();
                        if (x == null || y == null) {
                            if (x == null && y == null) {
                                continue;
                            }
                            int nullOrder = x == null ? -1 : 1;
                            int result = nullsFirst ? nullOrder : -nullOrder;
                            if (result != 0) {
                                return result;
                            }
                            continue;
                        }
                        Integer compared = KqlValues.compare(x, y);
                        int c = compared == null ? KqlValues.toString(x).compareTo(KqlValues.toString(y)) : compared;
                        if (c != 0) {
                            return key.descending() ? -c : c;
                        }
                    }
                    return 0;
                });
                yield out;
            }
            case Distinct distinct -> {
                Set<String> seen = new HashSet<>();
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    Map<String, Object> key = new LinkedHashMap<>();
                    if (distinct.columns().isEmpty()) {
                        key.putAll(row);
                    } else {
                        for (String column : distinct.columns()) {
                            key.put(column, column(row, column));
                        }
                    }
                    if (seen.add(KqlValues.toString(key))) {
                        out.add(distinct.columns().isEmpty() ? row : key);
                    }
                }
                yield out;
            }
            case Take take -> rows.size() <= take.count() ? rows : new ArrayList<>(rows.subList(0, (int) take.count()));
            case Union union -> {
                List<Map<String, Object>> out = new ArrayList<>(rows);
                for (Query query : union.queries()) {
                    out.addAll(run(query, tables));
                }
                yield out;
            }
            case Join join -> join(join, rows, run(join.right(), tables));
            case Count ignored -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("Count", (long) rows.size());
                yield List.of(row);
            }
        };
    }

    // ── Column naming ───────────────────────────────────────────────────────────

    /** The name an output column gets: the given one, a column's own name, or the column a function reads. */
    static String columnName(Named named) {
        return columnName(named, 0);
    }

    /** As {@link #columnName(Named)}, numbering an unnamed calculation by its {@code position} ({@code Column1}, ...). */
    static String columnName(Named named, int position) {
        if (named.name() != null) {
            return named.name();
        }
        return inferredName(named.expr(), position);
    }

    /** The columns a query's last operator produces, for a result with no rows: a projection or a summary. */
    public static List<String> outputColumns(Query query, List<Map<String, Object>> rows) {
        if (!rows.isEmpty() || query.ops().isEmpty()) {
            return columns(rows);
        }
        Op last = query.ops().getLast();
        List<String> names = new ArrayList<>();
        if (last instanceof Project project) {
            int position = 0;
            for (Named column : project.columns()) {
                names.add(columnName(column, position++));
            }
        } else if (last instanceof Summarize summarize) {
            int position = 0;
            for (Named by : summarize.by()) {
                names.add(columnName(by, position++));
            }
            for (Named aggregate : summarize.aggregates()) {
                names.add(aggregate.name() != null ? aggregate.name()
                    : aggregate.expr() instanceof Call call ? aggregateName(call) : "Column" + (position + 1));
                position++;
            }
        }
        return names;
    }

    private static String inferredName(Expr expr, int position) {
        if (expr instanceof Col col) {
            return col.path().size() == 1 ? col.leaf() : String.join("_", col.path());
        }
        if (expr instanceof Call call) {
            if (!call.args().isEmpty() && call.args().getFirst() instanceof Col col && !col.leaf().equals("*")) {
                return inferredName(col, position);
            }
            return call.name().equals("count") ? "count_" : call.name() + "_";
        }
        return "Column" + (position + 1);
    }

    // ── Summarize ───────────────────────────────────────────────────────────────

    private static List<Map<String, Object>> summarize(Summarize summarize, List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        Map<String, Map<String, Object>> keys = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> key = new LinkedHashMap<>();
            int position = 0;
            for (Named by : summarize.by()) {
                key.put(columnName(by, position++), eval(by.expr(), row));
            }
            String groupKey = KqlValues.toString(key);
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(row);
            keys.putIfAbsent(groupKey, key);
        }
        if (summarize.by().isEmpty() && groups.isEmpty()) {
            groups.put("", List.of());
            keys.put("", new LinkedHashMap<>());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> group : groups.entrySet()) {
            Map<String, Object> result = new LinkedHashMap<>(keys.get(group.getKey()));
            int position = result.size();
            for (Named aggregate : summarize.aggregates()) {
                if (aggregate.name() == null && aggregate.expr() instanceof Call call && selectsColumns(call)) {
                    result.putAll(selectedColumns(call, group.getValue()));
                    position++;
                    continue;
                }
                String name = aggregate.name() != null ? aggregate.name()
                    : aggregate.expr() instanceof Call call ? aggregateName(call) : "Column" + (position + 1);
                result.put(name, aggregate(aggregate.expr(), group.getValue()));
                position++;
            }
            out.add(result);
        }
        return out;
    }

    /** True for {@code arg_max(x, *)}, {@code arg_max(x, a, b)}, {@code take_any(*)} and the like, which yield several columns. */
    private static boolean selectsColumns(Call call) {
        boolean selector = call.name().equals("arg_max") || call.name().equals("arg_min");
        boolean any = call.name().equals("take_any") || call.name().equals("any");
        if (!selector && !any) {
            return false;
        }
        if (selector) {
            return call.args().size() >= 2; // arg_max(by, a, b) yields by, a and b; arg_max(by, *) the whole row
        }
        if (call.args().size() > 1) {
            return true;
        }
        return !call.args().isEmpty() && call.args().getFirst() instanceof Col col && col.leaf().equals("*");
    }

    /** The columns {@code arg_max} / {@code arg_min} / {@code take_any} return from the chosen row: the selected ones, or all of them for {@code *}. */
    private static Map<String, Object> selectedColumns(Call call, List<Map<String, Object>> group) {
        boolean selector = call.name().startsWith("arg_");
        Map<String, Object> chosen = group.isEmpty() ? null : chosenRow(call, group);
        List<Expr> selected = call.args().subList(selector ? 1 : 0, call.args().size());
        Map<String, Object> out = new LinkedHashMap<>();
        if (selector) {
            Named first = new Named(null, call.args().getFirst());
            out.put(columnName(first), chosen == null ? null : eval(call.args().getFirst(), chosen));
        }
        int position = 0;
        for (Expr expr : selected) {
            if (expr instanceof Col col && col.leaf().equals("*")) {
                if (chosen != null) {
                    chosen.forEach((name, value) -> out.putIfAbsent(name, value));
                }
                continue;
            }
            out.put(columnName(new Named(null, expr), position++), chosen == null ? null : eval(expr, chosen));
        }
        return out;
    }

    private static Map<String, Object> chosenRow(Call call, List<Map<String, Object>> group) {
        Map<String, Object> chosen = group.getFirst();
        if (call.name().startsWith("arg_")) {
            Expr by = call.args().getFirst();
            for (Map<String, Object> row : group) {
                Integer c = KqlValues.compare(eval(by, row), eval(by, chosen));
                if (c != null && (call.name().equals("arg_max") ? c > 0 : c < 0)) {
                    chosen = row;
                }
            }
        }
        return chosen;
    }

    private static String aggregateName(Call call) {
        if (call.args().isEmpty() || !(call.args().getFirst() instanceof Col col) || col.leaf().equals("*")) {
            return call.name() + "_";
        }
        return call.name() + "_" + col.leaf();
    }

    private static Object aggregate(Expr expr, List<Map<String, Object>> group) {
        if (!(expr instanceof Call call)) {
            throw KqlException.semantic("summarize expects aggregation functions");
        }
        List<Expr> args = call.args();
        switch (call.name()) {
            case "count" -> {
                return (long) group.size();
            }
            case "countif" -> {
                long n = 0;
                for (Map<String, Object> row : group) {
                    if (Boolean.TRUE.equals(KqlValues.toBool(eval(args.getFirst(), row)))) {
                        n++;
                    }
                }
                return n;
            }
            case "dcount" -> {
                Set<String> seen = new HashSet<>();
                for (Map<String, Object> row : group) {
                    Object v = eval(args.getFirst(), row);
                    if (v != null) {
                        seen.add(KqlValues.toString(v));
                    }
                }
                return (long) seen.size();
            }
            case "sum", "avg" -> {
                double total = 0;
                long n = 0;
                for (Map<String, Object> row : group) {
                    Double v = KqlValues.toDouble(eval(args.getFirst(), row));
                    if (v != null) {
                        total += v;
                        n++;
                    }
                }
                if (call.name().equals("avg")) {
                    return n == 0 ? null : total / n;
                }
                return total == Math.rint(total) ? (Object) (long) total : (Object) total;
            }
            case "min", "max" -> {
                Object best = null;
                for (Map<String, Object> row : group) {
                    Object v = eval(args.getFirst(), row);
                    if (v == null) {
                        continue;
                    }
                    Integer c = best == null ? null : KqlValues.compare(v, best);
                    if (best == null || (c != null && (call.name().equals("min") ? c < 0 : c > 0))) {
                        best = v;
                    }
                }
                return best;
            }
            case "make_list", "make_set" -> {
                List<Object> values = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (Map<String, Object> row : group) {
                    Object v = eval(args.getFirst(), row);
                    if (v == null) {
                        continue;
                    }
                    if (call.name().equals("make_list") || seen.add(KqlValues.toString(v))) {
                        values.add(v);
                    }
                }
                return values;
            }
            case "any", "take_any", "arg_max", "arg_min" -> {
                if (group.isEmpty()) {
                    return null;
                }
                Map<String, Object> chosen = chosenRow(call, group);
                if (call.name().startsWith("arg_")) {
                    return args.size() > 1 ? eval(args.get(1), chosen) : eval(args.getFirst(), chosen);
                }
                return eval(args.getFirst(), chosen);
            }
            default -> throw KqlException.semantic("Unknown aggregation function '" + call.name() + "'");
        }
    }

    // ── Join ────────────────────────────────────────────────────────────────────

    private static List<Map<String, Object>> join(Join join, List<Map<String, Object>> left, List<Map<String, Object>> right) {
        String kind = join.kind();
        Map<String, List<Map<String, Object>>> rightByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : right) {
            rightByKey.computeIfAbsent(joinKey(row, join.keys(), false), k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> matchedRightKeys = new HashSet<>();
        Set<String> seenLeftKeys = new HashSet<>();
        List<String> leftColumns = columns(left);
        List<String> rightColumns = columns(right);
        for (Map<String, Object> leftRow : left) {
            String key = joinKey(leftRow, join.keys(), true);
            List<Map<String, Object>> matches = key == null ? List.of() : rightByKey.getOrDefault(key, List.of());
            if (!matches.isEmpty()) {
                matchedRightKeys.add(key);
            }
            switch (kind) {
                case "inner", "innerunique" -> {
                    // innerunique, the default, keeps the first left row of each key and every right match.
                    if (kind.equals("innerunique") && key != null && !seenLeftKeys.add(key)) {
                        continue;
                    }
                    for (Map<String, Object> rightRow : matches) {
                        out.add(merge(leftRow, rightRow, leftColumns, rightColumns));
                    }
                }
                case "leftouter" -> {
                    if (matches.isEmpty()) {
                        out.add(merge(leftRow, Map.of(), leftColumns, rightColumns));
                    } else {
                        for (Map<String, Object> rightRow : matches) {
                            out.add(merge(leftRow, rightRow, leftColumns, rightColumns));
                        }
                    }
                }
                case "leftsemi" -> {
                    if (!matches.isEmpty()) {
                        out.add(leftRow);
                    }
                }
                case "leftanti", "leftantisemi", "anti" -> {
                    if (matches.isEmpty()) {
                        out.add(leftRow);
                    }
                }
                case "fullouter" -> {
                    if (matches.isEmpty()) {
                        out.add(merge(leftRow, Map.of(), leftColumns, rightColumns));
                    } else {
                        for (Map<String, Object> rightRow : matches) {
                            out.add(merge(leftRow, rightRow, leftColumns, rightColumns));
                        }
                    }
                }
                default -> throw KqlException.semantic("Unsupported join kind '" + kind + "'");
            }
        }
        if (kind.equals("fullouter")) {
            for (Map.Entry<String, List<Map<String, Object>>> entry : rightByKey.entrySet()) {
                if (!matchedRightKeys.contains(entry.getKey())) {
                    for (Map<String, Object> rightRow : entry.getValue()) {
                        out.add(merge(Map.of(), rightRow, leftColumns, rightColumns));
                    }
                }
            }
        }
        return out;
    }

    private static String joinKey(Map<String, Object> row, List<JoinKey> keys, boolean leftSide) {
        StringBuilder sb = new StringBuilder();
        for (JoinKey key : keys) {
            Object value = eval(new Col(leftSide ? key.left() : key.right()), row);
            if (value == null) {
                return null;
            }
            sb.append(KqlValues.toString(value)).append('\u0000');
        }
        return sb.toString();
    }

    /**
     * Every left column, then every right column, named from the two inputs' schemas rather than from the
     * rows at hand, so that matched and unmatched rows share one column set; a right column whose name the
     * left side already has gets the suffix {@code 1}, and a side that did not match contributes nulls.
     */
    private static Map<String, Object> merge(Map<String, Object> left, Map<String, Object> right,
                                             List<String> leftColumns, List<String> rightColumns) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (String name : leftColumns) {
            merged.put(name, left.get(name));
        }
        for (String name : rightColumns) {
            merged.put(leftColumns.contains(name) ? name + "1" : name, right.get(name));
        }
        return merged;
    }

    // ── Expressions ─────────────────────────────────────────────────────────────

    static Object column(Map<String, Object> row, String name) {
        if (row.containsKey(name)) {
            return KqlValues.normalize(row.get(name));
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return KqlValues.normalize(entry.getValue());
            }
        }
        return null;
    }

    static Object eval(Expr expr, Map<String, Object> row) {
        return switch (expr) {
            case Lit lit -> KqlValues.normalize(lit.value());
            case Col col -> {
                Object value = column(row, col.path().getFirst());
                for (int i = 1; i < col.path().size(); i++) {
                    value = KqlValues.property(value, col.path().get(i));
                }
                yield value;
            }
            case Member member -> KqlValues.property(eval(member.target(), row), member.name());
            case Index index -> KqlValues.element(eval(index.target(), row), eval(index.index(), row));
            case Neg neg -> {
                Object v = eval(neg.expr(), row);
                yield v instanceof Long l ? -l : v instanceof Double d ? -d : null;
            }
            case Not not -> {
                Boolean b = KqlValues.toBool(eval(not.expr(), row));
                yield b == null ? null : !b;
            }
            case In in -> {
                Object value = eval(in.expr(), row);
                boolean found = false;
                for (Expr option : in.options()) {
                    Object candidate = eval(option, row);
                    if (candidate instanceof List<?> list) {
                        for (Object member : list) {
                            if (KqlValues.equal(value, member, in.ignoreCase())) {
                                found = true;
                                break;
                            }
                        }
                    } else if (KqlValues.equal(value, candidate, in.ignoreCase())) {
                        found = true;
                    }
                    if (found) {
                        break;
                    }
                }
                yield in.negated() != found;
            }
            case Bin bin -> binary(bin, row);
            case Call call -> KqlFunctions.call(call.name(), call.args(), row);
        };
    }

    private static Object binary(Bin bin, Map<String, Object> row) {
        String op = bin.op();
        if (op.equals("and")) {
            Boolean l = KqlValues.toBool(eval(bin.left(), row));
            if (Boolean.FALSE.equals(l)) {
                return false;
            }
            Boolean r = KqlValues.toBool(eval(bin.right(), row));
            return Boolean.TRUE.equals(l) && Boolean.TRUE.equals(r);
        }
        if (op.equals("or")) {
            Boolean l = KqlValues.toBool(eval(bin.left(), row));
            if (Boolean.TRUE.equals(l)) {
                return true;
            }
            return Boolean.TRUE.equals(KqlValues.toBool(eval(bin.right(), row)));
        }
        Object l = eval(bin.left(), row);
        Object r = eval(bin.right(), row);
        switch (op) {
            // A comparison with a null operand is null, as in Kusto, so that not() keeps it out of a where.
            case "==" -> {
                return l == null || r == null ? null : KqlValues.equal(l, r, false);
            }
            case "!=" -> {
                return l == null || r == null ? null : !KqlValues.equal(l, r, false);
            }
            case "=~" -> {
                return l == null || r == null ? null : KqlValues.equal(l, r, true);
            }
            case "!~" -> {
                return l == null || r == null ? null : !KqlValues.equal(l, r, true);
            }
            case "<", "<=", ">", ">=" -> {
                Integer c = KqlValues.compare(l, r);
                if (l == null || r == null || c == null) {
                    return null;
                }
                return switch (op) {
                    case "<" -> c < 0;
                    case "<=" -> c <= 0;
                    case ">" -> c > 0;
                    default -> c >= 0;
                };
            }
            case "+", "-", "*", "/", "%" -> {
                return arithmetic(op, l, r);
            }
            case "has", "has_cs", "hasprefix", "hassuffix" -> {
                return hasTerm(KqlValues.toString(l), KqlValues.toString(r), op.endsWith("_cs"), op);
            }
            case "contains", "contains_cs" -> {
                String a = KqlValues.toString(l);
                String b = KqlValues.toString(r);
                return op.endsWith("_cs") ? a.contains(b) : a.toLowerCase(Locale.ROOT).contains(b.toLowerCase(Locale.ROOT));
            }
            case "startswith", "startswith_cs" -> {
                String a = KqlValues.toString(l);
                String b = KqlValues.toString(r);
                return op.endsWith("_cs") ? a.startsWith(b) : a.toLowerCase(Locale.ROOT).startsWith(b.toLowerCase(Locale.ROOT));
            }
            case "endswith", "endswith_cs" -> {
                String a = KqlValues.toString(l);
                String b = KqlValues.toString(r);
                return op.endsWith("_cs") ? a.endsWith(b) : a.toLowerCase(Locale.ROOT).endsWith(b.toLowerCase(Locale.ROOT));
            }
            case "matches regex" -> {
                try {
                    return Pattern.compile(KqlValues.toString(r)).matcher(KqlValues.toString(l)).find();
                } catch (PatternSyntaxException e) {
                    throw KqlException.semantic("Invalid regular expression: " + e.getDescription());
                }
            }
            default -> throw KqlException.semantic("Unknown operator '" + op + "'");
        }
    }

    private static Object arithmetic(String op, Object l, Object r) {
        if (l instanceof Instant instant && r instanceof Duration duration) {
            return op.equals("+") ? instant.plus(duration) : op.equals("-") ? instant.minus(duration) : null;
        }
        if (l instanceof Instant a && r instanceof Instant b && op.equals("-")) {
            return Duration.between(b, a);
        }
        if (op.equals("+") && (l instanceof String || r instanceof String)) {
            return KqlValues.toString(l) + KqlValues.toString(r);
        }
        if (l instanceof Long a && r instanceof Long b) {
            return switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> b == 0 ? null : (Object) ((double) a / b);
                default -> b == 0 ? null : a % b;
            };
        }
        Double a = KqlValues.toDouble(l);
        Double b = KqlValues.toDouble(r);
        if (a == null || b == null) {
            return null;
        }
        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> b == 0 ? null : a / b;
            default -> b == 0 ? null : a % b;
        };
    }

    /** {@code has}: a whole term of the text, split on non-alphanumerics; prefix/suffix variants match a term's edge. */
    private static boolean hasTerm(String text, String term, boolean caseSensitive, String op) {
        if (term.isEmpty()) {
            return false;
        }
        String[] terms = text.split("[^\\p{L}\\p{N}_]+");
        for (String candidate : terms) {
            String a = caseSensitive ? candidate : candidate.toLowerCase(Locale.ROOT);
            String b = caseSensitive ? term : term.toLowerCase(Locale.ROOT);
            boolean matched = switch (op) {
                case "hasprefix" -> a.startsWith(b);
                case "hassuffix" -> a.endsWith(b);
                default -> a.equals(b);
            };
            if (matched) {
                return true;
            }
        }
        return false;
    }

    /** The distinct column names of {@code rows}, in first-seen order. */
    public static List<String> columns(List<Map<String, Object>> rows) {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            names.addAll(row.keySet());
        }
        return new ArrayList<>(names);
    }
}
