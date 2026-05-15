package io.floci.az.core.docker;

import io.floci.az.config.EmulatorConfig;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Central helper for child-container volume management.
 *
 * Two modes:
 * - Named-volume (default) — floci-az manages per-resource Docker named volumes.
 *   Active when {@code FLOCI_AZ_STORAGE_HOST_PERSISTENT_PATH} is not set to an absolute path.
 * - Host-path (legacy) — active when {@code host-persistent-path} is set to an absolute path;
 *   callers use bind-mounts to the specified directory instead.
 */
public final class ContainerStorageHelper {

    private static final Logger LOG = Logger.getLogger(ContainerStorageHelper.class);

    private ContainerStorageHelper() {}

    /**
     * Canonical volume/container name for a resource.
     * Uses {@code volumeId} when set; falls back to {@code fallbackId} for legacy resources.
     */
    public static String resourceName(String service, String volumeId, String fallbackId) {
        return "floci-az-" + service + "-" + (volumeId != null ? volumeId : fallbackId);
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
