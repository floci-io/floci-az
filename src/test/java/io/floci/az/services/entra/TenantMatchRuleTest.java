package io.floci.az.services.entra;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.config.EmulatorConfig.TenantConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.SyntheticHttpHeaders;
import io.floci.az.core.arm.ArmCall;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmVerdict;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.ScopeContext;
import io.floci.az.core.cloud.ScopeSource;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.DirectorySeed;
import io.floci.az.core.storage.InMemoryStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("TenantMatchRule: when a token's tenant is compared, and what a mismatch does")
class TenantMatchRuleTest {

    private static final String TENANT = "aaaaaaaa-0000-0000-0000-000000000000";
    private static final String OTHER = "bbbbbbbb-0000-0000-0000-000000000000";
    private static final String UNKNOWN = "cccccccc-0000-0000-0000-000000000000";

    private static ArmCall call(String subscription, String tenant, String tokenTenant, SyntheticHttpHeaders headers) {
        AzureRequest request = new AzureRequest("GET", "arm", "arm", "subscriptions", headers, null, Map.of(), Map.of(),
            null, true, "floci-az", null, "subscriptions", null, "floci-az:4577");
        ScopeContext scope = new ScopeContext(CloudCatalog.forNameOrPublic(null), tenant, subscription, null, tokenTenant,
            ScopeSource.ARM_PATH);
        return new ArmCall(scope, null, null, "GET", null, null, request);
    }

    /** The call a request for an unknown subscription makes: the scope predicted the token's own tenant as owner. */
    private static ArmCall firstUse(String tokenTenant) {
        ArmCall call = call(UNKNOWN, tokenTenant, tokenTenant, null);
        return new ArmCall(call.scope(), ArmResourceId.parse("subscriptions/" + UNKNOWN).orElseThrow(), null, "GET", null,
            null, call.request());
    }

    private static AzureDirectory directory() {
        return new AzureDirectory(DirectorySeed.of("AzureCloud", TENANT, "dddddddd-0000-0000-0000-000000000000",
            List.of(tenant(OTHER)), List.of()), new InMemoryStorage<>());
    }

    private static TenantMatchRule rule(boolean strict) {
        return new TenantMatchRule(mock(EmulatorConfig.class), directory(), strict, false);
    }

    private static TenantMatchRule rule(AzureDirectory directory, boolean strictSubscriptions) {
        return new TenantMatchRule(mock(EmulatorConfig.class), directory, true, strictSubscriptions);
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

    @Test
    void aFirstUseRegistersTheSubscriptionUnderTheCallersTenant() {
        AzureDirectory directory = directory();

        assertInstanceOf(ArmVerdict.Proceed.class, rule(directory, false).enter(firstUse(OTHER)));

        assertEquals(OTHER, directory.subscription(UNKNOWN).orElseThrow().tenantId());
    }

    @Test
    void aSubscriptionAnotherTenantRegisteredSinceTheScopeWasResolvedIsRefused() {
        AzureDirectory directory = directory();
        directory.registerOnFirstUse(UNKNOWN, OTHER);

        ArmVerdict verdict = rule(directory, false).enter(firstUse(TENANT));

        ArmVerdict.Respond refusal = assertInstanceOf(ArmVerdict.Respond.class, verdict);
        assertEquals(401, refusal.response().getStatus());
        assertTrue(refusal.response().getHeaderString("WWW-Authenticate").contains("/" + OTHER + "\""),
            "the refusal names the tenant that registered it");
    }

    @Test
    void aRefusedRequestRegistersNothing() {
        AzureDirectory directory = directory();
        ArmCall call = firstUse(TENANT);
        ArmCall mismatch = new ArmCall(new ScopeContext(call.scope().cloud(), TENANT, UNKNOWN, null, OTHER,
            ScopeSource.ARM_PATH), call.id(), null, "GET", null, null, call.request());

        assertInstanceOf(ArmVerdict.Respond.class, rule(directory, false).enter(mismatch));

        assertEquals(Optional.empty(), directory.subscription(UNKNOWN));
    }

    @Test
    void strictSubscriptionsLeaveAnUnknownSubscriptionForTheSubscriptionRule() {
        AzureDirectory directory = directory();

        assertInstanceOf(ArmVerdict.Proceed.class, rule(directory, true).enter(firstUse(OTHER)));

        assertEquals(Optional.empty(), directory.subscription(UNKNOWN));
    }

    @Test
    void ofTwoTenantsRacingToFirstUseOnlyTheOneThatRegisteredIsServed() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 200; round++) {
                AzureDirectory directory = directory();
                TenantMatchRule rule = rule(directory, false);
                CountDownLatch start = new CountDownLatch(2);
                List<Future<ArmVerdict>> verdicts = new ArrayList<>();
                for (String tenant : List.of(TENANT, OTHER)) {
                    verdicts.add(pool.submit(() -> {
                        start.countDown();
                        start.await();
                        return rule.enter(firstUse(tenant));
                    }));
                }

                String owner = servedTenant(verdicts);
                assertEquals(directory.subscription(UNKNOWN).orElseThrow().tenantId(), owner);
            }
        }
    }

    /** The one tenant served of {@link #TENANT} and {@link #OTHER}, submitted in that order. */
    private static String servedTenant(List<Future<ArmVerdict>> verdicts) throws InterruptedException, ExecutionException {
        boolean firstServed = verdicts.get(0).get() instanceof ArmVerdict.Proceed;
        boolean secondServed = verdicts.get(1).get() instanceof ArmVerdict.Proceed;
        assertTrue(firstServed != secondServed, "exactly one tenant is served");
        return firstServed ? TENANT : OTHER;
    }

    private static TenantConfig tenant(String id) {
        return new TenantConfig() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Optional<String> displayName() {
                return Optional.empty();
            }

            @Override
            public Optional<String> defaultDomain() {
                return Optional.empty();
            }

            @Override
            public Optional<String> cloud() {
                return Optional.empty();
            }
        };
    }
}
