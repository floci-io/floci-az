package io.floci.az.core;

import jakarta.ws.rs.core.Response;

public interface AzureServiceHandler {
    String getServiceType();                     // "blob", "queue", "table"
    boolean canHandle(AzureRequest request);     // fine-grained match if needed
    Response handle(AzureRequest request);

    /**
     * Returns true if this handler can process requests for the given service type.
     * The default implementation delegates to {@link #getServiceType()}.
     * Override for handlers that serve multiple service types.
     */
    default boolean handlesServiceType(String serviceType) {
        return getServiceType().equals(serviceType);
    }
}
