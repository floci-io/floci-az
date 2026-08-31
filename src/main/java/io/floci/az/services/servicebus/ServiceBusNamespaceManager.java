package io.floci.az.services.servicebus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerStorageHelper;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.CurrentContainerNetworkResolver;
import io.floci.az.services.eventhub.ArtemisTlsGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.management.ObjectName;
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
import java.util.LinkedHashMap;
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

    /** Namespace used by SDK spec paths and lazy/boot-time starts when none is named explicitly. */
    public static final String DEFAULT_NAMESPACE = "default";

    private static final Logger LOG = Logger.getLogger(ServiceBusNamespaceManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    private static final Duration MESSAGE_COUNT_TIMEOUT = Duration.ofSeconds(3);
    private static final String MANAGEMENT_SUFFIX = "/$management";
    private static final String DEAD_LETTER_QUEUE_SUFFIX = "/$DeadLetterQueue";
    private static final String SUBSCRIPTION_DIVERT_SUFFIX = "/$Divert";
    private static final String TOPIC_ADDRESS_SUFFIX = "/$Topic";
    private static final String TOPIC_DIVERT_SUFFIX = "/$TopicDivert";
    private static final String SESSION_METADATA_PREFIX = "floci-az:servicebus-session:";
    private static final String SERVICE_LABEL = "servicebus";
    private static final String OWNER_CONTAINER_LABEL = "floci_owner_container";
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

    public record MessageCounts(long active, long deadLetter) {
        static final MessageCounts ZERO = new MessageCounts(0, 0);

        long total() {
            return Math.addExact(active, deadLetter);
        }
    }

    private final ConcurrentHashMap<String, NamespaceState> namespaces = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServiceBusCbsResponder> cbsResponders = new ConcurrentHashMap<>();

    private final EmulatorConfig config;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final CurrentContainerNetworkResolver currentContainerResolver;
    private final ServiceBusConfigGenerator configGenerator;
    private final ArtemisTlsGenerator tlsGenerator;

    @Inject
    public ServiceBusNamespaceManager(EmulatorConfig config,
                                       ContainerBuilder containerBuilder,
                                       ContainerLifecycleManager lifecycleManager,
                                       CurrentContainerNetworkResolver currentContainerResolver,
                                       ServiceBusConfigGenerator configGenerator,
                                       ArtemisTlsGenerator tlsGenerator) {
        this.config = config;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.currentContainerResolver = currentContainerResolver;
        this.configGenerator = configGenerator;
        this.tlsGenerator = tlsGenerator;
    }

    ServiceBusNamespaceManager(EmulatorConfig config,
                               ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               ServiceBusConfigGenerator configGenerator,
                               ArtemisTlsGenerator tlsGenerator) {
        this(config, containerBuilder, lifecycleManager, null, configGenerator, tlsGenerator);
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

        String containerId = null;
        ServiceBusCbsResponder cbs = null;
        try {
            if (!lifecycleManager.removeIfExistsAndConfirm(containerName)) {
                throw new IllegalStateException(
                        "Could not remove stale container " + containerName);
            }

            ArtemisTlsGenerator.TlsBundle tls;
            try {
                tls = tlsGenerator.generate(containerName);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to generate TLS certificate for Service Bus namespace '"
                                + namespaceName + "': " + rootMessage(e), e);
            }

            String brokerXml = configGenerator.generate(namespaceName);
            ContainerSpec spec = containerBuilder.newContainer(
                            config.services().serviceBus().artemisImage())
                    .withName(containerName)
                    .withEnv("ANONYMOUS_LOGIN", "true")
                    .withLabels(serviceContainerLabels())
                    .withPortBinding(AMQP_PORT, amqpHostPort)
                    .withPortBinding(AMQPS_PORT, amqpsHostPort)
                    .withDynamicPort(JOLOKIA_PORT)
                    .withDockerNetwork(config.services().dockerNetwork())
                    .withLogRotation()
                    .build();

            containerId = lifecycleManager.create(spec);
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

            EndpointInfo amqpEndpoint = info.getEndpoint(AMQP_PORT);
            EndpointInfo amqpsEndpoint = info.getEndpoint(AMQPS_PORT);
            EndpointInfo jolokiaEndpoint = info.getEndpoint(JOLOKIA_PORT);

            waitForPort(amqpEndpoint, "AMQP");
            waitForPort(amqpsEndpoint, "AMQPS");

            cbs = new ServiceBusCbsResponder(amqpEndpoint.host(), amqpEndpoint.port());
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
        } catch (RuntimeException e) {
            boolean portsReleased = false;
            try {
                cleanupFailedStart(namespaceName, containerName, containerId, cbs);
                portsReleased = true;
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw new NamespaceStartException(namespaceName, portsReleased, e);
        }
    }

    static final class NamespaceStartException extends RuntimeException {

        private final boolean portsReleased;

        NamespaceStartException(String namespaceName, boolean portsReleased, Throwable cause) {
            super("Failed to start Service Bus namespace '" + namespaceName + "'", cause);
            this.portsReleased = portsReleased;
        }

        boolean portsReleased() {
            return portsReleased;
        }
    }

    private void cleanupFailedStart(String namespaceName, String containerName,
                                    String containerId, ServiceBusCbsResponder cbs) {
        namespaces.remove(namespaceName);
        ServiceBusCbsResponder registeredCbs = cbsResponders.remove(namespaceName);
        if (registeredCbs != null) {
            registeredCbs.stop();
        } else if (cbs != null) {
            cbs.stop();
        }
        if (containerId != null) {
            lifecycleManager.stopAndRemove(containerId, null);
            if (!lifecycleManager.removeIfExistsAndConfirm(containerId)) {
                throw new IllegalStateException(
                        "Could not confirm removal of failed container " + containerId);
            }
        } else {
            if (!lifecycleManager.removeIfExistsAndConfirm(containerName)) {
                throw new IllegalStateException(
                        "Could not confirm removal of failed container " + containerName);
            }
        }
    }

    int reapOrphanedContainers() {
        return lifecycleManager.removeOrphanedContainers(
                containerName(""), ContainerStorageHelper.defaultLabels(config),
                OWNER_CONTAINER_LABEL);
    }

    Map<String, String> serviceContainerLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("floci_service", SERVICE_LABEL);
        if (currentContainerResolver != null) {
            currentContainerResolver.resolveContainerId()
                    .ifPresent(ownerId -> labels.put(OWNER_CONTAINER_LABEL, ownerId));
        }
        return labels;
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

    /** Reads live queue and dead-letter queue depths from Artemis. */
    public MessageCounts getMessageCounts(String namespaceName, String queueName) {
        return getMessageCounts(namespaceName, List.of(queueName))
                .getOrDefault(queueName, MessageCounts.ZERO);
    }

    /** Reads live queue and dead-letter queue depths from Artemis in one bounded Jolokia request. */
    public Map<String, MessageCounts> getMessageCounts(
            String namespaceName, List<String> queueNames) {
        if (queueNames.isEmpty()) {
            return Map.of();
        }
        NamespaceState state = namespaces.get(namespaceName);
        if (state == null || state.mocked()) {
            return zeroMessageCounts(queueNames);
        }

        String baseUrl = "http://" + state.jolokiaHost() + ":" + state.jolokiaPort()
                + "/console/jolokia";
        String auth = Base64.getEncoder().encodeToString(
                "artemis:artemis".getBytes(StandardCharsets.UTF_8));
        HttpClient http = HttpClient.newHttpClient();
        try {
            return jolokiaReadMessageCounts(
                    http, baseUrl, auth, namespaceName, queueNames);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warnf(e, "Failed to read message counts for %d queues in namespace '%s'",
                    queueNames.size(), namespaceName);
            return zeroMessageCounts(queueNames);
        }
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
            createManagementAddress(http, baseUrl, auth, mbean, queueName);
            createManagementAddress(http, baseUrl, auth, mbean, deadLetterQueue);
            applySessionMetadata(http, baseUrl, auth, mbean, queueName,
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
            deleteManagementAddress(http, baseUrl, auth, mbean, queueName);
            deleteManagementAddress(http, baseUrl, auth, mbean, deadLetterQueue);
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
            createManagementAddress(http, baseUrl, auth, mbean, queueName);
            createManagementAddress(http, baseUrl, auth, mbean, deadLetterQueue);
            applySessionMetadata(http, baseUrl, auth, mbean, queueName,
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

    /** Updates the existing subscription divert without interrupting its queue or receivers. */
    public void jolokiaUpdateSubscriptionFilter(String namespaceName, String topicName, String subName,
                                                 String filter) {
        String queueName = topicName + "/Subscriptions/" + subName;
        String divertName = queueName + SUBSCRIPTION_DIVERT_SUFFIX;
        withJolokia(namespaceName, (http, baseUrl, auth, mbean) -> {
            jolokiaExecRequired(http, baseUrl, auth, mbean,
                    "updateDivert(java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.util.Map,java.lang.String)",
                    jsonArr(divertName, queueName, filter, null, Map.of(), "STRIP"));
        });
    }

    /** Removes a subscription queue, divert, dead-letter queue, and address settings. */
    public void jolokiaDeleteSubscription(String namespaceName, String topicName, String subName) {
        String queueName = topicName + "/Subscriptions/" + subName;
        String deadLetterQueue = queueName + DEAD_LETTER_QUEUE_SUFFIX;
        String divertName = queueName + SUBSCRIPTION_DIVERT_SUFFIX;
        withJolokia(namespaceName, (http, baseUrl, auth, mbean) -> {
            deleteManagementAddress(http, baseUrl, auth, mbean, queueName);
            deleteManagementAddress(http, baseUrl, auth, mbean, deadLetterQueue);
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

    private void createManagementAddress(
            HttpClient http, String baseUrl, String auth, String mbean, String entityPath) {
        String managementAddress = entityPath + MANAGEMENT_SUFFIX;
        jolokiaExec(http, baseUrl, auth, mbean,
                "createAddress(java.lang.String,java.lang.String)",
                jsonArr(managementAddress, "MULTICAST"));
    }

    private void deleteManagementAddress(
            HttpClient http, String baseUrl, String auth, String mbean, String entityPath) {
        String managementAddress = entityPath + MANAGEMENT_SUFFIX;
        jolokiaExec(http, baseUrl, auth, mbean,
                "deleteAddress(java.lang.String,boolean)",
                jsonArr(managementAddress, true));
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
        try {
            HttpResponse<String> resp = sendJolokiaExec(
                    http, baseUrl, auth, mbean, operation, arguments);
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

    private void jolokiaExecRequired(HttpClient http, String baseUrl, String auth,
                                     String mbean, String operation, String arguments) {
        try {
            HttpResponse<String> response = sendJolokiaExec(
                    http, baseUrl, auth, mbean, operation, arguments);
            JsonNode payload = MAPPER.readTree(response.body());
            int jolokiaStatus = payload.path("status").asInt(-1);
            if (response.statusCode() != 200 || jolokiaStatus != 200) {
                throw new IllegalStateException("Jolokia " + operation.split("\\(")[0]
                        + " failed with HTTP " + response.statusCode()
                        + " and status " + jolokiaStatus + ": " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during required Jolokia operation", e);
        } catch (IOException e) {
            throw new RuntimeException("Required Jolokia operation failed", e);
        }
    }

    private HttpResponse<String> sendJolokiaExec(HttpClient http, String baseUrl, String auth,
                                                  String mbean, String operation, String arguments)
            throws IOException, InterruptedException {
        String body = "{\"type\":\"exec\",\"mbean\":\"" + mbean + "\","
                + "\"operation\":\"" + operation + "\","
                + "\"arguments\":" + arguments + "}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + auth)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, MessageCounts> jolokiaReadMessageCounts(
            HttpClient http, String baseUrl, String auth, String namespaceName,
            List<String> queueNames) throws IOException, InterruptedException {
        String body = jolokiaMessageCountRequest(namespaceName, queueNames);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(MESSAGE_COUNT_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + auth)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Jolokia HTTP status " + response.statusCode());
        }
        return parseJolokiaMessageCounts(queueNames, response.body());
    }

    static String jolokiaMessageCountRequest(
            String namespaceName, List<String> queueNames) throws IOException {
        var requests = MAPPER.createArrayNode();
        for (String queueName : queueNames) {
            for (String suffix : List.of("", DEAD_LETTER_QUEUE_SUFFIX)) {
                var request = requests.addObject();
                request.put("type", "read");
                request.put("mbean", queueMBean(namespaceName, queueName + suffix));
                request.put("attribute", "MessageCount");
            }
        }
        return MAPPER.writeValueAsString(requests);
    }

    static Map<String, MessageCounts> parseJolokiaMessageCounts(
            List<String> queueNames, String responseBody) throws IOException {
        JsonNode responses = MAPPER.readTree(responseBody);
        if (!responses.isArray()) {
            throw new IOException("Expected a Jolokia response array");
        }
        Map<String, MessageCounts> counts = new LinkedHashMap<>();
        for (int i = 0; i < queueNames.size(); i++) {
            long active = parseJolokiaMessageCountOrZero(responses.path(i * 2));
            long deadLetter = parseJolokiaMessageCountOrZero(responses.path(i * 2 + 1));
            counts.put(queueNames.get(i), new MessageCounts(active, deadLetter));
        }
        return Map.copyOf(counts);
    }

    private static long parseJolokiaMessageCountOrZero(JsonNode response) {
        try {
            return parseJolokiaMessageCount(response);
        } catch (IOException e) {
            return 0;
        }
    }

    static long parseJolokiaMessageCount(String responseBody) throws IOException {
        return parseJolokiaMessageCount(MAPPER.readTree(responseBody));
    }

    private static long parseJolokiaMessageCount(JsonNode response) throws IOException {
        if (response.path("status").asInt() != 200) {
            throw new IOException("Jolokia response status " + response.path("status").asInt());
        }
        JsonNode value = response.path("value");
        long count;
        if (value.isIntegralNumber()) {
            count = value.longValue();
        } else if (value.isTextual()) {
            try {
                count = Long.parseLong(value.textValue());
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Jolokia MessageCount value: " + value, e);
            }
        } else {
            throw new IOException("Missing Jolokia MessageCount value");
        }
        if (count < 0) {
            throw new IOException("Negative Jolokia MessageCount value: " + count);
        }
        return count;
    }

    private static Map<String, MessageCounts> zeroMessageCounts(List<String> queueNames) {
        Map<String, MessageCounts> counts = new LinkedHashMap<>();
        queueNames.forEach(queueName -> counts.put(queueName, MessageCounts.ZERO));
        return Map.copyOf(counts);
    }

    static String queueMBean(String namespaceName, String queueName) {
        return "org.apache.activemq.artemis:broker="
                + ObjectName.quote("floci-az-servicebus-" + namespaceName)
                + ",component=addresses,address=" + ObjectName.quote(queueName)
                + ",subcomponent=queues,routing-type=" + ObjectName.quote("anycast")
                + ",queue=" + ObjectName.quote(queueName);
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
