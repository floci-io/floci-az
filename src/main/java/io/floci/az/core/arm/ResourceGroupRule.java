package io.floci.az.core.arm;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.directory.ResourceGroups;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * The gate rule for a resource PUT under a resource group: the group must exist, the way ARM requires it.
 *
 * <ul>
 *   <li>By default nothing changes: a resource is created under a group that does not exist, and no group
 *       appears, as floci-az has always done.</li>
 *   <li>Under {@link EmulatorConfig#resourceGroupSemantics()} the group is created on the way out, once the
 *       handler has answered with a 2xx, so a client that never created the group still finds it, and a
 *       refused PUT leaves nothing behind. Its location is the one the created resource reports, or the cloud's
 *       default location when the response names none.</li>
 * </ul>
 *
 * <p>Only a PUT of a provider resource counts: the group's own requests, listings and actions pass untouched.
 */
@ApplicationScoped
public class ResourceGroupRule implements ArmRequestRule {

    /** Position in the gate: after the subscription rule, leaving room for the tenant and provider rules. */
    public static final int ORDER = 4;

    private final ResourceGroups groups;
    private final boolean autoCreate;

    @Inject
    public ResourceGroupRule(ResourceGroups groups, EmulatorConfig config) {
        this(groups, config.resourceGroupSemantics());
    }

    ResourceGroupRule(ResourceGroups groups, boolean autoCreate) {
        this.groups = groups;
        this.autoCreate = autoCreate;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void exit(ArmCall call, Response response) {
        if (!autoCreate || response == null || response.getStatus() / 100 != 2 || !isChildPut(call)) {
            return;
        }
        String subscriptionId = call.id().subscriptionId();
        String resourceGroup = call.id().resourceGroup();
        if (!groups.exists(subscriptionId, resourceGroup)) {
            groups.createIfAbsent(subscriptionId, resourceGroup, location(call, response));
        }
    }

    /** A PUT of a resource inside a resource group, as opposed to the group itself. */
    private static boolean isChildPut(ArmCall call) {
        return "PUT".equals(call.method()) && call.id() != null && call.id().subscriptionId() != null
            && call.id().resourceGroup() != null && call.id().providerNamespace() != null;
    }

    /** The location the created resource reports, else the request cloud's default. */
    private static String location(ArmCall call, Response response) {
        if (response.getEntity() instanceof Map<?, ?> body && body.get("location") instanceof String location
                && !location.isBlank()) {
            return location;
        }
        if (call.normalizedLocation() != null) {
            return call.normalizedLocation();
        }
        CloudProfile cloud = call.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD)
            : call.scope().cloud();
        return cloud.effectiveDefaultLocation();
    }
}
