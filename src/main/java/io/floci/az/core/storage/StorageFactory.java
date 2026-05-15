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
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class StorageFactory {

    private static final Logger LOG = Logger.getLogger(StorageFactory.class);

    static final TypeReference<Map<String, StoredObject>> TYPE_REF = new TypeReference<>() {};

    private final EmulatorConfig config;
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
    public StorageBackend<String, StoredObject> create(String serviceName) {
        String mode           = resolveMode(serviceName);
        long flushIntervalMs  = resolveFlushInterval(serviceName);
        Path basePath         = Path.of(config.storage().persistentPath());
        Path filePath         = basePath.resolve(serviceName + ".json");

        LOG.infov("Creating [{0}] storage backend: {1}", mode, serviceName);

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
        return backend;
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        shutdownAll();
    }

    public void shutdownAll() {
        hybridBackends.forEach(HybridStorage::shutdown);
        walBackends.forEach(WalStorage::shutdown);
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
            case "blob"  -> Optional.of(config.storage().services().blob());
            case "queue" -> Optional.of(config.storage().services().queue());
            case "table" -> Optional.of(config.storage().services().table());
            default      -> Optional.empty();
        };
    }
}
