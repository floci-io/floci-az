package io.floci.az.core.arm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A parsed ARM path: {@code /subscriptions/{sub}/resourceGroups/{rg}/providers/{ns}/{type}/{name}[/{type}/{name}]...}.
 *
 * <p>The grammar is the one ARM uses for every resource id:
 * <ul>
 *   <li>an optional {@code subscriptions/{sub}} root, then an optional {@code resourceGroups/{rg}}; both
 *       keywords are matched case-insensitively, since clients send {@code resourcegroups} too</li>
 *   <li>a {@code providers/{namespace}} section followed by alternating type and name segments; nested
 *       children simply continue the alternation ({@code storageAccounts/a/blobServices/default})</li>
 *   <li>an extension resource is a second {@code providers/{namespace}} section after a complete
 *       resource: everything before it is the {@link #scope()} it extends</li>
 *   <li>a path that ends on a type segment addresses a collection or an action
 *       ({@code storageAccounts}, {@code storageAccounts/a/listKeys}); see {@link #isCollection()}</li>
 * </ul>
 *
 * <p>Built-in segments outside a provider section ({@code subscriptions/{sub}/locations},
 * {@code subscriptions/{sub}/resourcegroups}, {@code tenants}) parse as types with a null
 * {@link #providerNamespace()}. The parser never rejects a path that starts at an ARM root; whether the
 * addressed type exists is the type registry's question, not this one's.
 *
 * <p>A query string and leading or trailing slashes are dropped, and empty segments are skipped.
 * {@link #id()} keeps the client's casing, which is what responses echo; {@link #canonicalKey()} is the
 * lowercase form used to compare and store ids, because ARM ids are case-insensitive.
 *
 * @param id                the path as an ARM id: a leading slash, original casing, no query
 * @param subscriptionId    the subscription, or null for a tenant-level path
 * @param resourceGroup     the resource group, or null when the path names none
 * @param providerNamespace the namespace of the last provider section, or null when there is none
 * @param types             the type segments of the last section, in order
 * @param names             the name segments of the last section; one shorter than {@code types} for a collection
 * @param scope             what the last provider section sits under (a resource group, a subscription, or
 *                          the resource an extension extends), or null at tenant level or without a provider section
 */
public record ArmResourceId(String id, String subscriptionId, String resourceGroup, String providerNamespace,
                            List<String> types, List<String> names, ArmResourceId scope) {

    private static final String SUBSCRIPTIONS = "subscriptions";
    private static final String RESOURCE_GROUPS = "resourceGroups";
    private static final String PROVIDERS = "providers";
    private static final String TENANTS = "tenants";

    public ArmResourceId {
        types = List.copyOf(types);
        names = List.copyOf(names);
    }

    /**
     * Parses an ARM path, with or without a leading slash or a query string. Empty for a null or blank
     * path and for one that does not start at an ARM root ({@code subscriptions}, {@code providers},
     * {@code tenants}).
     */
    public static Optional<ArmResourceId> parse(String path) {
        if (path == null) {
            return Optional.empty();
        }
        List<String> segments = segments(path);
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        String root = segments.getFirst();
        if (!root.equalsIgnoreCase(SUBSCRIPTIONS) && !root.equalsIgnoreCase(PROVIDERS)
                && !root.equalsIgnoreCase(TENANTS)) {
            return Optional.empty();
        }
        return Optional.of(fromSegments(segments));
    }

    /** {@link #id()} lowercased: the key under which two spellings of one id compare equal. */
    public String canonicalKey() {
        return id.toLowerCase(Locale.ROOT);
    }

    /** The last name segment, or null for a collection, a provider namespace or a bare scope. */
    public String name() {
        return names.size() == types.size() && !names.isEmpty() ? names.getLast() : null;
    }

    /**
     * The resource type of the last provider section, such as {@code Microsoft.Storage/storageAccounts/blobServices},
     * or null when the path has no provider section or names no type under it.
     */
    public String typePath() {
        if (providerNamespace == null || types.isEmpty()) {
            return null;
        }
        return providerNamespace + "/" + String.join("/", types);
    }

    /** True when the path ends on a type segment: a collection to list, or an action on the resource before it. */
    public boolean isCollection() {
        return types.size() > names.size();
    }

    /**
     * True when the last provider section extends another provider resource
     * ({@code .../storageAccounts/a/providers/Microsoft.Insights/diagnosticSettings/d}). Extension resources
     * at subscription or resource-group scope look like ordinary resources syntactically; telling those
     * apart needs the type descriptor.
     */
    public boolean isExtension() {
        return scope != null && scope.providerNamespace() != null;
    }

    /**
     * The id one level up: a nested child's parent resource, a resource's scope (its resource group, its
     * subscription, or the resource it extends), a resource group's subscription. A collection's parent is
     * the resource or scope it lists under. Null at the top: a subscription, a tenant-level resource, a root collection.
     */
    public ArmResourceId parent() {
        List<String> segments = segments(id);
        if (!types.isEmpty()) {
            if (types.size() == 1 && providerNamespace != null) {
                return scope;
            }
            int keep = segments.size() - (isCollection() ? 1 : 2);
            return keep <= 0 ? null : fromSegments(segments.subList(0, keep));
        }
        if (providerNamespace != null) {
            return scope;
        }
        if (resourceGroup != null) {
            return fromSegments(segments.subList(0, 2));
        }
        return null;
    }

    private static List<String> segments(String path) {
        String withoutQuery = path;
        int query = withoutQuery.indexOf('?');
        if (query >= 0) {
            withoutQuery = withoutQuery.substring(0, query);
        }
        List<String> segments = new ArrayList<>();
        for (String segment : withoutQuery.split("/")) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private static ArmResourceId fromSegments(List<String> segments) {
        int n = segments.size();
        int i = 0;
        String subscription = null;
        String resourceGroup = null;
        if (n > 1 && segments.get(0).equalsIgnoreCase(SUBSCRIPTIONS)) {
            subscription = segments.get(1);
            i = 2;
            if (n > 3 && segments.get(2).equalsIgnoreCase(RESOURCE_GROUPS)) {
                resourceGroup = segments.get(3);
                i = 4;
            }
        }

        int sectionStart = -1;
        String namespace = null;
        List<String> types = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (i < n) {
            String segment = segments.get(i);
            boolean atTypePosition = types.size() == names.size();
            if (atTypePosition && segment.equalsIgnoreCase(PROVIDERS) && i + 1 < n) {
                sectionStart = i;
                namespace = segments.get(i + 1);
                types.clear();
                names.clear();
                i += 2;
                continue;
            }
            if (atTypePosition) {
                types.add(segment);
            } else {
                names.add(segment);
            }
            i++;
        }

        ArmResourceId scope = sectionStart > 0 ? fromSegments(segments.subList(0, sectionStart)) : null;
        return new ArmResourceId("/" + String.join("/", segments), subscription, resourceGroup, namespace,
                types, names, scope);
    }

    @Override
    public String toString() {
        return id;
    }
}
