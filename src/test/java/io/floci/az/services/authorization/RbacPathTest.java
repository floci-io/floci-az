package io.floci.az.services.authorization;

import io.floci.az.services.authorization.RbacPath.ScopeKind;
import io.floci.az.services.authorization.RbacPath.Type;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RbacPathTest {

    private static final String RG = "subscriptions/s1/resourceGroups/rg1";
    private static final String VAULT = RG + "/providers/Microsoft.KeyVault/vaults/kv1";

    @Test
    void everyScopeKindIsRecognised() {
        assertEquals(ScopeKind.TENANT, parse("providers/Microsoft.Authorization/roleDefinitions").scopeKind());
        assertEquals(ScopeKind.MANAGEMENT_GROUP, parse("providers/Microsoft.Management/managementGroups/mg1"
                + "/providers/Microsoft.Authorization/roleAssignments/a").scopeKind());
        assertEquals(ScopeKind.SUBSCRIPTION, parse("subscriptions/s1/providers/Microsoft.Authorization/roleAssignments")
                .scopeKind());
        assertEquals(ScopeKind.RESOURCE_GROUP, parse(RG + "/providers/Microsoft.Authorization/roleAssignments/a")
                .scopeKind());
        assertEquals(ScopeKind.RESOURCE, parse(VAULT + "/providers/Microsoft.Authorization/roleAssignments/a")
                .scopeKind());
    }

    @Test
    void azurermsDoubleSlashTenantFormIsTheTenantRoot() {
        RbacPath path = parse("//providers/Microsoft.Authorization/roleDefinitions/acdd72a7-3385-48ef-bd42-f606fba81ae7");
        assertEquals("", path.scope());
        assertEquals(ScopeKind.TENANT, path.scopeKind());
        assertEquals("/", path.scopeId());
        assertEquals("/providers/Microsoft.Authorization/roleDefinitions/acdd72a7-3385-48ef-bd42-f606fba81ae7",
                path.resourceId());
    }

    @Test
    void theScopeIsWhatPrecedesTheLastAuthorizationSegmentInTheCallersCasing() {
        RbacPath path = parse(VAULT + "/providers/microsoft.authorization/ROLEASSIGNMENTS/a1");
        assertEquals(VAULT, path.scope());
        assertEquals(Type.ROLE_ASSIGNMENT, path.type());
        assertEquals("a1", path.name());
        assertEquals("s1", path.subscriptionId());
        assertEquals("/" + VAULT + "/providers/Microsoft.Authorization/roleAssignments/a1", path.resourceId());
    }

    @Test
    void onlyRoleTypesAreClaimed() {
        assertTrue(RbacPath.isRbacPath(RG + "/providers/Microsoft.Authorization/roleAssignments/a?api-version=x"));
        assertTrue(RbacPath.isRbacPath("/providers/Microsoft.Authorization/roleDefinitions"));
        assertFalse(RbacPath.isRbacPath(RG + "/providers/Microsoft.Authorization/policyAssignments/p"));
        assertFalse(RbacPath.isRbacPath(RG + "/providers/Microsoft.Authorization/locks/l"));
        assertFalse(RbacPath.isRbacPath(VAULT));
    }

    @Test
    void aRolePathUnderAPolicyAssignmentIsARolePath() {
        String policy = RG + "/providers/Microsoft.Authorization/policyAssignments/p";
        RbacPath path = parse(policy + "/providers/Microsoft.Authorization/roleAssignments/a");
        assertEquals(policy, path.scope());
        assertEquals(ScopeKind.RESOURCE, path.scopeKind());
    }

    @Test
    void aTenantOrManagementGroupScopeHasNoSubscription() {
        assertNull(parse("providers/Microsoft.Authorization/roleDefinitions").subscriptionId());
        assertNull(parse("providers/Microsoft.Management/managementGroups/mg1/providers/Microsoft.Authorization"
                + "/roleDefinitions").subscriptionId());
    }

    private static RbacPath parse(String path) {
        RbacPath parsed = RbacPath.parse(path);
        assertNotNull(parsed, path);
        return parsed;
    }
}
