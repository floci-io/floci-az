package io.floci.az.services.authorization;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed Microsoft.Authorization role path: {@code {scope}/providers/Microsoft.Authorization/{type}[/{name}]}.
 *
 * <p>Role definitions and role assignments are ARM extension resources, so the scope is whatever precedes the
 * <em>last</em> {@code /providers/Microsoft.Authorization/} segment: empty at the tenant root (which azurerm also
 * writes as {@code //providers/...}), a management group, a subscription, a resource group or any resource id. The
 * scope keeps the caller's casing, since it is echoed in ids and in an assignment's {@code scope}; comparisons use
 * {@link #scopeKey()}.</p>
 *
 * @param scope     the scope path without leading or trailing slashes ({@code ""} at the tenant root)
 * @param type      the role resource type, or {@code null} when the segment is not one
 * @param name      the resource name, or {@code null} for a collection request
 * @param remainder anything after the name; empty for plain resource paths
 */
record RbacPath(String scope, Type type, String name, String remainder) {

    private static final String MARKER = "/providers/microsoft.authorization/";
    private static final Pattern SUBSCRIPTION = Pattern.compile("subscriptions/([^/]+)");
    private static final Pattern RESOURCE_GROUP = Pattern.compile("subscriptions/([^/]+)/resourcegroups/([^/]+)");
    private static final Pattern RESOURCE = Pattern.compile("subscriptions/([^/]+)/resourcegroups/([^/]+)/providers/.+");
    /** A resource with no resource group, directly under its subscription: a policy definition, for one. */
    private static final Pattern SUBSCRIPTION_RESOURCE = Pattern.compile("subscriptions/([^/]+)/providers/.+");
    private static final Pattern MANAGEMENT_GROUP = Pattern.compile("providers/microsoft.management/managementgroups/[^/]+");
    private static final Pattern SUBSCRIPTION_PREFIX = Pattern.compile("(?i)subscriptions/([^/]+)(?:/.*)?");

    enum Type {
        ROLE_DEFINITION("roleDefinitions"),
        ROLE_ASSIGNMENT("roleAssignments");

        final String segment;

        Type(String segment) {
            this.segment = segment;
        }

        /** The ARM resource type, e.g. {@code Microsoft.Authorization/roleAssignments}. */
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
     * Parses a query-less request path (with or without leading slashes). Returns {@code null} when the path carries
     * no {@code /providers/Microsoft.Authorization/} segment at all.
     */
    static RbacPath parse(String path) {
        String normalized = "/" + stripSlashes(path);
        int marker = normalized.toLowerCase(Locale.ROOT).lastIndexOf(MARKER);
        if (marker < 0) {
            return null;
        }
        String scope = stripSlashes(normalized.substring(0, marker));
        String[] rest = stripSlashes(normalized.substring(marker + MARKER.length())).split("/", 3);
        Type type = rest.length > 0 && !rest[0].isEmpty() ? Type.fromSegment(rest[0]) : null;
        String name = rest.length > 1 && !rest[1].isEmpty() ? rest[1] : null;
        String remainder = rest.length > 2 ? rest[2] : "";
        return new RbacPath(scope, type, name, remainder);
    }

    /** True when the last Microsoft.Authorization segment of {@code path} names a role resource type. */
    static boolean isRbacPath(String path) {
        RbacPath parsed = parse(stripQuery(path));
        // An extension resource under a role resource would belong to its own provider.
        return parsed != null && parsed.type() != null
                && !parsed.remainder().toLowerCase(Locale.ROOT).startsWith("providers/");
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
        if (RESOURCE.matcher(key).matches() || SUBSCRIPTION_RESOURCE.matcher(key).matches()) {
            return ScopeKind.RESOURCE;
        }
        return ScopeKind.UNKNOWN;
    }

    /** The subscription the scope lies in, in the caller's casing; null at tenant and management-group scope. */
    String subscriptionId() {
        Matcher m = SUBSCRIPTION_PREFIX.matcher(scope);
        return m.matches() ? m.group(1) : null;
    }

    /** The scope as an assignment's {@code scope} property and its id prefix carry it: {@code "/"} at the tenant root. */
    String scopeId() {
        return "/" + scope;
    }

    /** The ARM id of this path's resource, e.g. {@code /subscriptions/s/providers/Microsoft.Authorization/roleAssignments/g}. */
    String resourceId() {
        return (scope.isEmpty() ? "" : "/" + scope) + "/providers/Microsoft.Authorization/" + type.segment + "/" + name;
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
