package io.floci.az.services.cosmos.engine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * CDI registry that discovers all {@link CosmosEngineProvider} implementations
 * and makes them available by {@link CosmosApi}.
 */
@ApplicationScoped
public class CosmosEngineRegistry {

    private static final Logger LOG = Logger.getLogger(CosmosEngineRegistry.class);

    private final Map<CosmosApi, CosmosEngineProvider> providers = new EnumMap<>(CosmosApi.class);

    @Inject
    public CosmosEngineRegistry(Instance<CosmosEngineProvider> discovered) {
        for (CosmosEngineProvider p : discovered) {
            providers.put(p.supportedApi(), p);
            LOG.debugf("Registered Cosmos engine provider: %s → %s",
                p.supportedApi(), p.engine().defaultImage());
        }
    }

    /** Returns the provider for the given API, or empty if not registered. */
    public Optional<CosmosEngineProvider> resolve(CosmosApi api) {
        if (api == null) return Optional.empty();
        return Optional.ofNullable(providers.get(api));
    }

    /** Returns the provider for the given service-type suffix string (e.g. "cosmos-mongo"). */
    public Optional<CosmosEngineProvider> resolveByServiceType(String serviceType) {
        for (CosmosApi api : CosmosApi.values()) {
            if (api.serviceTypeSuffix().equals(serviceType)) {
                return resolve(api);
            }
        }
        return Optional.empty();
    }

    /** All registered providers, keyed by API. */
    public Map<CosmosApi, CosmosEngineProvider> all() {
        return Map.copyOf(providers);
    }
}
