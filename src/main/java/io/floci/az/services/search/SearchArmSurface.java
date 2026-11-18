package io.floci.az.services.search;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.GlobalName;
import io.floci.az.core.arm.LocationPolicy;
import io.floci.az.core.arm.NameRule;
import io.floci.az.core.cloud.CloudSuffix;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/** The ARM resource types {@link SearchHandler} serves. */
@ApplicationScoped
public class SearchArmSurface implements ArmSurfaceProvider {

    static final String NAMESPACE = "Microsoft.Search";

    /**
     * 2 to 60 lowercase letters, digits and single hyphens, the first two characters and the last one not a hyphen:
     * the {@code searchServiceName} path parameter (2025-05-01), {@code ^(?=.{2,60}$)[a-z0-9][a-z0-9]+(-[a-z0-9]+)*$}.
     */
    static final NameRule SEARCH_SERVICE_NAME = new NameRule(2, 60, Pattern.compile("[a-z0-9][a-z0-9]+(-[a-z0-9]+)*"),
        "InvalidResourceName",
        "The resource name '%s' is invalid: a search service name is 2 to 60 lowercase letters, digits and hyphens, "
            + "starts with two letters or digits, does not end with a hyphen, and has no consecutive hyphens.");

    /**
     * A search service name is a global DNS label under {@code search.windows.net}. No public source gives Azure's
     * create-conflict answer, so ARM's generic {@code Conflict} stands in, as it does for Communication Services.
     */
    static final GlobalName SEARCH_DNS_NAME = new GlobalName(CloudSuffix.SEARCH, 409, "Conflict",
        "The search service name '%s' is already in use.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "searchServices", LocationPolicy.REQUIRED)
                .withName(SEARCH_SERVICE_NAME)
                .withGlobalName(SEARCH_DNS_NAME))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
