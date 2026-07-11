package io.floci.az.services.eventgrid;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Azure Event Grid handler. Serves both planes for {@code serviceType="eventgrid"}:
 * <ul>
 *   <li>Control plane — ARM paths containing {@code /providers/Microsoft.EventGrid/}
 *       (topics + classic scoped eventSubscriptions) → {@link EventGridService}.</li>
 *   <li>Data plane — {@code POST /api/events} reached via the {@code {topic}-eventgrid}
 *       account suffix → {@link EventGridPublisher}.</li>
 * </ul>
 */
@ApplicationScoped
public class EventGridHandler implements AzureServiceHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(EventGridHandler.class);
    private static final String ARM_MARKER = "/providers/Microsoft.EventGrid/";

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
        return ServiceRoutes.builder()
                .account("-eventgrid", "eventgrid")
                .provider("Microsoft.EventGrid")
                .build();

    }

    @Override
    public boolean canHandle(AzureRequest request) {
        return "eventgrid".equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest req) {
        String method = req.method().toUpperCase();

        // Control plane: the routing filter forwards the full ARM path as resourcePath.
        if (req.resourcePath().contains(ARM_MARKER)) {
            return service.handleArm(req, req.resourcePath(), method);
        }

        // Data plane: {topic}-eventgrid/api/events
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
