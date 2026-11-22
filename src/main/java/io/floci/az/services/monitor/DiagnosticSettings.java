package io.floci.az.services.monitor;

import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.services.monitor.MonitorModels.MonitorResource;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diagnostic settings, the {@code Microsoft.Insights/diagnosticSettings} extension resource of a resource or of a
 * subscription, answered in the shape Azure answers them.
 *
 * <p>A resource's setting is written whole: every destination property, {@code null} when unset, {@code logs} and
 * {@code metrics} ({@code []} when not sent), each entry with its {@code categoryGroup} and {@code retentionPolicy}.
 * A subscription's setting (the Activity Log's export) carries only what was sent, and its reads list every Activity
 * Log category, those not sent as disabled. Nothing is made up: a setting sent without categories has none.
 *
 * <p>Under {@code parity-overrides.strict-diagnostic-settings} a setting is refused as Azure refuses it: a
 * subscription category outside the Activity Log's, retention on a new setting, and a data sink another setting of the
 * same resource already uses for the same category.
 */
final class DiagnosticSettings {

    static final String TYPE = "Microsoft.Insights/diagnosticSettings";

    private static final Pattern SETTINGS = Pattern.compile(
        "(.+)/providers/microsoft\\.insights/diagnosticsettings(?![a-z])(?:/([^/?]+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CATEGORIES = Pattern.compile(
        "(.+)/providers/microsoft\\.insights/diagnosticsettingscategories(?![a-z])(?:/([^/?]+))?",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBSCRIPTION = Pattern.compile("(?i)^/?subscriptions/([^/]+)$");

    /** The Activity Log categories a subscription setting exports, in the order Azure lists them. */
    static final List<String> ACTIVITY_LOG_CATEGORIES = List.of("Security", "Administrative", "ServiceHealth", "Alert",
        "Recommendation", "Policy", "Autoscale", "ResourceHealth");

    /** The destination properties of a resource's setting, in the order Azure writes them. */
    private static final List<String> DESTINATIONS = List.of("storageAccountId", "serviceBusRuleId", "workspaceId",
        "eventHubAuthorizationRuleId", "eventHubName");
    /** The properties that name a data sink, which two settings of a resource may not share for one category. */
    private static final List<String> SINKS = List.of("storageAccountId", "serviceBusRuleId", "workspaceId",
        "eventHubAuthorizationRuleId", "marketplacePartnerId");

    private final ArmResourceStore<MonitorResource> store;
    private final boolean strict;

    DiagnosticSettings(ArmResourceStore<MonitorResource> store, boolean strict) {
        this.store = store;
        this.strict = strict;
    }

    /** Whether {@code path} addresses diagnostic settings or their categories. */
    static boolean matches(String path) {
        return path != null && (SETTINGS.matcher(path).find() || CATEGORIES.matcher(path).find());
    }

    Response handle(String path, String method, Map<String, Object> body) {
        Matcher categories = CATEGORIES.matcher(path);
        if (categories.find()) {
            return categories(categories.group(1));
        }
        Matcher m = SETTINGS.matcher(path);
        if (!m.find()) {
            return Response.status(400).entity("Invalid diagnosticSettings path").build();
        }
        String resourceUri = m.group(1);
        String name = m.group(2);
        Optional<ArmResourceId> target = ArmResourceId.parse(resourceUri);
        if (target.isEmpty()) {
            return Response.status(400).entity("Invalid diagnosticSettings path").build();
        }
        boolean subscription = SUBSCRIPTION.matcher(resourceUri).matches();
        if ("GET".equals(method) && name == null) {
            List<Map<String, Object>> value = settingsOf(target.get()).stream()
                .map(setting -> render(setting, subscription, true)).toList();
            return json(200, Map.of("value", value));
        }
        if (name == null) {
            return Response.status(400).entity("diagnosticSettings name is required for this operation").build();
        }
        ArmResourceId id = ArmResourceId.parse("/" + m.group(0)).orElseThrow();
        return switch (method) {
            case "PUT" -> put(id, target.get(), name, subscription, body);
            case "GET" -> store.get(id)
                .map(stored -> json(200, render(stored.value(), subscription, true)))
                .orElseGet(() -> error(404, "ResourceNotFound", "The diagnostic setting '" + name + "' doesn't exist."));
            case "DELETE" -> {
                store.delete(id);
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    private Response put(ArmResourceId id, ArmResourceId target, String name, boolean subscription,
                         Map<String, Object> body) {
        Object rawProps = body.get("properties");
        if (rawProps != null && !(rawProps instanceof Map<?, ?>)) {
            return Response.status(400).entity("'properties' must be an object").build();
        }
        Map<String, Object> properties = normalize(rawProps instanceof Map<?, ?> props ? cast(props) : Map.of());
        if (strict) {
            Optional<Response> refusal = refusal(id, target, subscription, properties);
            if (refusal.isPresent()) {
                return refusal.get();
            }
        }
        MonitorResource setting = store.createOrUpdate(id, existing -> new MonitorResource(
            existing.map(MonitorResource::id).orElse(id.id()), existing.map(MonitorResource::name).orElse(name),
            TYPE, null, null, properties)).stored().value();
        return json(200, render(setting, subscription, false));
    }

    /** The properties as sent, with {@code logs} and {@code metrics} lists of entries. */
    private static Map<String, Object> normalize(Map<String, Object> sent) {
        Map<String, Object> out = new LinkedHashMap<>();
        sent.forEach((key, value) -> {
            if (!"logs".equals(key) && !"metrics".equals(key)) {
                out.put(key, value);
            }
        });
        if (sent.containsKey("logs")) {
            out.put("logs", entries(sent.get("logs")));
        }
        if (sent.containsKey("metrics")) {
            out.put("metrics", entries(sent.get("metrics")));
        }
        return out;
    }

    private static List<Map<String, Object>> entries(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    out.add(cast(map));
                }
            }
        }
        return out;
    }

    // ── Refusals ────────────────────────────────────────────────────────────────

    private Optional<Response> refusal(ArmResourceId id, ArmResourceId target, boolean subscription,
                                       Map<String, Object> properties) {
        List<Map<String, Object>> logs = list(properties.get("logs"));
        List<Map<String, Object>> metrics = list(properties.get("metrics"));
        for (Map<String, Object> entry : concat(logs, metrics)) {
            if (entry.get("retentionPolicy") instanceof Map<?, ?> retention && Boolean.TRUE.equals(retention.get("enabled"))) {
                return Optional.of(error(400, "BadRequest",
                    "Diagnostic settings does not support retention for new diagnostic settings."));
            }
        }
        if (subscription) {
            for (Map<String, Object> entry : logs) {
                if (entry.get("category") instanceof String category && ACTIVITY_LOG_CATEGORIES.stream()
                        .noneMatch(category::equalsIgnoreCase)) {
                    return Optional.of(error(400, "BadRequest", "Category '" + category + "' is not supported."));
                }
            }
        }
        String key = id.canonicalKey();
        for (MonitorResource other : settingsOf(target)) {
            if (ArmResourceId.parse(other.id()).map(ArmResourceId::canonicalKey).orElse("").equals(key)) {
                continue;
            }
            for (String sink : SINKS) {
                String mine = text(properties.get(sink));
                if (mine == null || !mine.equalsIgnoreCase(text(other.properties() == null ? null : other.properties().get(sink)))) {
                    continue;
                }
                for (Map<String, Object> entry : concat(logs, metrics)) {
                    String category = enabledCategory(entry);
                    if (category != null && sends(other, category)) {
                        return Optional.of(error(409, "Conflict", "Data sink '" + mine + "' is already used in diagnostic"
                            + " setting '" + other.name() + "' for category '" + category + "'. Data sinks can't be reused"
                            + " in different settings on the same category for the same resource."));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean sends(MonitorResource setting, String category) {
        Map<String, Object> properties = setting.properties() == null ? Map.of() : setting.properties();
        return concat(list(properties.get("logs")), list(properties.get("metrics"))).stream()
            .anyMatch(entry -> category.equalsIgnoreCase(enabledCategory(entry)));
    }

    /** The category an entry sends, or null for a disabled entry or a category group. */
    private static String enabledCategory(Map<String, Object> entry) {
        return Boolean.TRUE.equals(entry.get("enabled")) ? text(entry.get("category")) : null;
    }

    // ── Shapes ──────────────────────────────────────────────────────────────────

    private List<MonitorResource> settingsOf(ArmResourceId target) {
        String targetKey = target.canonicalKey();
        return store.all().stream()
            .filter(stored -> stored.id().scope() != null && targetKey.equals(stored.id().scope().canonicalKey()))
            .map(ArmResourceStore.Stored::value)
            .toList();
    }

    /**
     * A setting as Azure answers it: a resource's whole; a subscription's with what was sent and, on a read,
     * every Activity Log category.
     */
    private static Map<String, Object> render(MonitorResource setting, boolean subscription, boolean read) {
        Map<String, Object> properties = setting.properties() == null ? Map.of() : setting.properties();
        Map<String, Object> out = new LinkedHashMap<>();
        if (subscription) {
            Matcher scope = Pattern.compile("(?i)^/?subscriptions/([^/]+)/").matcher(setting.id());
            String sub = scope.find() ? scope.group(1) : "";
            out.put("id", "/subscriptions/" + sub + "/providers/microsoft.insights/diagnosticSettings/" + setting.name());
            out.put("type", TYPE);
            out.put("name", setting.name());
            out.put("location", "global");
            Map<String, Object> props = new LinkedHashMap<>();
            for (String key : List.of("storageAccountId", "serviceBusRuleId", "workspaceId", "eventHubAuthorizationRuleId",
                    "eventHubName", "marketplacePartnerId")) {
                if (properties.get(key) != null) {
                    props.put(key, properties.get(key));
                }
            }
            List<Map<String, Object>> sent = new ArrayList<>();
            for (Map<String, Object> entry : list(properties.get("logs"))) {
                sent.add(subscriptionLog(text(entry.get("category")), entry.get("enabled"), entry.get("categoryGroup")));
            }
            props.put("logs", read ? everyActivityLogCategory(sent) : sent);
            out.put("properties", props);
            return out;
        }
        out.put("id", setting.id());
        out.put("type", TYPE);
        out.put("name", setting.name());
        out.put("location", null);
        out.put("kind", null);
        out.put("tags", null);
        Map<String, Object> props = new LinkedHashMap<>();
        for (String key : DESTINATIONS) {
            props.put(key, properties.get(key));
        }
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (Map<String, Object> entry : list(properties.get("metrics"))) {
            Map<String, Object> metric = new LinkedHashMap<>();
            metric.put("category", entry.get("category"));
            if (entry.get("timeGrain") != null) {
                metric.put("timeGrain", entry.get("timeGrain"));
            }
            metric.put("enabled", entry.get("enabled"));
            metric.put("retentionPolicy", retention(entry));
            metrics.add(metric);
        }
        props.put("metrics", metrics);
        List<Map<String, Object>> logs = new ArrayList<>();
        for (Map<String, Object> entry : list(properties.get("logs"))) {
            Map<String, Object> log = new LinkedHashMap<>();
            log.put("category", entry.get("category"));
            log.put("categoryGroup", entry.get("categoryGroup"));
            log.put("enabled", entry.get("enabled"));
            log.put("retentionPolicy", retention(entry));
            logs.add(log);
        }
        props.put("logs", logs);
        props.put("logAnalyticsDestinationType", properties.get("logAnalyticsDestinationType"));
        if (properties.get("marketplacePartnerId") != null) {
            props.put("marketplacePartnerId", properties.get("marketplacePartnerId"));
        }
        out.put("properties", props);
        out.put("identity", null);
        return out;
    }

    /** Every Activity Log category in Azure's order, as sent or disabled, then anything else that was sent. */
    private static List<Map<String, Object>> everyActivityLogCategory(List<Map<String, Object>> sent) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> rest = new ArrayList<>(sent);
        for (String category : ACTIVITY_LOG_CATEGORIES) {
            Optional<Map<String, Object>> match = rest.stream()
                .filter(entry -> category.equalsIgnoreCase(text(entry.get("category")))).findFirst();
            match.ifPresent(rest::remove);
            out.add(match.orElseGet(() -> subscriptionLog(category, false, null)));
        }
        out.addAll(rest);
        return out;
    }

    private static Map<String, Object> subscriptionLog(String category, Object enabled, Object categoryGroup) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("category", category);
        log.put("enabled", enabled);
        log.put("categoryGroup", categoryGroup);
        return log;
    }

    private static Object retention(Map<String, Object> entry) {
        if (entry.get("retentionPolicy") instanceof Map<?, ?> sent) {
            return sent;
        }
        Map<String, Object> retention = new LinkedHashMap<>();
        retention.put("enabled", false);
        retention.put("days", 0);
        return retention;
    }

    /** The categories list: not served for a subscription; per resource type, not modelled. */
    private static Response categories(String resourceUri) {
        if (SUBSCRIPTION.matcher(resourceUri).matches()) {
            return error(400, "ResourceTypeNotSupported", "The resource type '/' does not support diagnostic settings.");
        }
        return ArmErrors.notFound("Resource not found: " + resourceUri + "/providers/microsoft.insights/diagnosticSettingsCategories");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /** The flat {@code {code, message}} error body Azure Monitor answers diagnostic settings with. */
    private static Response error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return json(status, body);
    }

    private static Response json(int status, Object body) {
        return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return new LinkedHashMap<>((Map<String, Object>) map);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static List<Map<String, Object>> concat(List<Map<String, Object>> a, List<Map<String, Object>> b) {
        List<Map<String, Object>> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }
}
