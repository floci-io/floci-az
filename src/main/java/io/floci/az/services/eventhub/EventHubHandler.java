package io.floci.az.services.eventhub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP handler for the Event Hubs service (admin/health + namespace management).
 * AMQP and Kafka data-plane traffic go directly to sidecar ports — not proxied here.
 *
 * <p>Namespace management endpoints:
 * <pre>
 *   GET    /{account}-eventhub/namespaces              — list all namespaces
 *   PUT    /{account}-eventhub/namespaces/{ns}         — start namespace on-demand
 *   DELETE /{account}-eventhub/namespaces/{ns}         — stop namespace
 *   GET    /{account}-eventhub/namespaces/{ns}/connection  — connection info
 *   GET    /{account}-eventhub/namespaces/{ns}/tls-cert    — TLS certificate PEM
 * </pre>
 *
 * <p>Kafka (Redpanda) management endpoints:
 * <pre>
 *   GET    /{account}-eventhub/kafka   — status
 *   PUT    /{account}-eventhub/kafka   — start Kafka on-demand
 *   DELETE /{account}-eventhub/kafka   — stop Kafka
 * </pre>
 *
 * <p>Backward-compatible endpoints:
 * <pre>
 *   GET    /{account}-eventhub/health    — overall health
 *   GET    /{account}-eventhub/tls-cert  — TLS cert for default namespace
 * </pre>
 */
@ApplicationScoped
public class EventHubHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(EventHubHandler.class);

    private final EmulatorConfig config;
    private final EventHubNamespaceManager namespaceManager;
    private final EventHubsKafkaManager kafkaManager;
    private final ObjectMapper objectMapper;

    @Inject
    public EventHubHandler(EmulatorConfig config,
                            EventHubNamespaceManager namespaceManager,
                            EventHubsKafkaManager kafkaManager) {
        this.config = config;
        this.namespaceManager = namespaceManager;
        this.kafkaManager = kafkaManager;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getServiceType() {
        return "eventhub";
    }

    @Override
    public boolean canHandle(AzureRequest req) {
        return true;
    }

    @Override
    public Response handle(AzureRequest req) {
        String path = req.resourcePath();

        if ("health".equals(path) || path.startsWith("health/")) {
            return handleHealth();
        }
        if ("tls-cert".equals(path)) {
            return handleDefaultTlsCert();
        }

        // Kafka management
        if ("kafka".equals(path)) {
            return handleKafka(req);
        }

        // Namespace management: namespaces[/{ns}[/{sub}]]
        if ("namespaces".equals(path)) {
            return handleListNamespaces();
        }
        if (path.startsWith("namespaces/")) {
            String rest = path.substring("namespaces/".length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                // namespaces/{ns}
                return handleNamespace(req, rest);
            }
            String ns = rest.substring(0, slash);
            String sub = rest.substring(slash + 1);
            return handleNamespaceSub(ns, sub);
        }

        return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"Not found\"}")
                .type("application/json")
                .build();
    }

    // ── Namespace list ────────────────────────────────────────────────────────

    private Response handleListNamespaces() {
        StringBuilder sb = new StringBuilder("{\"namespaces\":[");
        boolean first = true;
        for (Map.Entry<String, EventHubNamespaceManager.NamespaceState> e :
                namespaceManager.listNamespaces().entrySet()) {
            if (!first) sb.append(",");
            first = false;
            appendNamespaceJson(sb, e.getKey(), e.getValue());
        }
        sb.append("]}");
        return Response.ok(sb.toString()).type("application/json").build();
    }

    // ── Single namespace CRUD ─────────────────────────────────────────────────

    private Response handleNamespace(AzureRequest req, String namespaceName) {
        return switch (req.method()) {
            case "GET" -> handleGetNamespace(namespaceName);
            case "PUT", "POST" -> handleCreateNamespace(req, namespaceName);
            case "DELETE" -> handleDeleteNamespace(namespaceName);
            default -> Response.status(405).entity("{\"error\":\"Method not allowed\"}")
                    .type("application/json").build();
        };
    }

    private Response handleGetNamespace(String namespaceName) {
        Optional<EventHubNamespaceManager.NamespaceState> state = namespaceManager.getNamespace(namespaceName);
        if (state.isEmpty()) {
            return notFound("Namespace not found: " + namespaceName);
        }
        StringBuilder sb = new StringBuilder();
        appendNamespaceJson(sb, namespaceName, state.get());
        return Response.ok(sb.toString()).type("application/json").build();
    }

    private Response handleCreateNamespace(AzureRequest req, String namespaceName) {
        // Idempotent: return 200 if already running
        if (namespaceManager.getNamespace(namespaceName).isPresent()) {
            StringBuilder sb = new StringBuilder();
            appendNamespaceJson(sb, namespaceName, namespaceManager.getNamespace(namespaceName).get());
            return Response.ok(sb.toString()).type("application/json").build();
        }

        EmulatorConfig.EventHubConfig eh = config.services().eventHub();
        String entitiesStr = eh.entities();
        String consumerGroupsStr = eh.consumerGroups();
        int amqpPort = eh.amqpPort();
        int amqpTlsPort = eh.amqpTlsPort();

        try {
            if (req.bodyStream() != null && req.bodyStream().available() > 0) {
                Map<String, Object> body = objectMapper.readValue(
                        req.bodyStream(), new TypeReference<>() {});
                if (body.containsKey("entities")) entitiesStr = body.get("entities").toString();
                if (body.containsKey("consumerGroups")) consumerGroupsStr = body.get("consumerGroups").toString();
                if (body.containsKey("amqpPort")) amqpPort = ((Number) body.get("amqpPort")).intValue();
                if (body.containsKey("amqpTlsPort")) amqpTlsPort = ((Number) body.get("amqpTlsPort")).intValue();
            }
        } catch (Exception e) {
            LOG.debugv("Could not parse namespace creation body: {0}", e.getMessage());
        }

        Map<String, List<String>> entities = ArtemisConfigGenerator.parseEntities(entitiesStr, consumerGroupsStr);

        if (eh.mocked()) {
            EventHubNamespaceManager.NamespaceState state = namespaceManager.registerMockedNamespace(namespaceName);
            StringBuilder sb = new StringBuilder();
            appendNamespaceJson(sb, namespaceName, state);
            return Response.status(201).entity(sb.toString()).type("application/json").build();
        }

        try {
            EventHubNamespaceManager.NamespaceState state =
                    namespaceManager.startNamespace(namespaceName, entities, amqpPort, amqpTlsPort);
            StringBuilder sb = new StringBuilder();
            appendNamespaceJson(sb, namespaceName, state);
            return Response.status(201).entity(sb.toString()).type("application/json").build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to create namespace '%s'", namespaceName);
            return Response.status(500)
                    .entity("{\"error\":\"Failed to create namespace: " + e.getMessage() + "\"}")
                    .type("application/json").build();
        }
    }

    private Response handleDeleteNamespace(String namespaceName) {
        boolean stopped = namespaceManager.stopNamespace(namespaceName);
        if (!stopped) {
            return notFound("Namespace not found: " + namespaceName);
        }
        return Response.noContent().build();
    }

    // ── Namespace sub-resources ───────────────────────────────────────────────

    private Response handleNamespaceSub(String namespaceName, String sub) {
        Optional<EventHubNamespaceManager.NamespaceState> state = namespaceManager.getNamespace(namespaceName);
        if (state.isEmpty()) {
            return notFound("Namespace not found: " + namespaceName);
        }
        EventHubNamespaceManager.NamespaceState ns = state.get();

        if ("tls-cert".equals(sub)) {
            return Response.ok(ns.tlsCertPem()).type("application/x-pem-file").build();
        }
        if ("connection".equals(sub)) {
            String json = String.format(
                    "{\"namespace\":\"%s\",\"amqpPort\":%d,\"amqpsPort\":%d,"
                    + "\"amqpEndpoint\":\"amqp://localhost:%d\","
                    + "\"amqpsEndpoint\":\"amqps://localhost:%d\"}",
                    namespaceName, ns.amqpHostPort(), ns.amqpsHostPort(),
                    ns.amqpHostPort(), ns.amqpsHostPort());
            return Response.ok(json).type("application/json").build();
        }

        return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"Unknown sub-resource: " + sub + "\"}")
                .type("application/json").build();
    }

    // ── Kafka management ──────────────────────────────────────────────────────

    private Response handleKafka(AzureRequest req) {
        return switch (req.method()) {
            case "GET"          -> handleKafkaStatus();
            case "PUT", "POST"  -> handleKafkaStart();
            case "DELETE"       -> handleKafkaStop();
            default             -> Response.status(405).build();
        };
    }

    private Response handleKafkaStatus() {
        boolean running = kafkaManager.isRunning();
        int port = config.services().eventHub().kafkaPort();
        String body = String.format(
                "{\"running\":%b,\"port\":%d}", running, running ? port : 0);
        return Response.ok(body).type("application/json").build();
    }

    private Response handleKafkaStart() {
        if (kafkaManager.isRunning()) {
            return handleKafkaStatus();
        }
        try {
            kafkaManager.start();
            return Response.status(201).entity(handleKafkaStatus().getEntity())
                    .type("application/json").build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to start Redpanda Kafka broker");
            return Response.status(500)
                    .entity("{\"error\":\"Failed to start Kafka: " + e.getMessage() + "\"}")
                    .type("application/json").build();
        }
    }

    private Response handleKafkaStop() {
        if (!kafkaManager.isRunning()) {
            return notFound("Kafka is not running");
        }
        kafkaManager.stop();
        return Response.noContent().build();
    }

    // ── Backward-compatible endpoints ─────────────────────────────────────────

    private Response handleHealth() {
        int amqpPort = config.services().eventHub().amqpPort();
        boolean amqpUp = isTcpOpen("localhost", amqpPort);
        int amqpsPort = config.services().eventHub().amqpTlsPort();
        boolean amqpsUp = isTcpOpen("localhost", amqpsPort);

        String body = String.format(
                "{\"amqp\":{\"port\":%d,\"status\":\"%s\"},\"amqps\":{\"port\":%d,\"status\":\"%s\"},\"kafka\":{\"running\":%b}}",
                amqpPort, amqpUp ? "up" : "down",
                amqpsPort, amqpsUp ? "up" : "down",
                kafkaManager.isRunning());

        int status = (amqpUp || amqpsUp) ? 200 : 503;
        return Response.status(status).entity(body).type("application/json").build();
    }

    private Response handleDefaultTlsCert() {
        String defaultNs = config.services().eventHub().defaultNamespace();
        Optional<EventHubNamespaceManager.NamespaceState> state = namespaceManager.getNamespace(defaultNs);
        if (state.isEmpty()) {
            return Response.status(503)
                    .entity("{\"error\":\"Default namespace TLS cert not yet available\"}")
                    .type("application/json").build();
        }
        return Response.ok(state.get().tlsCertPem()).type("application/x-pem-file").build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void appendNamespaceJson(StringBuilder sb, String name,
                                             EventHubNamespaceManager.NamespaceState state) {
        sb.append("{\"name\":\"").append(name).append("\"")
          .append(",\"amqpPort\":").append(state.amqpHostPort())
          .append(",\"amqpsPort\":").append(state.amqpsHostPort())
          .append(",\"mocked\":").append(state.mocked())
          .append("}");
    }

    private static Response notFound(String message) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"" + message + "\"}")
                .type("application/json").build();
    }

    private boolean isTcpOpen(String host, int port) {
        try (Socket s = new Socket(host, port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
