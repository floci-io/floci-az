package io.floci.az.core;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/")
public class HealthController {

    @GET
    @Path("{path:(health|_floci/health)}")
    public Response health() {
        String version = System.getenv("FLOCI_AZ_VERSION");
        if (version == null) version = "dev";
        
        return Response.ok(Map.of(
            "status", "UP",
            "version", version,
            "edition", "floci-az-always-free"
        )).build();
    }

    @GET
    @Path("ready")
    public Response ready() {
        return Response.ok(Map.of("status", "UP")).build();
    }
}
