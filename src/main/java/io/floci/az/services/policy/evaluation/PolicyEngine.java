package io.floci.az.services.policy.evaluation;

import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.directory.ResourceGroup;
import io.floci.az.core.directory.ResourceGroups;
import io.floci.az.services.policy.PolicyStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates every policy assignment that applies to a write against the request content, the way Azure
 * does before a request reaches its resource provider: assignments are collected by scope, excluded scopes
 * and exemptions drop out, each assigned definition (or each member of an assigned set definition) has its
 * parameters and effect resolved, and the effects run in Azure's order: {@code append} first, since it can
 * change what {@code deny} and {@code audit} then see.
 *
 * <p>Deviations from Azure, all documented on the policy service page: a management-group assignment applies
 * to every subscription because the emulator has no management-group hierarchy; a definition referenced by
 * a built-in id is skipped because built-ins are not seeded; {@code Indexed} mode is approximated as
 * "top-level provider resources"; a PATCH is evaluated on the stored resource with the patch applied
 * ({@code properties} merged, {@code tags} replaced), or on its own body when the resource cannot be read;
 * a PATCH that changes only {@code tags} is evaluated against the definitions whose condition inspects tags;
 * {@code modify}, {@code denyAction}, {@code auditIfNotExists}, {@code deployIfNotExists}, {@code manual}
 * and the resource-provider modes evaluate to nothing.
 */
@ApplicationScoped
public class PolicyEngine {

    private static final Logger LOG = Logger.getLogger(PolicyEngine.class);

    private static final String MANAGEMENT_GROUPS = "providers/microsoft.management/managementgroups/";
    private static final String RESOURCE_GROUP_TYPE = "Microsoft.Resources/subscriptions/resourceGroups";

    /**
     * Request-time facts the rule hands the engine.
     *
     * @param tagsOnly a PATCH whose body changes only {@code tags}: Azure then evaluates only the definitions
     *                 whose condition inspects a tags field
     */
    public record Request(ArmCall call, Map<String, Object> content, String apiVersion, Map<String, Object> identity,
                          boolean tagsOnly) {
    }

    /**
     * One definition whose {@code if} condition matched, with the effect it resolved to.
     *
     * @param enforced false under {@code enforcementMode: DoNotEnforce}, when the effect is evaluated but not applied
     * @param error    the evaluation failure that made the match a denial, or null
     */
    public record Match(Map<String, Object> assignment, Map<String, Object> definition, Map<String, Object> setDefinition,
                        String referenceId, String effect, boolean enforced, List<Map<String, Object>> trace,
                        String nonComplianceMessage, String error) {
    }

    /** What the request should do next: the denying matches, if any, and every match that fired. */
    public record Outcome(List<Match> matches, List<Match> denials, boolean contentChanged) {
        public boolean denied() {
            return !denials.isEmpty();
        }
    }

    private final PolicyStore store;
    private final ResourceGroups resourceGroups;

    @Inject
    public PolicyEngine(PolicyStore store, ResourceGroups resourceGroups) {
        this.store = store;
        this.resourceGroups = resourceGroups;
    }

    /** True for a write the engine evaluates: a PUT or PATCH of a provider resource or of a resource group. */
    public static boolean evaluates(ArmCall call) {
        if (call.id() == null || call.body() == null || !("PUT".equals(call.method()) || "PATCH".equals(call.method()))) {
            return false;
        }
        ArmResourceId id = call.id();
        if (id.providerNamespace() != null) {
            return !id.isCollection() && id.name() != null && !isPolicyResource(id);
        }
        return isResourceGroupPath(id);
    }

    /** The policy resources themselves are not evaluated, so a deny can never lock policy management out. */
    static boolean isPolicyResource(ArmResourceId id) {
        return "microsoft.authorization".equalsIgnoreCase(id.providerNamespace()) && !id.types().isEmpty()
            && id.types().getFirst().toLowerCase(Locale.ROOT).startsWith("policy");
    }

    static boolean isResourceGroupPath(ArmResourceId id) {
        return id.subscriptionId() != null && id.resourceGroup() != null && id.providerNamespace() == null
            && id.types().size() <= 1;
    }

    static String resourceType(ArmResourceId id) {
        return id.providerNamespace() != null ? id.typePath() : RESOURCE_GROUP_TYPE;
    }

    static String resourceName(ArmResourceId id) {
        return id.providerNamespace() != null ? id.name() : id.resourceGroup();
    }

    public Outcome evaluate(Request request) {
        ArmCall call = request.call();
        ArmResourceId id = call.id();
        String resourceKey = key(id.id());
        Map<String, Object> content = request.content();
        String resourceType = resourceType(id);
        String resourceName = resourceName(id);
        fillComputedFields(content, id, resourceType, resourceName);

        EvaluationContext base = new EvaluationContext(content, id, resourceType, resourceName,
            requestContext(request), resourceGroupFacts(id), subscriptionFacts(id));

        List<Candidate> candidates = new ArrayList<>();
        for (Map<String, Object> assignment : store.listAssignments()) {
            if (!applies(assignment, resourceKey, content, resourceType)) {
                continue;
            }
            expand(assignment, candidates, base);
        }

        List<Match> matches = new ArrayList<>();
        List<Match> denials = new ArrayList<>();
        boolean changed = false;
        for (String phase : List.of("append", "deny", "audit", "other")) {
            for (Candidate candidate : candidates) {
                String effect = candidate.effect();
                boolean inPhase = switch (phase) {
                    case "append" -> effect.equals("append");
                    case "deny" -> effect.equals("deny");
                    case "audit" -> effect.equals("audit");
                    default -> !Set.of("append", "deny", "audit", "disabled").contains(effect);
                };
                if (!inPhase || isExempt(candidate, resourceKey, content, resourceType)
                        || (request.tagsOnly() && !inspectsTags(candidate, base))) {
                    continue;
                }
                Match match = evaluateCandidate(candidate, base);
                if (match == null) {
                    continue;
                }
                if (match.error() != null) {
                    // A rule the engine cannot evaluate denies the request rather than silently standing aside.
                    if (match.enforced()) {
                        match = new Match(match.assignment(), match.definition(), match.setDefinition(), match.referenceId(),
                            "deny", true, match.trace(), match.nonComplianceMessage(), match.error());
                        denials.add(match);
                    }
                } else if ("append".equals(effect) && match.enforced()) {
                    String conflict;
                    try {
                        conflict = append(candidate, base.forDefinition(candidate.parameters(), candidate.policy()));
                    } catch (RuntimeException e) {
                        conflict = "The append details could not be applied: " + e.getMessage();
                    }
                    if (conflict != null) {
                        match = new Match(match.assignment(), match.definition(), match.setDefinition(), match.referenceId(),
                            "deny", true, match.trace(), match.nonComplianceMessage(), conflict);
                        denials.add(match);
                    } else {
                        changed = true;
                    }
                } else if ("deny".equals(effect) && match.enforced()) {
                    denials.add(match);
                }
                matches.add(match);
            }
        }
        return new Outcome(List.copyOf(matches), List.copyOf(denials), changed);
    }

    // ── Applicability ───────────────────────────────────────────────────────────

    private static boolean applies(Map<String, Object> assignment, String resourceKey, Map<String, Object> content,
                                   String resourceType) {
        String scope = key(String.valueOf(properties(assignment).getOrDefault("scope", "")));
        if (!scopeCovers(scope, resourceKey)) {
            return false;
        }
        if (properties(assignment).get("notScopes") instanceof List<?> notScopes) {
            for (Object notScope : notScopes) {
                if (notScope instanceof String s && scopeCovers(key(s), resourceKey)) {
                    return false;
                }
            }
        }
        return selectorsAdmit(properties(assignment).get("resourceSelectors"), content, resourceType);
    }

    /** A scope covers a resource at or below it; a management group covers every subscription (no hierarchy). */
    static boolean scopeCovers(String scopeKey, String resourceKey) {
        if (scopeKey.isEmpty()) {
            return false;
        }
        if (scopeKey.startsWith(MANAGEMENT_GROUPS)) {
            return resourceKey.startsWith("subscriptions/");
        }
        return resourceKey.equals(scopeKey) || resourceKey.startsWith(scopeKey + "/");
    }

    /** Resource selectors admit a resource when any selector set matches it in full; none means all resources. */
    static boolean selectorsAdmit(Object resourceSelectors, Map<String, Object> content, String resourceType) {
        if (!(resourceSelectors instanceof List<?> sets) || sets.isEmpty()) {
            return true;
        }
        for (Object set : sets) {
            if (!(set instanceof Map<?, ?> selectorSet)) {
                continue;
            }
            Object selectors = ResourceFields.get(selectorSet, "selectors");
            if (!(selectors instanceof List<?> list) || list.isEmpty()) {
                return true;
            }
            boolean all = true;
            for (Object selector : list) {
                if (!(selector instanceof Map<?, ?> s) || !selectorMatches(s, content, resourceType)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static boolean selectorMatches(Map<?, ?> selector, Map<String, Object> content, String resourceType) {
        String kind = String.valueOf(ResourceFields.get(selector, "kind")).toLowerCase(Locale.ROOT);
        Object in = ResourceFields.get(selector, "in");
        Object notIn = ResourceFields.get(selector, "notIn");
        String subject = switch (kind) {
            case "resourcelocation" -> ResourceFields.normalizeLocation(content.get("location"));
            case "resourcetype" -> resourceType;
            case "resourcewithoutlocation" -> content.get("location") == null ? "subscriptionLevelResources" : null;
            default -> null;
        };
        if (in instanceof List<?> allowed) {
            return subject != null && containsIgnoreCase(allowed, kind.equals("resourcelocation") ? subject : subject);
        }
        if (notIn instanceof List<?> blocked) {
            return subject == null || !containsIgnoreCase(blocked, subject);
        }
        return true;
    }

    private static boolean containsIgnoreCase(List<?> values, String subject) {
        for (Object value : values) {
            String candidate = value instanceof String s ? s : String.valueOf(value);
            if (subject.equalsIgnoreCase(candidate)
                    || subject.equalsIgnoreCase(ResourceFields.normalizeLocation(candidate))) {
                return true;
            }
        }
        return false;
    }

    private boolean isExempt(Candidate candidate, String resourceKey, Map<String, Object> content, String resourceType) {
        String assignmentId = String.valueOf(candidate.assignment().get("id")).toLowerCase(Locale.ROOT);
        for (Map<String, Object> exemption : store.listExemptions()) {
            Map<String, Object> properties = properties(exemption);
            if (!assignmentId.equalsIgnoreCase(String.valueOf(properties.get("policyAssignmentId")))) {
                continue;
            }
            String scope = exemptionScope(exemption);
            if (!scopeCovers(scope, resourceKey)) {
                continue;
            }
            if (properties.get("expiresOn") instanceof String expiresOn && isPast(expiresOn)) {
                continue;
            }
            if (properties.get("policyDefinitionReferenceIds") instanceof List<?> ids && !ids.isEmpty()
                    && (candidate.referenceId() == null || !containsIgnoreCase(ids, candidate.referenceId()))) {
                continue;
            }
            if (!selectorsAdmit(properties.get("resourceSelectors"), content, resourceType)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static String exemptionScope(Map<String, Object> exemption) {
        String id = String.valueOf(exemption.get("id"));
        int marker = id.toLowerCase(Locale.ROOT).lastIndexOf("/providers/microsoft.authorization/policyexemptions/");
        return key(marker > 0 ? id.substring(0, marker) : id);
    }

    private static boolean isPast(String dateTime) {
        try {
            return OffsetDateTime.parse(dateTime).isBefore(OffsetDateTime.now());
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── Expansion ───────────────────────────────────────────────────────────────

    /** One assigned definition with its resolved parameters and effect, or the failure that resolving them raised. */
    private record Candidate(Map<String, Object> assignment, Map<String, Object> definition,
                             Map<String, Object> setDefinition, String referenceId, Map<String, Object> parameters,
                             Map<String, Object> policy, String effect, boolean enforced, String nonComplianceMessage,
                             String error) {
    }

    private void expand(Map<String, Object> assignment, List<Candidate> out, EvaluationContext base) {
        Map<String, Object> assignmentProperties = properties(assignment);
        String targetId = String.valueOf(assignmentProperties.get("policyDefinitionId"));
        boolean enforced = !"DoNotEnforce".equalsIgnoreCase(String.valueOf(assignmentProperties.getOrDefault("enforcementMode", "Default")));
        Map<String, Object> assignmentParameters = parameterValues(assignmentProperties.get("parameters"));
        if (targetId.toLowerCase(Locale.ROOT).contains("/policysetdefinitions/")) {
            Map<String, Object> setDefinition = store.getSetDefinition(targetId);
            if (setDefinition == null) {
                LOG.debugf("Policy: set definition %s is not stored (built-in?), assignment %s evaluates nothing",
                    targetId, assignment.get("id"));
                return;
            }
            Map<String, Object> setProperties = properties(setDefinition);
            Map<String, Object> setParameters = resolveParameters(setProperties.get("parameters"), assignmentParameters, null);
            EvaluationContext setContext = base.forDefinition(setParameters, Map.of());
            if (!(setProperties.get("policyDefinitions") instanceof List<?> references)) {
                return;
            }
            for (Object reference : references) {
                if (!(reference instanceof Map<?, ?> ref)) {
                    continue;
                }
                String definitionId = String.valueOf(ResourceFields.get(ref, "policyDefinitionId"));
                Map<String, Object> definition = store.getDefinition(definitionId);
                if (definition == null) {
                    LOG.debugf("Policy: definition %s is not stored (built-in?), skipped", definitionId);
                    continue;
                }
                String referenceId = ResourceFields.get(ref, "policyDefinitionReferenceId") instanceof String r ? r : null;
                Map<String, Object> referenceValues = new LinkedHashMap<>();
                if (ResourceFields.get(ref, "parameters") instanceof Map<?, ?> refParameters) {
                    for (Map.Entry<?, ?> entry : refParameters.entrySet()) {
                        Object value = entry.getValue() instanceof Map<?, ?> v ? ResourceFields.get(v, "value") : entry.getValue();
                        referenceValues.put(String.valueOf(entry.getKey()), value);
                    }
                }
                addCandidate(out, assignment, definition, setDefinition, referenceId, referenceValues, setContext, enforced);
            }
            return;
        }
        Map<String, Object> definition = store.getDefinition(targetId);
        if (definition == null) {
            LOG.debugf("Policy: definition %s is not stored (built-in?), assignment %s evaluates nothing",
                targetId, assignment.get("id"));
            return;
        }
        addCandidate(out, assignment, definition, null, null, assignmentParameters, null, enforced);
    }

    private void addCandidate(List<Candidate> out, Map<String, Object> assignment, Map<String, Object> definition,
                              Map<String, Object> setDefinition, String referenceId, Map<String, Object> givenValues,
                              EvaluationContext valueContext, boolean enforced) {
        Map<String, Object> definitionProperties = properties(definition);
        String mode = String.valueOf(definitionProperties.getOrDefault("mode", "Indexed"));
        if (mode.contains(".")) {
            return; // a resource-provider mode (Microsoft.Kubernetes.Data and the like) is not evaluated here
        }
        Map<String, Object> policy = EvaluationContext.policyFacts(String.valueOf(assignment.get("id")),
            String.valueOf(definition.get("id")), setDefinition == null ? null : String.valueOf(setDefinition.get("id")),
            referenceId);
        Map<String, Object> parameters;
        try {
            parameters = resolveParameters(definitionProperties.get("parameters"), givenValues, valueContext);
        } catch (RuntimeException e) {
            LOG.warnf("Policy: cannot resolve parameters of %s for %s: %s", definition.get("id"), assignment.get("id"), e.getMessage());
            // A member an override disables stays disabled even when its parameters cannot be resolved.
            if ("disabled".equals(resolveEffect(assignment, definitionProperties, givenValues, policy, referenceId, null))) {
                return;
            }
            out.add(new Candidate(assignment, definition, setDefinition, referenceId, givenValues, policy, "deny", enforced,
                nonComplianceMessage(assignment, referenceId), "Parameters could not be resolved: " + e.getMessage()));
            return;
        }
        String effect = resolveEffect(assignment, definitionProperties, parameters, policy, referenceId, valueContext);
        if ("disabled".equals(effect)) {
            return;
        }
        out.add(new Candidate(assignment, definition, setDefinition, referenceId, parameters, policy, effect, enforced,
            nonComplianceMessage(assignment, referenceId), null));
    }

    /** Parameter values given as {@code {name: {value: ...}}} or {@code {name: value}}. */
    static Map<String, Object> parameterValues(Object raw) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object value = entry.getValue() instanceof Map<?, ?> v && ResourceFields.containsKey(v, "value")
                    ? ResourceFields.get(v, "value") : entry.getValue();
                values.put(String.valueOf(entry.getKey()), value);
            }
        }
        return values;
    }

    /**
     * Resolves a definition's declared parameters: a given value (evaluated in {@code valueContext} when it is
     * an expression, as set-definition references may be) wins over the declared default.
     */
    private static Map<String, Object> resolveParameters(Object declarations, Map<String, Object> given,
                                                         EvaluationContext valueContext) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (declarations instanceof Map<?, ?> declared) {
            for (Map.Entry<?, ?> entry : declared.entrySet()) {
                String name = String.valueOf(entry.getKey());
                Object value = null;
                boolean found = false;
                for (Map.Entry<String, Object> candidate : given.entrySet()) {
                    if (candidate.getKey().equalsIgnoreCase(name)) {
                        value = candidate.getValue();
                        found = true;
                        break;
                    }
                }
                if (!found && entry.getValue() instanceof Map<?, ?> declaration) {
                    value = ResourceFields.get(declaration, "defaultValue");
                }
                if (valueContext != null) {
                    value = ArmExpressions.evaluateDeep(value, valueContext);
                }
                resolved.put(name, value);
            }
        }
        for (Map.Entry<String, Object> entry : given.entrySet()) {
            resolved.putIfAbsent(entry.getKey(), valueContext == null ? entry.getValue()
                : ArmExpressions.evaluateDeep(entry.getValue(), valueContext));
        }
        return resolved;
    }

    /** The effect the assignment's overrides pick, else the definition's own, parameters resolved; lowercase. */
    private static String resolveEffect(Map<String, Object> assignment, Map<String, Object> definitionProperties,
                                        Map<String, Object> parameters, Map<String, Object> policy, String referenceId,
                                        EvaluationContext valueContext) {
        if (properties(assignment).get("overrides") instanceof List<?> overrides) {
            for (Object override : overrides) {
                if (override instanceof Map<?, ?> o && "policyEffect".equalsIgnoreCase(String.valueOf(ResourceFields.get(o, "kind")))
                        && overrideSelects(ResourceFields.get(o, "selectors"), referenceId)) {
                    return String.valueOf(ResourceFields.get(o, "value")).toLowerCase(Locale.ROOT);
                }
            }
        }
        Object rule = definitionProperties.get("policyRule");
        Object then = rule instanceof Map<?, ?> r ? ResourceFields.get(r, "then") : null;
        Object effect = then instanceof Map<?, ?> t ? ResourceFields.get(t, "effect") : null;
        if (effect instanceof String text && text.trim().startsWith("[")) {
            EvaluationContext ctx = valueContext != null ? valueContext.forDefinition(parameters, policy)
                : new EvaluationContext(Map.of(), null, null, null, Map.of(), null, Map.of()).forDefinition(parameters, policy);
            try {
                effect = ArmExpressions.evaluate(text, ctx);
            } catch (RuntimeException e) {
                LOG.warnf("Policy: cannot resolve the effect '%s': %s", text, e.getMessage());
                return "deny";
            }
        }
        return effect == null ? "disabled" : String.valueOf(effect).toLowerCase(Locale.ROOT);
    }

    private static boolean overrideSelects(Object selectors, String referenceId) {
        if (!(selectors instanceof List<?> list) || list.isEmpty()) {
            return true;
        }
        for (Object selector : list) {
            if (!(selector instanceof Map<?, ?> s)) {
                continue;
            }
            if (!"policyDefinitionReferenceId".equalsIgnoreCase(String.valueOf(ResourceFields.get(s, "kind")))) {
                continue;
            }
            Object in = ResourceFields.get(s, "in");
            Object notIn = ResourceFields.get(s, "notIn");
            if (in instanceof List<?> allowed && (referenceId == null || !containsIgnoreCase(allowed, referenceId))) {
                return false;
            }
            if (notIn instanceof List<?> blocked && referenceId != null && containsIgnoreCase(blocked, referenceId)) {
                return false;
            }
        }
        return true;
    }

    private static String nonComplianceMessage(Map<String, Object> assignment, String referenceId) {
        if (!(properties(assignment).get("nonComplianceMessages") instanceof List<?> messages)) {
            return null;
        }
        String general = null;
        for (Object entry : messages) {
            if (!(entry instanceof Map<?, ?> m)) {
                continue;
            }
            Object forReference = ResourceFields.get(m, "policyDefinitionReferenceId");
            Object message = ResourceFields.get(m, "message");
            if (forReference instanceof String r && !r.isBlank()) {
                if (referenceId != null && r.equalsIgnoreCase(referenceId)) {
                    return message == null ? null : String.valueOf(message);
                }
            } else if (general == null && message != null) {
                general = String.valueOf(message);
            }
        }
        return general;
    }

    // ── Evaluation ──────────────────────────────────────────────────────────────

    private static Match evaluateCandidate(Candidate candidate, EvaluationContext base) {
        Map<String, Object> definitionProperties = properties(candidate.definition());
        String mode = String.valueOf(definitionProperties.getOrDefault("mode", "Indexed"));
        if ("indexed".equalsIgnoreCase(mode) && !supportsTagsAndLocation(base.id)) {
            return null;
        }
        Object rule = definitionProperties.get("policyRule");
        Object condition = rule instanceof Map<?, ?> r ? ResourceFields.get(r, "if") : null;
        if (!(condition instanceof Map<?, ?> ifCondition)) {
            return null;
        }
        if (candidate.error() != null) {
            return new Match(candidate.assignment(), candidate.definition(), candidate.setDefinition(), candidate.referenceId(),
                candidate.effect(), candidate.enforced(), List.of(), candidate.nonComplianceMessage(), candidate.error());
        }
        EvaluationContext ctx = base.forDefinition(candidate.parameters(), candidate.policy());
        Map<String, Object> content = base.content;
        List<Map<String, Object>> trace = new ArrayList<>();
        boolean matched;
        String error = null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) ifCondition;
            matched = PolicyRuleEvaluator.evaluate(typed, ctx, trace);
        } catch (RuntimeException e) {
            // A rule that cannot be evaluated denies the request, whatever its effect would have been.
            LOG.warnf("Policy: evaluation of %s failed for %s: %s", candidate.definition().get("id"), content.get("id"), e.getMessage());
            matched = true;
            error = e.getMessage();
        }
        if (!matched) {
            return null;
        }
        return new Match(candidate.assignment(), candidate.definition(), candidate.setDefinition(), candidate.referenceId(),
            candidate.effect(), candidate.enforced(), trace, candidate.nonComplianceMessage(), error);
    }

    /** True when the candidate's {@code if} condition inspects a tags field, its parameters resolved. */
    private static boolean inspectsTags(Candidate candidate, EvaluationContext base) {
        Object rule = properties(candidate.definition()).get("policyRule");
        Object condition = rule instanceof Map<?, ?> r ? ResourceFields.get(r, "if") : null;
        return PolicyRuleEvaluator.inspectsTags(condition, base.forDefinition(candidate.parameters(), candidate.policy()));
    }

    /** {@code Indexed} mode covers resource types that take tags and a location: top-level provider resources. */
    static boolean supportsTagsAndLocation(ArmResourceId id) {
        if (id == null || id.providerNamespace() == null || id.isExtension() || id.types().size() != 1) {
            return false;
        }
        String namespace = id.providerNamespace().toLowerCase(Locale.ROOT);
        return !namespace.equals("microsoft.authorization") && !namespace.equals("microsoft.resources");
    }

    /** Applies an append effect's details to the content; returns a conflict description, or null when applied. */
    private static String append(Candidate candidate, EvaluationContext ctx) {
        Object rule = properties(candidate.definition()).get("policyRule");
        Object then = rule instanceof Map<?, ?> r ? ResourceFields.get(r, "then") : null;
        Object details = then instanceof Map<?, ?> t ? ResourceFields.get(t, "details") : null;
        if (!(details instanceof List<?> list)) {
            return null;
        }
        for (Object detail : list) {
            if (!(detail instanceof Map<?, ?> d)) {
                continue;
            }
            String field = ArmExpressions.stringify(ArmExpressions.evaluate(ResourceFields.get(d, "field"), ctx));
            Object value = ArmExpressions.evaluateDeep(ResourceFields.get(d, "value"), ctx);
            String conflict = ContentWriter.append(ctx.content, ctx.resourceType, field, value);
            if (conflict != null) {
                return conflict;
            }
        }
        return null;
    }

    // ── Request facts ───────────────────────────────────────────────────────────

    private static Map<String, Object> requestContext(Request request) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("apiVersion", request.apiVersion());
        context.put("identity", request.identity());
        return context;
    }

    private Map<String, Object> resourceGroupFacts(ArmResourceId id) {
        if (id.subscriptionId() == null || id.resourceGroup() == null) {
            return null;
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("id", "/subscriptions/" + id.subscriptionId() + "/resourceGroups/" + id.resourceGroup());
        facts.put("name", id.resourceGroup());
        ResourceGroup group = resourceGroups.get(id.subscriptionId(), id.resourceGroup()).orElse(null);
        facts.put("location", group == null ? null : group.location());
        facts.put("tags", group == null || group.tags() == null ? Map.of() : group.tags());
        facts.put("managedBy", group == null ? null : group.managedBy());
        facts.put("properties", Map.of("provisioningState", group == null || group.provisioningState() == null
            ? "Succeeded" : group.provisioningState()));
        return facts;
    }

    private static Map<String, Object> subscriptionFacts(ArmResourceId id) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("id", id.subscriptionId() == null ? null : "/subscriptions/" + id.subscriptionId());
        facts.put("subscriptionId", id.subscriptionId());
        facts.put("displayName", "floci-az local");
        return facts;
    }

    /** Azure fills {@code type}, {@code name} and {@code id} from the URL before evaluating the content. */
    private static void fillComputedFields(Map<String, Object> content, ArmResourceId id, String type, String name) {
        content.put("type", type);
        content.put("name", name);
        content.put("id", id.id());
    }

    static Map<String, Object> properties(Map<String, Object> resource) {
        Object properties = resource == null ? null : resource.get("properties");
        if (properties instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of();
    }

    /** Lower-cased scope key without a leading slash, the form the policy store compares scopes in. */
    static String key(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        int start = 0;
        while (start < lower.length() && lower.charAt(start) == '/') {
            start++;
        }
        int end = lower.length();
        while (end > start && lower.charAt(end - 1) == '/') {
            end--;
        }
        return lower.substring(start, end);
    }
}
