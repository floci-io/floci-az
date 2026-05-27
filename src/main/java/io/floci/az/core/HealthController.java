package io.floci.az.core;

import io.floci.az.core.tls.TlsConfigSource;
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

    @GET
    @Path("_floci/tls-cert")
    public Response tlsCert() {
        String pem = TlsConfigSource.currentCertPem;
        if (pem == null || pem.isBlank()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"TLS not enabled or certificate not available\"}")
                    .type("application/json")
                    .build();
        }
        return Response.ok(pem).type("application/x-pem-file").build();
    }
}
