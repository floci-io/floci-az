package io.floci.az.core.arm;

import io.floci.az.core.Resettable;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import io.floci.az.core.directory.AzureDirectory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One owner per global DNS name in each cloud: the first resource to create a name owns it until it is deleted.
 * Names are unique per cloud and suffix, because each cloud has its own DNS zones ({@code x.redis.cache.windows.net}
 * and {@code x.redis.cache.usgovcloudapi.net} are different names), so the key is {@code cloud/suffix/name}.
 *
 * <p>The exception is a {@link CloudSuffix#hostRouted() host-routed} suffix. floci-az serves those data planes on one
 * host suffix per service and keys their data by name alone (an ACR repository prefix, a storage account), with no
 * cloud in either, so two clouds holding the same name would share one data plane. Until the data planes are
 * cloud-aware, a host-routed name is unique across every cloud, and its key leaves the cloud out.
 *
 * <p>A PUT {@link #reserve reserves} a name, then {@link #commit commits} it on a 2xx answer or
 * {@link #releasePending releases} it otherwise ({@link GlobalNameRule}). A reservation in flight reads as taken to
 * a concurrent PUT from another resource, so concurrent creates leave one owner. Only the request that made a
 * reservation can release it, by its {@link Reservation#token() token}: a concurrent PUT to the same resource
 * finds it {@link Outcome#OWNED} and holds no token, so its failure cannot free a name the first request is still
 * creating.
 *
 * <p>Nothing is persisted: the registry rebuilds from every {@link GlobalNameSource} on first use, and again after
 * {@code POST /_admin/reset}, so it cannot drift from the stores it mirrors.
 */
@ApplicationScoped
public class GlobalNameRegistry implements Resettable {

    private static final Logger LOG = Logger.getLogger(GlobalNameRegistry.class);

    /** The namespace of a host-routed suffix's names: every cloud at once, since its data plane has no cloud. */
    private static final String ALL_CLOUDS = "*";

    /** What a reservation found. */
    public enum Outcome { RESERVED, OWNED, TAKEN }

    /**
     * The outcome, and the owner the name belongs to after it.
     *
     * @param token identifies a {@link Outcome#RESERVED} reservation to {@link #releasePending}; 0 otherwise
     */
    public record Reservation(Outcome outcome, ArmResourceId owner, long token) {
    }

    private record Entry(ArmResourceId owner, boolean committed, long token) {
    }

    private final Supplier<Iterable<GlobalNameSource>> sources;
    private final Function<String, CloudProfile> cloudOfSubscription;
    private final Map<String, Entry> names = new HashMap<>();
    private boolean built;
    private long lastToken;

    @Inject
    public GlobalNameRegistry(Instance<GlobalNameSource> sources, AzureDirectory directory) {
        this(() -> sources, subscription -> directory.cloudOfTenant(directory.tenantOfSubscription(subscription)));
    }

    GlobalNameRegistry(Supplier<Iterable<GlobalNameSource>> sources, Function<String, CloudProfile> cloudOfSubscription) {
        this.sources = sources;
        this.cloudOfSubscription = cloudOfSubscription;
    }

    /** Reserves {@code name} for {@code owner}, unless another resource owns or is creating it. */
    public synchronized Reservation reserve(CloudProfile cloud, CloudSuffix suffix, String name, ArmResourceId owner) {
        ensureBuilt();
        String key = key(cloud, suffix, name);
        Entry existing = names.get(key);
        if (existing == null) {
            long token = ++lastToken;
            names.put(key, new Entry(owner, false, token));
            return new Reservation(Outcome.RESERVED, owner, token);
        }
        return sameResource(existing.owner(), owner) ? new Reservation(Outcome.OWNED, existing.owner(), 0)
            : new Reservation(Outcome.TAKEN, existing.owner(), 0);
    }

    /**
     * Keeps the name for {@code owner}: a PUT to it succeeded. When the name is no longer held, because a DELETE
     * released it while the PUT ran, it is held again only if the resource is still stored: the PUT may have
     * created it after the delete, or found it deleted and stored nothing.
     */
    public synchronized void commit(CloudProfile cloud, CloudSuffix suffix, String name, ArmResourceId owner) {
        String key = key(cloud, suffix, name);
        Entry existing = names.get(key);
        if (existing != null && sameResource(existing.owner(), owner)) {
            names.put(key, new Entry(existing.owner(), true, existing.token()));
        } else if (existing == null && stored(cloud, suffix, name, owner)) {
            names.put(key, new Entry(owner, true, 0));
        }
    }

    /** Frees the reservation {@code token} made unless it was committed: its create did not succeed. */
    public synchronized void releasePending(CloudProfile cloud, CloudSuffix suffix, String name, long token) {
        String key = key(cloud, suffix, name);
        Entry existing = names.get(key);
        if (existing != null && !existing.committed() && token != 0 && existing.token() == token) {
            names.remove(key);
        }
    }

    /** Frees the name {@code owner} holds: it was deleted. */
    public synchronized void release(CloudProfile cloud, CloudSuffix suffix, String name, ArmResourceId owner) {
        String key = key(cloud, suffix, name);
        Entry existing = names.get(key);
        if (existing != null && sameResource(existing.owner(), owner)) {
            names.remove(key);
        }
    }

    /** The resource that owns {@code name}, committed or being created. */
    public synchronized Optional<ArmResourceId> owner(CloudProfile cloud, CloudSuffix suffix, String name) {
        ensureBuilt();
        return Optional.ofNullable(names.get(key(cloud, suffix, name))).map(Entry::owner);
    }

    /** {@code POST /_admin/reset}: the stores are cleared too, so the next use rebuilds from them. */
    @Override
    public synchronized void clear() {
        names.clear();
        built = false;
    }

    private void ensureBuilt() {
        if (built) {
            return;
        }
        names.clear();
        for (GlobalNameSource source : sources.get()) {
            for (GlobalNameSource.Claim claim : source.globalNames()) {
                CloudProfile cloud = cloudOfSubscription.apply(claim.owner().subscriptionId());
                Entry previous = names.putIfAbsent(key(cloud, claim.suffix(), claim.name()),
                    new Entry(claim.owner(), true, 0));
                if (previous != null && !sameResource(previous.owner(), claim.owner())) {
                    LOG.warnv("Global name {0} is held by both {1} and {2}; keeping the first", claim.name(),
                        previous.owner().id(), claim.owner().id());
                }
            }
        }
        built = true;
    }

    /** Whether a source stores {@code owner} holding {@code name}. */
    private boolean stored(CloudProfile cloud, CloudSuffix suffix, String name, ArmResourceId owner) {
        for (GlobalNameSource source : sources.get()) {
            for (GlobalNameSource.Claim claim : source.globalNames()) {
                if (claim.suffix() == suffix && claim.name().equalsIgnoreCase(name)
                        && sameResource(claim.owner(), owner)
                        && key(cloudOfSubscription.apply(claim.owner().subscriptionId()), suffix, name)
                            .equals(key(cloud, suffix, name))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sameResource(ArmResourceId a, ArmResourceId b) {
        return a.canonicalKey().equals(b.canonicalKey());
    }

    private static String key(CloudProfile cloud, CloudSuffix suffix, String name) {
        String namespace = suffix.hostRouted() ? ALL_CLOUDS : cloud.name().toLowerCase(Locale.ROOT);
        return namespace + "/" + suffix.name() + "/" + name.toLowerCase(Locale.ROOT);
    }
}
