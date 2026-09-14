package io.floci.az.services.signalr;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@ApplicationScoped
public class SignalRHandler implements AzureServiceHandler {
    private final EmulatorConfig config;

    @Inject
    public SignalRHandler(EmulatorConfig config) { this.config = config; }

    @Override
    public String getServiceType() { return "signalr"; }

    @Override
    public boolean enabled(String serviceType) { return config.services().signalr().enabled(); }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder().host(".service.signalr.net").account("-signalr", "signalr").build();
    }

    @Override
    public boolean canHandle(AzureRequest request) { return "signalr".equals(request.serviceType()); }

    @Override
    public Response handle(AzureRequest request) {
        return Response.status(501).entity(Map.of("error", Map.of("code", "NotImplemented",
                "message", "SignalR supports WebSocket default mode; REST management is not implemented"))).build();
    }
}
