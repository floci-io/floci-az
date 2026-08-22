package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerStorageHelper;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.services.eventhub.ArtemisTlsGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
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

    static final String ARTEMIS_EXTENSION_RESOURCE = "/artemis/servicebus-artemis-extension.jar";
    static final String PROTON_PATCH_RESOURCE = "/artemis/proton-j-0.34.1-floci-az-proton-patch.jar";
    static final String ARTEMIS_AMQP_PATCH_RESOURCE =
            "/artemis/artemis-amqp-protocol-2.44.0-floci-az-artemis-amqp-patch.jar";
    private static final String ARTEMIS_EXTENSION_PATH =
            "/var/lib/artemis-instance/lib/floci-az-servicebus-extension.jar";
    private static final String PROTON_J_PATH = "/opt/activemq-artemis/lib/proton-j-0.34.1.jar";
    private static final String ARTEMIS_AMQP_PATH =
            "/opt/activemq-artemis/lib/artemis-amqp-protocol-2.44.0.jar";
    private static final String MANAGEMENT_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <management-context xmlns="http://activemq.apache.org/schema">
              <authorisation>
                <allowlist>
                  <entry domain="hawtio"/>
                </allowlist>
                <default-access>
                  <access method="list*" roles="amq"/>
                  <access method="get*" roles="amq"/>
                  <access method="is*" roles="amq"/>
                </default-access>
                <role-access>
                  <match domain="org.apache.activemq.artemis">
                    <access method="*" roles="amq"/>
                  </match>
                  <match domain="io.floci.az.artemis">
                    <access method="*" roles="amq"/>
                  </match>
                </role-access>
              </authorisation>
            </management-context>
            """;
    private static final int AMQP_PORT = 5672;
    private static final int AMQPS_PORT = 5671;
    private static final int JOLOKIA_PORT = 8161;
    private static final String DEAD_LETTER_QUEUE_SUFFIX = "/$DeadLetterQueue";
    private static final String SUBSCRIPTION_DIVERT_SUFFIX = "/$Divert";
    private static final String TOPIC_ADDRESS_SUFFIX = "/$Topic";
    private static final String TOPIC_DIVERT_SUFFIX = "/$TopicDivert";
    private static final String SESSION_METADATA_PREFIX = "floci-az:servicebus-session:";
    private static final String DUPLICATE_DETECTION_MBEAN =
            "io.floci.az.artemis:type=ServiceBusDuplicateDetection";
    private static final String EXPIRY_MBEAN = "io.floci.az.artemis:type=ServiceBusExpiry";

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

    public synchronized NamespaceState startNamespace(
            String namespaceName, int amqpHostPort, int amqpsHostPort) {
        NamespaceState existing = namespaces.get(namespaceName);
        if (existing != null) {
            return existing;
        }
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
        lifecycleManager.copyFileToContainer(containerId, MANAGEMENT_XML,
                "/var/lib/artemis-instance/etc-override/management.xml");
        lifecycleManager.copyBytesToContainer(containerId, tls.pkcs12Bytes(),
                "/var/lib/artemis-instance/etc-override/artemis.p12");
        lifecycleManager.copyBytesToContainer(containerId, loadArtemisExtension(),
                ARTEMIS_EXTENSION_PATH);
        lifecycleManager.copyBytesToContainer(containerId, loadResource(PROTON_PATCH_RESOURCE),
                PROTON_J_PATH);
        lifecycleManager.copyBytesToContainer(containerId, loadResource(ARTEMIS_AMQP_PATCH_RESOURCE),
                ARTEMIS_AMQP_PATH);

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
    public void jolokiaCreateQueue(
            String namespaceName,
            String queueName,
            boolean requiresSession,
            long lockDurationSeconds,
            int maxDeliveryAttempts,
            ServiceBusEntityXml.DuplicateDetectionSettings duplicateDetection,
            ServiceBusEntityXml.MessageLifetimeSettings lifetime) {
        String deadLetterQueue = queueName + DEAD_LETTER_QUEUE_SUFFIX;
        String addressSettings = "{\"deadLetterAddress\":" + jsonString(deadLetterQueue)
                + ",\"maxDeliveryAttempts\":" + maxDeliveryAttempts
                + ",\"autoCreateQueues\":false,\"autoCreateAddresses\":false}";
        withJolokia(namespaceName, (http, baseUrl, auth, mbean) -> {
            jolokiaExec(http, baseUrl, auth, mbean,
                    "createAddress(java.lang.String,java.lang.String)",
                    jsonArr(queueName, "ANYCAST"));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "createQueue(java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean,int,boolean,boolean)",
                    jsonArr(queueName, "ANYCAST", queueName, "", true, -1, false, false));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "createAddress(java.lang.String,java.lang.String)",
                    jsonArr(deadLetterQueue, "ANYCAST"));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "createQueue(java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean,int,boolean,boolean)",
                    jsonArr(deadLetterQueue, "ANYCAST", deadLetterQueue, "", true, -1, false, false));
            applySessionMetadata(http, baseUrl, auth, mbean, queueName,
                    requiresSession, lockDurationSeconds);
            applySessionMetadata(http, baseUrl, auth, mbean, deadLetterQueue,
                    requiresSession, lockDurationSeconds);
            jolokiaExec(http, baseUrl, auth, mbean,
                    "addAddressSettings(java.lang.String,java.lang.String)",
                    jsonArr(queueName, addressSettings));
            configureDuplicateDetection(
                    http, baseUrl, auth, queueName, duplicateDetection);
            configureExpiry(
                    http, baseUrl, auth, queueName, lifetime.ttlMillis(),
                    lifetime.deadLetterOnExpiration() ? deadLetterQueue : "");
        });
    }

    /** Removes an ANYCAST queue from the running Artemis broker. */
    public void jolokiaDeleteQueue(String namespaceName, String queueName) {
        String deadLetterQueue = queueName + DEAD_LETTER_QUEUE_SUFFIX;
        withJolokia(namespaceName, (http, baseUrl, auth, mbean) -> {
            jolokiaExec(http, baseUrl, auth, mbean,
                    "destroyQueue(java.lang.String,boolean,boolean)",
                    jsonArr(queueName, true, true));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "destroyQueue(java.lang.String,boolean,boolean)",
                    jsonArr(deadLetterQueue, true, true));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "deleteAddress(java.lang.String,boolean)",
                    jsonArr(deadLetterQueue, true));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "removeAddressSettings(java.lang.String)",
                    jsonArr(queueName));
            removeDuplicateDetection(http, baseUrl, auth, queueName);
            removeExpiry(http, baseUrl, auth, queueName);
        });
    }

    /**
     * Provisions an ANYCAST ingress accepted by SDK sender links and diverts it exclusively to a
     * hidden MULTICAST address. Subscription diverts fan messages out from that hidden address.
     */
    public void jolokiaCreateTopic(
            String namespaceName,
            String topicName,
            ServiceBusEntityXml.DuplicateDetectionSettings duplicateDetection) {
        String topicAddress = topicName + TOPIC_ADDRESS_SUFFIX;
        withJolokia(namespaceName, (http, baseUrl, auth, mbean) -> {
            jolokiaExec(http, baseUrl, auth, mbean,
                    "createAddress(java.lang.String,java.lang.String)",
                    jsonArr(topicName, "ANYCAST"));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "createQueue(java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean,int,boolean,boolean)",
                    jsonArr(topicName, "ANYCAST", topicName, "", true, -1, false, false));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "createAddress(java.lang.String,java.lang.String)",
                    jsonArr(topicAddress, "MULTICAST"));
            jolokiaCreateDivert(http, baseUrl, auth, mbean,
                    topicName + TOPIC_DIVERT_SUFFIX, topicName, topicAddress, "", true);
            configureDuplicateDetection(
                    http, baseUrl, auth, topicName, duplicateDetection);
        });
    }

    /** Removes a topic ingress address and its hidden fan-out address from Artemis. */
    public void jolokiaDeleteTopic(String namespaceName, String topicName) {
        String topicAddress = topicName + TOPIC_ADDRESS_SUFFIX;
        withJolokia(namespaceName, (http, baseUrl, auth, mbean) -> {
            removeDuplicateDetection(http, baseUrl, auth, topicName);
            jolokiaExec(http, baseUrl, auth, mbean,
                    "destroyDivert(java.lang.String)",
                    jsonArr(topicName + TOPIC_DIVERT_SUFFIX));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "destroyQueue(java.lang.String,boolean,boolean)",
                    jsonArr(topicName, true, true));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "destroyDivert(java.lang.String)",
                    jsonArr(topicName + TOPIC_DIVERT_SUFFIX));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "destroyQueue(java.lang.String,boolean,boolean)",
                    jsonArr(topicName, true, true));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "deleteAddress(java.lang.String,boolean)",
                    jsonArr(topicName, true));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "deleteAddress(java.lang.String,boolean)",
                    jsonArr(topicAddress, true));
        });
    }

    private void configureDuplicateDetection(
            HttpClient http,
            String baseUrl,
            String auth,
            String address,
            ServiceBusEntityXml.DuplicateDetectionSettings settings) {
        if (!settings.enabled()) {
            return;
        }
        jolokiaExec(http, baseUrl, auth, DUPLICATE_DETECTION_MBEAN,
                "configure(java.lang.String,long)",
                jsonArr(address, Math.multiplyExact(settings.historySeconds(), 1_000L)));
    }

    private void removeDuplicateDetection(
            HttpClient http, String baseUrl, String auth, String address) {
        jolokiaExec(http, baseUrl, auth, DUPLICATE_DETECTION_MBEAN,
                "remove(java.lang.String)", jsonArr(address));
    }

    /**
     * Provisions a durable subscription queue with its own address and dead-letter queue.
     * A divert copies matching messages from the MULTICAST topic address to the subscription's
     * ANYCAST address. The queue name follows the Azure convention:
     * {@code {topicName}/Subscriptions/{subName}}.
     *
     * @param filter Artemis core filter (SQL92 selector) applied to the queue — the compiled
     *               form of the subscription's rules; empty string matches everything
     */
    public void jolokiaCreateSubscription(
            String namespaceName,
            String topicName,
            String subName,
            String filter,
            boolean requiresSession,
            long lockDurationSeconds,
            int maxDeliveryAttempts,
            ServiceBusEntityXml.DuplicateDetectionSettings duplicateDetection,
            ServiceBusEntityXml.MessageLifetimeSettings lifetime) {
        String queueName = topicName + "/Subscriptions/" + subName;
        String deadLetterQueue = queueName + DEAD_LETTER_QUEUE_SUFFIX;
        String divertName = queueName + SUBSCRIPTION_DIVERT_SUFFIX;
        String addressSettings = "{\"deadLetterAddress\":" + jsonString(deadLetterQueue)
                + ",\"maxDeliveryAttempts\":" + maxDeliveryAttempts
                + ",\"autoCreateQueues\":false,\"autoCreateAddresses\":false}";
        withJolokia(namespaceName, (http, baseUrl, auth, mbean) -> {
            jolokiaExec(http, baseUrl, auth, mbean,
                    "createAddress(java.lang.String,java.lang.String)",
                    jsonArr(queueName, "ANYCAST"));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "createQueue(java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean,int,boolean,boolean)",
                    jsonArr(queueName, "ANYCAST", queueName, "", true, -1, false, false));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "createAddress(java.lang.String,java.lang.String)",
                    jsonArr(deadLetterQueue, "ANYCAST"));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "createQueue(java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean,int,boolean,boolean)",
                    jsonArr(deadLetterQueue, "ANYCAST", deadLetterQueue, "", true, -1, false, false));
            applySessionMetadata(http, baseUrl, auth, mbean, queueName,
                    requiresSession, lockDurationSeconds);
            applySessionMetadata(http, baseUrl, auth, mbean, deadLetterQueue,
                    requiresSession, lockDurationSeconds);
            jolokiaExec(http, baseUrl, auth, mbean,
                    "addAddressSettings(java.lang.String,java.lang.String)",
                    jsonArr(queueName, addressSettings));
            jolokiaCreateDivert(http, baseUrl, auth, mbean, divertName,
                    topicName + TOPIC_ADDRESS_SUFFIX, queueName, filter, false);
            configureDuplicateDetection(
                    http, baseUrl, auth, queueName, duplicateDetection);
            configureExpiry(
                    http, baseUrl, auth, queueName, lifetime.ttlMillis(),
                    lifetime.deadLetterOnExpiration() ? deadLetterQueue : "");
        });
    }

    /**
     * Replaces the filter of an existing subscription divert. The broker applies the new filter
     * to future routing only, matching Azure's rule-change semantics: messages already routed to
     * the subscription stay, and attached receivers stay connected to the unchanged queue.
     */
    public void jolokiaUpdateSubscriptionFilter(String namespaceName, String topicName, String subName,
                                                 String filter) {
        String queueName = topicName + "/Subscriptions/" + subName;
        String divertName = queueName + SUBSCRIPTION_DIVERT_SUFFIX;
        withJolokia(namespaceName, (http, baseUrl, auth, mbean) -> {
            jolokiaExec(http, baseUrl, auth, mbean,
                    "destroyDivert(java.lang.String)",
                    jsonArr(divertName));
            jolokiaCreateDivert(http, baseUrl, auth, mbean, divertName,
                    topicName + TOPIC_ADDRESS_SUFFIX, queueName, filter, false);
        });
    }

    /** Removes a subscription queue, divert, dead-letter queue, and address settings. */
    public void jolokiaDeleteSubscription(String namespaceName, String topicName, String subName) {
        String queueName = topicName + "/Subscriptions/" + subName;
        String deadLetterQueue = queueName + DEAD_LETTER_QUEUE_SUFFIX;
        String divertName = queueName + SUBSCRIPTION_DIVERT_SUFFIX;
        withJolokia(namespaceName, (http, baseUrl, auth, mbean) -> {
            jolokiaExec(http, baseUrl, auth, mbean,
                    "destroyDivert(java.lang.String)",
                    jsonArr(divertName));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "destroyQueue(java.lang.String,boolean,boolean)",
                    jsonArr(queueName, true, true));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "deleteAddress(java.lang.String,boolean)",
                    jsonArr(queueName, true));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "destroyQueue(java.lang.String,boolean,boolean)",
                    jsonArr(deadLetterQueue, true, true));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "deleteAddress(java.lang.String,boolean)",
                    jsonArr(deadLetterQueue, true));
            jolokiaExec(http, baseUrl, auth, mbean,
                    "removeAddressSettings(java.lang.String)",
                    jsonArr(queueName));
            removeDuplicateDetection(http, baseUrl, auth, queueName);
            removeExpiry(http, baseUrl, auth, queueName);
        });
    }

    private void configureExpiry(
            HttpClient http, String baseUrl, String auth, String queueName, long ttlMillis,
            String deadLetterAddress) {
        jolokiaExec(http, baseUrl, auth, EXPIRY_MBEAN,
                "configure(java.lang.String,long,java.lang.String)",
                jsonArr(queueName, ttlMillis, deadLetterAddress));
    }

    private void removeExpiry(
            HttpClient http, String baseUrl, String auth, String queueName) {
        jolokiaExec(http, baseUrl, auth, EXPIRY_MBEAN,
                "remove(java.lang.String)", jsonArr(queueName));
    }
    // ── Private helpers ───────────────────────────────────────────────────────

    String containerName(String namespaceName) {
        return ContainerStorageHelper.dockerName(config, "servicebus-" + namespaceName);
    }

    private static byte[] loadArtemisExtension() {
        return loadResource(ARTEMIS_EXTENSION_RESOURCE);
    }

    private static byte[] loadResource(String resource) {
        try (InputStream stream = ServiceBusNamespaceManager.class.getResourceAsStream(
                resource)) {
            if (stream == null) {
                throw new IllegalStateException("Embedded Artemis resource not found: " + resource);
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read embedded Artemis resource: " + resource, e);
        }
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

    private void jolokiaCreateDivert(HttpClient http, String baseUrl, String auth,
                                      String mbean, String divertName, String sourceAddress,
                                      String forwardingAddress, String filter, boolean exclusive) {
        jolokiaExec(http, baseUrl, auth, mbean,
                "createDivert(java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean,java.lang.String,java.lang.String)",
                jsonArr(divertName, divertName, sourceAddress, forwardingAddress,
                        exclusive, filter, null));
    }

    private void applySessionMetadata(HttpClient http, String baseUrl, String auth,
                                      String mbean, String queueName,
                                      boolean requiresSession, long lockDurationSeconds) {
        if (!requiresSession) {
            return;
        }

        // Artemis security is disabled in this sidecar, so QueueConfiguration.user can carry
        // internal metadata that the patched AMQP protocol handler reads during link attach.
        String metadata = SESSION_METADATA_PREFIX + lockDurationSeconds;
        String queueConfiguration = "{\"name\":" + jsonString(queueName)
                + ",\"user\":" + jsonString(metadata) + "}";
        jolokiaExec(http, baseUrl, auth, mbean,
                "updateQueue(java.lang.String)", jsonArr(queueConfiguration));
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

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\f' -> sb.append("\\f");
                case '\b' -> sb.append("\\b");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String jsonArr(Object... values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(",");
            Object v = values[i];
            if (v instanceof String s) {
                sb.append(jsonString(s));
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
