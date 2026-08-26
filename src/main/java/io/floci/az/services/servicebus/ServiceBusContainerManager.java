package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Lifecycle manager for Service Bus sidecar containers.
 *
 * When {@code mocked = false} (the default), a namespace starts on its first entity-management
 * call -- not on boot. Namespaces can also be started explicitly via
 * PUT /{account}-servicebus/namespaces/{ns}. All running namespaces are stopped on shutdown.
 *
 * <p>When {@code mocked = true}, no container is started and the namespace is management-plane
 * state only: there is no AMQP data plane.
 */
@ApplicationScoped
public class ServiceBusContainerManager {

    private static final Logger LOG = Logger.getLogger(ServiceBusContainerManager.class);

    private final EmulatorConfig config;
    private final ServiceBusNamespaceManager namespaceManager;

    @Inject
    public ServiceBusContainerManager(EmulatorConfig config,
                                       ServiceBusNamespaceManager namespaceManager) {
        this.config = config;
        this.namespaceManager = namespaceManager;
    }

    void onStart(@Observes StartupEvent ev) {
        EmulatorConfig.ServiceBusConfig sb = config.services().serviceBus();
        if (!sb.enabled()) {
            return;
        }
        if (sb.mocked()) {
            LOG.info("Service Bus service mocked — skipping container startup");
        } else {
            try {
                int reaped = namespaceManager.reapOrphanedContainers();
                if (reaped > 0) {
                    LOG.infov("Removed {0} orphaned Service Bus container(s)", reaped);
                }
            } catch (Exception e) {
                LOG.errorf(e, "Could not reap orphaned Service Bus containers");
            }
            LOG.info("Service Bus service enabled — namespace starts on first entity management call");
        }
    }

    @PreDestroy
    void onStop() {
        if (!config.services().serviceBus().enabled()) {
            return;
        }
        try {
            namespaceManager.shutdownAll();
        } catch (Exception e) {
            LOG.warnf(e, "Error stopping Service Bus namespace containers");
        }
    }
}
