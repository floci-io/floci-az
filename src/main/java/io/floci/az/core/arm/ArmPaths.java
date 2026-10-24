package io.floci.az.core.arm;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Path helpers for ARM management-plane URLs
 * ({@code subscriptions/{sub}/resourceGroups/{rg}/providers/{ns}/{type}/{name}/...}).
 *
 * <p>Miss fallbacks are caller-supplied: services key their in-memory stores by these
 * values, so the historical per-service fallbacks ("unknown" for the ARM-plane services,
 * "default" for the sidecar services) must be preserved verbatim. Unifying them would
 * silently change storage keys for malformed paths — that is a protocol-level decision,
 * not a refactor.
 */
public final class ArmPaths {

    /** Compiled {@link #resourceName} patterns, keyed by resource type (a small, fixed set). */
    private static final ConcurrentMap<String, Pattern> RESOURCE_NAME_PATTERNS = new ConcurrentHashMap<>();

    /** {@code subscriptions/{sub}/providers/{namespace}/register} or {@code .../unregister}, in any casing. */
    private static final Pattern PROVIDER_REGISTRATION = Pattern.compile(
        "subscriptions/[^/?]+/providers/[^/?]+/(register|unregister)([?].*)?", Pattern.CASE_INSENSITIVE);

    private ArmPaths() {
    }

    /**
     * True for a subscription's register or unregister of a provider namespace ({@code Providers_Register},
     * {@code Providers_Unregister}). Its path carries the namespace's {@code /providers/Microsoft.X/} marker, so
     * the provider stage must leave it to the generic ARM handler, which owns registration for every namespace.
     */
    public static boolean isProviderRegistration(String path) {
        return path != null && PROVIDER_REGISTRATION.matcher(path).matches();
    }

    /** Segment after the case-sensitive {@code subscriptions} segment. */
    public static Optional<String> subscription(String path) {
        return segmentAfter(path, "subscriptions", false);
    }

    public static String subscription(String path, String fallback) {
        return subscription(path).orElse(fallback);
    }

    /** Segment after the case-insensitive {@code resourceGroups} segment. */
    public static Optional<String> resourceGroup(String path) {
        return segmentAfter(path, "resourcegroups", true);
    }

    public static String resourceGroup(String path, String fallback) {
        return resourceGroup(path).orElse(fallback);
    }

    /** Segment after the given case-insensitive segment. */
    public static String segmentAfter(String path, String segment, String fallback) {
        return segmentAfter(path, segment, true).orElse(fallback);
    }

    /** First path segment captured after {@code /{resourceType}/}. */
    public static Optional<String> resourceName(String path, String resourceType) {
        Pattern p = RESOURCE_NAME_PATTERNS.computeIfAbsent(resourceType,
                t -> Pattern.compile("/" + Pattern.quote(t) + "/([^/?]+)"));
        Matcher m = p.matcher(path);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    public static String resourceName(String path, String resourceType, String fallback) {
        return resourceName(path, resourceType).orElse(fallback);
    }

    /** Remainder after the last occurrence of {@code marker}, query string stripped. */
    public static String afterSegment(String path, String marker, String fallback) {
        int idx = path == null ? -1 : path.lastIndexOf(marker);
        if (idx < 0) {
            return fallback;
        }
        String rest = path.substring(idx + marker.length());
        int q = rest.indexOf('?');
        return q >= 0 ? rest.substring(0, q) : rest;
    }

    /** The {@code /providers/{namespace}/} marker of a provider namespace, in the namespace's own spelling. */
    public static String providerMarker(String namespace) {
        return "/providers/" + namespace + "/";
    }

    /**
     * Index of the first occurrence of {@code marker} in {@code path}, compared ignoring case, or -1. ARM resolves
     * provider namespaces and resource types case-insensitively, so a client may spell
     * {@code /providers/microsoft.network/} where floci-az declares {@code /providers/Microsoft.Network/}.
     */
    public static int indexOfIgnoreCase(String path, String marker) {
        if (path == null || marker == null) {
            return -1;
        }
        int last = path.length() - marker.length();
        for (int i = 0; i <= last; i++) {
            if (path.regionMatches(true, i, marker, 0, marker.length())) {
                return i;
            }
        }
        return -1;
    }

    /** True when {@code path} carries a {@code /providers/{namespace}/} segment, the namespace in any case. */
    public static boolean hasProvider(String path, String namespace) {
        return indexOfIgnoreCase(path, providerMarker(namespace)) >= 0;
    }

    /**
     * True when {@code path} carries {@code /providers/{namespace}/{type}/}, namespace and type in any case: the
     * path addresses a resource of that type or something under one.
     */
    public static boolean hasProviderType(String path, String namespace, String type) {
        return indexOfIgnoreCase(path, providerMarker(namespace) + type + "/") >= 0;
    }

    /**
     * The remainder of {@code path} after its first {@code /providers/{namespace}/} segment, the namespace matched
     * in any case, or {@code fallback} when the path carries none. The remainder keeps the request's spelling.
     */
    public static String afterProvider(String path, String namespace, String fallback) {
        String marker = providerMarker(namespace);
        int idx = indexOfIgnoreCase(path, marker);
        return idx < 0 ? fallback : path.substring(idx + marker.length());
    }

    private static Optional<String> segmentAfter(String path, String segment, boolean ignoreCase) {
        if (path == null) {
            return Optional.empty();
        }
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            boolean match = ignoreCase ? segment.equalsIgnoreCase(parts[i]) : segment.equals(parts[i]);
            if (match) {
                return Optional.of(parts[i + 1]);
            }
        }
        return Optional.empty();
    }
}
