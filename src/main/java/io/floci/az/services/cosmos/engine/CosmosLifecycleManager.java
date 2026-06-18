package io.floci.az.services.cosmos.engine;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle of Cosmos engine containers.
 *
 * <p>Supports three startup modes per engine:
 * <ul>
 *   <li>{@code on-demand} — container starts when first requested (default)</li>
 *   <li>{@code eager} — container starts at application startup</li>
 *   <li>{@code disabled} — engine is not started or registered</li>
 * </ul>
 */
@ApplicationScoped
public class CosmosLifecycleManager {

    private static final Logger LOG = Logger.getLogger(CosmosLifecycleManager.class);

    @Inject EmulatorConfig config;
    @Inject CosmosEngineRegistry registry;
    @Inject ContainerLifecycleManager containerManager;
    @Inject ContainerBuilder containerBuilder;

    /** Running container state per API. */
    private final ConcurrentHashMap<CosmosApi, RunningEngine> running = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent ev) {
        if (config.services().cosmos().mocked()) {
            LOG.info("Cosmos mocked mode — no engine containers will be started");
            return;
        }

        EmulatorConfig.CosmosEngineConfig cosmosConfig = config.services().cosmos().engines();
        String startupMode = cosmosConfig.startup();

        for (Map.Entry<CosmosApi, CosmosEngineProvider> entry : registry.all().entrySet()) {
            CosmosApi api = entry.getKey();
            EmulatorConfig.CosmosApiConfig apiConfig = resolveApiConfig(cosmosConfig, api);

            if (!apiConfig.enabled()) {
                LOG.debugf("Cosmos engine %s: disabled", api);
                continue;
            }

            if ("eager".equalsIgnoreCase(startupMode)) {
                try {
                    LOG.infof("Cosmos engine %s: eager startup", api);
                    startEngine(api, entry.getValue(), apiConfig);
                } catch (Exception e) {
                    LOG.errorf(e, "Cosmos engine %s: failed to start eagerly — will start on demand", api);
                }
            } else {
                LOG.debugf("Cosmos engine %s: registered for on-demand startup", api);
            }
        }
    }

    /**
     * Returns connection info for the given API, starting the container on demand if needed.
     */
    public synchronized Optional<CosmosConnectionInfo> getOrStart(CosmosApi api) {
        if (config.services().cosmos().mocked()) {
            LOG.debugf("Cosmos mocked mode — engine %s unavailable", api);
            return Optional.empty();
        }

        RunningEngine existing = running.get(api);
        if (existing != null) {
            return Optional.of(existing.connectionInfo());
        }

        Optional<CosmosEngineProvider> providerOpt = registry.resolve(api);
        if (providerOpt.isEmpty()) {
            LOG.warnf("No Cosmos engine provider registered for API: %s", api);
            return Optional.empty();
        }

        EmulatorConfig.CosmosEngineConfig cosmosConfig = config.services().cosmos().engines();
        EmulatorConfig.CosmosApiConfig apiConfig = resolveApiConfig(cosmosConfig, api);

        if (!apiConfig.enabled()) {
            LOG.warnf("Cosmos engine %s is disabled in configuration", api);
            return Optional.empty();
        }

        try {
            return Optional.of(startEngine(api, providerOpt.get(), apiConfig));
        } catch (Exception e) {
            LOG.errorf(e, "Failed to start Cosmos engine %s on demand", api);
            return Optional.empty();
        }
    }

    /** Returns connection info if the engine is already running, without starting it. */
    public Optional<CosmosConnectionInfo> getIfRunning(CosmosApi api) {
        RunningEngine state = running.get(api);
        return state != null ? Optional.of(state.connectionInfo()) : Optional.empty();
    }

    /** Returns true if the engine for the given service type suffix is enabled in config. */
    public boolean isEnabled(String serviceType) {
        Optional<CosmosEngineProvider> provider = registry.resolveByServiceType(serviceType);
        if (provider.isEmpty()) return false;
        EmulatorConfig.CosmosApiConfig apiConfig =
            resolveApiConfig(config.services().cosmos().engines(), provider.get().supportedApi());
        return apiConfig.enabled();
    }

    private CosmosConnectionInfo startEngine(CosmosApi api,
                                              CosmosEngineProvider provider,
                                              EmulatorConfig.CosmosApiConfig apiConfig) {
        CosmosEngine engine = provider.engine();

        // Embedded (in-process) engines — no Docker required.
        if (engine.isEmbedded()) {
            int port = apiConfig.port().orElse(config.port());
            CosmosConnectionInfo connInfo = engine.buildConnectionInfo("localhost", port);
            running.put(api, new RunningEngine(null, connInfo));
            LOG.infof("Cosmos engine %s ready (embedded, no Docker) at localhost:%d", api, port);
            return connInfo;
        }

        String image    = apiConfig.image().orElse(engine.defaultImage());
        int hostPort    = apiConfig.port().orElse(engine.defaultPort());

        LOG.infof("Starting Cosmos engine %s with image=%s port=%d", api, image, hostPort);

        String containerName = "floci-az-cosmos-" + api.name().toLowerCase();
        containerManager.removeIfExists(containerName);

        ContainerSpec spec = buildSpec(containerName, image, hostPort, engine.defaultPort(), api);
        var info = containerManager.createAndStart(spec);

        String host = "localhost";
        int mappedPort = Optional.ofNullable(info.getEndpoint(engine.defaultPort()))
            .map(ContainerLifecycleManager.EndpointInfo::port)
            .orElse(hostPort);

        CosmosConnectionInfo connInfo = engine.buildConnectionInfo(host, mappedPort);
        running.put(api, new RunningEngine(info.containerId(), connInfo));
        LOG.infof("Cosmos engine %s ready at %s:%d", api, host, mappedPort);
        return connInfo;
    }

    private ContainerSpec buildSpec(String name, String image, int hostPort, int containerPort, CosmosApi api) {
        ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
            .withName(name)
            .withPortBinding(containerPort, hostPort);

        // PostgreSQL needs env vars
        if (api == CosmosApi.POSTGRESQL) {
            builder.withEnv("POSTGRES_PASSWORD", "mypassword")
                   .withEnv("POSTGRES_USER", "citus")
                   .withEnv("POSTGRES_DB", "citus");
        }

        // ScyllaDB needs smp flag for single-core dev mode
        if (api == CosmosApi.CASSANDRA) {
            builder.withCmd(List.of("--smp", "1", "--memory", "256M"));
        }

        return builder.build();
    }

    @PreDestroy
    void shutdown() {
        for (Map.Entry<CosmosApi, RunningEngine> entry : running.entrySet()) {
            String containerId = entry.getValue().containerId();
            if (containerId == null) {
                // Embedded engine — nothing to stop
                LOG.debugf("Cosmos engine %s (embedded) — no container to stop", entry.getKey());
                continue;
            }
            try {
                LOG.infof("Stopping Cosmos engine %s (container %s)", entry.getKey(), containerId);
                containerManager.stopAndRemove(containerId, null);
            } catch (Exception e) {
                LOG.warnf(e, "Error stopping Cosmos engine %s", entry.getKey());
            }
        }
        running.clear();
    }

    private EmulatorConfig.CosmosApiConfig resolveApiConfig(
            EmulatorConfig.CosmosEngineConfig cosmosConfig, CosmosApi api) {
        return switch (api) {
            case NOSQL       -> cosmosConfig.nosql();
            case MONGODB     -> cosmosConfig.mongodb();
            case POSTGRESQL  -> cosmosConfig.postgresql();
            case CASSANDRA   -> cosmosConfig.cassandra();
            case GREMLIN     -> cosmosConfig.gremlin();
            case TABLE       -> cosmosConfig.table();
        };
    }

    private record RunningEngine(String containerId, CosmosConnectionInfo connectionInfo) {}
}
