package io.floci.az.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.config.EmulatorConfig.ServiceStorageConfig;
import io.floci.az.core.StoredObject;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Factory that creates {@link StorageBackend} instances based on configuration.
 * Tracks all created backends for lifecycle management.
 */
@ApplicationScoped
public class StorageFactory {

    private static final Logger LOG = Logger.getLogger(StorageFactory.class);

    static final TypeReference<Map<String, StoredObject>> TYPE_REF = new TypeReference<>() {};

    private final EmulatorConfig config;
    private final List<StorageBackend<?, ?>> allBackends = new ArrayList<>();
    // A file path identifies one logical store: callers sharing a path are expected to agree on
    // its value type and storage mode. The first create() wins; repeat calls reuse that backend.
    private final Map<Path, StorageBackend<String, StoredObject>> backendsByPath = new HashMap<>();
    private final List<HybridStorage<?, ?>> hybridBackends = new ArrayList<>();
    private final List<WalStorage<?, ?>> walBackends = new ArrayList<>();

    @Inject
    public StorageFactory(EmulatorConfig config) {
        this.config = config;
    }

    /**
     * Create a backend for the given service name (blob, queue, table).
     * Resolves storage mode by checking the service-level override first,
     * falling back to the global persistence mode.
     */
    public synchronized StorageBackend<String, StoredObject> create(String serviceName) {
        String mode           = resolveMode(serviceName);
        long flushIntervalMs  = resolveFlushInterval(serviceName);
        Path basePath         = Path.of(config.storage().persistentPath());
        Path filePath         = basePath.resolve(serviceName + ".json");

        // Reuse an existing backend for the same file. Handing out a second backend bound to the
        // same path creates a duplicate in-memory store; on shutdown the stale duplicate flushes
        // after the active instance and clobbers persisted state.
        StorageBackend<String, StoredObject> existing = backendsByPath.get(filePath);
        if (existing != null) {
            LOG.debugv("Reusing existing [{0}] storage backend: {1} (file: {2})", mode, serviceName, filePath);
            return existing;
        }

        LOG.debugv("Creating [{0}] storage backend: {1} (file: {2})", mode, serviceName, filePath);

        StorageBackend<String, StoredObject> backend = switch (mode) {
            case "memory"     -> new InMemoryStorage<>();
            case "persistent" -> new PersistentStorage<>(filePath, TYPE_REF);
            case "hybrid" -> {
                var h = new HybridStorage<>(filePath, TYPE_REF, flushIntervalMs);
                hybridBackends.add(h);
                yield h;
            }
            case "wal" -> {
                long compaction = config.storage().wal().compactionIntervalMs();
                Path snapshot   = basePath.resolve(serviceName + "-snapshot.json");
                Path wal        = basePath.resolve(serviceName + ".wal");
                var w = new WalStorage<>(snapshot, wal, TYPE_REF, compaction);
                walBackends.add(w);
                yield w;
            }
            default -> throw new IllegalArgumentException("Unknown persistence mode: " + mode);
        };

        backend.load();
        allBackends.add(backend);
        backendsByPath.put(filePath, backend);
        return backend;
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        shutdownAll();
    }

    /** Load all storage backends from disk. */
    public synchronized void loadAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.load();
        }
    }

    /** Flush all storage backends to disk. */
    public synchronized void flushAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.flush();
        }
    }

    /** Clear all storage backends. */
    public synchronized void clearAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.clear();
        }
        flushAll();
    }

    /** Shutdown all managed backends (stop schedulers, flush final state). */
    public synchronized void shutdownAll() {
        hybridBackends.forEach(HybridStorage::shutdown);
        walBackends.forEach(WalStorage::shutdown);
        flushAll();
    }

    private String resolveMode(String serviceName) {
        return serviceConfig(serviceName)
                .flatMap(ServiceStorageConfig::mode)
                .orElse(config.storage().mode());
    }

    private long resolveFlushInterval(String serviceName) {
        return serviceConfig(serviceName)
                .map(ServiceStorageConfig::flushIntervalMs)
                .orElse(config.storage().hybrid().flushIntervalMs());
    }

    private Optional<ServiceStorageConfig> serviceConfig(String serviceName) {
        return switch (serviceName) {
            case "blob"      -> Optional.of(config.storage().services().blob());
            case "queue"     -> Optional.of(config.storage().services().queue());
            case "table"     -> Optional.of(config.storage().services().table());
            case "appconfig"  -> Optional.of(config.storage().services().appConfig());
            case "cosmos"     -> Optional.of(config.storage().services().cosmos());
            case "keyvault"   -> Optional.of(config.storage().services().keyVault());
            case "servicebus" -> Optional.of(config.storage().services().serviceBus());
            case "sql"        -> Optional.of(config.storage().services().sql());
            case "monitor"    -> Optional.of(config.storage().services().monitor());
            case "containerapps" -> Optional.of(config.storage().services().containerApps());
            default           -> Optional.empty();
        };
    }
}
