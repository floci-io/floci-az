package io.floci.az.services.apim;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** The API Management resources kept in an {@code ArmResourceStore}. */
public final class ApiManagementModels {

    /** The ARM type of an API Management service. */
    public static final String SERVICE_TYPE = "Microsoft.ApiManagement/service";

    /** The type a deleted service's id carries, which the deleted-services store is keyed by. */
    public static final String DELETED_SERVICE_ID_TYPE = "Microsoft.ApiManagement/locations/deletedservices";

    /** The {@code type} a {@code DeletedServiceContract} answers with, which the spec spells without the location. */
    public static final String DELETED_SERVICE_TYPE = "Microsoft.ApiManagement/deletedservices";

    private ApiManagementModels() {
    }

    /**
     * One API Management service, as its PUT stored it.
     *
     * @param id         the ARM id, in the casing of the request that created the service
     * @param tags       the tags the request sent, or null when no write has sent any
     * @param identity   the managed identity, as {@code ArmIdentities} resolved it, or null for none
     * @param properties the service properties, defaults included
     * @param sku        the SKU the request sent, or the default one
     */
    @RegisterForReflection
    public record ApimService(String id, String name, String location, String etag, Map<String, Object> tags,
                              Map<String, Object> identity, Map<String, Object> properties, Map<String, Object> sku) {

        /**
         * The ARM body: the same fields, in the order the service has always answered with. {@code tags} and
         * {@code identity} appear only when the service has them, so a service stored before they were kept answers
         * as it did.
         */
        public Map<String, Object> toArm() {
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("id", id);
            resource.put("name", name);
            resource.put("type", SERVICE_TYPE);
            if (location != null) {
                resource.put("location", location);
            }
            if (tags != null) {
                resource.put("tags", tags);
            }
            resource.put("etag", etag);
            resource.put("properties", properties);
            resource.put("sku", sku);
            if (identity != null) {
                resource.put("identity", identity);
            }
            return resource;
        }
    }

    /**
     * A soft-deleted API Management service: what {@code DeletedServices_GetByName} answers, and the service it
     * replaced, kept so {@code restore: true} brings it back. The service is never returned to a client.
     *
     * @param id              the deleted service's id, {@code .../locations/{location}/deletedservices/{name}}
     * @param location        the location's display name, as the spec's example and Azure answer it
     * @param deletionDate    when the service was deleted
     * @param scheduledPurgeDate when the record goes, and the name with it
     * @param service         the service as it was when deleted
     */
    @RegisterForReflection
    public record ApimDeletedService(String id, String name, String location, Instant deletionDate,
                                     Instant scheduledPurgeDate, ApimService service) {

        /** The {@code DeletedServiceContract} body. */
        public Map<String, Object> toArm() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("serviceId", service.id());
            properties.put("scheduledPurgeDate", scheduledPurgeDate.toString());
            properties.put("deletionDate", deletionDate.toString());
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("id", id);
            resource.put("name", name);
            resource.put("type", DELETED_SERVICE_TYPE);
            resource.put("location", location);
            resource.put("properties", properties);
            return resource;
        }
    }
}
