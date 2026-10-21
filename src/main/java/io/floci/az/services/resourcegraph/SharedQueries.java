package io.floci.az.services.resourcegraph;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.Resettable;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ResourceIndexContributor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The namespace's one resource type, {@code Microsoft.ResourceGraph/queries}: shared queries saved in a
 * resource group, as the {@code GraphQuery} resource of the Resource Graph specification. In memory and
 * global, like the service's. Registered in the ARM resource index, so generic listings, the
 * {@code resources} table and a resource-group delete see them.
 */
@ApplicationScoped
public class SharedQueries implements Resettable, ResourceIndexContributor {

    static final String TYPE = "Microsoft.ResourceGraph/queries";

    private static final Pattern PATH = Pattern.compile(
        "(?i)^subscriptions/([^/]+)(?:/resourcegroups/([^/]+))?/providers/microsoft\\.resourcegraph/queries(?:/([^/]+))?$");

    private final Map<String, Map<String, Object>> queries = new LinkedHashMap<>();
    private final EmulatorConfig config;

    @Inject
    public SharedQueries(EmulatorConfig config) {
        this.config = config;
    }

    /** True when {@code resource} (without its query string, any case) addresses shared queries. */
    static boolean matches(String resource) {
        return PATH.matcher(resource).matches();
    }

    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        return indexEntries(sub, rg);
    }

    @Override
    public List<Map<String, Object>> listSubscriptionResources(String sub) {
        return indexEntries(sub, null);
    }

    @Override
    public boolean indexEnabled() {
        return config.services().resourceGraph().enabled();
    }

    /** Joined the index after the default listings were pinned: listed under {@code resource-group-delete} only. */
    @Override
    public boolean inDefaultListing() {
        return false;
    }

    private synchronized List<Map<String, Object>> indexEntries(String sub, String rg) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> query : queries.values()) {
            if (ArmResources.inScope(query.get("id"), sub, rg)) {
                out.add(ArmResources.indexEntry(query));
            }
        }
        return out;
    }

    /** Serves a shared-queries path; {@code resource} keeps the caller's casing, which the names and ids echo. */
    synchronized Response handle(AzureRequest req, String resource) {
        Matcher m = PATH.matcher(resource);
        if (!m.matches()) {
            return ArmErrors.error(404, "InvalidResourceType",
                "The resource type '" + resource + "' could not be found in the namespace 'Microsoft.ResourceGraph'.");
        }
        String subscription = m.group(1);
        String group = m.group(2);
        String name = m.group(3);
        String method = req.method().toUpperCase(Locale.ROOT);
        if (name == null) {
            return method.equals("GET") ? list(subscription, group) : methodNotAllowed(method);
        }
        if (group == null) {
            return ArmErrors.error(404, "ResourceNotFound", "Shared queries live in a resource group.");
        }
        String id = "/subscriptions/" + subscription + "/resourceGroups/" + group + "/providers/" + TYPE + "/" + name;
        String key = id.toLowerCase(Locale.ROOT);
        return switch (method) {
            case "GET" -> queries.containsKey(key) ? Response.ok(queries.get(key)).build() : notFound(name);
            case "PUT" -> createOrUpdate(req, id, key, name);
            case "PATCH" -> patch(req, key, name);
            case "DELETE" -> Response.status(queries.remove(key) != null ? 200 : 204).build();
            default -> methodNotAllowed(method);
        };
    }

    private Response list(String subscription, String group) {
        String prefix = ("/subscriptions/" + subscription + "/resourceGroups/" + (group == null ? "" : group + "/")).toLowerCase(Locale.ROOT);
        List<Map<String, Object>> value = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : queries.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                value.add(entry.getValue());
            }
        }
        return Response.ok(Map.of("value", value)).build();
    }

    private Response createOrUpdate(AzureRequest req, String id, String key, String name) {
        Map<String, Object> body;
        try {
            body = ArmJson.parseBodyStrict(req);
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent", "The request content was invalid and could not be deserialized.");
        }
        Map<String, Object> properties = ArmJson.cast(body.get("properties"));
        if (!(properties.get("query") instanceof String query) || query.isBlank()) {
            return ArmErrors.error(400, "InvalidRequestContent", "The query cannot be null or empty.");
        }
        boolean existed = queries.containsKey(key);
        Map<String, Object> stored = representation(id, name, body.get("tags"), query, properties.get("description"));
        queries.put(key, stored);
        return Response.status(existed ? 200 : 201).entity(stored).build();
    }

    private Response patch(AzureRequest req, String key, String name) {
        Map<String, Object> existing = queries.get(key);
        if (existing == null) {
            return notFound(name);
        }
        Map<String, Object> body;
        try {
            body = ArmJson.parseBodyStrict(req);
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent", "The request content was invalid and could not be deserialized.");
        }
        Map<String, Object> properties = ArmJson.cast(existing.get("properties"));
        Map<String, Object> given = ArmJson.cast(body.get("properties"));
        String query = given.get("query") instanceof String q && !q.isBlank() ? q : String.valueOf(properties.get("query"));
        Object description = given.containsKey("description") ? given.get("description") : properties.get("description");
        Object tags = body.containsKey("tags") ? body.get("tags") : existing.get("tags");
        Map<String, Object> updated = representation(String.valueOf(existing.get("id")), name, tags, query, description);
        queries.put(key, updated);
        return Response.ok(updated).build();
    }

    private static Map<String, Object> representation(String id, String name, Object tags, String query, Object description) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", description);
        properties.put("query", query);
        properties.put("timeModified", Instant.now().toString());
        properties.put("resultKind", "basic");
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("id", id);
        resource.put("name", name);
        resource.put("type", TYPE);
        resource.put("location", "global");
        resource.put("tags", tags instanceof Map<?, ?> map ? map : Map.of());
        resource.put("etag", "\"" + UUID.randomUUID() + "\"");
        resource.put("properties", properties);
        return resource;
    }

    private static Response notFound(String name) {
        return ArmErrors.error(404, "ResourceNotFound", "The Resource 'Microsoft.ResourceGraph/queries/" + name + "' was not found.");
    }

    private static Response methodNotAllowed(String method) {
        return ArmErrors.error(405, "MethodNotAllowed", "The requested resource does not support http method '" + method + "'.");
    }

    @Override
    public synchronized void clear() {
        queries.clear();
    }
}
