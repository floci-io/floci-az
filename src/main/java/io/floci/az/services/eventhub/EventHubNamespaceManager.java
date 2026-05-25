package io.floci.az.services.eventhub;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.az.core.docker.ContainerSpec;
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
 * Manages one Artemis container per Event Hubs namespace.
 * Each namespace gets its own isolated AMQP broker, analogous to how each RDS instance
 * gets its own PostgreSQL container in the AWS emulator.
 */
@ApplicationScoped
public class EventHubNamespaceManager {

    private static final Logger LOG = Logger.getLogger(EventHubNamespaceManager.class);

    /** Container-internal ports — always the same inside every Artemis container. */
    private static final int AMQP_PORT = 5672;
    private static final int AMQPS_PORT = 5671;
    private static final int JOLOKIA_PORT = 8161;

    /**
     * Immutable snapshot of a running namespace.
     *
     * @param containerId  Docker container ID (null when mocked)
     * @param amqpHostPort host-side port for plain AMQP (0 when mocked)
     * @param amqpsHostPort host-side port for TLS AMQP (0 when mocked)
     * @param tlsCertPem   PEM-encoded self-signed cert for TLS AMQP (empty when mocked)
     * @param mocked       true when no real Artemis broker is backing this namespace
     */
    public record NamespaceState(String containerId, int amqpHostPort, int amqpsHostPort, String tlsCertPem, boolean mocked) {}

    private final ConcurrentHashMap<String, NamespaceState> namespaces = new ConcurrentHashMap<>();

    private final EmulatorConfig config;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ArtemisConfigGenerator configGenerator;
    private final ArtemisTlsGenerator tlsGenerator;

    @Inject
    public EventHubNamespaceManager(EmulatorConfig config,
                                     ContainerBuilder containerBuilder,
                                     ContainerLifecycleManager lifecycleManager,
                                     ArtemisConfigGenerator configGenerator,
                                     ArtemisTlsGenerator tlsGenerator) {
        this.config = config;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.configGenerator = configGenerator;
        this.tlsGenerator = tlsGenerator;
    }

    /**
     * Starts an Artemis container for the given namespace and registers it.
     * Host ports of {@code 0} mean "allocate dynamically".
     */
    public NamespaceState startNamespace(String namespaceName,
                                          Map<String, List<String>> entities,
                                          int amqpHostPort, int amqpsHostPort) {
        String containerName = containerName(namespaceName);
        List<String> amqpHostnames = List.of("localhost", containerName);

        LOG.infov("Starting Artemis broker for Event Hubs namespace ''{0}'' (plain:{1}, TLS:{2})",
                namespaceName, amqpHostPort == 0 ? "dynamic" : amqpHostPort,
                amqpsHostPort == 0 ? "dynamic" : amqpsHostPort);

        ArtemisTlsGenerator.TlsBundle tls;
        try {
            tls = tlsGenerator.generate();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to generate TLS certificate for namespace: " + namespaceName, e);
        }

        String brokerXml = configGenerator.generate(namespaceName, entities);

        lifecycleManager.removeIfExists(containerName);

        ContainerSpec spec = containerBuilder.newContainer(config.services().eventHub().artemisImage())
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

        EndpointInfo amqpEndpoint = info.getEndpoint(AMQP_PORT);
        waitForPort(amqpEndpoint, "AMQP");
        EndpointInfo amqpsEndpoint = info.getEndpoint(AMQPS_PORT);
        waitForPort(amqpsEndpoint, "AMQPS");

        // Topology is pre-configured in broker.xml; Jolokia setup runs in background
        // to handle any dynamic additions (e.g. consumer groups created after startup).
        EndpointInfo jolokiaEndpoint = info.getEndpoint(JOLOKIA_PORT);
        Thread.ofVirtual().name("jolokia-setup-" + namespaceName).start(() -> {
            waitForJolokia(jolokiaEndpoint);
            setupAmqpTopology(jolokiaEndpoint, namespaceName, entities, amqpHostnames);
        });

        NamespaceState state = new NamespaceState(
                containerId, amqpEndpoint.port(), amqpsEndpoint.port(), tls.certPem(), false);
        namespaces.put(namespaceName, state);

        LOG.infov("Event Hubs namespace ''{0}'' ready: amqp:{1}, amqps:{2}",
                namespaceName, amqpEndpoint, amqpsEndpoint);
        return state;
    }

    /** Registers a mocked namespace with no backing broker — management API only. */
    public NamespaceState registerMockedNamespace(String namespaceName) {
        NamespaceState state = new NamespaceState(null, 0, 0, "", true);
        namespaces.put(namespaceName, state);
        LOG.infov("Registered mocked Event Hubs namespace ''{0}'' (no AMQP broker)", namespaceName);
        return state;
    }

    /**
     * Stops and removes the Artemis container for the given namespace.
     *
     * @return {@code true} if the namespace existed and was stopped; {@code false} if unknown
     */
    public boolean stopNamespace(String namespaceName) {
        NamespaceState state = namespaces.remove(namespaceName);
        if (state == null) {
            return false;
        }
        if (!state.mocked() && state.containerId() != null) {
            lifecycleManager.stopAndRemove(state.containerId(), null);
            LOG.infov("Stopped Artemis container for namespace ''{0}''", namespaceName);
        }
        return true;
    }

    public Optional<NamespaceState> getNamespace(String namespaceName) {
        return Optional.ofNullable(namespaces.get(namespaceName));
    }

    public Map<String, NamespaceState> listNamespaces() {
        return Map.copyOf(namespaces);
    }

    /** Stops all running namespace containers. Called on application shutdown. */
    public void shutdownAll() {
        for (String ns : List.copyOf(namespaces.keySet())) {
            try {
                stopNamespace(ns);
            } catch (Exception e) {
                LOG.warnf(e, "Error stopping namespace '%s'", ns);
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    static String containerName(String namespaceName) {
        return "floci-az-artemis-" + namespaceName;
    }

    private void waitForJolokia(EndpointInfo endpoint) {
        String url = "http://" + endpoint + "/console/jolokia";
        String auth = Base64.getEncoder().encodeToString(
                "artemis:artemis".getBytes(StandardCharsets.UTF_8));
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
                    LOG.debugv("Artemis Jolokia ready at {0}", url);
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
        LOG.warnv("Artemis Jolokia did not become ready at {0} within 120s; topology setup skipped", url);
    }

    /**
     * Creates ANYCAST addresses, durable queues, and exclusive diverts via the Artemis Jolokia API.
     * Sender targets entity address; exclusive diverts fan out to each consumer group's durable queue.
     */
    private void setupAmqpTopology(EndpointInfo jolokia, String namespace,
                                    Map<String, List<String>> entities, List<String> hostnames) {
        String baseUrl = "http://" + jolokia + "/console/jolokia";
        String auth = Base64.getEncoder().encodeToString(
                "artemis:artemis".getBytes(StandardCharsets.UTF_8));
        String mbean = "org.apache.activemq.artemis:broker=\\\"floci-az-eventhubs\\\"";
        HttpClient http = HttpClient.newHttpClient();

        for (String hostname : hostnames) {
            for (Map.Entry<String, List<String>> entry : entities.entrySet()) {
                String entityName = entry.getKey();
                String entityAddr = "amqp://" + hostname.toLowerCase(java.util.Locale.US) + "/" + namespace + "/" + entityName;

                jolokiaExec(http, baseUrl, auth, mbean,
                        "createAddress(java.lang.String,java.lang.String)",
                        jsonArr(entityAddr, "ANYCAST"));

                for (String cg : entry.getValue()) {
                    String cgAddr = entityAddr + "/" + cg;
                    String divertName = (hostname + "-" + entityName + "-to-" + cg)
                            .replaceAll("[^A-Za-z0-9_-]", "-");

                    jolokiaExec(http, baseUrl, auth, mbean,
                            "createAddress(java.lang.String,java.lang.String)",
                            jsonArr(cgAddr, "ANYCAST"));

                    jolokiaExec(http, baseUrl, auth, mbean,
                            "createQueue(java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean,int,boolean,boolean)",
                            jsonArr(cgAddr, "ANYCAST", cgAddr, "", true, -1, false, false));

                    jolokiaExec(http, baseUrl, auth, mbean,
                            "createDivert(java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean,java.lang.String,java.lang.String)",
                            jsonArr(divertName, divertName, entityAddr, cgAddr, true, "", ""));
                }
            }
        }
        LOG.infov("AMQP topology configured for namespace ''{0}'': {1} entities × {2} hostnames",
                namespace, entities.size(), hostnames.size());
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
                        "Interrupted waiting for Artemis " + label + " port", e);
            }
        }
        throw new RuntimeException(
                "Artemis did not open " + label + " port " + endpoint + " within 60s");
    }
}
