package io.floci.az.services.servicebus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP management handler for the Service Bus service.
 *
 * <p>Supports two routing modes:
 *
 * <p><b>Spec-compatible paths</b> (used by {@code ServiceBusAdministrationClient}):
 * <pre>
 *   GET    /{account}-servicebus/$namespaceinfo                        — namespace info
 *   GET    /{account}-servicebus/$Resources/queues                     — list queues
 *   GET    /{account}-servicebus/$Resources/topics                     — list topics
 *   GET    /{account}-servicebus/{entityName}                          — get queue or topic
 *   PUT    /{account}-servicebus/{entityName}                          — create queue or topic
 *   DELETE /{account}-servicebus/{entityName}                          — delete queue or topic
 *   GET    /{account}-servicebus/{topicName}/subscriptions             — list subscriptions
 *   GET    /{account}-servicebus/{topicName}/subscriptions/{sub}       — get subscription
 *   PUT    /{account}-servicebus/{topicName}/subscriptions/{sub}       — create subscription
 *   DELETE /{account}-servicebus/{topicName}/subscriptions/{sub}       — delete subscription
 * </pre>
 *
 * <p><b>Custom paths</b> (with explicit namespace, for programmatic setup):
 * <pre>
 *   GET    /{account}-servicebus/namespaces               — list namespaces
 *   PUT    /{account}-servicebus/namespaces/{ns}          — start namespace on-demand
 *   DELETE /{account}-servicebus/namespaces/{ns}          — stop namespace
 *   GET    /{account}-servicebus/{ns}/queues              — list queues in namespace
 *   PUT    /{account}-servicebus/{ns}/queues/{name}       — create queue in namespace
 *   ...etc for topics and subscriptions
 * </pre>
 */
@ApplicationScoped
public class ServiceBusHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(ServiceBusHandler.class);

    private static final String ATOM_XML_CONTENT_TYPE = "application/atom+xml;charset=utf-8";
    private static final String DEFAULT_NAMESPACE = "default";
    /** Main Service Bus namespace for entity descriptions. */
    private static final String SB_NS = "http://schemas.microsoft.com/netservices/2010/10/servicebus/connect";
    /** Separate namespace used by MessageCountDetails child elements (per spec). */
    private static final String SB_COUNT_NS = "http://schemas.microsoft.com/netservices/2011/06/servicebus";
    private static final DateTimeFormatter ISO8601 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final EmulatorConfig config;
    private final ServiceBusNamespaceManager namespaceManager;
    private final StorageBackend<String, StoredObject> store;

    @Inject
    public ServiceBusHandler(EmulatorConfig config,
                              ServiceBusNamespaceManager namespaceManager,
                              StorageFactory storageFactory) {
        this.config = config;
        this.namespaceManager = namespaceManager;
        this.store = storageFactory.create("servicebus");
    }

    @Override
    public String getServiceType() {
        return "servicebus";
    }

    @Override
    public boolean canHandle(AzureRequest req) {
        return true;
    }

    @Override
    public Response handle(AzureRequest req) {
        String path = req.resourcePath();
        String account = req.accountName();

        // ── Spec paths (start with $) ─────────────────────────────────────────
        if ("$namespaceinfo".equals(path)) {
            return handleNamespaceInfo(account);
        }
        if (path.startsWith("$Resources/")) {
            String entityType = path.substring("$Resources/".length()).split("\\?")[0];
            return handleListResources(account, entityType);
        }

        // ── Custom namespace management ───────────────────────────────────────
        if ("namespaces".equals(path)) {
            return handleListNamespaces();
        }
        if (path.startsWith("namespaces/")) {
            String rest = path.substring("namespaces/".length());
            int slash = rest.indexOf('/');
            String ns = slash < 0 ? rest : rest.substring(0, slash);
            return handleNamespace(req, ns);
        }

        // ── Dispatch by path structure ────────────────────────────────────────
        int firstSlash = path.indexOf('/');

        if (firstSlash < 0) {
            // Single segment: spec entity CRUD (queue or topic)
            return handleSpecEntityCrud(req, account, path);
        }

        String first = path.substring(0, firstSlash);
        String rest  = path.substring(firstSlash + 1);

        // Detect old custom paths by second segment being "queues" or "topics"
        int secondSlash = rest.indexOf('/');
        String second = secondSlash < 0 ? rest : rest.substring(0, secondSlash);

        if ("queues".equals(second)) {
            // Old custom: {namespace}/queues[/{name}]
            String entityPath = secondSlash < 0 ? "queues" : "queues/" + rest.substring(secondSlash + 1);
            return routeEntityRequest(req, account, first, entityPath);
        }
        if ("topics".equals(second)) {
            // Old custom: {namespace}/topics[/{name}[/subscriptions[/{sub}]]]
            String entityPath = secondSlash < 0 ? "topics" : "topics/" + rest.substring(secondSlash + 1);
            return routeEntityRequest(req, account, first, entityPath);
        }
        if ("subscriptions".equals(second)) {
            // Spec: {topicName}/subscriptions[/{subName}]
            String subRest = secondSlash < 0 ? "" : rest.substring(secondSlash + 1);
            return handleSpecSubscriptionPath(req, account, first, subRest);
        }

        return notFound("Path not found: " + path);
    }

    // ── Spec: namespace info ──────────────────────────────────────────────────

    private Response handleNamespaceInfo(String account) {
        Optional<String> activeNs = resolveActiveNamespace();
        String now = ISO8601.format(Instant.now());
        String nsName = activeNs.orElse(account);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<entry xmlns=\"http://www.w3.org/2005/Atom\">"
                + "<id>https://localhost/$namespaceinfo</id>"
                + "<title type=\"text\">" + nsName + "</title>"
                + "<updated>" + now + "</updated>"
                + "<content type=\"application/xml\">"
                + "<NamespaceInfo xmlns=\"" + SB_NS + "\">"
                + "<Name>" + nsName + "</Name>"
                + "<MessagingSKU>Standard</MessagingSKU>"
                + "<NamespaceType>Messaging</NamespaceType>"
                + "<CreatedTime>" + now + "</CreatedTime>"
                + "<ModifiedTime>" + now + "</ModifiedTime>"
                + "</NamespaceInfo>"
                + "</content>"
                + "</entry>";
        return Response.ok(xml).type(ATOM_XML_CONTENT_TYPE).build();
    }

    // ── Spec: list resources (queues or topics) ───────────────────────────────

    private Response handleListResources(String account, String entityType) {
        String ns = resolveActiveNamespace().orElse(null);
        if (ns == null) {
            return emptyFeed(entityType);
        }
        if ("queues".equals(entityType)) {
            return handleListQueues(account, ns);
        }
        if ("topics".equals(entityType)) {
            return handleListTopics(account, ns);
        }
        return notFound("Unknown entity type: " + entityType);
    }

    private Response emptyFeed(String entityType) {
        String now = ISO8601.format(Instant.now());
        return Response.ok("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<feed xmlns=\"http://www.w3.org/2005/Atom\">"
                + "<title type=\"text\">" + entityType + "</title>"
                + "<updated>" + now + "</updated>"
                + "</feed>")
                .type(ATOM_XML_CONTENT_TYPE).build();
    }

    // ── Spec: entity CRUD (queue or topic by body) ────────────────────────────

    private Response handleSpecEntityCrud(AzureRequest req, String account, String entityName) {
        String ns = resolveActiveNamespace().orElse(null);
        if (ns == null) {
            return Response.status(503)
                    .entity("{\"error\":\"No Service Bus namespace is running\"}")
                    .type("application/json").build();
        }

        return switch (req.method()) {
            case "GET"          -> handleSpecEntityGet(account, ns, entityName);
            case "PUT", "POST"  -> handleSpecEntityPut(req, account, ns, entityName);
            case "DELETE"       -> handleSpecEntityDelete(account, ns, entityName);
            default             -> Response.status(405).build();
        };
    }

    private Response handleSpecEntityGet(String account, String ns, String entityName) {
        // Check queues first, then topics
        String qKey = queueKey(account, ns, entityName);
        Optional<StoredObject> qObj = store.get(qKey);
        if (qObj.isPresent()) {
            ServiceBusModels.QueueEntity q = fromBytes(qObj.get().data(), ServiceBusModels.QueueEntity.class);
            return Response.ok(queueEntryXml(ns, q)).type(ATOM_XML_CONTENT_TYPE).build();
        }
        String tKey = topicKey(account, ns, entityName);
        Optional<StoredObject> tObj = store.get(tKey);
        if (tObj.isPresent()) {
            ServiceBusModels.TopicEntity t = fromBytes(tObj.get().data(), ServiceBusModels.TopicEntity.class);
            return Response.ok(topicEntryXml(ns, t)).type(ATOM_XML_CONTENT_TYPE).build();
        }
        return notFoundAtom(entityName + " not found");
    }

    private Response handleSpecEntityPut(AzureRequest req, String account, String ns, String entityName) {
        String body = readBody(req);
        boolean isQueue = body.isEmpty() || body.contains("QueueDescription");
        boolean isTopic = !isQueue && body.contains("TopicDescription");
        boolean requiresSession = body.contains("<RequiresSession>true</RequiresSession>");

        if (isTopic) {
            return handleCreateTopic(account, ns, entityName);
        }
        // Default to queue (empty body, QueueDescription, or ambiguous)
        return handleCreateQueue(account, ns, entityName, requiresSession);
    }

    private Response handleSpecEntityDelete(String account, String ns, String entityName) {
        String qKey = queueKey(account, ns, entityName);
        if (store.get(qKey).isPresent()) {
            return handleDeleteQueue(account, ns, entityName);
        }
        String tKey = topicKey(account, ns, entityName);
        if (store.get(tKey).isPresent()) {
            return handleDeleteTopic(account, ns, entityName);
        }
        return notFoundAtom(entityName + " not found");
    }

    // ── Spec: subscription paths ──────────────────────────────────────────────

    private Response handleSpecSubscriptionPath(AzureRequest req, String account,
                                                  String topicName, String subRest) {
        String ns = resolveActiveNamespace().orElse(null);
        if (ns == null) {
            return notFoundAtom("No running namespace for subscriptions");
        }
        if (subRest.isEmpty()) {
            return handleListSubscriptions(account, ns, topicName);
        }
        return handleSubscriptionCrud(req, account, ns, topicName, subRest);
    }

    // ── Namespace management ──────────────────────────────────────────────────

    private Response handleListNamespaces() {
        StringBuilder sb = new StringBuilder("{\"namespaces\":[");
        boolean first = true;
        for (Map.Entry<String, ServiceBusNamespaceManager.NamespaceState> e :
                namespaceManager.listNamespaces().entrySet()) {
            if (!first) sb.append(",");
            first = false;
            appendNamespaceJson(sb, e.getKey(), e.getValue());
        }
        sb.append("]}");
        return Response.ok(sb.toString()).type("application/json").build();
    }

    private Response handleNamespace(AzureRequest req, String namespaceName) {
        return switch (req.method()) {
            case "GET"          -> handleGetNamespace(namespaceName);
            case "PUT", "POST"  -> handleCreateNamespace(req, namespaceName);
            case "DELETE"       -> handleDeleteNamespace(namespaceName);
            default             -> Response.status(405).entity("{\"error\":\"Method not allowed\"}")
                                       .type("application/json").build();
        };
    }

    private Response handleGetNamespace(String namespaceName) {
        return namespaceManager.getNamespace(namespaceName)
                .map(state -> {
                    StringBuilder sb = new StringBuilder();
                    appendNamespaceJson(sb, namespaceName, state);
                    return Response.ok(sb.toString()).type("application/json").build();
                })
                .orElseGet(() -> notFound("Namespace not found: " + namespaceName));
    }

    private Response handleCreateNamespace(AzureRequest req, String namespaceName) {
        Optional<ServiceBusNamespaceManager.NamespaceState> existing =
                namespaceManager.getNamespace(namespaceName);
        if (existing.isPresent()) {
            StringBuilder sb = new StringBuilder();
            appendNamespaceJson(sb, namespaceName, existing.get());
            return Response.ok(sb.toString()).type("application/json").build();
        }

        if (config.services().serviceBus().mocked()) {
            ServiceBusNamespaceManager.NamespaceState state =
                    namespaceManager.startMockedNamespace(namespaceName);
            StringBuilder json = new StringBuilder();
            appendNamespaceJson(json, namespaceName, state);
            return Response.status(201).entity(json.toString()).type("application/json").build();
        }

        EmulatorConfig.ServiceBusConfig sb = config.services().serviceBus();
        int amqpPort = sb.amqpPort();
        int amqpTlsPort = sb.amqpTlsPort();

        try {
            Map<String, Object> body = parseJsonBody(req);
            if (body.containsKey("amqpPort")) {
                amqpPort = toInt(body.get("amqpPort"));
            }
            if (body.containsKey("amqpTlsPort")) {
                amqpTlsPort = toInt(body.get("amqpTlsPort"));
            }
        } catch (Exception ignored) {
        }

        try {
            ServiceBusNamespaceManager.NamespaceState state =
                    namespaceManager.startNamespace(namespaceName, amqpPort, amqpTlsPort);
            StringBuilder json = new StringBuilder();
            appendNamespaceJson(json, namespaceName, state);
            return Response.status(201).entity(json.toString()).type("application/json").build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to start Service Bus namespace '%s'", namespaceName);
            return Response.status(500)
                    .entity("{\"error\":\"Failed to start namespace: " + e.getMessage() + "\"}")
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

    // ── Old custom entity routing ─────────────────────────────────────────────

    private Response routeEntityRequest(AzureRequest req, String account,
                                         String namespace, String entityPath) {
        if ("queues".equals(entityPath)) {
            return handleListQueues(account, namespace);
        }
        if (entityPath.startsWith("queues/")) {
            String queueName = entityPath.substring("queues/".length());
            return handleQueueCrud(req, account, namespace, queueName);
        }
        if ("topics".equals(entityPath)) {
            return handleListTopics(account, namespace);
        }
        if (entityPath.startsWith("topics/")) {
            String rest = entityPath.substring("topics/".length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                return handleTopicCrud(req, account, namespace, rest);
            }
            String topicName = rest.substring(0, slash);
            String sub = rest.substring(slash + 1);
            if ("subscriptions".equals(sub)) {
                return handleListSubscriptions(account, namespace, topicName);
            }
            if (sub.startsWith("subscriptions/")) {
                String subName = sub.substring("subscriptions/".length());
                return handleSubscriptionCrud(req, account, namespace, topicName, subName);
            }
        }
        return notFound("Path not found: " + entityPath);
    }

    // ── Queue CRUD ────────────────────────────────────────────────────────────

    private Response handleListQueues(String account, String namespace) {
        String prefix = queuePrefix(account, namespace);
        List<ServiceBusModels.QueueEntity> queues = store.scan(k -> k.startsWith(prefix))
                .stream()
                .map(obj -> fromBytes(obj.data(), ServiceBusModels.QueueEntity.class))
                .filter(q -> q != null)
                .toList();
        return Response.ok(queueFeedXml(namespace, queues)).type(ATOM_XML_CONTENT_TYPE).build();
    }

    private Response handleQueueCrud(AzureRequest req, String account, String namespace, String queueName) {
        return switch (req.method()) {
            case "GET"          -> handleGetQueue(account, namespace, queueName);
            case "PUT", "POST"  -> {
                String body = readBody(req);
                boolean requiresSession = body.contains("<RequiresSession>true</RequiresSession>");
                yield handleCreateQueue(account, namespace, queueName, requiresSession);
            }
            case "DELETE"       -> handleDeleteQueue(account, namespace, queueName);
            default             -> Response.status(405).build();
        };
    }

    private Response handleGetQueue(String account, String namespace, String queueName) {
        String key = queueKey(account, namespace, queueName);
        return store.get(key)
                .map(obj -> {
                    ServiceBusModels.QueueEntity q = fromBytes(obj.data(), ServiceBusModels.QueueEntity.class);
                    return Response.ok(queueEntryXml(namespace, q)).type(ATOM_XML_CONTENT_TYPE).build();
                })
                .orElseGet(() -> notFoundAtom("Queue not found: " + queueName));
    }

    private Response handleCreateQueue(String account, String namespace, String queueName,
                                        boolean requiresSession) {
        String key = queueKey(account, namespace, queueName);
        if (store.get(key).isPresent()) {
            ServiceBusModels.QueueEntity existing =
                    fromBytes(store.get(key).get().data(), ServiceBusModels.QueueEntity.class);
            return Response.ok(queueEntryXml(namespace, existing)).type(ATOM_XML_CONTENT_TYPE).build();
        }

        if (namespaceManager.getNamespace(namespace).isEmpty()) {
            lazyStartNamespace(namespace);
        }
        EmulatorConfig.ServiceBusConfig sb = config.services().serviceBus();
        ServiceBusModels.QueueEntity queue = ServiceBusModels.QueueEntity.defaults(
                queueName, sb.maxDeliveryCount(), sb.lockDurationSeconds(), requiresSession);
        store.put(key, toStoredObject(key, queue));

        try {
            namespaceManager.jolokiaCreateQueue(namespace, queueName);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to provision queue '%s' in Artemis for namespace '%s'", queueName, namespace);
        }

        return Response.status(201).entity(queueEntryXml(namespace, queue))
                .type(ATOM_XML_CONTENT_TYPE).build();
    }

    private Response handleDeleteQueue(String account, String namespace, String queueName) {
        String key = queueKey(account, namespace, queueName);
        if (store.get(key).isEmpty()) {
            return notFoundAtom("Queue not found: " + queueName);
        }
        store.delete(key);
        try {
            namespaceManager.jolokiaDeleteQueue(namespace, queueName);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to remove queue '%s' from Artemis for namespace '%s'", queueName, namespace);
        }
        return Response.ok().build();
    }

    // ── Topic CRUD ────────────────────────────────────────────────────────────

    private Response handleListTopics(String account, String namespace) {
        String prefix = topicPrefix(account, namespace);
        List<ServiceBusModels.TopicEntity> topics = store.scan(k -> k.startsWith(prefix))
                .stream()
                .map(obj -> fromBytes(obj.data(), ServiceBusModels.TopicEntity.class))
                .filter(t -> t != null)
                .toList();
        return Response.ok(topicFeedXml(namespace, topics)).type(ATOM_XML_CONTENT_TYPE).build();
    }

    private Response handleTopicCrud(AzureRequest req, String account, String namespace, String topicName) {
        return switch (req.method()) {
            case "GET"          -> handleGetTopic(account, namespace, topicName);
            case "PUT", "POST"  -> handleCreateTopic(account, namespace, topicName);
            case "DELETE"       -> handleDeleteTopic(account, namespace, topicName);
            default             -> Response.status(405).build();
        };
    }

    private Response handleGetTopic(String account, String namespace, String topicName) {
        String key = topicKey(account, namespace, topicName);
        return store.get(key)
                .map(obj -> {
                    ServiceBusModels.TopicEntity t = fromBytes(obj.data(), ServiceBusModels.TopicEntity.class);
                    return Response.ok(topicEntryXml(namespace, t)).type(ATOM_XML_CONTENT_TYPE).build();
                })
                .orElseGet(() -> notFoundAtom("Topic not found: " + topicName));
    }

    private Response handleCreateTopic(String account, String namespace, String topicName) {
        String key = topicKey(account, namespace, topicName);
        if (store.get(key).isPresent()) {
            ServiceBusModels.TopicEntity existing =
                    fromBytes(store.get(key).get().data(), ServiceBusModels.TopicEntity.class);
            return Response.ok(topicEntryXml(namespace, existing)).type(ATOM_XML_CONTENT_TYPE).build();
        }

        if (namespaceManager.getNamespace(namespace).isEmpty()) {
            lazyStartNamespace(namespace);
        }
        ServiceBusModels.TopicEntity topic = ServiceBusModels.TopicEntity.defaults(topicName);
        store.put(key, toStoredObject(key, topic));

        try {
            namespaceManager.jolokiaCreateTopic(namespace, topicName);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to provision topic '%s' in Artemis for namespace '%s'", topicName, namespace);
        }

        return Response.status(201).entity(topicEntryXml(namespace, topic))
                .type(ATOM_XML_CONTENT_TYPE).build();
    }

    private Response handleDeleteTopic(String account, String namespace, String topicName) {
        String topicKey = topicKey(account, namespace, topicName);
        if (store.get(topicKey).isEmpty()) {
            return notFoundAtom("Topic not found: " + topicName);
        }

        String subPrefix = subPrefix(account, namespace, topicName);
        store.scan(k -> k.startsWith(subPrefix)).forEach(obj -> store.delete(obj.key()));
        store.delete(topicKey);

        try {
            namespaceManager.jolokiaDeleteTopic(namespace, topicName);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to remove topic '%s' from Artemis for namespace '%s'", topicName, namespace);
        }
        return Response.ok().build();
    }

    // ── Subscription CRUD ─────────────────────────────────────────────────────

    private Response handleListSubscriptions(String account, String namespace, String topicName) {
        String prefix = subPrefix(account, namespace, topicName);
        List<ServiceBusModels.SubscriptionEntity> subs = store.scan(k -> k.startsWith(prefix))
                .stream()
                .map(obj -> fromBytes(obj.data(), ServiceBusModels.SubscriptionEntity.class))
                .filter(s -> s != null)
                .toList();
        return Response.ok(subscriptionFeedXml(namespace, topicName, subs))
                .type(ATOM_XML_CONTENT_TYPE).build();
    }

    private Response handleSubscriptionCrud(AzureRequest req, String account,
                                              String namespace, String topicName, String subName) {
        return switch (req.method()) {
            case "GET"          -> handleGetSubscription(account, namespace, topicName, subName);
            case "PUT", "POST"  -> {
                String body = readBody(req);
                boolean requiresSession = body.contains("<RequiresSession>true</RequiresSession>");
                yield handleCreateSubscription(account, namespace, topicName, subName, requiresSession);
            }
            case "DELETE"       -> handleDeleteSubscription(account, namespace, topicName, subName);
            default             -> Response.status(405).build();
        };
    }

    private Response handleGetSubscription(String account, String namespace,
                                             String topicName, String subName) {
        String key = subKey(account, namespace, topicName, subName);
        return store.get(key)
                .map(obj -> {
                    ServiceBusModels.SubscriptionEntity s =
                            fromBytes(obj.data(), ServiceBusModels.SubscriptionEntity.class);
                    return Response.ok(subscriptionEntryXml(namespace, s))
                            .type(ATOM_XML_CONTENT_TYPE).build();
                })
                .orElseGet(() -> notFoundAtom("Subscription not found: " + subName));
    }

    private Response handleCreateSubscription(String account, String namespace,
                                                String topicName, String subName,
                                                boolean requiresSession) {
        if (store.get(topicKey(account, namespace, topicName)).isEmpty()) {
            return notFoundAtom("Topic not found: " + topicName);
        }

        String key = subKey(account, namespace, topicName, subName);
        if (store.get(key).isPresent()) {
            ServiceBusModels.SubscriptionEntity existing =
                    fromBytes(store.get(key).get().data(), ServiceBusModels.SubscriptionEntity.class);
            return Response.ok(subscriptionEntryXml(namespace, existing))
                    .type(ATOM_XML_CONTENT_TYPE).build();
        }

        EmulatorConfig.ServiceBusConfig sb = config.services().serviceBus();
        ServiceBusModels.SubscriptionEntity sub = ServiceBusModels.SubscriptionEntity.defaults(
                topicName, subName, sb.maxDeliveryCount(), sb.lockDurationSeconds(), requiresSession);
        store.put(key, toStoredObject(key, sub));

        try {
            namespaceManager.jolokiaCreateSubscription(namespace, topicName, subName);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to provision subscription '%s/%s' in Artemis for namespace '%s'",
                    topicName, subName, namespace);
        }

        return Response.status(201).entity(subscriptionEntryXml(namespace, sub))
                .type(ATOM_XML_CONTENT_TYPE).build();
    }

    private Response handleDeleteSubscription(String account, String namespace,
                                               String topicName, String subName) {
        String key = subKey(account, namespace, topicName, subName);
        if (store.get(key).isEmpty()) {
            return notFoundAtom("Subscription not found: " + subName);
        }
        store.delete(key);
        try {
            namespaceManager.jolokiaDeleteSubscription(namespace, topicName, subName);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to remove subscription '%s/%s' from Artemis", topicName, subName);
        }
        return Response.ok().build();
    }

    // ── ATOM XML serialization ────────────────────────────────────────────────

    private String queueEntryXml(String namespace, ServiceBusModels.QueueEntity q) {
        String created = ISO8601.format(q.createdAt());
        String updated = ISO8601.format(q.updatedAt());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<entry xmlns=\"http://www.w3.org/2005/Atom\">"
                + "<id>https://localhost/" + namespace + "/queues/" + xmlEsc(q.name()) + "</id>"
                + "<title type=\"text\">" + xmlEsc(q.name()) + "</title>"
                + "<published>" + created + "</published>"
                + "<updated>" + updated + "</updated>"
                + "<content type=\"application/xml\">"
                + queueDescriptionXml(q)
                + "</content>"
                + "</entry>";
    }

    private String queueDescriptionXml(ServiceBusModels.QueueEntity q) {
        String lockDuration = isoDuration(q.lockDurationSeconds());
        return "<QueueDescription xmlns=\"" + SB_NS + "\">"
                + "<MaxSizeInMegabytes>" + q.maxSizeInMegabytes() + "</MaxSizeInMegabytes>"
                + "<DefaultMessageTimeToLive>P14D</DefaultMessageTimeToLive>"
                + "<LockDuration>" + lockDuration + "</LockDuration>"
                + "<MaxDeliveryCount>" + q.maxDeliveryCount() + "</MaxDeliveryCount>"
                + "<RequiresDuplicateDetection>false</RequiresDuplicateDetection>"
                + "<RequiresSession>" + q.requiresSession() + "</RequiresSession>"
                + "<DeadLetteringOnMessageExpiration>false</DeadLetteringOnMessageExpiration>"
                + "<EnableBatchedOperations>true</EnableBatchedOperations>"
                + "<AutoDeleteOnIdle>P10675199DT2H48M5.4775807S</AutoDeleteOnIdle>"
                + "<Status>Active</Status>"
                + "<EntityAvailabilityStatus>Available</EntityAvailabilityStatus>"
                + messageCountDetailsXml()
                + "</QueueDescription>";
    }

    private String queueFeedXml(String namespace, List<ServiceBusModels.QueueEntity> queues) {
        String now = ISO8601.format(Instant.now());
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
          .append("<feed xmlns=\"http://www.w3.org/2005/Atom\">")
          .append("<title type=\"text\">Queues</title>")
          .append("<id>https://localhost/").append(xmlEsc(namespace)).append("/$Resources/queues</id>")
          .append("<updated>").append(now).append("</updated>");
        for (ServiceBusModels.QueueEntity q : queues) {
            sb.append(queueEntryXml(namespace, q));
        }
        sb.append("</feed>");
        return sb.toString();
    }

    private String topicEntryXml(String namespace, ServiceBusModels.TopicEntity t) {
        String created = ISO8601.format(t.createdAt());
        String updated = ISO8601.format(t.updatedAt());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<entry xmlns=\"http://www.w3.org/2005/Atom\">"
                + "<id>https://localhost/" + xmlEsc(namespace) + "/topics/" + xmlEsc(t.name()) + "</id>"
                + "<title type=\"text\">" + xmlEsc(t.name()) + "</title>"
                + "<published>" + created + "</published>"
                + "<updated>" + updated + "</updated>"
                + "<content type=\"application/xml\">"
                + topicDescriptionXml(t)
                + "</content>"
                + "</entry>";
    }

    private String topicDescriptionXml(ServiceBusModels.TopicEntity t) {
        return "<TopicDescription xmlns=\"" + SB_NS + "\">"
                + "<MaxSizeInMegabytes>" + t.maxSizeInMegabytes() + "</MaxSizeInMegabytes>"
                + "<DefaultMessageTimeToLive>P14D</DefaultMessageTimeToLive>"
                + "<RequiresDuplicateDetection>false</RequiresDuplicateDetection>"
                + "<EnableBatchedOperations>true</EnableBatchedOperations>"
                + "<AutoDeleteOnIdle>P10675199DT2H48M5.4775807S</AutoDeleteOnIdle>"
                + "<Status>Active</Status>"
                + "<EntityAvailabilityStatus>Available</EntityAvailabilityStatus>"
                + messageCountDetailsXml()
                + "</TopicDescription>";
    }

    private String topicFeedXml(String namespace, List<ServiceBusModels.TopicEntity> topics) {
        String now = ISO8601.format(Instant.now());
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
          .append("<feed xmlns=\"http://www.w3.org/2005/Atom\">")
          .append("<title type=\"text\">Topics</title>")
          .append("<id>https://localhost/").append(xmlEsc(namespace)).append("/$Resources/topics</id>")
          .append("<updated>").append(now).append("</updated>");
        for (ServiceBusModels.TopicEntity t : topics) {
            sb.append(topicEntryXml(namespace, t));
        }
        sb.append("</feed>");
        return sb.toString();
    }

    private String subscriptionEntryXml(String namespace, ServiceBusModels.SubscriptionEntity s) {
        String created = ISO8601.format(s.createdAt());
        String updated = ISO8601.format(s.updatedAt());
        String lockDuration = isoDuration(s.lockDurationSeconds());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<entry xmlns=\"http://www.w3.org/2005/Atom\">"
                + "<id>https://localhost/" + xmlEsc(namespace) + "/topics/" + xmlEsc(s.topicName())
                + "/subscriptions/" + xmlEsc(s.name()) + "</id>"
                + "<title type=\"text\">" + xmlEsc(s.name()) + "</title>"
                + "<published>" + created + "</published>"
                + "<updated>" + updated + "</updated>"
                + "<content type=\"application/xml\">"
                + subscriptionDescriptionXml(s, lockDuration)
                + "</content>"
                + "</entry>";
    }

    private String subscriptionDescriptionXml(ServiceBusModels.SubscriptionEntity s, String lockDuration) {
        return "<SubscriptionDescription xmlns=\"" + SB_NS + "\">"
                + "<LockDuration>" + lockDuration + "</LockDuration>"
                + "<MaxDeliveryCount>" + s.maxDeliveryCount() + "</MaxDeliveryCount>"
                + "<RequiresSession>" + s.requiresSession() + "</RequiresSession>"
                + "<DefaultMessageTimeToLive>P14D</DefaultMessageTimeToLive>"
                + "<DeadLetteringOnMessageExpiration>false</DeadLetteringOnMessageExpiration>"
                + "<DeadLetteringOnFilterEvaluationExceptions>true</DeadLetteringOnFilterEvaluationExceptions>"
                + "<EnableBatchedOperations>true</EnableBatchedOperations>"
                + "<AutoDeleteOnIdle>P10675199DT2H48M5.4775807S</AutoDeleteOnIdle>"
                + "<Status>Active</Status>"
                + "<EntityAvailabilityStatus>Available</EntityAvailabilityStatus>"
                + messageCountDetailsXml()
                + "</SubscriptionDescription>";
    }

    private String subscriptionFeedXml(String namespace, String topicName,
                                        List<ServiceBusModels.SubscriptionEntity> subs) {
        String now = ISO8601.format(Instant.now());
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
          .append("<feed xmlns=\"http://www.w3.org/2005/Atom\">")
          .append("<title type=\"text\">Subscriptions</title>")
          .append("<id>https://localhost/").append(xmlEsc(namespace)).append("/topics/")
          .append(xmlEsc(topicName)).append("/subscriptions</id>")
          .append("<updated>").append(now).append("</updated>");
        for (ServiceBusModels.SubscriptionEntity s : subs) {
            sb.append(subscriptionEntryXml(namespace, s));
        }
        sb.append("</feed>");
        return sb.toString();
    }

    /**
     * Returns a {@code <MessageCountDetails>} element using the spec-mandated 2011/06 namespace.
     * All counts are zero because message state lives entirely in Artemis.
     */
    private String messageCountDetailsXml() {
        return "<MessageCountDetails xmlns=\"" + SB_COUNT_NS + "\">"
                + "<ActiveMessageCount>0</ActiveMessageCount>"
                + "<DeadLetterMessageCount>0</DeadLetterMessageCount>"
                + "<ScheduledMessageCount>0</ScheduledMessageCount>"
                + "<TransferDeadLetterMessageCount>0</TransferDeadLetterMessageCount>"
                + "<TransferMessageCount>0</TransferMessageCount>"
                + "</MessageCountDetails>";
    }

    // ── Storage key helpers ───────────────────────────────────────────────────

    private static String queuePrefix(String account, String namespace) {
        return "sb/" + account + "/" + namespace + "/queues/";
    }

    private static String queueKey(String account, String namespace, String queueName) {
        return queuePrefix(account, namespace) + queueName;
    }

    private static String topicPrefix(String account, String namespace) {
        return "sb/" + account + "/" + namespace + "/topics/";
    }

    private static String topicKey(String account, String namespace, String topicName) {
        return topicPrefix(account, namespace) + topicName;
    }

    private static String subPrefix(String account, String namespace, String topicName) {
        return "sb/" + account + "/" + namespace + "/topics/" + topicName + "/subscriptions/";
    }

    private static String subKey(String account, String namespace, String topicName, String subName) {
        return subPrefix(account, namespace, topicName) + subName;
    }

    // ── Utility helpers ───────────────────────────────────────────────────────

    /**
     * Returns the first running namespace for this account (if any).
     * In practice there will only be one namespace active at a time.
     */
    private Optional<String> resolveActiveNamespace() {
        if (namespaceManager.listNamespaces().isEmpty()) {
            lazyStartNamespace(DEFAULT_NAMESPACE);
        }
        return namespaceManager.listNamespaces().keySet().stream().findFirst();
    }

    private synchronized void lazyStartNamespace(String name) {
        if (namespaceManager.getNamespace(name).isPresent()) return;
        EmulatorConfig.ServiceBusConfig sb = config.services().serviceBus();
        if (sb.mocked()) {
            namespaceManager.startMockedNamespace(name);
            return;
        }
        try {
            namespaceManager.startNamespace(name, sb.amqpPort(), sb.amqpTlsPort());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to lazily start Service Bus namespace '%s'", name);
        }
    }

    private static void appendNamespaceJson(StringBuilder sb, String name,
                                             ServiceBusNamespaceManager.NamespaceState state) {
        String certPem = state.tlsCertPem() != null
                ? state.tlsCertPem().replace("\r", "").replace("\n", "\\n").replace("\"", "\\\"")
                : "";
        sb.append("{\"name\":\"").append(name).append("\"")
          .append(",\"amqpPort\":").append(state.amqpHostPort())
          .append(",\"amqpsPort\":").append(state.amqpsHostPort())
          .append(",\"tlsCertPem\":\"").append(certPem).append("\"")
          .append(",\"mocked\":").append(state.mocked())
          .append("}");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonBody(AzureRequest req) {
        try {
            if (req.bodyStream() != null && req.bodyStream().available() > 0) {
                return MAPPER.readValue(req.bodyStream(), Map.class);
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    private static String readBody(AzureRequest req) {
        try {
            InputStream is = req.bodyStream();
            if (is == null) return "";
            byte[] bytes = is.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(value.toString());
    }

    /** Converts seconds to an ISO 8601 duration string, e.g. 60 → "PT1M", 90 → "PT1M30S". */
    private static String isoDuration(long seconds) {
        if (seconds < 60) return "PT" + seconds + "S";
        long minutes = seconds / 60;
        long secs = seconds % 60;
        if (secs == 0) return "PT" + minutes + "M";
        return "PT" + minutes + "M" + secs + "S";
    }

    /** Minimal XML attribute/content escaping. */
    private static String xmlEsc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private <T> StoredObject toStoredObject(String key, T value) {
        try {
            byte[] data = MAPPER.writeValueAsBytes(value);
            return new StoredObject(key, data, Map.of(), Instant.now(), key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize entity", e);
        }
    }

    private <T> T fromBytes(byte[] data, Class<T> type) {
        try {
            return MAPPER.readValue(data, type);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to deserialize entity of type %s", type.getSimpleName());
            return null;
        }
    }

    private Response namespaceNotStarted(String namespace) {
        return Response.status(404)
                .entity("{\"error\":\"Service Bus namespace '" + namespace + "' is not running. "
                        + "Start it first via PUT /namespaces/" + namespace + "\"}")
                .type("application/json").build();
    }

    private static Response notFound(String message) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"" + message + "\"}")
                .type("application/json").build();
    }

    private static Response notFoundAtom(String message) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Error><Code>404</Code><Detail>" + xmlEsc(message) + "</Detail></Error>")
                .type(ATOM_XML_CONTENT_TYPE).build();
    }
}
