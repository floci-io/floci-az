package io.floci.az.services.iothub;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.GlobalNameSource;
import io.floci.az.core.arm.StoreIndexSource;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudSuffix;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * HTTP handler for Azure IoT Hub.
 *
 * <h2>ARM management plane</h2>
 * <p>{@code Microsoft.Devices/IotHubs}, their shared access policies and their consumer groups, served by
 * {@link IotHubArmService}.
 *
 * <h2>Service API</h2>
 * <p>Requests to {@code {hub}.azure-devices.net}, served by {@link IotHubDataPlane}: the device identity registry so
 * far. The device endpoints (MQTT, AMQP, HTTPS) are not served yet.
 */
@ApplicationScoped
public class IotHubHandler implements AzureServiceHandler, Resettable, StoreIndexSource, GlobalNameSource {

    private static final Logger LOG = Logger.getLogger(IotHubHandler.class);

    static final String SERVICE_TYPE = "iothub";

    /** The routing table is keyed by the public cloud's suffix, read from the catalog rather than spelled here. */
    private static final String PUBLIC_HOST_SUFFIX = CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD)
            .suffix(CloudSuffix.IOTHUB).orElseThrow();

    private final EmulatorConfig config;
    private final IotHubArmService arm;
    private final IotHubDataPlane dataPlane;

    @Inject
    public IotHubHandler(EmulatorConfig config, IotHubArmService arm, IotHubDataPlane dataPlane) {
        this.config = config;
        this.arm = arm;
        this.dataPlane = dataPlane;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().iothub().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .host("." + PUBLIC_HOST_SUFFIX)
                .provider(IotHubArmSurface.NAMESPACE)
                .build();
    }

    @Override
    public Response handle(AzureRequest req) {
        String path = req.resourcePath();
        LOG.debugf("IotHubHandler %s %s", req.method(), path);
        // A tenant-rooted path (providers/Microsoft.Devices/operations) has no slash before its provider marker.
        if (ArmPaths.hasProvider(path.startsWith("/") ? path : "/" + path, IotHubArmSurface.NAMESPACE)) {
            return arm.handle(req);
        }
        return dataPlane.handle(req);
    }

    // ── Resource index ───────────────────────────────────────────────────────

    @Override
    public boolean indexEnabled() {
        return config.services().iothub().enabled();
    }

    @Override
    public List<Map<String, Object>> indexEntries(ArmScope scope) {
        return arm.indexEntries(scope);
    }

    // ── GlobalNameSource ─────────────────────────────────────────────────────

    @Override
    public List<Claim> globalNames() {
        return arm.globalNames();
    }

    @Override
    public boolean holds(CloudSuffix suffix, String name, ArmResourceId owner) {
        return suffix == CloudSuffix.IOTHUB && arm.holds(name, owner);
    }

    /** Wipes the hubs and their devices: {@code POST /_admin/reset}. */
    @Override
    public void clear() {
        arm.clear();
    }
}
