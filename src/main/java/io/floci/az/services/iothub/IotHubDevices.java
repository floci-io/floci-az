package io.floci.az.services.iothub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.iothub.IotHubModels.Device;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The identity registries of the hubs: each hub's devices, kept apart from every other hub's.
 *
 * <p>The devices share the {@code iothub} backend with the ARM resources, so they follow its storage mode. Their keys
 * are {@code dev}, the cloud, the hub, the hub's incarnation and the device ID, separated by null bytes; no ARM id
 * starts that way. The cloud and the hub match ignoring case, as Azure matches host names, and the device ID as sent:
 * Azure's IDs are case-sensitive. The incarnation ties a device to the hub that registered it: a hub created again
 * under the name has another, so it never sees a device its predecessor left, even one a restart kept from the
 * predecessor's cleanup.
 */
@ApplicationScoped
public class IotHubDevices {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String STORE = "iothub";
    /** A null byte cannot appear in a hub name or a device ID, so it separates the parts of a key safely. */
    private static final char SEP = '\0';
    private static final String KEY_PREFIX = "dev" + SEP;

    /**
     * How many devices a hub holds, by status.
     *
     * @param total    every device
     * @param enabled  the devices that may connect
     * @param disabled the devices that may not
     */
    public record Counts(int total, int enabled, int disabled) {
    }

    /**
     * One hub's registry: the hub as one incarnation of its name.
     *
     * @param cloud       the cloud the hub lives in
     * @param hub         the hub name
     * @param incarnation the hub's incarnation, chosen when it was created
     */
    public record Registry(String cloud, String hub, int incarnation) {
    }

    private final StorageBackend<String, StoredObject> storage;
    /** Guards a read of a device followed by its write, so the ETag conditions and the write are one step. */
    private final Object writeLock = new Object();

    @Inject
    public IotHubDevices(StorageFactory storageFactory) {
        this.storage = storageFactory.create(STORE);
    }

    /** The lock a check-then-write of a device takes. */
    Object writeLock() {
        return writeLock;
    }

    /** The device {@code deviceId} of {@code registry}. */
    public Optional<Device> get(Registry registry, String deviceId) {
        return storage.get(key(registry, deviceId)).map(IotHubDevices::decode);
    }

    /** Stores {@code device} in {@code registry}, replacing the one with its ID. */
    void put(Registry registry, Device device) {
        String key = key(registry, device.deviceId());
        try {
            storage.put(key, new StoredObject(key, MAPPER.writeValueAsBytes(device), Map.of(), Instant.now(),
                    device.etag()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A device identity could not be written as JSON", e);
        }
    }

    /** Removes the device {@code deviceId} of {@code registry}. */
    void delete(Registry registry, String deviceId) {
        storage.delete(key(registry, deviceId));
    }

    /** The devices of {@code registry}, by device ID, in ordinal order so that paging is stable. */
    public List<Device> list(Registry registry) {
        String prefix = prefix(registry);
        return storage.scan(key -> key.startsWith(prefix)).stream()
                .map(IotHubDevices::decode)
                .sorted(Comparator.comparing(Device::deviceId))
                .toList();
    }

    /** How many devices {@code registry} holds, by status. */
    public Counts counts(Registry registry) {
        List<Device> devices = list(registry);
        int enabled = (int) devices.stream().filter(Device::enabled).count();
        return new Counts(devices.size(), enabled, devices.size() - enabled);
    }

    /**
     * Forgets the devices of every hub named {@code hub} in {@code cloud}: the hub was deleted. Any incarnation's go,
     * so the devices an earlier hub of the name left behind go with them.
     */
    public void deleteAll(String cloud, String hub) {
        deleteKeysStartingWith(namePrefix(cloud, hub));
    }

    /** Forgets every device: {@code POST /_admin/reset}. */
    public void clear() {
        deleteKeysStartingWith(KEY_PREFIX);
    }

    /** Deletes in one batch, so a persistent backend saves once rather than once per device. */
    private void deleteKeysStartingWith(String prefix) {
        synchronized (writeLock) {
            Set<String> doomed = storage.keys().stream()
                    .filter(key -> key.startsWith(prefix))
                    .collect(Collectors.toSet());
            if (!doomed.isEmpty()) {
                storage.applyBatch(Map.of(), doomed);
            }
        }
    }

    private static String namePrefix(String cloud, String hub) {
        return KEY_PREFIX + cloud.toLowerCase(Locale.ROOT) + SEP + hub.toLowerCase(Locale.ROOT) + SEP;
    }

    private static String prefix(Registry registry) {
        return namePrefix(registry.cloud(), registry.hub()) + registry.incarnation() + SEP;
    }

    private static String key(Registry registry, String deviceId) {
        return prefix(registry) + deviceId;
    }

    private static Device decode(StoredObject stored) {
        try {
            return MAPPER.readValue(stored.data(), Device.class);
        } catch (IOException e) {
            throw new UncheckedIOException("The stored device " + stored.key() + " is not readable", e);
        }
    }
}
