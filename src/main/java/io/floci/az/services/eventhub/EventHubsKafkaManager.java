package io.floci.az.services.eventhub;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;

/**
 * Manages the Redpanda sidecar container that provides Kafka-compatible access to Event Hubs.
 * Only started when {@code kafkaEnabled: true}.
 */
@ApplicationScoped
public class EventHubsKafkaManager {

    private static final Logger LOG = Logger.getLogger(EventHubsKafkaManager.class);
    private static final String CONTAINER_NAME = "floci-az-eventhubs-kafka";
    private static final int KAFKA_PORT = 9092;
    private static final int ADMIN_PORT = 9644;

    private final EmulatorConfig config;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;

    private volatile String containerId;

    @Inject
    public EventHubsKafkaManager(EmulatorConfig config, ContainerBuilder containerBuilder,
                                  ContainerLifecycleManager lifecycleManager) {
        this.config = config;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
    }

    public boolean isRunning() {
        return containerId != null;
    }

    public synchronized void start() {
        if (containerId != null) {
            return;
        }
        EmulatorConfig.EventHubConfig eh = config.services().eventHub();
        LOG.infov("Starting Redpanda (Kafka) sidecar for Event Hubs on port {0}", eh.kafkaPort());

        lifecycleManager.removeIfExists(CONTAINER_NAME);

        ContainerSpec spec = containerBuilder.newContainer(eh.redpandaImage())
                .withName(CONTAINER_NAME)
                .withCmd(List.of(
                        "redpanda", "start",
                        "--overprovisioned",
                        "--smp", "1",
                        "--memory", "256M",
                        "--reserve-memory", "0M",
                        "--kafka-addr", "0.0.0.0:" + KAFKA_PORT,
                        "--advertise-kafka-addr", "localhost:" + eh.kafkaPort()))
                .withPortBinding(KAFKA_PORT, eh.kafkaPort())
                .withPortBinding(ADMIN_PORT, 0)
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation()
                .build();

        ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
        containerId = info.containerId();

        int resolvedAdminPort = info.getEndpoint(ADMIN_PORT).port();
        waitForReady(resolvedAdminPort);
        LOG.infov("Event Hubs Kafka (Redpanda) ready at localhost:{0}", eh.kafkaPort());
    }

    public void stop() {
        if (containerId != null) {
            lifecycleManager.stopAndRemove(containerId, null);
            LOG.infov("Redpanda (Kafka) container stopped");
            containerId = null;
        }
    }

    private void waitForReady(int adminPort) {
        String url = "http://localhost:" + adminPort + "/ready";
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                if (conn.getResponseCode() == 200) return;
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for Redpanda admin API", e);
            }
        }
        throw new RuntimeException("Redpanda did not become ready within 30s on " + url);
    }
}
