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
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.Subscription;
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
 * tenant is the one its first use registers it under: a matching token registers it here, in one step with the
 * comparison, so of two tenants racing to first use one subscription only the one that registered it is served. A
 * request that names no subscription passes, and so does one floci-az makes to itself
 * ({@link SyntheticHttpHeaders}), whose caller was checked on the way in.
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
    private final AzureDirectory directory;
    private final boolean strict;
    private final boolean strictSubscriptions;

    @Inject
    public TenantMatchRule(EmulatorConfig config, AzureDirectory directory) {
        this(config, directory, config.strictTokens(), config.strictSubscriptions());
    }

    TenantMatchRule(EmulatorConfig config, AzureDirectory directory, boolean strict, boolean strictSubscriptions) {
        this.config = config;
        this.directory = directory;
        this.strict = strict;
        this.strictSubscriptions = strictSubscriptions;
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
            || scope.subscriptionId() == null || scope.tenantId() == null || scope.tokenTenantId() == null) {
            return ArmVerdict.proceed();
        }
        boolean predicted = scope.tenantId().equalsIgnoreCase(scope.tokenTenantId());
        if (!strict) {
            if (predicted) {
                return ArmVerdict.proceed();
            }
            // The tid is an unverified hint here, so it stays out of the log.
            LOG.warnv("{0} {1} carries a token from another tenant than subscription {2}''s ({3}); serving it, since "
                + "floci-az.parity-overrides.strict-tokens is off", call.method(), request.resourcePath(),
                scope.subscriptionId(), scope.tenantId());
            return ArmVerdict.proceed();
        }
        String owner = predicted ? owner(call, scope) : scope.tenantId();
        if (owner.equalsIgnoreCase(scope.tokenTenantId())) {
            return ArmVerdict.proceed();
        }
        LOG.infov("Refused {0} {1}: the token''s tenant is not subscription {2}''s, {3} "
            + "(floci-az.parity-overrides.strict-tokens is on)", call.method(), request.resourcePath(),
            scope.subscriptionId(), owner);
        return ArmVerdict.respond(refusal(owner, scope.tokenTenantId(), RequestUrls.resolveBaseUrl(request, config)));
    }

    /**
     * The subscription's owner now, since the scope only predicted it when the request arrived. Registering here makes
     * the comparison and the registration one step; with strict subscriptions an unknown subscription stays
     * unregistered, for the subscription rule to refuse.
     */
    private String owner(ArmCall call, ScopeContext scope) {
        if (!strictSubscriptions && call.id() != null && call.id().subscriptionId() != null) {
            return directory.registerOnFirstUse(call.id().subscriptionId(), scope.tokenTenantId()).tenantId();
        }
        return directory.subscription(scope.subscriptionId()).map(Subscription::tenantId).orElse(scope.tenantId());
    }

    private static Response refusal(String owner, String tokenTenant, String baseUrl) {
        String authority = baseUrl + "/" + owner;
        String message = "The access token is from the wrong issuer '" + baseUrl + "/" + tokenTenant
            + "/'. It must match the tenant '" + authority + "/' associated with this subscription. Please use the "
            + "authority (URL) '" + authority + "' to get the token.";
        return ArmErrors.unauthorized("InvalidAuthenticationTokenTenant", message, authority,
            "The access token is from the wrong issuer. It must match the tenant associated with this subscription. "
                + "Please use correct authority to get the token.");
    }
}
