package io.floci.az.core;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/_admin")
@Produces(MediaType.APPLICATION_JSON)
public class AdminController {

    private final EmulatorStateReset stateReset;

    @Inject
    public AdminController(EmulatorStateReset stateReset) {
        this.stateReset = stateReset;
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
     * Wipes all emulator state across every service (see {@link EmulatorStateReset}). Intended for test
     * isolation: call at the start of each test suite. {@code POST /_floci/state/reset} does the same and
     * answers 200; this path keeps its 204.
     */
    @POST
    @Path("/reset")
    public Response reset() {
        stateReset.resetAll();
        return Response.noContent().build();
    }
}
