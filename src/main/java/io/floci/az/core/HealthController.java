package io.floci.az.core;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/")
public class HealthController {

    @GET
    @Path("health")
    public Response health() {
        return Response.ok(Map.of("status", "UP")).build();
    }

    @GET
    @Path("ready")
    public Response ready() {
        return Response.ok(Map.of("status", "UP")).build();
    }
}
