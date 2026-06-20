package io.floci.az.services.eventgrid;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.eventgrid.EventGridModels.EventSubscription;
import io.floci.az.services.eventgrid.EventGridModels.Filter;
import io.floci.az.services.eventgrid.EventGridModels.RetryPolicy;
import io.floci.az.services.eventgrid.EventGridModels.Topic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ARM (management-plane) handler for {@code Microsoft.EventGrid/topics} and the classic scoped
 * {@code eventSubscriptions}. State is persisted via {@link StorageBackend}; the ARM resource JSON
 * returned to clients is projected from the stored {@link EventGridModels} records.
 */
@ApplicationScoped
public class EventGridService {

    private static final Logger LOG = Logger.getLogger(EventGridService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String TOPIC_PREFIX = "eg/topics/";
    private static final String SUB_PREFIX = "eg/subs/";
    private static final String ES_MARKER = "/providers/Microsoft.EventGrid/eventSubscriptions";
    private static final String EG_MARKER = "/providers/Microsoft.EventGrid/";

    private final StorageBackend<String, StoredObject> store;
    private final EmulatorConfig config;
    private final EventGridDelivery delivery;

    @Inject
    public EventGridService(StorageFactory factory, EmulatorConfig config, EventGridDelivery delivery) {
        this.store = factory.create("eventgrid");
        this.config = config;
        this.delivery = delivery;
    }

    // ── ARM dispatch ──────────────────────────────────────────────────────────

    public Response handleArm(AzureRequest req, String path, String method) {
        int esIdx = path.indexOf(ES_MARKER);
        if (esIdx >= 0) {
            String scope = normalizeId(path.substring(0, esIdx));
            String rest = stripQuery(path.substring(esIdx + ES_MARKER.length()));
            return handleEventSubscriptions(req, method, scope, rest);
        }

        int idx = path.indexOf(EG_MARKER);
        if (idx < 0) {
            return notFound(path);
        }
        String[] seg = split(stripQuery(path.substring(idx + EG_MARKER.length())));
        if (seg.length == 0 || !"topics".equals(seg[0])) {
            return notFound(path);
        }

        String sub = extractSegment(path, "subscriptions");
        String rg = extractSegment(path, "resourceGroups");

        if (seg.length == 1) {
            if (!"GET".equals(method)) {
                return Response.status(405).build();
            }
            return rg == null ? Response.ok(Map.of("value", listTopicsBySubscription(sub))).build()
                    : Response.ok(Map.of("value", listTopicsByResourceGroup(sub, rg))).build();
        }

        String topicName = seg[1];
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
        String location = stringOr(body.get("location"), config.services().eventGrid().defaultRegion());

        Topic existing = readTopic(sub, rg, name).orElse(null);
        String key1 = existing != null ? existing.key1() : generateKey();
        String key2 = existing != null ? existing.key2() : generateKey();

        Topic topic = new Topic(sub, rg, name, location, inputSchema, key1, key2);
        writeTopic(topic);
        LOG.infov("ARM: created Event Grid topic {0} (schema={1})", name, inputSchema);
        return Response.ok(topicJson(topic)).build();
    }

    private Response getTopic(String sub, String rg, String name) {
        return readTopic(sub, rg, name)
                .<Response>map(t -> Response.ok(topicJson(t)).build())
                .orElseGet(() -> notFound("topics/" + name));
    }

    private Response deleteTopic(String sub, String rg, String name) {
        Optional<Topic> topic = readTopic(sub, rg, name);
        store.delete(topicKey(sub, rg, name));
        topic.ifPresent(t -> {
            String prefix = SUB_PREFIX + t.resourceId().toLowerCase() + "/";
            store.keys().stream().filter(k -> k.startsWith(prefix)).toList()
                    .forEach(store::delete);
        });
        return Response.ok().build();
    }

    private Response listKeys(String sub, String rg, String name) {
        return readTopic(sub, rg, name)
                .<Response>map(t -> Response.ok(Map.of("key1", t.key1(), "key2", t.key2())).build())
                .orElseGet(() -> notFound("topics/" + name));
    }

    private Response regenerateKey(AzureRequest req, String sub, String rg, String name) {
        Optional<Topic> existing = readTopic(sub, rg, name);
        if (existing.isEmpty()) {
            return notFound("topics/" + name);
        }
        Topic t = existing.get();
        String keyName = stringOr(parseBody(req).get("keyName"), "key1");
        Topic updated = "key2".equalsIgnoreCase(keyName)
                ? new Topic(sub, rg, name, t.location(), t.inputSchema(), t.key1(), generateKey())
                : new Topic(sub, rg, name, t.location(), t.inputSchema(), generateKey(), t.key2());
        writeTopic(updated);
        return Response.ok(Map.of("key1", updated.key1(), "key2", updated.key2())).build();
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
                store.delete(subKey(topicResourceId, name));
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    private Response createOrUpdateSubscription(AzureRequest req, String topicResourceId, String name) {
        Map<String, Object> body = parseBody(req);
        Map<String, Object> properties = cast(body.get("properties"));

        Map<String, Object> destination = cast(properties.get("destination"));
        Map<String, Object> destProps = cast(destination.get("properties"));
        String endpointUrl = stringOr(destProps.get("endpointUrl"), null);
        if (endpointUrl == null) {
            return badRequest("Event subscription '" + name + "' requires destination.properties.endpointUrl");
        }

        String schema = stringOr(properties.get("eventDeliverySchema"), EventGridModels.SCHEMA_EVENT_GRID);
        Filter filter = parseFilter(cast(properties.get("filter")));
        RetryPolicy retry = parseRetryPolicy(cast(properties.get("retryPolicy")));
        String deadLetter = parseDeadLetter(cast(properties.get("deadLetterDestination")));

        EventSubscription es = new EventSubscription(name, topicResourceId, endpointUrl,
                schema, filter, retry, deadLetter);
        writeSubscription(es);

        boolean validated = delivery.validate(es, topicResourceId);
        LOG.infov("ARM: created Event Grid subscription {0} → {1} (validated={2})",
                name, endpointUrl, validated);
        return Response.ok(subscriptionJson(es)).build();
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

    private String parseDeadLetter(Map<String, Object> raw) {
        Map<String, Object> props = cast(raw.get("properties"));
        return stringOr(props.get("endpointUrl"), null);
    }

    // ── Lookups used by the data plane ──────────────────────────────────────────

    public Optional<Topic> findTopicByName(String name) {
        return store.scan(k -> k.startsWith(TOPIC_PREFIX)).stream()
                .map(this::toTopicFrom)
                .filter(t -> t != null && t.name().equals(name))
                .findFirst();
    }

    public List<EventSubscription> subscriptionsForTopic(String topicResourceId) {
        String prefix = SUB_PREFIX + topicResourceId.toLowerCase() + "/";
        return store.scan(k -> k.startsWith(prefix)).stream()
                .map(this::toSubscriptionFrom)
                .filter(s -> s != null)
                .toList();
    }

    public void clearAll() {
        store.clear();
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
        destination.put("endpointType", "WebHook");
        destination.put("properties", destProps);

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("subjectBeginsWith", s.filter().subjectBeginsWith());
        filter.put("subjectEndsWith", s.filter().subjectEndsWith());
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

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", s.resourceId());
        resource.put("name", s.name());
        resource.put("type", "Microsoft.EventGrid/eventSubscriptions");
        resource.put("properties", properties);
        return resource;
    }

    private String topicEndpoint(String name) {
        return config.effectiveBaseUrl().replaceAll("/+$", "") + "/" + name + "-eventgrid/api/events";
    }

    private List<Map<String, Object>> listTopicsByResourceGroup(String sub, String rg) {
        return store.scan(k -> k.startsWith(topicRgPrefix(sub, rg))).stream()
                .map(this::toTopicFrom).filter(t -> t != null)
                .map(this::topicJson).toList();
    }

    private List<Map<String, Object>> listTopicsBySubscription(String sub) {
        return store.scan(k -> k.startsWith(TOPIC_PREFIX + sub + "/")).stream()
                .map(this::toTopicFrom).filter(t -> t != null)
                .map(this::topicJson).toList();
    }

    // ── Persistence helpers ──────────────────────────────────────────────────────

    private Optional<Topic> readTopic(String sub, String rg, String name) {
        return store.get(topicKey(sub, rg, name)).map(this::toTopicFrom);
    }

    private void writeTopic(Topic t) {
        store.put(topicKey(t.subscriptionId(), t.resourceGroup(), t.name()), serialize(t));
    }

    private Optional<EventSubscription> readSubscription(String topicResourceId, String name) {
        return store.get(subKey(topicResourceId, name)).map(this::toSubscriptionFrom);
    }

    private void writeSubscription(EventSubscription s) {
        store.put(subKey(s.topicResourceId(), s.name()), serialize(s));
    }

    private Topic toTopicFrom(StoredObject obj) {
        return deserialize(obj, Topic.class);
    }

    private EventSubscription toSubscriptionFrom(StoredObject obj) {
        return deserialize(obj, EventSubscription.class);
    }

    private <T> StoredObject serialize(T value) {
        try {
            return new StoredObject("eg", MAPPER.writeValueAsBytes(value), Map.of(), Instant.now(), "eg");
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Event Grid model", e);
        }
    }

    private <T> T deserialize(StoredObject obj, Class<T> type) {
        try {
            return MAPPER.readValue(obj.data(), type);
        } catch (Exception e) {
            LOG.warnv("Failed to deserialize Event Grid {0}: {1}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private static String topicKey(String sub, String rg, String name) {
        return topicRgPrefix(sub, rg) + name;
    }

    private static String topicRgPrefix(String sub, String rg) {
        return TOPIC_PREFIX + sub + "/" + rg + "/";
    }

    private static String subKey(String topicResourceId, String name) {
        return SUB_PREFIX + topicResourceId.toLowerCase() + "/" + name;
    }

    // ── Generic parsing helpers ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(AzureRequest req) {
        try {
            if (req.bodyStream() == null || req.bodyStream().available() == 0) {
                return Map.of();
            }
            return MAPPER.readValue(req.bodyStream(), Map.class);
        } catch (IOException e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
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
        return Response.status(404).entity(Map.of("error", Map.of(
                "code", "ResourceNotFound",
                "message", "Resource not found: " + resource))).build();
    }

    private static Response badRequest(String message) {
        return Response.status(400).entity(Map.of("error", Map.of(
                "code", "InvalidRequest",
                "message", message))).build();
    }
}
