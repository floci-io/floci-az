package io.floci.az.core.arm;

import io.floci.az.core.cloud.CloudSuffix;

import java.util.List;

/**
 * A service whose resources own global DNS names: the {@link GlobalNameRegistry} rebuilds itself from every
 * source's {@link #globalNames()}, so a name stays taken across restarts without the registry persisting anything.
 */
public interface GlobalNameSource {

    /** One global name a stored resource owns. */
    record Claim(CloudSuffix suffix, String name, ArmResourceId owner) {
    }

    /** Every global name this service's stored resources own. */
    List<Claim> globalNames();

    /**
     * Whether {@code owner} is stored holding {@code name} under {@code suffix}. The registry asks this on every
     * delete, under its lock, so a source should answer from a keyed lookup of {@code owner}, and answer false at
     * once for a suffix it does not hold. The default scans {@link #globalNames()}.
     */
    default boolean holds(CloudSuffix suffix, String name, ArmResourceId owner) {
        String resource = owner.canonicalKey();
        return globalNames().stream().anyMatch(claim -> claim.suffix() == suffix
            && claim.name().equalsIgnoreCase(name) && claim.owner().canonicalKey().equals(resource));
    }
}
