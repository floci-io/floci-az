package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DependencyGraph: creation order from dependsOn and implicit references")
class DependencyGraphTest {

    private static final String RG = "/subscriptions/s1/resourceGroups/rg1";

    private static DependencyGraph.Node node(int index, String type, String name, String... dependsOn) {
        return new DependencyGraph.Node(index, ArmResourcePath.id(RG, type, name), type, name, List.of(dependsOn));
    }

    private static List<String> names(List<DependencyGraph.Node> nodes) {
        return nodes.stream().map(DependencyGraph.Node::name).toList();
    }

    @Test
    void withoutDependenciesTemplateOrderIsKept() {
        assertEquals(List.of("a", "b", "c"), names(DependencyGraph.order(List.of(
            node(0, "Microsoft.Storage/storageAccounts", "a"),
            node(1, "Microsoft.Storage/storageAccounts", "b"),
            node(2, "Microsoft.Storage/storageAccounts", "c")))));
    }

    @Test
    void aResourceComesAfterWhatItDependsOnByNameTypeAndNameOrId() {
        List<DependencyGraph.Node> ordered = DependencyGraph.order(List.of(
            node(0, "Microsoft.Network/virtualNetworks/subnets", "vnet/sub", "Microsoft.Network/virtualNetworks/vnet"),
            node(1, "Microsoft.Network/networkInterfaces", "nic",
                RG + "/providers/Microsoft.Network/virtualNetworks/vnet/subnets/sub"),
            node(2, "Microsoft.Network/virtualNetworks", "vnet"),
            node(3, "Microsoft.Compute/virtualMachines", "vm", "NIC")));

        assertEquals(List.of("vnet", "vnet/sub", "nic", "vm"), names(ordered));
    }

    @Test
    void aTypeWithATrailingSlashMatchesToo() {
        assertEquals(List.of("nsg", "nic"), names(DependencyGraph.order(List.of(
            node(0, "Microsoft.Network/networkInterfaces", "nic", "Microsoft.Network/networkSecurityGroups/nsg"),
            node(1, "Microsoft.Network/networkSecurityGroups/", "nsg")))));
    }

    @Test
    void aCycleIsAnInvalidTemplate() {
        TemplateException cycle = assertThrows(TemplateException.class, () -> DependencyGraph.order(List.of(
            node(0, "Microsoft.Storage/storageAccounts", "a", "b"),
            node(1, "Microsoft.Storage/storageAccounts", "b", "a"))));

        assertEquals("InvalidTemplate", cycle.code());
        assertTrue(cycle.getMessage().contains("Circular dependency detected on resource"), cycle.getMessage());
    }

    @Test
    void anUnknownOrAmbiguousDependencyIsAnInvalidTemplate() {
        assertThrows(TemplateException.class, () -> DependencyGraph.order(List.of(
            node(0, "Microsoft.Storage/storageAccounts", "a", "missing"))));
        assertThrows(TemplateException.class, () -> DependencyGraph.order(List.of(
            node(0, "Microsoft.Storage/storageAccounts", "x"),
            node(1, "Microsoft.KeyVault/vaults", "x"),
            node(2, "Microsoft.Web/sites", "w", "x"))));
    }

    @Test
    void referenceAndListCallsAreImplicitReferencesAndResourceIdIsNot() throws Exception {
        List<Expression> references = DependencyGraph.implicitReferences(new ObjectMapper().readTree("""
            {"properties": {
               "a": "[reference('sa1').primaryEndpoints.blob]",
               "b": "[listKeys(parameters('account'), '2023-01-01').keys[0].value]",
               "c": "[resourceId('Microsoft.Storage/storageAccounts', 'sa2')]",
               "d": "[[reference('literal')]",
               "e": "plain"}}
            """));

        assertEquals(2, references.size());
        assertEquals(new Expression.Literal(new ObjectMapper().getNodeFactory().textNode("sa1")), references.get(0));
        assertTrue(references.get(1) instanceof Expression.FunctionCall call
            && call.name().equalsIgnoreCase("parameters"));
        assertTrue(DependencyGraph.isName("sa1"));
        assertFalse(DependencyGraph.isName(RG + "/providers/Microsoft.Storage/storageAccounts/sa1"));
    }
}
