package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
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

    void onStart(@Observes StartupEvent ev) {
        LOGGER.info("=== Local Azure Emulator Starting ===");
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
}
