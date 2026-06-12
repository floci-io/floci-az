package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.services.eventhub.ArtemisTlsGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages one Artemis container per Service Bus namespace.
 * Entity topology (queues, topics, subscriptions) is provisioned dynamically
 * via Jolokia when the management API creates entities — unlike Event Hubs,
 * which pre-configures topology in broker.xml.
 */
@ApplicationScoped
public class ServiceBusNamespaceManager {

    private static final Logger LOG = Logger.getLogger(ServiceBusNamespaceManager.class);

    private static final int AMQP_PORT = 5672;
    private static final int AMQPS_PORT = 5671;
    private static final int JOLOKIA_PORT = 8161;

    /**
     * Immutable snapshot of a running namespace.
     *
     * @param mocked       true when no real broker is running (management API only)
     * @param jolokiaHost  hostname/IP to reach Jolokia from floci-az
     * @param jolokiaPort  host-side port for the Artemis Jolokia console
     */
    public record NamespaceState(
            String containerId,
            int amqpHostPort,
            int amqpsHostPort,
            String tlsCertPem,
            String jolokiaHost,
            int jolokiaPort,
            boolean mocked) {}

    private final ConcurrentHashMap<String, NamespaceState> namespaces = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServiceBusCbsResponder> cbsResponders = new ConcurrentHashMap<>();

    private final EmulatorConfig config;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ServiceBusConfigGenerator configGenerator;
    private final ArtemisTlsGenerator tlsGenerator;

    @Inject
    public ServiceBusNamespaceManager(EmulatorConfig config,
                                       ContainerBuilder containerBuilder,
                                       ContainerLifecycleManager lifecycleManager,
                                       ServiceBusConfigGenerator configGenerator,
                                       ArtemisTlsGenerator tlsGenerator) {
        this.config = config;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.configGenerator = configGenerator;
        this.tlsGenerator = tlsGenerator;
    }

    public NamespaceState startNamespace(String namespaceName, int amqpHostPort, int amqpsHostPort) {
        String containerName = containerName(namespaceName);

        LOG.infov("Starting Artemis broker for Service Bus namespace ''{0}'' (plain:{1}, TLS:{2})",
                namespaceName,
                amqpHostPort == 0 ? "dynamic" : amqpHostPort,
                amqpsHostPort == 0 ? "dynamic" : amqpsHostPort);

        ArtemisTlsGenerator.TlsBundle tls;
        try {
            tls = tlsGenerator.generate(containerName);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to generate TLS certificate for Service Bus namespace '" + namespaceName
                            + "': " + rootMessage(e), e);
        }

        String brokerXml = configGenerator.generate(namespaceName);
        lifecycleManager.removeIfExists(containerName);

        ContainerSpec spec = containerBuilder.newContainer(config.services().serviceBus().artemisImage())
                .withName(containerName)
                .withEnv("ANONYMOUS_LOGIN", "true")
                .withPortBinding(AMQP_PORT, amqpHostPort)
                .withPortBinding(AMQPS_PORT, amqpsHostPort)
                .withDynamicPort(JOLOKIA_PORT)
                .withDockerNetwork(config.services().dockerNetwork())
                .withLogRotation()
                .build();

        String containerId = lifecycleManager.create(spec);
        lifecycleManager.copyFileToContainer(containerId, brokerXml,
                "/var/lib/artemis-instance/etc-override/broker.xml");
        lifecycleManager.copyBytesToContainer(containerId, tls.pkcs12Bytes(),
                "/var/lib/artemis-instance/etc-override/artemis.p12");

        ContainerLifecycleManager.ContainerInfo info = lifecycleManager.startCreated(containerId, spec);

        EndpointInfo amqpEndpoint  = info.getEndpoint(AMQP_PORT);
        EndpointInfo amqpsEndpoint = info.getEndpoint(AMQPS_PORT);
        EndpointInfo jolokiaEndpoint = info.getEndpoint(JOLOKIA_PORT);

        waitForPort(amqpEndpoint, "AMQP");
        waitForPort(amqpsEndpoint, "AMQPS");

        ServiceBusCbsResponder cbs = new ServiceBusCbsResponder(amqpEndpoint.host(), amqpEndpoint.port());
        cbs.start();
        cbsResponders.put(namespaceName, cbs);

        NamespaceState state = new NamespaceState(
                containerId,
                amqpEndpoint.port(),
                amqpsEndpoint.port(),
                tls.certPem(),
                jolokiaEndpoint.host(),
                jolokiaEndpoint.port(),
                false);
        namespaces.put(namespaceName, state);

        LOG.infov("Service Bus namespace ''{0}'' ready: amqp:{1}, amqps:{2}",
                namespaceName, amqpEndpoint, amqpsEndpoint);
        return state;
    }

    /** Registers a mocked namespace with no backing broker — management API only. */
    public NamespaceState startMockedNamespace(String namespaceName) {
        NamespaceState state = new NamespaceState(null, 0, 0, "", "", 0, true);
        namespaces.put(namespaceName, state);
        LOG.infov("Registered mocked Service Bus namespace ''{0}'' (no AMQP broker)", namespaceName);
        return state;
    }

    public boolean stopNamespace(String namespaceName) {
        NamespaceState state = namespaces.remove(namespaceName);
        if (state == null) {
            return false;
        }
        ServiceBusCbsResponder cbs = cbsResponders.remove(namespaceName);
        if (cbs != null) {
            cbs.stop();
        }
        if (!state.mocked() && state.containerId() != null) {
            lifecycleManager.stopAndRemove(state.containerId(), null);
            LOG.infov("Stopped Artemis container for Service Bus namespace ''{0}''", namespaceName);
        }
        return true;
    }

    public Optional<NamespaceState> getNamespace(String namespaceName) {
        return Optional.ofNullable(namespaces.get(namespaceName));
    }

    public Map<String, NamespaceState> listNamespaces() {
        return Map.copyOf(namespaces);
    }

    public void shutdownAll() {
        for (String ns : List.copyOf(namespaces.keySet())) {
            try {
                stopNamespace(ns);
            } catch (Exception e) {
                LOG.warnf(e, "Error stopping Service Bus namespace '%s'", ns);
            }
        }
    }

    // ── Jolokia entity management ─────────────────────────────────────────────

    /** Provisions an ANYCAST queue in the running Artemis broker. */
    public void jolokiaCreateQueue(String namespaceName, String queueName) {
        withJolokia(namespaceName, (http, baseUrl, auth, mbean) -> {
            jolokiaExec(http, baseUrl, auth, mbean,
                    "createAddress(java.lang.String,java.lang.String)",
                    jsonArr(queueName, "ANYCAST"));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "createQueue(java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean,int,boolean,boolean)",
                    jsonArr(queueName, "ANYCAST", queueName, "", true, -1, false, false));
        });
    }

    /** Removes an ANYCAST queue from the running Artemis broker. */
    public void jolokiaDeleteQueue(String namespaceName, String queueName) {
        withJolokia(namespaceName, (http, baseUrl, auth, mbean) -> {
            jolokiaExec(http, baseUrl, auth, mbean,
                    "destroyQueue(java.lang.String,boolean,boolean)",
                    jsonArr(queueName, true, true));
        });
    }

    /** Provisions a MULTICAST address for a topic in the running Artemis broker. */
    public void jolokiaCreateTopic(String namespaceName, String topicName) {
        withJolokia(namespaceName, (http, baseUrl, auth, mbean) -> {
            jolokiaExec(http, baseUrl, auth, mbean,
                    "createAddress(java.lang.String,java.lang.String)",
                    jsonArr(topicName, "MULTICAST"));
        });
    }

    /** Removes a MULTICAST address and all its subscription queues from the running Artemis broker. */
    public void jolokiaDeleteTopic(String namespaceName, String topicName) {
        withJolokia(namespaceName, (http, baseUrl, auth, mbean) -> {
            jolokiaExec(http, baseUrl, auth, mbean,
                    "deleteAddress(java.lang.String,boolean)",
                    jsonArr(topicName, true));
        });
    }

    /**
     * Provisions a durable MULTICAST queue (subscription) bound to the topic address.
     * The queue name follows the Azure convention: {@code {topicName}/Subscriptions/{subName}}.
     */
    public void jolokiaCreateSubscription(String namespaceName, String topicName, String subName) {
        String queueName = topicName + "/Subscriptions/" + subName;
        withJolokia(namespaceName, (http, baseUrl, auth, mbean) -> {
            // address=topicName (MULTICAST), queue name=topicName/Subscriptions/subName
            jolokiaExec(http, baseUrl, auth, mbean,
                    "createQueue(java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean,int,boolean,boolean)",
                    jsonArr(topicName, "MULTICAST", queueName, "", true, -1, false, false));
        });
    }

    /** Removes a subscription queue from the running Artemis broker. */
    public void jolokiaDeleteSubscription(String namespaceName, String topicName, String subName) {
        String queueName = topicName + "/Subscriptions/" + subName;
        withJolokia(namespaceName, (http, baseUrl, auth, mbean) -> {
            jolokiaExec(http, baseUrl, auth, mbean,
                    "destroyQueue(java.lang.String,boolean,boolean)",
                    jsonArr(queueName, true, true));
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    static String containerName(String namespaceName) {
        return "floci-az-servicebus-" + namespaceName;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    @FunctionalInterface
    interface JolokiaAction {
        void run(HttpClient http, String baseUrl, String auth, String mbean) throws Exception;
    }

    private void withJolokia(String namespaceName, JolokiaAction action) {
        NamespaceState state = namespaces.get(namespaceName);
        if (state == null) {
            throw new IllegalStateException("Service Bus namespace not running: " + namespaceName);
        }
        if (state.mocked()) {
            return;
        }
        String baseUrl = "http://" + state.jolokiaHost() + ":" + state.jolokiaPort() + "/console/jolokia";
        String auth = Base64.getEncoder().encodeToString(
                "artemis:artemis".getBytes(StandardCharsets.UTF_8));
        String mbean = "org.apache.activemq.artemis:broker=\\\"floci-az-servicebus-" + namespaceName + "\\\"";
        HttpClient http = HttpClient.newHttpClient();
        waitForJolokia(baseUrl, auth);
        try {
            action.run(http, baseUrl, auth, mbean);
        } catch (Exception e) {
            throw new RuntimeException("Jolokia operation failed for namespace " + namespaceName, e);
        }
    }

    private void waitForJolokia(String url, String auth) {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(3))
                        .header("Authorization", "Basic " + auth)
                        .GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOG.warnv("Artemis Jolokia did not become ready at {0} within 120s", url);
    }

    private void jolokiaExec(HttpClient http, String baseUrl, String auth,
                              String mbean, String operation, String arguments) {
        String body = "{\"type\":\"exec\",\"mbean\":\"" + mbean + "\","
                + "\"operation\":\"" + operation + "\","
                + "\"arguments\":" + arguments + "}";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + auth)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                String err = resp.body();
                if (!err.contains("already exists") && !err.contains("already been deployed")) {
                    LOG.debugv("Jolokia {0}: status={1}", operation.split("\\(")[0], resp.statusCode());
                }
            }
        } catch (Exception e) {
            LOG.debugv("Jolokia call failed ({0}): {1}", operation.split("\\(")[0], e.getMessage());
        }
    }

    private static String jsonArr(Object... values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            Object v = values[i];
            if (v instanceof String s) {
                sb.append("\"").append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            } else {
                sb.append(v);
            }
        }
        return sb.append("]").toString();
    }

    private void waitForPort(EndpointInfo endpoint, String label) {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket(endpoint.host(), endpoint.port())) {
                return;
            } catch (IOException ignored) {
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Interrupted waiting for Service Bus Artemis " + label + " port", e);
            }
        }
        throw new RuntimeException(
                "Artemis did not open " + label + " port " + endpoint + " within 60s");
    }
}
