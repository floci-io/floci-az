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
        String globalMode = config.storage().mode();
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
