package io.floci.az.core.cloud;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One Azure cloud as {@code azure/clouds.json} describes it: endpoints, ARM audiences, the DNS suffix of
 * every {@link CloudSuffix}, locations and the {@code /metadata/endpoints} payloads.
 *
 * @param name              the cloud name as the Azure CLI and ARM use it, e.g. {@code AzureUSGovernment}
 * @param endpoints         ARM, login and Graph endpoints
 * @param audiences         the two ARM token audiences: {@code https://management.core.<suffix>} and the ARM host
 * @param suffixes          one entry per {@link CloudSuffix}; an unsettled entry has a null value
 * @param defaultLocation   the cloud's default location when a source names one; see {@link #effectiveDefaultLocation()}
 * @param locations         the cloud's locations, in the Azure SDK for Java's {@code Region} order
 * @param metadataEndpoints the {@code /metadata/endpoints} payload per api-version; the accessor returns copies
 * @param metadataUnsettled payload keys left out because no local source settles them
 */
public record CloudProfile(
        String name,
        Endpoints endpoints,
        List<String> audiences,
        Map<CloudSuffix, Sourced<String>> suffixes,
        Sourced<String> defaultLocation,
        List<Location> locations,
        Map<String, JsonNode> metadataEndpoints,
        List<UnsettledField> metadataUnsettled) {

    public CloudProfile {
        audiences = List.copyOf(audiences);
        suffixes = Collections.unmodifiableMap(new EnumMap<>(suffixes));
        locations = List.copyOf(locations);
        metadataEndpoints = Map.copyOf(metadataEndpoints);
        metadataUnsettled = List.copyOf(metadataUnsettled);
    }

    /** A copy of every {@code /metadata/endpoints} payload, so no caller can change the catalog's own nodes. */
    @Override
    public Map<String, JsonNode> metadataEndpoints() {
        Map<String, JsonNode> copies = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : metadataEndpoints.entrySet()) {
            copies.put(entry.getKey(), entry.getValue().deepCopy());
        }
        return Collections.unmodifiableMap(copies);
    }

    /** The DNS suffix of {@code suffix} in this cloud, without a leading dot, or empty when it is unsettled here. */
    public Optional<String> suffix(CloudSuffix suffix) {
        Sourced<String> entry = suffixes.get(suffix);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.value());
    }

    /**
     * The location new resources default to. No local source names one for any cloud yet, so this falls
     * back to the first listed location, which is the Azure SDK for Java's first region for the cloud
     * ({@code eastus}, {@code usgovvirginia}, {@code chinanorth}).
     */
    public String effectiveDefaultLocation() {
        return defaultLocation.value() != null ? defaultLocation.value() : locations.getFirst().name();
    }

    /** The location whose canonical name is {@code name}, compared exactly. */
    public Optional<Location> location(String name) {
        for (Location location : locations) {
            if (location.name().equals(name)) {
                return Optional.of(location);
            }
        }
        return Optional.empty();
    }

    /** A copy of the {@code /metadata/endpoints} payload for {@code apiVersion}, or empty for an unknown version. */
    public Optional<JsonNode> metadataPayload(String apiVersion) {
        JsonNode payload = metadataEndpoints.get(apiVersion);
        return payload == null ? Optional.empty() : Optional.of(payload.deepCopy());
    }

    /**
     * @param resourceManager the ARM endpoint, e.g. {@code https://management.usgovcloudapi.net}
     * @param activeDirectory the login endpoint the SDKs use
     * @param authorityHosts  every login host of the cloud, the SDK one first (China also has
     *                        {@code login.partner.microsoftonline.cn})
     * @param microsoftGraph  the Microsoft Graph endpoint
     * @param portal          the portal URL
     */
    public record Endpoints(String resourceManager, String activeDirectory, List<String> authorityHosts,
                            String microsoftGraph, String portal) {

        public Endpoints {
            authorityHosts = List.copyOf(authorityHosts);
        }
    }

    /**
     * A catalog value with its provenance. A value no local source settles is null, and its source starts
     * with {@value #UNSETTLED_PREFIX}.
     */
    public record Sourced<T>(T value, String source) {

        public static final String UNSETTLED_PREFIX = "UNSETTLED:";

        public boolean unsettled() {
            return value == null;
        }
    }

    /**
     * @param name        the canonical location name, e.g. {@code eastus}
     * @param displayName the display name, e.g. {@code East US}
     * @param zones       the logical availability zones ({@code "1"} to {@code "n"}), empty for a region
     *                    without zones, or unsettled
     */
    public record Location(String name, String displayName, Sourced<List<String>> zones) {
    }

    /** A {@code /metadata/endpoints} key left out of this cloud's payloads, and why. */
    public record UnsettledField(String field, String source) {
    }
}
