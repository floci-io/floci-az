package io.floci.az.services.iothub;

import io.floci.az.core.arm.ArmJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The credentials a hub holds for other resources: the connection strings of its file-upload storage endpoint and of
 * its routing endpoints, and the account keys of its Cosmos DB routing endpoints ({@code x-ms-secret} in the spec).
 * Azure answers connection strings with the secret masked ({@code AccountKey=****}, {@code SharedAccessKey=****}),
 * and the az CLI, which updates a hub by reading it and writing it back, sends the masked value on the next
 * {@code PUT}. A masked secret in a request therefore means "keep the stored one"; one with nothing stored to keep is
 * refused rather than stored as {@code ****}.
 */
final class Secrets {

    static final String MASK = "****";

    private static final String CONNECTION_STRING = "connectionString";
    /** The fields that are a secret whole: a Cosmos DB endpoint's account keys. */
    private static final Set<String> SECRET_FIELDS = Set.of("primaryKey", "secondaryKey");
    /** The connection-string settings that carry a secret. */
    private static final Set<String> SECRET_SETTINGS = Set.of("accountkey", "sharedaccesskey",
            "sharedaccesssignature");

    private Secrets() {
    }

    /** {@code value} with every secret in it masked. */
    static Object mask(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> masked = new LinkedHashMap<>();
            ArmJson.cast(value).forEach((key, field) -> masked.put(key, maskField(key, field)));
            return masked;
        }
        if (value instanceof List<?> list) {
            List<Object> masked = new ArrayList<>();
            list.forEach(entry -> masked.add(mask(entry)));
            return masked;
        }
        return value;
    }

    private static Object maskField(String key, Object field) {
        if (CONNECTION_STRING.equals(key) && field instanceof String text) {
            return maskConnectionString(text);
        }
        if (SECRET_FIELDS.contains(key) && field instanceof String text && !text.isEmpty()) {
            return MASK;
        }
        return mask(field);
    }

    /**
     * {@code sent} with each masked secret replaced by the one stored at the same place: the same key path, and in a
     * list the entry with the same {@code name} (an endpoint's name is unique in its hub). A masked secret with no
     * stored counterpart stays as sent, for {@link #containsMask} to find.
     */
    static Map<String, Object> restore(Map<String, Object> sent, Map<String, Object> stored) {
        Map<String, Object> restored = new LinkedHashMap<>();
        sent.forEach((key, value) -> {
            Object before = stored.get(key);
            if (CONNECTION_STRING.equals(key) && value instanceof String text && before instanceof String kept) {
                restored.put(key, unmask(text, kept));
            } else if (SECRET_FIELDS.contains(key) && MASK.equals(value) && before instanceof String kept) {
                restored.put(key, kept);
            } else {
                restored.put(key, restoreValue(value, before));
            }
        });
        return restored;
    }

    /** Whether {@code value} still holds a masked secret: one a client sent back with nothing stored to keep. */
    static boolean containsMask(Object value) {
        if (value instanceof Map<?, ?>) {
            return ArmJson.cast(value).entrySet().stream().anyMatch(entry -> {
                String key = entry.getKey();
                Object field = entry.getValue();
                if (CONNECTION_STRING.equals(key) && field instanceof String text) {
                    return masksASetting(text);
                }
                if (SECRET_FIELDS.contains(key)) {
                    return MASK.equals(field);
                }
                return containsMask(field);
            });
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(Secrets::containsMask);
        }
        return false;
    }

    private static Object restoreValue(Object value, Object stored) {
        if (value instanceof Map<?, ?> && stored instanceof Map<?, ?>) {
            return restore(ArmJson.cast(value), ArmJson.cast(stored));
        }
        if (value instanceof List<?> list && stored instanceof List<?> storedList) {
            List<Object> restored = new ArrayList<>();
            for (Object entry : list) {
                restored.add(restoreValue(entry, sameNamed(entry, storedList)));
            }
            return restored;
        }
        return value;
    }

    private static Object sameNamed(Object entry, List<?> stored) {
        Object name = ArmJson.cast(entry).get("name");
        if (name == null) {
            return null;
        }
        return stored.stream()
                .filter(candidate -> name.equals(ArmJson.cast(candidate).get("name")))
                .findFirst()
                .orElse(null);
    }

    private static String maskConnectionString(String connectionString) {
        List<String> parts = new ArrayList<>();
        for (String part : connectionString.split(";", -1)) {
            int equals = part.indexOf('=');
            parts.add(equals > 0 && SECRET_SETTINGS.contains(setting(part, equals))
                    ? part.substring(0, equals + 1) + MASK : part);
        }
        return String.join(";", parts);
    }

    private static boolean masksASetting(String connectionString) {
        for (String part : connectionString.split(";", -1)) {
            int equals = part.indexOf('=');
            if (equals > 0 && SECRET_SETTINGS.contains(setting(part, equals))
                    && MASK.equals(part.substring(equals + 1))) {
                return true;
            }
        }
        return false;
    }

    /** {@code sent} with each setting it masks taken from {@code stored}. */
    private static String unmask(String sent, String stored) {
        Map<String, String> kept = new LinkedHashMap<>();
        for (String part : stored.split(";")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                kept.put(setting(part, equals), part.substring(equals + 1));
            }
        }
        List<String> parts = new ArrayList<>();
        for (String part : sent.split(";", -1)) {
            int equals = part.indexOf('=');
            String setting = equals > 0 ? setting(part, equals) : "";
            if (equals > 0 && MASK.equals(part.substring(equals + 1)) && SECRET_SETTINGS.contains(setting)
                    && kept.containsKey(setting)) {
                parts.add(part.substring(0, equals + 1) + kept.get(setting));
            } else {
                parts.add(part);
            }
        }
        return String.join(";", parts);
    }

    private static String setting(String part, int equals) {
        return part.substring(0, equals).trim().toLowerCase(Locale.ROOT);
    }
}
