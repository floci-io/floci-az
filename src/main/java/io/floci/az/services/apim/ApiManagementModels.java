package io.floci.az.services.apim;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** The API Management resources kept in an {@code ArmResourceStore}. */
public final class ApiManagementModels {

    /** The ARM type of an API Management service. */
    public static final String SERVICE_TYPE = "Microsoft.ApiManagement/service";

    private ApiManagementModels() {
    }

    /**
     * One API Management service, as its PUT stored it.
     *
     * @param id         the ARM id, in the casing of the request that created the service
     * @param properties the service properties, defaults included
     * @param sku        the SKU the request sent, or the default one
     */
    @RegisterForReflection
    public record ApimService(String id, String name, String location, String etag,
                              Map<String, Object> properties, Map<String, Object> sku) {

        /** The ARM body: the same fields, in the order the service has always answered with. */
        public Map<String, Object> toArm() {
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("id", id);
            resource.put("name", name);
            resource.put("type", SERVICE_TYPE);
            if (location != null) {
                resource.put("location", location);
            }
            resource.put("etag", etag);
            resource.put("properties", properties);
            resource.put("sku", sku);
            return resource;
        }
    }
}
