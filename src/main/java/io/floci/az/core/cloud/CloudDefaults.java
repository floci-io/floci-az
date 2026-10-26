package io.floci.az.core.cloud;

import io.floci.az.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The location a resource gets when its request names none, per cloud: {@code floci-az.cloud.<name>.default-location}
 * when configured, otherwise the catalog's {@link CloudProfile#effectiveDefaultLocation()}.
 *
 * <p>Fails at startup on a configured location the cloud does not offer, or on a cloud name the catalog does not
 * hold, rather than creating resources in a location Azure would reject.
 */
@ApplicationScoped
public class CloudDefaults {

    private final Map<String, String> locations;

    @Inject
    public CloudDefaults(EmulatorConfig config) {
        this(configured(config.cloud().clouds()));
    }

    /** {@code locations} by cloud name, as configured; validated and normalized here. */
    public CloudDefaults(Map<String, String> configured) {
        Map<String, String> resolved = new HashMap<>();
        configured.forEach((name, location) -> {
            CloudProfile cloud = CloudCatalog.forName(name).orElseThrow(() -> new IllegalStateException(
                "floci-az.cloud." + name + ".default-location: '" + name + "' is not a cloud of the catalog ("
                    + CloudCatalog.all().stream().map(CloudProfile::name).collect(Collectors.joining(", ")) + ")"));
            String normalized = CloudCatalog.normalizeLocation(cloud, location);
            if (cloud.location(normalized).isEmpty()) {
                throw new IllegalStateException("floci-az.cloud." + name + ".default-location: '" + location
                    + "' is not a location of " + cloud.name());
            }
            resolved.put(cloud.name().toLowerCase(Locale.ROOT), normalized);
        });
        this.locations = Map.copyOf(resolved);
    }

    /** No configured default anywhere: every cloud answers its catalog default. */
    public static CloudDefaults catalogOnly() {
        return new CloudDefaults(Map.of());
    }

    /** The default location of {@code cloud}. */
    public String defaultLocation(CloudProfile cloud) {
        String configured = locations.get(cloud.name().toLowerCase(Locale.ROOT));
        return configured != null ? configured : cloud.effectiveDefaultLocation();
    }

    /** The default location of the request's cloud, the public cloud's when the request resolved no scope. */
    public String defaultLocation(ScopeContext scope) {
        return scope == null ? defaultLocation() : defaultLocation(scope.cloud());
    }

    /** The public cloud's default location, for a stored resource that predates its location. */
    public String defaultLocation() {
        return defaultLocation(CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD));
    }

    /** The configured clouds, keeping only entries that set a location (the map also sees the sibling key). */
    private static Map<String, String> configured(Map<String, EmulatorConfig.CloudSettings> clouds) {
        Map<String, String> configured = new HashMap<>();
        clouds.forEach((name, settings) -> settings.defaultLocation()
            .filter(location -> !location.isBlank())
            .ifPresent(location -> configured.put(name, location)));
        return configured;
    }
}
