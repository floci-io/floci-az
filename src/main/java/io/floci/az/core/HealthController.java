package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.tls.TlsConfigSource;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;

@Path("/")
public class HealthController {

    @Inject EmulatorConfig config;

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
            boolean tlsEnabled = config.tls().enabled();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tlsEnabled", tlsEnabled);
            if (!tlsEnabled) {
                body.put("error", "TLS is not enabled");
                body.put("message",
                    "floci-az is serving plain HTTP only. Set FLOCI_AZ_TLS_ENABLED=true and "
                    + "restart to serve HTTPS on the same port. The Terraform/OpenTofu azurerm "
                    + "provider requires this, because it discovers the cloud over HTTPS "
                    + "(GET https://<host>/metadata/endpoints). See "
                    + "https://floci.io/floci-az/terraform/");
            } else {
                body.put("error", "TLS certificate not available yet");
                body.put("message",
                    "TLS is enabled but no certificate is available. If floci-az has only just "
                    + "started, the certificate is still being generated — retry shortly. "
                    + "Otherwise check the startup logs for certificate generation/read errors.");
            }
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(body)
                    .type("application/json")
                    .build();
        }
        return Response.ok(pem).type("application/x-pem-file").build();
    }
}
