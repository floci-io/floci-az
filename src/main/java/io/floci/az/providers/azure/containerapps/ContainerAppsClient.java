package io.floci.az.providers.azure.containerapps;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.floci.az.services.containerapp.ContainerAppService;

/**
 * Simple CDI client wrapper for Container App operations.
 * It exposes the {@link ContainerAppService} bean so that other components (e.g., {@link io.floci.az.core.AzureServiceRegistry}) can inject
 * a concrete client without depending directly on the service implementation.
 */
@ApplicationScoped
public class ContainerAppsClient {

    private final ContainerAppService containerAppService;

    @Inject
    public ContainerAppsClient(ContainerAppService containerAppService) {
        this.containerAppService = containerAppService;
    }

    public ContainerAppService getService() {
        return containerAppService;
    }
}
