package io.floci.az.services.apim;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ApiManagementHandler implements AzureServiceHandler, Resettable {

    private final ApiManagementGateway gateway;
    private final ApiManagementService service;

    @Inject
    public ApiManagementHandler(ApiManagementGateway gateway, ApiManagementService service) {
        this.gateway = gateway;
        this.service = service;
    }

    @Override
    public String getServiceType() {
        return "apim";
    }

    @Override
    public boolean canHandle(AzureRequest request) {
        return "apim".equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest request) {
        return gateway.handle(request);
    }

    public Response handleArm(AzureRequest request, String path, String method, String sub) {
        return service.handleArm(request, path, method, sub);
    }

    public List<Map<String, Object>> listServices(String sub, String rg) {
        return service.listServices(sub, rg);
    }

    public List<Map<String, Object>> listSubscriptionServices(String sub) {
        return service.listSubscriptionServices(sub);
    }

    @Override
    public void clearAll() {
        service.clearAll();
    }
}
