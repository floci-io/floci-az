package io.floci.az.services.network;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.Resettable;
import io.floci.az.core.arm.ArmProviderService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class NetworkHandler implements Resettable, ArmProviderService {

    private final NetworkService service;
    private final EmulatorConfig config;

    @Inject
    public NetworkHandler(NetworkService service, EmulatorConfig config) {
        this.service = service;
        this.config = config;
    }

    @Override
    public Set<String> providerNamespaces() {
        return Set.of("Microsoft.Network");
    }

    @Override
    public boolean armEnabled() {
        return config.services().network().enabled();
    }

    @Override
    public Response handleArm(AzureRequest request, String path, String method, String sub) {
        return service.handleArm(request, path, method, sub);
    }

    public List<Map<String, Object>> listResources(String sub, String rg) {
        return service.listResources(sub, rg);
    }

    @Override
    public void clearAll() {
        service.clearAll();
    }
}
