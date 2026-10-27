package io.floci.az.core.ui;

import io.floci.az.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsoleEnvironmentTest {

    private static final String ENDPOINT = "http://floci-az:4577";

    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.ui().internalPort()).thenReturn(4500);
        when(config.services().arm().defaultSubscriptionId()).thenReturn("00000000-0000-0000-0000-000000000001");
        when(config.services().entra().defaultTenantId()).thenReturn("00000000-0000-0000-0000-000000000002");
    }

    @Test
    void theCoreVariablesNameTheEmulatorAndItsManifest() {
        Map<String, String> env = ConsoleEnvironment.build(config, ENDPOINT, ConsoleProbe.contractV2(), "eastus");

        assertEquals("az", env.get("FLOCI_CLOUD"));
        assertEquals(ENDPOINT, env.get("FLOCI_ENDPOINT"));
        assertEquals("floci-az", env.get("FLOCI_HOSTNAME"));
        assertEquals(ENDPOINT + "/_floci/info", env.get("FLOCI_INFO_URL"));
        assertEquals("4500", env.get("PORT"));
    }

    @Test
    void theAzureProfileCarriesTheEndpointAndTheDefaultsFlociUiWouldGuessWrong() {
        Map<String, String> env = ConsoleEnvironment.build(config, ENDPOINT, ConsoleProbe.flociUi(), "westeurope");

        assertEquals(ENDPOINT, env.get("FLOCI_AZURE_ENDPOINT"));
        assertEquals("00000000-0000-0000-0000-000000000001", env.get("FLOCI_AZURE_SUBSCRIPTION_ID"),
                "floci-ui assumes the all-zero subscription unless told otherwise");
        assertEquals("00000000-0000-0000-0000-000000000002", env.get("FLOCI_AZURE_TENANT_ID"));
        assertEquals("westeurope", env.get("FLOCI_AZURE_LOCATION"));
        assertEquals("devstoreaccount1", env.get("FLOCI_AZURE_ACCOUNT_NAME"));
        assertFalse(env.containsKey("FLOCI_AZURE_RESOURCE_GROUP"));
    }

    @Test
    void flociEndpointIsWithheldFromAConsoleThatReadsItAsAws() {
        Map<String, String> env = ConsoleEnvironment.build(config, ENDPOINT, ConsoleProbe.flociUi(), "eastus");

        assertFalse(env.containsKey("FLOCI_ENDPOINT"));
        assertEquals(ENDPOINT, env.get("FLOCI_AZURE_ENDPOINT"));
    }

    @Test
    void noAzureSdkCredentialVariablesAreSet() {
        Map<String, String> env = ConsoleEnvironment.build(config, ENDPOINT, ConsoleProbe.contractV2(), "eastus");

        assertTrue(env.keySet().stream().noneMatch(key -> key.startsWith("AZURE_")), env.keySet().toString());
    }

    @Test
    void aTrailingSlashIsNotDoubledInTheManifestUrl() {
        Map<String, String> env = ConsoleEnvironment.build(config, ENDPOINT + "/", ConsoleProbe.contractV2(), "eastus");

        assertEquals(ENDPOINT, env.get("FLOCI_ENDPOINT"));
        assertEquals(ENDPOINT + "/_floci/info", env.get("FLOCI_INFO_URL"));
    }

    @Test
    void anIpv6EndpointYieldsABareHostname() {
        Map<String, String> env = ConsoleEnvironment.build(config, "http://[fd00::5]:4577",
                ConsoleProbe.contractV2(), "eastus");

        assertEquals("fd00::5", env.get("FLOCI_HOSTNAME"));
    }

    @Test
    void aMissingLocationIsOmittedRatherThanSentEmpty() {
        Map<String, String> env = ConsoleEnvironment.build(config, ENDPOINT, ConsoleProbe.contractV2(), null);

        assertNull(env.get("FLOCI_AZURE_LOCATION"));
    }

    @Test
    void theCoreVariablesComeBeforeTheCloudProfile() {
        Map<String, String> env = ConsoleEnvironment.build(config, ENDPOINT, ConsoleProbe.contractV2(), "eastus");

        List<String> keys = List.copyOf(env.keySet());
        assertTrue(keys.indexOf("PORT") < keys.indexOf("FLOCI_AZURE_ENDPOINT"), keys.toString());
    }
}
