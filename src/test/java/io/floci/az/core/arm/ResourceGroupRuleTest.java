package io.floci.az.core.arm;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ResourceGroupRule: strict 404 and creation on exit")
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
    void strictRefusesAResourcePutUnderAMissingGroup() {
        ResourceGroupRule rule = new ResourceGroupRule(new ResourceGroups(null), true, false);

        ArmVerdict verdict = rule.enter(call("PUT", ACCOUNT));

        Response response = assertInstanceOf(ArmVerdict.Respond.class, verdict).response();
        assertEquals(404, response.getStatus());
        Map<?, ?> error = (Map<?, ?>) ((Map<?, ?>) response.getEntity()).get("error");
        assertEquals("ResourceGroupNotFound", error.get("code"));
        assertEquals("Resource group 'rg-rule' could not be found.", error.get("message"));
    }

    @Test
    void strictRefusesReadsActionsAndDeletesUnderAMissingGroupToo() {
        ResourceGroupRule rule = new ResourceGroupRule(new ResourceGroups(null), true, false);

        for (ArmCall call : new ArmCall[] {call("GET", ACCOUNT), call("DELETE", ACCOUNT),
                call("POST", ACCOUNT + "/listKeys"), call("GET", "subscriptions/" + SUB
                    + "/resourceGroups/rg-rule/providers/Microsoft.Storage/storageAccounts")}) {
            Response response = assertInstanceOf(ArmVerdict.Respond.class, rule.enter(call)).response();
            assertEquals(404, response.getStatus(), call.method() + " " + call.id());
        }
    }

    @Test
    void strictLetsTheGroupItselfAndEverythingAboveItThrough() {
        ResourceGroups groups = new ResourceGroups(null);
        ResourceGroupRule rule = new ResourceGroupRule(groups, true, false);

        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(call("PUT", "subscriptions/" + SUB + "/resourceGroups/rg-rule")));
        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(call("GET", "subscriptions/" + SUB + "/resourceGroups/rg-rule")));
        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(call("GET",
            "subscriptions/" + SUB + "/providers/Microsoft.Storage/storageAccounts")));
        groups.put(new ResourceGroup(SUB, "RG-RULE", "eastus", null, null, ResourceGroup.SUCCEEDED));
        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(call("PUT", ACCOUNT)));
    }

    @Test
    void autoCreateTakesTheResourcesLocationAfterA2xx() {
        ResourceGroups groups = new ResourceGroups(null);
        ResourceGroupRule rule = new ResourceGroupRule(groups, false, true);

        rule.exit(call("PUT", ACCOUNT), Response.status(200).entity(Map.of("location", "westus2")).build());

        assertEquals("westus2", groups.get(SUB, "rg-rule").orElseThrow().location());
    }

    @Test
    void autoCreateReadsTheLocationOfAJacksonBodyToo() {
        ResourceGroups groups = new ResourceGroups(null);
        ResourceGroupRule rule = new ResourceGroupRule(groups, false, true);
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("location", "westeurope");

        rule.exit(call("PUT", ACCOUNT), Response.status(201).entity(body).build());

        assertEquals("westeurope", groups.get(SUB, "rg-rule").orElseThrow().location());
    }

    @Test
    void aJacksonBodyWithoutATextLocationFallsBackToTheCloudsDefault() {
        ResourceGroups groups = new ResourceGroups(null);
        ResourceGroupRule rule = new ResourceGroupRule(groups, false, true);
        ObjectNode body = JsonNodeFactory.instance.objectNode().putNull("location");

        rule.exit(call("PUT", ACCOUNT), Response.status(201).entity(body).build());

        assertEquals(CloudCatalog.forNameOrPublic("AzureUSGovernment").effectiveDefaultLocation(),
            groups.get(SUB, "rg-rule").orElseThrow().location());
    }

    @Test
    void autoCreateFallsBackToTheCloudsDefaultLocation() {
        ResourceGroups groups = new ResourceGroups(null);
        ResourceGroupRule rule = new ResourceGroupRule(groups, false, true);

        rule.exit(call("PUT", ACCOUNT), Response.status(201).build());

        assertEquals(CloudCatalog.forNameOrPublic("AzureUSGovernment").effectiveDefaultLocation(),
            groups.get(SUB, "rg-rule").orElseThrow().location());
    }

    @Test
    void autoCreateSkipsRefusedPutsAndOtherMethods() {
        ResourceGroups groups = new ResourceGroups(null);
        ResourceGroupRule rule = new ResourceGroupRule(groups, false, true);

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
        ResourceGroupRule rule = new ResourceGroupRule(groups, false, true);

        rule.exit(call("PUT", ACCOUNT), Response.ok(Map.of("location", "westus2")).build());

        ResourceGroup group = groups.get(SUB, "rg-rule").orElseThrow();
        assertEquals("eastus", group.location());
        assertTrue(group.tags().containsKey("keep"));
    }

    @Test
    void writesAreNeitherRefusedNorCountedUnlessTheDeleteGuardIsOn() {
        ResourceGroups groups = new ResourceGroups(null);
        groups.put(new ResourceGroup(SUB, "rg-rule", "eastus", null, null, ResourceGroup.SUCCEEDED));
        ResourceGroupRule rule = new ResourceGroupRule(groups, false, false);
        assertTrue(groups.beginDelete(SUB, "rg-rule"));

        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(call("PUT", ACCOUNT)));
    }

    @Test
    void theDeleteGuardRefusesAWriteUnderAGroupBeingDeletedInAnyCasing() {
        ResourceGroups groups = new ResourceGroups(null);
        groups.put(new ResourceGroup(SUB, "RG-RULE", "eastus", null, null, ResourceGroup.SUCCEEDED));
        ResourceGroupRule rule = new ResourceGroupRule(groups, false, false, true);
        assertTrue(groups.beginDelete(SUB, "rg-Rule"));

        Response response = assertInstanceOf(ArmVerdict.Respond.class, rule.enter(call("PUT", ACCOUNT))).response();
        assertEquals(409, response.getStatus());
        Map<?, ?> error = (Map<?, ?>) ((Map<?, ?>) response.getEntity()).get("error");
        assertEquals("ResourceGroupBeingDeleted", error.get("code"));
        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(call("GET", ACCOUNT)));
        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(call("DELETE", ACCOUNT)));
        assertInstanceOf(ArmVerdict.Proceed.class,
            rule.enter(call("PUT", "subscriptions/" + SUB + "/resourceGroups/rg-rule")));

        groups.endDelete(SUB, "rg-rule");
        ArmCall admitted = call("PUT", ACCOUNT);
        assertInstanceOf(ArmVerdict.Proceed.class, rule.enter(admitted));
        rule.exit(admitted, null);
    }
}
