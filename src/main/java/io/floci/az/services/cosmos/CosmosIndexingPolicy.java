package io.floci.az.services.cosmos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Container indexing-policy handling for the Cosmos DB SQL API emulator.
 *
 * <p>Normalizes a client-supplied {@code indexingPolicy} the way Azure does on
 * container create/replace (fills defaults, lowercases enum values, defaults
 * composite-index path order to {@code ascending}) and implements the Azure
 * rule that an {@code ORDER BY} over two or more properties can only be served
 * by a composite index that matches the clause exactly:
 * <ul>
 *   <li>the composite index paths must match the ORDER BY properties in the
 *       same sequence and with the same number of paths, and</li>
 *   <li>the sort directions must match either exactly or exactly inverted
 *       on all paths.</li>
 * </ul>
 *
 * @see <a href="https://learn.microsoft.com/en-us/azure/cosmos-db/index-policy#composite-indexes">
 *      Azure Cosmos DB composite indexes</a>
 */
final class CosmosIndexingPolicy {

    private CosmosIndexingPolicy() {}

    /** Azure's default indexing policy for new containers. */
    static Map<String, Object> defaultPolicy() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("automatic",     true);
        p.put("indexingMode",  "consistent");
        p.put("includedPaths", List.of(Map.of("path", "/*")));
        p.put("excludedPaths", List.of(Map.of("path", "/\"_etag\"/?")));
        return p;
    }

    /**
     * Normalize a client-supplied indexing policy.  A missing or non-object
     * policy yields the default policy.
     *
     * @throws IllegalArgumentException when the policy is invalid — callers
     *   translate this into a 400 BadRequest
     */
    static Map<String, Object> normalize(Object raw) {
        if (!(raw instanceof Map<?, ?> in)) return defaultPolicy();

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("automatic", !(in.get("automatic") instanceof Boolean b) || b);

        String mode = in.get("indexingMode") instanceof String s ? s.toLowerCase(Locale.ROOT) : "consistent";
        if (!"consistent".equals(mode) && !"lazy".equals(mode) && !"none".equals(mode)) {
            throw new IllegalArgumentException("The indexing mode '" + mode + "' is invalid. "
                    + "Valid values are: 'consistent', 'lazy', 'none'.");
        }
        p.put("indexingMode", mode);

        p.put("includedPaths", in.get("includedPaths") instanceof List<?> inc
                ? List.copyOf(inc) : List.of(Map.of("path", "/*")));
        p.put("excludedPaths", in.get("excludedPaths") instanceof List<?> exc
                ? List.copyOf(exc) : List.of(Map.of("path", "/\"_etag\"/?")));

        List<List<Map<String, Object>>> composites = normalizeComposites(in.get("compositeIndexes"));
        if (!composites.isEmpty()) p.put("compositeIndexes", composites);
        return p;
    }

    private static List<List<Map<String, Object>>> normalizeComposites(Object raw) {
        if (!(raw instanceof List<?> outer)) return List.of();
        List<List<Map<String, Object>>> result = new ArrayList<>();
        for (Object idx : outer) {
            if (!(idx instanceof List<?> paths)) {
                throw new IllegalArgumentException("Each composite index must be an array of paths.");
            }
            if (paths.size() < 2) {
                throw new IllegalArgumentException(
                        "The indexing policy is invalid. A composite index must have at least 2 paths.");
            }
            List<Map<String, Object>> normPaths = new ArrayList<>();
            for (Object entry : paths) {
                if (!(entry instanceof Map<?, ?> pm)
                        || !(pm.get("path") instanceof String path) || !path.startsWith("/")) {
                    throw new IllegalArgumentException(
                            "Each composite index path must be an object with a 'path' starting with '/'.");
                }
                String order = pm.get("order") instanceof String o ? o.toLowerCase(Locale.ROOT) : "ascending";
                if (!"ascending".equals(order) && !"descending".equals(order)) {
                    throw new IllegalArgumentException(
                            "Composite index path order must be 'ascending' or 'descending'.");
                }
                Map<String, Object> norm = new LinkedHashMap<>();
                norm.put("path",  path);
                norm.put("order", order);
                normPaths.add(norm);
            }
            result.add(normPaths);
        }
        return result;
    }

    /**
     * Whether the container's indexing policy can serve the given ORDER BY
     * clause.  Single-property ORDER BY is always served by the default range
     * index; two or more properties require an exactly-matching composite
     * index (same paths, same sequence, same length; directions matching
     * exactly or all-inverted).
     */
    static boolean supportsOrderBy(Object policy, List<CosmosQueryEngine.OrderByField> orderBy) {
        if (orderBy.size() < 2) return true;
        if (!(policy instanceof Map<?, ?> pm)) return false;
        if (!(pm.get("compositeIndexes") instanceof List<?> composites)) return false;

        List<String>  wantPaths = orderBy.stream()
                .map(f -> normalizeOrderByPath(f.path())).toList();
        List<Boolean> wantAsc   = orderBy.stream()
                .map(CosmosQueryEngine.OrderByField::asc).toList();

        for (Object idx : composites) {
            if (!(idx instanceof List<?> paths) || paths.size() != orderBy.size()) continue;
            if (matches(paths, wantPaths, wantAsc, false)
                    || matches(paths, wantPaths, wantAsc, true)) return true;
        }
        return false;
    }

    private static boolean matches(List<?> compositePaths, List<String> wantPaths,
            List<Boolean> wantAsc, boolean inverted) {
        for (int i = 0; i < compositePaths.size(); i++) {
            if (!(compositePaths.get(i) instanceof Map<?, ?> pm)) return false;
            if (!(pm.get("path") instanceof String rawPath)
                    || !wantPaths.get(i).equals(normalizeCompositePath(rawPath))) return false;
            boolean asc = !"descending".equalsIgnoreCase(String.valueOf(pm.get("order")));
            if ((inverted ? !asc : asc) != wantAsc.get(i).booleanValue()) return false;
        }
        return true;
    }

    /** {@code "c.a.b"} → {@code "/a/b"} — strip the FROM alias, dots become slashes. */
    static String normalizeOrderByPath(String expr) {
        String path = expr.trim();
        int dot = path.indexOf('.');
        if (dot > 0 && path.substring(0, dot).matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            path = path.substring(dot + 1);
        }
        return "/" + String.join("/", path.split("\\."));
    }

    /** {@code '/a/"b-c"/'} → {@code "/a/b-c"} — strip quote escapes and a trailing slash. */
    static String normalizeCompositePath(String path) {
        String p = path.trim().replace("\"", "");
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
