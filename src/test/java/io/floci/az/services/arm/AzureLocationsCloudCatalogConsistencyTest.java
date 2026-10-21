package io.floci.az.services.arm;

import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AzureLocations} serves each cloud's catalog locations, in catalog order, every one a physical region with
 * zones 1 to 3. The public cloud's 51 regions are the list floci-az has always served; the golden
 * {@code locations list} step pins that output byte for byte. Lives in this package because
 * {@link AzureLocations} is package-private.
 */
class AzureLocationsCloudCatalogConsistencyTest {

    private static final String SUB = "00000000-0000-0000-0000-000000000001";

    @Test
    void everyCloudServesItsCatalogLocationsInOrder() {
        for (CloudProfile cloud : CloudCatalog.all()) {
            List<Map<String, Object>> served = AzureLocations.forSubscription(cloud, SUB);

            assertEquals(cloud.locations().stream().map(CloudProfile.Location::name).toList(),
                served.stream().map(body -> body.get("name")).toList(), cloud.name());
            assertEquals(cloud.locations().stream().map(CloudProfile.Location::displayName).toList(),
                AzureLocations.displayNames(cloud), cloud.name());
            for (Map<String, Object> body : served) {
                assertEquals(3, ((List<?>) body.get("availabilityZoneMappings")).size(), body.get("name") + "");
            }
        }
    }

    @Test
    void thePublicCloudKeepsItsFiftyOneRegionsStartingWithEastUs() {
        List<Map<String, Object>> served =
            AzureLocations.forSubscription(CloudCatalog.forName(CloudCatalog.PUBLIC_CLOUD).orElseThrow(), SUB);

        assertEquals(51, served.size());
        assertEquals("eastus", served.getFirst().get("name"));
        assertEquals("/subscriptions/" + SUB + "/locations/eastus", served.getFirst().get("id"));
    }
}
