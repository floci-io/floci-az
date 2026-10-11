package io.floci.az.services.arm;

import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The public cloud's locations in {@code azure/clouds.json} are exactly the catalog
 * {@code GET /subscriptions/{sub}/locations} serves today, in the same order and with the same display
 * names, so {@link AzureLocations} can later be replaced by the cloud catalog without a visible change.
 * Lives in this package because {@link AzureLocations} is package-private.
 */
class AzureLocationsCloudCatalogConsistencyTest {

    @Test
    void thePublicCloudLocationsEqualTheServedLocationCatalog() {
        CloudProfile cloud = CloudCatalog.forName(CloudCatalog.PUBLIC_CLOUD).orElseThrow();
        List<String> catalog = cloud.locations().stream()
                .map(location -> location.name() + "=" + location.displayName())
                .toList();
        List<String> served = AzureLocations.ALL.stream()
                .map(location -> location.name() + "=" + location.displayName())
                .toList();
        assertEquals(51, served.size());
        assertEquals(served, catalog);
    }
}
