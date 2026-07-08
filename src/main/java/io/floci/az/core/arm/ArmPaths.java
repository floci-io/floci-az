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

    private ArmPaths() {
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
