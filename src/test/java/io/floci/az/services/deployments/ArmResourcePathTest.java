package io.floci.az.services.deployments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ArmResourcePath: a template resource's id from its type and name")
class ArmResourcePathTest {

    private static final String RG = "/subscriptions/s1/resourceGroups/rg1";

    @Test
    void aTopLevelResourceSitsUnderItsNamespace() {
        assertEquals(RG + "/providers/Microsoft.Storage/storageAccounts/sa1",
            ArmResourcePath.id(RG, "Microsoft.Storage/storageAccounts", "sa1"));
    }

    @Test
    void childTypeAndNameSegmentsInterleave() {
        assertEquals(RG + "/providers/Microsoft.Storage/storageAccounts/sa/blobServices/default/containers/c1",
            ArmResourcePath.id(RG, "Microsoft.Storage/storageAccounts/blobServices/containers", "sa/default/c1"));
    }

    @Test
    void leadingAndTrailingSlashesAreIgnored() {
        assertEquals(RG + "/providers/Microsoft.Network/networkSecurityGroups/nsg",
            ArmResourcePath.id(RG, "Microsoft.Network/networkSecurityGroups/", "/nsg"));
        assertEquals("Microsoft.Network/networkSecurityGroups",
            ArmResourcePath.normalizedType("/Microsoft.Network/networkSecurityGroups/"));
    }

    @Test
    void mismatchedSegmentCountsAreAnInvalidTemplate() {
        TemplateException tooFew = assertThrows(TemplateException.class,
            () -> ArmResourcePath.id(RG, "Microsoft.KeyVault/vaults/secrets", "vault"));
        assertEquals("InvalidTemplate", tooFew.code());
        assertTrue(tooFew.getMessage().startsWith("Deployment template validation failed: 'The template resource "
            + "'vault' for type 'Microsoft.KeyVault/vaults/secrets' has incorrect segment lengths"), tooFew.getMessage());
        assertThrows(TemplateException.class, () -> ArmResourcePath.id(RG, "Microsoft.KeyVault/vaults", "v/s"));
        assertThrows(TemplateException.class, () -> ArmResourcePath.id(RG, "Microsoft.KeyVault", "v"));
    }
}
