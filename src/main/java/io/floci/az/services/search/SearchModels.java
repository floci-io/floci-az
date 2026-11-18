package io.floci.az.services.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What floci-az stores for Azure AI Search. */
public final class SearchModels {

    /** The resource type of a search service, spelled as Azure spells it. */
    public static final String SEARCH_SERVICES = "Microsoft.Search/searchServices";

    private SearchModels() {
    }

    /**
     * A query API key. The key a service is created with has no name, which Azure lists as {@code "name": null}.
     *
     * @param name the name the client gave it, or null for the default key
     * @param key  the key itself
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueryKey(String name, String key) {

        /** The key's wire body, with an explicit null name for the default key. */
        public Map<String, Object> toArm() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            body.put("key", key);
            return body;
        }
    }

    /**
     * A {@code Microsoft.Search/searchServices} resource with the keys that belong to it. The keys are stored with
     * the resource and never returned on it: Azure hands them out only through the key operations.
     *
     * @param id              the resource id, in the casing of the PUT that created it
     * @param name            the service name
     * @param location        the location in canonical form ({@code westus}), for the generic resource listings
     * @param displayLocation the location as the service answers it, a display name ({@code West US}) when the
     *                        cloud knows the location
     * @param tags            the tags the client sent; Azure answers {@code {}} when it sent none
     * @param sku             the SKU name
     * @param identity        the managed identity, or null when the service has none
     * @param properties      the response {@code properties}
     * @param createdAt       when the service was created, for {@code systemData}
     * @param primaryKey      the primary admin key
     * @param secondaryKey    the secondary admin key
     * @param queryKeys       the query keys, the default one first
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchServiceResource(
            String id,
            String name,
            String location,
            String displayLocation,
            Map<String, Object> tags,
            String sku,
            Map<String, Object> identity,
            Map<String, Object> properties,
            String createdAt,
            String primaryKey,
            String secondaryKey,
            List<QueryKey> queryKeys
    ) {

        public SearchServiceResource {
            queryKeys = queryKeys == null ? List.of() : List.copyOf(queryKeys);
        }

        /** The resource's wire body: everything but the keys. */
        public Map<String, Object> toArm() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", id);
            body.put("name", name);
            body.put("type", SEARCH_SERVICES);
            body.put("location", displayLocation);
            body.put("tags", tags == null ? Map.of() : tags);
            body.put("sku", Map.of("name", sku));
            if (identity != null) {
                body.put("identity", identity);
            }
            body.put("properties", properties);
            body.put("systemData", Map.of("createdAt", createdAt));
            return body;
        }

        /** This service with other admin keys. */
        public SearchServiceResource withAdminKeys(String primary, String secondary) {
            return new SearchServiceResource(id, name, location, displayLocation, tags, sku, identity, properties,
                    createdAt, primary, secondary, queryKeys);
        }

        /** This service with other query keys. */
        public SearchServiceResource withQueryKeys(List<QueryKey> keys) {
            return new SearchServiceResource(id, name, location, displayLocation, tags, sku, identity, properties,
                    createdAt, primaryKey, secondaryKey, keys);
        }
    }
}
