package io.floci.az.services.policy.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.ArmRouter;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmRequestRule;
import io.floci.az.core.arm.ArmVerdict;
import io.floci.az.core.auth.BearerClaims;
import io.floci.az.services.policy.evaluation.PolicyEngine.Match;
import io.floci.az.services.policy.evaluation.PolicyEngine.Outcome;
import io.floci.az.services.policyinsights.ComplianceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The gate rule that evaluates Azure Policy on every PUT and PATCH of a resource, before its handler runs:
 * a matching {@code deny} answers {@code 403 RequestDisallowedByPolicy} in the handler's place, a matching
 * {@code append} amends the body the handler then reads, and a matching {@code audit} is recorded once the
 * handler has answered. Every effect that fires is kept in the {@link PolicyEvaluationLog}.
 *
 * <p>Off under {@code floci-az.services.policy.enforcement=false}, and when the policy service is disabled.
 */
@ApplicationScoped
public class PolicyEvaluationRule implements ArmRequestRule {

    /** Position in the gate: after the subscription and resource-group rules, which decide the scope exists. */
    public static final int ORDER = 40;

    private static final Logger LOG = Logger.getLogger(PolicyEvaluationRule.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> KEPT_CLAIMS = Set.of("oid", "appid", "azp", "idtyp", "tid", "upn", "name",
        "preferred_username", "sub", "acrs");

    private final PolicyEngine engine;
    private final PolicyEvaluationLog log;
    private final Instance<ArmRouter> router;
    private final boolean enabled;

    /** The admitted requests, whose audits and compliance verdicts are recorded on exit when the handler answered 2xx. */
    private final Map<ArmCall, Pending> pending = Collections.synchronizedMap(new IdentityHashMap<>());

    /** The deletes in flight, whose resource loses its compliance records once the handler answered 2xx. */
    private final Set<ArmCall> deletions = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private record Pending(PolicyEngine.Request request, List<Match> audits, String caller, String correlationId,
                           List<PolicyEngine.Evaluation> evaluations) {
    }

    private final ComplianceStore compliance;

    @Inject
    public PolicyEvaluationRule(PolicyEngine engine, PolicyEvaluationLog log, Instance<ArmRouter> router,
                                ComplianceStore compliance, EmulatorConfig config) {
        this(engine, log, router, compliance,
            config.services().policy().enabled() && config.services().policy().enforcement());
    }

    PolicyEvaluationRule(PolicyEngine engine, PolicyEvaluationLog log, Instance<ArmRouter> router,
                         ComplianceStore compliance, boolean enabled) {
        this.engine = engine;
        this.log = log;
        this.router = router;
        this.compliance = compliance;
        this.enabled = enabled;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public boolean readsBody() {
        return enabled;
    }

    @Override
    public ArmVerdict enter(ArmCall call) {
        if (enabled && "DELETE".equals(call.method()) && call.id() != null && call.id().name() != null) {
            deletions.add(call);
            return ArmVerdict.proceed();
        }
        if (!enabled || !PolicyEngine.evaluates(call)) {
            return ArmVerdict.proceed();
        }
        Map<String, Object> content = call.body().json();
        if (content == null) {
            if (!call.body().isEmpty()) {
                return ArmVerdict.proceed(); // malformed or not a JSON object: the handler rejects it unchanged
            }
            content = Map.of(); // an empty body is evaluated as the empty content a lenient handler accepts
        }
        AzureRequest request = call.request();
        // A PATCH is judged on the resource it produces: the stored resource with the patch applied, read only
        // once an assignment's scope reaches the resource, so unassigned scopes cost no extra read. The same
        // assignment snapshot decides the read and the evaluation, so one landing in between cannot see a bare patch.
        List<Map<String, Object>> assignments = engine.assignments();
        Map<String, Object> stored = "PATCH".equals(call.method()) && engine.anyAssignmentCovers(call.id(), assignments)
            ? storedResource(call) : null;
        Map<String, Object> evaluated = stored == null ? PatchMerge.copy(content) : PatchMerge.apply(stored, content);
        Map<String, Object> beforePolicies = stored == null ? null : PatchMerge.copy(evaluated);
        BearerClaims claims = BearerClaims.of(request);
        Map<String, Object> identity = identity(claims);
        String caller = caller(claims);
        String correlationId = header(request, "x-ms-correlation-request-id");
        PolicyEngine.Request evaluation = new PolicyEngine.Request(call, evaluated,
            request == null ? null : request.queryParams().get("api-version"), identity,
            "PATCH".equals(call.method()) && changesOnlyTags(content));
        Outcome outcome;
        try {
            outcome = engine.evaluate(evaluation, assignments);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Policy evaluation failed for %s %s", call.method(), call.id().id());
            return ArmVerdict.respond(ArmErrors.error(500, "PolicyEvaluationFailed",
                "The assigned policies could not be evaluated for this request: " + e.getMessage()));
        }
        if (outcome.denied()) {
            for (Match denial : outcome.denials()) {
                log.record(event(evaluation, denial, "deny", "Error", "Failed", caller, correlationId));
            }
            String name = PolicyEngine.resourceName(call.id());
            LOG.infof("Policy: %s %s disallowed by %d policy definition(s)", call.method(), call.id().id(),
                outcome.denials().size());
            return ArmVerdict.respond(PolicyDenial.response(name, outcome.denials()));
        }
        if (outcome.contentChanged()) {
            // An append on a PATCH amends the caller's patch, so the handler still receives a PATCH.
            call.body().replace(stored == null ? evaluation.content()
                : PatchMerge.amend(content, beforePolicies, evaluation.content()));
        }
        List<Match> appends = outcome.matches().stream()
            .filter(m -> "append".equals(m.effect()) && m.enforced()).toList();
        List<Match> audits = outcome.matches().stream()
            .filter(m -> "audit".equals(m.effect()) && m.enforced()).toList();
        for (Match append : appends) {
            log.record(event(evaluation, append, "append", "Informational", "Succeeded", caller, correlationId));
        }
        pending.put(call, new Pending(evaluation, audits, caller, correlationId, outcome.evaluations()));
        return ArmVerdict.proceed();
    }

    @Override
    public void exit(ArmCall call, Response response) {
        boolean succeeded = response != null && response.getStatus() / 100 == 2;
        if (deletions.remove(call)) {
            if (succeeded) {
                compliance.forgetResource(call.id().id());
            }
            return;
        }
        Pending admitted = pending.remove(call);
        if (admitted == null || !succeeded) {
            return;
        }
        for (Match match : admitted.audits()) {
            log.record(event(admitted.request(), match, "audit", "Warning", "Succeeded", admitted.caller(), admitted.correlationId()));
        }
        // A tags-only PATCH is evaluated against the tag-inspecting definitions only, so the verdicts of the
        // others stand; any other write re-evaluated everything and replaces the resource's records.
        if (!admitted.request().tagsOnly()) {
            compliance.forgetResource(call.id().id());
        }
        compliance.record(call.id(), admitted.request().content(), admitted.evaluations());
    }

    private static PolicyEvaluationEvent event(PolicyEngine.Request request, Match match, String operation, String level,
                                               String status, String caller, String correlationId) {
        ArmCall call = request.call();
        Map<String, Object> assignmentProperties = PolicyEngine.properties(match.assignment());
        return new PolicyEvaluationEvent(UUID.randomUUID().toString(), Instant.now(),
            "Microsoft.Authorization/policies/" + operation + "/action", level, status, call.method(), request.apiVersion(),
            call.id().id(), PolicyEngine.resourceType(call.id()), call.id().subscriptionId(), call.id().resourceGroup(),
            caller, request.identity(), correlationId == null ? UUID.randomUUID().toString() : correlationId,
            String.valueOf(match.assignment().get("id")), String.valueOf(match.definition().get("id")),
            match.setDefinition() == null ? null : String.valueOf(match.setDefinition().get("id")), match.referenceId(),
            match.effect(), String.valueOf(assignmentProperties.getOrDefault("enforcementMode", "Default")),
            match.nonComplianceMessage(), match.trace());
    }

    /** A PATCH body that changes nothing but {@code tags}. */
    static boolean changesOnlyTags(Map<String, Object> patch) {
        return !patch.isEmpty() && patch.keySet().stream().allMatch(key -> key.equalsIgnoreCase("tags"));
    }

    /** The resource a PATCH updates, as its own GET answers it; null when there is none or it cannot be read. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> storedResource(ArmCall call) {
        AzureRequest request = call.request();
        if (request == null || router == null || router.isUnsatisfied()) {
            return null;
        }
        Map<String, String> query = new LinkedHashMap<>();
        String apiVersion = request.queryParams() == null ? null : request.queryParams().get("api-version");
        if (apiVersion != null) {
            query.put("api-version", apiVersion);
        }
        try {
            Response current = router.get().dispatchInternal("GET", call.id().id(), query, request);
            if (current.getStatus() != 200) {
                return null;
            }
            Object entity = current.getEntity();
            if (entity instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            if (entity instanceof String text) {
                Object parsed = MAPPER.readValue(text, Object.class);
                return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
            }
            return entity == null ? null : MAPPER.convertValue(entity, Map.class);
        } catch (RuntimeException | IOException e) {
            LOG.debugf(e, "Policy: the stored resource %s could not be read; the PATCH is evaluated on its body", call.id().id());
            return null;
        }
    }

    // ── Caller identity ─────────────────────────────────────────────────────────

    /**
     * The identity claims {@code requestContext().identity} exposes, from the request's {@link BearerClaims}:
     * the claims Azure's policy context carries, {@code appid} and {@code idtyp} filled in the way the token
     * implies them, and the object-identifier claim Azure adds.
     */
    static Map<String, Object> identity(BearerClaims claims) {
        Map<String, Object> identity = new LinkedHashMap<>();
        if (claims == BearerClaims.NONE) {
            return identity;
        }
        // The typed values: an array claim such as acrs stays an array for count.value and the like.
        for (Map.Entry<String, Object> entry : claims.values().entrySet()) {
            if (KEPT_CLAIMS.contains(entry.getKey())) {
                identity.put(entry.getKey(), entry.getValue());
            }
        }
        if (claims.appId() != null) {
            identity.put("appid", claims.appId());
        }
        if (claims.idtyp() != null) {
            identity.put("idtyp", claims.idtyp());
        }
        if (claims.oid() != null) {
            identity.put("http://schemas.microsoft.com/identity/claims/objectidentifier", claims.oid());
        }
        return identity;
    }

    /** The caller the evaluation log names: the user principal name, else the object id, else the client id. */
    private static String caller(BearerClaims claims) {
        if (claims.upn() != null) {
            return claims.upn();
        }
        return claims.oid() != null ? claims.oid() : claims.appId();
    }

    private static String header(AzureRequest request, String name) {
        return request == null || request.headers() == null ? null : request.headers().getHeaderString(name);
    }


}
