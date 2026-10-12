package io.floci.az.core.arm;

import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.ScopeContext;
import io.floci.az.core.cloud.ScopeSource;
import io.floci.az.core.directory.ResourceGroup;
import io.floci.az.core.directory.ResourceGroups;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ResourceGroupRule: creation on exit")
class ResourceGroupRuleTest {

    private static final String SUB = "00000000-0000-0000-0000-000000000001";
    private static final String ACCOUNT = "subscriptions/" + SUB
        + "/resourceGroups/rg-rule/providers/Microsoft.Storage/storageAccounts/ruleacct";

    private static ArmCall call(String method, String path) {
        ArmResourceId id = ArmResourceId.parse(path).orElseThrow();
        ScopeContext scope = new ScopeContext(CloudCatalog.forNameOrPublic("AzureUSGovernment"), null,
            id.subscriptionId(), id.resourceGroup(), null, ScopeSource.ARM_PATH);
        return new ArmCall(scope, id, null, method, null);
    }

    @Test
    void autoCreateTakesTheResourcesLocationAfterA2xx() {
        ResourceGroups groups = new ResourceGroups(null);
        ResourceGroupRule rule = new ResourceGroupRule(groups, true);

        rule.exit(call("PUT", ACCOUNT), Response.status(200).entity(Map.of("location", "westus2")).build());

        assertEquals("westus2", groups.get(SUB, "rg-rule").orElseThrow().location());
    }

    @Test
    void autoCreateFallsBackToTheCloudsDefaultLocation() {
        ResourceGroups groups = new ResourceGroups(null);
        ResourceGroupRule rule = new ResourceGroupRule(groups, true);

        rule.exit(call("PUT", ACCOUNT), Response.status(201).build());

        assertEquals(CloudCatalog.forNameOrPublic("AzureUSGovernment").effectiveDefaultLocation(),
            groups.get(SUB, "rg-rule").orElseThrow().location());
    }

    @Test
    void autoCreateSkipsRefusedPutsAndOtherMethods() {
        ResourceGroups groups = new ResourceGroups(null);
        ResourceGroupRule rule = new ResourceGroupRule(groups, true);

        rule.exit(call("PUT", ACCOUNT), Response.status(400).build());
        rule.exit(call("PUT", ACCOUNT), null);
        rule.exit(call("POST", ACCOUNT + "/listKeys"), Response.ok().build());
        rule.exit(call("GET", ACCOUNT), Response.ok().build());

        assertFalse(groups.exists(SUB, "rg-rule"));
    }

    @Test
    void anExistingGroupIsNeverReplaced() {
        ResourceGroups groups = new ResourceGroups(null);
        groups.put(new ResourceGroup(SUB, "rg-rule", "eastus", Map.of("keep", "me"), null, ResourceGroup.SUCCEEDED));
        ResourceGroupRule rule = new ResourceGroupRule(groups, true);

        rule.exit(call("PUT", ACCOUNT), Response.ok(Map.of("location", "westus2")).build());

        ResourceGroup group = groups.get(SUB, "rg-rule").orElseThrow();
        assertEquals("eastus", group.location());
        assertTrue(group.tags().containsKey("keep"));
    }
}
