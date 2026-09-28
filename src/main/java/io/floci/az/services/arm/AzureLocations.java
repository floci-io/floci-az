package io.floci.az.services.arm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The public-cloud region catalog served by {@code GET /subscriptions/{sub}/locations}
 * ({@code Subscriptions_ListLocations}, api-version 2022-12-01).
 *
 * <p>Names and display names are the public-cloud entries of the Azure SDK for Java's
 * {@code com.azure.core.management.Region}; the sovereign-cloud entries (US Gov, US DoD, China and the
 * retired Germany cloud) are left out because the emulated subscription is a public-cloud one.
 *
 * <p>Every entry is a physical region with zones 1 to 3, which is what {@code data.azurerm_location}
 * filters on. {@code geography}, {@code geographyGroup}, {@code latitude}, {@code longitude},
 * {@code physicalLocation} and {@code pairedRegion} are optional in the spec and have no local source,
 * so they are omitted rather than invented.
 */
final class AzureLocations {

    record Location(String name, String displayName) {}

    static final List<Location> ALL = List.of(
            new Location("eastus", "East US"),
            new Location("eastus2", "East US 2"),
            new Location("southcentralus", "South Central US"),
            new Location("westus2", "West US 2"),
            new Location("centralus", "Central US"),
            new Location("northcentralus", "North Central US"),
            new Location("westus", "West US"),
            new Location("westcentralus", "West Central US"),
            new Location("westus3", "West US 3"),
            new Location("canadacentral", "Canada Central"),
            new Location("canadaeast", "Canada East"),
            new Location("brazilsouth", "Brazil South"),
            new Location("brazilsoutheast", "Brazil Southeast"),
            new Location("mexicocentral", "Mexico Central"),
            new Location("northeurope", "North Europe"),
            new Location("uksouth", "UK South"),
            new Location("westeurope", "West Europe"),
            new Location("francecentral", "France Central"),
            new Location("germanywestcentral", "Germany West Central"),
            new Location("norwayeast", "Norway East"),
            new Location("switzerlandnorth", "Switzerland North"),
            new Location("swedencentral", "Sweden Central"),
            new Location("francesouth", "France South"),
            new Location("germanynorth", "Germany North"),
            new Location("norwaywest", "Norway West"),
            new Location("switzerlandwest", "Switzerland West"),
            new Location("ukwest", "UK West"),
            new Location("italynorth", "Italy North"),
            new Location("spaincentral", "Spain Central"),
            new Location("polandcentral", "Poland Central"),
            new Location("australiaeast", "Australia East"),
            new Location("southeastasia", "Southeast Asia"),
            new Location("centralindia", "Central India"),
            new Location("eastasia", "East Asia"),
            new Location("japaneast", "Japan East"),
            new Location("koreacentral", "Korea Central"),
            new Location("australiacentral", "Australia Central"),
            new Location("australiacentral2", "Australia Central 2"),
            new Location("australiasoutheast", "Australia Southeast"),
            new Location("japanwest", "Japan West"),
            new Location("koreasouth", "Korea South"),
            new Location("southindia", "South India"),
            new Location("westindia", "West India"),
            new Location("indonesiacentral", "Indonesia Central"),
            new Location("newzealandnorth", "New Zealand North"),
            new Location("uaenorth", "UAE North"),
            new Location("uaecentral", "UAE Central"),
            new Location("israelcentral", "Israel Central"),
            new Location("qatarcentral", "Qatar Central"),
            new Location("southafricanorth", "South Africa North"),
            new Location("southafricawest", "South Africa West"));

    private AzureLocations() {
    }

    /** The {@code Location} bodies for one subscription, in catalog order. */
    static List<Map<String, Object>> forSubscription(String subscriptionId) {
        List<Map<String, Object>> locations = new ArrayList<>();
        for (Location location : ALL) {
            locations.add(body(subscriptionId, location));
        }
        return locations;
    }

    private static Map<String, Object> body(String subscriptionId, Location location) {
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
