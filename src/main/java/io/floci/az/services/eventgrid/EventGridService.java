package io.floci.az.services.eventgrid;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmResourceStore.Stored;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.cloud.CloudDefaults;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.eventgrid.EventGridModels.DeadLetterDestination;
import io.floci.az.services.eventgrid.EventGridModels.DeadLetterIdentity;
import io.floci.az.services.eventgrid.EventGridModels.DeadLetterWithResourceIdentity;
import io.floci.az.services.eventgrid.EventGridModels.EventSubscription;
import io.floci.az.services.eventgrid.EventGridModels.Filter;
import io.floci.az.services.eventgrid.EventGridModels.RetryPolicy;
import io.floci.az.services.eventgrid.EventGridModels.Topic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * ARM (management-plane) handler for {@code Microsoft.EventGrid/topics}, the classic scoped
 * {@code eventSubscriptions} and {@code systemTopics}. All are kept in {@link ArmResourceStore}s over the
 * {@code eventgrid} backend, keyed by canonical id; the ARM resource JSON returned to clients for topics and event
 * subscriptions is projected from the stored {@link EventGridModels} records, and a system topic is stored as the
 * ARM document it answers with.
 */
@ApplicationScoped
public class EventGridService {

    private static final Logger LOG = Logger.getLogger(EventGridService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The key prefixes topics and event subscriptions were kept under before the stores. */
    private static final String LEGACY_TOPIC_PREFIX = "eg/topics/";
    private static final String LEGACY_SUB_PREFIX = "eg/subs/";
    private static final String TOPIC_TYPE = "Microsoft.EventGrid/topics";
    private static final String SUBSCRIPTION_TYPE = "Microsoft.EventGrid/eventSubscriptions";
    private static final String SYSTEM_TOPIC_TYPE = "Microsoft.EventGrid/systemTopics";
    private static final String ES_MARKER = "/providers/Microsoft.EventGrid/eventSubscriptions";
    private static final String EG_MARKER = "/providers/Microsoft.EventGrid/";
    private static final String ENDPOINT_TYPE_WEBHOOK = "WebHook";

    /**
     * Topics and event subscriptions share the {@code eventgrid} backend, each store owning its own type's canonical
     * keys. Before the stores they were kept under {@code eg/topics/sub/rg/name} (raw case) and
     * {@code eg/subs/{lowercased topic id}/name}; those entries are read as they are and move to their canonical key
     * on their next write.
     */
    private final ArmResourceStore<Topic> topics;
    private final ArmResourceStore<EventSubscription> subscriptions;
    private final ArmResourceStore<Map<String, Object>> systemTopics;

    /**
     * Held by a system topic PUT, which reads the stored topic to refuse a change of its {@code source} or
     * {@code topicType} and then writes, so a concurrent PUT cannot change either between the check and the write.
     */
    private final Object systemTopicWrites = new Object();
    private final EmulatorConfig config;
    private final EventGridDelivery delivery;
    private final CloudDefaults defaults;

    @Inject
    public EventGridService(StorageFactory factory, EmulatorConfig config, EventGridDelivery delivery,
                            CloudDefaults defaults) {
        this.topics = ArmResourceStore.create(factory, "eventgrid", TOPIC_TYPE, Topic.class, Topic::resourceId,
                key -> key.startsWith(LEGACY_TOPIC_PREFIX));
        this.subscriptions = ArmResourceStore.create(factory, "eventgrid", SUBSCRIPTION_TYPE, EventSubscription.class,
                EventSubscription::resourceId, key -> key.startsWith(LEGACY_SUB_PREFIX));
        this.systemTopics = ArmResourceStore.create(factory, "eventgrid", SYSTEM_TOPIC_TYPE, documentType(),
                document -> String.valueOf(document.get("id")), key -> false);
        this.config = config;
        this.delivery = delivery;
        this.defaults = defaults;
    }

    // ── ARM dispatch ──────────────────────────────────────────────────────────

    public Response handleArm(AzureRequest req, String path, String method, String sub) {
        int esIdx = ArmPaths.indexOfIgnoreCase(path, ES_MARKER);
        if (esIdx >= 0) {
            String scope = normalizeId(path.substring(0, esIdx));
            String rest = stripQuery(path.substring(esIdx + ES_MARKER.length()));
            return handleEventSubscriptions(req, method, scope, rest);
        }

        int idx = ArmPaths.indexOfIgnoreCase(path, EG_MARKER);
        if (idx < 0) {
            return notFound(path);
        }
        String[] seg = split(stripQuery(path.substring(idx + EG_MARKER.length())));
        if (seg.length > 0 && "systemTopics".equalsIgnoreCase(seg[0])) {
            return handleSystemTopics(req, method, sub, extractSegment(path, "resourceGroups"), seg);
        }
        if (seg.length == 0 || !"topics".equals(seg[0])) {
            return notFound(path);
        }

        String rg = extractSegment(path, "resourceGroups");

        if (seg.length == 1) {
            if (!"GET".equals(method)) {
                return Response.status(405).build();
            }
            return Response.ok(Map.of("value", listTopics(sub, rg))).build();
        }

        String topicName = seg[1];
        if (rg == null) {
            return notFound(path);
        }
        if (seg.length == 2) {
            return switch (method) {
                case "PUT" -> createOrUpdateTopic(req, sub, rg, topicName);
                case "GET" -> getTopic(sub, rg, topicName);
                case "DELETE" -> deleteTopic(sub, rg, topicName);
                default -> Response.status(405).build();
            };
        }
        if (seg.length == 3 && "POST".equals(method)) {
            return switch (seg[2]) {
                case "listKeys" -> listKeys(sub, rg, topicName);
                case "regenerateKey" -> regenerateKey(req, sub, rg, topicName);
                default -> notFound(path);
            };
        }
        return notFound(path);
    }

    // ── Topics ─────────────────────────────────────────────────────────────────

    private Response createOrUpdateTopic(AzureRequest req, String sub, String rg, String name) {
        Map<String, Object> body = parseBody(req);
        Map<String, Object> properties = cast(body.get("properties"));
        String inputSchema = stringOr(properties.get("inputSchema"), EventGridModels.SCHEMA_EVENT_GRID);
        String location = stringOr(body.get("location"), config.services().eventGrid().defaultRegion()
                .orElseGet(() -> defaults.defaultLocation(req.scope())));

        Topic topic = topics.createOrUpdate(topicId(sub, rg, name), existing -> existing
                .map(t -> new Topic(t.subscriptionId(), t.resourceGroup(), t.name(), location, inputSchema,
                        t.key1(), t.key2()))
                .orElseGet(() -> new Topic(sub, rg, name, location, inputSchema, generateKey(), generateKey())))
                .stored().value();
        LOG.infov("ARM: created Event Grid topic {0} (schema={1})", name, inputSchema);
        // Topics_CreateOrUpdate declares only 201 (2025-02-15).
        return Response.status(201).entity(topicJson(topic)).build();
    }

    private Response getTopic(String sub, String rg, String name) {
        return readTopic(sub, rg, name)
                .<Response>map(t -> Response.ok(topicJson(t)).build())
                .orElseGet(() -> notFound("topics/" + name));
    }

    private Response deleteTopic(String sub, String rg, String name) {
        topics.delete(topicId(sub, rg, name)).ifPresent(t -> subscriptionsForTopic(t.value().resourceId())
                .forEach(s -> subscriptions.delete(eventSubscriptionId(s.topicResourceId(), s.name()))));
        // Topics_Delete declares 202 and 204 (2025-02-15); the delete is synchronous, so 204.
        return Response.noContent().build();
    }

    private Response listKeys(String sub, String rg, String name) {
        return readTopic(sub, rg, name)
                .<Response>map(t -> Response.ok(Map.of("key1", t.key1(), "key2", t.key2())).build())
                .orElseGet(() -> notFound("topics/" + name));
    }

    private Response regenerateKey(AzureRequest req, String sub, String rg, String name) {
        if (readTopic(sub, rg, name).isEmpty()) {
            return notFound("topics/" + name);
        }
        String keyName = stringOr(parseBody(req).get("keyName"), "key1");
        Optional<Stored<Topic>> updated = topics.update(topicId(sub, rg, name), t -> "key2".equalsIgnoreCase(keyName)
                ? new Topic(t.subscriptionId(), t.resourceGroup(), t.name(), t.location(), t.inputSchema(),
                        t.key1(), generateKey())
                : new Topic(t.subscriptionId(), t.resourceGroup(), t.name(), t.location(), t.inputSchema(),
                        generateKey(), t.key2()));
        return updated
                .<Response>map(s -> Response.ok(Map.of("key1", s.value().key1(), "key2", s.value().key2())).build())
                .orElseGet(() -> notFound("topics/" + name));
    }

    // ── System topics ──────────────────────────────────────────────────────────

    /**
     * {@code Microsoft.EventGrid/systemTopics} (2025-02-15): the topic Azure keeps for an Azure resource's own events.
     * PUT creates (201) or updates (200) it, GET reads it, PATCH replaces its tags or identity
     * ({@code SystemTopics_Update}), DELETE removes it (200, or 204 when there is none), and a resource group or
     * subscription lists them. A system topic has no publish endpoint, and floci-az raises no events from the
     * resources it emulates, so nothing is delivered through one; its event subscriptions are not served yet.
     */
    private Response handleSystemTopics(AzureRequest req, String method, String sub, String rg, String[] seg) {
        if (seg.length == 1) {
            if (!"GET".equals(method)) {
                return Response.status(405).build();
            }
            List<Map<String, Object>> value = systemTopics.list(new ArmScope(sub, rg)).stream()
                    .map(Stored::value).toList();
            return Response.ok(Map.of("value", value)).build();
        }
        if (rg == null || seg.length > 2) {
            return notFound("systemTopics/" + String.join("/", List.of(seg).subList(1, seg.length)));
        }
        String name = seg[1];
        ArmResourceId id = systemTopicId(sub, rg, name);
        return switch (method) {
            case "PUT" -> createOrUpdateSystemTopic(req, id);
            case "GET" -> systemTopics.get(id)
                    .<Response>map(t -> Response.ok(t.value()).build())
                    .orElseGet(() -> notFound("systemTopics/" + name));
            case "PATCH" -> updateSystemTopic(req, id);
            case "DELETE" -> systemTopics.delete(id).isPresent()
                    ? Response.ok().build()
                    : Response.noContent().build();
            default -> Response.status(405).build();
        };
    }

    /**
     * A system topic's {@code source} (the resource whose events it carries) and {@code topicType} are required to
     * create it and cannot change afterwards, as in Azure. {@code metricResourceId} is generated on create and kept;
     * a system-assigned identity gets a {@code principalId}, kept by every later PUT, so role assignments made to it
     * stay valid.
     */
    private Response createOrUpdateSystemTopic(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = parseBody(req);
        Map<String, Object> properties = cast(body.get("properties"));
        String source = stringOr(properties.get("source"), null);
        String topicType = stringOr(properties.get("topicType"), null);
        String location = stringOr(body.get("location"), config.services().eventGrid().defaultRegion()
                .orElseGet(() -> defaults.defaultLocation(req.scope())));

        Map<String, Object> topic;
        boolean created;
        synchronized (systemTopicWrites) {
            Map<String, Object> existing = systemTopics.get(id).map(Stored::value).orElse(null);
            created = existing == null;
            Map<String, Object> kept = created ? Map.of() : cast(existing.get("properties"));
            if (created && (source == null || topicType == null)) {
                return badRequest("System topic '" + id.name() + "' requires properties.source and "
                        + "properties.topicType");
            }
            if (!created && source != null && !source.equalsIgnoreCase(stringOr(kept.get("source"), ""))) {
                return badRequest("The source of system topic '" + id.name() + "' cannot be changed");
            }
            if (!created && topicType != null && !topicType.equalsIgnoreCase(stringOr(kept.get("topicType"), ""))) {
                return badRequest("The topicType of system topic '" + id.name() + "' cannot be changed");
            }
            topic = systemTopics.createOrUpdate(id, stored -> systemTopicDocument(req, id, body, location,
                    stored.orElse(null), source, topicType)).stored().value();
        }
        LOG.infov("ARM: {0} Event Grid system topic {1} (source={2})", created ? "created" : "updated",
                topic.get("name"), cast(topic.get("properties")).get("source"));
        return Response.status(created ? 201 : 200).entity(topic).build();
    }

    private Map<String, Object> systemTopicDocument(AzureRequest req, ArmResourceId id, Map<String, Object> body,
                                                    String location, Map<String, Object> existing, String source,
                                                    String topicType) {
        Map<String, Object> kept = existing == null ? Map.of() : cast(existing.get("properties"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("provisioningState", "Succeeded");
        properties.put("source", stringOr(kept.get("source"), source));
        properties.put("topicType", stringOr(kept.get("topicType"), topicType));
        properties.put("metricResourceId", stringOr(kept.get("metricResourceId"), UUID.randomUUID().toString()));

        Map<String, Object> topic = new LinkedHashMap<>();
        topic.put("id", existing == null ? id.id() : String.valueOf(existing.get("id")));
        topic.put("name", existing == null ? id.name() : String.valueOf(existing.get("name")));
        topic.put("type", SYSTEM_TOPIC_TYPE);
        topic.put("location", location);
        if (body.get("tags") instanceof Map<?, ?> tags) {
            topic.put("tags", new LinkedHashMap<>(cast(tags)));
        }
        Map<String, Object> identity = systemTopicIdentity(req, body.get("identity"),
                existing == null ? null : identityOf(existing));
        if (identity != null) {
            topic.put("identity", identity);
        }
        topic.put("properties", properties);
        return topic;
    }

    /** {@code SystemTopics_Update}: the tags and identity it sends replace the stored ones; the rest is kept. */
    private Response updateSystemTopic(AzureRequest req, ArmResourceId id) {
        Map<String, Object> body = parseBody(req);
        Optional<Stored<Map<String, Object>>> updated = systemTopics.update(id, existing -> {
            Map<String, Object> topic = new LinkedHashMap<>(existing);
            if (body.get("tags") instanceof Map<?, ?> tags) {
                topic.put("tags", new LinkedHashMap<>(cast(tags)));
            }
            if (body.containsKey("identity")) {
                Map<String, Object> identity = systemTopicIdentity(req, body.get("identity"), identityOf(existing));
                if (identity == null) {
                    topic.remove("identity");
                } else {
                    topic.put("identity", identity);
                }
            }
            return topic;
        });
        return updated
                .<Response>map(t -> Response.ok(t.value()).build())
                .orElseGet(() -> notFound("systemTopics/" + id.name()));
    }

    /**
     * The identity to store: null for none, else the sent one, with a {@code principalId} and {@code tenantId} for a
     * system-assigned identity, as Azure creates a service principal for it. A later write keeps the principal the
     * topic was given.
     */
    private static Map<String, Object> systemTopicIdentity(AzureRequest req, Object sent,
                                                           Map<String, Object> existing) {
        if (!(sent instanceof Map<?, ?> requested)) {
            return null;
        }
        Map<String, Object> body = cast(requested);
        String type = stringOr(body.get("type"), "None");
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
        } else {
            identity.remove("principalId");
            identity.remove("tenantId");
        }
        return identity;
    }

    private static Map<String, Object> identityOf(Map<String, Object> document) {
        return document.get("identity") instanceof Map<?, ?> identity ? cast(identity) : null;
    }

    // ── Event subscriptions ─────────────────────────────────────────────────────

    private Response handleEventSubscriptions(AzureRequest req, String method, String topicResourceId, String rest) {
        if (rest.isBlank() || "/".equals(rest)) {
            if (!"GET".equals(method)) {
                return Response.status(405).build();
            }
            List<Map<String, Object>> items = subscriptionsForTopic(topicResourceId).stream()
                    .map(this::subscriptionJson).toList();
            return Response.ok(Map.of("value", items)).build();
        }
        String[] seg = split(rest);
        String name = seg[0];
        if (seg.length == 2 && "getFullUrl".equals(seg[1]) && "POST".equals(method)) {
            return readSubscription(topicResourceId, name)
                    .<Response>map(s -> Response.ok(Map.of("endpointUrl", s.endpointUrl())).build())
                    .orElseGet(() -> notFound("eventSubscriptions/" + name));
        }
        return switch (method) {
            case "PUT" -> createOrUpdateSubscription(req, topicResourceId, name);
            case "GET" -> readSubscription(topicResourceId, name)
                    .<Response>map(s -> Response.ok(subscriptionJson(s)).build())
                    .orElseGet(() -> notFound("eventSubscriptions/" + name));
            case "DELETE" -> {
                subscriptions.delete(eventSubscriptionId(topicResourceId, name));
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    private Response createOrUpdateSubscription(AzureRequest req, String topicResourceId, String name) {
        Map<String, Object> body = parseBody(req);
        Map<String, Object> properties = cast(body.get("properties"));

        Map<String, Object> destination = cast(properties.get("destination"));
        String endpointType = stringOr(destination.get("endpointType"), ENDPOINT_TYPE_WEBHOOK);
        if (!ENDPOINT_TYPE_WEBHOOK.equalsIgnoreCase(endpointType)) {
            return badRequest("Event subscription '" + name + "' has destination endpointType '" + endpointType
                    + "', which floci-az does not deliver to. Supported endpoint types: " + ENDPOINT_TYPE_WEBHOOK);
        }
        Map<String, Object> destProps = cast(destination.get("properties"));
        String endpointUrl = stringOr(destProps.get("endpointUrl"), null);
        if (endpointUrl == null) {
            return badRequest("Event subscription '" + name + "' requires destination.properties.endpointUrl");
        }

        String schema = stringOr(properties.get("eventDeliverySchema"), EventGridModels.SCHEMA_EVENT_GRID);
        Filter filter = parseFilter(cast(properties.get("filter")));
        RetryPolicy retry = parseRetryPolicy(cast(properties.get("retryPolicy")));
        DeadLetterDestination deadLetter = parseDeadLetter(cast(properties.get("deadLetterDestination")));
        DeadLetterWithResourceIdentity deadLetterWithIdentity =
                parseDeadLetterWithIdentity(cast(properties.get("deadLetterWithResourceIdentity")));

        EventSubscription es = subscriptions.createOrUpdate(eventSubscriptionId(topicResourceId, name), existing -> {
            String scope = existing.map(EventSubscription::topicResourceId).orElse(topicResourceId);
            String storedName = existing.map(EventSubscription::name).orElse(name);
            return new EventSubscription(storedName, scope, endpointUrl, schema, filter, retry, deadLetter,
                    deadLetterWithIdentity);
        }).stored().value();

        boolean validated = delivery.validate(es, es.topicResourceId());
        LOG.infov("ARM: created Event Grid subscription {0} → {1} (validated={2})",
                name, endpointUrl, validated);
        // EventSubscriptions_CreateOrUpdate declares only 201, for create and update alike (2025-02-15).
        return Response.status(201).entity(subscriptionJson(es)).build();
    }

    private Filter parseFilter(Map<String, Object> raw) {
        if (raw.isEmpty()) {
            return Filter.empty();
        }
        return new Filter(
                stringOr(raw.get("subjectBeginsWith"), null),
                stringOr(raw.get("subjectEndsWith"), null),
                stringList(raw.get("includedEventTypes")),
                Boolean.parseBoolean(String.valueOf(raw.getOrDefault("isSubjectCaseSensitive", false))));
    }

    private RetryPolicy parseRetryPolicy(Map<String, Object> raw) {
        int maxAttempts = intOr(raw.get("maxDeliveryAttempts"), config.services().eventGrid().maxDeliveryAttempts());
        int ttl = intOr(raw.get("eventTimeToLiveInMinutes"), 1440);
        return new RetryPolicy(maxAttempts, ttl);
    }

    private DeadLetterDestination parseDeadLetter(Map<String, Object> raw) {
        if (raw.isEmpty()) {
            return null;
        }
        Map<String, Object> props = cast(raw.get("properties"));
        return new DeadLetterDestination(
                stringOr(raw.get("endpointType"), null),
                stringOr(props.get("resourceId"), null),
                stringOr(props.get("blobContainerName"), null));
    }

    private DeadLetterWithResourceIdentity parseDeadLetterWithIdentity(Map<String, Object> raw) {
        if (raw.isEmpty()) {
            return null;
        }
        Map<String, Object> identity = cast(raw.get("identity"));
        DeadLetterIdentity parsedIdentity = identity.isEmpty() ? null : new DeadLetterIdentity(
                stringOr(identity.get("type"), null),
                stringOr(identity.get("userAssignedIdentity"), null));
        return new DeadLetterWithResourceIdentity(parsedIdentity,
                parseDeadLetter(cast(raw.get("deadLetterDestination"))));
    }

    // ── Lookups used by the data plane ──────────────────────────────────────────

    /**
     * The topic a {@code {topic}-eventgrid} data-plane request names. The data plane is keyed by name alone, so the
     * first topic of that name in any subscription or resource group answers, as before the stores.
     */
    public Optional<Topic> findTopicByName(String name) {
        return topics.all().stream()
                .map(Stored::value)
                .filter(t -> t.name().equals(name))
                .findFirst();
    }

    /**
     * Index entries for the topics and system topics in a subscription, or in one of its resource groups when
     * {@code rg} is non-null. Event subscriptions are extension resources: they are not listed, and a topic's delete takes
     * them with it.
     */
    public List<Map<String, Object>> topicIndexEntries(String sub, String rg) {
        Stream<Map<String, Object>> customTopics = topics.list(new ArmScope(sub, rg)).stream()
                .map(Stored::value)
                .map(t -> ArmResources.indexEntry(t.resourceId(), t.name(), TOPIC_TYPE, t.location(), null));
        Stream<Map<String, Object>> systemTopicEntries = systemTopics.list(new ArmScope(sub, rg)).stream()
                .map(Stored::value)
                .map(ArmResources::indexEntry);
        return Stream.concat(customTopics, systemTopicEntries).toList();
    }

    /**
     * The event subscriptions scoped to {@code topicResourceId}, compared as ARM ids (case-insensitively). An event
     * subscription's id is {@code {topicResourceId}/providers/Microsoft.EventGrid/eventSubscriptions/{name}}, so only
     * the entries under the topic are read.
     */
    public List<EventSubscription> subscriptionsForTopic(String topicResourceId) {
        List<Stored<EventSubscription>> candidates = ArmResourceId.parse(topicResourceId)
                .map(subscriptions::under)
                .orElseGet(subscriptions::all);
        return candidates.stream()
                .map(Stored::value)
                .filter(s -> s.topicResourceId().equalsIgnoreCase(topicResourceId))
                .toList();
    }

    public void clear() {
        topics.clear();
        subscriptions.clear();
        systemTopics.clear();
    }

    // ── JSON projection ─────────────────────────────────────────────────────────

    private Map<String, Object> topicJson(Topic t) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("provisioningState", "Succeeded");
        properties.put("endpoint", topicEndpoint(t.name()));
        properties.put("inputSchema", t.inputSchema());
        properties.put("publicNetworkAccess", "Enabled");

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", t.resourceId());
        resource.put("name", t.name());
        resource.put("type", "Microsoft.EventGrid/topics");
        resource.put("location", t.location());
        resource.put("properties", properties);
        return resource;
    }

    private Map<String, Object> subscriptionJson(EventSubscription s) {
        Map<String, Object> destProps = new LinkedHashMap<>();
        destProps.put("endpointUrl", s.endpointUrl());
        Map<String, Object> destination = new LinkedHashMap<>();
        destination.put("endpointType", ENDPOINT_TYPE_WEBHOOK);
        destination.put("properties", destProps);

        Map<String, Object> filter = new LinkedHashMap<>();
        // An unset subject filter reads back as "", as Azure answers it: azurerm treats only two empty strings
        // as "no subject_filter", so nulls would leave a block in state that the config never declared.
        filter.put("subjectBeginsWith", emptyIfNull(s.filter().subjectBeginsWith()));
        filter.put("subjectEndsWith", emptyIfNull(s.filter().subjectEndsWith()));
        filter.put("includedEventTypes",
                s.filter().includedEventTypes().isEmpty() ? null : s.filter().includedEventTypes());
        filter.put("isSubjectCaseSensitive", s.filter().isSubjectCaseSensitive());

        Map<String, Object> retry = new LinkedHashMap<>();
        retry.put("maxDeliveryAttempts", s.retryPolicy().maxDeliveryAttempts());
        retry.put("eventTimeToLiveInMinutes", s.retryPolicy().eventTimeToLiveInMinutes());

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("provisioningState", "Succeeded");
        properties.put("topic", s.topicResourceId());
        properties.put("destination", destination);
        properties.put("filter", filter);
        properties.put("retryPolicy", retry);
        properties.put("eventDeliverySchema", s.eventDeliverySchema());
        properties.put("labels", List.of());
        if (s.deadLetterDestination() != null) {
            properties.put("deadLetterDestination", deadLetterJson(s.deadLetterDestination()));
        }
        if (s.deadLetterWithResourceIdentity() != null) {
            properties.put("deadLetterWithResourceIdentity",
                    deadLetterWithIdentityJson(s.deadLetterWithResourceIdentity()));
        }

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", s.resourceId());
        resource.put("name", s.name());
        resource.put("type", "Microsoft.EventGrid/eventSubscriptions");
        resource.put("properties", properties);
        return resource;
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, Object> deadLetterJson(DeadLetterDestination d) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("resourceId", d.resourceId());
        props.put("blobContainerName", d.blobContainerName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpointType", d.endpointType());
        out.put("properties", props);
        return out;
    }

    private static Map<String, Object> deadLetterWithIdentityJson(DeadLetterWithResourceIdentity d) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (d.identity() != null) {
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("type", d.identity().type());
            if (d.identity().userAssignedIdentity() != null) {
                identity.put("userAssignedIdentity", d.identity().userAssignedIdentity());
            }
            out.put("identity", identity);
        }
        if (d.destination() != null) {
            out.put("deadLetterDestination", deadLetterJson(d.destination()));
        }
        return out;
    }

    private String topicEndpoint(String name) {
        return config.effectiveBaseUrl().replaceAll("/+$", "") + "/" + name + "-eventgrid/api/events";
    }

    /** The topics in {@code sub}, or in its resource group {@code rg} when that is non-null. */
    private List<Map<String, Object>> listTopics(String sub, String rg) {
        return topics.list(new ArmScope(sub, rg)).stream()
                .map(Stored::value)
                .map(this::topicJson).toList();
    }

    // ── Store helpers ──────────────────────────────────────────────────────────────

    private Optional<Topic> readTopic(String sub, String rg, String name) {
        return topics.get(topicId(sub, rg, name)).map(Stored::value);
    }

    private Optional<EventSubscription> readSubscription(String topicResourceId, String name) {
        return subscriptions.get(eventSubscriptionId(topicResourceId, name)).map(Stored::value);
    }

    private static ArmResourceId topicId(String sub, String rg, String name) {
        return armId(new Topic(sub, rg, name, null, null, null, null).resourceId());
    }

    private static ArmResourceId systemTopicId(String sub, String rg, String name) {
        return armId("/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/" + SYSTEM_TOPIC_TYPE + "/" + name);
    }

    private static ArmResourceId eventSubscriptionId(String topicResourceId, String name) {
        return armId(topicResourceId + "/providers/" + SUBSCRIPTION_TYPE + "/" + name);
    }

    private static ArmResourceId armId(String id) {
        return ArmResourceId.parse(id)
                .orElseThrow(() -> new IllegalArgumentException("not an ARM resource id: " + id));
    }

    // ── Generic parsing helpers ──────────────────────────────────────────────────

    private Map<String, Object> parseBody(AzureRequest req) {
        return ArmJson.parseBodyStrict(req);
    }

    private static Map<String, Object> cast(Object value) {
        return ArmJson.cast(value);
    }

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> documentType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private static String stringOr(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static int intOr(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    private static String generateKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String normalizeId(String id) {
        return id.startsWith("/") ? id : "/" + id;
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    private static String[] split(String tail) {
        String clean = tail.replaceAll("^/+", "").replaceAll("/+$", "");
        return clean.isBlank() ? new String[0] : clean.split("/", -1);
    }

    private static String extractSegment(String path, String marker) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (marker.equalsIgnoreCase(parts[i])) {
                return parts[i + 1];
            }
        }
        return null;
    }

    private static Response notFound(String resource) {
        return ArmErrors.notFound("Resource not found: " + resource);
    }

    private static Response badRequest(String message) {
        return ArmErrors.error(400, "InvalidRequest", message);
    }
}
