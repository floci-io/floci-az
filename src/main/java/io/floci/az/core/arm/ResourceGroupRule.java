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
 * The gate rule for a resource under a resource group: the group must exist, the way ARM requires it.
 *
 * <ul>
 *   <li>By default nothing changes: a resource is created under a group that does not exist, and no group
 *       appears, as floci-az has always done.</li>
 *   <li>Under {@link EmulatorConfig#strictResourceGroups()} any request for a resource under a missing group
 *       (a PUT, and also a read, an action or a delete, as ARM answers them) is refused before its handler
 *       runs with {@code 404 ResourceGroupNotFound}, ARM's answer for a missing group.</li>
 *   <li>Under {@link EmulatorConfig#resourceGroupSemantics()} the group is created on the way out, once the
 *       handler has answered with a 2xx, so a client that never created the group still finds it, and a
 *       refused PUT leaves nothing behind. Its location is the one the created resource reports, or the cloud's
 *       default location when the response names none.</li>
 * </ul>
 *
 * <p>Only provider paths under a group count: the group's own requests and listings, and every path above the
 * group, pass untouched.
 */
@ApplicationScoped
public class ResourceGroupRule implements ArmRequestRule {

    /** Position in the gate: after the subscription rule, leaving room for the tenant and provider rules. */
    public static final int ORDER = 4;

    private final ResourceGroups groups;
    private final boolean strict;
    private final boolean autoCreate;

    @Inject
    public ResourceGroupRule(ResourceGroups groups, EmulatorConfig config) {
        this(groups, config.strictResourceGroups(), config.resourceGroupSemantics());
    }

    ResourceGroupRule(ResourceGroups groups, boolean strict, boolean autoCreate) {
        this.groups = groups;
        this.strict = strict;
        this.autoCreate = autoCreate;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ArmVerdict enter(ArmCall call) {
        if (strict && isUnderResourceGroup(call)
                && !groups.exists(call.id().subscriptionId(), call.id().resourceGroup())) {
            return ArmVerdict.respond(ArmErrors.resourceGroupNotFound(call.id().resourceGroup()));
        }
        return ArmVerdict.proceed();
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

    /** A request for a provider resource or collection inside a resource group, as opposed to the group itself. */
    private static boolean isUnderResourceGroup(ArmCall call) {
        return call.id() != null && call.id().subscriptionId() != null && call.id().resourceGroup() != null
            && call.id().providerNamespace() != null;
    }

    /** A PUT of a resource inside a resource group. */
    private static boolean isChildPut(ArmCall call) {
        return "PUT".equals(call.method()) && isUnderResourceGroup(call);
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
