package io.floci.az.core.arm;

import io.floci.az.core.directory.ProviderRegistrations;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@DisplayName("ProviderRule: strict 409 MissingSubscriptionRegistration until the namespace is registered")
class ProviderRuleTest {

    private static final String SUB = "00000000-0000-0000-0000-0000000000a1";
    private static final String ACCOUNT = "subscriptions/" + SUB
        + "/resourceGroups/rg-rule/providers/microsoft.storage/storageAccounts/ruleacct";
    private static final String STORAGE_ACCOUNTS = "Microsoft.Storage/storageAccounts";

    private static ArmCall call(String method, String path, String type) {
        return new ArmCall(null, ArmResourceId.parse(path).orElseThrow(), type, method, null);
    }

    private static ProviderRegistrations notRegisteredByDefault() {
        return new ProviderRegistrations(null, false);
    }

    @Test
    void strictRefusesARequestForATypeOfAnUnregisteredNamespace() {
        ProviderRule rule = new ProviderRule(notRegisteredByDefault(), true);

        Response response = assertInstanceOf(ArmVerdict.Respond.class,
            rule.enter(call("PUT", ACCOUNT, STORAGE_ACCOUNTS))).response();

        assertEquals(409, response.getStatus());
        Map<?, ?> error = (Map<?, ?>) ((Map<?, ?>) response.getEntity()).get("error");
        String message = "The subscription is not registered to use namespace 'microsoft.storage'. "
            + "See https://aka.ms/rps-not-found for how to register subscriptions.";
        assertEquals("MissingSubscriptionRegistration", error.get("code"));
        assertEquals(message, error.get("message"));
        assertEquals(List.of(Map.of("code", "MissingSubscriptionRegistration", "target", "microsoft.storage",
            "message", message)), error.get("details"));
    }

    @Test
    void aRegisteredNamespacePassesWhateverItsCasing() {
        ProviderRegistrations registrations = notRegisteredByDefault();
        registrations.register(SUB.toUpperCase(), "Microsoft.Storage");
        ProviderRule rule = new ProviderRule(registrations, true);

        assertEquals(ArmVerdict.proceed(), rule.enter(call("GET", ACCOUNT, STORAGE_ACCOUNTS)));
    }

    @Test
    void anUnregisteredNamespaceIsRefusedAgain() {
        ProviderRegistrations registrations = notRegisteredByDefault();
        registrations.register(SUB, "Microsoft.Storage");
        registrations.unregister(SUB, "Microsoft.Storage");
        ProviderRule rule = new ProviderRule(registrations, true);

        assertInstanceOf(ArmVerdict.Respond.class, rule.enter(call("DELETE", ACCOUNT, STORAGE_ACCOUNTS)));
    }

    @Test
    void pathsThatAddressNoDeclaredTypeAndResourceGroupsAlwaysPass() {
        ProviderRule rule = new ProviderRule(notRegisteredByDefault(), true);
        String sub = "subscriptions/" + SUB;

        for (ArmCall call : List.of(
                call("GET", sub + "/providers/Microsoft.Storage", null),
                call("POST", sub + "/providers/Microsoft.Storage/register", null),
                call("POST", sub + "/providers/Microsoft.Storage/checkNameAvailability", null),
                call("PUT", sub + "/resourceGroups/rg-rule", "Microsoft.Resources/resourceGroups"))) {
            assertEquals(ArmVerdict.proceed(), rule.enter(call), call.method() + " " + call.id());
        }
    }

    @Test
    void withoutTheSwitchNothingIsRefused() {
        ProviderRule rule = new ProviderRule(notRegisteredByDefault(), false);

        assertEquals(ArmVerdict.proceed(), rule.enter(call("PUT", ACCOUNT, STORAGE_ACCOUNTS)));
    }
}
