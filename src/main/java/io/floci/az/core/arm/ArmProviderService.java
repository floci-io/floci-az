package io.floci.az.core.arm;

import io.floci.az.core.AzureRequest;
import jakarta.ws.rs.core.Response;

import java.util.Set;

/**
 * A management-plane handler for one or more ARM provider namespaces, dispatched by
 * {@code ArmHandler}. Implementers are discovered via CDI ({@code Instance<ArmProviderService>}) and
 * assembled into a namespace → service map at startup, replacing the hand-maintained if-ladder that
 * used to route {@code /providers/Microsoft.X/} paths.
 *
 * <p>This is the "ArmHandler lane": the routing filter forwards the whole ARM path to {@code ArmHandler}
 * (serviceType {@code arm}), which extracts the subscription and delegates here. Contrast the "filter
 * lane", where a full service declares {@code .provider(...)} in its {@code ServiceRoutes} and is
 * dispatched directly by the filter to {@code handle()}. A service belongs in exactly one lane.
 */
public interface ArmProviderService {

    /** The ARM provider namespaces this service owns, e.g. {@code Microsoft.Network}. */
    Set<String> providerNamespaces();

    /**
     * Handles an ARM management-plane request under one of {@link #providerNamespaces()}.
     *
     * @param sub the subscription id, already extracted from the path by {@code ArmHandler} — so
     *            implementers never re-parse it (the inconsistency A6 removed from Event Grid)
     */
    Response handleArm(AzureRequest req, String path, String method, String sub);

    /**
     * Whether this provider is currently served. When {@code false}, {@code ArmHandler} answers the
     * ARM {@code 404} it would for an unknown provider. Defaults to enabled; a service whose config flag
     * gates its ARM plane (e.g. Network) overrides this.
     */
    default boolean armEnabled() {
        return true;
    }
}
