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
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * One owner per global DNS name: the first resource to create a name owns it until it is deleted. A name is unique
 * across every cloud, keyed {@code suffix/name}, because floci-az keys most data planes by name alone; a type that
 * declares {@link GlobalName#perCloud()} is unique per cloud instead, as in Azure, where each cloud has its own DNS
 * zones ({@code x.redis.cache.windows.net} and {@code x.redis.cache.usgovcloudapi.net} are different names), keyed
 * {@code cloud/suffix/name}. Each suffix belongs to one type's global name, so the registry reads the per-cloud
 * suffixes from the declared types.
 *
 * <p>A PUT {@link #reserve reserves} a name, then {@link #commit commits} it on a 2xx answer or
 * {@link #releasePending releases} it otherwise ({@link GlobalNameRule}). A reservation in flight reads as taken to
 * a concurrent PUT from another resource, so concurrent creates leave one owner. Only the request that made a
 * reservation can release it, by its {@link Reservation#token() token}: a concurrent PUT to the same resource
 * finds it {@link Outcome#OWNED} and holds no token, so its failure cannot free a name the first request is still
 * creating. A {@link #release delete} frees a name only once its owner is no longer stored, so a PUT that recreated
 * the resource while the delete was finishing keeps it. The gate's PUTs are counted per resource while they run
 * ({@link #reserveForPut}, {@link #endPut}), and a gate DELETE that finishes while one is in flight leaves its
 * release to the last of them ({@link #releaseAfterPuts}), so a PUT that has found the name owned but not yet
 * stored the resource cannot lose it to another resource.
 *
 * <p>Nothing is persisted: the registry rebuilds from every {@link GlobalNameSource} on first use, and again after
 * {@code POST /_admin/reset}, so it cannot drift from the stores it mirrors.
 */
@ApplicationScoped
public class GlobalNameRegistry implements Resettable {

    private static final Logger LOG = Logger.getLogger(GlobalNameRegistry.class);

    /** The namespace of a name unique across clouds. */
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
    private final Supplier<Set<CloudSuffix>> perCloudSuffixes;
    private Set<CloudSuffix> perCloud;
    private final Map<String, Entry> names = new HashMap<>();
    /** The gate PUTs in flight per resource ({@link ArmResourceId#canonicalKey()}), and the deletes waiting on them. */
    private final Map<String, Integer> putsInFlight = new HashMap<>();
    private final Set<String> deferredReleases = new HashSet<>();
    private boolean built;
    private long lastToken;

    @Inject
    public GlobalNameRegistry(Instance<GlobalNameSource> sources, AzureDirectory directory, ArmTypeRegistry types) {
        this(() -> sources, subscription -> directory.cloudOfTenant(directory.tenantOfSubscription(subscription)),
            () -> perCloudSuffixes(types));
    }

    GlobalNameRegistry(Supplier<Iterable<GlobalNameSource>> sources, Function<String, CloudProfile> cloudOfSubscription,
                       Supplier<Set<CloudSuffix>> perCloudSuffixes) {
        this.sources = sources;
        this.cloudOfSubscription = cloudOfSubscription;
        this.perCloudSuffixes = perCloudSuffixes;
    }

    private static Set<CloudSuffix> perCloudSuffixes(ArmTypeRegistry types) {
        return types.surfaces().stream()
            .flatMap(surface -> surface.types().stream())
            .map(ArmResourceType::globalName)
            .filter(global -> global.declared() && global.perCloud())
            .map(GlobalName::suffix)
            .collect(Collectors.toUnmodifiableSet());
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
     * {@link #reserve}, and counts a PUT to {@code owner} in flight unless the name is {@link Outcome#TAKEN}, under
     * the same lock: a reservation this returns that is not taken must be ended with {@link #endPut}.
     */
    public synchronized Reservation reserveForPut(CloudProfile cloud, CloudSuffix suffix, String name,
                                                  ArmResourceId owner) {
        Reservation reservation = reserve(cloud, suffix, name, owner);
        if (reservation.outcome() != Outcome.TAKEN) {
            putsInFlight.merge(owner.canonicalKey(), 1, Integer::sum);
        }
        return reservation;
    }

    /**
     * Ends a PUT {@link #reserveForPut} counted, after its {@link #commit} or {@link #releasePending}. The last PUT to
     * a resource whose DELETE finished meanwhile runs that delete's {@link #release}: it keeps the name when this
     * PUT stored the resource again, and frees it otherwise.
     */
    public synchronized void endPut(CloudProfile cloud, CloudSuffix suffix, String name, ArmResourceId owner) {
        String resource = owner.canonicalKey();
        Integer remaining = putsInFlight.computeIfPresent(resource, (key, count) -> count == 1 ? null : count - 1);
        if (remaining == null && deferredReleases.remove(resource)) {
            release(cloud, suffix, name, owner);
        }
    }

    /**
     * {@link #release} for a DELETE the gate saw succeed, deferred to the last PUT to the same resource still in
     * flight: such a PUT may have found the name owned before the delete removed the resource, and not yet stored it.
     */
    public synchronized void releaseAfterPuts(CloudProfile cloud, CloudSuffix suffix, String name,
                                              ArmResourceId owner) {
        if (putsInFlight.containsKey(owner.canonicalKey())) {
            deferredReleases.add(owner.canonicalKey());
        } else {
            release(cloud, suffix, name, owner);
        }
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

    /**
     * Frees the name {@code owner} holds: it was deleted. A name whose owner is still stored stays held: a PUT to the
     * same resource that ran between the delete's store removal and this release found the name owned, created the
     * resource again and committed, so freeing the name would let another resource take a name a stored one uses.
     */
    public synchronized void release(CloudProfile cloud, CloudSuffix suffix, String name, ArmResourceId owner) {
        String key = key(cloud, suffix, name);
        Entry existing = names.get(key);
        if (existing != null && sameResource(existing.owner(), owner) && !stored(cloud, suffix, name, owner)) {
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

    private String key(CloudProfile cloud, CloudSuffix suffix, String name) {
        if (perCloud == null) {
            perCloud = perCloudSuffixes.get();
        }
        String namespace = perCloud.contains(suffix) ? cloud.name().toLowerCase(Locale.ROOT) : ALL_CLOUDS;
        return namespace + "/" + suffix.name() + "/" + name.toLowerCase(Locale.ROOT);
    }
}
