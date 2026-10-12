package io.floci.az.services.monitor.activitylog;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmErrors;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * {@code GET /subscriptions/{sub}/providers/Microsoft.Insights/eventtypes/management/values}: the Activity
 * Logs list operation, with its {@code $filter} grammar, {@code $select} projection and {@code nextLink}
 * paging through an opaque {@code $skiptoken}.
 */
@ApplicationScoped
public class ActivityLogEndpoint {

    static final String PATH_MARKER = "/providers/microsoft.insights/eventtypes/management/values";

    private final ActivityLog log;
    private final int pageSize;

    @Inject
    public ActivityLogEndpoint(ActivityLog log, io.floci.az.config.EmulatorConfig config) {
        this(log, config.services().monitor().activityLog().pageSize());
    }

    ActivityLogEndpoint(ActivityLog log, int pageSize) {
        this.log = log;
        this.pageSize = Math.max(1, pageSize);
    }

    public static boolean matches(String path) {
        int query = path.indexOf('?');
        String resource = (query >= 0 ? path.substring(0, query) : path).toLowerCase(Locale.ROOT);
        return resource.endsWith(PATH_MARKER.substring(1)) || resource.endsWith(PATH_MARKER);
    }

    public Response list(AzureRequest req, String subscriptionId) {
        if (!"GET".equalsIgnoreCase(req.method())) {
            return ArmErrors.error(405, "MethodNotAllowed",
                "The requested resource does not support http method '" + req.method() + "'.");
        }
        Predicate<ActivityLogEvent> filter;
        try {
            filter = ActivityLogFilter.parse(req.queryParams().get("$filter"));
        } catch (ActivityLogFilter.InvalidFilterException e) {
            return ArmErrors.error(400, "BadRequest", e.getMessage());
        }
        Set<String> select = select(req.queryParams().get("$select"));
        List<ActivityLogEvent> events = log.query(subscriptionId, filter);

        int skip = 0;
        String token = req.queryParams().get("$skiptoken");
        if (token != null && !token.isBlank()) {
            Integer offset = decode(token);
            if (offset == null) {
                return ArmErrors.error(400, "BadRequest", "The provided $skiptoken is invalid.");
            }
            skip = offset;
        }
        int from = Math.min(skip, events.size());
        int to = Math.min(from + pageSize, events.size());
        List<Map<String, Object>> value = new ArrayList<>();
        for (ActivityLogEvent event : events.subList(from, to)) {
            value.add(event.toJson(select));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", value);
        if (to < events.size()) {
            body.put("nextLink", nextLink(req, subscriptionId, to));
        }
        return Response.ok(body).build();
    }

    private static Set<String> select(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String name : raw.split(",")) {
                if (!name.isBlank()) {
                    out.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static String nextLink(AzureRequest req, String subscriptionId, int offset) {
        String base = (req.secure() ? "https://" : "http://") + (req.authority() == null ? req.host() : req.authority());
        StringBuilder link = new StringBuilder(base == null || base.endsWith("//") ? "" : base);
        link.append("/subscriptions/").append(subscriptionId).append("/providers/microsoft.insights/eventtypes/management/values");
        link.append("?api-version=").append(req.queryParams().getOrDefault("api-version", "2015-04-01"));
        for (String param : List.of("$filter", "$select")) {
            String value = req.queryParams().get(param);
            if (value != null) {
                link.append('&').append(param).append('=').append(java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
            }
        }
        link.append("&$skiptoken=").append(encode(offset));
        return link.toString();
    }

    static String encode(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("skip:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    static Integer decode(String token) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            return decoded.startsWith("skip:") ? Integer.parseInt(decoded.substring(5)) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
