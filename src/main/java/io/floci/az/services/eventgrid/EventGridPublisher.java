package io.floci.az.services.eventgrid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureRequest;
import io.floci.az.services.eventgrid.EventGridModels.EventSubscription;
import io.floci.az.services.eventgrid.EventGridModels.Filter;
import io.floci.az.services.eventgrid.EventGridModels.Topic;
import io.floci.az.core.arm.ArmErrors;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Data-plane publish endpoint ({@code POST /api/events}). Parses incoming events (Event Grid or
 * CloudEvents 1.0 schema), matches them against each subscription's filter, renders them into the
 * subscription's delivery schema, and hands them to {@link EventGridDelivery} for async fan-out.
 */
@ApplicationScoped
public class EventGridPublisher {

    private static final Logger LOG = Logger.getLogger(EventGridPublisher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EventGridService service;
    private final EventGridDelivery delivery;

    @Inject
    public EventGridPublisher(EventGridService service, EventGridDelivery delivery) {
        this.service = service;
        this.delivery = delivery;
    }

    /** Internal canonical view of an incoming event, schema-agnostic. */
    private record CanonEvent(String id, String subject, String eventType,
                              String eventTime, JsonNode data, String dataVersion) {
    }

    public Response publish(AzureRequest req, String topicName) {
        Optional<Topic> topic = service.findTopicByName(topicName);
        if (topic.isEmpty()) {
            return error(404, "ResourceNotFound", "Topic '" + topicName + "' was not found.");
        }

        List<JsonNode> events;
        try {
            JsonNode root = MAPPER.readTree(req.bodyStream());
            if (root == null || root.isNull()) {
                return error(400, "BadRequest", "Request body is empty.");
            }
            events = root.isArray() ? toList(root) : List.of(root);
        } catch (Exception e) {
            return error(400, "BadRequest", "Could not parse request body: " + e.getMessage());
        }

        Topic t = topic.get();
        String topicResourceId = t.resourceId();
        List<EventSubscription> subs = service.subscriptionsForTopic(topicResourceId);

        for (JsonNode raw : events) {
            CanonEvent event = canonicalize(raw, t.inputSchema());
            for (EventSubscription sub : subs) {
                if (!matches(sub.filter(), event.subject(), event.eventType())) {
                    continue;
                }
                dispatch(sub, event, topicResourceId);
            }
        }
        LOG.debugv("Published {0} event(s) to topic {1} ({2} subscription(s))",
                events.size(), topicName, subs.size());
        return Response.ok().build();
    }

    private void dispatch(EventSubscription sub, CanonEvent event, String topicResourceId) {
        boolean cloudEvents = EventGridModels.SCHEMA_CLOUD_EVENT.equalsIgnoreCase(sub.eventDeliverySchema());
        Map<String, Object> rendered = cloudEvents
                ? renderCloudEvent(event, topicResourceId)
                : renderEventGridEvent(event, topicResourceId);
        String contentType = cloudEvents ? "application/cloudevents-batch+json" : "application/json";
        try {
            byte[] body = MAPPER.writeValueAsBytes(List.of(rendered));
            delivery.deliver(sub, body, contentType);
        } catch (Exception e) {
            LOG.warnv("Failed to render event for subscription {0}: {1}", sub.name(), e.getMessage());
        }
    }

    private CanonEvent canonicalize(JsonNode e, String inputSchema) {
        if (EventGridModels.SCHEMA_CLOUD_EVENT.equalsIgnoreCase(inputSchema)) {
            return new CanonEvent(
                    text(e, "id"), text(e, "subject"), text(e, "type"),
                    text(e, "time"), e.get("data"), "1");
        }
        // EventGridSchema and CustomEventSchema (treated as Event Grid).
        return new CanonEvent(
                text(e, "id"), text(e, "subject"), text(e, "eventType"),
                text(e, "eventTime"), e.get("data"), textOr(e, "dataVersion", "1"));
    }

    private Map<String, Object> renderEventGridEvent(CanonEvent e, String topicResourceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", e.id());
        out.put("topic", topicResourceId);
        out.put("subject", e.subject() == null ? "" : e.subject());
        out.put("eventType", e.eventType());
        out.put("eventTime", e.eventTime());
        out.put("data", asObject(e.data()));
        out.put("dataVersion", e.dataVersion());
        out.put("metadataVersion", "1");
        return out;
    }

    private Map<String, Object> renderCloudEvent(CanonEvent e, String topicResourceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", e.id());
        out.put("source", topicResourceId);
        out.put("type", e.eventType());
        if (e.subject() != null) {
            out.put("subject", e.subject());
        }
        out.put("time", e.eventTime());
        out.put("specversion", "1.0");
        out.put("data", asObject(e.data()));
        return out;
    }

    private boolean matches(Filter f, String subject, String eventType) {
        if (!f.includedEventTypes().isEmpty()) {
            boolean typeMatch = eventType != null && f.includedEventTypes().stream()
                    .anyMatch(t -> t.equalsIgnoreCase(eventType));
            if (!typeMatch) {
                return false;
            }
        }
        String subj = subject == null ? "" : subject;
        if (f.subjectBeginsWith() != null && !f.subjectBeginsWith().isEmpty()
                && !startsWith(subj, f.subjectBeginsWith(), f.isSubjectCaseSensitive())) {
            return false;
        }
        if (f.subjectEndsWith() != null && !f.subjectEndsWith().isEmpty()
                && !endsWith(subj, f.subjectEndsWith(), f.isSubjectCaseSensitive())) {
            return false;
        }
        return true;
    }

    private static boolean startsWith(String subject, String prefix, boolean caseSensitive) {
        return caseSensitive ? subject.startsWith(prefix)
                : subject.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }

    private static boolean endsWith(String subject, String suffix, boolean caseSensitive) {
        return caseSensitive ? subject.endsWith(suffix)
                : subject.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT));
    }

    private Object asObject(JsonNode data) {
        if (data == null || data.isNull() || data.isMissingNode()) {
            return null;
        }
        return MAPPER.convertValue(data, Object.class);
    }

    private static List<JsonNode> toList(JsonNode array) {
        java.util.List<JsonNode> list = new java.util.ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isValueNode() ? v.asText() : null;
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String v = text(node, field);
        return v != null ? v : fallback;
    }

    private static Response error(int status, String code, String message) {
        return ArmErrors.error(status, code, message);
    }
}
