package io.floci.az.services.policy.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmRequestRule;
import io.floci.az.core.arm.ArmVerdict;
import io.floci.az.services.policy.evaluation.PolicyEngine.Match;
import io.floci.az.services.policy.evaluation.PolicyEngine.Outcome;
import io.floci.az.services.policyinsights.ComplianceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
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
    public PolicyEvaluationRule(PolicyEngine engine, PolicyEvaluationLog log, ComplianceStore compliance, EmulatorConfig config) {
        this(engine, log, compliance, config.services().policy().enabled() && config.services().policy().enforcement());
    }

    PolicyEvaluationRule(PolicyEngine engine, PolicyEvaluationLog log, ComplianceStore compliance, boolean enabled) {
        this.engine = engine;
        this.log = log;
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
            content = Map.of(); // empty, or not a JSON object: evaluated as the empty content a lenient handler accepts
        }
        AzureRequest request = call.request();
        Map<String, Object> identity = identity(request);
        String caller = caller(identity);
        String correlationId = header(request, "x-ms-correlation-request-id");
        PolicyEngine.Request evaluation = new PolicyEngine.Request(call, new LinkedHashMap<>(content),
            request == null ? null : request.queryParams().get("api-version"), identity);
        Outcome outcome;
        try {
            outcome = engine.evaluate(evaluation);
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
            call.body().replace(evaluation.content());
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
        compliance.forgetResource(call.id().id());
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

    // ── Caller identity ─────────────────────────────────────────────────────────

    /**
     * The identity claims {@code requestContext().identity} exposes, read from the bearer token without
     * verifying it: {@code idtyp}, {@code appid}, {@code oid} and the object-identifier claim Azure adds.
     */
    static Map<String, Object> identity(AzureRequest request) {
        Map<String, Object> identity = new LinkedHashMap<>();
        String authorization = header(request, "Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return identity;
        }
        String[] parts = authorization.substring(7).trim().split("\\.");
        if (parts.length < 2) {
            return identity;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(padded(parts[1]));
            Map<?, ?> claims = MAPPER.readValue(payload, Map.class);
            for (Map.Entry<?, ?> entry : claims.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (KEPT_CLAIMS.contains(key) && entry.getValue() != null) {
                    identity.put(key, entry.getValue());
                }
            }
            if (!identity.containsKey("appid") && identity.get("azp") != null) {
                identity.put("appid", identity.get("azp"));
            }
            if (!identity.containsKey("idtyp")) {
                identity.put("idtyp", identity.containsKey("upn") || identity.containsKey("preferred_username") ? "user" : "app");
            }
            if (identity.get("oid") != null) {
                identity.put("http://schemas.microsoft.com/identity/claims/objectidentifier", identity.get("oid"));
            }
        } catch (IOException | IllegalArgumentException e) {
            LOG.debugf("Policy: bearer token payload is not readable: %s", e.getMessage());
        }
        return identity;
    }

    private static String caller(Map<String, Object> identity) {
        for (String claim : List.of("upn", "preferred_username", "oid", "appid")) {
            if (identity.get(claim) instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String header(AzureRequest request, String name) {
        return request == null || request.headers() == null ? null : request.headers().getHeaderString(name);
    }

    private static String padded(String base64url) {
        int remainder = base64url.length() % 4;
        return remainder == 0 ? base64url : base64url + "=".repeat(4 - remainder);
    }

}
