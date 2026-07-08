package io.floci.az.services.network;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class NetworkHandler implements Resettable {

    private final NetworkService service;

    @Inject
    public NetworkHandler(NetworkService service) {
        this.service = service;
    }

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
