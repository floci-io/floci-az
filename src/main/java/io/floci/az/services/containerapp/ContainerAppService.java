package io.floci.az.services.containerapp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.StoredObject;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import java.util.Optional;

/**
 * Minimal Container App service implementation to satisfy compilation.
 * Provides basic handler scaffolding; actual business logic can be expanded later.
 */
@ApplicationScoped
public class ContainerAppService implements AzureServiceHandler {

    private static final Logger LOGGER = Logger.getLogger(ContainerAppService.class);

    private final StorageBackend<String, StoredObject> store;

    @Inject
    public ContainerAppService(StorageFactory storageFactory) {
        // Use a dedicated namespace for container apps.
        this.store = storageFactory.create("containerapp");
    }

    @Override
    public String getServiceType() {
        return "containerapp";
    }

    @Override
    public boolean canHandle(AzureRequest request) {
        return "containerapp".equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest request) {
        // Placeholder: return Not Implemented response.
        LOGGER.infof("ContainerAppService received %s %s", request.method(), request.resourcePath());
        return new io.floci.az.core.AzureErrorResponse("NotImplemented", "Container App operations are not yet implemented.")
                .toJsonResponse(Response.Status.NOT_IMPLEMENTED.getStatusCode());
    }
}
