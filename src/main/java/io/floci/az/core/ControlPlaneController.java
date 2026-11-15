package io.floci.az.core;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parts of the floci-loft 0022 control-plane contract that floci-az serves beside health, info and the
 * TLS certificate: {@code /_floci/init}, {@code /_floci/state/reset} (and its alias {@code state/nuke}), and a
 * JSON 404 for any other {@code /_floci/*} path, so a wrong control-plane path never reaches a service.
 *
 * <p>Mounted at {@code /} like {@link HealthController}: a resource class at {@code _floci} would win the class
 * match for {@code /_floci/health} and {@code /_floci/tls-cert} and hide them. The catch-all's template has fewer
 * literal characters than every other {@code _floci/...} path, so each of those still matches first. The ui routes
 * live on {@code /} too, so {@code /_floci/ui/<unknown>} also reaches this catch-all.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class ControlPlaneController {

    /** The init hook stages of the shared contract, in order. floci-az runs no init scripts. */
    private static final List<String> STAGES = List.of("boot", "start", "ready", "shutdown");

    /** The contract endpoints this catch-all also covers for OPTIONS, and the methods each answers. */
    private static final Map<String, String> ENDPOINT_METHODS = Map.of(
        "_floci/health", "GET, HEAD, OPTIONS",
        "_floci/info", "GET, HEAD, OPTIONS",
        "_floci/init", "GET, HEAD, OPTIONS",
        "_floci/tls-cert", "GET, HEAD, OPTIONS",
        "_floci/state/reset", "POST, OPTIONS",
        "_floci/state/nuke", "POST, OPTIONS");

    private final EmulatorReadiness readiness;
    private final EmulatorStateReset stateReset;

    @Inject
    public ControlPlaneController(EmulatorReadiness readiness, EmulatorStateReset stateReset) {
        this.readiness = readiness;
        this.stateReset = stateReset;
    }

    @GET
    @Path("_floci/init")
    public Response init() {
        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put("boot", true);
        completed.put("start", readiness.hasStarted());
        completed.put("ready", readiness.isReady());
        completed.put("shutdown", readiness.isShuttingDown());

        Map<String, Object> scripts = new LinkedHashMap<>();
        for (String stage : STAGES) {
            scripts.put(stage, List.of());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("completed", completed);
        body.put("scripts", scripts);
        return Response.ok(body).build();
    }

    @POST
    @Path("_floci/state/reset")
    public Response reset() {
        stateReset.resetAll();
        return Response.ok(Map.of("status", "OK")).build();
    }

    @POST
    @Path("_floci/state/nuke")
    public Response nuke() {
        return reset();
    }

    @GET
    @Path("_floci/{any: .*}")
    public Response notFoundGet(@Context UriInfo uri) {
        return notFound(uri);
    }

    @POST
    @Path("_floci/{any: .*}")
    public Response notFoundPost(@Context UriInfo uri) {
        return notFound(uri);
    }

    @PUT
    @Path("_floci/{any: .*}")
    public Response notFoundPut(@Context UriInfo uri) {
        return notFound(uri);
    }

    @DELETE
    @Path("_floci/{any: .*}")
    public Response notFoundDelete(@Context UriInfo uri) {
        return notFound(uri);
    }

    @PATCH
    @Path("_floci/{any: .*}")
    public Response notFoundPatch(@Context UriInfo uri) {
        return notFound(uri);
    }

    @HEAD
    @Path("_floci/{any: .*}")
    public Response notFoundHead(@Context UriInfo uri) {
        return notFound(uri);
    }

    /**
     * Without this, JAX-RS answers OPTIONS on any {@code /_floci/*} path itself, from the catch-all's methods, so an
     * unknown path looked served. A contract endpoint still answers with the methods it serves.
     */
    @OPTIONS
    @Path("_floci/{any: .*}")
    public Response options(@Context UriInfo uri) {
        String allowed = ENDPOINT_METHODS.get(uri.getPath().replaceFirst("^/", ""));
        if (allowed != null) {
            return Response.ok().header("Allow", allowed).build();
        }
        return notFound(uri);
    }

    private static Response notFound(UriInfo uri) {
        return Response.status(Response.Status.NOT_FOUND)
            .entity(notFoundBody(uri.getRequestUri().getRawPath()))
            .type(MediaType.APPLICATION_JSON)
            .build();
    }

    /** The contract's 404 body for a control-plane path no endpoint serves. */
    static Map<String, Object> notFoundBody(String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "NotFound");
        body.put("path", path);
        return body;
    }
}
