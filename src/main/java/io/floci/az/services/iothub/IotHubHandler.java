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
 * <p>{@code Microsoft.Devices/IotHubs} and their shared access policies, served by {@link IotHubArmService}. The
 * service-side registry and the device data plane are not served yet, so the handler claims no host suffix.
 */
@ApplicationScoped
public class IotHubHandler implements AzureServiceHandler, Resettable, StoreIndexSource, GlobalNameSource {

    private static final Logger LOG = Logger.getLogger(IotHubHandler.class);

    static final String SERVICE_TYPE = "iothub";

    private final EmulatorConfig config;
    private final IotHubArmService arm;

    @Inject
    public IotHubHandler(EmulatorConfig config, IotHubArmService arm) {
        this.config = config;
        this.arm = arm;
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
                .provider(IotHubArmSurface.NAMESPACE)
                .build();
    }

    @Override
    public Response handle(AzureRequest req) {
        LOG.debugf("IotHubHandler %s %s", req.method(), req.resourcePath());
        return arm.handle(req);
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

    /** Wipes the hubs: {@code POST /_admin/reset}. */
    @Override
    public void clear() {
        arm.clear();
    }
}
