package io.floci.az.services.iothub;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
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
 * <p>Requests to {@code {hub}.azure-devices.net} ({@code .us} in the US Government cloud), served by {@link IotHubDataPlane}: the device identity registry so
 * far. The device endpoints (MQTT, AMQP, HTTPS) are not served yet.
 */
@ApplicationScoped
public class IotHubHandler implements AzureServiceHandler, Resettable, StoreIndexSource, GlobalNameSource {

    private static final Logger LOG = Logger.getLogger(IotHubHandler.class);

    static final String SERVICE_TYPE = "iothub";

    /**
     * The host suffixes of every cloud whose IoT Hub suffix the catalog settles ({@code azure-devices.net},
     * {@code azure-devices.us}), so a hub answers on the {@code hostName} its own cloud gives it. They are read from
     * the catalog rather than spelled here.
     */
    private static final List<String> HOST_SUFFIXES = CloudCatalog.all().stream()
            .flatMap(cloud -> cloud.suffix(CloudSuffix.IOTHUB).stream())
            .distinct()
            .toList();

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
        ServiceRoutes.Builder routes = ServiceRoutes.builder();
        HOST_SUFFIXES.forEach(suffix -> routes.host("." + suffix));
        return routes.provider(IotHubArmSurface.NAMESPACE).build();
    }

    @Override
    public Response handle(AzureRequest req) {
        String path = req.resourcePath();
        LOG.debugf("IotHubHandler %s %s", req.method(), path);
        // Only a request that came through the ARM request gate reaches the ARM service: one sent to a hub's host is
        // routed here directly, past the gate's subscription, policy and name checks, so it is the registry's to
        // answer, whatever its path. floci-az's own ARM calls (a resource group's cascade) pass the gate too.
        if (req.armCall() != null) {
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
