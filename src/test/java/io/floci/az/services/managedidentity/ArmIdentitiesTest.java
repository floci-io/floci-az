package io.floci.az.services.managedidentity;

import io.floci.az.core.cloud.ScopeContext;
import io.floci.az.services.managedidentity.ManagedIdentityModels.UserAssignedIdentity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmIdentitiesTest {

    private static final String HELD = "/subscriptions/s/resourceGroups/rg/providers/"
            + "Microsoft.ManagedIdentity/userAssignedIdentities/held";
    private static final String UNKNOWN = "/subscriptions/s/resourceGroups/rg/providers/"
            + "Microsoft.ManagedIdentity/userAssignedIdentities/unknown";
    private static final ScopeContext SCOPE = new ScopeContext(null, "tenant-1", "s", "rg", null, null);

    private final ArmIdentities identities = new ArmIdentities(id -> HELD.equalsIgnoreCase(id)
            ? Optional.of(new UserAssignedIdentity(HELD, "held", "westus", null, "tenant-1", "held-principal",
                    "held-client"))
            : Optional.empty());

    @Test
    void noneOrNoIdentityIsNull() {
        assertNull(identities.resolve(null, null, SCOPE));
        assertNull(identities.resolve(Map.of("type", "None"), Map.of("type", "SystemAssigned"), SCOPE));
        assertNull(identities.resolve(Map.of(), null, SCOPE));
    }

    @Test
    void aSystemAssignedIdentityGetsAPrincipalAndTheTenantAndKeepsThePrincipal() {
        Map<String, Object> first = identities.resolve(Map.of("type", "systemassigned"), null, SCOPE);
        assertEquals("SystemAssigned", first.get("type"));
        assertNotNull(first.get("principalId"));
        assertEquals("tenant-1", first.get("tenantId"));
        assertFalse(first.containsKey("userAssignedIdentities"));

        Map<String, Object> again = identities.resolve(Map.of("type", "SystemAssigned", "principalId", "sent"),
                first, SCOPE);
        assertEquals(first.get("principalId"), again.get("principalId"));
    }

    @Test
    void aPrincipalIsNotCarriedOverOnceTheSystemIdentityWasRemoved() {
        Map<String, Object> first = identities.resolve(Map.of("type", "SystemAssigned"), null, SCOPE);
        Map<String, Object> second = identities.resolve(Map.of("type", "SystemAssigned"), null, SCOPE);
        assertNotEquals(first.get("principalId"), second.get("principalId"));
    }

    @Test
    void userAssignedIdentitiesGetTheStoreIdsOrDeterministicOnes() {
        Map<String, Object> named = new LinkedHashMap<>();
        named.put(HELD, Map.of());
        named.put(UNKNOWN, Map.of());
        Map<String, Object> identity = identities.resolve(
                Map.of("type", "SystemAssigned,UserAssigned", "userAssignedIdentities", named), null, SCOPE);

        assertEquals("SystemAssigned, UserAssigned", identity.get("type"));
        Map<?, ?> users = (Map<?, ?>) identity.get("userAssignedIdentities");
        assertEquals(Map.of("principalId", "held-principal", "clientId", "held-client"), users.get(HELD));
        Map<?, ?> fallback = (Map<?, ?>) users.get(UNKNOWN);
        assertNotNull(fallback.get("principalId"));
        Map<String, Object> again = identities.resolve(
                Map.of("type", "UserAssigned", "userAssignedIdentities", Map.of(UNKNOWN, Map.of())), null, SCOPE);
        assertEquals(fallback, ((Map<?, ?>) again.get("userAssignedIdentities")).get(UNKNOWN));
        assertFalse(again.containsKey("principalId"));
    }

    @Test
    void aNullEntryRemovesThatIdentityAndANamelessWriteKeepsTheStoredOnes() {
        Map<String, Object> stored = identities.resolve(
                Map.of("type", "UserAssigned", "userAssignedIdentities", Map.of(HELD, Map.of(), UNKNOWN, Map.of())),
                null, SCOPE);

        Map<String, Object> removal = new HashMap<>();
        removal.put(UNKNOWN, null);
        Map<String, Object> removed = identities.resolve(
                Map.of("type", "UserAssigned", "userAssignedIdentities", removal), stored, SCOPE);
        assertTrue(((Map<?, ?>) removed.get("userAssignedIdentities")).isEmpty());

        Map<String, Object> kept = identities.resolve(Map.of("type", "SystemAssigned, UserAssigned"), stored, SCOPE);
        assertEquals(stored.get("userAssignedIdentities"), kept.get("userAssignedIdentities"));
    }

    @Test
    void malformedNamesANonObjectAndAMisspeltType() {
        assertTrue(ArmIdentities.malformed(null, "a thing").isEmpty());
        assertTrue(ArmIdentities.malformed(Map.of(), "a thing").isEmpty());
        assertTrue(ArmIdentities.malformed(Map.of("type", "UserAssigned"), "a thing").isEmpty());
        assertTrue(ArmIdentities.malformed("SystemAssigned", "a thing").orElseThrow().contains("a thing"));
        assertTrue(ArmIdentities.malformed(Map.of("type", "Sytem"), "a thing").orElseThrow().contains("Sytem"));
    }
}
