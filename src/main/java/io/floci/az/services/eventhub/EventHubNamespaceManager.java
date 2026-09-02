package io.floci.az.services.eventhub;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerStorageHelper;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.services.servicebus.ServiceBusCbsResponder;
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

    // Artemis library patches, shared with the Service Bus broker — see where they are
    // copied in below for why each is needed.
    static final String PROTON_PATCH_RESOURCE = "/artemis/proton-j-0.34.1-floci-az-proton-patch.jar";
    static final String ARTEMIS_AMQP_PATCH_RESOURCE =
            "/artemis/artemis-amqp-protocol-2.44.0-floci-az-artemis-amqp-patch.jar";
    private static final String PROTON_J_PATH = "/opt/activemq-artemis/lib/proton-j-0.34.1.jar";
    private static final String ARTEMIS_AMQP_PATH =
            "/opt/activemq-artemis/lib/artemis-amqp-protocol-2.44.0.jar";
    static final String ARTEMIS_EXTENSION_RESOURCE = "/artemis/servicebus-artemis-extension.jar";
    private static final String ARTEMIS_EXTENSION_PATH =
            "/var/lib/artemis-instance/lib/floci-az-artemis-extension.jar";

    private final ConcurrentHashMap<String, NamespaceState> namespaces = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServiceBusCbsResponder> cbsResponders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EventHubManagementResponder> managementResponders =
            new ConcurrentHashMap<>();

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
    public synchronized NamespaceState startNamespace(String namespaceName,
                                          Map<String, ArtemisConfigGenerator.EntitySpec> entities,
                                          int amqpHostPort, int amqpsHostPort) {
        // Synchronized, and re-checking inside the lock, as the Service Bus manager does:
        // the handler's own existence check is not atomic with this call, so two
        // overlapping creates would otherwise each start a broker and a responder, and
        // the second registration would orphan the first — leaking a daemon thread and
        // its Proton reactor's file descriptors.
        NamespaceState existing = namespaces.get(namespaceName);
        if (existing != null) {
            return existing;
        }
        String containerName = containerName(namespaceName);

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
        // The same two library patches the Service Bus broker gets. Artemis never
        // advertises max-message-size on ATTACH (spec-legal: unset means "no limit"),
        // but the Azure SDKs read the absent field as a zero-byte limit and reject
        // every send — so the patched proton-j sets it. The patched amqp-protocol
        // carries the MSSBCBS SASL mechanism the acceptors offer.
        lifecycleManager.copyBytesToContainer(containerId, loadResource(PROTON_PATCH_RESOURCE),
                PROTON_J_PATH);
        lifecycleManager.copyBytesToContainer(containerId, loadResource(ARTEMIS_AMQP_PATCH_RESOURCE),
                ARTEMIS_AMQP_PATH);
        // Carries EventHubPartitionPlugin, which stamps the partition each message belongs to;
        // the generated partition diverts filter on what it sets.
        lifecycleManager.copyBytesToContainer(containerId, loadResource(ARTEMIS_EXTENSION_RESOURCE),
                ARTEMIS_EXTENSION_PATH);

        ContainerLifecycleManager.ContainerInfo info = lifecycleManager.startCreated(containerId, spec);

        EndpointInfo amqpEndpoint = info.getEndpoint(AMQP_PORT);
        waitForPort(amqpEndpoint, "AMQP");
        EndpointInfo amqpsEndpoint = info.getEndpoint(AMQPS_PORT);
        waitForPort(amqpsEndpoint, "AMQPS");

        // Answers the CBS put-token every Azure SDK sends before opening entity links;
        // the broker.xml divert routes those requests to the intercept queue this
        // attaches to. Same responder the Service Bus namespaces use.
        ServiceBusCbsResponder cbs = new ServiceBusCbsResponder(amqpEndpoint.host(), amqpEndpoint.port());
        cbs.start();
        cbsResponders.put(namespaceName, cbs);

        // Answers the $management READ every consumer issues before it can attach per partition.
        EventHubManagementResponder management =
                new EventHubManagementResponder(amqpEndpoint.host(), amqpEndpoint.port(), entities);
        management.start();
        managementResponders.put(namespaceName, management);

        // The topology is declared in broker.xml, written above and read as the broker starts.
        // It used to be rebuilt over Jolokia as well, addressed per host; the AMQP layer now
        // reduces every spelling to the entity path, so that second copy addressed nothing the
        // broker would ever route to — and while the two copies still overlapped, any drift
        // between how they named a divert installed a duplicate over the same route and delivered
        // every message twice.

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
    public synchronized boolean stopNamespace(String namespaceName) {
        // Shares startNamespace's lock: a delete arriving mid-start would otherwise see
        // no namespace, clean nothing up, and let the start it raced publish a namespace
        // the caller had already deleted — leaving its container and responder running.
        NamespaceState state = namespaces.remove(namespaceName);
        if (state == null) {
            return false;
        }
        ServiceBusCbsResponder cbs = cbsResponders.remove(namespaceName);
        if (cbs != null) {
            cbs.stop();
        }
        EventHubManagementResponder management = managementResponders.remove(namespaceName);
        if (management != null) {
            management.stop();
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

    String containerName(String namespaceName) {
        return ContainerStorageHelper.dockerName(config, "artemis-" + namespaceName);
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

    private static byte[] loadResource(String resource) {
        try (java.io.InputStream stream =
                EventHubNamespaceManager.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Embedded Artemis resource not found: " + resource);
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read embedded Artemis resource: " + resource, e);
        }
    }
}
