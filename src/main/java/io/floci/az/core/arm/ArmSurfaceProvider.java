package io.floci.az.core.arm;

import java.util.List;

/**
 * A service's declaration of the ARM namespaces and resource types it serves. Implemented by one
 * {@code *ArmSurface} bean next to each service's handler, and collected by the {@link ArmTypeRegistry}.
 *
 * <p>Separate from {@code AzureServiceHandler} and {@link ArmProviderService} because a surface is data that
 * every lane needs: filter-lane handlers, {@code ArmHandler}'s inline namespaces and provider services alike.
 */
public interface ArmSurfaceProvider {

    /** The surfaces this service serves; usually one, one per namespace. */
    List<ArmSurface> armSurfaces();
}
