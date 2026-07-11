package io.floci.az.services.eventgrid;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.Resettable;
import io.floci.az.core.arm.ArmProviderService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * Azure Event Grid handler, split across the two ARM lanes:
 * <ul>
 *   <li>Control plane — {@code /providers/Microsoft.EventGrid/} (topics + classic scoped
 *       eventSubscriptions). Reached through the ArmHandler lane via {@link ArmProviderService};
 *       {@code ArmHandler} extracts the subscription and passes it in, so this handler no longer
 *       re-parses it. Delegates to {@link EventGridService}.</li>
 *   <li>Data plane — {@code POST /api/events} reached via the {@code {topic}-eventgrid} account
 *       suffix (the filter lane) → {@link EventGridPublisher}.</li>
 * </ul>
 */
@ApplicationScoped
public class EventGridHandler implements AzureServiceHandler, Resettable, ArmProviderService {

    private static final Logger LOG = Logger.getLogger(EventGridHandler.class);

    private final EventGridService service;
    private final EventGridPublisher publisher;

    private final EmulatorConfig config;


    @Inject
    public EventGridHandler(EventGridService service, EventGridPublisher publisher, EmulatorConfig config) {
        this.config = config;
        this.service = service;
        this.publisher = publisher;
    }

    @Override
    public String getServiceType() {
        return "eventgrid";
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().eventGrid().enabled();
    }


    @Override

    public ServiceRoutes routes() {
        // Only the data-plane account suffix. The control plane is claimed via ArmProviderService
        // (providerNamespaces), routed through the ArmHandler lane — not the filter's provider table.
        return ServiceRoutes.builder()
                .account("-eventgrid", "eventgrid")
                .build();
    }

    @Override
    public boolean canHandle(AzureRequest request) {
        return "eventgrid".equals(request.serviceType());
    }

    @Override
    public Set<String> providerNamespaces() {
        return Set.of("Microsoft.EventGrid");
    }

    @Override
    public boolean armEnabled() {
        return config.services().eventGrid().enabled();
    }

    /** Control plane (ArmHandler lane): {@code sub} is supplied by ArmHandler. */
    @Override
    public Response handleArm(AzureRequest req, String path, String method, String sub) {
        return service.handleArm(req, path, method.toUpperCase(), sub);
    }

    @Override
    public Response handle(AzureRequest req) {
        // Data plane only: {topic}-eventgrid/api/events (control plane arrives via handleArm).
        String method = req.method().toUpperCase();
        String path = stripQuery(req.resourcePath());
        if (path.equals("api/events") && "POST".equals(method)) {
            return publisher.publish(req, req.accountName());
        }

        LOG.debugf("EventGrid: unhandled %s /%s", method, path);
        return Response.status(404).entity("{\"error\":{\"code\":\"NotFound\",\"message\":\"Unsupported Event Grid path: "
                + path + "\"}}").type("application/json").build();
    }

    public void clearAll() {
        service.clearAll();
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }
}
