package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.ui.ConsoleEnvironment;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code GET /_floci/info}: the emulator manifest of the Floci Console Contract v2.
 *
 * <p>A console reads it (the contract gives it the URL as {@code FLOCI_INFO_URL}) to learn which emulator it
 * fronts, which services are enabled and the defaults it would otherwise have to guess. Keys are snake_case and
 * only add to the fields floci-aws already serves here, so every emulator can grow into the same shape. A
 * console must ignore fields it does not know.
 */
@Path("_floci/info")
@Produces(MediaType.APPLICATION_JSON)
public class EmulatorInfoController {

    /** The manifest schema this controller serves. */
    public static final int MANIFEST_VERSION = 1;

    /** The console contract the manifest belongs to. */
    public static final int CONSOLE_CONTRACT = 2;

    private final EmulatorConfig config;
    private final AzureServiceRegistry registry;
    // Read per instance, never in a static initializer: see HealthController.
    private final String version;

    @Inject
    public EmulatorInfoController(EmulatorConfig config, AzureServiceRegistry registry) {
        this.config = config;
        this.registry = registry;
        this.version = HealthController.resolveVersion();
    }

    @GET
    public Response info() {
        return Response.ok(manifest()).build();
    }

    Map<String, Object> manifest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("manifest_version", MANIFEST_VERSION);
        body.put("console_contract", CONSOLE_CONTRACT);
        body.put("cloud", ConsoleEnvironment.CLOUD);
        body.put("emulator", "floci-az");
        body.put("version", version);
        body.put("edition", "floci-az-always-free");
        body.put("endpoint", config.effectiveBaseUrl());
        body.put("health_url", "/_floci/health");
        body.put("tls", Map.of("enabled", config.tls().enabled()));
        body.put("services", services());
        body.put("defaults", defaults());

        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("enabled", config.ui().enabled());
        ui.put("port", config.ui().port());
        body.put("ui", ui);
        return body;
    }

    private Map<String, String> services() {
        Map<String, String> services = new TreeMap<>();
        for (AzureServiceHandler handler : registry.handlers()) {
            String type = handler.getServiceType();
            services.put(type, registry.isEnabled(type) ? "enabled" : "disabled");
        }
        return services;
    }

    private Map<String, Object> defaults() {
        CloudProfile cloud = CloudCatalog.forNameOrPublic(config.cloud().defaultCloud());
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("cloud", cloud.name());
        defaults.put("subscription_id", config.services().arm().defaultSubscriptionId());
        defaults.put("tenant_id", config.services().entra().defaultTenantId());
        defaults.put("location", cloud.effectiveDefaultLocation());
        defaults.put("storage_account", ConsoleEnvironment.DEFAULT_STORAGE_ACCOUNT);
        return defaults;
    }
}
