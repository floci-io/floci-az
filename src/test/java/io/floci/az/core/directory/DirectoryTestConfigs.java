package io.floci.az.core.directory;

import io.floci.az.config.EmulatorConfig.SubscriptionConfig;
import io.floci.az.config.EmulatorConfig.TenantConfig;

import java.util.Optional;

/** Plain config entries for directory tests, without a running config source. */
final class DirectoryTestConfigs {

    private DirectoryTestConfigs() {
    }

    static TenantConfig tenant(String id, String cloud) {
        return new TenantConfig() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Optional<String> displayName() {
                return Optional.of("Tenant " + id);
            }

            @Override
            public Optional<String> defaultDomain() {
                return Optional.of(id + ".example");
            }

            @Override
            public Optional<String> cloud() {
                return Optional.ofNullable(cloud);
            }
        };
    }

    static SubscriptionConfig subscription(String id, String tenantId, String state) {
        return new SubscriptionConfig() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Optional<String> tenantId() {
                return Optional.ofNullable(tenantId);
            }

            @Override
            public Optional<String> displayName() {
                return Optional.empty();
            }

            @Override
            public Optional<String> state() {
                return Optional.ofNullable(state);
            }
        };
    }
}
