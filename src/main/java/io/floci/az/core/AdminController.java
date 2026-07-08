package io.floci.az.core;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import java.util.List;

@Path("/_admin")
@Produces(MediaType.APPLICATION_JSON)
public class AdminController {

    private static final Logger LOG = Logger.getLogger(AdminController.class);

    private final Instance<Resettable> resettables;

    @Inject
    public AdminController(Instance<Resettable> resettables) {
        this.resettables = resettables;
    }

    @GET
    @Path("/accounts")
    public List<String> listAccounts() {
        return List.of("devstoreaccount1");
    }

    @DELETE
    @Path("/accounts/{name}")
    public Response deleteAccount(@PathParam("name") String name) {
        return Response.noContent().build();
    }

    /**
     * Wipes all emulator state across every service. Every state-holding
     * component self-registers by implementing {@link Resettable}; each
     * {@code clearAll()} is self-contained (services with sidecar containers
     * stop them themselves). The reset is best-effort: a failing handler is
     * logged and skipped so the remaining services still get cleared — CDI
     * iteration order is non-deterministic, so one failure must not decide
     * which services reset. Intended for test isolation — call at the start
     * of each test suite.
     */
    @POST
    @Path("/reset")
    public Response reset() {
        for (Resettable resettable : resettables) {
            try {
                resettable.clearAll();
            } catch (Exception e) {
                LOG.errorf(e, "Reset failed for %s — continuing with remaining services",
                        resettable.getClass().getSimpleName());
            }
        }
        return Response.noContent().build();
    }
}
