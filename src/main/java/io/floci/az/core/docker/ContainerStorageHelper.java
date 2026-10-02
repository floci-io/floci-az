package io.floci.az.core.docker;

import io.floci.az.config.EmulatorConfig;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central helper for Docker resource naming, labelling and child-container volume management.
 *
 * <p>Naming convention (shared across the Floci emulators): every emulator-created container
 * and volume is named {@code floci-az-[<namespace>-]<service>-<id>} and labelled so it is
 * attributable to exactly one emulator by name and by label. The prefix is owned here —
 * call sites pass bare {@code <service>-<id>} tokens, never the prefix.</p>
 *
 * <p>Volume storage modes:</p>
 * <ul>
 *   <li>Named-volume (default) — floci-az manages per-resource Docker named volumes.
 *       Active when {@code FLOCI_AZ_STORAGE_HOST_PERSISTENT_PATH} is not set to an absolute path.</li>
 *   <li>Host-path (legacy) — active when {@code host-persistent-path} is set to an absolute path;
 *       callers use bind-mounts to the specified directory instead.</li>
 * </ul>
 */
public final class ContainerStorageHelper {

    private static final Logger LOG = Logger.getLogger(ContainerStorageHelper.class);

    static final String CLOUD = "az";
    static final String CONTAINER_PREFIX = "floci-" + CLOUD + "-";
    static final String LEGACY_PREFIX = "floci-";

    /** Cloud discriminator shared by every Floci emulator; its value is {@code az} here. */
    public static final String CLOUD_LABEL = "io.floci";
    public static final String SERVICE_LABEL = "io.floci.service";
    public static final String RESOURCE_ID_LABEL = "io.floci.resource-id";
    public static final String SUBSCRIPTION_LABEL = "io.floci.subscription";
    public static final String RESOURCE_GROUP_LABEL = "io.floci.resource-group";
    public static final String LOCATION_LABEL = "io.floci.location";

    /**
     * New label key to the legacy key it replaces. Writers set only the new key; the container
     * create path stamps the legacy alias next to it with the same value, and readers resolve
     * through {@link #labelValue}. This is the only place a legacy key is spelled.
     */
    private static final Map<String, String> LEGACY_LABEL_ALIASES = Map.of(
            SERVICE_LABEL, "floci_service");

    private ContainerStorageHelper() {}

    /**
     * Canonical volume/container name for a resource.
     * Uses {@code volumeId} when set; falls back to {@code fallbackId} for legacy resources.
     */
    public static String resourceName(EmulatorConfig config, String service, String volumeId, String fallbackId) {
        return dockerName(config, service + "-" + (volumeId != null ? volumeId : fallbackId));
    }

    /**
     * Prefixes {@code baseName} with {@code floci-az-} and the configured resource namespace.
     * Accepts already-prefixed names (current or legacy {@code floci-} prefix) and normalises
     * them, so the namespace always lands between the cloud token and the service token.
     */
    public static String dockerName(EmulatorConfig config, String baseName) {
        String base = stripPrefix(baseName);
        String namespace = resourceNamespace(config);
        if (namespace.isBlank()) {
            return CONTAINER_PREFIX + base;
        }
        return CONTAINER_PREFIX + namespace + "-" + base;
    }

    /**
     * Labels applied to every emulator-created container and volume:
     * {@code floci=true} (umbrella across all Floci emulators),
     * {@code floci_emulator=floci-az} (per-emulator discriminator), and
     * {@code floci_namespace} when a resource namespace is configured.
     */
    public static Map<String, String> defaultLabels(EmulatorConfig config) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("floci", "true");
        labels.put("floci_emulator", "floci-" + CLOUD);
        String namespace = resourceNamespace(config);
        if (!namespace.isBlank()) {
            labels.put("floci_namespace", namespace);
        }
        return labels;
    }

    /**
     * Labels tying a container to the emulated Azure resource it backs, merged into a container
     * spec's labels (never into {@link #defaultLabels}). The key set is shared with the other
     * Floci emulators; subscription, resource group and location are the Azure scope keys. A
     * {@code null} or blank value omits its key, so shared singletons pass no resource id.
     */
    public static Map<String, String> resourceIdentityLabels(String service, String resourceId,
                                                             String subscription, String resourceGroup,
                                                             String location) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(CLOUD_LABEL, CLOUD);
        putIfNotBlank(labels, SERVICE_LABEL, service);
        putIfNotBlank(labels, RESOURCE_ID_LABEL, resourceId);
        putIfNotBlank(labels, SUBSCRIPTION_LABEL, subscription);
        putIfNotBlank(labels, RESOURCE_GROUP_LABEL, resourceGroup);
        putIfNotBlank(labels, LOCATION_LABEL, location);
        return labels;
    }

    /** The new-key to legacy-key alias table. */
    public static Map<String, String> legacyLabelAliases() {
        return LEGACY_LABEL_ALIASES;
    }

    /**
     * Returns a copy of {@code labels} with the legacy alias of every aliased new key it carries,
     * set to the new key's value, so the two can never drift at the source.
     */
    public static Map<String, String> withLegacyAliases(Map<String, String> labels) {
        Map<String, String> result = new LinkedHashMap<>(labels);
        LEGACY_LABEL_ALIASES.forEach((newKey, legacyKey) -> {
            String value = labels.get(newKey);
            if (value != null) {
                result.put(legacyKey, value);
            }
        });
        return result;
    }

    /**
     * Reads a label by its new key, falling back to the legacy alias when the new key is absent,
     * so containers created before the new keys existed are still recognised.
     */
    public static String labelValue(Map<String, String> labels, String newKey) {
        if (labels == null) {
            return null;
        }
        String value = labels.get(newKey);
        if (value != null) {
            return value;
        }
        String legacyKey = LEGACY_LABEL_ALIASES.get(newKey);
        return legacyKey == null ? null : labels.get(legacyKey);
    }

    /**
     * True when {@code labels} carries both {@code newKey} and its legacy alias with different
     * values. Floci always writes them equal, so a disagreement means something else relabelled
     * the container; callers that act on the label leave such a container alone.
     */
    public static boolean legacyAliasDisagrees(Map<String, String> labels, String newKey) {
        String legacyKey = LEGACY_LABEL_ALIASES.get(newKey);
        if (labels == null || legacyKey == null) {
            return false;
        }
        String value = labels.get(newKey);
        String legacyValue = labels.get(legacyKey);
        return value != null && legacyValue != null && !value.equals(legacyValue);
    }

    private static void putIfNotBlank(Map<String, String> labels, String key, String value) {
        if (value != null && !value.isBlank()) {
            labels.put(key, value);
        }
    }

    private static String stripPrefix(String baseName) {
        if (baseName.startsWith(CONTAINER_PREFIX)) {
            return baseName.substring(CONTAINER_PREFIX.length());
        }
        if (baseName.startsWith(LEGACY_PREFIX)) {
            return baseName.substring(LEGACY_PREFIX.length());
        }
        return baseName;
    }

    private static String resourceNamespace(EmulatorConfig config) {
        if (config == null || config.docker() == null || config.docker().resourceNamespace() == null) {
            return "";
        }
        return sanitizeNamePart(config.docker().resourceNamespace().orElse(""));
    }

    private static String sanitizeNamePart(String value) {
        String cleaned = value.trim().replaceAll("[^A-Za-z0-9_.-]+", "-");
        while (cleaned.startsWith("-")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("-")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.equals(".") || cleaned.equals("..")) {
            return "";
        }
        return cleaned;
    }

    /**
     * Returns {@code true} when named-volume mode is active.
     * Returns {@code false} only when {@code host-persistent-path} is an absolute path,
     * indicating the caller should use a host bind-mount instead.
     */
    public static boolean isNamedVolumeMode(EmulatorConfig config) {
        return !config.storage().hostPersistentPath().startsWith("/");
    }

    /**
     * Returns whether the given volume should be removed on resource delete,
     * honouring the configured prune policy.
     *
     * - In {@code memory} storage mode: always prune (data cannot survive a restart anyway).
     * - In persistent modes: prune only when {@code prune-volumes-on-delete: true}.
     */
    public static boolean shouldPruneVolume(EmulatorConfig config) {
        return "memory".equals(config.storage().mode()) || config.storage().pruneVolumesOnDelete();
    }

    /**
     * Ensures the host data directory exists for host-path mode (absolute paths only).
     */
    public static void ensureHostDir(String hostDataPath) {
        try {
            Files.createDirectories(Path.of(hostDataPath));
        } catch (IOException e) {
            LOG.errorv("Failed to create data directory {0}: {1}", hostDataPath, e.getMessage());
        }
    }
}
