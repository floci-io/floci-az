package io.floci.az.core.arm;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.directory.ResourceGroups;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

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
 *   <li>Under {@link EmulatorConfig#resourceGroupDelete()} a PUT or PATCH of a resource under a group whose
 *       delete is running is refused with {@code 409 ResourceGroupBeingDeleted}, and one admitted before the
 *       delete began is counted until it ends, so the delete can wait for it. Without both, a resource
 *       created while the delete lists and removes the group's resources would outlive the group. Azure
 *       documents the error code; the 409 and the message are an emulator choice.</li>
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
    private final boolean guardDeletes;

    /**
     * The writes this rule admitted and has not yet seen leave, by identity: the gate builds one call per
     * request and hands the same one to {@link #exit}.
     */
    private final Set<ArmCall> admittedWrites = Collections.synchronizedSet(
        Collections.newSetFromMap(new IdentityHashMap<>()));

    @Inject
    public ResourceGroupRule(ResourceGroups groups, EmulatorConfig config) {
        this(groups, config.strictResourceGroups(), config.resourceGroupSemantics(), config.resourceGroupDelete());
    }

    ResourceGroupRule(ResourceGroups groups, boolean strict, boolean autoCreate) {
        this(groups, strict, autoCreate, false);
    }

    ResourceGroupRule(ResourceGroups groups, boolean strict, boolean autoCreate, boolean guardDeletes) {
        this.groups = groups;
        this.strict = strict;
        this.autoCreate = autoCreate;
        this.guardDeletes = guardDeletes;
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
        if (guardDeletes && isChildWrite(call)) {
            if (!groups.beginWrite(call.id().subscriptionId(), call.id().resourceGroup())) {
                return ArmVerdict.respond(ArmErrors.resourceGroupBeingDeleted(call.id().resourceGroup()));
            }
            admittedWrites.add(call);
        }
        return ArmVerdict.proceed();
    }

    @Override
    public void exit(ArmCall call, Response response) {
        try {
            createMissingGroup(call, response);
        } finally {
            if (admittedWrites.remove(call)) {
                groups.endWrite(call.id().subscriptionId(), call.id().resourceGroup());
            }
        }
    }

    private void createMissingGroup(ArmCall call, Response response) {
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

    /** A PUT or PATCH of a resource inside a resource group: the methods that can leave a resource behind. */
    private static boolean isChildWrite(ArmCall call) {
        return ("PUT".equals(call.method()) || "PATCH".equals(call.method())) && isUnderResourceGroup(call);
    }

    /** The location the created resource reports, else the request cloud's default. */
    private static String location(ArmCall call, Response response) {
        String reported = reportedLocation(response.getEntity());
        if (reported != null && !reported.isBlank()) {
            return reported;
        }
        if (call.normalizedLocation() != null) {
            return call.normalizedLocation();
        }
        CloudProfile cloud = call.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD)
            : call.scope().cloud();
        return cloud.effectiveDefaultLocation();
    }

    /** The {@code location} of a response body, whether the handler built it as a map or as a Jackson tree. */
    private static String reportedLocation(Object entity) {
        if (entity instanceof Map<?, ?> body && body.get("location") instanceof String location) {
            return location;
        }
        if (entity instanceof JsonNode body && body.path("location").isTextual()) {
            return body.path("location").asText();
        }
        return null;
    }
}
