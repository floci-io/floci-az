package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.services.cosmos.engine.CosmosApi;
import io.floci.az.services.cosmos.engine.CosmosEngineRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class BannerLogger {

    private static final Logger LOGGER = Logger.getLogger(BannerLogger.class);

    @Inject
    EmulatorConfig config;

    @Inject
    CosmosEngineRegistry cosmosEngineRegistry;

    void onStart(@Observes StartupEvent ev) {
        LOGGER.info("=== FLOCI - AZ Starting ===");
        LOGGER.infof("Storage mode: %s", config.storage().mode());
        
        StringBuilder sb = new StringBuilder("\nEnabled Services:\n");
        if (config.services().blob().enabled()) {
            sb.append(serviceStatus("blob", true, getStorageMode("blob")));
        }
        if (config.services().queue().enabled()) {
            sb.append(serviceStatus("queue", true, getStorageMode("queue")));
        }
        if (config.services().table().enabled()) {
            sb.append(serviceStatus("table", true, getStorageMode("table")));
        }
        if (config.services().functions().enabled()) {
            sb.append(serviceStatusDocker("functions", true, config.docker().dockerHost()));
        }
        if (config.services().appConfig().enabled()) {
            sb.append(serviceStatus("appconfig", true, getStorageMode("appconfig")));
        }
        if (config.services().cosmos().enabled()) {
            sb.append(serviceStatus("cosmos", true, getStorageMode("cosmos")));
            // Cosmos engine sub-APIs
            EmulatorConfig.CosmosEngineConfig cosmosEngines = config.services().cosmos().engines();
            String startupMode = cosmosEngines.startup();
            for (CosmosApi api : CosmosApi.values()) {
                EmulatorConfig.CosmosApiConfig apiCfg = resolveCosmosApiConfig(cosmosEngines, api);
                if (apiCfg.enabled()) {
                    boolean embedded = cosmosEngineRegistry.resolve(api)
                            .map(p -> p.engine().isEmbedded()).orElse(false);
                    String image = apiCfg.image().orElseGet(() ->
                        cosmosEngineRegistry.resolve(api)
                            .map(p -> p.engine().defaultImage())
                            .orElse("?"));
                    if (embedded) {
                        sb.append(String.format("     %-10s [enabled ] mode:embedded\n",
                            api.name().toLowerCase()));
                    } else {
                        sb.append(String.format("     %-10s [enabled ] startup:%-10s image:%s\n",
                            api.name().toLowerCase(), startupMode, image));
                    }
                }
            }
        }
        if (config.services().keyVault().enabled()) {
            sb.append(serviceStatus("keyvault", true, getStorageMode("keyvault")));
        }
        if (config.services().eventHub().enabled()) {
            String amqpInfo = "amqp:" + config.services().eventHub().amqpPort()
                    + "  ns:" + config.services().eventHub().defaultNamespace();
            if (config.services().eventHub().kafkaEnabled()) {
                amqpInfo += "  kafka:" + config.services().eventHub().kafkaPort();
            }
            sb.append(serviceStatusDocker("eventhub", true, amqpInfo));
        }
        if (config.services().serviceBus().enabled()) {
            String amqpInfo = "amqp:" + config.services().serviceBus().amqpPort()
                    + "  (on-demand)  storage:" + getStorageMode("servicebus");
            sb.append(serviceStatusDocker("servicebus", true, amqpInfo));
        }
        if (config.services().aks().enabled()) {
            String aksInfo = config.services().aks().mocked()
                    ? "mocked  (no k3s)"
                    : "k3s:" + config.services().aks().defaultImage()
                            + "  ports:" + config.services().aks().apiServerBasePort()
                            + "-" + config.services().aks().apiServerMaxPort();
            sb.append(serviceStatusDocker("aks", true, aksInfo));
        }
        if (config.services().vm().enabled()) {
            String vmInfo = config.services().vm().mocked()
                    ? "mocked  (no docker)"
                    : "image:" + config.services().vm().defaultImage();
            sb.append(serviceStatusDocker("vm", true, vmInfo));
        }
        LOGGER.info(sb.toString());
        LOGGER.info("=== Local Azure Emulator Ready ===");
    }

    private String getStorageMode(String service) {
        return switch (service) {
            case "blob"      -> config.storage().services().blob().mode().orElse(config.storage().mode());
            case "queue"     -> config.storage().services().queue().mode().orElse(config.storage().mode());
            case "table"     -> config.storage().services().table().mode().orElse(config.storage().mode());
            case "appconfig" -> config.storage().services().appConfig().mode().orElse(config.storage().mode());
            case "cosmos"    -> config.storage().services().cosmos().mode().orElse(config.storage().mode());
            case "keyvault"  -> config.storage().services().keyVault().mode().orElse(config.storage().mode());
            case "servicebus" -> config.storage().services().serviceBus().mode().orElse(config.storage().mode());
            default          -> config.storage().mode();
        };
    }

    private static String serviceStatus(String name, boolean enabled, String storageMode) {
        String status = enabled ? "enabled " : "disabled";
        return String.format("   %-9s [%s]  storage: %s\n", name, status, storageMode);
    }

    private static String serviceStatusDocker(String name, boolean enabled, String dockerHost) {
        String status = enabled ? "enabled " : "disabled";
        return String.format("   %-9s [%s]  docker: %s\n", name, status, dockerHost);
    }

    private static EmulatorConfig.CosmosApiConfig resolveCosmosApiConfig(
            EmulatorConfig.CosmosEngineConfig cosmosEngines, CosmosApi api) {
        return switch (api) {
            case NOSQL       -> cosmosEngines.nosql();
            case MONGODB     -> cosmosEngines.mongodb();
            case POSTGRESQL  -> cosmosEngines.postgresql();
            case CASSANDRA   -> cosmosEngines.cassandra();
            case GREMLIN     -> cosmosEngines.gremlin();
            case TABLE       -> cosmosEngines.table();
        };
    }
}
