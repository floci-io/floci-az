package io.floci.az.services.monitor.activitylog;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.arm.ArmRequestRule;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmVerdict;
import io.floci.az.core.auth.BearerClaims;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The gate rule that writes the {@code Administrative} Activity Log: every PUT, PATCH, DELETE and POST
 * action on a provider resource or a resource group gets a {@code BeginRequest} entry on entry and an
 * {@code EndRequest} entry on exit carrying the outcome, the way Resource Manager logs them. Reads are
 * not logged, as in Azure. Entries name the caller from the request's bearer token. A write a later gate rule
 * refuses is logged as {@code Failed} at {@code Error} level with that rule's error.
 */
@ApplicationScoped
public class ActivityLogRule implements ArmRequestRule {

    /**
     * Position in the gate: right after {@code SubscriptionRule} and ahead of every other rule, so a write refused
     * by a later rule (an unregistered provider, a missing resource group, an invalid or taken name, a policy deny)
     * is still logged: the gate hands every entered rule's exit the final response, whichever rule gave it. Azure
     * shows validation, preflight and policy refusals in the Activity Log. A request refused by the subscription
     * rule is not logged, since there is no subscription whose log would hold it.
     */
    public static final int ORDER = 2;

    private final ActivityLog log;
    private final boolean enabled;

    /** The operations begun and not yet ended, by call identity. */
    private final Map<ArmCall, ActivityLog.RequestFacts> begun = Collections.synchronizedMap(new IdentityHashMap<>());

    @Inject
    public ActivityLogRule(ActivityLog log, EmulatorConfig config) {
        this(log, config.services().monitor().enabled() && config.services().monitor().activityLog().enabled());
    }

    ActivityLogRule(ActivityLog log, boolean enabled) {
        this.log = log;
        this.enabled = enabled;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ArmVerdict enter(ArmCall call) {
        if (!enabled || !isLogged(call)) {
            return ArmVerdict.proceed();
        }
        ActivityLog.RequestFacts facts = facts(call);
        begun.put(call, facts);
        log.record(entry(facts, "BeginRequest", "Started", "", "Informational", Map.of()));
        return ArmVerdict.proceed();
    }

    @Override
    public void exit(ArmCall call, Response response) {
        ActivityLog.RequestFacts facts = begun.remove(call);
        if (facts == null) {
            return;
        }
        int status = response == null ? 500 : response.getStatus();
        boolean succeeded = status / 100 == 2;
        String subStatus = ActivityLogEvent.subStatusOf(status);
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("statusCode", subStatus);
        properties.put("serviceRequestId", UUID.randomUUID().toString());
        if (!succeeded && response != null && response.getEntity() instanceof Map<?, ?> body
                && body.get("error") instanceof Map<?, ?> error) {
            properties.put("statusMessage", ActivityLog.json(Map.of("error", error)));
        }
        log.record(entry(facts, "EndRequest", succeeded ? "Succeeded" : "Failed", subStatus,
            succeeded ? "Informational" : "Error", properties));
    }

    /** Writes, deletes and actions on provider resources and on resource groups; never reads or listings. */
    static boolean isLogged(ArmCall call) {
        ArmResourceId id = call.id();
        if (id == null || call.method() == null) {
            return false;
        }
        boolean mutation = switch (call.method()) {
            case "PUT", "PATCH", "DELETE", "POST" -> true;
            default -> false;
        };
        if (!mutation || id.subscriptionId() == null) {
            return false;
        }
        if (id.providerNamespace() != null) {
            return !id.types().isEmpty() && (!id.isCollection() || "POST".equals(call.method()));
        }
        return id.resourceGroup() != null && id.types().size() <= 1;
    }

    /** The operation ARM names: {@code {type}/write}, {@code {type}/delete} or {@code {type}/{action}/action}. */
    static String operationName(ArmCall call) {
        ArmResourceId id = call.id();
        String type = resourceType(id);
        return switch (call.method()) {
            case "PUT", "PATCH" -> type + "/write";
            case "DELETE" -> type + "/delete";
            default -> id.isCollection() && id.providerNamespace() != null
                ? type + "/" + id.types().getLast() + "/action"
                : type + "/action";
        };
    }

    static String resourceType(ArmResourceId id) {
        if (id.providerNamespace() == null) {
            return "Microsoft.Resources/subscriptions/resourceGroups";
        }
        if (id.isCollection() && id.types().size() > 1) {
            return id.providerNamespace() + "/" + String.join("/", id.types().subList(0, id.types().size() - 1));
        }
        return id.typePath();
    }

    /** The resource the entry is about: the resource itself, or the resource an action was posted to. */
    static String resourceId(ArmResourceId id) {
        if (id.providerNamespace() == null) {
            return "/subscriptions/" + id.subscriptionId() + "/resourceGroups/" + id.resourceGroup();
        }
        if (id.isCollection() && id.types().size() > 1) {
            String path = id.id();
            int cut = path.lastIndexOf('/');
            return cut > 0 ? path.substring(0, cut) : path;
        }
        return id.id();
    }

    private static ActivityLog.RequestFacts facts(ArmCall call) {
        AzureRequest request = call.request();
        BearerClaims claims = BearerClaims.of(request);
        ArmResourceId id = call.id();
        String correlationId = header(request, "x-ms-correlation-request-id");
        String uri = request == null ? null
            : "/" + request.rawPath() + (request.rawQuery() == null ? "" : "?" + request.rawQuery());
        String tenantId = call.scope() == null ? null : call.scope().tenantId();
        if (tenantId == null && claims.claims().get("tid") != null) {
            tenantId = claims.claims().get("tid");
        }
        return new ActivityLog.RequestFacts(UUID.randomUUID().toString(),
            correlationId == null ? UUID.randomUUID().toString() : correlationId,
            claims.present() ? caller(claims) : null, claims.activityLogClaims(), operationName(call),
            call.method(), uri, header(request, "x-ms-client-request-id"),
            request == null ? null : request.remoteAddress(), resourceId(id), resourceType(id),
            id.providerNamespace() == null ? "Microsoft.Resources" : id.providerNamespace(), id.resourceGroup(),
            id.subscriptionId(), tenantId);
    }

    private static String caller(BearerClaims claims) {
        if (claims.upn() != null) {
            return claims.upn();
        }
        return claims.oid() != null ? claims.oid() : claims.appId();
    }

    private static ActivityLogEvent entry(ActivityLog.RequestFacts facts, String eventName, String status, String subStatus,
                                          String level, Map<String, String> properties) {
        Instant now = Instant.now();
        return new ActivityLogEvent(UUID.randomUUID().toString(), facts.operationId(), facts.correlationId(), now, now,
            "Administrative", eventName, facts.action(), level, status, subStatus, facts.caller(), facts.claims(),
            facts.action(), facts.resourceId(), facts.httpMethod(), facts.httpUri(), facts.clientRequestId(),
            facts.clientIpAddress(), facts.resourceId(), facts.resourceType(), facts.resourceProviderName(),
            facts.resourceGroupName(), facts.subscriptionId(), facts.tenantId(), properties);
    }

    private static String header(AzureRequest request, String name) {
        if (request == null || request.headers() == null) {
            return null;
        }
        String value = request.headers().getHeaderString(name);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
