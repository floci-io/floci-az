package io.floci.az.core.directory;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.config.EmulatorConfig.SubscriptionConfig;
import io.floci.az.config.EmulatorConfig.TenantConfig;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The tenants and subscriptions the directory holds whatever was persisted: the default tenant and
 * subscription, then the configured ones in config order. Built once from {@link EmulatorConfig} and validated
 * there, so a typo in a cloud name, a state or a tenant reference fails startup instead of a later request.
 *
 * @param defaultCloud          the cloud of a tenant that names none, and of an unknown tenant
 * @param defaultTenantId       {@code entra.default-tenant-id}
 * @param defaultSubscriptionId {@code arm.default-subscription-id}
 * @param tenants               the default tenant first, then every configured one
 * @param subscriptions         the default subscription first, then every configured one
 */
public record DirectorySeed(CloudProfile defaultCloud, String defaultTenantId, String defaultSubscriptionId,
                            List<Tenant> tenants, List<Subscription> subscriptions) {

    /** The display name of the default tenant and subscription, and of a first-use subscription. */
    public static final String DEFAULT_DISPLAY_NAME = "floci-az local";

    /** The default tenant's domain. */
    public static final String DEFAULT_DOMAIN = "floci-az.local";

    public DirectorySeed {
        tenants = List.copyOf(tenants);
        subscriptions = List.copyOf(subscriptions);
    }

    /** Reads and validates the directory's configuration. */
    public static DirectorySeed from(EmulatorConfig config) {
        return of(config.cloud().defaultCloud(), config.services().entra().defaultTenantId(),
            config.services().arm().defaultSubscriptionId(), config.tenants(), config.subscriptions());
    }

    /**
     * Builds the seed from its configuration values.
     *
     * @throws IllegalStateException when a cloud name or a state is unknown, an id is blank or repeated, or a
     *                               subscription names a tenant that is neither the default nor configured
     */
    public static DirectorySeed of(String defaultCloudName, String defaultTenantId, String defaultSubscriptionId,
                                   List<TenantConfig> tenantConfigs, List<SubscriptionConfig> subscriptionConfigs) {
        CloudProfile defaultCloud = cloud(defaultCloudName, "floci-az.cloud.default");
        requireId(defaultTenantId, "floci-az.services.entra.default-tenant-id");
        requireId(defaultSubscriptionId, "floci-az.services.arm.default-subscription-id");

        Map<String, Tenant> tenants = new LinkedHashMap<>();
        tenants.put(key(defaultTenantId), new Tenant(defaultTenantId, defaultCloud.name(), DEFAULT_DISPLAY_NAME,
            DEFAULT_DOMAIN, DirectoryOrigin.SEED));
        Set<String> configuredTenants = new HashSet<>();
        for (int i = 0; i < tenantConfigs.size(); i++) {
            TenantConfig tenant = tenantConfigs.get(i);
            String property = "floci-az.tenants[" + i + "]";
            requireId(tenant.id(), property + ".id");
            if (!configuredTenants.add(key(tenant.id()))) {
                throw new IllegalStateException(property + ": tenant '" + tenant.id() + "' is configured twice");
            }
            String cloud = tenant.cloud().map(name -> cloud(name, property + ".cloud").name())
                .orElse(defaultCloud.name());
            tenants.put(key(tenant.id()), new Tenant(tenant.id(), cloud,
                tenant.displayName().orElse(DEFAULT_DISPLAY_NAME), tenant.defaultDomain().orElse(DEFAULT_DOMAIN),
                DirectoryOrigin.CONFIG));
        }

        Map<String, Subscription> subscriptions = new LinkedHashMap<>();
        subscriptions.put(key(defaultSubscriptionId), new Subscription(defaultSubscriptionId, defaultTenantId,
            DEFAULT_DISPLAY_NAME, SubscriptionState.ENABLED, DirectoryOrigin.SEED));
        Set<String> configuredSubscriptions = new HashSet<>();
        for (int i = 0; i < subscriptionConfigs.size(); i++) {
            SubscriptionConfig subscription = subscriptionConfigs.get(i);
            String property = "floci-az.subscriptions[" + i + "]";
            requireId(subscription.id(), property + ".id");
            if (!configuredSubscriptions.add(key(subscription.id()))) {
                throw new IllegalStateException(property + ": subscription '" + subscription.id()
                    + "' is configured twice");
            }
            String tenantId = subscription.tenantId().orElse(defaultTenantId);
            Tenant tenant = tenants.get(key(tenantId));
            if (tenant == null) {
                throw new IllegalStateException(property + ".tenant-id: tenant '" + tenantId
                    + "' is neither the default tenant nor listed under floci-az.tenants");
            }
            SubscriptionState state = subscription.state().map(value -> state(value, property + ".state"))
                .orElse(SubscriptionState.ENABLED);
            subscriptions.put(key(subscription.id()), new Subscription(subscription.id(), tenant.id(),
                subscription.displayName().orElse(DEFAULT_DISPLAY_NAME), state, DirectoryOrigin.CONFIG));
        }
        return new DirectorySeed(defaultCloud, defaultTenantId, defaultSubscriptionId,
            new ArrayList<>(tenants.values()), new ArrayList<>(subscriptions.values()));
    }

    /** The key a tenant or subscription id is stored and compared under: ids are case-insensitive. */
    static String key(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    private static CloudProfile cloud(String name, String property) {
        return CloudCatalog.forName(name).orElseThrow(() -> new IllegalStateException(property + ": unknown cloud '"
            + name + "'; expected one of " + CloudCatalog.all().stream().map(CloudProfile::name).toList()));
    }

    private static SubscriptionState state(String value, String property) {
        try {
            return SubscriptionState.fromWireName(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(property + ": " + e.getMessage(), e);
        }
    }

    private static void requireId(String id, String property) {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException(property + " must not be blank");
        }
    }
}
