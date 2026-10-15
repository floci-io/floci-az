package io.floci.az.services.policy.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How Resource Manager applies a PATCH to a stored resource, so that a policy judges the resource the
 * write produces rather than the fragment it carries: {@code properties} are merged recursively (a null
 * removes a property), {@code tags} are replaced as a whole, and any other top-level value is replaced.
 */
final class PatchMerge {

    private PatchMerge() {
    }

    /** The resource {@code patch} produces from {@code stored}, as a writable deep copy sharing nothing with either. */
    static Map<String, Object> apply(Map<String, Object> stored, Map<String, Object> patch) {
        Map<String, Object> merged = copy(stored);
        for (Map.Entry<String, Object> entry : copy(patch).entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                merged.remove(key);
            } else if (!key.equalsIgnoreCase("tags") && value instanceof Map<?, ?> given
                    && merged.get(key) instanceof Map<?, ?> existing) {
                merged.put(key, mergeObjects(existing, given));
            } else {
                merged.put(key, value);
            }
        }
        return merged;
    }

    /**
     * The original {@code patch} plus what the policies changed between {@code before} (the resource the patch
     * alone produces) and {@code after}: the caller's values are kept as supplied, amendments are merged in, and
     * {@code tags} travel whole when an amendment touched them, since a PATCH replaces them.
     */
    static Map<String, Object> amend(Map<String, Object> patch, Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> amendments = diff(before, after);
        if (amendments.isEmpty()) {
            return patch;
        }
        Map<String, Object> amended = copy(patch);
        for (Map.Entry<String, Object> entry : amendments.entrySet()) {
            String key = entry.getKey();
            if (key.equalsIgnoreCase("tags")) {
                amended.put(key, copy(after.get(key)));
            } else if (entry.getValue() instanceof Map<?, ?> change && amended.get(key) instanceof Map<?, ?> given) {
                amended.put(key, mergeObjects(given, change));
            } else {
                amended.put(key, entry.getValue());
            }
        }
        return amended;
    }

    /** A deep copy of JSON-shaped content: maps and lists are copied, scalars shared. */
    @SuppressWarnings("unchecked")
    static <T> T copy(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, member) -> out.put(String.valueOf(key), copy(member)));
            return (T) out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object member : list) {
                out.add(copy(member));
            }
            return (T) out;
        }
        return value;
    }

    /**
     * The PATCH that turns {@code stored} into {@code after}, in the shape {@link #apply} reads ({@code tags}
     * whole, {@code properties} as a merge).
     */
    static Map<String, Object> diff(Map<String, Object> stored, Map<String, Object> after) {
        Map<String, Object> patch = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Object previous = stored.get(key);
            if (Objects.equals(previous, value)) {
                continue;
            }
            if (!key.equalsIgnoreCase("tags") && value instanceof Map<?, ?> now && previous instanceof Map<?, ?> was) {
                patch.put(key, diffObjects(was, now));
            } else {
                patch.put(key, value);
            }
        }
        for (String key : stored.keySet()) {
            if (!after.containsKey(key)) {
                patch.put(key, null);
            }
        }
        return patch;
    }

    private static Map<String, Object> mergeObjects(Map<?, ?> existing, Map<?, ?> given) {
        Map<String, Object> merged = new LinkedHashMap<>();
        existing.forEach((key, value) -> merged.put(String.valueOf(key), value));
        for (Map.Entry<?, ?> entry : given.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value == null) {
                merged.remove(key);
            } else if (value instanceof Map<?, ?> nested && merged.get(key) instanceof Map<?, ?> current) {
                merged.put(key, mergeObjects(current, nested));
            } else {
                merged.put(key, value);
            }
        }
        return merged;
    }

    private static Map<String, Object> diffObjects(Map<?, ?> was, Map<?, ?> now) {
        Map<String, Object> patch = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : now.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            Object previous = was.get(key);
            if (Objects.equals(previous, value)) {
                continue;
            }
            if (value instanceof Map<?, ?> nested && previous instanceof Map<?, ?> current) {
                patch.put(key, diffObjects(current, nested));
            } else {
                patch.put(key, value);
            }
        }
        for (Object key : was.keySet()) {
            if (!now.containsKey(key)) {
                patch.put(String.valueOf(key), null);
            }
        }
        return patch;
    }
}
