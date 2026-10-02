package io.floci.az.core.docker;

import io.floci.az.config.EmulatorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ContainerStorageHelper — naming and labels")
class ContainerStorageHelperTest {

    @Test
    void namesCarryTheAzPrefixWithoutNamespace() {
        assertEquals("floci-az-pg-server1", ContainerStorageHelper.dockerName(config(""), "pg-server1"));
        assertEquals("floci-az-aks-abc123",
                ContainerStorageHelper.resourceName(config(""), "aks", null, "abc123"));
        assertEquals("floci-az-aks-vol9",
                ContainerStorageHelper.resourceName(config(""), "aks", "vol9", "abc123"));
    }

    @Test
    void nullConfigYieldsDefaultModeNames() {
        assertEquals("floci-az-pg-server1", ContainerStorageHelper.dockerName(null, "pg-server1"));
    }

    @Test
    void namespaceLandsBetweenCloudAndServiceTokens() {
        assertEquals("floci-az-run-one-pg-server1",
                ContainerStorageHelper.dockerName(config("run-one"), "pg-server1"));
    }

    @Test
    void alreadyPrefixedNamesAreNormalized() {
        assertEquals("floci-az-pg-x", ContainerStorageHelper.dockerName(config(""), "floci-az-pg-x"));
        assertEquals("floci-az-pg-x", ContainerStorageHelper.dockerName(config(""), "floci-pg-x"));
        assertEquals("floci-az-run-one-pg-x",
                ContainerStorageHelper.dockerName(config("run-one"), "floci-az-pg-x"));
    }

    @Test
    void defaultLabelsIdentifyThisEmulator() {
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-az"),
                ContainerStorageHelper.defaultLabels(config("")));
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-az", "floci_namespace", "run-one"),
                ContainerStorageHelper.defaultLabels(config(" run/one ")));
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-az"),
                ContainerStorageHelper.defaultLabels(null));
    }

    @Test
    void unsafeNamespaceSegmentsAreIgnored() {
        assertEquals("floci-az-pg-x", ContainerStorageHelper.dockerName(config(".."), "pg-x"));
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-az"),
                ContainerStorageHelper.defaultLabels(config("..")));
    }

    @Test
    void resourceIdentityLabelsCarryCloudServiceResourceAndAzureScope() {
        assertEquals(
                Map.of(
                        "io.floci", "az",
                        "io.floci.service", "redis",
                        "io.floci.resource-id", "cache-one",
                        "io.floci.subscription", "00000000-0000-0000-0000-000000000000",
                        "io.floci.resource-group", "rg-one",
                        "io.floci.location", "eastus"),
                ContainerStorageHelper.resourceIdentityLabels("redis", "cache-one",
                        "00000000-0000-0000-0000-000000000000", "rg-one", "eastus"));
    }

    @Test
    void resourceIdentityLabelsOmitBlankAndNullValues() {
        assertEquals(
                Map.of("io.floci", "az", "io.floci.service", "acr"),
                ContainerStorageHelper.resourceIdentityLabels("acr", null, "", " ", null));
        assertEquals(
                Map.of("io.floci", "az", "io.floci.service", "servicebus", "io.floci.resource-id", "default"),
                ContainerStorageHelper.resourceIdentityLabels("servicebus", "default", null, null, ""));
    }

    @Test
    void serviceKeyIsTheOnlyLegacyAlias() {
        assertEquals(Map.of("io.floci.service", "floci_service"), ContainerStorageHelper.legacyLabelAliases());
    }

    @Test
    void withLegacyAliasesStampsTheLegacyKeyWithTheNewKeysValue() {
        Map<String, String> labels = Map.of("floci", "true", "io.floci.service", "containerapps");

        assertEquals(
                Map.of("floci", "true", "io.floci.service", "containerapps", "floci_service", "containerapps"),
                ContainerStorageHelper.withLegacyAliases(labels));
        assertEquals(Map.of("floci", "true"), ContainerStorageHelper.withLegacyAliases(Map.of("floci", "true")));
    }

    @Test
    void withLegacyAliasesOverwritesAStaleLegacyValue() {
        assertEquals(
                Map.of("io.floci.service", "servicebus", "floci_service", "servicebus"),
                ContainerStorageHelper.withLegacyAliases(
                        Map.of("io.floci.service", "servicebus", "floci_service", "other")));
    }

    @Test
    void labelValueReadsTheNewKeyFirstAndFallsBackToTheLegacyAlias() {
        assertEquals("containerapps", ContainerStorageHelper.labelValue(
                Map.of("io.floci.service", "containerapps"), "io.floci.service"));
        assertEquals("containerapps", ContainerStorageHelper.labelValue(
                Map.of("floci_service", "containerapps"), "io.floci.service"));
        assertEquals("containerapps", ContainerStorageHelper.labelValue(
                Map.of("io.floci.service", "containerapps", "floci_service", "servicebus"), "io.floci.service"));
        assertNull(ContainerStorageHelper.labelValue(Map.of("floci", "true"), "io.floci.service"));
        assertNull(ContainerStorageHelper.labelValue(null, "io.floci.service"));
    }

    @Test
    void labelValueHasNoFallbackForUnaliasedKeys() {
        assertNull(ContainerStorageHelper.labelValue(
                Map.of("floci_service", "containerapps"), "io.floci.resource-id"));
        assertEquals("app", ContainerStorageHelper.labelValue(
                Map.of("io.floci.resource-id", "app"), "io.floci.resource-id"));
    }

    @Test
    void legacyAliasDisagreesOnlyWhenBothKeysArePresentWithDifferentValues() {
        assertTrue(ContainerStorageHelper.legacyAliasDisagrees(
                Map.of("io.floci.service", "containerapps", "floci_service", "servicebus"), "io.floci.service"));
        assertFalse(ContainerStorageHelper.legacyAliasDisagrees(
                Map.of("io.floci.service", "containerapps", "floci_service", "containerapps"), "io.floci.service"));
        assertFalse(ContainerStorageHelper.legacyAliasDisagrees(
                Map.of("io.floci.service", "containerapps"), "io.floci.service"));
        assertFalse(ContainerStorageHelper.legacyAliasDisagrees(
                Map.of("floci_service", "containerapps"), "io.floci.service"));
        assertFalse(ContainerStorageHelper.legacyAliasDisagrees(null, "io.floci.service"));
        assertFalse(ContainerStorageHelper.legacyAliasDisagrees(
                Map.of("floci_emulator", "floci-az"), "floci_emulator"));
    }

    private static EmulatorConfig config(String namespace) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(docker);
        when(docker.resourceNamespace()).thenReturn(
                namespace.isBlank() ? Optional.empty() : Optional.of(namespace));
        return config;
    }
}
