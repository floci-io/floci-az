package io.floci.az.core.arm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ArmResourceFilter — the $filter grammar of the generic resource listings")
class ArmResourceFilterTest {

    private static final String SUB = "/subscriptions/00000000-0000-0000-0000-000000000001";

    private static final Map<String, Object> VAULT = ArmResources.indexEntry(
            SUB + "/resourceGroups/rg-one/providers/Microsoft.KeyVault/vaults/kv-demo",
            "kv-demo", "Microsoft.KeyVault/vaults", "eastus", Map.of("env", "prod", "department", "finance"));
    private static final Map<String, Object> STORAGE = ArmResources.indexEntry(
            SUB + "/resourceGroups/rg-one/providers/Microsoft.Storage/storageAccounts/flocitestsa",
            "flocitestsa", "Microsoft.Storage/storageAccounts", "eastus", Map.of("env", "dev"));
    private static final Map<String, Object> VM = ArmResources.indexEntry(
            SUB + "/resourceGroups/rg-two/providers/Microsoft.Compute/virtualMachines/vm-demo",
            "vm-demo", "Microsoft.Compute/virtualMachines", "westus", null);
    private static final List<Map<String, Object>> ESTATE = List.of(VAULT, STORAGE, VM);

    private static List<String> names(String filter) {
        Predicate<Map<String, Object>> matches = ArmResourceFilter.parse(filter);
        return ESTATE.stream().filter(matches).map(e -> (String) e.get("name")).toList();
    }

    @Test
    @DisplayName("No filter matches everything")
    void absentFilterMatchesAll() {
        assertEquals(List.of("kv-demo", "flocitestsa", "vm-demo"), names(null));
        assertEquals(List.of("kv-demo", "flocitestsa", "vm-demo"), names("  "));
    }

    @Test
    @DisplayName("azurerm's Key Vault cache query keeps only vaults")
    void resourceTypeEqualsIsWhatAzurermSends() {
        assertEquals(List.of("kv-demo"), names("resourceType eq 'Microsoft.KeyVault/vaults'"));
    }

    @Test
    @DisplayName("Comparisons ignore case, as ARM does")
    void comparisonsIgnoreCase() {
        assertEquals(List.of("kv-demo"), names("resourcetype EQ 'microsoft.keyvault/VAULTS'"));
        assertEquals(List.of("vm-demo"), names("location eq 'WestUS'"));
        assertEquals(List.of("flocitestsa"), names("name eq 'FlociTestSA'"));
    }

    @Test
    @DisplayName("ne excludes; resourceGroup is read from the id")
    void notEqualsAndResourceGroup() {
        assertEquals(List.of("flocitestsa", "vm-demo"), names("resourceType ne 'Microsoft.KeyVault/vaults'"));
        assertEquals(List.of("kv-demo", "flocitestsa"), names("resourceGroup eq 'RG-ONE'"));
    }

    @Test
    @DisplayName("and binds tighter than or")
    void andBindsTighterThanOr() {
        assertEquals(List.of("kv-demo", "vm-demo"),
                names("resourceType eq 'Microsoft.KeyVault/vaults' and location eq 'eastus' or location eq 'westus'"));
    }

    @Test
    @DisplayName("substringof and startswith")
    void substringAndPrefixForms() {
        assertEquals(List.of("kv-demo", "vm-demo"), names("substringof('demo', name)"));
        assertEquals(List.of("vm-demo"), names("substringof('two', resourceGroup)"));
        assertEquals(List.of("kv-demo"), names("startswith(tagName, 'depart')"));
    }

    @Test
    @DisplayName("A tagName/tagValue pair means one tag carrying both")
    void tagPairIsOneTag() {
        assertEquals(List.of("kv-demo"), names("tagName eq 'env' and tagValue eq 'prod'"));
        assertEquals(List.of("kv-demo", "flocitestsa"), names("tagName eq 'ENV'"));
        assertEquals(List.of(), names("tagName eq 'department' and tagValue eq 'prod'"));
        assertEquals(List.of(), names("tagValue eq 'PROD'"));
    }

    @Test
    @DisplayName("What is not implemented is refused, not ignored")
    void unsupportedFiltersAreRefused() {
        assertThrows(ArmResourceFilter.InvalidFilterException.class,
                () -> ArmResourceFilter.parse("identity/principalId eq 'abc'"));
        assertThrows(ArmResourceFilter.InvalidFilterException.class,
                () -> ArmResourceFilter.parse("plan/publisher eq 'x'"));
        assertThrows(ArmResourceFilter.InvalidFilterException.class,
                () -> ArmResourceFilter.parse("resourceType gt 'x'"));
        assertThrows(ArmResourceFilter.InvalidFilterException.class,
                () -> ArmResourceFilter.parse("(resourceType eq 'x')"));
    }
}
