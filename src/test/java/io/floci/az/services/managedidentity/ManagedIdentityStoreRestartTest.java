package io.floci.az.services.managedidentity;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.storage.PersistentStorage;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.managedidentity.ManagedIdentityModels.FederatedIdentityCredential;
import io.floci.az.services.managedidentity.ManagedIdentityModels.UserAssignedIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ManagedIdentityStore: identities, their ids and credentials survive a restart of a persistent backend")
class ManagedIdentityStoreRestartTest {

    private static final TypeReference<Map<String, StoredObject>> TYPE_REF = new TypeReference<>() {};
    private static final String IDENTITY = "/subscriptions/Sub-Restart/resourceGroups/RG-Restart"
        + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/Kept";
    private static final String CREDENTIAL = IDENTITY + "/federatedIdentityCredentials/Fic1";
    private static final String PRINCIPAL_ID = "aaaaaaaa-0000-0000-0000-00000000000a";
    private static final String CLIENT_ID = "bbbbbbbb-0000-0000-0000-00000000000b";

    @TempDir
    Path tempDir;

    private ManagedIdentityStore open(Path file) {
        PersistentStorage<String, StoredObject> backend = new PersistentStorage<>(file, TYPE_REF);
        backend.load();
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(ManagedIdentityStore.STORE_NAME)).thenReturn(backend);
        return new ManagedIdentityStore(factory);
    }

    private static ArmResourceId id(String path) {
        return ArmResourceId.parse(path).orElseThrow();
    }

    @Test
    void anIdentityAndItsCredentialComeBackWithTheSameIdsAndTokenLookups() {
        Path file = tempDir.resolve("managedidentity.json");
        ManagedIdentityStore first = open(file);
        UserAssignedIdentity identity = new UserAssignedIdentity(IDENTITY, "Kept", "eastus", Map.of("env", "t"),
            "00000000-0000-0000-0000-000000000001", PRINCIPAL_ID, CLIENT_ID);
        first.createOrUpdateIdentity(id(IDENTITY), existing -> identity);
        FederatedIdentityCredential credential = new FederatedIdentityCredential(CREDENTIAL, "Fic1",
            "https://issuer", "subject", List.of("api://AzureADTokenExchange"));
        assertTrue(first.createOrUpdateCredential(id(IDENTITY), id(CREDENTIAL), existing -> credential).isPresent());

        ManagedIdentityStore restarted = open(file);

        assertEquals(identity, restarted.identity(id(IDENTITY.toLowerCase())).orElseThrow());
        assertEquals(List.of(identity), restarted.identities(new ArmScope("sub-restart", "rg-restart")));
        assertEquals(identity, restarted.findByClientId(CLIENT_ID.toUpperCase()).orElseThrow());
        assertEquals(identity, restarted.findByPrincipalId(PRINCIPAL_ID).orElseThrow());
        assertEquals(identity, restarted.findByResourceId(IDENTITY.toUpperCase()).orElseThrow());
        assertEquals(List.of(credential), restarted.credentials(id(IDENTITY)));
        assertEquals(credential, restarted.credential(id(CREDENTIAL.toLowerCase())).orElseThrow());
    }

    @Test
    void anIdentityDeletedAfterARestartTakesItsCredentialAndLookupsWithIt() {
        Path file = tempDir.resolve("managedidentity.json");
        ManagedIdentityStore first = open(file);
        first.createOrUpdateIdentity(id(IDENTITY), existing -> new UserAssignedIdentity(IDENTITY, "Kept", "eastus",
            Map.of(), "00000000-0000-0000-0000-000000000001", PRINCIPAL_ID, CLIENT_ID));
        first.createOrUpdateCredential(id(IDENTITY), id(CREDENTIAL), existing ->
            new FederatedIdentityCredential(CREDENTIAL, "Fic1", "https://issuer", "subject", List.of("a")));

        assertTrue(open(file).deleteIdentity(id(IDENTITY)).isPresent());

        ManagedIdentityStore third = open(file);
        assertTrue(third.identity(id(IDENTITY)).isEmpty());
        assertTrue(third.findByClientId(CLIENT_ID).isEmpty());
        assertTrue(third.credential(id(CREDENTIAL)).isEmpty());
    }
}
