package io.floci.az.services.policy;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A parsed Microsoft.Authorization policy path:
 * {@code {scope}/providers/Microsoft.Authorization/{type}[/{name}[/{remainder}]]}.
 *
 * <p>Policy resources are ARM extension resources, so the scope is whatever precedes the
 * <em>last</em> {@code /providers/Microsoft.Authorization/} segment: empty for tenant-rooted built-in
 * reads, a management group, a subscription, a resource group, or any resource id. The scope keeps
 * the caller's casing so it can be echoed back in resource ids; comparisons happen on
 * {@link #scopeKey()}.</p>
 *
 * @param scope     the scope path without leading or trailing slash ({@code ""} at the tenant root)
 * @param type      the policy resource type, or {@code null} when the segment is not a policy type
 * @param name      the resource name, or {@code null} for a collection request
 * @param remainder anything after the name ({@code versions/...}); empty for plain resource paths
 */
record PolicyPath(String scope, Type type, String name, String remainder) {

    private static final String MARKER = "/providers/microsoft.authorization/";
    private static final Pattern SUBSCRIPTION = Pattern.compile("subscriptions/[^/]+");
    private static final Pattern RESOURCE_GROUP = Pattern.compile("subscriptions/[^/]+/resourcegroups/[^/]+");
    private static final Pattern RESOURCE = Pattern.compile("subscriptions/[^/]+/resourcegroups/[^/]+/providers/.+");
    private static final Pattern MANAGEMENT_GROUP = Pattern.compile("providers/microsoft.management/managementgroups/[^/]+");

    enum Type {
        DEFINITION("policyDefinitions"),
        SET_DEFINITION("policySetDefinitions"),
        ASSIGNMENT("policyAssignments"),
        EXEMPTION("policyExemptions");

        final String segment;

        Type(String segment) {
            this.segment = segment;
        }

        /** The ARM resource type, e.g. {@code Microsoft.Authorization/policyDefinitions}. */
        String armType() {
            return "Microsoft.Authorization/" + segment;
        }

        static Type fromSegment(String segment) {
            for (Type type : values()) {
                if (type.segment.equalsIgnoreCase(segment)) {
                    return type;
                }
            }
            return null;
        }
    }

    enum ScopeKind { TENANT, MANAGEMENT_GROUP, SUBSCRIPTION, RESOURCE_GROUP, RESOURCE, UNKNOWN }

    /**
     * Parses a query-less request path (with or without a leading slash). Returns {@code null} when
     * the path carries no {@code /providers/Microsoft.Authorization/} segment at all.
     */
    static PolicyPath parse(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        int marker = normalized.toLowerCase(Locale.ROOT).lastIndexOf(MARKER);
        if (marker < 0) {
            return null;
        }
        String scope = stripSlashes(normalized.substring(0, marker));
        String[] rest = stripSlashes(normalized.substring(marker + MARKER.length())).split("/", 3);
        Type type = rest.length > 0 && !rest[0].isEmpty() ? Type.fromSegment(rest[0]) : null;
        String name = rest.length > 1 && !rest[1].isEmpty() ? rest[1] : null;
        String remainder = rest.length > 2 ? rest[2] : "";
        return new PolicyPath(scope, type, name, remainder);
    }

    /** True when the last Microsoft.Authorization segment of {@code path} names a policy resource type. */
    static boolean isPolicyPath(String path) {
        PolicyPath parsed = parse(stripQuery(path));
        return parsed != null && parsed.type() != null;
    }

    static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    /** Lower-cased scope for comparisons and store keys. */
    String scopeKey() {
        return scope.toLowerCase(Locale.ROOT);
    }

    ScopeKind scopeKind() {
        String key = scopeKey();
        if (key.isEmpty()) {
            return ScopeKind.TENANT;
        }
        if (MANAGEMENT_GROUP.matcher(key).matches()) {
            return ScopeKind.MANAGEMENT_GROUP;
        }
        if (SUBSCRIPTION.matcher(key).matches()) {
            return ScopeKind.SUBSCRIPTION;
        }
        if (RESOURCE_GROUP.matcher(key).matches()) {
            return ScopeKind.RESOURCE_GROUP;
        }
        if (RESOURCE.matcher(key).matches()) {
            return ScopeKind.RESOURCE;
        }
        return ScopeKind.UNKNOWN;
    }

    /** The ARM resource id of this path's resource, e.g. {@code /subscriptions/s/providers/Microsoft.Authorization/policyDefinitions/d}. */
    String resourceId() {
        return "/" + scope + "/providers/Microsoft.Authorization/" + type.segment + "/" + name;
    }

    private static String stripSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }
}
