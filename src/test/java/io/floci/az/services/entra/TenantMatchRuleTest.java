package io.floci.az.services.entra;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.SyntheticHttpHeaders;
import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.arm.ArmVerdict;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.ScopeContext;
import io.floci.az.core.cloud.ScopeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

@DisplayName("TenantMatchRule: when a token's tenant is compared, and what a mismatch does")
class TenantMatchRuleTest {

    private static final String TENANT = "aaaaaaaa-0000-0000-0000-000000000000";
    private static final String OTHER = "bbbbbbbb-0000-0000-0000-000000000000";

    private static ArmCall call(String subscription, String tenant, String tokenTenant, SyntheticHttpHeaders headers) {
        AzureRequest request = new AzureRequest("GET", "arm", "arm", "subscriptions", headers, null, Map.of(), Map.of(),
            null, true, "floci-az", null, "subscriptions", null, "floci-az:4577");
        ScopeContext scope = new ScopeContext(CloudCatalog.forNameOrPublic(null), tenant, subscription, null, tokenTenant,
            ScopeSource.ARM_PATH);
        return new ArmCall(scope, null, null, "GET", null, null, request);
    }

    private static TenantMatchRule rule(boolean strict) {
        return new TenantMatchRule(mock(EmulatorConfig.class), strict);
    }

    @Test
    void aMismatchIsRefusedUnderStrictTokens() {
        ArmVerdict verdict = rule(true).enter(call("sub", TENANT, OTHER, null));

        ArmVerdict.Respond refusal = assertInstanceOf(ArmVerdict.Respond.class, verdict);
        assertEquals(401, refusal.response().getStatus());
    }

    @Test
    void aMismatchIsServedWithStrictTokensOff() {
        assertInstanceOf(ArmVerdict.Proceed.class, rule(false).enter(call("sub", TENANT, OTHER, null)));
    }

    @Test
    void tenantsCompareCaseInsensitively() {
        assertInstanceOf(ArmVerdict.Proceed.class,
            rule(true).enter(call("sub", TENANT, TENANT.toUpperCase(), null)));
    }

    @Test
    void nothingToCompareProceeds() {
        assertInstanceOf(ArmVerdict.Proceed.class, rule(true).enter(call(null, null, OTHER, null)));
        assertInstanceOf(ArmVerdict.Proceed.class, rule(true).enter(call("sub", TENANT, null, null)));
    }

    @Test
    void anInternalRequestPasses() {
        assertInstanceOf(ArmVerdict.Proceed.class,
            rule(true).enter(call("sub", TENANT, OTHER, new SyntheticHttpHeaders(Map.of()))));
    }
}
