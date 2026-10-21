package io.floci.az.core.directory;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.Resettable;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Whether each subscription is registered with each resource provider namespace, keyed case-insensitively by
 * subscription and namespace, as ARM matches them.
 *
 * <p>Only a register or an unregister is recorded; a pair never touched has the default {@link RegistrationState}.
 * That default is {@code Registered}, so every served namespace works without a register call, as floci-az always has, unless
 * {@link EmulatorConfig#strictProviders()} is on: then it is {@code NotRegistered}, as in a new Azure
 * subscription, and the recorded states are also written through to the {@link AzureDirectory#STORE directory}
 * store, so a registration survives a restart alongside the subscription it belongs to.
 */
@ApplicationScoped
public class ProviderRegistrations implements Resettable {

    private static final Logger LOG = Logger.getLogger(ProviderRegistrations.class);
    private static final String PREFIX = "providerregistration/";
    private static final String REGISTRATION_FREE = "Microsoft.Resources";

    /** Null when registrations are memory-only. */
    private final StorageBackend<String, StoredObject> store;
    private final boolean registeredByDefault;

    /** Guarded by {@code this}; keyed by {@link #key}, true for registered. */
    private final Map<String, Boolean> recorded = new HashMap<>();

    @Inject
    public ProviderRegistrations(EmulatorConfig config, StorageFactory storageFactory) {
        this(config.strictProviders() ? storageFactory.create(AzureDirectory.STORE) : null,
            !config.strictProviders());
    }

    /** For tests: registrations over an explicit backend, or memory-only for a null one. */
    public ProviderRegistrations(StorageBackend<String, StoredObject> store, boolean registeredByDefault) {
        this.store = store;
        this.registeredByDefault = registeredByDefault;
        load();
    }

    /**
     * True for a namespace every subscription is always registered with ({@code registrationPolicy}
     * {@code RegistrationFree}): {@code Microsoft.Resources}, the only one Azure records that way.
     */
    public static boolean isRegistrationFree(String namespace) {
        return REGISTRATION_FREE.equalsIgnoreCase(namespace);
    }

    /** The key two spellings of one pair share. */
    static String key(String subscriptionId, String namespace) {
        return subscriptionId.toLowerCase(Locale.ROOT) + "/" + namespace.toLowerCase(Locale.ROOT);
    }

    /**
     * The state of {@code subscriptionId} with {@code namespace}: {@code Registered} or {@code Unregistered} as
     * last recorded, else the default, {@code Registered} or {@code NotRegistered}.
     */
    public synchronized RegistrationState state(String subscriptionId, String namespace) {
        if (isRegistrationFree(namespace)) {
            return RegistrationState.REGISTERED;
        }
        Boolean registered = recorded.get(key(subscriptionId, namespace));
        if (registered == null) {
            return registeredByDefault ? RegistrationState.REGISTERED : RegistrationState.NOT_REGISTERED;
        }
        return registered ? RegistrationState.REGISTERED : RegistrationState.UNREGISTERED;
    }

    /** Whether {@code subscriptionId} is registered with {@code namespace}. */
    public boolean isRegistered(String subscriptionId, String namespace) {
        return state(subscriptionId, namespace) == RegistrationState.REGISTERED;
    }

    /** Registers the subscription with the namespace; registering twice is harmless. */
    public void register(String subscriptionId, String namespace) {
        record(subscriptionId, namespace, true);
    }

    /** Unregisters the subscription from the namespace; unregistering twice is harmless. */
    public void unregister(String subscriptionId, String namespace) {
        record(subscriptionId, namespace, false);
    }

    private synchronized void record(String subscriptionId, String namespace, boolean registered) {
        String key = key(subscriptionId, namespace);
        recorded.put(key, registered);
        persist(key, registered);
        LOG.infov("Subscription {0}: {1} is now {2}", subscriptionId, namespace,
            registered ? "registered" : "not registered");
    }

    @Override
    public synchronized void clear() {
        if (store != null) {
            for (String key : store.keys().stream().filter(k -> k.startsWith(PREFIX)).toList()) {
                store.delete(key);
            }
        }
        recorded.clear();
    }

    private synchronized void load() {
        recorded.clear();
        if (store == null) {
            return;
        }
        for (String key : store.keys().stream().filter(k -> k.startsWith(PREFIX)).toList()) {
            store.get(key).ifPresent(stored -> recorded.put(key.substring(PREFIX.length()),
                Boolean.parseBoolean(new String(stored.data(), StandardCharsets.UTF_8))));
        }
        LOG.debugv("Provider registrations: {0} loaded from the directory store", recorded.size());
    }

    private void persist(String key, boolean registered) {
        if (store == null) {
            return;
        }
        store.put(PREFIX + key, new StoredObject(PREFIX + key,
            Boolean.toString(registered).getBytes(StandardCharsets.UTF_8), Map.of(), Instant.now(),
            UUID.randomUUID().toString()));
    }
}
