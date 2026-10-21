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
}
