package io.floci.az.core.cloud;

import io.floci.az.config.EmulatorConfig;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudDefaultLocationEnvTest {

    private static final CloudProfile PUBLIC = CloudCatalog.forNameOrPublic("AzureCloud");
    private static final CloudProfile GOVERNMENT = CloudCatalog.forNameOrPublic("AzureUSGovernment");

    @Test
    void theEnvironmentVariableAloneSetsTheCloudDefaultLocation() {
        EmulatorConfig.CloudConfig cloud = cloudConfig(Map.of(
            "FLOCI_AZ_CLOUD_AZURECLOUD_DEFAULT_LOCATION", "westeurope"));

        CloudDefaults defaults = new CloudDefaults(CloudDefaults.configured(cloud.clouds()));

        assertEquals("westeurope", defaults.defaultLocation(PUBLIC));
        assertEquals("usgovvirginia", defaults.defaultLocation(GOVERNMENT));
        assertEquals("AzureCloud", cloud.defaultCloud());
    }

    @Test
    void theDefaultCloudVariableIsNotMistakenForACloudEntry() {
        EmulatorConfig.CloudConfig cloud = cloudConfig(Map.of(
            "FLOCI_AZ_CLOUD_DEFAULT", "AzureUSGovernment",
            "FLOCI_AZ_CLOUD_AZUREUSGOVERNMENT_DEFAULT_LOCATION", "usgovarizona"));

        CloudDefaults defaults = new CloudDefaults(CloudDefaults.configured(cloud.clouds()));

        assertEquals("AzureUSGovernment", cloud.defaultCloud());
        assertEquals("usgovarizona", defaults.defaultLocation(GOVERNMENT));
        assertEquals("eastus", defaults.defaultLocation(PUBLIC));
    }

    private static EmulatorConfig.CloudConfig cloudConfig(Map<String, String> env) {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
            .withSources(new EnvConfigSource(env, 300))
            .withMapping(EmulatorConfig.CloudConfig.class, "floci-az.cloud")
            .build();
        return config.getConfigMapping(EmulatorConfig.CloudConfig.class, "floci-az.cloud");
    }
}
