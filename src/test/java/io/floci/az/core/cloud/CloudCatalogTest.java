package io.floci.az.core.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.cloud.CloudProfile.Location;
import io.floci.az.core.cloud.CloudProfile.Sourced;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudCatalogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The host suffixes {@code RoutingTableAssemblyTest} pins for today's routes, all public-cloud. */
    private static final Set<String> ROUTED_PUBLIC_HOST_SUFFIXES = Set.of(
            ".vault.azure.net", ".managedhsm.azure.net", ".communication.azure.com", ".blob.core.windows.net",
            ".dfs.core.windows.net", ".queue.core.windows.net", ".table.core.windows.net", ".servicebus.windows.net",
            ".service.signalr.net", ".azurecontainerapps.io", ".azurecr.io");

    @Test
    void theThreeCloudsLoadPublicFirst() {
        List<String> names = CloudCatalog.all().stream().map(CloudProfile::name).toList();
        assertEquals(List.of("AzureCloud", "AzureUSGovernment", "AzureChinaCloud"), names);
    }

    @Test
    void forNameIsCaseInsensitiveAndStrict() {
        assertEquals("AzureUSGovernment", CloudCatalog.forName("azureusgovernment").orElseThrow().name());
        assertEquals(Optional.empty(), CloudCatalog.forName("AzureGermanCloud"));
        assertEquals(Optional.empty(), CloudCatalog.forName(null));
    }

    @Test
    void forNameOrPublicFallsBackToThePublicCloud() {
        assertEquals("AzureCloud", CloudCatalog.forNameOrPublic("nowhere").name());
        assertEquals("AzureCloud", CloudCatalog.forNameOrPublic(null).name());
        assertEquals("AzureChinaCloud", CloudCatalog.forNameOrPublic("AZURECHINACLOUD").name());
    }

    @Test
    void everySuffixResolvesInEveryCloudOrIsExplicitlyUnsettled() {
        for (CloudProfile cloud : CloudCatalog.all()) {
            for (CloudSuffix suffix : CloudSuffix.values()) {
                Sourced<String> entry = cloud.suffixes().get(suffix);
                assertNotNull(entry, cloud.name() + " lacks " + suffix);
                if (entry.unsettled()) {
                    assertThat(cloud.name() + " " + suffix, entry.source(), startsWith(Sourced.UNSETTLED_PREFIX));
                    assertEquals(Optional.empty(), cloud.suffix(suffix));
                } else {
                    assertFalse(entry.source().startsWith(Sourced.UNSETTLED_PREFIX), cloud.name() + " " + suffix);
                    assertFalse(entry.value().startsWith("."), cloud.name() + " " + suffix);
                }
            }
        }
    }

    @Test
    void thePublicCloudSettlesEverySuffix() {
        CloudProfile cloud = CloudCatalog.forName("AzureCloud").orElseThrow();
        for (CloudSuffix suffix : CloudSuffix.values()) {
            assertTrue(cloud.suffix(suffix).isPresent(), suffix.name());
        }
    }

    @Test
    void sovereignCloudsCarryTheSettledSuffixes() {
        CloudProfile gov = CloudCatalog.forName("AzureUSGovernment").orElseThrow();
        CloudProfile china = CloudCatalog.forName("AzureChinaCloud").orElseThrow();
        assertEquals(Optional.of("blob.core.usgovcloudapi.net"), gov.suffix(CloudSuffix.BLOB));
        assertEquals(Optional.of("vault.usgovcloudapi.net"), gov.suffix(CloudSuffix.KEYVAULT));
        assertEquals(Optional.of("redis.cache.usgovcloudapi.net"), gov.suffix(CloudSuffix.REDIS));
        assertEquals(Optional.of("vault.azure.cn"), china.suffix(CloudSuffix.KEYVAULT));
        assertEquals(Optional.of("azurecr.cn"), china.suffix(CloudSuffix.ACR));
        assertEquals(Optional.empty(), china.suffix(CloudSuffix.REDIS));
    }

    @Test
    void hostRoutedSuffixesAreTheOnesRoutedToday() {
        CloudProfile cloud = CloudCatalog.forName("AzureCloud").orElseThrow();
        Set<String> routed = Arrays.stream(CloudSuffix.values())
                .filter(CloudSuffix::hostRouted)
                .map(suffix -> "." + cloud.suffix(suffix).orElseThrow())
                .collect(Collectors.toSet());
        assertEquals(ROUTED_PUBLIC_HOST_SUFFIXES, routed);
    }

    @Test
    void endpointsAndAudiencesFollowEachCloud() {
        CloudProfile gov = CloudCatalog.forName("AzureUSGovernment").orElseThrow();
        assertEquals("https://management.usgovcloudapi.net", gov.endpoints().resourceManager());
        assertEquals(List.of("https://management.core.usgovcloudapi.net", "https://management.usgovcloudapi.net"),
                gov.audiences());
        CloudProfile china = CloudCatalog.forName("AzureChinaCloud").orElseThrow();
        assertEquals(List.of("login.chinacloudapi.cn", "login.partner.microsoftonline.cn"),
                china.endpoints().authorityHosts());
        assertEquals("https://login.chinacloudapi.cn", china.endpoints().activeDirectory());
    }

    @Test
    void stripKnownDnsSuffixMatchesTheLongestSuffixAcrossClouds() {
        DnsSuffixMatch gov = CloudCatalog.stripKnownDnsSuffix("x.blob.core.usgovcloudapi.net").orElseThrow();
        assertEquals("AzureUSGovernment", gov.cloud().name());
        assertEquals(CloudSuffix.BLOB, gov.service());
        assertEquals("blob.core.usgovcloudapi.net", gov.suffix());
        assertEquals("x", gov.name());

        DnsSuffixMatch china = CloudCatalog.stripKnownDnsSuffix("v.vault.azure.cn").orElseThrow();
        assertEquals("AzureChinaCloud", china.cloud().name());
        assertEquals(CloudSuffix.KEYVAULT, china.service());
        assertEquals("v", china.name());

        DnsSuffixMatch publicBlob = CloudCatalog.stripKnownDnsSuffix("A.Blob.Core.Windows.Net:443").orElseThrow();
        assertEquals("AzureCloud", publicBlob.cloud().name());
        assertEquals(CloudSuffix.BLOB, publicBlob.service());
        assertEquals("a", publicBlob.name());

        DnsSuffixMatch aks = CloudCatalog.stripKnownDnsSuffix("dns-1a2b.hcp.eastus.azmk8s.io").orElseThrow();
        assertEquals(CloudSuffix.AKS, aks.service());
        assertEquals("dns-1a2b.hcp.eastus", aks.name());
    }

    @Test
    void stripKnownDnsSuffixIgnoresUnknownHostsAndBareSuffixes() {
        assertEquals(Optional.empty(), CloudCatalog.stripKnownDnsSuffix("example.com"));
        assertEquals(Optional.empty(), CloudCatalog.stripKnownDnsSuffix("blob.core.windows.net"));
        assertEquals(Optional.empty(), CloudCatalog.stripKnownDnsSuffix("localhost:4577"));
        assertEquals(Optional.empty(), CloudCatalog.stripKnownDnsSuffix(null));
    }

    @Test
    void normalizeLocationMapsDisplayNamesAndCase() {
        CloudProfile cloud = CloudCatalog.forName("AzureCloud").orElseThrow();
        CloudProfile gov = CloudCatalog.forName("AzureUSGovernment").orElseThrow();
        assertEquals("eastus", CloudCatalog.normalizeLocation(cloud, "East US"));
        assertEquals("eastus2", CloudCatalog.normalizeLocation(cloud, "EASTUS2"));
        assertEquals("usgovvirginia", CloudCatalog.normalizeLocation(gov, "US Gov Virginia"));
        assertEquals("usgovvirginia", CloudCatalog.normalizeLocation(gov, "usgovvirginia"));
    }

    @Test
    void normalizeLocationKeepsAnUnknownLocationNormalized() {
        CloudProfile gov = CloudCatalog.forName("AzureUSGovernment").orElseThrow();
        assertEquals("eastus", CloudCatalog.normalizeLocation(gov, "East US"));
        assertEquals("global", CloudCatalog.normalizeLocation(gov, "Global"));
        assertNull(CloudCatalog.normalizeLocation(gov, null));
    }

    @Test
    void sovereignCloudsListTheirSixLocations() {
        assertEquals(List.of("usgovvirginia", "usgoviowa", "usgovarizona", "usgovtexas", "usdodeast", "usdodcentral"),
                names(CloudCatalog.forName("AzureUSGovernment").orElseThrow()));
        assertEquals(List.of("chinanorth", "chinaeast", "chinanorth2", "chinaeast2", "chinanorth3", "chinaeast3"),
                names(CloudCatalog.forName("AzureChinaCloud").orElseThrow()));
    }

    @Test
    void zonesAreSourcedOrUnsettled() {
        CloudProfile cloud = CloudCatalog.forName("AzureCloud").orElseThrow();
        assertEquals(List.of("1", "2", "3"), cloud.location("eastus").orElseThrow().zones().value());
        assertEquals(List.of(), cloud.location("westus").orElseThrow().zones().value());
        // regions-list.md counts zones still in preview for these two, so the count is not a fact.
        assertTrue(cloud.location("eastus2").orElseThrow().zones().unsettled());
        assertTrue(cloud.location("northcentralus").orElseThrow().zones().unsettled());
        CloudProfile gov = CloudCatalog.forName("AzureUSGovernment").orElseThrow();
        assertEquals(List.of(), gov.location("usgovtexas").orElseThrow().zones().value());
        assertTrue(gov.location("usgovvirginia").orElseThrow().zones().unsettled());
        for (Location location : CloudCatalog.forName("AzureChinaCloud").orElseThrow().locations()) {
            assertTrue(location.zones().unsettled(), location.name());
        }
    }

    @Test
    void theDefaultLocationFallsBackToTheFirstListedLocation() {
        assertEquals("eastus", CloudCatalog.forName("AzureCloud").orElseThrow().effectiveDefaultLocation());
        assertEquals("usgovvirginia", CloudCatalog.forName("AzureUSGovernment").orElseThrow().effectiveDefaultLocation());
        assertEquals("chinanorth", CloudCatalog.forName("AzureChinaCloud").orElseThrow().effectiveDefaultLocation());
    }

    @Test
    void everyCloudCarriesTheThreeMetadataShapes() {
        for (CloudProfile cloud : CloudCatalog.all()) {
            JsonNode latest = cloud.metadataPayload("2022-09-01").orElseThrow();
            assertEquals(cloud.name(), latest.path("name").asText());
            assertFalse(latest.path("authentication").path("loginEndpoint").asText().isBlank(), cloud.name());
            assertTrue(latest.path("authentication").path("audiences").isArray(), cloud.name());
            assertTrue(latest.path("suffixes").isObject(), cloud.name());
            assertEquals(latest.path("ossrDbmsResourceId"), latest.path("ossrdbmsResourceId"), cloud.name());
            assertEquals(cloud.name(), cloud.metadataPayload("2019-05-01").orElseThrow().path("name").asText());
            JsonNode legacy = cloud.metadataPayload("2018-01-01").orElseThrow();
            assertTrue(legacy.path("cloudEndpoint").path("usGovCloud").path("locations").isArray(), cloud.name());
        }
        assertEquals(Optional.empty(), CloudCatalog.forNameOrPublic(null).metadataPayload("2015-01-01"));
    }

    @Test
    void metadataPayloadsAreCopies() {
        CloudProfile cloud = CloudCatalog.forNameOrPublic(null);
        ((ObjectNode) cloud.metadataPayload("2022-09-01").orElseThrow()).put("name", "changed");
        assertEquals("AzureCloud", cloud.metadataPayload("2022-09-01").orElseThrow().path("name").asText());
        ((ObjectNode) cloud.metadataEndpoints().get("2022-09-01")).put("name", "changed");
        assertEquals("AzureCloud", cloud.metadataPayload("2022-09-01").orElseThrow().path("name").asText());
    }

    @Test
    void loadingIsCachedOnce() {
        assertSame(CloudCatalog.all(), CloudCatalog.all());
    }

    @Test
    void aMissingSuffixIsRejected() throws IOException {
        ObjectNode document = bundled();
        ((ObjectNode) document.path("clouds").get(1).path("suffixes")).remove("ACI");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CloudCatalog.parse(stream(document)));
        assertThat(e.getMessage(), containsString("ACI is missing"));
    }

    @Test
    void anUnsettledCellWithAValueIsRejected() throws IOException {
        ObjectNode document = bundled();
        ((ObjectNode) document.path("clouds").get(2).path("suffixes").path("REDIS")).put("value", "redis.cache.example.cn");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CloudCatalog.parse(stream(document)));
        assertThat(e.getMessage(), containsString("UNSETTLED"));
    }

    @Test
    void aNullValueWithoutAnUnsettledSourceIsRejected() throws IOException {
        ObjectNode document = bundled();
        ((ObjectNode) document.path("clouds").get(0).path("suffixes").path("BLOB")).putNull("value");
        assertThrows(IllegalStateException.class, () -> CloudCatalog.parse(stream(document)));
    }

    @Test
    void anUnknownSuffixKeyIsRejected() throws IOException {
        ObjectNode document = bundled();
        ((ObjectNode) document.path("clouds").get(0).path("suffixes")).set("COSMOS",
                document.path("clouds").get(0).path("suffixes").path("BLOB").deepCopy());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CloudCatalog.parse(stream(document)));
        assertThat(e.getMessage(), containsString("COSMOS"));
    }

    @Test
    void aSuffixClaimedByTwoCloudsIsRejected() throws IOException {
        ObjectNode document = bundled();
        ((ObjectNode) document.path("clouds").get(1).path("suffixes").path("BLOB")).put("value", "blob.core.windows.net");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CloudCatalog.parse(stream(document)));
        assertThat(e.getMessage(), containsString("claimed"));
    }

    @Test
    void aCatalogWithoutThePublicCloudIsRejected() throws IOException {
        ObjectNode document = bundled();
        ((ArrayNode) document.path("clouds")).remove(0);
        assertThrows(IllegalStateException.class, () -> CloudCatalog.parse(stream(document)));
    }

    @Test
    void malformedJsonIsRejected() {
        InputStream in = new ByteArrayInputStream("{not json".getBytes(StandardCharsets.UTF_8));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CloudCatalog.parse(in));
        assertThat(e.getMessage(), containsString("not valid JSON"));
        assertThrows(IllegalStateException.class,
                () -> CloudCatalog.parse(new ByteArrayInputStream("{\"clouds\":[]}".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void theNativeImageIncludesTheCatalogResource() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(in);
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml, containsString("azure/**"));
        }
    }

    @Test
    void theStartupValidatorLoadsTheCatalog() {
        new CloudStartupValidator().onStart(null);
    }

    private static List<String> names(CloudProfile cloud) {
        return cloud.locations().stream().map(Location::name).toList();
    }

    private static ObjectNode bundled() throws IOException {
        try (InputStream in = CloudCatalogTest.class.getClassLoader().getResourceAsStream(CloudCatalog.RESOURCE)) {
            assertNotNull(in);
            return (ObjectNode) MAPPER.readTree(in);
        }
    }

    private static InputStream stream(JsonNode document) throws IOException {
        return new ByteArrayInputStream(MAPPER.writeValueAsBytes(document));
    }
}
