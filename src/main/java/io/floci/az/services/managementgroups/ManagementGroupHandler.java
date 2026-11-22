package io.floci.az.services.managementgroups;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.SubscriptionRule;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.Subscription;
import io.floci.az.core.directory.Tenant;
import io.floci.az.services.managementgroups.ManagementGroupStore.Group;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

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
 * Azure Management Groups ({@code Microsoft.Management}): the tenant hierarchy of groups and subscriptions,
 * one hierarchy per tenant, chosen by the caller's token tenant the way the ARM subscription listing is
 * (the default tenant when the token names none the directory holds). Get with
 * {@code $expand=children|path|ancestors} and {@code $recurse}, list, create or update, patch (display name
 * and parent), delete, descendants, subscription membership, {@code getEntities}, {@code checkNameAvailability}
 * and the tenant backfill operations the CLI calls. Creates and deletes answer synchronously, which the
 * service allows.
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
    public Response handle(AzureRequest req) {
        String path = req.resourcePath();
        int query = path.indexOf('?');
        String resource = query >= 0 ? path.substring(0, query) : path;
        String method = req.method().toUpperCase(Locale.ROOT);
        String lower = resource.toLowerCase(Locale.ROOT);
        String tenant = callerTenantId(req);
        if (!lower.startsWith("providers/microsoft.management/")) {
            return ArmErrors.error(404, "InvalidResourceType",
                "The resource type could not be found in the namespace 'Microsoft.Management'.");
        }
        String tail = resource.substring("providers/microsoft.management/".length());
        String tailLower = tail.toLowerCase(Locale.ROOT);
        return switch (tailLower) {
            case "managementgroups" -> method.equals("GET") ? list(tenant) : methodNotAllowed(method);
            case "operations" -> method.equals("GET") ? operations() : methodNotAllowed(method);
            case "checknameavailability" -> method.equals("POST") ? checkNameAvailability(tenant, req) : methodNotAllowed(method);
            case "getentities" -> method.equals("POST") ? entities(tenant, req) : methodNotAllowed(method);
            case "starttenantbackfill", "tenantbackfillstatus" -> method.equals("POST")
                ? Response.ok(Map.of("tenantId", tenant, "status", "Completed")).build()
                : methodNotAllowed(method);
            default -> group(tenant, req, method, resource);
        };
    }

    // ── Groups ──────────────────────────────────────────────────────────────────

    private Response group(String tenant, AzureRequest req, String method, String resource) {
        Matcher m = GROUP.matcher(resource);
        if (!m.matches()) {
            return ArmErrors.error(404, "InvalidResourceType",
                "The resource type could not be found in the namespace 'Microsoft.Management'.");
        }
        String name = m.group(1);
        String rest = m.group(2) == null ? "" : m.group(2);
        if (rest.isEmpty()) {
            return switch (method) {
                case "GET" -> get(tenant, req, name);
                case "PUT" -> createOrUpdate(tenant, req, name);
                case "PATCH" -> patch(tenant, req, name);
                case "DELETE" -> delete(tenant, name);
                default -> methodNotAllowed(method);
            };
        }
        String restLower = rest.toLowerCase(Locale.ROOT);
        if (restLower.equals("descendants")) {
            return method.equals("GET") ? descendants(tenant, name) : methodNotAllowed(method);
        }
        if (restLower.equals("subscriptions")) {
            return method.equals("GET") ? subscriptions(tenant, name) : methodNotAllowed(method);
        }
        if (restLower.startsWith("subscriptions/")) {
            // One subscription segment and nothing below it: a child path is not a resource type here and
            // must not register "id/child" as a subscription.
            String subscriptionId = rest.substring("subscriptions/".length());
            if (subscriptionId.isEmpty() || subscriptionId.contains("/")) {
                return ArmErrors.error(404, "InvalidResourceType",
                    "The resource type could not be found in the namespace 'Microsoft.Management'.");
            }
            return switch (method) {
                case "GET" -> subscription(tenant, name, subscriptionId);
                case "PUT" -> placeSubscription(tenant, name, subscriptionId);
                case "DELETE" -> removeSubscription(tenant, name, subscriptionId);
                default -> methodNotAllowed(method);
            };
        }
        if (restLower.equals("settings") || restLower.equals("settings/default")) {
            return settings(tenant, name, method, restLower.equals("settings"));
        }
        return ArmErrors.error(404, "InvalidResourceType",
            "The resource type could not be found in the namespace 'Microsoft.Management'.");
    }

    private Response list(String tenant) {
        List<Map<String, Object>> value = new ArrayList<>();
        for (Group group : store.list(tenant)) {
            value.add(info(group));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", value);
        body.put("@nextLink", null);
        return Response.ok(body).build();
    }

    private Response get(String tenant, AzureRequest req, String name) {
        Optional<Group> found = store.get(tenant, name);
        if (found.isEmpty()) {
            return notFound(name);
        }
        Group group = found.get();
        String expand = req.queryParams().get("$expand");
        boolean recurse = "true".equalsIgnoreCase(req.queryParams().get("$recurse"));
        Map<String, Object> body = representation(tenant, group);
        Map<String, Object> properties = ArmJson.cast(body.get("properties"));
        if (expand != null) {
            String option = expand.toLowerCase(Locale.ROOT);
            Map<String, Object> details = ArmJson.cast(properties.get("details"));
            if (option.equals("children")) {
                properties.put("children", children(tenant, group, recurse));
            } else if (option.equals("path")) {
                details.put("path", pathTo(tenant, group));
            } else if (option.equals("ancestors")) {
                // The spec types managementGroupAncestors as the ancestors' names; the chain carries the path elements.
                List<String> ancestors = new ArrayList<>();
                for (Group ancestor : store.ancestors(tenant, group.name())) {
                    ancestors.add(ancestor.name());
                }
                details.put("managementGroupAncestors", ancestors);
                details.put("managementGroupAncestorsChain", ancestorsChain(tenant, group));
            } else {
                return ArmErrors.error(400, "InvalidRequest",
                    "The $expand value '" + expand + "' is not supported. Supported values are children, path and ancestors.");
            }
        }
        return Response.ok(body).build();
    }

    private Response createOrUpdate(String tenant, AzureRequest req, String name) {
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
        String parentName = null;
        Object details = props.get("details");
        if (details instanceof Map<?, ?> d && ArmJson.cast(d.get("parent")).get("id") instanceof String parentId) {
            parentName = lastSegment(parentId);
        }
        boolean existed = store.get(tenant, name).isPresent();
        Group group;
        try {
            group = store.createOrUpdate(tenant, name, ArmJson.string(props, "displayName", null), parentName);
        } catch (ManagementGroupStore.HierarchyException e) {
            return ArmErrors.error(e.status(), e.code(), e.getMessage());
        }
        LOG.infof("Management group %s %s under %s", name, existed ? "updated" : "created", group.parent());
        return Response.ok(representation(tenant, group)).build();
    }

    private Response patch(String tenant, AzureRequest req, String name) {
        Optional<Group> existing = store.get(tenant, name);
        if (existing.isEmpty()) {
            return notFound(name);
        }
        Map<String, Object> body;
        try {
            body = ArmJson.parseBodyStrict(req);
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent", "The request content was invalid and could not be deserialized.");
        }
        String parentName = body.get("parentGroupId") instanceof String parentId && !parentId.isBlank() ? lastSegment(parentId) : null;
        Optional<Group> updated;
        try {
            updated = store.move(tenant, name, ArmJson.string(body, "displayName", null), parentName);
        } catch (ManagementGroupStore.HierarchyException e) {
            return ArmErrors.error(e.status(), e.code(), e.getMessage());
        }
        return updated.map(group -> Response.ok(representation(tenant, group)).build()).orElseGet(() -> notFound(name));
    }

    private Response delete(String tenant, String name) {
        try {
            if (store.delete(tenant, name)) {
                LOG.infof("Management group %s deleted", name);
            }
        } catch (ManagementGroupStore.HierarchyException e) {
            return ArmErrors.error(e.status(), e.code(), e.getMessage());
        }
        return Response.status(204).build();
    }

    private Response descendants(String tenant, String name) {
        Optional<Group> found = store.get(tenant, name);
        if (found.isEmpty()) {
            return notFound(name);
        }
        List<Map<String, Object>> value = new ArrayList<>();
        collectDescendants(tenant, found.get(), value);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", value);
        return Response.ok(body).build();
    }

    private void collectDescendants(String tenant, Group group, List<Map<String, Object>> out) {
        for (Group child : store.childGroups(tenant, group.name())) {
            out.add(descendant(child.id(), TYPE, child.name(), child.displayName(), group.id()));
            collectDescendants(tenant, child, out);
        }
        for (Subscription subscription : store.childSubscriptions(tenant, group.name())) {
            out.add(descendant("/subscriptions/" + subscription.id(), SUBSCRIPTION_TYPE, subscription.id(),
                subscription.displayName(), group.id()));
        }
    }

    // ── Subscriptions ───────────────────────────────────────────────────────────

    private Response subscriptions(String tenant, String name) {
        Optional<Group> found = store.get(tenant, name);
        if (found.isEmpty()) {
            return notFound(name);
        }
        List<Map<String, Object>> value = new ArrayList<>();
        for (Subscription subscription : store.childSubscriptions(tenant, name)) {
            value.add(subscriptionUnderGroup(found.get(), subscription));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", value);
        return Response.ok(body).build();
    }

    private Response subscription(String tenant, String name, String subscriptionId) {
        Optional<Group> found = store.get(tenant, name);
        if (found.isEmpty()) {
            return notFound(name);
        }
        for (Subscription subscription : store.childSubscriptions(tenant, name)) {
            if (subscription.id().equalsIgnoreCase(subscriptionId)) {
                return Response.ok(subscriptionUnderGroup(found.get(), subscription)).build();
            }
        }
        return ArmErrors.error(404, "NotFound",
            "The subscription '" + subscriptionId + "' is not a member of management group '" + name + "'.");
    }

    private Response placeSubscription(String tenant, String name, String subscriptionId) {
        Optional<Group> found = store.get(tenant, name);
        if (found.isEmpty()) {
            return notFound(name);
        }
        // The membership path has no subscription segment, so the gate's subscription checks apply here.
        Optional<Subscription> known = directory.subscription(subscriptionId);
        if (known.isEmpty() && config.strictSubscriptions()) {
            return ArmErrors.subscriptionNotFound(subscriptionId);
        }
        if (known.isPresent() && !known.get().state().allowsWrites()) {
            return SubscriptionRule.readOnly(known.get());
        }
        Subscription subscription;
        try {
            subscription = store.place(tenant, subscriptionId, found.get().name());
        } catch (ManagementGroupStore.HierarchyException e) {
            return ArmErrors.error(e.status(), e.code(), e.getMessage());
        }
        LOG.infof("Subscription %s placed under management group %s", subscriptionId, name);
        return Response.ok(subscriptionUnderGroup(found.get(), subscription)).build();
    }

    private Response removeSubscription(String tenant, String name, String subscriptionId) {
        if (store.get(tenant, name).isEmpty()) {
            return notFound(name);
        }
        // Membership check and removal are one locked store step, so a delete addressed to this group
        // cannot undo a move to another group that landed in between.
        return store.unplace(tenant, subscriptionId, name) ? Response.ok().build() : Response.status(204).build();
    }

    // ── Entities, names, operations, settings ───────────────────────────────────

    private Response entities(String tenant, AzureRequest req) {
        List<Map<String, Object>> value = new ArrayList<>();
        for (Group group : store.list(tenant)) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("tenantId", group.tenantId());
            properties.put("displayName", group.displayName());
            Group parent = group.parent() == null ? null : store.get(tenant, group.parent()).orElse(null);
            properties.put("parent", parent == null ? null : Map.of("id", parent.id()));
            properties.put("permissions", "view");
            properties.put("inheritedPermissions", "view");
            properties.put("numberOfDescendants", countDescendants(tenant, group));
            properties.put("numberOfChildren", store.childGroups(tenant, group.name()).size() + store.childSubscriptions(tenant, group.name()).size());
            properties.put("numberOfChildGroups", store.childGroups(tenant, group.name()).size());
            properties.put("parentDisplayNameChain", store.ancestors(tenant, group.name()).stream().map(Group::displayName).toList());
            properties.put("parentNameChain", store.ancestors(tenant, group.name()).stream().map(Group::name).toList());
            value.add(entity(group.id(), TYPE, group.name(), properties));
        }
        for (Subscription subscription : store.tenantSubscriptions(tenant)) {
            Group parent = store.parentOf(tenant, subscription.id());
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
            chain.addAll(store.ancestors(tenant, parent.name()));
            properties.put("parentDisplayNameChain", chain.stream().map(Group::displayName).toList());
            properties.put("parentNameChain", chain.stream().map(Group::name).toList());
            value.add(entity("/subscriptions/" + subscription.id(), SUBSCRIPTION_TYPE, subscription.id(), properties));
        }
        String groupName = req.queryParams().get("groupName");
        if (groupName != null && !groupName.isBlank()) {
            Optional<Group> found = store.get(tenant, groupName);
            if (found.isEmpty()) {
                return notFound(groupName);
            }
            value.removeIf(entity -> {
                String name = String.valueOf(entity.get("name"));
                boolean isGroup = TYPE.equals(entity.get("type"));
                return isGroup ? !store.isWithin(tenant, name, groupName)
                    : !store.isWithin(tenant, store.parentOf(tenant, name).name(), groupName);
            });
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", value);
        body.put("count", value.size()); // EntityListResult.count is a plain integer
        return Response.ok(body).build();
    }

    private Response checkNameAvailability(String tenant, AzureRequest req) {
        Map<String, Object> body;
        try {
            body = ArmJson.parseBodyLenient(req);
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent", "The request content was invalid and could not be deserialized.");
        }
        String name = ArmJson.string(body, "name", "");
        Map<String, Object> result = new LinkedHashMap<>();
        if (!NAME.matcher(name).matches() || RESERVED_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            result.put("nameAvailable", false);
            result.put("reason", "Invalid");
            result.put("message", "The management group name is invalid.");
        } else if (store.get(tenant, name).isPresent()) {
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

    private Response settings(String tenant, String name, String method, boolean listing) {
        if (store.get(tenant, name).isEmpty()) {
            return notFound(name);
        }
        Map<String, Object> setting = new LinkedHashMap<>();
        setting.put("id", "/providers/Microsoft.Management/managementGroups/" + name + "/settings/default");
        setting.put("type", "Microsoft.Management/managementGroups/settings");
        setting.put("name", "default");
        setting.put("properties", Map.of("requireAuthorizationForGroupCreation", false,
            "defaultManagementGroup", store.root(tenant).id()));
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
    private Map<String, Object> representation(String tenant, Group group) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("version", group.version());
        details.put("updatedTime", group.updatedTime().toString());
        details.put("updatedBy", group.updatedBy());
        Group parent = group.parent() == null ? null : store.get(tenant, group.parent()).orElse(null);
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

    private List<Map<String, Object>> children(String tenant, Group group, boolean recurse) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Group child : store.childGroups(tenant, group.name())) {
            Map<String, Object> info = childInfo(child);
            if (recurse) {
                info.put("children", children(tenant, child, true));
            }
            out.add(info);
        }
        for (Subscription subscription : store.childSubscriptions(tenant, group.name())) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", SUBSCRIPTION_TYPE);
            info.put("id", "/subscriptions/" + subscription.id());
            info.put("name", subscription.id());
            info.put("displayName", subscription.displayName());
            out.add(info);
        }
        return out;
    }

    private static Map<String, Object> childInfo(Group group) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("type", TYPE);
        info.put("id", group.id());
        info.put("name", group.name());
        info.put("displayName", group.displayName());
        return info;
    }

    private List<Map<String, Object>> pathTo(String tenant, Group group) {
        List<Map<String, Object>> path = new ArrayList<>();
        List<Group> ancestors = store.ancestors(tenant, group.name());
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            path.add(Map.of("name", ancestors.get(i).name(), "displayName", ancestors.get(i).displayName()));
        }
        path.add(Map.of("name", group.name(), "displayName", group.displayName()));
        return path;
    }

    private List<Map<String, Object>> ancestorsChain(String tenant, Group group) {
        List<Map<String, Object>> chain = new ArrayList<>();
        for (Group ancestor : store.ancestors(tenant, group.name())) {
            chain.add(Map.of("name", ancestor.name(), "displayName", ancestor.displayName()));
        }
        return chain;
    }

    /** The ancestors chain of a subscription, direct parent first, in the shape Resource Graph publishes. */
    public List<Map<String, Object>> ancestorsChainOf(String subscriptionId) {
        String tenant = directory.tenantOfSubscription(subscriptionId);
        Group parent = store.parentOf(tenant, subscriptionId);
        List<Map<String, Object>> chain = new ArrayList<>();
        chain.add(Map.of("name", parent.name(), "displayName", parent.displayName()));
        chain.addAll(ancestorsChain(tenant, parent));
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

    private int countDescendants(String tenant, Group group) {
        int count = store.childSubscriptions(tenant, group.name()).size();
        for (Group child : store.childGroups(tenant, group.name())) {
            count += 1 + countDescendants(tenant, child);
        }
        return count;
    }

    /** The tenant whose hierarchy the caller works on: the token's tenant when the directory holds it, else the default. */
    private String callerTenantId(AzureRequest req) {
        String tokenTenantId = req.scope() == null ? null : req.scope().tokenTenantId();
        return directory.tenant(tokenTenantId).map(Tenant::id).orElse(directory.defaultTenantId());
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
