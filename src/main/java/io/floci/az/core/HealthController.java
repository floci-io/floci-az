package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.tls.TlsConfigSource;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class HealthController {

    private final EmulatorConfig config;
    private final Instance<ServiceHealth> serviceHealth;
    // Resolved per instance, never in a static initializer: native image runs those at build
    // time, where FLOCI_AZ_VERSION is unset, and would bake "dev" into every release binary.
    private final String version;

    @Inject
    public HealthController(EmulatorConfig config, Instance<ServiceHealth> serviceHealth) {
        this.config = config;
        this.serviceHealth = serviceHealth;
        this.version = resolveVersion();
    }

    static String resolveVersion() {
        String env = System.getenv("FLOCI_AZ_VERSION");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return "dev";
    }

    @GET
    @Path("{path:(health|_floci/health)}")
    public Response health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("edition", "floci-az-always-free");
        return status(body);
    }

    @GET
    @Path("ready")
    public Response ready() {
        return status(new LinkedHashMap<>());
    }

    // A service that cannot serve (e.g. a sidecar that failed to start) makes floci-az DOWN, so
    // orchestrators waiting on health don't hand clients an endpoint that refuses them.
    private Response status(Map<String, Object> body) {
        Map<String, String> problems = new TreeMap<>();
        for (ServiceHealth health : serviceHealth) {
            problems.putAll(health.problems());
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", problems.isEmpty() ? "UP" : "DOWN");
        response.putAll(body);
        if (problems.isEmpty()) {
            return Response.ok(response).build();
        }
        response.put("problems", problems);
        return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(response).build();
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
