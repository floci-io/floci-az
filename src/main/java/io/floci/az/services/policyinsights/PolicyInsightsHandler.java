package io.floci.az.services.policyinsights;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.Tenant;
import io.floci.az.services.managementgroups.ManagementGroupStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Azure Policy Insights ({@code Microsoft.PolicyInsights}): compliance states of resources against the
 * assigned policies. {@code policyStates/latest/queryResults} at subscription, resource-group, resource,
 * assignment, definition and set-definition scope; {@code policyStates/latest/summarize};
 * {@code triggerEvaluation} at subscription and resource-group scope as the long-running operation Azure
 * answers, completed by a synchronous scan; and the {@code asyncOperationResults} it points at.
 */
@ApplicationScoped
public class PolicyInsightsHandler implements AzureServiceHandler {

    static final String SERVICE_TYPE = "policyinsights";
    static final int MAX_TOP = 1000;

    private static final Logger LOG = Logger.getLogger(PolicyInsightsHandler.class);
    private static final String MARKER = "/providers/microsoft.policyinsights/";
    private static final String MANAGEMENT_GROUP_SCOPE = "/providers/microsoft.management/managementgroups/";
    private static final Pattern FILTER_CLAUSE = Pattern.compile("(?i)^\\s*(\\w+)\\s+eq\\s+(?:'((?:[^']|'')*)'|(true|false))\\s*$");
    private static final Pattern ASSIGNMENT_SCOPE = Pattern.compile("(?i)/providers/microsoft\\.authorization/policyassignments/([^/]+)$");
    private static final Pattern DEFINITION_SCOPE = Pattern.compile("(?i)/providers/microsoft\\.authorization/(policydefinitions|policysetdefinitions)/([^/]+)$");

    private final EmulatorConfig config;
    private final ComplianceStore store;
    private final ComplianceScanner scanner;
    private final ManagementGroupStore managementGroups;
    private final AzureDirectory directory;

    @Inject
    public PolicyInsightsHandler(EmulatorConfig config, ComplianceStore store, ComplianceScanner scanner,
                                 ManagementGroupStore managementGroups, AzureDirectory directory) {
        this.config = config;
        this.store = store;
        this.scanner = scanner;
        this.managementGroups = managementGroups;
        this.directory = directory;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().policy().enabled() && config.services().policy().insights();
    }

    @Override
    public ServiceRoutes routes() {
        // Guarded: an extension path under any resource (.../containerApps/app/providers/Microsoft.PolicyInsights/...)
        // belongs to this handler whenever Microsoft.PolicyInsights is the last provider segment, ahead of the
        // resource's own provider route.
        return ServiceRoutes.builder().provider("Microsoft.PolicyInsights", PolicyInsightsHandler::isLeafProviderSegment).build();
    }

    static boolean isLeafProviderSegment(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int marker = lower.lastIndexOf(MARKER);
        return marker >= 0 && lower.indexOf("/providers/", marker + 1) < 0;
    }


    @Override
    public Response handle(AzureRequest req) {
        String path = req.resourcePath();
        int query = path.indexOf('?');
        String resource = query >= 0 ? path.substring(0, query) : path;
        String normalized = "/" + resource;
        int marker = normalized.toLowerCase(Locale.ROOT).lastIndexOf(MARKER);
        if (marker < 0) {
            return ArmErrors.error(404, "InvalidResourceType", "The resource type could not be found in the namespace 'Microsoft.PolicyInsights'.");
        }
        String scope = normalized.substring(0, marker);
        String tail = normalized.substring(marker + MARKER.length());
        String tailLower = tail.toLowerCase(Locale.ROOT);
        String method = req.method().toUpperCase(Locale.ROOT);

        if (tailLower.equals("policystates/latest/triggerevaluation")) {
            return method.equals("POST") ? trigger(req, scope) : methodNotAllowed(method);
        }
        if (tailLower.startsWith("asyncoperationresults/")) {
            return method.equals("GET") ? Response.ok(Map.of("status", "Succeeded")).build() : methodNotAllowed(method);
        }
        if (tailLower.matches("policystates/(latest|default)/queryresults")) {
            return method.equals("POST") ? queryResults(req, scope) : methodNotAllowed(method);
        }
        if (tailLower.matches("policystates/(latest|default)/summarize")) {
            return method.equals("POST") ? summarize(req, scope) : methodNotAllowed(method);
        }
        if (tailLower.equals("operations")) {
            return method.equals("GET") ? operations() : methodNotAllowed(method);
        }
        if (tailLower.equals("policymetadata")) {
            // Metadata describes built-in definitions, none of which are seeded.
            return method.equals("GET") ? Response.ok(Map.of("value", List.of())).build() : methodNotAllowed(method);
        }
        if (tailLower.startsWith("policymetadata/")) {
            return method.equals("GET")
                ? ArmErrors.error(404, "PolicyMetadataNotFound", "The policy metadata '" + tail.substring("policyMetadata/".length()) + "' could not be found.")
                : methodNotAllowed(method);
        }
        return ArmErrors.error(404, "InvalidResourceType", "The resource type could not be found in the namespace 'Microsoft.PolicyInsights'.");
    }

    // ── Trigger evaluation ──────────────────────────────────────────────────────

    private Response trigger(AzureRequest req, String scope) {
        Matcher m = Pattern.compile("(?i)^/subscriptions/([^/]+)(?:/resourcegroups/([^/]+))?$").matcher(scope);
        if (!m.matches()) {
            return ArmErrors.error(400, "InvalidRequest", "Evaluation can be triggered at subscription or resource group scope.");
        }
        int evaluated = scanner.scan(m.group(1), m.group(2), req);
        LOG.infof("Policy compliance evaluation triggered for %s: %d resources", scope, evaluated);
        String operationId = UUID.randomUUID().toString();
        String base = (req.secure() ? "https://" : "http://") + (req.authority() == null ? req.host() : req.authority());
        String location = (base == null || base.endsWith("//") ? "" : base) + "/subscriptions/" + m.group(1)
            + "/providers/Microsoft.PolicyInsights/asyncOperationResults/" + operationId
            + "?api-version=" + req.queryParams().getOrDefault("api-version", "2024-10-01");
        return Response.status(202).header("Location", location).header("Retry-After", "1").build();
    }

    // ── Query results ───────────────────────────────────────────────────────────

    private Response queryResults(AzureRequest req, String scope) {
        Predicate<PolicyState> inScope = scopePredicate(scope, callerTenantId(req));
        Predicate<PolicyState> filter;
        try {
            filter = parseFilter(req.queryParams().get("$filter"));
        } catch (IllegalArgumentException e) {
            return queryFailure("InvalidQueryFilter", e.getMessage());
        }
        List<PolicyState> states = new ArrayList<>(store.query(inScope.and(filter)));
        states.sort(Comparator.comparing(PolicyState::timestamp).reversed().thenComparing(PolicyState::resourceId));
        String orderBy = req.queryParams().get("$orderby");
        if (orderBy != null && orderBy.toLowerCase(Locale.ROOT).startsWith("timestamp asc")) {
            states.sort(Comparator.comparing(PolicyState::timestamp).thenComparing(PolicyState::resourceId));
        }
        int top = (int) Math.min(MAX_TOP, Math.max(1, longParam(req, "$top", MAX_TOP)));
        int skip = 0;
        String token = req.queryParams().get("$skiptoken");
        if (token != null && !token.isBlank()) {
            try {
                skip = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                return queryFailure("InvalidQueryOption", "The $skiptoken is invalid.");
            }
            if (skip < 0) {
                return queryFailure("InvalidQueryOption", "The $skiptoken is invalid.");
            }
        }
        int from = Math.min(skip, states.size());
        int to = Math.min(from + top, states.size());
        Set<String> select = selected(req.queryParams().get("$select"));
        String context = contextBase(req);
        List<Map<String, Object>> value = new ArrayList<>();
        for (PolicyState state : states.subList(from, to)) {
            Map<String, Object> json = state.toJson(context);
            if (!select.isEmpty()) {
                json.keySet().removeIf(key -> !key.startsWith("@") && !select.contains(key.toLowerCase(Locale.ROOT)));
            }
            value.add(json);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("@odata.context", context + "$metadata#latest");
        body.put("@odata.count", states.size());
        if (to < states.size()) {
            body.put("@odata.nextLink", nextLink(req, to));
        }
        body.put("value", value);
        return Response.ok(body).build();
    }

    private Response summarize(AzureRequest req, String scope) {
        Predicate<PolicyState> inScope = scopePredicate(scope, callerTenantId(req));
        Predicate<PolicyState> filter;
        try {
            filter = parseFilter(req.queryParams().get("$filter"));
        } catch (IllegalArgumentException e) {
            return queryFailure("InvalidQueryFilter", e.getMessage());
        }
        List<PolicyState> states = store.query(inScope.and(filter));
        Map<String, List<PolicyState>> byAssignment = new LinkedHashMap<>();
        Set<String> nonCompliantResources = new LinkedHashSet<>();
        Set<String> nonCompliantPolicies = new LinkedHashSet<>();
        for (PolicyState state : states) {
            byAssignment.computeIfAbsent(state.policyAssignmentId().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(state);
            if (!state.isCompliant() && !"Exempt".equals(state.complianceState())) {
                nonCompliantResources.add(state.resourceId().toLowerCase(Locale.ROOT));
                nonCompliantPolicies.add((state.policyAssignmentId() + "|" + state.policyDefinitionId()).toLowerCase(Locale.ROOT));
            }
        }
        List<Map<String, Object>> assignments = new ArrayList<>();
        for (List<PolicyState> group : byAssignment.values()) {
            PolicyState first = group.getFirst();
            Set<String> resources = new LinkedHashSet<>();
            Map<String, Set<String>> perDefinition = new LinkedHashMap<>();
            for (PolicyState state : group) {
                if (!state.isCompliant() && !"Exempt".equals(state.complianceState())) {
                    resources.add(state.resourceId().toLowerCase(Locale.ROOT));
                    perDefinition.computeIfAbsent(state.policyDefinitionId(), k -> new LinkedHashSet<>())
                        .add(state.resourceId().toLowerCase(Locale.ROOT));
                }
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("policyAssignmentId", first.policyAssignmentId());
            summary.put("policySetDefinitionId", first.policySetDefinitionId() == null ? "" : first.policySetDefinitionId());
            summary.put("results", results(group, resources.size(), perDefinition.size()));
            List<Map<String, Object>> definitions = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : perDefinition.entrySet()) {
                Map<String, Object> definition = new LinkedHashMap<>();
                definition.put("policyDefinitionId", entry.getKey());
                definition.put("effect", group.stream().filter(s -> s.policyDefinitionId().equals(entry.getKey()))
                    .findFirst().map(PolicyState::policyDefinitionAction).orElse(""));
                definition.put("results", results(group.stream().filter(s -> s.policyDefinitionId().equals(entry.getKey())).toList(),
                    entry.getValue().size(), 1));
                definitions.add(definition);
            }
            summary.put("policyDefinitions", definitions);
            summary.put("policyGroups", List.of());
            assignments.add(summary);
        }
        Map<String, Object> summaryEntry = new LinkedHashMap<>();
        summaryEntry.put("@odata.id", null);
        summaryEntry.put("@odata.context", contextBase(req) + "$metadata#summary/$entity");
        summaryEntry.put("results", results(states, nonCompliantResources.size(), nonCompliantPolicies.size()));
        summaryEntry.put("policyAssignments", assignments);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("@odata.context", contextBase(req) + "$metadata#summary");
        body.put("@odata.count", 1);
        body.put("value", List.of(summaryEntry));
        return Response.ok(body).build();
    }

    /**
     * The {@code SummaryResults} shape. A resource counts once, under its overall state across the states
     * given: non-compliant when any policy finds it so, otherwise compliant when any policy evaluated it,
     * otherwise exempt.
     */
    private static Map<String, Object> results(List<PolicyState> states, int nonCompliantResources, int nonCompliantPolicies) {
        Map<String, String> overall = new LinkedHashMap<>();
        for (PolicyState state : states) {
            String resource = state.resourceId().toLowerCase(Locale.ROOT);
            String current = overall.get(resource);
            if ("NonCompliant".equals(current)) {
                continue;
            }
            if (!state.isCompliant() && !"Exempt".equals(state.complianceState())) {
                overall.put(resource, "NonCompliant");
            } else if (state.isCompliant() || current == null) {
                overall.put(resource, state.isCompliant() ? "Compliant" : "Exempt");
            }
        }
        long compliant = overall.values().stream().filter("Compliant"::equals).count();
        long exempt = overall.values().stream().filter("Exempt"::equals).count();
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("queryResultsUri", "");
        results.put("nonCompliantResources", nonCompliantResources);
        results.put("nonCompliantPolicies", nonCompliantPolicies);
        results.put("resourceDetails", List.of(
            Map.of("complianceState", "noncompliant", "count", nonCompliantResources),
            Map.of("complianceState", "compliant", "count", compliant),
            Map.of("complianceState", "exempt", "count", exempt)));
        results.put("policyDetails", List.of(Map.of("complianceState", "noncompliant", "count", nonCompliantPolicies)));
        results.put("policyGroupDetails", List.of());
        return results;
    }

    // ── Scope and filter ────────────────────────────────────────────────────────

    /**
     * The states a scope covers: a management group covers the subscriptions of the caller's tenant placed
     * under it or one of its descendants in that tenant's hierarchy (another tenant's group of the same name
     * is a different group), a subscription or resource group its resources, a resource itself and its
     * children, and an assignment or definition the states it produced.
     */
    private Predicate<PolicyState> scopePredicate(String scope, String tenant) {
        String lower = scope.toLowerCase(Locale.ROOT);
        Matcher assignment = ASSIGNMENT_SCOPE.matcher(scope);
        if (assignment.find()) {
            return state -> state.policyAssignmentId().equalsIgnoreCase(scope);
        }
        Matcher definition = DEFINITION_SCOPE.matcher(scope);
        if (definition.find()) {
            boolean set = definition.group(1).equalsIgnoreCase("policysetdefinitions");
            return state -> set ? scope.equalsIgnoreCase(state.policySetDefinitionId()) : scope.equalsIgnoreCase(state.policyDefinitionId());
        }
        if (lower.startsWith(MANAGEMENT_GROUP_SCOPE)) {
            String groupName = scope.substring(MANAGEMENT_GROUP_SCOPE.length());
            return state -> tenant.equalsIgnoreCase(directory.tenantOfSubscription(state.subscriptionId()))
                && managementGroups.isWithin(tenant, managementGroups.parentOf(tenant, state.subscriptionId()).name(), groupName);
        }
        return state -> {
            String id = state.resourceId().toLowerCase(Locale.ROOT);
            return id.equals(lower) || id.startsWith(lower + "/");
        };
    }

    /** The tenant whose hierarchy a management-group scope names: the token's tenant when the directory holds it, else the default. */
    private String callerTenantId(AzureRequest req) {
        String tokenTenantId = req.scope() == null ? null : req.scope().tokenTenantId();
        return directory.tenant(tokenTenantId).map(Tenant::id).orElse(directory.defaultTenantId());
    }

    private static Predicate<PolicyState> parseFilter(String filter) {
        if (filter == null || filter.isBlank()) {
            return state -> true;
        }
        Predicate<PolicyState> predicate = state -> true;
        for (String clause : filter.split("(?i)\\s+and\\s+")) {
            Matcher m = FILTER_CLAUSE.matcher(clause);
            if (!m.matches()) {
                throw new IllegalArgumentException("The $filter clause '" + clause.trim() + "' is not supported.");
            }
            String field = m.group(1).toLowerCase(Locale.ROOT);
            String value = m.group(2) != null ? m.group(2).replace("''", "'") : m.group(3);
            predicate = predicate.and(switch (field) {
                case "compliancestate" -> state -> state.complianceState().equalsIgnoreCase(value);
                case "iscompliant" -> state -> state.isCompliant() == Boolean.parseBoolean(value);
                case "policyassignmentid" -> state -> state.policyAssignmentId().equalsIgnoreCase(value);
                case "policyassignmentname" -> state -> state.policyAssignmentName().equalsIgnoreCase(value);
                case "policydefinitionid" -> state -> state.policyDefinitionId().equalsIgnoreCase(value);
                case "policydefinitionname" -> state -> state.policyDefinitionName().equalsIgnoreCase(value);
                case "policysetdefinitionid" -> state -> value.equalsIgnoreCase(state.policySetDefinitionId());
                case "policydefinitionreferenceid" -> state -> value.equalsIgnoreCase(state.policyDefinitionReferenceId());
                case "policydefinitionaction" -> state -> value.equalsIgnoreCase(state.policyDefinitionAction());
                case "resourcetype" -> state -> value.equalsIgnoreCase(state.resourceType());
                case "resourcelocation" -> state -> value.equalsIgnoreCase(state.resourceLocation());
                case "resourcegroup" -> state -> value.equalsIgnoreCase(state.resourceGroup());
                case "resourceid" -> state -> value.equalsIgnoreCase(state.resourceId());
                case "subscriptionid" -> state -> value.equalsIgnoreCase(state.subscriptionId());
                default -> throw new IllegalArgumentException("The field '" + m.group(1) + "' is not supported in $filter.");
            });
        }
        return predicate;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private Response operations() {
        List<Map<String, Object>> value = new ArrayList<>();
        for (String operation : List.of("policyStates/queryResults/action", "policyStates/summarize/action",
                "policyStates/triggerEvaluation/action", "asyncOperationResults/read")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", "Microsoft.PolicyInsights/" + operation);
            entry.put("display", Map.of("provider", "Microsoft Policy Insights", "resource", "Policy States",
                "operation", operation, "description", "Policy Insights " + operation));
            value.add(entry);
        }
        return Response.ok(Map.of("value", value)).build();
    }

    private static Response queryFailure(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        return Response.status(400).entity(Map.of("error", error)).build();
    }

    private static Response methodNotAllowed(String method) {
        return ArmErrors.error(405, "MethodNotAllowed", "The requested resource does not support http method '" + method + "'.");
    }

    private static long longParam(AzureRequest req, String name, long fallback) {
        String value = req.queryParams().get(name);
        if (value != null && value.matches("\\d+")) {
            return Long.parseLong(value);
        }
        return fallback;
    }

    private static Set<String> selected(String raw) {
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

    private static String contextBase(AzureRequest req) {
        String base = (req.secure() ? "https://" : "http://") + (req.authority() == null ? req.host() : req.authority());
        return (base == null || base.endsWith("//") ? "" : base) + "/";
    }

    private static String nextLink(AzureRequest req, int offset) {
        String base = contextBase(req);
        StringBuilder link = new StringBuilder(base.substring(0, base.length() - 1)).append('/').append(req.rawPath());
        link.append("?api-version=").append(req.queryParams().getOrDefault("api-version", "2024-10-01"));
        for (String param : List.of("$filter", "$select", "$top", "$orderby")) {
            String value = req.queryParams().get(param);
            if (value != null) {
                link.append('&').append(param).append('=')
                    .append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
            }
        }
        return link.append("&$skiptoken=").append(offset).toString();
    }
}
