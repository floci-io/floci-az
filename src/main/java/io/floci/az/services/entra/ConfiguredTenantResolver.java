package io.floci.az.services.entra;

import io.floci.az.config.EmulatorConfig;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * Resolves tenants against {@code entra.default-tenant-id} alone. The aliases {@code common},
 * {@code organizations} and {@code consumers} resolve to the default tenant. Any other segment is
 * passed through as the tenant id, unregistered, with lookups served from the default tenant's
 * directory: tokens for an arbitrary tenant id work as they always have.
 */
@DefaultBean
@ApplicationScoped
public class ConfiguredTenantResolver implements TenantResolver {

    private static final Set<String> ALIASES = Set.of("common", "organizations", "consumers");

    private final EmulatorConfig config;

    @Inject
    public ConfiguredTenantResolver(EmulatorConfig config) {
        this.config = config;
    }

    @Override
    public TenantRef resolve(String segment, String hint) {
        if (segment == null || segment.isBlank()) {
            return hint == null || hint.isBlank() ? defaultTenant() : resolve(hint, null);
        }
        if (ALIASES.contains(segment)) {
            return defaultTenant();
        }
        String defaultTenantId = defaultTenantId();
        return new TenantRef(segment, defaultTenantId, segment.equals(defaultTenantId));
    }

    @Override
    public TenantRef defaultTenant() {
        String defaultTenantId = defaultTenantId();
        return new TenantRef(defaultTenantId, defaultTenantId, true);
    }

    private String defaultTenantId() {
        return config.services().entra().defaultTenantId();
    }
}
