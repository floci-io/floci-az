package io.floci.az.services.keyvault;

import io.floci.az.core.arm.ArmJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code Vaults_UpdateAccessPolicy}'s three operation kinds over a vault's {@code properties.accessPolicies}. An entry
 * is identified by its {@code tenantId}, {@code objectId} and {@code applicationId}, compared ignoring case.
 *
 * <ul>
 *   <li>{@code add} merges each listed entry's permissions into the matching entry, or appends the entry</li>
 *   <li>{@code replace} replaces each listed entry's permissions on the matching entry, or appends the entry, and
 *       leaves every other entry as it is</li>
 *   <li>{@code remove} removes each listed entry's permissions from the matching entry, and drops an entry left
 *       with no permissions</li>
 * </ul>
 */
final class VaultAccessPolicies {

    enum Kind {
        ADD, REPLACE, REMOVE;

        /** The kind a path segment names, in any case; empty for anything else. */
        static Optional<Kind> of(String segment) {
            for (Kind kind : values()) {
                if (kind.name().equalsIgnoreCase(segment)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    private VaultAccessPolicies() {
    }

    /** The entries a request body lists under {@code properties.accessPolicies}; none when it lists nothing. */
    static List<Map<String, Object>> requested(Map<String, Object> body) {
        if (!(body.get("properties") instanceof Map<?, ?> properties)
                || !(properties.get("accessPolicies") instanceof List<?> entries)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> map) {
                result.add(normalized(ArmJson.cast(map)));
            }
        }
        return result;
    }

    /** The vault's list after applying {@code requested} as {@code kind} to {@code current}. */
    static List<Map<String, Object>> apply(Kind kind, List<Map<String, Object>> current,
                                           List<Map<String, Object>> requested) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> entry : current) {
            result.add(normalized(entry));
        }
        for (Map<String, Object> change : requested) {
            int index = indexOf(result, change);
            if (kind == Kind.REPLACE) {
                if (index < 0) {
                    result.add(change);
                } else {
                    result.set(index, withPermissions(result.get(index), permissions(change)));
                }
            } else if (kind == Kind.ADD) {
                if (index < 0) {
                    result.add(change);
                } else {
                    result.set(index, withPermissions(result.get(index), merge(result.get(index), change, true)));
                }
            } else if (index >= 0) {
                Map<String, List<String>> left = merge(result.get(index), change, false);
                if (left.isEmpty()) {
                    result.remove(index);
                } else {
                    result.set(index, withPermissions(result.get(index), left));
                }
            }
        }
        return List.copyOf(result);
    }

    private static int indexOf(List<Map<String, Object>> entries, Map<String, Object> target) {
        for (int i = 0; i < entries.size(); i++) {
            if (sameIdentity(entries.get(i), target)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean sameIdentity(Map<String, Object> a, Map<String, Object> b) {
        return equalsIgnoreCase(a.get("tenantId"), b.get("tenantId"))
            && equalsIgnoreCase(a.get("objectId"), b.get("objectId"))
            && equalsIgnoreCase(a.get("applicationId"), b.get("applicationId"));
    }

    private static boolean equalsIgnoreCase(Object a, Object b) {
        String left = a == null ? "" : String.valueOf(a);
        String right = b == null ? "" : String.valueOf(b);
        return left.equalsIgnoreCase(right);
    }

    /**
     * The permissions of {@code entry} with {@code change}'s added ({@code add} true) or taken away, per category,
     * values compared ignoring case. A category left empty is dropped; on removal, so is the whole map when nothing
     * is left.
     */
    private static Map<String, List<String>> merge(Map<String, Object> entry, Map<String, Object> change,
                                                   boolean add) {
        Map<String, List<String>> result = new LinkedHashMap<>(permissions(entry));
        for (Map.Entry<String, List<String>> category : permissions(change).entrySet()) {
            List<String> values = new ArrayList<>(result.getOrDefault(category.getKey(), List.of()));
            for (String value : category.getValue()) {
                boolean present = values.stream().anyMatch(existing -> existing.equalsIgnoreCase(value));
                if (add && !present) {
                    values.add(value);
                } else if (!add) {
                    values.removeIf(existing -> existing.equalsIgnoreCase(value));
                }
            }
            if (values.isEmpty()) {
                result.remove(category.getKey());
            } else {
                result.put(category.getKey(), List.copyOf(values));
            }
        }
        return result;
    }

    private static Map<String, List<String>> permissions(Map<String, Object> entry) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (entry.get("permissions") instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> category : map.entrySet()) {
                if (category.getValue() instanceof List<?> values) {
                    List<String> unique = new ArrayList<>();
                    for (Object value : values) {
                        String text = value == null ? null : String.valueOf(value);
                        if (text != null && unique.stream().noneMatch(seen -> seen.equalsIgnoreCase(text))) {
                            unique.add(text);
                        }
                    }
                    if (!unique.isEmpty()) {
                        result.put(String.valueOf(category.getKey()), List.copyOf(unique));
                    }
                }
            }
        }
        return result;
    }

    private static Map<String, Object> withPermissions(Map<String, Object> entry, Map<String, List<String>> permissions) {
        Map<String, Object> result = new LinkedHashMap<>(entry);
        result.put("permissions", new LinkedHashMap<>(permissions));
        return result;
    }

    /** The entry's identity fields and its permissions, in the order the spec's example lists them. */
    private static Map<String, Object> normalized(Map<String, Object> entry) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("tenantId", "objectId", "applicationId")) {
            if (entry.get(field) != null) {
                result.put(field, String.valueOf(entry.get(field)));
            }
        }
        result.put("permissions", new LinkedHashMap<>(permissions(entry)));
        return result;
    }
}
