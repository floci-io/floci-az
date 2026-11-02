package io.floci.az.services.entra;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.SyntheticHttpHeaders;
import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmRequestRule;
import io.floci.az.core.arm.ArmVerdict;
import io.floci.az.core.cloud.ScopeContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * A token's tenant must be the tenant of the subscription the request names. Under
 * {@link EmulatorConfig#strictTokens()} a mismatch is {@code 401 InvalidAuthenticationTokenTenant}; otherwise the path
 * wins and a warning is logged, whenever the token's tenant was read at all ({@link EmulatorConfig#tenantScoping()}).
 *
 * <p>Runs after {@link TokenRule}, which verifies the {@code tid} under strict tokens, and before the subscription
 * rule, so a refused request registers no subscription and writes no Activity Log entry. An unknown subscription's
 * tenant is the one its first use would register it under. A request that names no subscription passes, and so does
 * one floci-az makes to itself ({@link SyntheticHttpHeaders}), whose caller was checked on the way in.
 *
 * <p>Only the code is in the local reference sources. The status, the challenge and the message are an emulator
 * choice, recorded in {@code docs/services/arm.md}; the message opens with the wording real ARM errors report.
 */
@ApplicationScoped
public class TenantMatchRule implements ArmRequestRule {

    /** Position in the gate: after the token is verified, before anything acts for the subscription. */
    public static final int ORDER = 0;

    private static final Logger LOG = Logger.getLogger(TenantMatchRule.class);

    private final EmulatorConfig config;
    private final boolean strict;

    @Inject
    public TenantMatchRule(EmulatorConfig config) {
        this(config, config.strictTokens());
    }

    TenantMatchRule(EmulatorConfig config, boolean strict) {
        this.config = config;
        this.strict = strict;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ArmVerdict enter(ArmCall call) {
        ScopeContext scope = call.scope();
        AzureRequest request = call.request();
        if (scope == null || request == null || request.headers() instanceof SyntheticHttpHeaders
            || scope.subscriptionId() == null || scope.tenantId() == null || scope.tokenTenantId() == null
            || scope.tenantId().equalsIgnoreCase(scope.tokenTenantId())) {
            return ArmVerdict.proceed();
        }
        if (!strict) {
            // The tid is an unverified hint here, so it stays out of the log.
            LOG.warnv("{0} {1} carries a token from another tenant than subscription {2}''s ({3}); serving it, since "
                + "floci-az.parity-overrides.strict-tokens is off", call.method(), request.resourcePath(),
                scope.subscriptionId(), scope.tenantId());
            return ArmVerdict.proceed();
        }
        LOG.infov("Refused {0} {1}: the token''s tenant is not subscription {2}''s, {3} "
            + "(floci-az.parity-overrides.strict-tokens is on)", call.method(), request.resourcePath(),
            scope.subscriptionId(), scope.tenantId());
        return ArmVerdict.respond(refusal(scope, RequestUrls.resolveBaseUrl(request, config)));
    }

    private static Response refusal(ScopeContext scope, String baseUrl) {
        String authority = baseUrl + "/" + scope.tenantId();
        String message = "The access token is from the wrong issuer '" + baseUrl + "/" + scope.tokenTenantId()
            + "/'. It must match the tenant '" + authority + "/' associated with this subscription. Please use the "
            + "authority (URL) '" + authority + "' to get the token.";
        return ArmErrors.unauthorized("InvalidAuthenticationTokenTenant", message, authority,
            "The access token is from the wrong issuer. It must match the tenant associated with this subscription. "
                + "Please use correct authority to get the token.");
    }
}
