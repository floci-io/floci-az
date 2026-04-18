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

        LOGGER.info("""
                  ______ _      ____   _____ _____           _____  _______
                 |  ____| |    / __ \ / ____|_   _|         / ____| |___  /
                 | |__  | |   | |  | | |      | |          / /__\ \    / /
                 |  __| | |   | |  | | |      | |         / /____\ \  / /
                 | |    | |___| |__| | |____ _| |_       / /      \ \/ /____
                 |_|    |______\____/ \_____|_____|     /_/        \_\______|
                """+
                " Local Azure Emulator - floci.io\n" +
                " Port    : " + config.port() + "\n" +
                " Auth    : " + config.auth().mode() + "\n" +
                " Storage : " + globalMode + "\n" +
                " Services:\n" +
                serviceStatus("blob",      config.services().blob().enabled(),
                        config.storage().services().blob().mode().orElse(globalMode)) +
                serviceStatus("queue",     config.services().queue().enabled(),
                        config.storage().services().queue().mode().orElse(globalMode)) +
                serviceStatus("table",     config.services().table().enabled(),
                        config.storage().services().table().mode().orElse(globalMode)) +
                serviceStatusDocker("functions", config.services().functions().enabled(),
                        config.services().functions().dockerHost())
        );
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
