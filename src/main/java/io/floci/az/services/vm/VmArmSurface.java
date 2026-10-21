package io.floci.az.services.vm;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import io.floci.az.core.arm.NameRule;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/** The ARM resource types {@link VmHandler} serves. */
@ApplicationScoped
public class VmArmSurface implements ArmSurfaceProvider {

    private static final String NAMESPACE = "Microsoft.Compute";

    /**
     * {@code resource-name-rules.md}, Linux bound: 1 to 64 characters with no whitespace, control characters or
     * {@code ~ ! @ # $ % ^ & * ( ) = + _ [ ] { } \ | ; : ' " , < > / ?}, not ending with a period or hyphen. The
     * Windows bound (15, no periods) depends on the body's OS, so it is not checked. The spec declares no pattern.
     */
    static final NameRule VM_NAME = new NameRule(1, 64,
        Pattern.compile("[^\\s\\p{Cntrl}~!@#$%^&*()=+_\\[\\]{}\\\\|;:'\",<>/?]+(?<![.-])"), "InvalidResourceName",
        "The resource name '%s' is invalid: a virtual machine name is 1 to 64 characters, with no spaces or "
            + "special characters, and does not end with a period or hyphen.");

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "virtualMachines", LocationPolicy.REQUIRED)
                .withName(VM_NAME))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
