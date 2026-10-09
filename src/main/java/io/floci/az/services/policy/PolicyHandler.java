package io.floci.az.services.policy;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.services.entra.TokenIssuer;
import io.floci.az.services.managedidentity.ManagedIdentityStore;
import io.floci.az.services.policy.PolicyPath.ScopeKind;
import io.floci.az.services.policy.PolicyPath.Type;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Azure Policy control plane under {@code Microsoft.Authorization}: policy definitions, policy set
 * definitions (initiatives), policy assignments and policy exemptions, at every ARM scope the real
 * service accepts. Shapes follow the policy spec {@code 2025-03-01} (exemptions:
 * {@code 2022-07-01-preview}); any {@code api-version} is accepted.
 *
 * <p>This is the control plane only. Definitions are stored and validated structurally, but no
 * policy rule is ever evaluated against resource requests, so nothing is denied, audited or
 * remediated, and there is no compliance state. Built-in definitions are not seeded: the tenant-rooted
 * built-in listings answer an empty collection and built-in ids are accepted in references without
 * validation.</p>
 */
@ApplicationScoped
public class PolicyHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(PolicyHandler.class);

    static final String SERVICE_TYPE = "policy";
    private static final String SCOPE_KEY = "_scope";
    /** Object id reported as the author in {@code metadata} and {@code systemData}, stable across restarts. */
    private static final String AUTHOR = TokenIssuer.deterministicGuid("policy-author");
    private static final Set<String> EXEMPTION_CATEGORIES = Set.of("Waiver", "Mitigated");
    private static final Set<ScopeKind> DEFINITION_SCOPES = EnumSet.of(ScopeKind.MANAGEMENT_GROUP, ScopeKind.SUBSCRIPTION);
    private static final Set<ScopeKind> ASSIGNMENT_SCOPES = EnumSet.of(ScopeKind.MANAGEMENT_GROUP,
            ScopeKind.SUBSCRIPTION, ScopeKind.RESOURCE_GROUP, ScopeKind.RESOURCE);
    private static final Pattern FILTER_EQUALS = Pattern.compile("(\\w+)\\s+eq\\s+'([^']*)'", Pattern.CASE_INSENSITIVE);

    private final EmulatorConfig config;
    private final PolicyStore store;
    private final ManagedIdentityStore identities;

    @Inject
    public PolicyHandler(EmulatorConfig config, PolicyStore store, ManagedIdentityStore identities) {
        this.config = config;
        this.store = store;
        this.identities = identities;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().policy().enabled();
    }

    /**
     * Guarded: Microsoft.Authorization also owns role assignments, locks and deny assignments, which
     * this handler does not serve. Only paths whose last {@code Microsoft.Authorization} segment names a
     * policy resource type are claimed; everything else keeps falling through to the generic ARM handler.
     */
    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .provider("Microsoft.Authorization", PolicyPath::isPolicyPath)
                .build();
    }

    @Override
    public boolean canHandle(AzureRequest request) {
        return SERVICE_TYPE.equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest req) {
        String method = req.method().toUpperCase(Locale.ROOT);
        String path = PolicyPath.stripQuery(req.resourcePath());
        PolicyPath parsed = PolicyPath.parse(path);
        if (parsed == null || parsed.type() == null) {
            return ArmErrors.error(404, "ResourceNotFound", "Unsupported Microsoft.Authorization path: " + path);
        }
        if (!parsed.remainder().isEmpty()) {
            return ArmErrors.error(404, "ResourceNotFound",
                    "Sub-resources of " + parsed.type().armType() + " are not supported: " + path);
        }
        ListFilter filter = ListFilter.parse(req.queryParams() == null ? null : req.queryParams().get("$filter"));
        try {
            return switch (parsed.type()) {
                case DEFINITION -> handleDefinition(req, parsed, method, filter);
                case SET_DEFINITION -> handleSetDefinition(req, parsed, method, filter);
                case ASSIGNMENT -> handleAssignment(req, parsed, method, filter);
                case EXEMPTION -> handleExemption(req, parsed, method, filter);
            };
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The request content was invalid and could not be deserialized.");
        }
    }

    @Override
    public void clear() {
        store.clear();
    }

    // ── Policy definitions ──────────────────────────────────────────────────────

    private Response handleDefinition(AzureRequest req, PolicyPath path, String method, ListFilter filter) {
        ScopeKind kind = path.scopeKind();
        if (kind == ScopeKind.TENANT) {
            return handleBuiltIn(path, method, "PolicyDefinitionNotFound",
                    "The policy definition '" + path.name() + "' could not be found.");
        }
        if (!DEFINITION_SCOPES.contains(kind)) {
            return unsupportedScope(path);
        }
        if (path.name() == null) {
            if (!"GET".equals(method)) {
                return methodNotAllowed(method);
            }
            return listResponse(store.listDefinitions(), path, filter, true);
        }
        String id = path.resourceId();
        return switch (method) {
            case "PUT" -> putDefinition(req, path, id);
            case "GET" -> {
                Map<String, Object> existing = store.getDefinition(id);
                yield existing == null
                        ? ArmErrors.error(404, "PolicyDefinitionNotFound",
                                "The policy definition '" + path.name() + "' could not be found.")
                        : Response.ok(strip(existing)).build();
            }
            case "DELETE" -> deleteDefinition(path, id);
            default -> methodNotAllowed(method);
        };
    }

    private Response putDefinition(AzureRequest req, PolicyPath path, String id) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Map<String, Object> props = ArmJson.cast(body.get("properties"));
        Object rule = props.get("policyRule");
        if (rule == null) {
            return ArmErrors.error(400, "InvalidCreatePolicyDefinitionRequest",
                    "The policy definition '" + path.name() + "' create request is invalid. "
                            + "The 'policyRule' property is required.");
        }
        if (!(rule instanceof Map<?, ?> ruleMap)
                || !(ruleMap.get("if") instanceof Map<?, ?>)
                || !(ruleMap.get("then") instanceof Map<?, ?> then)
                || !(then.get("effect") instanceof String)) {
            return ArmErrors.error(400, "InvalidPolicyRule",
                    "Failed to parse policy rule: the rule must be an object with an 'if' condition "
                            + "and a 'then' block that names an 'effect'.");
        }
        Map<String, Object> existing = store.getDefinition(id);

        Map<String, Object> properties = new LinkedHashMap<>();
        putIfString(properties, "displayName", props);
        properties.put("policyType", "Custom");
        properties.put("mode", ArmJson.string(props, "mode", "Indexed"));
        putIfString(properties, "description", props);
        properties.put("metadata", auditedMetadata(props.get("metadata"), existing));
        properties.put("version", ArmJson.string(props, "version", "1.0.0"));
        putIfMap(properties, "parameters", props);
        properties.put("policyRule", rule);

        Map<String, Object> resource = envelope(path, id, properties, existing);
        store.putDefinition(id, resource);
        // Per the policy spec, CreateOrUpdate on a definition answers 201 for both create and update.
        return Response.status(201).entity(strip(resource)).build();
    }

    private Response deleteDefinition(PolicyPath path, String id) {
        if (store.getDefinition(id) == null) {
            return Response.status(204).build();
        }
        Optional<String> set = store.findSetDefinitionReferencing(id);
        if (set.isPresent()) {
            return ArmErrors.error(400, "InvalidDeletePolicyDefinitionRequest",
                    "The policy definition '" + path.name() + "' cannot be deleted. It is referenced by the "
                            + "policy set definition '" + set.get() + "'. Please remove this policy definition "
                            + "from all policy set definitions that reference it.");
        }
        Optional<String> assignment = store.findAssignmentReferencing(id);
        if (assignment.isPresent()) {
            return ArmErrors.error(400, "InvalidDeletePolicyDefinitionRequest",
                    "The policy definition '" + path.name() + "' cannot be deleted. It is referenced by the "
                            + "policy assignment '" + assignment.get() + "'. Please remove this policy assignment "
                            + "before deleting the policy definition.");
        }
        store.removeDefinition(id);
        return Response.ok().build();
    }

    // ── Policy set definitions ──────────────────────────────────────────────────

    private Response handleSetDefinition(AzureRequest req, PolicyPath path, String method, ListFilter filter) {
        ScopeKind kind = path.scopeKind();
        if (kind == ScopeKind.TENANT) {
            return handleBuiltIn(path, method, "PolicySetDefinitionNotFound",
                    "The policy set definition '" + path.name() + "' could not be found.");
        }
        if (!DEFINITION_SCOPES.contains(kind)) {
            return unsupportedScope(path);
        }
        if (path.name() == null) {
            if (!"GET".equals(method)) {
                return methodNotAllowed(method);
            }
            return listResponse(store.listSetDefinitions(), path, filter, true);
        }
        String id = path.resourceId();
        return switch (method) {
            case "PUT" -> putSetDefinition(req, path, id);
            case "GET" -> {
                Map<String, Object> existing = store.getSetDefinition(id);
                yield existing == null
                        ? ArmErrors.error(404, "PolicySetDefinitionNotFound",
                                "The policy set definition '" + path.name() + "' could not be found.")
                        : Response.ok(strip(existing)).build();
            }
            case "DELETE" -> deleteSetDefinition(path, id);
            default -> methodNotAllowed(method);
        };
    }

    private Response putSetDefinition(AzureRequest req, PolicyPath path, String id) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Map<String, Object> props = ArmJson.cast(body.get("properties"));
        if (!(props.get("policyDefinitions") instanceof List<?> references) || references.isEmpty()) {
            return ArmErrors.error(400, "InvalidCreatePolicySetDefinitionRequest",
                    "The policy set definition '" + path.name() + "' create request is invalid. "
                            + "At least one policy definition must be referenced.");
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object element : references) {
            if (!(element instanceof Map<?, ?> raw)
                    || !(raw.get("policyDefinitionId") instanceof String definitionId)
                    || definitionId.isBlank()) {
                return ArmErrors.error(400, "InvalidPolicyDefinitionReference",
                        "The policy set definition '" + path.name() + "' references a policy definition "
                                + "without a 'policyDefinitionId'.");
            }
            Response missing = requireDefinition(definitionId);
            if (missing != null) {
                return missing;
            }
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("policyDefinitionReferenceId",
                    raw.get("policyDefinitionReferenceId") instanceof String refId && !refId.isBlank()
                            ? refId : generatedReferenceId());
            reference.put("policyDefinitionId", definitionId);
            reference.put("definitionVersion",
                    raw.get("definitionVersion") instanceof String version ? version : "1.*.*");
            if (raw.get("parameters") instanceof Map<?, ?> parameters) {
                reference.put("parameters", parameters);
            }
            if (raw.get("groupNames") instanceof List<?> groupNames) {
                reference.put("groupNames", groupNames);
            }
            normalized.add(reference);
        }
        Map<String, Object> existing = store.getSetDefinition(id);

        Map<String, Object> properties = new LinkedHashMap<>();
        putIfString(properties, "displayName", props);
        properties.put("policyType", "Custom");
        putIfString(properties, "description", props);
        properties.put("metadata", auditedMetadata(props.get("metadata"), existing));
        properties.put("version", ArmJson.string(props, "version", "1.0.0"));
        putIfMap(properties, "parameters", props);
        properties.put("policyDefinitions", normalized);
        putIfList(properties, "policyDefinitionGroups", props);

        Map<String, Object> resource = envelope(path, id, properties, existing);
        store.putSetDefinition(id, resource);
        return Response.status(existing == null ? 201 : 200).entity(strip(resource)).build();
    }

    private Response deleteSetDefinition(PolicyPath path, String id) {
        if (store.getSetDefinition(id) == null) {
            return Response.status(204).build();
        }
        Optional<String> assignment = store.findAssignmentReferencing(id);
        if (assignment.isPresent()) {
            return ArmErrors.error(400, "InvalidDeletePolicySetDefinitionRequest",
                    "The policy set definition '" + path.name() + "' cannot be deleted. It is referenced by "
                            + "the policy assignment '" + assignment.get() + "'. Please remove this policy "
                            + "assignment before deleting the policy set definition.");
        }
        store.removeSetDefinition(id);
        return Response.ok().build();
    }

    // ── Policy assignments ──────────────────────────────────────────────────────

    private Response handleAssignment(AzureRequest req, PolicyPath path, String method, ListFilter filter) {
        Response scopeError = requireAssignmentScope(path);
        if (scopeError != null) {
            return scopeError;
        }
        if (path.name() == null) {
            if (!"GET".equals(method)) {
                return methodNotAllowed(method);
            }
            return listResponse(store.listAssignments(), path, filter, false);
        }
        String id = path.resourceId();
        return switch (method) {
            case "PUT" -> putAssignment(req, path, id);
            case "PATCH" -> patchAssignment(req, path, id);
            case "GET" -> {
                Map<String, Object> existing = store.getAssignment(id);
                yield existing == null ? assignmentNotFound(path.name()) : Response.ok(strip(existing)).build();
            }
            case "DELETE" -> {
                Map<String, Object> existing = store.getAssignment(id);
                if (existing == null) {
                    yield Response.status(204).build();
                }
                // Exemptions are bound to their assignment and go with it, as on Azure.
                store.removeExemptionsForAssignment(id);
                store.removeAssignment(id);
                yield Response.ok(strip(existing)).build();
            }
            default -> methodNotAllowed(method);
        };
    }

    private Response putAssignment(AzureRequest req, PolicyPath path, String id) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Map<String, Object> props = ArmJson.cast(body.get("properties"));
        String definitionId = ArmJson.string(props, "policyDefinitionId", null);
        if (definitionId == null || definitionId.isBlank()) {
            return ArmErrors.error(400, "InvalidCreatePolicyAssignmentRequest",
                    "The policy assignment '" + path.name() + "' create request is invalid. "
                            + "A 'policyDefinitionId' must be provided.");
        }
        Response missing = definitionId.toLowerCase(Locale.ROOT).contains("/policysetdefinitions/")
                ? requireSetDefinition(definitionId)
                : requireDefinition(definitionId);
        if (missing != null) {
            return missing;
        }
        Map<String, Object> existing = store.getAssignment(id);

        Map<String, Object> resource = new LinkedHashMap<>();
        Map<String, Object> identity = buildIdentity(body.get("identity"), existing);
        if (identity != null) {
            resource.put("identity", identity);
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        putIfString(properties, "displayName", props);
        properties.put("policyDefinitionId", definitionId);
        properties.put("definitionVersion", ArmJson.string(props, "definitionVersion", "1.*.*"));
        properties.put("scope", "/" + path.scope());
        putIfList(properties, "notScopes", props);
        putIfMap(properties, "parameters", props);
        putIfString(properties, "description", props);
        properties.put("metadata", auditedMetadata(props.get("metadata"), existing));
        properties.put("enforcementMode", ArmJson.string(props, "enforcementMode", "Default"));
        putIfList(properties, "nonComplianceMessages", props);
        putIfList(properties, "resourceSelectors", props);
        putIfList(properties, "overrides", props);
        properties.put("instanceId", existing == null
                ? UUID.randomUUID().toString()
                : propertiesOf(existing).get("instanceId"));

        resource.put("properties", properties);
        resource.put("id", id);
        resource.put("type", path.type().armType());
        resource.put("name", path.name());
        if (body.get("location") instanceof String location) {
            resource.put("location", location);
        }
        resource.put("systemData", systemData(existing));
        resource.put(SCOPE_KEY, path.scopeKey());
        store.putAssignment(id, resource);
        // Per the policy spec, Create on an assignment answers 201 for both create and update.
        return Response.status(201).entity(strip(resource)).build();
    }

    /** PATCH updates only the identity, location, resource selectors and overrides (PolicyAssignmentUpdate). */
    private Response patchAssignment(AzureRequest req, PolicyPath path, String id) {
        Map<String, Object> existing = store.getAssignment(id);
        if (existing == null) {
            return assignmentNotFound(path.name());
        }
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Map<String, Object> patch = ArmJson.cast(body.get("properties"));

        Map<String, Object> updated = new LinkedHashMap<>();
        Object identity = body.containsKey("identity") ? buildIdentity(body.get("identity"), existing) : existing.get("identity");
        if (identity != null) {
            updated.put("identity", identity);
        }
        Map<String, Object> properties = new LinkedHashMap<>(propertiesOf(existing));
        if (patch.get("resourceSelectors") instanceof List<?> selectors) {
            properties.put("resourceSelectors", selectors);
        }
        if (patch.get("overrides") instanceof List<?> overrides) {
            properties.put("overrides", overrides);
        }
        updated.put("properties", properties);
        updated.put("id", existing.get("id"));
        updated.put("type", existing.get("type"));
        updated.put("name", existing.get("name"));
        Object location = body.get("location") instanceof String given ? given : existing.get("location");
        if (location != null) {
            updated.put("location", location);
        }
        updated.put("systemData", systemData(existing));
        updated.put(SCOPE_KEY, existing.get(SCOPE_KEY));
        store.putAssignment(id, updated);
        return Response.ok(strip(updated)).build();
    }

    /**
     * Managed identity for an assignment. System-assigned identities get a server-generated principal
     * that stays stable across updates; user-assigned identities resolve their principal and client ids
     * from the Managed Identity store when the identity exists there, and get deterministic ids otherwise.
     */
    private Map<String, Object> buildIdentity(Object given, Map<String, Object> existing) {
        if (!(given instanceof Map<?, ?> requested)) {
            return null;
        }
        String type = requested.get("type") instanceof String value ? value : "None";
        Map<String, Object> identity = new LinkedHashMap<>();
        if ("SystemAssigned".equalsIgnoreCase(type)) {
            Map<?, ?> previous = existing != null && existing.get("identity") instanceof Map<?, ?> map ? map : Map.of();
            String principalId = "SystemAssigned".equalsIgnoreCase(String.valueOf(previous.get("type")))
                    && previous.get("principalId") instanceof String kept
                    ? kept : UUID.randomUUID().toString();
            identity.put("principalId", principalId);
            identity.put("tenantId", config.services().entra().defaultTenantId());
            identity.put("type", "SystemAssigned");
            return identity;
        }
        if ("UserAssigned".equalsIgnoreCase(type)) {
            identity.put("type", "UserAssigned");
            Map<String, Object> userAssigned = new LinkedHashMap<>();
            if (requested.get("userAssignedIdentities") instanceof Map<?, ?> ids) {
                for (Object key : ids.keySet()) {
                    String resourceId = String.valueOf(key);
                    userAssigned.put(resourceId, identities.findByResourceId(resourceId)
                            .map(PolicyHandler::identityIds)
                            .orElseGet(() -> Map.<String, Object>of(
                                    "principalId", TokenIssuer.deterministicGuid("policy-uai-principal:" + resourceId.toLowerCase(Locale.ROOT)),
                                    "clientId", TokenIssuer.deterministicGuid("policy-uai-client:" + resourceId.toLowerCase(Locale.ROOT)))));
                }
            }
            identity.put("userAssignedIdentities", userAssigned);
            return identity;
        }
        identity.put("type", "None");
        return identity;
    }

    private static Map<String, Object> identityIds(Map<String, Object> identity) {
        Map<String, Object> props = ArmJson.cast(identity.get("properties"));
        Map<String, Object> ids = new LinkedHashMap<>();
        ids.put("principalId", props.get("principalId"));
        ids.put("clientId", props.get("clientId"));
        return ids;
    }

    private static Response assignmentNotFound(String name) {
        return ArmErrors.error(404, "PolicyAssignmentNotFound", "The policy assignment '" + name + "' is not found.");
    }

    // ── Policy exemptions ───────────────────────────────────────────────────────

    private Response handleExemption(AzureRequest req, PolicyPath path, String method, ListFilter filter) {
        Response scopeError = requireAssignmentScope(path);
        if (scopeError != null) {
            return scopeError;
        }
        if (path.name() == null) {
            if (!"GET".equals(method)) {
                return methodNotAllowed(method);
            }
            return listResponse(store.listExemptions(), path, filter, false);
        }
        String id = path.resourceId();
        return switch (method) {
            case "PUT" -> putExemption(req, path, id);
            case "PATCH" -> patchExemption(req, path, id);
            case "GET" -> {
                Map<String, Object> existing = store.getExemption(id);
                yield existing == null ? exemptionNotFound(path.name()) : Response.ok(strip(existing)).build();
            }
            case "DELETE" -> {
                Map<String, Object> removed = store.removeExemption(id);
                yield removed == null ? Response.status(204).build() : Response.ok(strip(removed)).build();
            }
            default -> methodNotAllowed(method);
        };
    }

    private Response putExemption(AzureRequest req, PolicyPath path, String id) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        Map<String, Object> props = ArmJson.cast(body.get("properties"));
        String assignmentId = ArmJson.string(props, "policyAssignmentId", null);
        if (assignmentId == null || assignmentId.isBlank()) {
            return ArmErrors.error(400, "InvalidCreatePolicyExemptionRequest",
                    "The policy exemption '" + path.name() + "' create request is invalid. "
                            + "A 'policyAssignmentId' must be provided.");
        }
        String category = ArmJson.string(props, "exemptionCategory", null);
        if (category == null || !EXEMPTION_CATEGORIES.contains(category)) {
            return ArmErrors.error(400, "InvalidCreatePolicyExemptionRequest",
                    "The policy exemption '" + path.name() + "' create request is invalid. "
                            + "The 'exemptionCategory' must be 'Waiver' or 'Mitigated'.");
        }
        if (store.getAssignment(assignmentId) == null) {
            return assignmentNotFound(leaf(assignmentId));
        }
        Map<String, Object> existing = store.getExemption(id);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("policyAssignmentId", assignmentId);
        putIfList(properties, "policyDefinitionReferenceIds", props);
        properties.put("exemptionCategory", category);
        putIfString(properties, "expiresOn", props);
        putIfString(properties, "displayName", props);
        putIfString(properties, "description", props);
        properties.put("assignmentScopeValidation", ArmJson.string(props, "assignmentScopeValidation", "Default"));
        putIfMap(properties, "metadata", props);
        putIfList(properties, "resourceSelectors", props);

        Map<String, Object> resource = envelope(path, id, properties, existing);
        store.putExemption(id, resource);
        return Response.status(existing == null ? 201 : 200).entity(strip(resource)).build();
    }

    /** PATCH updates only the resource selectors and the scope validation mode (PolicyExemptionUpdate). */
    private Response patchExemption(AzureRequest req, PolicyPath path, String id) {
        Map<String, Object> existing = store.getExemption(id);
        if (existing == null) {
            return exemptionNotFound(path.name());
        }
        Map<String, Object> patch = ArmJson.cast(ArmJson.parseBodyStrict(req).get("properties"));
        Map<String, Object> properties = new LinkedHashMap<>(propertiesOf(existing));
        if (patch.get("resourceSelectors") instanceof List<?> selectors) {
            properties.put("resourceSelectors", selectors);
        }
        if (patch.get("assignmentScopeValidation") instanceof String validation) {
            properties.put("assignmentScopeValidation", validation);
        }
        Map<String, Object> updated = envelope(path, id, properties, existing);
        store.putExemption(id, updated);
        return Response.ok(strip(updated)).build();
    }

    private static Response exemptionNotFound(String name) {
        return ArmErrors.error(404, "PolicyExemptionNotFound", "The policy exemption '" + name + "' could not be found.");
    }

    // ── Shared: scopes, references, listing ─────────────────────────────────────

    /**
     * Tenant-rooted definition paths are where Azure serves built-in definitions. None are seeded, so
     * collections are empty and names are not found; writes get the ARM gateway's tenant-level rejection.
     */
    private Response handleBuiltIn(PolicyPath path, String method, String notFoundCode, String notFoundMessage) {
        if ("GET".equals(method)) {
            return path.name() == null
                    ? Response.ok(Map.of("value", List.of())).build()
                    : ArmErrors.error(404, notFoundCode, notFoundMessage);
        }
        return missingSubscription();
    }

    private Response requireAssignmentScope(PolicyPath path) {
        ScopeKind kind = path.scopeKind();
        if (kind == ScopeKind.TENANT) {
            return missingSubscription();
        }
        return ASSIGNMENT_SCOPES.contains(kind) ? null : unsupportedScope(path);
    }

    /**
     * Definitions in a subscription or management group must exist; tenant-rooted ids are built-in
     * references and are accepted as-is because built-ins are not seeded.
     */
    private Response requireDefinition(String definitionId) {
        if (isScopedId(definitionId) && store.getDefinition(definitionId) == null) {
            return ArmErrors.error(404, "PolicyDefinitionNotFound",
                    "The policy definition '" + leaf(definitionId) + "' could not be found.");
        }
        return null;
    }

    private Response requireSetDefinition(String setDefinitionId) {
        if (isScopedId(setDefinitionId) && store.getSetDefinition(setDefinitionId) == null) {
            return ArmErrors.error(404, "PolicySetDefinitionNotFound",
                    "The policy set definition '" + leaf(setDefinitionId) + "' could not be found.");
        }
        return null;
    }

    private static boolean isScopedId(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.contains("/subscriptions/") || lower.contains("/managementgroups/");
    }

    /**
     * Lists resources for a scope. Definitions and set definitions live at exactly the requested
     * scope. Assignments and exemptions are extension resources whose default listing also includes
     * the ancestors and descendants of the scope, narrowed by {@code atScope()}, {@code atExactScope()}
     * or {@code atScopeAndBelow()}, plus the {@code eq} filters the spec allows.
     */
    private Response listResponse(List<Map<String, Object>> candidates, PolicyPath path, ListFilter filter,
                                  boolean exactScopeOnly) {
        String requested = path.scopeKey();
        List<Map<String, Object>> value = new ArrayList<>();
        for (Map<String, Object> resource : candidates) {
            String candidate = String.valueOf(resource.get(SCOPE_KEY));
            boolean scopeMatch = exactScopeOnly ? candidate.equals(requested) : filter.matchesScope(candidate, requested);
            if (scopeMatch && filter.matchesProperties(propertiesOf(resource))) {
                value.add(strip(resource));
            }
        }
        return Response.ok(Map.of("value", value)).build();
    }

    /** The {@code $filter} grammar of the policy list operations, parsed leniently. */
    record ListFilter(boolean atExactScope, boolean atScope, boolean atScopeAndBelow, boolean excludeExpired,
                      Map<String, String> equalities) {

        static ListFilter parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return new ListFilter(false, false, false, false, Map.of());
            }
            String lower = raw.toLowerCase(Locale.ROOT).replace(" ", "");
            Map<String, String> equalities = new LinkedHashMap<>();
            Matcher matcher = FILTER_EQUALS.matcher(raw);
            while (matcher.find()) {
                equalities.put(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2));
            }
            return new ListFilter(lower.contains("atexactscope()"), lower.contains("atscope()"),
                    lower.contains("atscopeandbelow()"), lower.contains("excludeexpired()"), equalities);
        }

        boolean matchesScope(String candidate, String requested) {
            boolean equal = candidate.equals(requested);
            boolean ancestor = requested.startsWith(candidate + "/");
            boolean descendant = candidate.startsWith(requested + "/");
            if (atExactScope) {
                return equal;
            }
            if (atScope) {
                return equal || ancestor;
            }
            if (atScopeAndBelow) {
                return equal || descendant;
            }
            return equal || ancestor || descendant;
        }

        boolean matchesProperties(Map<String, Object> properties) {
            for (Map.Entry<String, String> equality : equalities.entrySet()) {
                String wanted = equality.getValue();
                switch (equality.getKey()) {
                    case "policytype" -> {
                        if (!"Custom".equalsIgnoreCase(wanted)) {
                            return false;
                        }
                    }
                    case "policydefinitionid" -> {
                        if (!wanted.equalsIgnoreCase(String.valueOf(properties.get("policyDefinitionId")))) {
                            return false;
                        }
                    }
                    case "policyassignmentid" -> {
                        if (!wanted.equalsIgnoreCase(String.valueOf(properties.get("policyAssignmentId")))) {
                            return false;
                        }
                    }
                    default -> {
                        // Unknown filter keys are ignored rather than rejected, matching the lenient
                        // treatment ARM-plane handlers give unrecognised query options.
                    }
                }
            }
            if (excludeExpired && properties.get("expiresOn") instanceof String expiresOn) {
                try {
                    if (Instant.parse(expiresOn).isBefore(Instant.now())) {
                        return false;
                    }
                } catch (DateTimeParseException e) {
                    LOG.debugf("Ignoring unparseable expiresOn %s while filtering exemptions", expiresOn);
                }
            }
            return true;
        }
    }

    // ── Shared: resource assembly ───────────────────────────────────────────────

    /** Wraps {@code properties} in the ARM envelope: properties, id, type, name, systemData. */
    private Map<String, Object> envelope(PolicyPath path, String id, Map<String, Object> properties,
                                         Map<String, Object> existing) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("properties", properties);
        resource.put("id", id);
        resource.put("type", path.type().armType());
        resource.put("name", path.name());
        resource.put("systemData", systemData(existing));
        resource.put(SCOPE_KEY, path.scopeKey());
        return resource;
    }

    /**
     * The caller-supplied {@code metadata} plus the audit keys Azure stamps on definitions, set
     * definitions and assignments. Server values always win over any audit keys echoed back by the client.
     */
    private static Map<String, Object> auditedMetadata(Object given, Map<String, Object> existing) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (given instanceof Map<?, ?> map) {
            map.forEach((key, value) -> metadata.put(String.valueOf(key), value));
        }
        String now = Instant.now().toString();
        Map<String, Object> previous = existing == null ? null : ArmJson.cast(propertiesOf(existing).get("metadata"));
        if (previous == null || previous.isEmpty()) {
            metadata.put("createdBy", AUTHOR);
            metadata.put("createdOn", now);
        } else {
            metadata.put("createdBy", previous.get("createdBy"));
            metadata.put("createdOn", previous.get("createdOn"));
            metadata.put("updatedBy", AUTHOR);
            metadata.put("updatedOn", now);
        }
        return metadata;
    }

    private static Map<String, Object> systemData(Map<String, Object> existing) {
        String now = Instant.now().toString();
        Map<String, Object> previous = existing == null ? Map.of() : ArmJson.cast(existing.get("systemData"));
        Map<String, Object> systemData = new LinkedHashMap<>();
        systemData.put("createdBy", previous.getOrDefault("createdBy", AUTHOR));
        systemData.put("createdByType", "Application");
        systemData.put("createdAt", previous.getOrDefault("createdAt", now));
        systemData.put("lastModifiedBy", AUTHOR);
        systemData.put("lastModifiedByType", "Application");
        systemData.put("lastModifiedAt", now);
        return systemData;
    }

    /** Azure hands out numeric reference ids for policy definition references that were created without one. */
    private static String generatedReferenceId() {
        return Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
    }

    private static Map<String, Object> strip(Map<String, Object> resource) {
        Map<String, Object> copy = new LinkedHashMap<>(resource);
        copy.remove(SCOPE_KEY);
        return copy;
    }

    private static Map<String, Object> propertiesOf(Map<String, Object> resource) {
        return ArmJson.cast(resource.get("properties"));
    }

    private static String leaf(String resourceId) {
        return resourceId.substring(resourceId.lastIndexOf('/') + 1);
    }

    private static void putIfString(Map<String, Object> target, String key, Map<String, Object> source) {
        if (source.get(key) instanceof String value) {
            target.put(key, value);
        }
    }

    private static void putIfMap(Map<String, Object> target, String key, Map<String, Object> source) {
        if (source.get(key) instanceof Map<?, ?> value) {
            target.put(key, value);
        }
    }

    private static void putIfList(Map<String, Object> target, String key, Map<String, Object> source) {
        if (source.get(key) instanceof List<?> value) {
            target.put(key, value);
        }
    }

    private static Response unsupportedScope(PolicyPath path) {
        return ArmErrors.error(404, "ResourceNotFound",
                path.type().armType() + " is not available at scope '/" + path.scope() + "'.");
    }

    private static Response missingSubscription() {
        return ArmErrors.error(400, "MissingSubscription",
                "The request did not have a subscription or a valid tenant level resource provider.");
    }

    private static Response methodNotAllowed(String method) {
        return ArmErrors.error(405, "MethodNotAllowed", "Method not allowed: " + method);
    }
}
