package io.floci.az.services.web;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.GlobalName;
import io.floci.az.core.arm.LocationPolicy;
import io.floci.az.core.arm.NameRule;
import io.floci.az.core.cloud.CloudSuffix;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The ARM resource types {@link WebArmService} serves. */
@ApplicationScoped
public class WebArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Web";

    /** The message of a taken site name, with {@code %s} for the name. */
    static final String SITE_NAME_TAKEN = "Website with given name %s already exists.";

    /**
     * 2 to 60 letters, digits and hyphens, not starting or ending with a hyphen: {@code resource-name-rules.md}
     * (Microsoft.Web, sites). The doc also admits Unicode characters that map to Punycode; this rule keeps to ASCII,
     * as azurerm's {@code validate.WebAppName} ({@code ^[0-9a-zA-Z-]{1,60}$}) does. The 2026-07-15 spec declares no
     * pattern on the site {@code name}.
     */
    static final NameRule SITE_NAME = new NameRule(2, 60,
        Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"), "InvalidResourceName",
        "The resource name '%s' is invalid: a site name is 2 to 60 letters, digits and hyphens, and does not start "
            + "or end with a hyphen.");

    /**
     * A site name is a global DNS label under {@code azurewebsites.net}. App Service answers a taken name in its own
     * error shape, not the ARM {@code CloudError} envelope, so the conflict is {@link #siteNameTaken}.
     */
    static final GlobalName SITE_DNS_NAME = new GlobalName(CloudSuffix.WEB, 409, "Conflict", SITE_NAME_TAKEN)
        .answeredBy(WebArmSurface::siteNameTaken);

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.PROVIDER_SERVICE, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "sites", LocationPolicy.REQUIRED)
                .withName(SITE_NAME)
                .withGlobalName(SITE_DNS_NAME),
            ArmResourceType.child(NAMESPACE, "sites/config"))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }

    /** App Service's answer to a PUT of a site name another resource owns: {@code 409} with extended code 54001. */
    static Response siteNameTaken(String siteName) {
        Map<String, Object> errorEntity = new LinkedHashMap<>();
        errorEntity.put("ExtendedCode", "54001");
        errorEntity.put("MessageTemplate", "Website with given name {0} already exists.");
        errorEntity.put("Parameters", List.of(siteName));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Code", "Conflict");
        body.put("Message", String.format(SITE_NAME_TAKEN, siteName));
        body.put("ErrorEntity", errorEntity);
        return Response.status(409).entity(body).build();
    }
}
