package io.floci.az.core.arm;

import io.floci.az.config.EmulatorConfig.SubscriptionConfig;
import io.floci.az.config.EmulatorConfig.TenantConfig;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.ScopeContext;
import io.floci.az.core.cloud.ScopeSource;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.DirectoryOrigin;
import io.floci.az.core.directory.DirectorySeed;
import io.floci.az.core.directory.SubscriptionState;
import io.floci.az.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SubscriptionRule: first use, strict 404 and read-only states")
class SubscriptionRuleTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String SUB = "00000000-0000-0000-0000-000000000001";

    private static AzureDirectory directory() {
        return new AzureDirectory(DirectorySeed.of("AzureCloud", TENANT, SUB, List.of(tenant("other")),
            List.of(subscription("warned", "Warned"), subscription("disabled", "Disabled"),
                subscription("pastdue", "PastDue"), subscription("deleted", "Deleted"))), new InMemoryStorage<>());
    }

    private static ArmCall call(String method, String path, String tokenTenantId) {
        ArmResourceId id = ArmResourceId.parse(path).orElseThrow();
        ScopeContext scope = new ScopeContext(CloudCatalog.forNameOrPublic(null), TENANT, id.subscriptionId(), null,
            tokenTenantId, ScopeSource.ARM_PATH);
        return new ArmCall(scope, id, null, method, null);
    }

    private static Map<?, ?> error(ArmVerdict verdict) {
        Response response = assertInstanceOf(ArmVerdict.Respond.class, verdict).response();
        return (Map<?, ?>) ((Map<?, ?>) response.getEntity()).get("error");
    }

    private static int status(ArmVerdict verdict) {
        return assertInstanceOf(ArmVerdict.Respond.class, verdict).response().getStatus();
    }

    @Test
    void aKnownEnabledSubscriptionPasses() {
        SubscriptionRule rule = new SubscriptionRule(directory(), true);

        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(call("PUT", "subscriptions/" + SUB + "/resourceGroups/rg", null)));
    }

    @Test
    void aPathWithoutASubscriptionPassesEvenWhenStrict() {
        SubscriptionRule rule = new SubscriptionRule(directory(), true);

        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(call("GET", "subscriptions", null)));
        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(call("GET", "tenants", null)));
        assertInstanceOf(ArmVerdict.Proceed.class,
            rule.enter(call("GET", "providers/Microsoft.Authorization/policyDefinitions", null)));
    }

    @Test
    void anUnknownSubscriptionIsRegisteredOnFirstUseOutsideStrictMode() {
        AzureDirectory directory = directory();
        SubscriptionRule rule = new SubscriptionRule(directory, false);

        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(call("PUT", "subscriptions/new-sub/resourceGroups/rg", null)));

        assertEquals(DirectoryOrigin.FIRST_USE, directory.subscription("new-sub").orElseThrow().origin());
        assertEquals(TENANT, directory.subscription("new-sub").orElseThrow().tenantId());
    }

    @Test
    void firstUseFollowsTheTokenTenantTheDirectoryHolds() {
        AzureDirectory directory = directory();
        SubscriptionRule rule = new SubscriptionRule(directory, false);

        rule.enter(call("GET", "subscriptions/token-sub", "other"));

        assertEquals("other", directory.subscription("token-sub").orElseThrow().tenantId());
    }

    @Test
    void anUnknownSubscriptionIsNotFoundInStrictModeWithAzuresMessage() {
        AzureDirectory directory = directory();
        SubscriptionRule rule = new SubscriptionRule(directory, true);
        String unknown = "ae0a5678-da86-4bd9-a3a2-9a7558415de5";

        ArmVerdict verdict = rule.enter(call("GET", "subscriptions/" + unknown + "/resourceGroups", null));

        assertEquals(404, status(verdict));
        assertEquals(Map.of("code", "SubscriptionNotFound",
            "message", "The subscription '" + unknown + "' could not be found."), error(verdict));
        assertEquals(Optional.empty(), directory.subscription(unknown), "strict mode registers nothing");
    }

    @Test
    void aMalformedIdIsAcceptedInDevAndNotFoundInStrict() {
        assertInstanceOf(ArmVerdict.Proceed.class,
            new SubscriptionRule(directory(), false).enter(call("GET", "subscriptions/not-a-guid", null)));
        assertEquals("SubscriptionNotFound",
            error(new SubscriptionRule(directory(), true).enter(call("GET", "subscriptions/not-a-guid", null))).get("code"));
    }

    @Test
    void readOnlyStatesServeReadsAndDeletesAndRefuseWrites() {
        SubscriptionRule rule = new SubscriptionRule(directory(), false);
        for (String sub : List.of("warned", "disabled", "deleted")) {
            for (String method : List.of("GET", "HEAD", "DELETE")) {
                assertInstanceOf(ArmVerdict.Proceed.class,
                    rule.enter(call(method, "subscriptions/" + sub + "/resourceGroups/rg", null)), sub + " " + method);
            }
            for (String method : List.of("PUT", "PATCH", "POST")) {
                ArmVerdict verdict = rule.enter(call(method, "subscriptions/" + sub + "/resourceGroups/rg", null));
                assertEquals(409, status(verdict), sub + " " + method);
                assertEquals("ReadOnlyDisabledSubscription", error(verdict).get("code"));
                assertTrue(((String) error(verdict).get("message")).startsWith("The subscription '" + sub + "' is "),
                    String.valueOf(error(verdict)));
            }
        }
    }

    @Test
    void aPastDueSubscriptionStaysWritable() {
        SubscriptionRule rule = new SubscriptionRule(directory(), true);

        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(call("PUT", "subscriptions/pastdue/resourceGroups/rg", null)));
        assertTrue(SubscriptionState.PAST_DUE.allowsWrites());
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

    private static SubscriptionConfig subscription(String id, String state) {
        return new SubscriptionConfig() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Optional<String> tenantId() {
                return Optional.empty();
            }

            @Override
            public Optional<String> displayName() {
                return Optional.empty();
            }

            @Override
            public Optional<String> state() {
                return Optional.of(state);
            }
        };
    }
}
