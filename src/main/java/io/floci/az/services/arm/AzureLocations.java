package io.floci.az.services.arm;

import io.floci.az.core.cloud.CloudProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The region list served by {@code GET /subscriptions/{sub}/locations} ({@code Subscriptions_ListLocations},
 * api-version 2022-12-01), for the cloud the subscription belongs to.
 *
 * <p>Names, display names and order come from the cloud catalog ({@code azure/clouds.json}), whose entries are
 * the Azure SDK for Java's {@code com.azure.core.management.Region}; for the public cloud that is the same
 * 51-region list this class once held itself.
 *
 * <p>Every entry is a physical region with zones 1 to 3, which is what {@code data.azurerm_location}
 * filters on. {@code geography}, {@code geographyGroup}, {@code latitude}, {@code longitude},
 * {@code physicalLocation} and {@code pairedRegion} are optional in the spec and have no local source,
 * so they are omitted rather than invented.
 */
final class AzureLocations {

    private AzureLocations() {
    }

    /** The {@code Location} bodies for one subscription of {@code cloud}, in catalog order. */
    static List<Map<String, Object>> forSubscription(CloudProfile cloud, String subscriptionId) {
        List<Map<String, Object>> locations = new ArrayList<>();
        for (CloudProfile.Location location : cloud.locations()) {
            locations.add(body(subscriptionId, location));
        }
        return locations;
    }

    /** The display names of {@code cloud}'s regions, in catalog order. */
    static List<String> displayNames(CloudProfile cloud) {
        return cloud.locations().stream().map(CloudProfile.Location::displayName).toList();
    }

    private static Map<String, Object> body(String subscriptionId, CloudProfile.Location location) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "/subscriptions/" + subscriptionId + "/locations/" + location.name());
        body.put("subscriptionId", subscriptionId);
        body.put("name", location.name());
        body.put("type", "Region");
        body.put("displayName", location.displayName());
        body.put("regionalDisplayName", location.displayName());
        body.put("metadata", Map.of(
                "regionType", "Physical",
                "regionCategory", "Recommended"));
        body.put("availabilityZoneMappings", zoneMappings(location.name()));
        return body;
    }

    private static List<Map<String, String>> zoneMappings(String name) {
        List<Map<String, String>> zones = new ArrayList<>();
        for (int zone = 1; zone <= 3; zone++) {
            zones.add(Map.of("logicalZone", String.valueOf(zone), "physicalZone", name + "-az" + zone));
        }
        return zones;
    }
}
