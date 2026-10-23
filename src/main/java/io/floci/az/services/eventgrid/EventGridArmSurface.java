package io.floci.az.services.eventgrid;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmScopeKind;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import io.floci.az.core.arm.NameRule;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/** The ARM resource types {@link EventGridHandler} serves. */
@ApplicationScoped
public class EventGridArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.EventGrid";

    /**
     * {@code resource-name-rules.md}: 3 to 50 alphanumerics and hyphens; azurerm validates the same
     * ({@code ^[-a-zA-Z0-9]{3,50}$}). The spec declares no pattern for {@code topicName}.
     */
    static final NameRule TOPIC_NAME = new NameRule(3, 50, Pattern.compile("[A-Za-z0-9-]+"), "InvalidResourceName",
        "The resource name '%s' is invalid: a topic name is 3 to 50 letters, digits and hyphens.");

    /**
     * {@code resource-name-rules.md}: 3 to 64 alphanumerics and hyphens; azurerm validates the same
     * ({@code ^[-a-zA-Z0-9]{3,64}$}). The spec declares no pattern for {@code eventSubscriptionName}.
     */
    static final NameRule EVENT_SUBSCRIPTION_NAME = new NameRule(3, 64, Pattern.compile("[A-Za-z0-9-]+"),
        "InvalidResourceName",
        "The resource name '%s' is invalid: an event subscription name is 3 to 64 letters, digits and hyphens.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.PROVIDER_SERVICE, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "topics", LocationPolicy.REQUIRED)
                .withName(TOPIC_NAME),
            new ArmResourceType(NAMESPACE, "eventSubscriptions", ArmScopeKind.EXTENSION, LocationPolicy.OPTIONAL)
                .withName(EVENT_SUBSCRIPTION_NAME))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
