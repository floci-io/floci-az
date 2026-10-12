package io.floci.az.services.managementgroups;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.Subscription;
import io.floci.az.services.managementgroups.ManagementGroupStore.Group;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Azure Management Groups ({@code Microsoft.Management}): the tenant hierarchy of groups and subscriptions.
 * Get with {@code $expand=children|path|ancestors} and {@code $recurse}, list, create or update, patch
 * (display name and parent), delete, descendants, subscription membership, {@code getEntities},
 * {@code checkNameAvailability} and the tenant backfill operations the CLI calls. Creates and deletes
 * answer synchronously, which the service allows.
 */
@ApplicationScoped
public class ManagementGroupHandler implements AzureServiceHandler {

    static final String SERVICE_TYPE = "managementgroups";
    static final String TYPE = "Microsoft.Management/managementGroups";
    static final String SUBSCRIPTION_TYPE = "/subscriptions";

    private static final Logger LOG = Logger.getLogger(ManagementGroupHandler.class);
    private static final Pattern GROUP = Pattern.compile("(?i)^providers/microsoft\\.management/managementgroups/([^/?]+)(?:/(.*))?$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_()\\-.]{1,90}$");
    private static final Set<String> RESERVED_NAMES = Set.of("operations", "checknameavailability", "getentities",
        "starttenantbackfill", "tenantbackfillstatus");

    private final EmulatorConfig config;
    private final ManagementGroupStore store;
    private final AzureDirectory directory;

    @Inject
    public ManagementGroupHandler(EmulatorConfig config, ManagementGroupStore store, AzureDirectory directory) {
        this.config = config;
        this.store = store;
        this.directory = directory;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().managementGroups().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder().provider("Microsoft.Management").build();
    }

    @Override
    public boolean canHandle(AzureRequest request) {
        return SERVICE_TYPE.equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest req) {
        String path = req.resourcePath();
        int query = path.indexOf('?');
        String resource = query >= 0 ? path.substring(0, query) : path;
        String method = req.method().toUpperCase(Locale.ROOT);
        String lower = resource.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("providers/microsoft.management/")) {
            return ArmErrors.error(404, "InvalidResourceType",
                "The resource type could not be found in the namespace 'Microsoft.Management'.");
        }
        String tail = resource.substring("providers/microsoft.management/".length());
        String tailLower = tail.toLowerCase(Locale.ROOT);
        return switch (tailLower) {
            case "managementgroups" -> method.equals("GET") ? list() : methodNotAllowed(method);
            case "operations" -> method.equals("GET") ? operations() : methodNotAllowed(method);
            case "checknameavailability" -> method.equals("POST") ? checkNameAvailability(req) : methodNotAllowed(method);
            case "getentities" -> method.equals("POST") ? entities(req) : methodNotAllowed(method);
            case "starttenantbackfill", "tenantbackfillstatus" -> method.equals("POST")
                ? Response.ok(Map.of("tenantId", directory.defaultTenantId(), "status", "Completed")).build()
                : methodNotAllowed(method);
            default -> group(req, method, resource);
        };
    }

    // ── Groups ──────────────────────────────────────────────────────────────────

    private Response group(AzureRequest req, String method, String resource) {
        Matcher m = GROUP.matcher(resource);
        if (!m.matches()) {
            return ArmErrors.error(404, "InvalidResourceType",
                "The resource type could not be found in the namespace 'Microsoft.Management'.");
        }
        String name = m.group(1);
        String rest = m.group(2) == null ? "" : m.group(2);
        if (rest.isEmpty()) {
            return switch (method) {
                case "GET" -> get(req, name);
                case "PUT" -> createOrUpdate(req, name);
                case "PATCH" -> patch(req, name);
                case "DELETE" -> delete(name);
                default -> methodNotAllowed(method);
            };
        }
        String restLower = rest.toLowerCase(Locale.ROOT);
        if (restLower.equals("descendants")) {
            return method.equals("GET") ? descendants(name) : methodNotAllowed(method);
        }
        if (restLower.equals("subscriptions")) {
            return method.equals("GET") ? subscriptions(name) : methodNotAllowed(method);
        }
        if (restLower.startsWith("subscriptions/")) {
            String subscriptionId = rest.substring("subscriptions/".length());
            return switch (method) {
                case "GET" -> subscription(name, subscriptionId);
                case "PUT" -> placeSubscription(name, subscriptionId);
                case "DELETE" -> removeSubscription(name, subscriptionId);
                default -> methodNotAllowed(method);
            };
        }
        if (restLower.equals("settings") || restLower.equals("settings/default")) {
            return settings(name, method, restLower.equals("settings"));
        }
        return ArmErrors.error(404, "InvalidResourceType",
            "The resource type could not be found in the namespace 'Microsoft.Management'.");
    }

    private Response list() {
        List<Map<String, Object>> value = new ArrayList<>();
        for (Group group : store.list()) {
            value.add(info(group));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", value);
        body.put("@nextLink", null);
        return Response.ok(body).build();
    }

    private Response get(AzureRequest req, String name) {
        Optional<Group> found = store.get(name);
        if (found.isEmpty()) {
            return notFound(name);
        }
        Group group = found.get();
        String expand = req.queryParams().get("$expand");
        boolean recurse = "true".equalsIgnoreCase(req.queryParams().get("$recurse"));
        Map<String, Object> body = representation(group);
        Map<String, Object> properties = ArmJson.cast(body.get("properties"));
        if (expand != null) {
            String option = expand.toLowerCase(Locale.ROOT);
            Map<String, Object> details = ArmJson.cast(properties.get("details"));
            if (option.equals("children")) {
                properties.put("children", children(group, recurse));
            } else if (option.equals("path")) {
                details.put("path", pathTo(group));
            } else if (option.equals("ancestors")) {
                List<Map<String, Object>> ancestors = new ArrayList<>();
                for (Group ancestor : store.ancestors(group.name())) {
                    ancestors.add(childInfo(ancestor, false));
                }
                details.put("managementGroupAncestors", ancestors);
                details.put("managementGroupAncestorsChain", ancestorsChain(group));
            } else {
                return ArmErrors.error(400, "InvalidRequest",
                    "The $expand value '" + expand + "' is not supported. Supported values are children, path and ancestors.");
            }
        }
        return Response.ok(body).build();
    }

    private Response createOrUpdate(AzureRequest req, String name) {
        if (!NAME.matcher(name).matches() || RESERVED_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            return ArmErrors.error(400, "InvalidManagementGroupName",
                "The management group name '" + name + "' is invalid. The name can only be an ASCII letter, digit, -, _, (, ), . and can be at most 90 characters long.");
        }
        Map<String, Object> body;
        try {
            body = ArmJson.parseBodyStrict(req);
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent", "The request content was invalid and could not be deserialized.");
        }
        Map<String, Object> props = ArmJson.cast(body.get("properties"));
        Group root = store.root();
        if (name.equalsIgnoreCase(root.name())) {
            return ArmErrors.error(400, "InvalidRequest", "The tenant root management group cannot be created or updated.");
        }
        String parentName = root.name();
        Object details = props.get("details");
        if (details instanceof Map<?, ?> d && ArmJson.cast(d.get("parent")).get("id") instanceof String parentId) {
            parentName = lastSegment(parentId);
            if (store.get(parentName).isEmpty()) {
                return ArmErrors.error(400, "InvalidRequest", "The parent management group '" + parentName + "' could not be found.");
            }
            if (store.isWithin(parentName, name)) {
                return ArmErrors.error(400, "InvalidRequest", "A management group cannot be moved under itself or one of its descendants.");
            }
        }
        Optional<Group> existing = store.get(name);
        String displayName = ArmJson.string(props, "displayName", existing.map(Group::displayName).orElse(name));
        Group group = new Group(name, displayName, directory.defaultTenantId(), parentName,
            existing.map(g -> g.version() + 1).orElse(1), Instant.now(), null);
        store.put(group);
        LOG.infof("Management group %s %s under %s", name, existing.isPresent() ? "updated" : "created", parentName);
        return Response.ok(representation(group)).build();
    }

    private Response patch(AzureRequest req, String name) {
        Optional<Group> existing = store.get(name);
        if (existing.isEmpty()) {
            return notFound(name);
        }
        Group group = existing.get();
        if (group.parent() == null) {
            return ArmErrors.error(400, "InvalidRequest", "The tenant root management group cannot be updated.");
        }
        Map<String, Object> body;
        try {
            body = ArmJson.parseBodyStrict(req);
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent", "The request content was invalid and could not be deserialized.");
        }
        String parentName = group.parent();
        if (body.get("parentGroupId") instanceof String parentId && !parentId.isBlank()) {
            parentName = lastSegment(parentId);
            if (store.get(parentName).isEmpty()) {
                return ArmErrors.error(400, "InvalidRequest", "The parent management group '" + parentName + "' could not be found.");
            }
            if (store.isWithin(parentName, name)) {
                return ArmErrors.error(400, "InvalidRequest", "A management group cannot be moved under itself or one of its descendants.");
            }
        }
        Group updated = new Group(name, ArmJson.string(body, "displayName", group.displayName()), group.tenantId(), parentName,
            group.version() + 1, Instant.now(), null);
        store.put(updated);
        return Response.ok(representation(updated)).build();
    }

    private Response delete(String name) {
        Optional<Group> existing = store.get(name);
        if (existing.isEmpty()) {
            return Response.status(204).build();
        }
        if (existing.get().parent() == null) {
            return ArmErrors.error(400, "InvalidRequest", "The tenant root management group cannot be deleted.");
        }
        if (!store.childGroups(name).isEmpty() || !store.childSubscriptions(name).isEmpty()) {
            return ArmErrors.error(409, "Conflict",
                "The management group '" + name + "' has children and cannot be deleted. Move or delete its children first.");
        }
        store.remove(name);
        LOG.infof("Management group %s deleted", name);
        return Response.status(204).build();
    }

    private Response descendants(String name) {
        Optional<Group> found = store.get(name);
        if (found.isEmpty()) {
            return notFound(name);
        }
        List<Map<String, Object>> value = new ArrayList<>();
        collectDescendants(found.get(), value);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", value);
        return Response.ok(body).build();
    }

    private void collectDescendants(Group group, List<Map<String, Object>> out) {
        for (Group child : store.childGroups(group.name())) {
            out.add(descendant(child.id(), TYPE, child.name(), child.displayName(), group.id()));
            collectDescendants(child, out);
        }
        for (Subscription subscription : store.childSubscriptions(group.name())) {
            out.add(descendant("/subscriptions/" + subscription.id(), SUBSCRIPTION_TYPE, subscription.id(),
                subscription.displayName(), group.id()));
        }
    }

    // ── Subscriptions ───────────────────────────────────────────────────────────

    private Response subscriptions(String name) {
        Optional<Group> found = store.get(name);
        if (found.isEmpty()) {
            return notFound(name);
        }
        List<Map<String, Object>> value = new ArrayList<>();
        for (Subscription subscription : store.childSubscriptions(name)) {
            value.add(subscriptionUnderGroup(found.get(), subscription));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", value);
        return Response.ok(body).build();
    }

    private Response subscription(String name, String subscriptionId) {
        Optional<Group> found = store.get(name);
        if (found.isEmpty()) {
            return notFound(name);
        }
        for (Subscription subscription : store.childSubscriptions(name)) {
            if (subscription.id().equalsIgnoreCase(subscriptionId)) {
                return Response.ok(subscriptionUnderGroup(found.get(), subscription)).build();
            }
        }
        return ArmErrors.error(404, "NotFound",
            "The subscription '" + subscriptionId + "' is not a member of management group '" + name + "'.");
    }

    private Response placeSubscription(String name, String subscriptionId) {
        Optional<Group> found = store.get(name);
        if (found.isEmpty()) {
            return notFound(name);
        }
        store.place(subscriptionId, found.get().name());
        Subscription subscription = directory.subscription(subscriptionId).orElseThrow();
        LOG.infof("Subscription %s placed under management group %s", subscriptionId, name);
        return Response.ok(subscriptionUnderGroup(found.get(), subscription)).build();
    }

    private Response removeSubscription(String name, String subscriptionId) {
        if (store.get(name).isEmpty()) {
            return notFound(name);
        }
        boolean member = store.childSubscriptions(name).stream().anyMatch(s -> s.id().equalsIgnoreCase(subscriptionId));
        if (!member) {
            return Response.status(204).build();
        }
        store.unplace(subscriptionId);
        return Response.ok().build();
    }

    // ── Entities, names, operations, settings ───────────────────────────────────

    private Response entities(AzureRequest req) {
        List<Map<String, Object>> value = new ArrayList<>();
        for (Group group : store.list()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("tenantId", group.tenantId());
            properties.put("displayName", group.displayName());
            Group parent = group.parent() == null ? null : store.get(group.parent()).orElse(null);
            properties.put("parent", parent == null ? null : Map.of("id", parent.id()));
            properties.put("permissions", "view");
            properties.put("inheritedPermissions", "view");
            properties.put("numberOfDescendants", countDescendants(group));
            properties.put("numberOfChildren", store.childGroups(group.name()).size() + store.childSubscriptions(group.name()).size());
            properties.put("numberOfChildGroups", store.childGroups(group.name()).size());
            properties.put("parentDisplayNameChain", store.ancestors(group.name()).stream().map(Group::displayName).toList());
            properties.put("parentNameChain", store.ancestors(group.name()).stream().map(Group::name).toList());
            value.add(entity(group.id(), TYPE, group.name(), properties));
        }
        for (Subscription subscription : directory.subscriptions()) {
            Group parent = store.parentOf(subscription.id());
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("tenantId", subscription.tenantId());
            properties.put("displayName", subscription.displayName());
            properties.put("parent", Map.of("id", parent.id()));
            properties.put("permissions", "view");
            properties.put("inheritedPermissions", "view");
            properties.put("numberOfDescendants", 0);
            properties.put("numberOfChildren", 0);
            properties.put("numberOfChildGroups", 0);
            List<Group> chain = new ArrayList<>();
            chain.add(parent);
            chain.addAll(store.ancestors(parent.name()));
            properties.put("parentDisplayNameChain", chain.stream().map(Group::displayName).toList());
            properties.put("parentNameChain", chain.stream().map(Group::name).toList());
            value.add(entity("/subscriptions/" + subscription.id(), SUBSCRIPTION_TYPE, subscription.id(), properties));
        }
        String groupName = req.queryParams().get("groupName");
        if (groupName != null && !groupName.isBlank()) {
            Optional<Group> found = store.get(groupName);
            if (found.isEmpty()) {
                return notFound(groupName);
            }
            value.removeIf(entity -> {
                String name = String.valueOf(entity.get("name"));
                boolean isGroup = TYPE.equals(entity.get("type"));
                return isGroup ? !store.isWithin(name, groupName)
                    : !store.isWithin(store.parentOf(name).name(), groupName);
            });
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", value);
        body.put("count", Map.of("type", "Total", "value", value.size()));
        return Response.ok(body).build();
    }

    private Response checkNameAvailability(AzureRequest req) {
        Map<String, Object> body = ArmJson.parseBodyLenient(req);
        String name = ArmJson.string(body, "name", "");
        Map<String, Object> result = new LinkedHashMap<>();
        if (!NAME.matcher(name).matches()) {
            result.put("nameAvailable", false);
            result.put("reason", "Invalid");
            result.put("message", "The management group name is invalid.");
        } else if (store.get(name).isPresent()) {
            result.put("nameAvailable", false);
            result.put("reason", "AlreadyExists");
            result.put("message", "The management group name '" + name + "' is already in use.");
        } else {
            result.put("nameAvailable", true);
        }
        return Response.ok(result).build();
    }

    private Response operations() {
        List<Map<String, Object>> value = new ArrayList<>();
        for (String operation : List.of("read", "write", "delete", "descendants/read", "subscriptions/read",
                "subscriptions/write", "subscriptions/delete")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", "Microsoft.Management/managementGroups/" + operation);
            entry.put("display", Map.of("provider", "Microsoft Management", "resource", "Management Group",
                "operation", operation, "description", "Management group " + operation));
            value.add(entry);
        }
        return Response.ok(Map.of("value", value)).build();
    }

    private Response settings(String name, String method, boolean listing) {
        if (store.get(name).isEmpty()) {
            return notFound(name);
        }
        Map<String, Object> setting = new LinkedHashMap<>();
        setting.put("id", "/providers/Microsoft.Management/managementGroups/" + name + "/settings/default");
        setting.put("type", "Microsoft.Management/managementGroups/settings");
        setting.put("name", "default");
        setting.put("properties", Map.of("requireAuthorizationForGroupCreation", false,
            "defaultManagementGroup", store.root().id()));
        if (listing) {
            return method.equals("GET") ? Response.ok(Map.of("value", List.of(setting))).build() : methodNotAllowed(method);
        }
        return switch (method) {
            case "GET", "PUT", "PATCH" -> Response.ok(setting).build();
            case "DELETE" -> Response.ok().build();
            default -> methodNotAllowed(method);
        };
    }

    // ── Shapes ──────────────────────────────────────────────────────────────────

    /** The full {@code ManagementGroup} representation of a Get, with its details and parent. */
    private Map<String, Object> representation(Group group) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("version", group.version());
        details.put("updatedTime", group.updatedTime().toString());
        details.put("updatedBy", group.updatedBy());
        Group parent = group.parent() == null ? null : store.get(group.parent()).orElse(null);
        if (parent != null) {
            details.put("parent", Map.of("id", parent.id(), "name", parent.name(), "displayName", parent.displayName()));
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tenantId", group.tenantId());
        properties.put("displayName", group.displayName());
        properties.put("details", details);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", group.id());
        body.put("type", TYPE);
        body.put("name", group.name());
        body.put("properties", properties);
        return body;
    }

    private static Map<String, Object> info(Group group) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", group.id());
        body.put("type", TYPE);
        body.put("name", group.name());
        body.put("properties", Map.of("tenantId", group.tenantId(), "displayName", group.displayName()));
        return body;
    }

    private List<Map<String, Object>> children(Group group, boolean recurse) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Group child : store.childGroups(group.name())) {
            Map<String, Object> info = childInfo(child, true);
            if (recurse) {
                info.put("children", children(child, true));
            }
            out.add(info);
        }
        for (Subscription subscription : store.childSubscriptions(group.name())) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", SUBSCRIPTION_TYPE);
            info.put("id", "/subscriptions/" + subscription.id());
            info.put("name", subscription.id());
            info.put("displayName", subscription.displayName());
            out.add(info);
        }
        return out;
    }

    private static Map<String, Object> childInfo(Group group, boolean typed) {
        Map<String, Object> info = new LinkedHashMap<>();
        if (typed) {
            info.put("type", TYPE);
        }
        info.put("id", group.id());
        info.put("name", group.name());
        info.put("displayName", group.displayName());
        return info;
    }

    private List<Map<String, Object>> pathTo(Group group) {
        List<Map<String, Object>> path = new ArrayList<>();
        List<Group> ancestors = store.ancestors(group.name());
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            path.add(Map.of("name", ancestors.get(i).name(), "displayName", ancestors.get(i).displayName()));
        }
        path.add(Map.of("name", group.name(), "displayName", group.displayName()));
        return path;
    }

    private List<Map<String, Object>> ancestorsChain(Group group) {
        List<Map<String, Object>> chain = new ArrayList<>();
        for (Group ancestor : store.ancestors(group.name())) {
            chain.add(Map.of("name", ancestor.name(), "displayName", ancestor.displayName()));
        }
        return chain;
    }

    /** The ancestors chain of a subscription, direct parent first, in the shape Resource Graph publishes. */
    public List<Map<String, Object>> ancestorsChainOf(String subscriptionId) {
        Group parent = store.parentOf(subscriptionId);
        List<Map<String, Object>> chain = new ArrayList<>();
        chain.add(Map.of("name", parent.name(), "displayName", parent.displayName()));
        chain.addAll(ancestorsChain(parent));
        return chain;
    }

    private static Map<String, Object> descendant(String id, String type, String name, String displayName, String parentId) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("type", type);
        entry.put("name", name);
        entry.put("properties", Map.of("displayName", displayName, "parent", Map.of("id", parentId)));
        return entry;
    }

    private static Map<String, Object> subscriptionUnderGroup(Group group, Subscription subscription) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", group.id() + "/subscriptions/" + subscription.id());
        entry.put("type", "Microsoft.Management/managementGroups/subscriptions");
        entry.put("name", subscription.id());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tenant", subscription.tenantId());
        properties.put("displayName", subscription.displayName());
        properties.put("parent", Map.of("id", group.id()));
        properties.put("state", subscription.state() == null ? "Active" : "Active");
        entry.put("properties", properties);
        return entry;
    }

    private static Map<String, Object> entity(String id, String type, String name, Map<String, Object> properties) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("type", type);
        entry.put("name", name);
        entry.put("properties", properties);
        return entry;
    }

    private int countDescendants(Group group) {
        int count = store.childSubscriptions(group.name()).size();
        for (Group child : store.childGroups(group.name())) {
            count += 1 + countDescendants(child);
        }
        return count;
    }

    private static Response notFound(String name) {
        return ArmErrors.error(404, "NotFound", "Management group '" + name + "' could not be found.");
    }

    private static Response methodNotAllowed(String method) {
        return ArmErrors.error(405, "MethodNotAllowed", "The requested resource does not support http method '" + method + "'.");
    }

    private static String lastSegment(String id) {
        String trimmed = id.endsWith("/") ? id.substring(0, id.length() - 1) : id;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }
}
