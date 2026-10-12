package io.floci.az.core.directory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.Resettable;
import io.floci.az.core.StoredObject;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The tenant and subscription directory: which tenants exist, which cloud each lives in, and which
 * subscriptions each owns, in what state.
 *
 * <p>The default tenant and subscription and the configured ones ({@link DirectorySeed}) are rewritten from
 * configuration at every start, so editing the config takes effect; an entry that was seeded or configured
 * before and no longer is gets dropped. Entries registered on first use survive restarts in a persistent
 * storage mode; a first-use subscription whose tenant was dropped that way moves to the default tenant.
 * Everything goes through one {@link StorageFactory} store, {@code directory}, keyed
 * {@code tenant/{id}} and {@code subscription/{id}} with the id lowercased, because ids are case-insensitive.
 *
 * <p>Listing order is the default entry first, then configured entries in config order, then first-use
 * entries: the az CLI selects the first {@code Enabled} subscription at login, so the default subscription
 * stays the one it picks. First-use entries keep their registration order until a restart, and are ordered by
 * id after one.
 */
@Startup
@ApplicationScoped
public class AzureDirectory implements Resettable {

    private static final Logger LOG = Logger.getLogger(AzureDirectory.class);

    /** The {@link StorageFactory} service name, and so {@code storage.services.directory}. */
    public static final String STORE = "directory";

    private static final String TENANT_PREFIX = "tenant/";
    private static final String SUBSCRIPTION_PREFIX = "subscription/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DirectorySeed seed;
    private final StorageBackend<String, StoredObject> store;

    /** Guarded by {@code this}; keyed by {@link DirectorySeed#key}, in listing order. */
    private final Map<String, Tenant> tenants = new LinkedHashMap<>();
    private final Map<String, Subscription> subscriptions = new LinkedHashMap<>();

    @Inject
    public AzureDirectory(EmulatorConfig config, StorageFactory storageFactory) {
        this(DirectorySeed.from(config), storageFactory.create(STORE));
    }

    /** For tests: a directory over an explicit seed and backend. */
    public AzureDirectory(DirectorySeed seed, StorageBackend<String, StoredObject> store) {
        this.seed = seed;
        this.store = store;
        load();
        LOG.infov("Directory: {0} tenant(s), {1} subscription(s), default cloud {2}", tenants.size(),
            subscriptions.size(), seed.defaultCloud().name());
    }

    // ── Defaults ─────────────────────────────────────────────────────────────

    public CloudProfile defaultCloud() {
        return seed.defaultCloud();
    }

    public String defaultTenantId() {
        return seed.defaultTenantId();
    }

    public String defaultSubscriptionId() {
        return seed.defaultSubscriptionId();
    }

    // ── Tenants ──────────────────────────────────────────────────────────────

    public synchronized Optional<Tenant> tenant(String tenantId) {
        return tenantId == null ? Optional.empty() : Optional.ofNullable(tenants.get(DirectorySeed.key(tenantId)));
    }

    /** Every tenant, in listing order. */
    public synchronized List<Tenant> tenants() {
        return List.copyOf(tenants.values());
    }

    /** The cloud a tenant lives in; the default cloud for a tenant the directory does not hold. */
    public CloudProfile cloudOfTenant(String tenantId) {
        return tenant(tenantId).map(tenant -> CloudCatalog.forNameOrPublic(tenant.cloud())).orElse(defaultCloud());
    }

    // ── Subscriptions ────────────────────────────────────────────────────────

    public synchronized Optional<Subscription> subscription(String subscriptionId) {
        return subscriptionId == null
            ? Optional.empty()
            : Optional.ofNullable(subscriptions.get(DirectorySeed.key(subscriptionId)));
    }

    /** Every subscription, in listing order. */
    public synchronized List<Subscription> subscriptions() {
        return List.copyOf(subscriptions.values());
    }

    /** The subscriptions one tenant owns, in listing order. */
    public synchronized List<Subscription> subscriptionsOfTenant(String tenantId) {
        return subscriptions.values().stream()
            .filter(subscription -> subscription.tenantId().equalsIgnoreCase(tenantId))
            .toList();
    }

    /** The tenant that owns a subscription; the default tenant for a subscription the directory does not hold. */
    public String tenantOfSubscription(String subscriptionId) {
        return subscription(subscriptionId).map(Subscription::tenantId).orElse(defaultTenantId());
    }

    /**
     * Registers a subscription the directory does not hold yet, {@code Enabled}, under {@code tenantId} when the
     * directory holds that tenant and under the default tenant otherwise. A subscription already held is
     * returned unchanged, so concurrent first uses register it once.
     */
    public synchronized Subscription registerOnFirstUse(String subscriptionId, String tenantId) {
        String key = DirectorySeed.key(subscriptionId);
        Subscription existing = subscriptions.get(key);
        if (existing != null) {
            return existing;
        }
        String owner = tenant(tenantId).map(Tenant::id).orElse(defaultTenantId());
        Subscription registered = new Subscription(subscriptionId, owner, DirectorySeed.DEFAULT_DISPLAY_NAME,
            SubscriptionState.ENABLED, DirectoryOrigin.FIRST_USE);
        subscriptions.put(key, registered);
        persist(SUBSCRIPTION_PREFIX + key, registered);
        LOG.infov("Registered subscription {0} on first use, under tenant {1}", subscriptionId, owner);
        return registered;
    }

    // ── Reset and load ───────────────────────────────────────────────────────

    /** Drops every first-use entry and restores the seeded and configured ones. */
    @Override
    public synchronized void clear() {
        store.clear();
        load();
    }

    private synchronized void load() {
        tenants.clear();
        subscriptions.clear();
        Map<String, Tenant> firstUseTenants = new LinkedHashMap<>();
        Map<String, Subscription> firstUseSubscriptions = new LinkedHashMap<>();
        List<String> stale = new ArrayList<>();
        for (String key : sortedKeys()) {
            Optional<StoredObject> stored = store.get(key);
            if (stored.isEmpty()) {
                continue;
            }
            if (key.startsWith(TENANT_PREFIX)) {
                read(key, stored.get(), Tenant.class).ifPresentOrElse(tenant -> {
                    if (tenant.origin() == DirectoryOrigin.FIRST_USE) {
                        firstUseTenants.put(key.substring(TENANT_PREFIX.length()), tenant);
                    } else {
                        stale.add(key);
                    }
                }, () -> stale.add(key));
            } else if (key.startsWith(SUBSCRIPTION_PREFIX)) {
                read(key, stored.get(), Subscription.class).ifPresentOrElse(subscription -> {
                    if (subscription.origin() == DirectoryOrigin.FIRST_USE) {
                        firstUseSubscriptions.put(key.substring(SUBSCRIPTION_PREFIX.length()), subscription);
                    } else {
                        stale.add(key);
                    }
                }, () -> stale.add(key));
            }
        }

        Map<String, StoredObject> puts = new LinkedHashMap<>();
        for (Tenant tenant : seed.tenants()) {
            String key = DirectorySeed.key(tenant.id());
            tenants.put(key, tenant);
            puts.put(TENANT_PREFIX + key, stored(TENANT_PREFIX + key, tenant));
        }
        for (Map.Entry<String, Tenant> entry : firstUseTenants.entrySet()) {
            tenants.putIfAbsent(entry.getKey(), entry.getValue());
        }
        for (Subscription subscription : seed.subscriptions()) {
            String key = DirectorySeed.key(subscription.id());
            subscriptions.put(key, subscription);
            puts.put(SUBSCRIPTION_PREFIX + key, stored(SUBSCRIPTION_PREFIX + key, subscription));
        }
        for (Map.Entry<String, Subscription> entry : firstUseSubscriptions.entrySet()) {
            String key = entry.getKey();
            if (subscriptions.containsKey(key)) {
                continue;
            }
            Subscription restored = underAKnownTenant(entry.getValue());
            subscriptions.put(key, restored);
            if (restored != entry.getValue()) {
                puts.put(SUBSCRIPTION_PREFIX + key, stored(SUBSCRIPTION_PREFIX + key, restored));
            }
        }

        // A stale key a seed entry now rewrites stays; the others were seeded or configured once and no longer are.
        stale.removeIf(puts::containsKey);
        if (!stale.isEmpty()) {
            LOG.infov("Directory: dropping {0} entries no longer seeded or configured: {1}", stale.size(), stale);
        }
        store.applyBatch(puts, Set.copyOf(stale));
    }

    /**
     * A first-use subscription restored from storage may name a tenant the directory no longer holds: the
     * default tenant id changed, or a configured tenant was removed. It then moves to the default tenant, the
     * same owner first use gives a subscription whose tenant is unknown, so it stays listed and never reports
     * a tenant that {@code GET /tenants} does not.
     */
    private Subscription underAKnownTenant(Subscription subscription) {
        if (tenants.containsKey(DirectorySeed.key(subscription.tenantId()))) {
            return subscription;
        }
        LOG.infov("Directory: subscription {0} belonged to tenant {1}, which is gone; moving it to the default"
            + " tenant {2}", subscription.id(), subscription.tenantId(), defaultTenantId());
        return new Subscription(subscription.id(), defaultTenantId(), subscription.displayName(),
            subscription.state(), subscription.origin());
    }

    private List<String> sortedKeys() {
        List<String> keys = new ArrayList<>(store.keys());
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    private static <T> Optional<T> read(String key, StoredObject stored, Class<T> type) {
        try {
            return Optional.of(MAPPER.readValue(stored.data(), type));
        } catch (IOException e) {
            LOG.warnv(e, "Directory: dropping unreadable entry {0}", key);
            return Optional.empty();
        }
    }

    private void persist(String key, Object value) {
        store.put(key, stored(key, value));
    }

    private static StoredObject stored(String key, Object value) {
        try {
            return new StoredObject(key, MAPPER.writeValueAsBytes(value), Map.of(), Instant.now(),
                UUID.randomUUID().toString());
        } catch (IOException e) {
            throw new IllegalStateException("Directory: cannot serialize " + key, e);
        }
    }
}
