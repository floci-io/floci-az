package io.floci.az.services.arm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.cloud.CloudProfile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
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
 * filters on. {@code physicalLocation} comes from real Azure answers in the az CLI's recordings, for the public cloud,
 * because azd's location list drops a region without one. {@code geography}, {@code geographyGroup},
 * {@code latitude}, {@code longitude} and {@code pairedRegion} are optional in the spec and are omitted.
 */
final class AzureLocations {

    private static final String PHYSICAL_LOCATIONS_RESOURCE = "azure/physical-locations.json";

    /** Each public region's {@code physicalLocation}, as real Azure answers it in the az CLI's recordings. */
    private static final Map<String, String> PHYSICAL_LOCATIONS = physicalLocations();

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
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("regionType", "Physical");
        metadata.put("regionCategory", "Recommended");
        String physicalLocation = PHYSICAL_LOCATIONS.get(location.name());
        if (physicalLocation != null) {
            metadata.put("physicalLocation", physicalLocation);
        }
        body.put("metadata", metadata);
        body.put("availabilityZoneMappings", zoneMappings(location.name()));
        return body;
    }

    private static Map<String, String> physicalLocations() {
        try (InputStream in = AzureLocations.class.getClassLoader().getResourceAsStream(PHYSICAL_LOCATIONS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + PHYSICAL_LOCATIONS_RESOURCE);
            }
            Map<String, String> locations = new HashMap<>();
            new ObjectMapper().readTree(in).path("locations").properties()
                .forEach(field -> locations.put(field.getKey(), field.getValue().asText()));
            return Map.copyOf(locations);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + PHYSICAL_LOCATIONS_RESOURCE, e);
        }
    }

    private static List<Map<String, String>> zoneMappings(String name) {
        List<Map<String, String>> zones = new ArrayList<>();
        for (int zone = 1; zone <= 3; zone++) {
            zones.add(Map.of("logicalZone", String.valueOf(zone), "physicalZone", name + "-az" + zone));
        }
        return zones;
    }
}
