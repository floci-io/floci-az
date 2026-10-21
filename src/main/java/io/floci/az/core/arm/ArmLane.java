package io.floci.az.core.arm;

/** Which path an ARM request takes to the handler that serves a namespace. */
public enum ArmLane {
    /** The routing filter dispatches to a handler that declares the namespace in its {@code ServiceRoutes}. */
    FILTER,
    /** {@code ArmHandler} serves the namespace itself. */
    ARM_HANDLER,
    /** {@code ArmHandler} delegates to an {@link ArmProviderService}. */
    PROVIDER_SERVICE
}
