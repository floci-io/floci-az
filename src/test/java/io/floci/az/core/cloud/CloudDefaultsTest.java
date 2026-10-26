package io.floci.az.core.cloud;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudDefaultsTest {

    private static final CloudProfile PUBLIC = CloudCatalog.forNameOrPublic("AzureCloud");
    private static final CloudProfile GOVERNMENT = CloudCatalog.forNameOrPublic("AzureUSGovernment");

    @Test
    void withNothingConfiguredEachCloudAnswersItsCatalogDefault() {
        CloudDefaults defaults = CloudDefaults.catalogOnly();

        assertEquals("eastus", defaults.defaultLocation(PUBLIC));
        assertEquals("usgovvirginia", defaults.defaultLocation(GOVERNMENT));
        assertEquals("eastus", defaults.defaultLocation());
    }

    @Test
    void aConfiguredLocationIsNormalizedAndAppliesOnlyToItsCloud() {
        CloudDefaults defaults = new CloudDefaults(Map.of("AzureCloud", "West Europe"));

        assertEquals("westeurope", defaults.defaultLocation(PUBLIC));
        assertEquals("usgovvirginia", defaults.defaultLocation(GOVERNMENT));
    }

    @Test
    void theCloudNameIsMatchedWithoutCaseAsTheEnvironmentVariableFormSpellsIt() {
        assertEquals("westeurope", new CloudDefaults(Map.of("azurecloud", "westeurope")).defaultLocation(PUBLIC));
    }

    @Test
    void aLocationTheCloudDoesNotOfferFailsValidation() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> new CloudDefaults(Map.of("AzureCloud", "usgovvirginia")));
        assertThat(refused.getMessage(), containsString("is not a location of AzureCloud"));
    }

    @Test
    void aCloudTheCatalogDoesNotHoldFailsValidation() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> new CloudDefaults(Map.of("AzureMoon", "eastus")));
        assertThat(refused.getMessage(), containsString("is not a cloud of the catalog"));
    }
}
