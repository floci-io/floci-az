package io.floci.az.services.monitor.activitylog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The subscription Activity Log: every management-plane write, delete and action the ARM request gate
 * saw, as {@code Administrative} entries, plus the {@code Policy} entries other services record. Kept in
 * memory, newest last, bounded by {@code floci-az.services.monitor.activity-log.capacity}.
 */
@ApplicationScoped
public class ActivityLog implements Resettable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Deque<ActivityLogEvent> events = new ArrayDeque<>();
    private final int capacity;

    @Inject
    public ActivityLog(EmulatorConfig config) {
        this(config.services().monitor().activityLog().capacity());
    }

    ActivityLog(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void record(ActivityLogEvent event) {
        if (events.size() >= capacity) {
            events.removeFirst();
        }
        events.addLast(event);
    }

    /** The entries of a subscription that satisfy {@code filter}, newest first, as the API lists them. */
    public synchronized List<ActivityLogEvent> query(String subscriptionId, Predicate<ActivityLogEvent> filter) {
        List<ActivityLogEvent> out = new ArrayList<>();
        for (ActivityLogEvent event : events) {
            if ((subscriptionId == null || subscriptionId.equalsIgnoreCase(event.subscriptionId())) && filter.test(event)) {
                out.add(event);
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    public synchronized List<ActivityLogEvent> events() {
        return new ArrayList<>(events);
    }

    @Override
    public synchronized void clear() {
        events.clear();
    }

    /**
     * Records a {@code Policy} category entry for a policy effect that fired on a request, the way Azure
     * writes {@code Microsoft.Authorization/policies/{effect}/action}: a deny is {@code Failed} at
     * {@code Error}, an audit {@code Succeeded} at {@code Warning}, anything else {@code Succeeded} at
     * {@code Informational}. {@code policies} carries the definitions involved, serialised as JSON the way
     * the Activity Log stores it.
     *
     * @param request  the facts of the evaluated request
     * @param effect   the effect that fired, lowercase ({@code deny}, {@code audit}, {@code append}, ...)
     * @param policies one map per definition, with the {@code policyDefinitionId}, {@code policyAssignmentId}
     *                 and related keys the Policy event schema lists
     */
    public ActivityLogEvent recordPolicyEffect(RequestFacts request, String effect, List<Map<String, Object>> policies,
                                               String resourceLocation) {
        String level = switch (effect) {
            case "deny" -> "Error";
            case "audit" -> "Warning";
            default -> "Informational";
        };
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("isComplianceCheck", "False");
        if (resourceLocation != null) {
            properties.put("resourceLocation", resourceLocation);
        }
        properties.put("ancestors", "");
        properties.put("policies", json(policies));
        Instant now = Instant.now();
        ActivityLogEvent event = new ActivityLogEvent(UUID.randomUUID().toString(), request.operationId(),
            request.correlationId(), now, now, "Policy", "EndRequest",
            "Microsoft.Authorization/policies/" + effect + "/action", level, "deny".equals(effect) ? "Failed" : "Succeeded",
            "", request.caller(), request.claims(), request.action(), request.resourceId(), request.httpMethod(),
            request.httpUri(), request.clientRequestId(), request.clientIpAddress(), request.resourceId(),
            request.resourceType(), request.resourceProviderName(), request.resourceGroupName(), request.subscriptionId(),
            request.tenantId(), properties);
        record(event);
        return event;
    }

    /** The request-side facts an entry is built from, shared by the Administrative and Policy recorders. */
    public record RequestFacts(String operationId, String correlationId, String caller, Map<String, String> claims,
                               String action, String httpMethod, String httpUri, String clientRequestId,
                               String clientIpAddress, String resourceId, String resourceType,
                               String resourceProviderName, String resourceGroupName, String subscriptionId,
                               String tenantId) {
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
