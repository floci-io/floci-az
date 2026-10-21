package io.floci.az.core.directory;

import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.InMemoryStorage;
import io.floci.az.core.storage.StorageBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ProviderRegistrations: default state, recorded states and persistence")
class ProviderRegistrationsTest {

    private static final String SUB = "7a1d0000-0000-4000-8000-00000000c0b1";

    @Test
    void anUntouchedNamespaceHasTheDefaultState() {
        assertEquals(RegistrationState.REGISTERED,
            new ProviderRegistrations(null, true).state(SUB, "Microsoft.Storage"));
        assertEquals(RegistrationState.NOT_REGISTERED,
            new ProviderRegistrations(null, false).state(SUB, "Microsoft.Storage"));
    }

    @Test
    void microsoftResourcesIsAlwaysRegistered() {
        ProviderRegistrations registrations = new ProviderRegistrations(null, false);

        assertEquals(RegistrationState.REGISTERED, registrations.state(SUB, "microsoft.resources"));
    }

    @Test
    void registerAndUnregisterAreRecordedCaseInsensitively() {
        ProviderRegistrations registrations = new ProviderRegistrations(null, false);

        registrations.register(SUB, "Microsoft.KeyVault");
        assertEquals(RegistrationState.REGISTERED, registrations.state(SUB.toUpperCase(), "MICROSOFT.KEYVAULT"));
        registrations.unregister(SUB.toUpperCase(), "microsoft.keyvault");
        assertEquals(RegistrationState.UNREGISTERED, registrations.state(SUB, "Microsoft.KeyVault"));
    }

    @Test
    void recordedStatesSurviveARestartAndAResetClearsThem() {
        StorageBackend<String, StoredObject> store = new InMemoryStorage<>();
        ProviderRegistrations first = new ProviderRegistrations(store, false);
        first.register(SUB, "Microsoft.Storage");
        first.unregister(SUB, "Microsoft.Cache");

        ProviderRegistrations restarted = new ProviderRegistrations(store, false);
        assertEquals(RegistrationState.REGISTERED, restarted.state(SUB, "Microsoft.Storage"));
        assertEquals(RegistrationState.UNREGISTERED, restarted.state(SUB, "Microsoft.Cache"));

        restarted.clear();
        assertEquals(RegistrationState.NOT_REGISTERED,
            new ProviderRegistrations(store, false).state(SUB, "Microsoft.Storage"));
    }
}
