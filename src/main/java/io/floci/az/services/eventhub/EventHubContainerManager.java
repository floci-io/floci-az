package io.floci.az.services.eventhub;

import io.floci.az.config.EmulatorConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Lifecycle manager for Event Hubs sidecar containers.
 * Starts one Artemis container for the default namespace on application startup
 * and optionally Redpanda for Kafka. Stops all namespace containers on shutdown.
 */
@ApplicationScoped
public class EventHubContainerManager {

    private static final Logger LOG = Logger.getLogger(EventHubContainerManager.class);

    private final EmulatorConfig config;
    private final EventHubNamespaceManager namespaceManager;
    private final EventHubsKafkaManager kafkaManager;

    @Inject
    public EventHubContainerManager(EmulatorConfig config,
                                     EventHubNamespaceManager namespaceManager,
                                     EventHubsKafkaManager kafkaManager) {
        this.config = config;
        this.namespaceManager = namespaceManager;
        this.kafkaManager = kafkaManager;
    }

    void onStart(@Observes StartupEvent ev) {
        if (!config.services().eventHub().enabled()) {
            LOG.info("Event Hubs service disabled — skipping container startup");
            return;
        }

        EmulatorConfig.EventHubConfig eh = config.services().eventHub();
        try {
            Map<String, java.util.List<String>> entities =
                    ArtemisConfigGenerator.parseEntities(eh.entities(), eh.consumerGroups());
            namespaceManager.startNamespace(eh.defaultNamespace(), entities,
                    eh.amqpPort(), eh.amqpTlsPort());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to start default Event Hubs namespace '%s' — AMQP will be unavailable. " +
                    "Ensure Docker is accessible at %s", eh.defaultNamespace(), config.docker().dockerHost());
            return;
        }

        if (eh.kafkaEnabled()) {
            try {
                kafkaManager.start();
            } catch (Exception e) {
                LOG.errorf(e, "Failed to start Redpanda Kafka broker — Event Hubs Kafka will be unavailable");
            }
        }
    }

    @PreDestroy
    void onStop() {
        if (!config.services().eventHub().enabled()) return;

        if (config.services().eventHub().kafkaEnabled()) {
            try {
                kafkaManager.stop();
            } catch (Exception e) {
                LOG.warnf(e, "Error stopping Redpanda Kafka container");
            }
        }

        try {
            namespaceManager.shutdownAll();
        } catch (Exception e) {
            LOG.warnf(e, "Error stopping Event Hubs namespace containers");
        }
    }
}
