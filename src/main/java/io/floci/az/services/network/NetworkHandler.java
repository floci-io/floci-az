package io.floci.az.services.network;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.Resettable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Network is intentionally NOT an {@link io.floci.az.core.arm.ArmProviderService}. It is dispatched by
 * the dedicated early block in {@code ArmHandler.dispatch()} rather than the provider-lane map, because
 * subscription-scoped Network list paths (no {@code /resourceGroups/}) must be intercepted before the
 * resource-group branch — which the map lane, reached only from that branch, cannot do. {@code ArmHandler}
 * calls {@link #handleArm} directly and applies the {@code network().enabled()} guard there.
 */
@ApplicationScoped
public class NetworkHandler implements Resettable {

    private final NetworkService service;

    @Inject
    public NetworkHandler(NetworkService service) {
        this.service = service;
    }

    public Response handleArm(AzureRequest request, String path, String method, String sub) {
        return service.handleArm(request, path, method, sub);
    }

    public List<Map<String, Object>> listResources(String sub, String rg) {
        return service.listResources(sub, rg);
    }

    @Override
    public void clearAll() {
        service.clearAll();
    }
}
