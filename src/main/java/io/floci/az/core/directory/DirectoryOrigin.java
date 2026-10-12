package io.floci.az.core.directory;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** How a tenant or subscription came to be in the directory. */
@RegisterForReflection
public enum DirectoryOrigin {
    /** The default tenant or subscription, from {@code entra.default-tenant-id} or {@code arm.default-subscription-id}. */
    SEED,
    /** Listed under {@code floci-az.tenants} or {@code floci-az.subscriptions}. */
    CONFIG,
    /** Registered by the first request that named it, unless subscriptions are strict. */
    FIRST_USE
}
