package io.floci.az.core.arm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ArmResourceId: ARM path parsing")
class ArmResourceIdTest {

    private static ArmResourceId parse(String path) {
        return ArmResourceId.parse(path).orElseThrow(() -> new AssertionError("did not parse: " + path));
    }

    @Test
    void parsesAResourceGroupScopedResource() {
        ArmResourceId id = parse("subscriptions/sub-1/resourceGroups/rg-1/providers/Microsoft.Storage/storageAccounts/acct1");

        assertEquals("/subscriptions/sub-1/resourceGroups/rg-1/providers/Microsoft.Storage/storageAccounts/acct1", id.id());
        assertEquals("sub-1", id.subscriptionId());
        assertEquals("rg-1", id.resourceGroup());
        assertEquals("Microsoft.Storage", id.providerNamespace());
        assertEquals(List.of("storageAccounts"), id.types());
        assertEquals(List.of("acct1"), id.names());
        assertEquals("acct1", id.name());
        assertEquals("Microsoft.Storage/storageAccounts", id.typePath());
        assertFalse(id.isCollection());
        assertFalse(id.isExtension());
        assertEquals("/subscriptions/sub-1/resourceGroups/rg-1", id.scope().id());
    }

    @Test
    void lowercaseResourceGroupsKeywordIsAccepted() {
        ArmResourceId id = parse("subscriptions/s/resourcegroups/My-RG/providers/Microsoft.Cache/redis/c1");

        assertEquals("My-RG", id.resourceGroup());
        assertEquals("Microsoft.Cache/redis", id.typePath());
        assertEquals("/subscriptions/s/resourcegroups/My-RG/providers/Microsoft.Cache/redis/c1", id.id());
    }

    @Test
    void canonicalKeyIsLowercaseWhileIdKeepsCase() {
        ArmResourceId upper = parse("subscriptions/S/resourceGroups/RG/providers/Microsoft.Storage/storageAccounts/Acct");
        ArmResourceId lower = parse("subscriptions/s/resourcegroups/rg/providers/microsoft.storage/storageaccounts/acct");

        assertEquals(upper.canonicalKey(), lower.canonicalKey());
        assertEquals("/subscriptions/s/resourcegroups/rg/providers/microsoft.storage/storageaccounts/acct",
            upper.canonicalKey());
        assertEquals("/subscriptions/S/resourceGroups/RG/providers/Microsoft.Storage/storageAccounts/Acct", upper.id());
    }

    @Test
    void capitalisedRootKeywordsAreAccepted() {
        ArmResourceId id = parse("Subscriptions/s/ResourceGroups/rg/Providers/Microsoft.Web/sites/app");

        assertEquals("s", id.subscriptionId());
        assertEquals("rg", id.resourceGroup());
        assertEquals("Microsoft.Web/sites", id.typePath());
    }

    @Test
    void leadingAndTrailingSlashesAndQueryStringAreDropped() {
        ArmResourceId id = parse("/subscriptions/s/resourceGroups/rg/providers/Microsoft.KeyVault/vaults/kv1/?api-version=2023-07-01");

        assertEquals("/subscriptions/s/resourceGroups/rg/providers/Microsoft.KeyVault/vaults/kv1", id.id());
        assertEquals("kv1", id.name());
    }

    @Test
    void emptySegmentsAreSkipped() {
        ArmResourceId id = parse("subscriptions//s/resourceGroups/rg//providers/Microsoft.Sql/servers/db1");

        assertEquals("/subscriptions/s/resourceGroups/rg/providers/Microsoft.Sql/servers/db1", id.id());
        assertEquals("s", id.subscriptionId());
    }

    @Test
    void subscriptionPathHasNoProviderSection() {
        ArmResourceId id = parse("subscriptions/s?api-version=2022-12-01");

        assertEquals("/subscriptions/s", id.id());
        assertEquals("s", id.subscriptionId());
        assertNull(id.resourceGroup());
        assertNull(id.providerNamespace());
        assertTrue(id.types().isEmpty());
        assertNull(id.typePath());
        assertNull(id.scope());
        assertNull(id.parent());
    }

    @Test
    void resourceGroupPathHasNoProviderSection() {
        ArmResourceId id = parse("subscriptions/s/resourceGroups/rg");

        assertEquals("rg", id.resourceGroup());
        assertNull(id.providerNamespace());
        assertTrue(id.types().isEmpty());
        assertEquals("/subscriptions/s", id.parent().id());
    }

    @Test
    void builtInCollectionsParseAsTypesWithoutANamespace() {
        ArmResourceId locations = parse("subscriptions/s/locations");
        assertEquals(List.of("locations"), locations.types());
        assertNull(locations.providerNamespace());
        assertTrue(locations.isCollection());
        assertEquals("/subscriptions/s", locations.parent().id());

        ArmResourceId groups = parse("subscriptions/s/resourcegroups");
        assertNull(groups.resourceGroup());
        assertEquals(List.of("resourcegroups"), groups.types());
        assertEquals("/subscriptions/s", groups.parent().id());

        ArmResourceId rgResources = parse("subscriptions/s/resourceGroups/rg/resources");
        assertEquals("rg", rgResources.resourceGroup());
        assertEquals(List.of("resources"), rgResources.types());
        assertEquals("/subscriptions/s/resourceGroups/rg", rgResources.parent().id());
    }

    @Test
    void rootCollectionsHaveNoParent() {
        ArmResourceId subscriptions = parse("subscriptions");
        assertNull(subscriptions.subscriptionId());
        assertEquals(List.of("subscriptions"), subscriptions.types());
        assertNull(subscriptions.parent());

        ArmResourceId tenants = parse("tenants?api-version=2022-12-01");
        assertEquals(List.of("tenants"), tenants.types());
        assertNull(tenants.parent());
    }

    @Test
    void providerRegistrationPaths() {
        ArmResourceId providers = parse("subscriptions/s/providers");
        assertNull(providers.providerNamespace());
        assertEquals(List.of("providers"), providers.types());

        ArmResourceId namespace = parse("subscriptions/s/providers/Microsoft.Storage");
        assertEquals("Microsoft.Storage", namespace.providerNamespace());
        assertTrue(namespace.types().isEmpty());
        assertNull(namespace.typePath());
        assertEquals("/subscriptions/s", namespace.parent().id());
    }

    @Test
    void nestedChildTypesFollowTheAlternation() {
        ArmResourceId id = parse("subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/acct"
            + "/blobServices/default/containers/c1");

        assertEquals(List.of("storageAccounts", "blobServices", "containers"), id.types());
        assertEquals(List.of("acct", "default", "c1"), id.names());
        assertEquals("Microsoft.Storage/storageAccounts/blobServices/containers", id.typePath());
        assertEquals("c1", id.name());

        ArmResourceId blobService = id.parent();
        assertEquals("/subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/acct"
            + "/blobServices/default", blobService.id());
        ArmResourceId account = blobService.parent();
        assertEquals("/subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/acct", account.id());
        assertEquals("/subscriptions/s/resourceGroups/rg", account.parent().id());
        assertEquals("/subscriptions/s", account.parent().parent().id());
        assertNull(account.parent().parent().parent());
    }

    @Test
    void collectionsAndActionsEndOnATypeSegment() {
        ArmResourceId list = parse("subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts");
        assertTrue(list.isCollection());
        assertNull(list.name());
        assertEquals("Microsoft.Storage/storageAccounts", list.typePath());
        assertEquals("/subscriptions/s/resourceGroups/rg", list.parent().id());

        ArmResourceId action = parse("subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/a/listKeys");
        assertTrue(action.isCollection());
        assertEquals(List.of("storageAccounts", "listKeys"), action.types());
        assertEquals("/subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/a",
            action.parent().id());
    }

    @Test
    void subscriptionLevelResourceHasNoResourceGroup() {
        ArmResourceId id = parse("subscriptions/s/providers/Microsoft.Authorization/policyAssignments/pa1");

        assertEquals("s", id.subscriptionId());
        assertNull(id.resourceGroup());
        assertEquals("Microsoft.Authorization/policyAssignments", id.typePath());
        assertEquals("/subscriptions/s", id.scope().id());
        assertEquals("/subscriptions/s", id.parent().id());
    }

    @Test
    void tenantLevelResourceHasNoScope() {
        ArmResourceId id = parse("providers/Microsoft.Authorization/policyDefinitions/builtin-1?api-version=2025-03-01");

        assertNull(id.subscriptionId());
        assertEquals("Microsoft.Authorization/policyDefinitions", id.typePath());
        assertNull(id.scope());
        assertNull(id.parent());
    }

    @Test
    void extensionResourceUnderAResource() {
        ArmResourceId id = parse("subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/acct"
            + "/providers/microsoft.insights/diagnosticSettings/ds1");

        assertTrue(id.isExtension());
        assertEquals("s", id.subscriptionId());
        assertEquals("rg", id.resourceGroup());
        assertEquals("microsoft.insights", id.providerNamespace());
        assertEquals("microsoft.insights/diagnosticSettings", id.typePath());
        assertEquals("ds1", id.name());
        assertEquals("/subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/acct", id.scope().id());
        assertEquals("Microsoft.Storage/storageAccounts", id.scope().typePath());
        assertEquals(id.scope(), id.parent());
    }

    @Test
    void extensionResourceAtManagementGroupScope() {
        ArmResourceId id = parse("providers/Microsoft.Management/managementGroups/mg1"
            + "/providers/Microsoft.Authorization/policyDefinitions/pd1");

        assertTrue(id.isExtension());
        assertNull(id.subscriptionId());
        assertEquals("Microsoft.Authorization/policyDefinitions", id.typePath());
        assertEquals("Microsoft.Management/managementGroups", id.scope().typePath());
        assertNull(id.scope().parent());
    }

    @Test
    void extensionAtSubscriptionScopeLooksLikeAnOrdinaryResource() {
        ArmResourceId id = parse("subscriptions/s/providers/Microsoft.Authorization/roleAssignments/ra1");

        assertFalse(id.isExtension());
        assertEquals("/subscriptions/s", id.scope().id());
    }

    @Test
    void nonArmPathsDoNotParse() {
        assertEquals(Optional.empty(), ArmResourceId.parse(null));
        assertEquals(Optional.empty(), ArmResourceId.parse(""));
        assertEquals(Optional.empty(), ArmResourceId.parse("/"));
        assertEquals(Optional.empty(), ArmResourceId.parse("?api-version=1"));
        assertEquals(Optional.empty(), ArmResourceId.parse("devstoreaccount1/container/blob"));
        assertEquals(Optional.empty(), ArmResourceId.parse("secrets/foo"));
        assertEquals(Optional.empty(), ArmResourceId.parse("metadata/endpoints"));
    }

    @Test
    void equalIdsParseToEqualValues() {
        assertEquals(parse("subscriptions/s/resourceGroups/rg"), parse("/subscriptions/s/resourceGroups/rg/"));
    }
}
