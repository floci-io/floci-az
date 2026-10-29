package io.floci.az.services.eventgrid;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmProviderService;
import io.floci.az.core.arm.ResourceIndexContributor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
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
public class EventGridHandler implements AzureServiceHandler, Resettable, ArmProviderService, ResourceIndexContributor {

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
    public Set<String> providerNamespaces() {
        return Set.of("Microsoft.EventGrid");
    }

    /** Its subscription-wide topic list and the event subscriptions whose scope is the subscription. */
    @Override
    public boolean servesSubscriptionPaths() {
        return true;
    }

    @Override
    public boolean armEnabled() {
        return config.services().eventGrid().enabled();
    }

    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        return service.topicIndexEntries(sub, rg);
    }

    @Override
    public List<Map<String, Object>> listSubscriptionResources(String sub) {
        return service.topicIndexEntries(sub, null);
    }

    @Override
    public boolean indexEnabled() {
        return config.services().eventGrid().enabled();
    }

    /** Joined the index with the resource-group cascade; listed only under {@code resource-group-delete}. */
    @Override
    public boolean inDefaultListing() {
        return false;
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

    public void clear() {
        service.clear();
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }
}
