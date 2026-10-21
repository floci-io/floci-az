package io.floci.az.core.arm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ArmTypeRegistry: collection, validation and path matching")
class ArmTypeRegistryTest {

    private static final ArmSurface STORAGE = new ArmSurface("Microsoft.Storage", ArmLane.ARM_HANDLER, List.of(
        ArmResourceType.resourceGroup("Microsoft.Storage", "storageAccounts", LocationPolicy.REQUIRED),
        ArmResourceType.child("Microsoft.Storage", "storageAccounts/blobServices"),
        ArmResourceType.child("Microsoft.Storage", "storageAccounts/blobServices/containers")));

    private static final ArmSurface RESOURCES = new ArmSurface("Microsoft.Resources", ArmLane.ARM_HANDLER, List.of(
        new ArmResourceType("Microsoft.Resources", "resourceGroups", ArmScopeKind.SUBSCRIPTION,
            LocationPolicy.REQUIRED)));

    private static ArmTypeRegistry registry(ArmSurface... surfaces) {
        return new ArmTypeRegistry(List.<ArmSurfaceProvider>of(() -> List.of(surfaces)));
    }

    private static String matched(ArmTypeRegistry registry, String path) {
        ArmResourceType type = registry.match(ArmResourceId.parse(path).orElse(null));
        return type == null ? null : type.type();
    }

    @Test
    void aResourceAndItsCollectionMatchTheDeclaredType() {
        ArmTypeRegistry registry = registry(STORAGE);
        assertEquals("Microsoft.Storage/storageAccounts",
            matched(registry, "/subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/a"));
        assertEquals("Microsoft.Storage/storageAccounts",
            matched(registry, "/subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts"));
        assertEquals("Microsoft.Storage/storageAccounts",
            matched(registry, "/subscriptions/s/providers/Microsoft.Storage/storageAccounts?api-version=2023-05-01"));
    }

    @Test
    void matchingIgnoresTheClientsCasing() {
        assertEquals("Microsoft.Storage/storageAccounts/blobServices/containers", matched(registry(STORAGE),
            "/subscriptions/s/resourcegroups/rg/providers/microsoft.storage/STORAGEACCOUNTS/a/blobservices/default/Containers/c"));
    }

    @Test
    void anActionOnAResourceMatchesTheResourcesType() {
        assertEquals("Microsoft.Storage/storageAccounts", matched(registry(STORAGE),
            "/subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/a/listKeys"));
    }

    @Test
    void resourceGroupsMatchTheBuiltInResourcesType() {
        ArmTypeRegistry registry = registry(RESOURCES);
        assertEquals("Microsoft.Resources/resourceGroups", matched(registry, "/subscriptions/s/resourceGroups/rg"));
        assertEquals("Microsoft.Resources/resourceGroups", matched(registry, "/subscriptions/s/resourcegroups"));
        assertNull(matched(registry, "/subscriptions/s"));
        assertNull(matched(registry, "/subscriptions/s/locations"));
    }

    @Test
    void anUndeclaredTypeOrAPathOutsideArmMatchesNothing() {
        ArmTypeRegistry registry = registry(STORAGE);
        assertNull(matched(registry, "/subscriptions/s/resourceGroups/rg/providers/Microsoft.Cache/redis/r"));
        assertNull(matched(registry, "/subscriptions/s/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/a/fileServices/default"));
        assertNull(registry.match(null));
    }

    @Test
    void declaredTypesCarryAzuresApiVersionsNewestFirst() {
        ArmTypeRegistry registry = registry(STORAGE);
        ArmResourceType accounts = registry.type("microsoft.storage/storageaccounts").orElseThrow();
        List<String> versions = registry.apiVersions(accounts);
        assertTrue(versions.contains("2023-05-01"), versions.toString());
        assertTrue(versions.indexOf("2023-05-01") < versions.indexOf("2021-09-01"), versions.toString());
        assertEquals(accounts.typePath(), "storageAccounts");
        assertEquals("Microsoft.Storage/storageAccounts/blobServices",
            registry.type("Microsoft.Storage/storageAccounts/blobServices/containers").orElseThrow().parentType());
        assertFalse(registry.type("Microsoft.Storage/fileShares").isPresent());
    }

    @Test
    void aTypeAzureDoesNotKnowFailsStartup() {
        ArmSurface typo = new ArmSurface("Microsoft.Storage", ArmLane.ARM_HANDLER,
            List.of(ArmResourceType.resourceGroup("Microsoft.Storage", "storageAcounts", LocationPolicy.REQUIRED)));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> registry(typo));
        assertTrue(e.getMessage().contains("storageAcounts"), e.getMessage());
    }

    @Test
    void aNamespaceMissingFromTheProviderDataFailsStartup() {
        ArmSurface unknown = new ArmSurface("Microsoft.Unknown", ArmLane.FILTER,
            List.of(ArmResourceType.resourceGroup("Microsoft.Unknown", "things", LocationPolicy.REQUIRED)));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> registry(unknown));
        assertTrue(e.getMessage().contains("gen_providers.py"), e.getMessage());
    }

    @Test
    void twoSurfacesForOneNamespaceOrATypeDeclaredTwiceFailStartup() {
        assertThrows(IllegalStateException.class, () -> registry(STORAGE, STORAGE));
        ArmSurface twice = new ArmSurface("Microsoft.Storage", ArmLane.ARM_HANDLER, List.of(
            ArmResourceType.resourceGroup("Microsoft.Storage", "storageAccounts", LocationPolicy.REQUIRED),
            ArmResourceType.resourceGroup("Microsoft.Storage", "StorageAccounts", LocationPolicy.REQUIRED)));
        assertThrows(IllegalStateException.class, () -> registry(twice));
    }

    @Test
    void aSurfaceRejectsATypeFromAnotherNamespace() {
        assertThrows(IllegalArgumentException.class, () -> new ArmSurface("Microsoft.Storage", ArmLane.ARM_HANDLER,
            List.of(ArmResourceType.resourceGroup("Microsoft.Cache", "redis", LocationPolicy.REQUIRED))));
    }
}
