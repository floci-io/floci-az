package io.floci.az.services.eventhub;

import io.floci.az.config.EmulatorConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Lifecycle manager for Event Hubs sidecar containers.
 *
 * Namespaces start on-demand via PUT /{account}-eventhub/namespaces/{ns}.
 * When {@code mocked = true}, no containers are started; useful for unit tests.
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
        EmulatorConfig.EventHubConfig eh = config.services().eventHub();
        if (!eh.enabled()) {
            return;
        }
        if (eh.mocked()) {
            LOG.info("Event Hubs service mocked — skipping container startup");
        } else {
            LOG.info("Event Hubs service enabled — namespaces start on-demand via PUT /{account}-eventhub/namespaces/{ns}");
        }
    }

    @PreDestroy
    void onStop() {
        if (!config.services().eventHub().enabled()) return;

        if (kafkaManager.isRunning()) {
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
