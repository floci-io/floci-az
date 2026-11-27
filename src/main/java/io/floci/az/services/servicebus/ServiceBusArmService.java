package io.floci.az.services.servicebus;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.Resettable;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.GlobalNameRegistry;
import io.floci.az.core.arm.GlobalNameSource;
import io.floci.az.core.arm.StoreIndexSource;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudDefaults;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.cloud.CloudSuffix;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.servicebus.ServiceBusArmModels.ArmProperties;
import io.floci.az.services.servicebus.ServiceBusArmModels.AuthorizationRule;
import io.floci.az.services.servicebus.ServiceBusArmModels.Namespace;
import io.floci.az.services.servicebus.ServiceBusModels.QueueEntity;
import io.floci.az.services.servicebus.ServiceBusModels.RuleEntity;
import io.floci.az.services.servicebus.ServiceBusModels.SubscriptionEntity;
import io.floci.az.services.servicebus.ServiceBusModels.TopicEntity;
import io.floci.az.services.servicebus.ServiceBusNamespaceManager.MessageCounts;
import io.floci.az.services.servicebus.ServiceBusNamespaceManager.NamespaceState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The {@code Microsoft.ServiceBus} management plane: namespaces, their queues, topics, subscriptions and rules, the
 * shared access authorization rules of namespaces, queues and topics, and the namespace network rule set.
 *
 * <pre>
 *   PUT/GET/PATCH/DELETE .../namespaces/{ns}                    GET .../namespaces (resource group or subscription)
 *   PUT/GET/DELETE       .../namespaces/{ns}/queues/{q}          GET .../queues
 *   PUT/GET/DELETE       .../namespaces/{ns}/topics/{t}          GET .../topics
 *   PUT/GET/DELETE       .../topics/{t}/subscriptions/{s}        GET .../subscriptions
 *   PUT/GET/DELETE       .../subscriptions/{s}/rules/{r}         GET .../rules
 *   PUT/GET/DELETE       .../{namespaces/{ns}|queues/{q}|topics/{t}}/authorizationRules/{rule}
 *   POST                 .../authorizationRules/{rule}/listKeys | regenerateKeys
 *   PUT/GET              .../namespaces/{ns}/networkRuleSets/default   GET .../networkRuleSets
 *   POST                 /subscriptions/{sub}/providers/Microsoft.ServiceBus/CheckNameAvailability
 * </pre>
 *
 * <p>Shapes follow the resource-manager spec at 2024-01-01, the version azurerm pins; 2026-01-01, which the az CLI
 * sends, only adds properties. How floci-az departs from Azure:
 * <ul>
 *   <li>A namespace PUT and DELETE are long-running in Azure. floci-az finishes the write before answering, so the
 *       body already reads {@code Succeeded} and no polling header is sent.</li>
 *   <li>An ARM namespace is the broker namespace of the same name: the PUT starts its broker (a mocked one when
 *       Service Bus is mocked), and its queues, topics, subscriptions and rules are the entities the Atom API and the
 *       broker see. Only what the entity records do not model is kept beside them.</li>
 *   <li>A PUT of a queue, topic or subscription that exists answers it unchanged, as the Atom API does: updating an
 *       entity in place is not supported yet. The ARM-only properties the PUT sends are still stored.</li>
 *   <li>The connection strings name the broker's AMQP endpoint, not {@code {ns}.servicebus.windows.net}, and carry
 *       {@code UseDevelopmentEmulator=true}, which is how the SDKs connect to floci-az. Keys are not checked.</li>
 * </ul>
 */
@ApplicationScoped
public class ServiceBusArmService implements StoreIndexSource, GlobalNameSource, Resettable {

    private static final Logger LOG = Logger.getLogger(ServiceBusArmService.class);

    private static final String STORE = "servicebus";
    private static final String PROVIDER = ServiceBusArmSurface.NAMESPACE + "/";

    /** The {@code type} each resource answers with, spelled as Azure spells it. */
    private static final String NAMESPACE_TYPE = PROVIDER + "Namespaces";
    private static final String QUEUE_TYPE = NAMESPACE_TYPE + "/Queues";
    private static final String TOPIC_TYPE = NAMESPACE_TYPE + "/Topics";
    private static final String SUBSCRIPTION_TYPE = TOPIC_TYPE + "/Subscriptions";
    private static final String RULE_TYPE = SUBSCRIPTION_TYPE + "/Rules";
    private static final String NAMESPACE_RULE_TYPE = NAMESPACE_TYPE + "/AuthorizationRules";
    private static final String QUEUE_RULE_TYPE = QUEUE_TYPE + "/AuthorizationRules";
    private static final String TOPIC_RULE_TYPE = TOPIC_TYPE + "/AuthorizationRules";
    private static final String NETWORK_RULE_SET_TYPE = NAMESPACE_TYPE + "/NetworkRuleSets";

    private static final String ROOT_RULE = "RootManageSharedAccessKey";
    private static final String NETWORK_RULE_SET = "default";
    private static final List<String> ALL_RIGHTS = List.of("Listen", "Send", "Manage");
    private static final List<String> SKUS = List.of("Basic", "Standard", "Premium");
    private static final String PREMIUM = "Premium";

    private static final Set<String> NAMESPACE_READ_ONLY = Set.of("provisioningState", "status", "createdAt",
            "updatedAt", "serviceBusEndpoint", "metricId");
    private static final Set<String> ENTITY_READ_ONLY = Set.of("countDetails", "createdAt", "updatedAt",
            "accessedAt", "sizeInBytes", "messageCount", "subscriptionCount");

    /** TimeSpan.MaxValue, which Azure answers for a duration nobody set. */
    private static final String MAX_DURATION = ServiceBusEntityXml.EntityMetadata.DEFAULT_AUTO_DELETE_ON_IDLE;
    private static final int STANDARD_MAX_MESSAGE_KB = 256;
    private static final int PREMIUM_MAX_MESSAGE_KB = 1024;
    private static final int SQL_COMPATIBILITY_LEVEL = 20;
    private static final int KEY_BYTES = 32;

    private final EmulatorConfig config;
    private final GlobalNameRegistry names;
    private final CloudDefaults defaults;
    private final ServiceBusEntities entities;
    private final ServiceBusNamespaceManager brokers;
    private final ArmResourceStore<Namespace> namespaces;
    private final ArmResourceStore<AuthorizationRule> namespaceRules;
    private final ArmResourceStore<AuthorizationRule> queueRules;
    private final ArmResourceStore<AuthorizationRule> topicRules;
    private final ArmResourceStore<ArmProperties> queueProperties;
    private final ArmResourceStore<ArmProperties> topicProperties;
    private final ArmResourceStore<ArmProperties> subscriptionProperties;
    private final ArmResourceStore<ArmProperties> networkRuleSets;
    /** Per instance, not static: a static one would be built into the native image with a fixed seed. */
    private final SecureRandom random = new SecureRandom();

    @Inject
    public ServiceBusArmService(EmulatorConfig config, StorageFactory storageFactory, GlobalNameRegistry names,
                                CloudDefaults defaults, ServiceBusEntities entities,
                                ServiceBusNamespaceManager brokers) {
        this.config = config;
        this.names = names;
        this.defaults = defaults;
        this.entities = entities;
        this.brokers = brokers;
        this.namespaces = store(storageFactory, ServiceBusArmSurface.NAMESPACES, Namespace.class, Namespace::id);
        this.namespaceRules = store(storageFactory, ServiceBusArmSurface.NAMESPACE_RULES, AuthorizationRule.class,
                AuthorizationRule::id);
        this.queueRules = store(storageFactory, ServiceBusArmSurface.QUEUE_RULES, AuthorizationRule.class,
                AuthorizationRule::id);
        this.topicRules = store(storageFactory, ServiceBusArmSurface.TOPIC_RULES, AuthorizationRule.class,
                AuthorizationRule::id);
        this.queueProperties = store(storageFactory, ServiceBusArmSurface.QUEUES, ArmProperties.class,
                ArmProperties::id);
        this.topicProperties = store(storageFactory, ServiceBusArmSurface.TOPICS, ArmProperties.class,
                ArmProperties::id);
        this.subscriptionProperties = store(storageFactory, ServiceBusArmSurface.SUBSCRIPTIONS, ArmProperties.class,
                ArmProperties::id);
        this.networkRuleSets = store(storageFactory, ServiceBusArmSurface.NETWORK_RULE_SETS, ArmProperties.class,
                ArmProperties::id);
    }

    private static <T> ArmResourceStore<T> store(StorageFactory factory, String typePath, Class<T> type,
                                                 Function<T, String> idOf) {
        return ArmResourceStore.create(factory, STORE, PROVIDER + typePath, type, idOf, key -> false);
    }

    /** Serves one request under {@code /providers/Microsoft.ServiceBus/}. */
    public Response handle(AzureRequest req) {
        try {
            return route(req);
        } catch (ArmJson.InvalidBodyException e) {
            return invalid("The request content was invalid and could not be deserialized.");
        } catch (Invalid e) {
            return invalid(e.getMessage());
        } catch (ServiceBusEntities.Refused e) {
            return ArmErrors.error(e.status() == 404 ? 404 : e.status() >= 500 ? 500 : 400,
                    e.status() == 404 ? "NotFound" : e.status() >= 500 ? "InternalServerError" : "BadRequest",
                    e.getMessage());
        }
    }

    // ── Routing ──────────────────────────────────────────────────────────────

    private Response route(AzureRequest req) {
        String path = withoutQuery(req.resourcePath());
        String method = req.method().toUpperCase(Locale.ROOT);
        List<String> tail = segments(ArmPaths.afterProvider(path, ServiceBusArmSurface.NAMESPACE, ""));

        if (tail.size() == 1 && tail.getFirst().equalsIgnoreCase("CheckNameAvailability")) {
            return "POST".equals(method) ? checkNameAvailability(req) : methodNotAllowed();
        }
        if (tail.isEmpty() || !tail.getFirst().equalsIgnoreCase(ServiceBusArmSurface.NAMESPACES)) {
            return unknownPath(tail);
        }

        String action = null;
        String resourcePath = path;
        String last = tail.getLast();
        if (tail.size() >= 4 && (last.equalsIgnoreCase("listKeys") || last.equalsIgnoreCase("regenerateKeys"))) {
            action = last.toLowerCase(Locale.ROOT);
            resourcePath = path.substring(0, path.length() - last.length() - 1);
        }
        Optional<ArmResourceId> parsed = ArmResourceId.parse(resourcePath);
        if (parsed.isEmpty() || parsed.get().subscriptionId() == null) {
            return unknownPath(tail);
        }
        ArmResourceId id = parsed.get();
        String kind = String.join("/", id.types()).toLowerCase(Locale.ROOT);
        boolean collection = id.isCollection();

        if (kind.equals("namespaces") && collection) {
            return "GET".equals(method) ? listNamespaces(req) : methodNotAllowed();
        }
        if (id.resourceGroup() == null) {
            return ArmErrors.notFound("The Resource '" + id.typePath() + "' was not found.");
        }
        ArmResourceId namespaceId = namespaceId(id);
        if (kind.equals("namespaces")) {
            return switch (method) {
                case "PUT" -> putNamespace(req, id);
                case "GET" -> namespaces.get(id).map(stored -> ok(namespaceBody(req, stored.value())))
                        .orElseGet(() -> notFound(id, "namespaces"));
                case "PATCH" -> patchNamespace(req, id);
                case "DELETE" -> deleteNamespace(id);
                default -> methodNotAllowed();
            };
        }

        Optional<Namespace> namespace = namespaces.get(namespaceId).map(ArmResourceStore.Stored::value);
        if (namespace.isEmpty()) {
            return parentNotFound(id, namespaceId);
        }
        // After a restart with persistent storage the namespace is stored but its broker is not running; start it
        // here, with the port choice the namespace PUT makes, rather than leave it to the entity store's lazy start.
        Optional<Response> started = startBroker(namespace.get().name());
        if (started.isPresent()) {
            return started.get();
        }
        Scope scope = new Scope(req, namespace.get(), id, collection, method);
        return switch (kind) {
            case "namespaces/authorizationrules" -> authorizationRules(scope, namespaceRules, NAMESPACE_RULE_TYPE,
                    null, action);
            case "namespaces/networkrulesets" -> networkRuleSets(scope);
            case "namespaces/disasterrecoveryconfigs" -> disasterRecoveryConfigs(scope);
            case "namespaces/queues" -> queues(scope);
            case "namespaces/queues/authorizationrules" -> entityRules(scope, queueRules, QUEUE_RULE_TYPE,
                    entities.queue(scope.broker(), id.names().get(1)).map(QueueEntity::name), action);
            case "namespaces/topics" -> topics(scope);
            case "namespaces/topics/authorizationrules" -> entityRules(scope, topicRules, TOPIC_RULE_TYPE,
                    entities.topic(scope.broker(), id.names().get(1)).map(TopicEntity::name), action);
            case "namespaces/topics/subscriptions" -> subscriptions(scope);
            case "namespaces/topics/subscriptions/rules" -> rules(scope);
            default -> unknownPath(tail);
        };
    }

    /**
     * What a child request addresses: the stored namespace, the request id, whether it lists a collection, and the
     * method. {@link #broker()} is the broker namespace the entities live in.
     */
    private record Scope(AzureRequest req, Namespace namespace, ArmResourceId id, boolean collection, String method) {

        String broker() {
            return namespace.name();
        }

        /** The name segment at {@code index}: 0 the namespace, 1 the queue or topic, 2 the subscription. */
        String name(int index) {
            return id.names().get(index);
        }

        /** The id of the resource the request names, under the stored namespace's casing. */
        String childId(String... segments) {
            return namespace.id() + "/" + String.join("/", segments);
        }
    }

    // ── Namespaces ───────────────────────────────────────────────────────────

    /** {@code Namespaces_CreateOrUpdate}: the body replaces the namespace; the keys and creation time stay. */
    private Response putNamespace(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Map<String, Object> sku = sku(body.get("sku"), null);
        Map<String, Object> properties = namespaceProperties(ArmJson.cast(body.get("properties")),
                Map.of(), String.valueOf(sku.get("name")));
        String location = location(req, body);

        ArmResourceStore.Written<Namespace> written;
        try {
            written = namespaces.createOrUpdate(id, existing -> {
                existing.ifPresent(stored -> refuseLocationChange(stored, location));
                String now = now();
                return new Namespace(existing.map(Namespace::id).orElse(id.id()),
                        existing.map(Namespace::name).orElse(id.name()), location,
                        new LinkedHashMap<>(ArmJson.cast(body.get("tags"))), sku,
                        identity(req, body.get("identity"), existing.map(Namespace::identity).orElse(null)),
                        properties, existing.map(Namespace::createdAt).orElse(now), now);
            });
        } catch (Refused refusal) {
            return refusal.response();
        }
        Namespace namespace = written.stored().value();
        // The broker starts only once the store has accepted the PUT, under the stored name: a refused PUT, or a PUT
        // spelling an existing namespace in another casing, must not start a broker nothing would ever stop.
        Optional<Response> started = startBroker(namespace.name());
        if (started.isPresent()) {
            if (written.created()) {
                namespaces.delete(ArmResourceId.parse(namespace.id()).orElseThrow());
            }
            return started.get();
        }
        ArmResourceId rootRule = ArmResourceId.parse(namespace.id() + "/AuthorizationRules/" + ROOT_RULE).orElseThrow();
        namespaceRules.createOrUpdate(rootRule, existing -> existing.orElseGet(
                () -> new AuthorizationRule(rootRule.id(), ROOT_RULE, ALL_RIGHTS, newKey(), newKey())));
        LOG.infov("ARM: {0} Service Bus namespace {1}", written.created() ? "created" : "updated", namespace.id());
        return Response.status(written.created() ? 201 : 200).entity(namespaceBody(req, namespace))
                .type("application/json").build();
    }

    /** {@code Namespaces_Update}: what the body carries replaces the namespace's; properties merge key by key. */
    private Response patchNamespace(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        return namespaces.update(id, namespace -> {
                    Map<String, Object> sku = body.containsKey("sku") ? sku(body.get("sku"), namespace.sku())
                            : namespace.sku();
                    Map<String, Object> properties = namespaceProperties(ArmJson.cast(body.get("properties")),
                            namespace.properties(), String.valueOf(sku.get("name")));
                    return new Namespace(namespace.id(), namespace.name(), namespace.location(),
                            body.containsKey("tags") ? new LinkedHashMap<>(ArmJson.cast(body.get("tags")))
                                    : namespace.tags(),
                            sku,
                            body.containsKey("identity") ? identity(req, body.get("identity"), namespace.identity())
                                    : namespace.identity(),
                            properties, namespace.createdAt(), now());
                })
                .map(stored -> ok(namespaceBody(req, stored.value())))
                .orElseGet(() -> notFound(id, "namespaces"));
    }

    /**
     * {@code Namespaces_Delete}: removes the namespace with everything under it, its entities on the broker included,
     * and stops its broker. {@code 200} for a namespace that existed, {@code 204} for one that did not.
     */
    private Response deleteNamespace(ArmResourceId id) {
        Optional<ArmResourceStore.Stored<Namespace>> found = namespaces.get(id);
        if (found.isEmpty()) {
            return Response.noContent().build();
        }
        Namespace namespace = found.get().value();
        String broker = namespace.name();
        for (TopicEntity topic : entities.topics(broker)) {
            entities.deleteTopic(broker, topic.name());
        }
        for (QueueEntity queue : entities.queues(broker)) {
            entities.deleteQueue(broker, queue.name());
        }
        brokers.stopNamespace(broker);
        ArmResourceId stored = ArmResourceId.parse(namespace.id()).orElseThrow();
        for (ArmResourceStore<?> children : List.of(namespaceRules, queueRules, topicRules, queueProperties,
                topicProperties, subscriptionProperties, networkRuleSets)) {
            children.under(stored).forEach(child -> children.delete(child.id()));
        }
        namespaces.delete(id);
        LOG.infov("ARM: deleted Service Bus namespace {0}", namespace.id());
        return Response.ok().build();
    }

    /** The namespaces of the request's subscription, or of its resource group when it names one. */
    private Response listNamespaces(AzureRequest req) {
        List<Map<String, Object>> value = scope(req)
                .map(scope -> namespaces.list(scope).stream().map(stored -> namespaceBody(req, stored.value()))
                        .toList())
                .orElse(List.of());
        return ok(Map.of("value", value));
    }

    /**
     * {@code Namespaces_CheckNameAvailability}: {@code InvalidName} for a name outside the namespace rule,
     * {@code NameInUse} for one a namespace in any subscription holds.
     */
    private Response checkNameAvailability(AzureRequest req) {
        String name = ArmJson.string(ArmJson.parseBodyStrict(req), "name", "");
        Map<String, Object> answer = new LinkedHashMap<>();
        if (!ServiceBusArmSurface.NAMESPACE_NAME.accepts(name)) {
            answer.put("nameAvailable", false);
            answer.put("reason", "InvalidName");
            answer.put("message", ServiceBusArmSurface.NAMESPACE_NAME.message(name));
        } else if (names.owner(cloud(req), CloudSuffix.SERVICEBUS, name).isPresent()) {
            answer.put("nameAvailable", false);
            answer.put("reason", "NameInUse");
            answer.put("message", "The specified service namespace is not available.");
        } else {
            answer.put("nameAvailable", true);
            answer.put("reason", "None");
            answer.put("message", "");
        }
        return ok(answer);
    }

    /** The SKU as Azure answers it, from the body's, else {@code fallback}, else {@code Standard}. */
    private static Map<String, Object> sku(Object sent, Map<String, Object> fallback) {
        Map<String, Object> body = ArmJson.cast(sent);
        if (body.isEmpty() && fallback != null) {
            return fallback;
        }
        String requested = ArmJson.string(body, "name", "Standard");
        String name = SKUS.stream().filter(known -> known.equalsIgnoreCase(requested)).findFirst()
                .orElseThrow(() -> new Invalid("The SKU '" + requested + "' is invalid: it must be one of "
                        + String.join(", ", SKUS) + "."));
        Map<String, Object> sku = new LinkedHashMap<>();
        sku.put("name", name);
        sku.put("tier", name);
        Object capacity = body.get("capacity");
        if (capacity instanceof Number units) {
            sku.put("capacity", units.intValue());
        } else if (PREMIUM.equals(name)) {
            sku.put("capacity", 1);
        }
        return sku;
    }

    /** The writable properties: {@code base} with what was sent laid over it, and Azure's defaults under both. */
    private static Map<String, Object> namespaceProperties(Map<String, Object> sent, Map<String, Object> base,
                                                           String sku) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("minimumTlsVersion", "1.2");
        properties.put("publicNetworkAccess", "Enabled");
        properties.put("disableLocalAuth", false);
        properties.put("zoneRedundant", false);
        properties.put("premiumMessagingPartitions", PREMIUM.equals(sku) ? 1 : 0);
        properties.putAll(base);
        sent.forEach((key, value) -> {
            if (!NAMESPACE_READ_ONLY.contains(key) && value != null) {
                properties.put(key, value);
            }
        });
        return properties;
    }

    /** The identity to store: null for none, else the sent one with a principal for a system-assigned identity. */
    private static Map<String, Object> identity(AzureRequest req, Object sent, Map<String, Object> existing) {
        Map<String, Object> body = ArmJson.cast(sent);
        String type = ArmJson.string(body, "type", "None");
        if ("None".equalsIgnoreCase(type)) {
            return null;
        }
        Map<String, Object> identity = new LinkedHashMap<>(body);
        if (type.toLowerCase(Locale.ROOT).contains("systemassigned")) {
            identity.put("principalId", existing != null && existing.get("principalId") instanceof String principal
                    ? principal : UUID.randomUUID().toString());
            if (req.scope() != null && req.scope().tenantId() != null) {
                identity.put("tenantId", req.scope().tenantId());
            }
        }
        return identity;
    }

    /**
     * Starts the namespace's broker unless it runs: a mocked one when Service Bus is mocked. The first broker binds
     * the configured AMQP host ports; later ones get dynamic ports so they do not collide, as the topology file's
     * namespaces do. Empty when the broker runs; the ARM error otherwise.
     */
    private Optional<Response> startBroker(String name) {
        if (brokers.getNamespace(name).isPresent()) {
            return Optional.empty();
        }
        EmulatorConfig.ServiceBusConfig sb = config.services().serviceBus();
        if (sb.mocked()) {
            brokers.startMockedNamespace(name);
            return Optional.empty();
        }
        boolean first = brokers.listNamespaces().isEmpty();
        try {
            brokers.startNamespace(name, first ? sb.amqpPort() : 0, first ? sb.amqpTlsPort() : 0);
            return Optional.empty();
        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to start the broker of Service Bus namespace '%s'", name);
            return Optional.of(ArmErrors.error(500, "InternalServerError",
                    "The broker of Service Bus namespace '" + name + "' could not be started: " + e.getMessage()));
        }
    }

    private Map<String, Object> namespaceBody(AzureRequest req, Namespace namespace) {
        CloudProfile cloud = cloud(req);
        ArmResourceId id = ArmResourceId.parse(namespace.id()).orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", namespace.sku());
        if (namespace.identity() != null) {
            body.put("identity", namespace.identity());
        }
        body.put("id", namespace.id());
        body.put("name", namespace.name());
        body.put("type", NAMESPACE_TYPE);
        body.put("location", namespace.location());
        body.put("tags", namespace.tags() == null ? Map.of() : namespace.tags());
        Map<String, Object> properties = new LinkedHashMap<>(namespace.properties());
        properties.put("provisioningState", "Succeeded");
        properties.put("status", "Active");
        properties.put("metricId", id.subscriptionId() + ":" + namespace.name().toLowerCase(Locale.ROOT));
        properties.put("createdAt", namespace.createdAt());
        properties.put("updatedAt", namespace.updatedAt());
        properties.put("serviceBusEndpoint", "https://" + namespace.name() + "."
                + suffix(cloud, CloudSuffix.SERVICEBUS) + ":443/");
        body.put("properties", properties);
        body.put("systemData", Map.of("createdAt", namespace.createdAt(), "lastModifiedAt", namespace.updatedAt()));
        return body;
    }

    // ── Network rule set ─────────────────────────────────────────────────────

    /**
     * {@code networkRuleSets/default}: answered for every namespace, with Azure's defaults until a PUT stores one,
     * because azurerm reads it after every namespace read and fails when it cannot.
     */
    private Response networkRuleSets(Scope scope) {
        if (scope.collection()) {
            return "GET".equals(scope.method())
                    ? ok(Map.of("value", List.of(networkRuleSetBody(scope)))) : methodNotAllowed();
        }
        if (!NETWORK_RULE_SET.equalsIgnoreCase(scope.id().name())) {
            return ArmErrors.notFound("The network rule set '" + scope.id().name() + "' was not found: a namespace"
                    + " has only 'default'.");
        }
        return switch (scope.method()) {
            case "GET" -> ok(networkRuleSetBody(scope));
            case "PUT" -> {
                Map<String, Object> sent = ArmJson.cast(ArmJson.parseBodyStrict(scope.req()).get("properties"));
                String id = scope.childId("networkRuleSets", NETWORK_RULE_SET);
                networkRuleSets.put(new ArmProperties(id, new LinkedHashMap<>(sent)));
                yield ok(networkRuleSetBody(scope));
            }
            default -> methodNotAllowed();
        };
    }

    private Map<String, Object> networkRuleSetBody(Scope scope) {
        String id = scope.childId("networkRuleSets", NETWORK_RULE_SET);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("publicNetworkAccess", scope.namespace().properties().getOrDefault("publicNetworkAccess",
                "Enabled"));
        properties.put("defaultAction", "Allow");
        properties.put("virtualNetworkRules", List.of());
        properties.put("ipRules", List.of());
        properties.put("trustedServiceAccessEnabled", false);
        networkRuleSets.get(ArmResourceId.parse(id).orElseThrow())
                .ifPresent(stored -> stored.value().properties().forEach((key, value) -> {
                    if (value != null) {
                        properties.put(key, value);
                    }
                }));
        return resource(id, NETWORK_RULE_SET, NETWORK_RULE_SET_TYPE, properties);
    }

    // ── Geo-disaster recovery ────────────────────────────────────────────────

    /**
     * {@code DisasterRecoveryConfigs_*}: no namespace is ever paired, so the list is empty and an alias is not found.
     * azurerm lists the aliases after writing any authorization rule on a Premium namespace and fails the apply when
     * the list fails; pairing two namespaces is not emulated.
     */
    private Response disasterRecoveryConfigs(Scope scope) {
        if (scope.collection()) {
            return "GET".equals(scope.method()) ? ok(Map.of("value", List.of())) : methodNotAllowed();
        }
        return switch (scope.method()) {
            case "GET" -> ArmErrors.notFound("The disaster recovery configuration '" + scope.id().name()
                    + "' could not be found.");
            case "DELETE" -> Response.noContent().build();
            default -> invalid("Geo-disaster recovery pairing is not emulated: the namespace '"
                    + scope.namespace().name() + "' cannot be paired.");
        };
    }

    // ── Queues ───────────────────────────────────────────────────────────────

    private Response queues(Scope scope) {
        String broker = scope.broker();
        if (scope.collection()) {
            if (!"GET".equals(scope.method())) {
                return methodNotAllowed();
            }
            List<QueueEntity> queues = entities.queues(broker);
            Map<String, MessageCounts> counts = brokers.getMessageCounts(broker,
                    queues.stream().map(QueueEntity::name).toList());
            return ok(Map.of("value", queues.stream()
                    .map(queue -> queueBody(scope, queue, counts.getOrDefault(queue.name(), MessageCounts.ZERO)))
                    .toList()));
        }
        String name = scope.name(1);
        return switch (scope.method()) {
            case "GET" -> entities.queue(broker, name)
                    .map(queue -> ok(queueBody(scope, queue, brokers.getMessageCounts(broker, queue.name()))))
                    .orElseGet(() -> entityNotFound("queue", name, broker));
            case "PUT" -> putQueue(scope, name);
            case "DELETE" -> {
                Optional<QueueEntity> queue = entities.queue(broker, name);
                if (queue.isEmpty()) {
                    yield Response.noContent().build();
                }
                entities.deleteQueue(broker, queue.get().name());
                String id = scope.childId("queues", queue.get().name());
                queueProperties.delete(ArmResourceId.parse(id).orElseThrow());
                deleteRulesUnder(queueRules, id);
                yield Response.ok().build();
            }
            default -> methodNotAllowed();
        };
    }

    /** {@code Queues_CreateOrUpdate}: creates the queue or updates the one that exists, storing what was sent. */
    private Response putQueue(Scope scope, String name) {
        Map<String, Object> sent = sentProperties(scope.req());
        QueueEntity queue = entities.createOrUpdateQueue(scope.broker(), name, bool(sent, "requiresSession", false),
                duplicateDetection(sent), lifetime(sent), delivery(sent), metadata(sent, false), fixed(sent));
        queueProperties.put(new ArmProperties(scope.childId("queues", queue.name()), sent));
        return ok(queueBody(scope, queue, brokers.getMessageCounts(scope.broker(), queue.name())));
    }

    private Map<String, Object> queueBody(Scope scope, QueueEntity queue, MessageCounts counts) {
        String id = scope.childId("queues", queue.name());
        Map<String, Object> sent = sent(queueProperties, id);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("maxMessageSizeInKilobytes", maxMessageSize(scope, sent));
        properties.put("lockDuration", duration(sent, "lockDuration", Duration.ofSeconds(queue.lockDurationSeconds())));
        properties.put("maxSizeInMegabytes", queue.maxSizeInMegabytes());
        properties.put("requiresDuplicateDetection", queue.requiresDuplicateDetection());
        properties.put("requiresSession", queue.requiresSession());
        properties.put("defaultMessageTimeToLive", duration(sent, "defaultMessageTimeToLive",
                Duration.ofMillis(queue.defaultMessageTtlMillis())));
        properties.put("deadLetteringOnMessageExpiration", queue.deadLetteringOnMessageExpiration());
        properties.put("duplicateDetectionHistoryTimeWindow", duration(sent, "duplicateDetectionHistoryTimeWindow",
                Duration.ofSeconds(queue.duplicateDetectionHistorySeconds())));
        properties.put("maxDeliveryCount", queue.maxDeliveryCount());
        properties.put("status", ArmJson.string(sent, "status", "Active"));
        properties.put("enableBatchedOperations", queue.enableBatchedOperations() == null
                || queue.enableBatchedOperations());
        properties.put("autoDeleteOnIdle", queue.autoDeleteOnIdle() == null ? MAX_DURATION : queue.autoDeleteOnIdle());
        properties.put("enablePartitioning", bool(sent, "enablePartitioning", false));
        properties.put("enableExpress", bool(sent, "enableExpress", false));
        copyIfSent(sent, properties, "forwardTo", "forwardDeadLetteredMessagesTo");
        if (queue.userMetadata() != null) {
            properties.put("userMetadata", queue.userMetadata());
        }
        properties.put("sizeInBytes", 0);
        properties.put("messageCount", counts.active() + counts.deadLetter());
        properties.put("countDetails", countDetails(counts.active(), counts.deadLetter()));
        timestamps(properties, queue.createdAt(), queue.updatedAt());
        return resource(id, queue.name(), QUEUE_TYPE, properties);
    }

    // ── Topics ───────────────────────────────────────────────────────────────

    private Response topics(Scope scope) {
        String broker = scope.broker();
        if (scope.collection()) {
            return "GET".equals(scope.method())
                    ? ok(Map.of("value", entities.topics(broker).stream().map(topic -> topicBody(scope, topic))
                            .toList()))
                    : methodNotAllowed();
        }
        String name = scope.name(1);
        return switch (scope.method()) {
            case "GET" -> entities.topic(broker, name).map(topic -> ok(topicBody(scope, topic)))
                    .orElseGet(() -> entityNotFound("topic", name, broker));
            case "PUT" -> {
                Map<String, Object> sent = sentProperties(scope.req());
                TopicEntity topic = entities.createOrUpdateTopic(broker, name, duplicateDetection(sent),
                        lifetime(sent), metadata(sent, true), fixed(sent));
                topicProperties.put(new ArmProperties(scope.childId("topics", topic.name()), sent));
                yield ok(topicBody(scope, topic));
            }
            case "DELETE" -> {
                Optional<TopicEntity> topic = entities.topic(broker, name);
                if (topic.isEmpty()) {
                    yield Response.noContent().build();
                }
                entities.deleteTopic(broker, topic.get().name());
                String id = scope.childId("topics", topic.get().name());
                ArmResourceId topicId = ArmResourceId.parse(id).orElseThrow();
                topicProperties.delete(topicId);
                deleteRulesUnder(topicRules, id);
                subscriptionProperties.under(topicId).forEach(sub -> subscriptionProperties.delete(sub.id()));
                yield Response.ok().build();
            }
            default -> methodNotAllowed();
        };
    }

    private Map<String, Object> topicBody(Scope scope, TopicEntity topic) {
        String id = scope.childId("topics", topic.name());
        Map<String, Object> sent = sent(topicProperties, id);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("maxMessageSizeInKilobytes", maxMessageSize(scope, sent));
        properties.put("defaultMessageTimeToLive", duration(sent, "defaultMessageTimeToLive",
                Duration.ofMillis(topic.defaultMessageTtlMillis())));
        properties.put("maxSizeInMegabytes", topic.maxSizeInMegabytes());
        properties.put("requiresDuplicateDetection", topic.requiresDuplicateDetection());
        properties.put("duplicateDetectionHistoryTimeWindow", duration(sent, "duplicateDetectionHistoryTimeWindow",
                Duration.ofSeconds(topic.duplicateDetectionHistorySeconds())));
        properties.put("enableBatchedOperations", topic.enableBatchedOperations() == null
                || topic.enableBatchedOperations());
        properties.put("status", ArmJson.string(sent, "status", "Active"));
        properties.put("supportOrdering", topic.supportOrdering());
        properties.put("autoDeleteOnIdle", topic.autoDeleteOnIdle() == null ? MAX_DURATION : topic.autoDeleteOnIdle());
        properties.put("enablePartitioning", bool(sent, "enablePartitioning", false));
        properties.put("enableExpress", bool(sent, "enableExpress", false));
        if (topic.userMetadata() != null) {
            properties.put("userMetadata", topic.userMetadata());
        }
        properties.put("sizeInBytes", 0);
        properties.put("subscriptionCount", entities.subscriptions(scope.broker(), topic.name()).size());
        properties.put("countDetails", countDetails(0, 0));
        timestamps(properties, topic.createdAt(), topic.updatedAt());
        return resource(id, topic.name(), TOPIC_TYPE, properties);
    }

    // ── Subscriptions ────────────────────────────────────────────────────────

    private Response subscriptions(Scope scope) {
        String broker = scope.broker();
        Optional<TopicEntity> topic = entities.topic(broker, scope.name(1));
        if (topic.isEmpty()) {
            return entityNotFound("topic", scope.name(1), broker);
        }
        String topicName = topic.get().name();
        if (scope.collection()) {
            return "GET".equals(scope.method())
                    ? ok(Map.of("value", entities.subscriptions(broker, topicName).stream()
                            .map(sub -> subscriptionBody(scope, sub)).toList()))
                    : methodNotAllowed();
        }
        String name = scope.name(2);
        return switch (scope.method()) {
            case "GET" -> entities.subscription(broker, topicName, name).map(sub -> ok(subscriptionBody(scope, sub)))
                    .orElseGet(() -> entityNotFound("subscription", name, topicName));
            case "PUT" -> {
                Map<String, Object> sent = sentProperties(scope.req());
                SubscriptionEntity sub = entities.createOrUpdateSubscription(broker, topicName, name,
                        bool(sent, "requiresSession", false), lifetime(sent), delivery(sent), metadata(sent, false),
                        fixed(sent));
                subscriptionProperties.put(new ArmProperties(subscriptionId(scope, sub), sent));
                yield ok(subscriptionBody(scope, sub));
            }
            case "DELETE" -> {
                Optional<SubscriptionEntity> sub = entities.subscription(broker, topicName, name);
                if (sub.isEmpty()) {
                    yield Response.noContent().build();
                }
                entities.deleteSubscription(broker, topicName, sub.get().name());
                subscriptionProperties.delete(ArmResourceId.parse(subscriptionId(scope, sub.get())).orElseThrow());
                yield Response.ok().build();
            }
            default -> methodNotAllowed();
        };
    }

    private static String subscriptionId(Scope scope, SubscriptionEntity sub) {
        return scope.childId("topics", sub.topicName(), "subscriptions", sub.name());
    }

    private Map<String, Object> subscriptionBody(Scope scope, SubscriptionEntity sub) {
        String id = subscriptionId(scope, sub);
        Map<String, Object> sent = sent(subscriptionProperties, id);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("lockDuration", duration(sent, "lockDuration", Duration.ofSeconds(sub.lockDurationSeconds())));
        properties.put("requiresSession", sub.requiresSession());
        properties.put("defaultMessageTimeToLive", duration(sent, "defaultMessageTimeToLive",
                Duration.ofMillis(sub.defaultMessageTtlMillis())));
        properties.put("deadLetteringOnFilterEvaluationExceptions",
                bool(sent, "deadLetteringOnFilterEvaluationExceptions", true));
        properties.put("deadLetteringOnMessageExpiration", sub.deadLetteringOnMessageExpiration());
        properties.put("maxDeliveryCount", sub.maxDeliveryCount());
        properties.put("status", ArmJson.string(sent, "status", "Active"));
        properties.put("enableBatchedOperations", sub.enableBatchedOperations() == null
                || sub.enableBatchedOperations());
        properties.put("autoDeleteOnIdle", sub.autoDeleteOnIdle() == null ? MAX_DURATION : sub.autoDeleteOnIdle());
        boolean clientAffine = bool(sent, "isClientAffine", false);
        properties.put("isClientAffine", clientAffine);
        // azurerm sends an empty clientAffineProperties on every PUT and reads any it gets back as a
        // client_scoped_subscription block, so it is answered only for a client-affine subscription.
        if (clientAffine) {
            copyIfSent(sent, properties, "clientAffineProperties");
        }
        copyIfSent(sent, properties, "forwardTo", "forwardDeadLetteredMessagesTo",
                "duplicateDetectionHistoryTimeWindow");
        if (sub.userMetadata() != null) {
            properties.put("userMetadata", sub.userMetadata());
        }
        properties.put("messageCount", 0);
        properties.put("countDetails", countDetails(0, 0));
        timestamps(properties, sub.createdAt(), sub.updatedAt());
        return resource(id, sub.name(), SUBSCRIPTION_TYPE, properties);
    }

    // ── Rules ────────────────────────────────────────────────────────────────

    private Response rules(Scope scope) {
        String broker = scope.broker();
        Optional<TopicEntity> topic = entities.topic(broker, scope.name(1));
        if (topic.isEmpty()) {
            return entityNotFound("topic", scope.name(1), broker);
        }
        Optional<SubscriptionEntity> sub = entities.subscription(broker, topic.get().name(), scope.name(2));
        if (sub.isEmpty()) {
            return entityNotFound("subscription", scope.name(2), topic.get().name());
        }
        String topicName = topic.get().name();
        String subName = sub.get().name();
        if (scope.collection()) {
            return "GET".equals(scope.method())
                    ? ok(Map.of("value", entities.rules(broker, topicName, subName).stream()
                            .map(rule -> ruleBody(scope, sub.get(), rule)).toList()))
                    : methodNotAllowed();
        }
        String name = scope.id().name();
        return switch (scope.method()) {
            case "GET" -> entities.rule(broker, topicName, subName, name)
                    .map(rule -> ok(ruleBody(scope, sub.get(), rule)))
                    .orElseGet(() -> entityNotFound("rule", name, subName));
            case "PUT" -> {
                String ruleName = entities.rule(broker, topicName, subName, name).map(RuleEntity::name).orElse(name);
                RuleEntity rule = ruleFrom(topicName, subName, ruleName,
                        ArmJson.cast(ArmJson.parseBodyStrict(scope.req()).get("properties")));
                entities.putRule(broker, rule);
                yield ok(ruleBody(scope, sub.get(), rule));
            }
            case "DELETE" -> entities.deleteRule(broker, topicName, subName, name) ? Response.ok().build()
                    : Response.noContent().build();
            default -> methodNotAllowed();
        };
    }

    /** The rule an ARM {@code Ruleproperties} body describes; a body with no filter accepts every message. */
    private static RuleEntity ruleFrom(String topic, String subscription, String name, Map<String, Object> sent) {
        String filterType = ArmJson.string(sent, "filterType", sent.containsKey("correlationFilter")
                ? "CorrelationFilter" : "SqlFilter");
        String action = ArmJson.string(ArmJson.cast(sent.get("action")), "sqlExpression", null);
        if ("CorrelationFilter".equalsIgnoreCase(filterType)) {
            Map<String, Object> filter = ArmJson.cast(sent.get("correlationFilter"));
            Map<String, String> properties = new LinkedHashMap<>();
            ArmJson.cast(filter.get("properties")).forEach((key, value) -> properties.put(key, String.valueOf(value)));
            return new RuleEntity(topic, subscription, name, "CorrelationFilter", null,
                    ArmJson.string(filter, "correlationId", null), ArmJson.string(filter, "messageId", null),
                    ArmJson.string(filter, "to", null), ArmJson.string(filter, "replyTo", null),
                    ArmJson.string(filter, "label", null), ArmJson.string(filter, "sessionId", null),
                    ArmJson.string(filter, "replyToSessionId", null), ArmJson.string(filter, "contentType", null),
                    properties, Map.of(), action, Instant.now());
        }
        if (!"SqlFilter".equalsIgnoreCase(filterType)) {
            throw new Invalid("The filter type '" + filterType + "' is invalid: it must be SqlFilter or"
                    + " CorrelationFilter.");
        }
        String expression = ArmJson.string(ArmJson.cast(sent.get("sqlFilter")), "sqlExpression", "1=1");
        return new RuleEntity(topic, subscription, name, "SqlFilter", expression, null, null, null, null, null,
                null, null, null, Map.of(), Map.of(), action, Instant.now());
    }

    /**
     * The rule as ARM answers it. The Atom API's {@code TrueFilter} and {@code FalseFilter} are SQL filters to ARM,
     * {@code 1=1} and {@code 1=0}, as Azure answers the {@code $Default} rule.
     */
    private Map<String, Object> ruleBody(Scope scope, SubscriptionEntity sub, RuleEntity rule) {
        String id = subscriptionId(scope, sub) + "/rules/" + rule.name();
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> action = new LinkedHashMap<>();
        if (rule.actionSqlExpression() != null) {
            action.put("sqlExpression", rule.actionSqlExpression());
            action.put("compatibilityLevel", SQL_COMPATIBILITY_LEVEL);
        }
        properties.put("action", action);
        if ("CorrelationFilter".equals(rule.filterType())) {
            properties.put("filterType", "CorrelationFilter");
            Map<String, Object> filter = new LinkedHashMap<>();
            putIfPresent(filter, "correlationId", rule.correlationId());
            putIfPresent(filter, "messageId", rule.messageId());
            putIfPresent(filter, "to", rule.to());
            putIfPresent(filter, "replyTo", rule.replyTo());
            putIfPresent(filter, "label", rule.label());
            putIfPresent(filter, "sessionId", rule.sessionId());
            putIfPresent(filter, "replyToSessionId", rule.replyToSessionId());
            putIfPresent(filter, "contentType", rule.contentType());
            filter.put("properties", rule.correlationProperties());
            properties.put("correlationFilter", filter);
        } else {
            properties.put("filterType", "SqlFilter");
            String expression = switch (rule.filterType()) {
                case "TrueFilter" -> "1=1";
                case "FalseFilter" -> "1=0";
                default -> rule.sqlExpression();
            };
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("sqlExpression", expression);
            filter.put("compatibilityLevel", SQL_COMPATIBILITY_LEVEL);
            properties.put("sqlFilter", filter);
        }
        return resource(id, rule.name(), RULE_TYPE, properties);
    }

    // ── Authorization rules ──────────────────────────────────────────────────

    /** The rules of a queue or a topic, once the entity is known to exist. */
    private Response entityRules(Scope scope, ArmResourceStore<AuthorizationRule> store, String type,
                                 Optional<String> entity, String action) {
        if (entity.isEmpty()) {
            return entityNotFound(store == queueRules ? "queue" : "topic", scope.name(1), scope.broker());
        }
        return authorizationRules(scope, store, type, entity.get(), action);
    }

    /**
     * The shared access authorization rules of the namespace ({@code entityPath} null) or of the queue or topic
     * {@code entityPath}: create, read, list, delete, and the {@code listKeys} and {@code regenerateKeys} actions.
     */
    private Response authorizationRules(Scope scope, ArmResourceStore<AuthorizationRule> store, String type,
                                        String entityPath, String action) {
        String parentId = entityPath == null ? scope.namespace().id()
                : scope.childId(store == queueRules ? "queues" : "topics", entityPath);
        String segment = entityPath == null ? "AuthorizationRules" : "authorizationRules";
        if (scope.collection()) {
            if (!"GET".equals(scope.method())) {
                return methodNotAllowed();
            }
            ArmResourceId parent = ArmResourceId.parse(parentId).orElseThrow();
            return ok(Map.of("value", store.under(parent).stream()
                    .filter(stored -> stored.id().names().size() == parent.names().size() + 1)
                    .map(stored -> ruleBody(scope, stored.value(), type)).toList()));
        }
        ArmResourceId id = ArmResourceId.parse(parentId + "/" + segment + "/" + scope.id().name()).orElseThrow();
        if (action != null) {
            if (!"POST".equals(scope.method())) {
                return methodNotAllowed();
            }
            Optional<AuthorizationRule> rule = store.get(id).map(ArmResourceStore.Stored::value);
            if (rule.isEmpty()) {
                return ruleNotFound(id);
            }
            AuthorizationRule keys = action.equals("regeneratekeys")
                    ? regenerate(store, id, ArmJson.parseBodyStrict(scope.req())) : rule.get();
            return ok(accessKeys(scope, keys, entityPath));
        }
        return switch (scope.method()) {
            case "GET" -> store.get(id).map(stored -> ok(ruleBody(scope, stored.value(), type)))
                    .orElseGet(() -> ruleNotFound(id));
            case "PUT" -> {
                List<String> rights = rights(ArmJson.cast(ArmJson.parseBodyStrict(scope.req()).get("properties")));
                AuthorizationRule rule = store.createOrUpdate(id, existing -> existing
                        .map(stored -> new AuthorizationRule(stored.id(), stored.name(), rights, stored.primaryKey(),
                                stored.secondaryKey()))
                        .orElseGet(() -> new AuthorizationRule(id.id(), id.name(), rights, newKey(), newKey())))
                        .stored().value();
                yield ok(ruleBody(scope, rule, type));
            }
            case "DELETE" -> store.delete(id).isPresent() ? Response.ok().build() : Response.noContent().build();
            default -> methodNotAllowed();
        };
    }

    private AuthorizationRule regenerate(ArmResourceStore<AuthorizationRule> store, ArmResourceId id,
                                         Map<String, Object> body) {
        String keyType = ArmJson.string(body, "keyType", "");
        if (!keyType.equalsIgnoreCase("PrimaryKey") && !keyType.equalsIgnoreCase("SecondaryKey")) {
            throw new Invalid("The 'keyType' must be PrimaryKey or SecondaryKey.");
        }
        String sent = ArmJson.string(body, "key", null);
        String key = sent == null || sent.isBlank() ? newKey() : sent;
        boolean primary = keyType.equalsIgnoreCase("PrimaryKey");
        return store.update(id, rule -> primary ? rule.withKeys(key, rule.secondaryKey())
                        : rule.withKeys(rule.primaryKey(), key))
                .orElseThrow().value();
    }

    private static List<String> rights(Map<String, Object> properties) {
        if (!(properties.get("rights") instanceof List<?> sent) || sent.isEmpty()) {
            throw new Invalid("The 'rights' of an authorization rule are required: one or more of "
                    + String.join(", ", ALL_RIGHTS) + ".");
        }
        List<String> rights = new ArrayList<>();
        for (Object right : sent) {
            String known = ALL_RIGHTS.stream().filter(name -> name.equalsIgnoreCase(String.valueOf(right)))
                    .findFirst()
                    .orElseThrow(() -> new Invalid("The right '" + right + "' is invalid: it must be one of "
                            + String.join(", ", ALL_RIGHTS) + "."));
            if (!rights.contains(known)) {
                rights.add(known);
            }
        }
        if (rights.contains("Manage") && !(rights.contains("Listen") && rights.contains("Send"))) {
            throw new Invalid("An authorization rule with the Manage right must also have Listen and Send.");
        }
        return rights;
    }

    private Map<String, Object> ruleBody(Scope scope, AuthorizationRule rule, String type) {
        return resource(rule.id(), rule.name(), type, Map.of("rights", rule.rights()));
    }

    /** {@code AccessKeys}: the keys and the connection strings built on them, without the alias ones. */
    private Map<String, Object> accessKeys(Scope scope, AuthorizationRule rule, String entityPath) {
        String endpoint = endpoint(scope.req(), scope.broker());
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("primaryConnectionString", connectionString(endpoint, rule.name(), rule.primaryKey(), entityPath));
        keys.put("secondaryConnectionString", connectionString(endpoint, rule.name(), rule.secondaryKey(),
                entityPath));
        keys.put("primaryKey", rule.primaryKey());
        keys.put("secondaryKey", rule.secondaryKey());
        keys.put("keyName", rule.name());
        return keys;
    }

    private static String connectionString(String endpoint, String keyName, String key, String entityPath) {
        return "Endpoint=" + endpoint + ";SharedAccessKeyName=" + keyName + ";SharedAccessKey=" + key
                + ";UseDevelopmentEmulator=true" + (entityPath == null ? "" : ";EntityPath=" + entityPath);
    }

    /**
     * The AMQP endpoint a client reaches the namespace's broker on, as {@code GET /{account}-servicebus/namespaces}
     * reports it: the host and port floci-az itself reaches the broker on. A mocked broker has none, so the request's
     * host and the configured AMQP port stand in.
     */
    private String endpoint(AzureRequest req, String broker) {
        startBroker(broker);
        Optional<NamespaceState> state = brokers.getNamespace(broker);
        if (state.isPresent() && !state.get().mocked() && state.get().amqpPort() > 0
                && state.get().jolokiaHost() != null && !state.get().jolokiaHost().isBlank()) {
            return "sb://" + state.get().jolokiaHost() + ":" + state.get().amqpPort();
        }
        String host = URI.create(RequestUrls.resolveBaseUrl(req, config)).getHost();
        return "sb://" + (host == null ? "localhost" : host) + ":" + config.services().serviceBus().amqpPort();
    }

    private String newKey() {
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static void deleteRulesUnder(ArmResourceStore<AuthorizationRule> store, String parentId) {
        store.under(ArmResourceId.parse(parentId).orElseThrow()).forEach(rule -> store.delete(rule.id()));
    }

    // ── Entity properties from ARM ───────────────────────────────────────────

    /** The {@code properties} of the body, without the read-only ones and nulls. */
    private static Map<String, Object> sentProperties(AzureRequest req) {
        Map<String, Object> sent = new LinkedHashMap<>();
        ArmJson.cast(ArmJson.parseBodyStrict(req).get("properties")).forEach((key, value) -> {
            if (!ENTITY_READ_ONLY.contains(key) && value != null) {
                sent.put(key, value);
            }
        });
        return sent;
    }

    private static ServiceBusEntityXml.DuplicateDetectionSettings duplicateDetection(Map<String, Object> sent) {
        try {
            return ServiceBusEntityXml.duplicateDetectionOf(bool(sent, "requiresDuplicateDetection", false),
                    ArmJson.string(sent, "duplicateDetectionHistoryTimeWindow", null));
        } catch (IllegalArgumentException e) {
            throw new Invalid(e.getMessage());
        }
    }

    private static ServiceBusEntityXml.MessageLifetimeSettings lifetime(Map<String, Object> sent) {
        try {
            return ServiceBusEntityXml.messageLifetimeOf(ArmJson.string(sent, "defaultMessageTimeToLive", null),
                    bool(sent, "deadLetteringOnMessageExpiration", false));
        } catch (IllegalArgumentException e) {
            throw new Invalid(e.getMessage());
        }
    }

    private static ServiceBusEntityXml.DeliverySettings delivery(Map<String, Object> sent) {
        Integer maxDeliveryCount = sent.get("maxDeliveryCount") instanceof Number count ? count.intValue() : null;
        try {
            return ServiceBusEntityXml.deliveryOf(maxDeliveryCount, ArmJson.string(sent, "lockDuration", null));
        } catch (IllegalArgumentException e) {
            throw new Invalid(e.getMessage());
        }
    }

    private static ServiceBusEntityXml.EntityMetadata metadata(Map<String, Object> sent, boolean topic) {
        // azurerm sends 0 for a size its configuration leaves unset, which Azure reads as the SKU's default.
        long maxSize = sent.get("maxSizeInMegabytes") instanceof Number size ? size.longValue() : 0;
        if (maxSize < 0) {
            throw new Invalid("The 'maxSizeInMegabytes' must not be negative.");
        }
        if (maxSize == 0) {
            maxSize = ServiceBusEntityXml.EntityMetadata.DEFAULT_MAX_SIZE_IN_MEGABYTES;
        }
        String autoDelete = ArmJson.string(sent, "autoDeleteOnIdle", null);
        if (autoDelete != null) {
            try {
                if (Duration.parse(autoDelete).compareTo(Duration.ofMinutes(5)) < 0) {
                    throw new Invalid("The 'autoDeleteOnIdle' must be at least PT5M.");
                }
            } catch (DateTimeParseException e) {
                throw new Invalid("The 'autoDeleteOnIdle' must be an ISO 8601 duration.");
            }
        }
        return new ServiceBusEntityXml.EntityMetadata(maxSize, autoDelete,
                bool(sent, "enableBatchedOperations", true), ArmJson.string(sent, "userMetadata", null),
                topic && bool(sent, "supportOrdering", false));
    }

    // ── Response pieces ──────────────────────────────────────────────────────

    /** The properties the last ARM PUT sent for {@code id}, or none. */
    private static Map<String, Object> sent(ArmResourceStore<ArmProperties> store, String id) {
        return store.get(ArmResourceId.parse(id).orElseThrow()).map(stored -> stored.value().properties())
                .orElse(Map.of());
    }

    /**
     * The duration to answer: the sent spelling when it means the stored value, so a client comparing strings sees
     * what it sent ({@code PT60S} stays {@code PT60S}); else the stored value in Azure's spelling. The entities keep
     * milliseconds, so the comparison is at that precision: the unbounded {@code P10675199DT2H48M5.4775807S} azurerm
     * sends by default comes back as sent.
     */
    private static String duration(Map<String, Object> sent, String key, Duration stored) {
        if (sent.get(key) instanceof String text) {
            try {
                if (Duration.parse(text.trim()).toMillis() == stored.toMillis()) {
                    return text;
                }
            } catch (DateTimeParseException | ArithmeticException e) {
                LOG.debugf("Stored ARM duration %s='%s' does not parse; answering the entity's", key, text);
            }
        }
        return ServiceBusArmModels.isoDuration(stored);
    }

    private static Object maxMessageSize(Scope scope, Map<String, Object> sent) {
        if (sent.get("maxMessageSizeInKilobytes") instanceof Number size && size.longValue() > 0) {
            return size.longValue();
        }
        return PREMIUM.equals(scope.namespace().sku().get("name")) ? PREMIUM_MAX_MESSAGE_KB : STANDARD_MAX_MESSAGE_KB;
    }

    private static Map<String, Object> countDetails(long active, long deadLetter) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("activeMessageCount", active);
        details.put("deadLetterMessageCount", deadLetter);
        details.put("scheduledMessageCount", 0);
        details.put("transferMessageCount", 0);
        details.put("transferDeadLetterMessageCount", 0);
        return details;
    }

    private static void timestamps(Map<String, Object> properties, Instant createdAt, Instant updatedAt) {
        Instant created = createdAt == null ? Instant.EPOCH : createdAt;
        Instant updated = updatedAt == null ? created : updatedAt;
        properties.put("createdAt", created.toString());
        properties.put("updatedAt", updated.toString());
        properties.put("accessedAt", updated.toString());
    }

    /** A child resource as Azure answers one: no location, which only the namespace carries. */
    private static Map<String, Object> resource(String id, String name, String type, Map<String, Object> properties) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("name", name);
        body.put("type", type);
        body.put("properties", properties);
        return body;
    }

    private static void copyIfSent(Map<String, Object> sent, Map<String, Object> properties, String... keys) {
        for (String key : keys) {
            if (sent.get(key) != null) {
                properties.put(key, sent.get(key));
            }
        }
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /** The settings Azure fixes at creation, as sent; an absent one leaves the stored value alone. */
    private static ServiceBusEntityXml.FixedSettings fixed(Map<String, Object> sent) {
        return new ServiceBusEntityXml.FixedSettings(
                sent.containsKey("requiresSession") ? bool(sent, "requiresSession", false) : null,
                sent.containsKey("requiresDuplicateDetection") ? bool(sent, "requiresDuplicateDetection", false) : null);
    }

    private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof String text ? Boolean.parseBoolean(text) : fallback;
    }

    // ── Resource index, global names, reset ──────────────────────────────────

    @Override
    public boolean indexEnabled() {
        return config.services().serviceBus().enabled();
    }

    /** The index entries of the namespaces in {@code scope}; their children are deleted with them. */
    @Override
    public List<Map<String, Object>> indexEntries(ArmScope scope) {
        return namespaces.list(scope).stream().map(ArmResourceStore.Stored::value)
                .map(namespace -> ArmResources.indexEntry(namespace.id(), namespace.name(), NAMESPACE_TYPE,
                        namespace.location(), stringTags(namespace.tags())))
                .toList();
    }

    /** Every stored namespace owns {@code {name}.servicebus.windows.net}. */
    @Override
    public List<Claim> globalNames() {
        return namespaces.all().stream()
                .map(stored -> new Claim(CloudSuffix.SERVICEBUS, stored.id().name(), stored.id()))
                .toList();
    }

    @Override
    public boolean holds(CloudSuffix suffix, String name, ArmResourceId owner) {
        return suffix == CloudSuffix.SERVICEBUS
                && namespaces.get(owner).filter(stored -> name.equalsIgnoreCase(stored.id().name())).isPresent();
    }

    /** Forgets every ARM resource: {@code POST /_admin/reset}. The entities go with the handler's own reset. */
    @Override
    public void clear() {
        for (ArmResourceStore<?> store : List.of(namespaces, namespaceRules, queueRules, topicRules, queueProperties,
                topicProperties, subscriptionProperties, networkRuleSets)) {
            store.clear();
        }
    }

    // ── Request reading ──────────────────────────────────────────────────────

    /** The id of the namespace a request under {@code namespaces/{ns}} addresses. */
    private static ArmResourceId namespaceId(ArmResourceId id) {
        String name = id.names().isEmpty() ? "" : id.names().getFirst();
        return ArmResourceId.parse("/subscriptions/" + id.subscriptionId() + "/resourceGroups/" + id.resourceGroup()
                + "/providers/" + ServiceBusArmSurface.NAMESPACE + "/namespaces/" + name).orElseThrow();
    }

    /** The canonical location of the PUT: the one the gate's location rule read, else the body's, else the default. */
    private String location(AzureRequest req, Map<String, Object> body) {
        if (req.armCall() != null && req.armCall().normalizedLocation() != null) {
            return req.armCall().normalizedLocation();
        }
        String sent = ArmJson.string(body, "location", null);
        return sent == null ? defaults.defaultLocation(req.scope()) : CloudCatalog.normalizeLocation(cloud(req), sent);
    }

    private static Map<String, String> stringTags(Map<String, Object> tags) {
        Map<String, String> strings = new LinkedHashMap<>();
        if (tags != null) {
            tags.forEach((key, value) -> strings.put(key, String.valueOf(value)));
        }
        return strings;
    }

    private static String suffix(CloudProfile cloud, CloudSuffix suffix) {
        return cloud.suffix(suffix)
                .or(() -> CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD).suffix(suffix))
                .orElseThrow();
    }

    private static Optional<ArmScope> scope(AzureRequest req) {
        if (req.scope() == null || req.scope().subscriptionId() == null) {
            return Optional.empty();
        }
        return Optional.of(new ArmScope(req.scope().subscriptionId(), req.scope().resourceGroup()));
    }

    private static CloudProfile cloud(AzureRequest req) {
        return req.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : req.scope().cloud();
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static List<String> segments(String tail) {
        List<String> segments = new ArrayList<>();
        for (String segment : tail.split("/")) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private static String withoutQuery(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    // ── Refusals ─────────────────────────────────────────────────────────────

    /** A request body the contract refuses; answered as {@code 400 BadRequest}. */
    private static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message, null, false, false);
        }
    }

    /** A write refused by a check that reads the stored namespace, thrown from inside a store change. */
    private static final class Refused extends RuntimeException {
        private final transient Response response;

        Refused(Response response) {
            super(null, null, false, false);
            this.response = response;
        }

        Response response() {
            return response;
        }
    }

    /** Refuses a PUT that moves an existing namespace: ARM fixes a resource's location at creation. */
    private static void refuseLocationChange(Namespace namespace, String location) {
        if (!namespace.location().equalsIgnoreCase(location)) {
            throw new Refused(ArmErrors.error(400, "InvalidResourceLocation", "The resource '" + namespace.name()
                    + "' already exists in location '" + namespace.location() + "' in resource group '"
                    + ArmResourceId.parse(namespace.id()).map(ArmResourceId::resourceGroup).orElse("")
                    + "'. A resource with the same name cannot be created in location '" + location
                    + "'. Please select a new resource name."));
        }
    }

    // ── Responses ────────────────────────────────────────────────────────────

    private static Response ok(Object body) {
        return Response.ok(body).type("application/json").build();
    }

    private static Response notFound(ArmResourceId id, String type) {
        return ArmErrors.notFound("The Resource '" + ServiceBusArmSurface.NAMESPACE + "/" + type + "/" + id.name()
                + "' under resource group '" + id.resourceGroup()
                + "' was not found. For more details please go to https://aka.ms/ARMResourceNotFoundFix");
    }

    private static Response parentNotFound(ArmResourceId id, ArmResourceId namespaceId) {
        return ArmErrors.error(404, "ParentResourceNotFound", "Failed to perform the operation on resource(s) of"
                + " type '" + id.typePath() + "', because the parent resource '" + namespaceId.id()
                + "' could not be found.");
    }

    private static Response entityNotFound(String kind, String name, String parent) {
        return ArmErrors.notFound("The messaging entity " + kind + " '" + name + "' could not be found under '"
                + parent + "'.");
    }

    private static Response ruleNotFound(ArmResourceId id) {
        return ArmErrors.notFound("The authorization rule '" + id.name() + "' could not be found.");
    }

    private static Response unknownPath(List<String> tail) {
        return ArmErrors.notFound("Unknown Microsoft.ServiceBus path: " + String.join("/", tail));
    }

    private static Response invalid(String message) {
        return ArmErrors.error(400, "BadRequest", message);
    }

    private static Response methodNotAllowed() {
        return Response.status(405).build();
    }
}
