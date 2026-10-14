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
 * paging through an opaque {@code $skiptoken} that names the entry the previous page ended on, so a
 * page stays stable while writes keep adding entries.
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

        long before = Long.MAX_VALUE;
        String token = req.queryParams().get("$skiptoken");
        if (token != null && !token.isBlank()) {
            Long cursor = decode(token);
            if (cursor == null) {
                return ArmErrors.error(400, "BadRequest", "The provided $skiptoken is invalid.");
            }
            before = cursor;
        }
        List<ActivityLog.Entry> entries = log.query(subscriptionId, filter, before);
        int to = Math.min(pageSize, entries.size());
        List<Map<String, Object>> value = new ArrayList<>();
        for (ActivityLog.Entry entry : entries.subList(0, to)) {
            value.add(entry.event().toJson(select));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", value);
        if (to < entries.size()) {
            body.put("nextLink", nextLink(req, subscriptionId, entries.get(to - 1).sequence()));
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

    private static String nextLink(AzureRequest req, String subscriptionId, long lastSequence) {
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
        link.append("&$skiptoken=").append(encode(lastSequence));
        return link.toString();
    }

    /** A cursor naming the last entry served: the next page holds the entries recorded before it. */
    static String encode(long lastSequence) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("before:" + lastSequence).getBytes(StandardCharsets.UTF_8));
    }

    /** The sequence number a cursor names, or null when the token is malformed or not positive. */
    static Long decode(String token) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith("before:")) {
                return null;
            }
            long sequence = Long.parseLong(decoded.substring(7));
            return sequence > 0 ? sequence : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
