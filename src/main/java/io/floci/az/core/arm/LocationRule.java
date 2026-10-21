package io.floci.az.core.arm;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import io.floci.az.core.directory.ResourceGroup;
import io.floci.az.core.directory.ResourceGroups;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * The gate rule for the {@code location} of a resource PUT or PATCH, read from the body of a declared type
 * according to its {@link LocationPolicy}, in the cloud the subscription belongs to.
 *
 * <ul>
 *   <li>By default nothing changes: the body reaches its handler as sent, and the location is echoed as
 *       floci-az always has.</li>
 *   <li>Under {@link EmulatorConfig#locationNormalize()} the body's {@code location} is rewritten to its
 *       canonical form ({@code "East US"} becomes {@code eastus}) before the handler runs, so it is stored,
 *       echoed and built into names that way. A {@link LocationPolicy#GLOBAL} type gets {@code global}, and an
 *       {@link LocationPolicy#INHERITS_RG} PUT that names no location gets its resource group's.
 *       {@link ArmCall#normalizedLocation()} carries the result.</li>
 * </ul>
 *
 * <p>The rule reads the body the gate buffers ({@link ArmCall#body()}) and rewrites it in place; a body that is
 * not a JSON object passes untouched, for its handler to refuse.
 */
@ApplicationScoped
public class LocationRule implements ArmRequestRule {

    /** Position in the gate: after resource groups, so an inherited location reads a group that exists. */
    public static final int ORDER = 5;

    static final String GLOBAL = "global";

    private final ArmTypeRegistry types;
    private final ResourceGroups groups;
    private final boolean normalize;

    @Inject
    public LocationRule(ArmTypeRegistry types, ResourceGroups groups, EmulatorConfig config) {
        this(types, groups, config.locationNormalize());
    }

    LocationRule(ArmTypeRegistry types, ResourceGroups groups, boolean normalize) {
        this.types = types;
        this.groups = groups;
        this.normalize = normalize;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public boolean readsBody() {
        return normalize;
    }

    @Override
    public ArmVerdict enter(ArmCall call) {
        if (!normalize || call.type() == null || call.body() == null) {
            return ArmVerdict.proceed();
        }
        ArmResourceType type = types.type(call.type()).orElse(null);
        Map<String, Object> body = call.body().json();
        if (type == null || body == null) {
            return ArmVerdict.proceed();
        }
        CloudProfile cloud = cloud(call);
        String sent = body.get("location") instanceof String location ? location : null;
        String canonical = canonical(call, type, cloud, sent);
        if (canonical == null) {
            return ArmVerdict.proceed();
        }
        if (!canonical.equals(sent)) {
            body.put("location", canonical);
            call.body().replace(body);
        }
        return ArmVerdict.amend(new ArmCall(call.scope(), call.id(), call.type(), call.method(), canonical,
            call.body(), call.request()));
    }

    /** The location the resource ends up with, or null when the body names none and none is inherited. */
    private String canonical(ArmCall call, ArmResourceType type, CloudProfile cloud, String sent) {
        if (type.location() == LocationPolicy.GLOBAL) {
            return sent != null || "PUT".equals(call.method()) ? GLOBAL : null;
        }
        if (sent != null) {
            return CloudCatalog.normalizeLocation(cloud, sent);
        }
        if (type.location() == LocationPolicy.INHERITS_RG && "PUT".equals(call.method())
                && call.id().resourceGroup() != null) {
            return groups.get(call.id().subscriptionId(), call.id().resourceGroup())
                .map(ResourceGroup::location)
                .map(location -> CloudCatalog.normalizeLocation(cloud, location))
                .orElse(null);
        }
        return null;
    }

    private static CloudProfile cloud(ArmCall call) {
        return call.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : call.scope().cloud();
    }
}
