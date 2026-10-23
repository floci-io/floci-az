package io.floci.az.services.email;

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

/** The ARM resource types {@link EmailHandler} serves. */
@ApplicationScoped
public class EmailArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Communication";

    /** 1 to 63 word characters and hyphens, the {@code communicationServiceName} parameter (2023-03-31). */
    static final NameRule COMMUNICATION_SERVICE_NAME = new NameRule(1, 63, Pattern.compile("[-\\w]+"),
        "InvalidResourceName",
        "The resource name '%s' is invalid: a communication service name is 1 to 63 letters, digits, underscores "
            + "and hyphens.");

    /** 1 to 63 letters, digits and hyphens, the {@code emailServiceName} parameter (2023-03-31). */
    static final NameRule EMAIL_SERVICE_NAME = new NameRule(1, 63, Pattern.compile("[a-zA-Z0-9-]+"),
        "InvalidResourceName",
        "The resource name '%s' is invalid: an email service name is 1 to 63 letters, digits and hyphens.");

    /** 1 to 253 characters, the {@code domainName} parameter (2023-03-31), which declares no pattern. */
    static final NameRule DOMAIN_NAME = new NameRule(1, 253, Pattern.compile("[^/]+"), "InvalidResourceName",
        "The resource name '%s' is invalid: a domain name is 1 to 253 characters.");

    /**
     * A communication service name is a global DNS label under {@code communication.azure.com}. No public source
     * gives Azure's create-conflict answer, so ARM's generic {@code Conflict} stands in, as it always has here.
     */
    static final GlobalName COMMUNICATION_DNS_NAME = new GlobalName(CloudSuffix.COMMUNICATION, 409, "Conflict",
        "The communication service name '%s' is already in use.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "communicationServices", LocationPolicy.GLOBAL)
                .withName(COMMUNICATION_SERVICE_NAME)
                .withGlobalName(COMMUNICATION_DNS_NAME),
            ArmResourceType.resourceGroup(NAMESPACE, "emailServices", LocationPolicy.GLOBAL)
                .withName(EMAIL_SERVICE_NAME),
            ArmResourceType.child(NAMESPACE, "emailServices/domains")
                .withName(DOMAIN_NAME))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
