package io.floci.az.services.iothub;

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

/** The ARM resource types {@link IotHubHandler} serves. */
@ApplicationScoped
public class IotHubArmSurface implements ArmSurfaceProvider {

    static final String NAMESPACE = "Microsoft.Devices";

    /**
     * 3 to 50 letters, digits and hyphens, not all digits, not starting or ending with a hyphen: the {@code name}
     * pattern of {@code IotHubDescription} (2023-06-30), {@code ^(?![0-9]+$)(?!-)[a-zA-Z0-9-]{2,49}[a-zA-Z0-9]$}.
     */
    static final NameRule IOT_HUB_NAME = new NameRule(3, 50,
        Pattern.compile("(?![0-9]+$)(?!-)[a-zA-Z0-9-]{2,49}[a-zA-Z0-9]"),
        "InvalidResourceName",
        "The resource name '%s' is invalid: an IoT hub name is 3 to 50 letters, digits and hyphens, is not all "
            + "digits, and does not start or end with a hyphen.");

    /**
     * 1 to 50 letters, digits, hyphens, periods and underscores, or the built-in {@code $Default}, as Learn's resource
     * naming rules give it for {@code IotHubs / eventHubEndpoints / ConsumerGroups} (scope: the endpoint, 1-50,
     * "Alphanumerics, hyphens, periods, and underscores"). Checked in every mode, so a name the handler stores always
     * fits it; {@code $Default} matches ignoring case, as the group names do.
     */
    static final NameRule CONSUMER_GROUP_NAME = new NameRule(1, 50,
        Pattern.compile("(?i:\\$Default)|[0-9a-zA-Z._-]{1,50}"),
        "InvalidResourceName",
        "The resource name '%s' is invalid: a consumer group name is 1 to 50 letters, digits, hyphens, periods and "
            + "underscores.").inEveryMode();

    /**
     * A hub name is a global DNS label under {@code azure-devices.net}. No public source gives Azure's create-conflict
     * answer, so ARM's generic {@code Conflict} stands in, with the message Azure gives for the name in
     * {@code checkNameAvailability}.
     */
    static final GlobalName IOT_HUB_DNS_NAME = new GlobalName(CloudSuffix.IOTHUB, 409, "Conflict",
        "IotHub name '%s' is not available");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "IotHubs", LocationPolicy.REQUIRED)
                .withName(IOT_HUB_NAME)
                .withGlobalName(IOT_HUB_DNS_NAME),
            ArmResourceType.child(NAMESPACE, "IotHubs/eventHubEndpoints/ConsumerGroups")
                .withName(CONSUMER_GROUP_NAME))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
