package io.floci.az.services.authorization;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.ArmRouter;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmTypeRegistry;
import io.floci.az.core.arm.ResourceGroupCleanup;
import io.floci.az.core.auth.BearerClaims;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.Tenant;
import io.floci.az.services.authorization.RbacPath.ScopeKind;
import io.floci.az.services.authorization.RbacPath.Type;
import io.floci.az.services.authorization.RoleDefinitionCatalog.Permission;
import io.floci.az.services.authorization.RoleDefinitionCatalog.RoleDefinition;
import io.floci.az.services.entra.DirectoryReader;
import io.floci.az.services.managedidentity.ManagedIdentityStore;
import io.floci.az.services.managementgroups.ManagementGroupStore;
import io.floci.az.services.managementgroups.ManagementGroupStore.Group;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Azure role-based access control under {@code Microsoft.Authorization}: Azure's built-in role definitions, read-only,
 * and the role assignments clients create, at any scope from the tenant root to a resource. Assignments are stored and
 * answered as Azure answers them; nothing evaluates them, so they grant and deny nothing.
 *
 * <p>The contract is the one clients pin, assignments at {@code 2022-04-01} and definitions at
 * {@code 2022-05-01-preview} (azurerm, the az CLI and azure-mgmt-authorization all send that pair). Where the
 * references do not settle an answer, the code says so.</p>
 *
 * <p>One deliberate deviation: Azure refuses an assignment whose principal is not in the directory
 * ({@code 400 PrincipalNotFound}). floci-az accepts any principal at every parity level, because it cannot resolve
 * every principal Azure would (a system-assigned identity's object id is derived, not stored) and azurerm retries
 * that error for 30 minutes.</p>
 */
@ApplicationScoped
public class RbacHandler implements AzureServiceHandler, Resettable, ResourceGroupCleanup {

    private static final Logger LOG = Logger.getLogger(RbacHandler.class);

    static final String SERVICE_TYPE = "rbac";
    private static final String DEFINITIONS_SEGMENT = "/providers/Microsoft.Authorization/roleDefinitions/";
    /** The first api-version whose role definition permissions carry {@code condition} and {@code conditionVersion}. */
    private static final String PERMISSION_CONDITION_VERSION = "2022-05-01-preview";
    private static final Pattern FILTER_EQUALS = Pattern.compile("(\\w+)\\s+eq\\s+'([^']*)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSIGNED_TO = Pattern.compile("assignedTo\\('([^']*)'\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBSCRIPTION_SCOPE = Pattern.compile("subscriptions/([^/]+)(?:/.*)?");
    private static final Pattern MANAGEMENT_GROUP_SCOPE =
            Pattern.compile("providers/microsoft\\.management/managementgroups/([^/]+)(?:/.*)?");
    private static final String MANAGEMENT_GROUP_PREFIX = "providers/microsoft.management/managementgroups/";
    /**
     * Internal: the tenant an assignment at a management group or the tenant root was made in. floci-az keeps one
     * management-group hierarchy per tenant, and those scopes' ids do not name the tenant. Never answered.
     */
    private static final String TENANT_KEY = "_tenant";
    private static final Pattern GUID_AFTER_DEFINITIONS =
            Pattern.compile("(?i).*/roleDefinitions/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/?");
    /** An assignment's properties, in the order Azure writes them; every one is present, absent ones as null. */
    private static final List<String> ASSIGNMENT_PROPERTIES = List.of("roleDefinitionId", "principalId",
            "principalType", "scope", "condition", "conditionVersion", "createdOn", "updatedOn", "createdBy",
            "updatedBy", "delegatedManagedIdentityResourceId", "description");

    private final EmulatorConfig config;
    private final RoleAssignmentStore assignments;
    private final DirectoryReader directory;
    private final ManagedIdentityStore identities;
    private final ArmTypeRegistry types;
    private final Instance<ArmRouter> router;
    private final AzureDirectory tenants;
    private final ManagementGroupStore managementGroups;

    @Inject
    public RbacHandler(EmulatorConfig config, RoleAssignmentStore assignments, DirectoryReader directory,
                       ManagedIdentityStore identities, ArmTypeRegistry types, Instance<ArmRouter> router,
                       AzureDirectory tenants, ManagementGroupStore managementGroups) {
        this.config = config;
        this.assignments = assignments;
        this.directory = directory;
        this.identities = identities;
        this.types = types;
        this.router = router;
        this.tenants = tenants;
        this.managementGroups = managementGroups;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().authorization().enabled();
    }

    /**
     * Guarded: Microsoft.Authorization also owns policy resources (served by {@code PolicyHandler}), locks and deny
     * assignments. Only paths whose last {@code Microsoft.Authorization} segment names a role type are claimed. A
     * guarded route is tried before the unguarded ones, which is what keeps a role path on a registry or a vault out
     * of that resource's own handler.
     */
    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .provider("Microsoft.Authorization", RbacPath::isRbacPath)
                .build();
    }

    @Override
    public Response handle(AzureRequest req) {
        String method = req.method().toUpperCase(Locale.ROOT);
        String path = RbacPath.stripQuery(req.resourcePath());
        RbacPath parsed = RbacPath.parse(path);
        if (parsed == null || parsed.type() == null || !parsed.remainder().isEmpty()
                || parsed.scopeKind() == ScopeKind.UNKNOWN) {
            return ArmErrors.error(404, "ResourceNotFound", "Unsupported Microsoft.Authorization path: " + path);
        }
        try {
            return switch (parsed.type()) {
                case ROLE_DEFINITION -> handleDefinition(req, parsed, method);
                case ROLE_ASSIGNMENT -> handleAssignment(req, parsed, method);
            };
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The request content was invalid and could not be deserialized.");
        }
    }

    @Override
    public void clear() {
        assignments.clear();
    }

    /** A resource group's assignments, and those on its resources, go with it, as Azure's extension resources do. */
    @Override
    public void resourceGroupDeleted(String subscriptionId, String resourceGroup) {
        assignments.removeAtOrBelow("/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup);
    }

    // ── Role definitions ─────────────────────────────────────────────────────────

    private Response handleDefinition(AzureRequest req, RbacPath path, String method) {
        if (path.name() == null) {
            return "GET".equals(method) ? listDefinitions(req, path) : methodNotAllowed(method);
        }
        return switch (method) {
            case "GET" -> RoleDefinitionCatalog.byGuid(path.name())
                    .map(role -> json(200, definitionBody(role, path, apiVersion(req))))
                    .orElseGet(() -> definitionNotFound(path.name()));
            // Custom roles are not emulated; an honest refusal beats a stored role nothing else knows about.
            case "PUT", "DELETE" -> ArmErrors.error(501, "NotImplemented",
                    "floci-az serves Azure's built-in role definitions only; custom role definitions are not supported.");
            default -> methodNotAllowed(method);
        };
    }

    /**
     * The built-ins assignable at the scope. {@code roleName eq} matches ignoring case, as Azure does (a recorded
     * {@code roleName eq 'reader'} answers Reader), and {@code type eq} matches the role type. Other filter terms,
     * {@code atScopeAndBelow()} among them, change nothing: there are no custom roles below any scope.
     */
    private Response listDefinitions(AzureRequest req, RbacPath path) {
        String roleName = null;
        String roleType = null;
        Matcher m = FILTER_EQUALS.matcher(filterOf(req));
        while (m.find()) {
            switch (m.group(1).toLowerCase(Locale.ROOT)) {
                case "rolename" -> roleName = m.group(2);
                case "type" -> roleType = m.group(2);
                default -> {
                    // Ignored, as Policy ignores filter keys it does not know.
                }
            }
        }
        String apiVersion = apiVersion(req);
        List<Map<String, Object>> value = new ArrayList<>();
        for (RoleDefinition role : RoleDefinitionCatalog.all()) {
            if ((roleName == null || roleName.equalsIgnoreCase(role.roleName()))
                    && (roleType == null || roleType.equalsIgnoreCase(role.roleType()))
                    && assignableAt(role, path)) {
                value.add(definitionBody(role, path, apiVersion));
            }
        }
        return json(200, Map.of("value", value));
    }

    private static boolean assignableAt(RoleDefinition role, RbacPath path) {
        String scope = path.scopeId().toLowerCase(Locale.ROOT);
        for (String assignable : role.assignableScopes()) {
            String key = assignable.toLowerCase(Locale.ROOT);
            if ("/".equals(key) || scope.equals(key) || scope.startsWith(key + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A built-in role read at a scope. Inside a subscription Azure answers the subscription form of the id
     * ({@code /subscriptions/{sub}/providers/Microsoft.Authorization/roleDefinitions/{guid}}, recorded at
     * subscription scope), and clients rely on it: the az CLI maps assignments to role names by comparing the two
     * ids exactly. At the tenant root and a management group the id has no subscription; what Azure writes there is
     * not recorded. The dates are not in the dataset the roles come from, so they are null.
     */
    private static Map<String, Object> definitionBody(RoleDefinition role, RbacPath path, String apiVersion) {
        String subscription = path.subscriptionId();
        String prefix = subscription == null ? "" : "/subscriptions/" + subscription;
        boolean withConditions = apiVersion == null || apiVersion.compareTo(PERMISSION_CONDITION_VERSION) >= 0;
        List<Map<String, Object>> permissions = new ArrayList<>();
        for (Permission permission : role.permissions()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("actions", permission.actions());
            entry.put("notActions", permission.notActions());
            entry.put("dataActions", permission.dataActions());
            entry.put("notDataActions", permission.notDataActions());
            if (withConditions) {
                entry.put("condition", permission.condition());
                entry.put("conditionVersion", permission.conditionVersion());
            }
            permissions.add(entry);
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("roleName", role.roleName());
        properties.put("type", role.roleType());
        properties.put("description", role.description());
        properties.put("assignableScopes", role.assignableScopes());
        properties.put("permissions", permissions);
        properties.put("createdOn", null);
        properties.put("updatedOn", null);
        properties.put("createdBy", null);
        properties.put("updatedBy", null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", prefix + DEFINITIONS_SEGMENT + role.name());
        body.put("type", Type.ROLE_DEFINITION.armType());
        body.put("name", role.name());
        body.put("properties", properties);
        return body;
    }

    /** The code Azure answers for an unknown role is not in the spec, the RBAC docs or the SDKs. */
    private static Response definitionNotFound(String guid) {
        return ArmErrors.error(404, "RoleDefinitionDoesNotExist",
                "The specified role definition with ID '" + guid + "' does not exist.");
    }

    // ── Role assignments ─────────────────────────────────────────────────────────

    private Response handleAssignment(AzureRequest req, RbacPath path, String method) {
        if (path.name() == null) {
            return "GET".equals(method) ? listAssignments(req, path) : methodNotAllowed(method);
        }
        String id = path.resourceId();
        return switch (method) {
            case "GET" -> assignments.get(id)
                    .map(body -> json(200, complete(body)))
                    .orElseGet(() -> assignmentNotFound(path.name()));
            case "PUT" -> putAssignment(req, path, id);
            // 200 with the deleted assignment, 204 when there is none (RoleAssignments_Delete, 2022-04-01).
            case "DELETE" -> assignments.remove(id)
                    .map(body -> json(200, complete(body)))
                    .orElseGet(() -> Response.noContent().build());
            default -> methodNotAllowed(method);
        };
    }

    private Response putAssignment(AzureRequest req, RbacPath path, String id) {
        Map<String, Object> props = ArmJson.cast(ArmJson.parseBodyStrict(req).get("properties"));
        String roleDefinitionId = ArmJson.string(props, "roleDefinitionId", null);
        String principalId = ArmJson.string(props, "principalId", null);
        if (roleDefinitionId == null || roleDefinitionId.isBlank()) {
            return ArmErrors.error(400, "InvalidRequestContent", "The role assignment must name a roleDefinitionId.");
        }
        if (principalId == null || principalId.isBlank()) {
            return ArmErrors.error(400, "InvalidRequestContent", "The role assignment must name a principalId.");
        }
        Optional<String> roleGuid = roleGuid(roleDefinitionId);
        if (roleGuid.flatMap(RoleDefinitionCatalog::byGuid).isEmpty()) {
            // The status and code Azure answers here are not in the spec, the RBAC docs or the SDKs.
            return ArmErrors.error(400, "RoleDefinitionDoesNotExist",
                    "The specified role definition with ID '" + roleGuid.orElse(roleDefinitionId)
                            + "' does not exist.");
        }
        Response missingScope = missingScopeResource(req, path);
        if (missingScope != null) {
            return missingScope;
        }
        String caller = callerObjectId(req);
        return assignments.atomically(() -> {
            Optional<Map<String, Object>> existing = assignments.get(id);
            Response conflict = conflict(path, id, principalId, roleGuid.get(), existing, callerTenantId(req));
            if (conflict != null) {
                return conflict;
            }
            Map<String, Object> previous = existing.map(RbacHandler::propertiesOf).orElse(Map.of());
            String now = Instant.now().toString();
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("roleDefinitionId", roleDefinitionId);
            properties.put("principalId", principalId);
            properties.put("principalType", principalType(props, previous, principalId, directoryTenant(req, path)));
            properties.put("scope", path.scopeId());
            properties.put("condition", props.get("condition"));
            properties.put("conditionVersion", props.get("conditionVersion"));
            properties.put("createdOn", previous.getOrDefault("createdOn", now));
            properties.put("updatedOn", now);
            properties.put("createdBy", existing.isPresent() ? previous.get("createdBy") : caller);
            properties.put("updatedBy", caller);
            properties.put("delegatedManagedIdentityResourceId", props.get("delegatedManagedIdentityResourceId"));
            properties.put("description", props.get("description"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", id);
            body.put("type", Type.ROLE_ASSIGNMENT.armType());
            body.put("name", path.name());
            body.put("properties", properties);
            if (path.scopeKind() == ScopeKind.TENANT || path.scopeKind() == ScopeKind.MANAGEMENT_GROUP) {
                body.put(TENANT_KEY, callerTenantId(req));
            }
            assignments.put(body);
            return json(existing.isEmpty() ? 201 : 200, complete(body));
        });
    }

    /**
     * The two rules that span assignments. A name is unique across every scope, and an assignment's principal and
     * scope cannot change: {@code 400 RoleAssignmentUpdateNotPermitted} ("Tenant ID, application ID, principal ID, and
     * scope are not allowed to be updated", the RBAC troubleshooting docs). The same principal, role and scope under
     * a second name is {@code 409 RoleAssignmentExists} (recorded by the az CLI tests, which treat it as success).
     * The tenant is in the docs' list of what cannot be updated, so an assignment at a tenant-bound scope keeps the
     * tenant that created it.
     */
    private Response conflict(RbacPath path, String id, String principalId, String roleGuid,
                              Optional<Map<String, Object>> existing, String callerTenant) {
        if (existing.isPresent() && !principalId.equalsIgnoreCase(
                String.valueOf(propertiesOf(existing.get()).get("principalId")))) {
            return updateNotPermitted();
        }
        // The tenant cannot change either: at a management group or the tenant root, another tenant's re-PUT of the
        // same id would otherwise move the assignment into its own hierarchy.
        boolean tenantBound = path.scopeKind() == ScopeKind.TENANT || path.scopeKind() == ScopeKind.MANAGEMENT_GROUP;
        if (existing.isPresent() && tenantBound) {
            String recorded = existing.get().get(TENANT_KEY) instanceof String t ? t : tenants.defaultTenantId();
            if (!recorded.equalsIgnoreCase(callerTenant)) {
                return updateNotPermitted();
            }
        }
        String scopeKey = qualified(path.scopeKey(), callerTenant);
        for (Map<String, Object> other : assignments.all()) {
            if (String.valueOf(other.get("id")).equalsIgnoreCase(id)) {
                continue;
            }
            if (path.name().equalsIgnoreCase(String.valueOf(other.get("name")))) {
                return updateNotPermitted();
            }
            Map<String, Object> props = propertiesOf(other);
            String otherTenant = other.get(TENANT_KEY) instanceof String t ? t : tenants.defaultTenantId();
            String otherScope = stripLeadingSlash(String.valueOf(props.get("scope")).toLowerCase(Locale.ROOT));
            if (principalId.equalsIgnoreCase(String.valueOf(props.get("principalId")))
                    && scopeKey.equals(qualified(otherScope, otherTenant))
                    && roleGuid(String.valueOf(props.get("roleDefinitionId"))).map(roleGuid::equalsIgnoreCase)
                            .orElse(false)) {
                return ArmErrors.error(409, "RoleAssignmentExists", "The role assignment already exists.");
            }
        }
        return null;
    }

    private static Response updateNotPermitted() {
        return ArmErrors.error(400, "RoleAssignmentUpdateNotPermitted",
                "Tenant ID, application ID, principal ID, and scope are not allowed to be updated.");
    }

    /**
     * Under {@code strict-resource-groups}, an assignment on a resource that does not exist is refused, as Azure
     * refuses it. The resource is read through the router, the way a client would read it, at the newest
     * api-version its type has; a type floci-az does not serve cannot be checked, so it is accepted.
     */
    private Response missingScopeResource(AzureRequest req, RbacPath path) {
        if (!config.strictResourceGroups() || path.scopeKind() != ScopeKind.RESOURCE) {
            return null;
        }
        Optional<ArmResourceId> scope = ArmResourceId.parse(path.scopeId());
        ArmResourceType type = scope.map(types::match).orElse(null);
        if (type == null || types.apiVersions(type).isEmpty()) {
            return null;
        }
        Response read = router.get().dispatchInternal("GET", scope.get().id(),
                Map.of("api-version", types.apiVersions(type).getFirst()), req);
        if (read.getStatus() != 404) {
            return null;
        }
        ArmResourceId resource = scope.get();
        LOG.infov("Role assignment on missing resource {0} refused: floci-az.parity-overrides.strict-resource-groups"
                + " is on (or turn the override off)", resource.id());
        String named = resource.typePath() + "/" + String.join("/", resource.names());
        return ArmErrors.error(404, "ResourceNotFound", resource.resourceGroup() == null
                ? "The Resource '" + named + "' was not found."
                : "The Resource '" + named + "' under resource group '" + resource.resourceGroup() + "' was not found.");
    }

    /**
     * The principal's type: what the client sent, else what the assignment already said, else what the directory
     * knows the principal as (Azure resolves it from Entra ID). The directory searched is the tenant's that owns the
     * scope's subscription. A principal floci-az does not know stays null.
     */
    private String principalType(Map<String, Object> props, Map<String, Object> previous, String principalId,
                                 String tenant) {
        String requested = ArmJson.string(props, "principalType", null);
        if (requested != null) {
            return requested;
        }
        if (previous.get("principalType") instanceof String kept) {
            return kept;
        }
        if (directory.user(tenant, principalId).isPresent()) {
            return "User";
        }
        if (directory.group(tenant, principalId).isPresent()) {
            return "Group";
        }
        if (directory.servicePrincipal(tenant, principalId).isPresent()
                || identities.findByPrincipalId(principalId).isPresent()) {
            return "ServicePrincipal";
        }
        return null;
    }

    /**
     * {@code atScope()} lists the assignments at or above the scope; {@code principalId eq '{id}'} and
     * {@code assignedTo('{id}')} those of one principal at, above or below it (2022-04-01); no filter lists at, above
     * and below. Above and below follow the scope hierarchy: a resource sits in its group and subscription, and a
     * subscription in its management group and that group's ancestors, up to the tenant root.
     * {@code assignedTo} does not expand group membership here. Other terms are ignored.
     */
    private Response listAssignments(AzureRequest req, RbacPath path) {
        String filter = filterOf(req);
        boolean atScope = filter.toLowerCase(Locale.ROOT).contains("atscope()");
        String principal = null;
        Matcher equals = FILTER_EQUALS.matcher(filter);
        while (equals.find()) {
            if ("principalid".equalsIgnoreCase(equals.group(1))) {
                principal = equals.group(2);
            }
        }
        Matcher assignedTo = ASSIGNED_TO.matcher(filter);
        if (assignedTo.find()) {
            principal = assignedTo.group(1);
        }
        String scope = path.scopeKey();
        String callerTenant = callerTenantId(req);
        Set<String> scopeAndAbove = scopeAndAncestors(scope, callerTenant);
        String qualifiedScope = qualified(scope, callerTenant);
        List<Map<String, Object>> value = new ArrayList<>();
        for (Map<String, Object> assignment : assignments.all()) {
            Map<String, Object> props = propertiesOf(assignment);
            String at = stripLeadingSlash(String.valueOf(props.get("scope")).toLowerCase(Locale.ROOT));
            String atTenant = assignment.get(TENANT_KEY) instanceof String t ? t : tenants.defaultTenantId();
            boolean atOrAbove = scopeAndAbove.contains(qualified(at, atTenant));
            boolean below = !atOrAbove && scopeAndAncestors(at, atTenant).contains(qualifiedScope);
            if (!(atOrAbove || (!atScope && below))) {
                continue;
            }
            if (principal != null && !principal.equalsIgnoreCase(String.valueOf(props.get("principalId")))) {
                continue;
            }
            value.add(complete(assignment));
        }
        return json(200, Map.of("value", value));
    }

    /**
     * {@code scopeKey} and every scope above it, lowercased and without a leading slash: its parent paths (a resource's
     * group and subscription), the subscription's management group and that group's ancestors, and the tenant root.
     * A subscription that was never placed sits under the tenant root group. Management groups and the tenant root are
     * {@link #qualified} with their tenant, since each tenant has its own hierarchy and those ids do not name it: the
     * tenant is the subscription's own for a subscription scope, and {@code scopeTenant} for the rest.
     */
    private Set<String> scopeAndAncestors(String scopeKey, String scopeTenant) {
        Set<String> out = new LinkedHashSet<>();
        String tenant = scopeTenant;
        String group = null;
        Matcher subscription = SUBSCRIPTION_SCOPE.matcher(scopeKey);
        Matcher managementGroup = MANAGEMENT_GROUP_SCOPE.matcher(scopeKey);
        if (subscription.matches()) {
            tenant = tenants.tenantOfSubscription(subscription.group(1));
            group = managementGroups.parentOf(tenant, subscription.group(1)).name();
            StringBuilder prefix = new StringBuilder();
            for (String segment : scopeKey.split("/")) {
                if (!prefix.isEmpty()) {
                    prefix.append('/');
                }
                out.add(prefix.append(segment).toString());
            }
        } else if (managementGroup.matches()) {
            group = managementGroup.group(1);
        }
        out.add(qualified("", tenant));
        if (group != null) {
            out.add(qualified(MANAGEMENT_GROUP_PREFIX + group.toLowerCase(Locale.ROOT), tenant));
            for (Group ancestor : managementGroups.ancestors(tenant, group)) {
                out.add(qualified(MANAGEMENT_GROUP_PREFIX + ancestor.name().toLowerCase(Locale.ROOT), tenant));
            }
        }
        return out;
    }

    /**
     * A scope key as {@link #scopeAndAncestors} compares it: a management group or the tenant root carries its tenant
     * ({@code {tenant}|{key}}); any other scope is unique across tenants as it is.
     */
    private static String qualified(String scopeKey, String tenant) {
        boolean tenantBound = scopeKey.isEmpty() || MANAGEMENT_GROUP_SCOPE.matcher(scopeKey).matches();
        return tenantBound ? tenant.toLowerCase(Locale.ROOT) + "|" + scopeKey : scopeKey;
    }

    /** The directory a principal on this scope is looked up in: the tenant that owns the scope's subscription. */
    private String directoryTenant(AzureRequest req, RbacPath path) {
        String subscription = path.subscriptionId();
        return subscription != null ? tenants.tenantOfSubscription(subscription) : callerTenantId(req);
    }

    /** The token's tenant when the directory holds it, else the default, as the management-group handler reads it. */
    private String callerTenantId(AzureRequest req) {
        String tokenTenantId = req.scope() == null ? null : req.scope().tokenTenantId();
        return tenants.tenant(tokenTenantId).map(Tenant::id).orElse(tenants.defaultTenantId());
    }

    /** The code Azure answers for a missing assignment is not recorded; clients act on the 404. */
    private static Response assignmentNotFound(String name) {
        return ArmErrors.error(404, "RoleAssignmentNotFound", "The role assignment '" + name + "' is not found.");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** An assignment with every property present, absent ones as explicit nulls, as Azure writes them. */
    private static Map<String, Object> complete(Map<String, Object> assignment) {
        Map<String, Object> stored = propertiesOf(assignment);
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String key : ASSIGNMENT_PROPERTIES) {
            properties.put(key, stored.get(key));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", assignment.get("id"));
        body.put("type", assignment.get("type"));
        body.put("name", assignment.get("name"));
        body.put("properties", properties);
        return body;
    }

    private static Optional<String> roleGuid(String roleDefinitionId) {
        Matcher m = GUID_AFTER_DEFINITIONS.matcher(roleDefinitionId);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private static Map<String, Object> propertiesOf(Map<String, Object> resource) {
        return ArmJson.cast(resource.get("properties"));
    }

    private static String callerObjectId(AzureRequest req) {
        BearerClaims claims = BearerClaims.of(req);
        return claims.present() ? claims.objectId() : null;
    }

    private static String filterOf(AzureRequest req) {
        String filter = req.queryParams() == null ? null : req.queryParams().get("$filter");
        return filter == null ? "" : filter;
    }

    private static String apiVersion(AzureRequest req) {
        return req.queryParams() == null ? null : req.queryParams().get("api-version");
    }

    private static String stripLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private static Response json(int status, Object body) {
        return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build();
    }

    private static Response methodNotAllowed(String method) {
        return ArmErrors.error(405, "MethodNotAllowed", "The method '" + method + "' is not allowed here.");
    }
}
