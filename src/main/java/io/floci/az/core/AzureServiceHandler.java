package io.floci.az.core;

import jakarta.ws.rs.core.Response;

public interface AzureServiceHandler {
    String getServiceType();                     // "blob", "queue", "table"
    boolean canHandle(AzureRequest request);     // fine-grained match if needed
    Response handle(AzureRequest request);

    /**
     * Returns true if this handler can process requests for the given service type.
     * The default implementation delegates to {@link #getServiceType()}.
     * Override for handlers that serve multiple service types.
     */
    default boolean handlesServiceType(String serviceType) {
        return getServiceType().equals(serviceType);
    }

    /**
     * The host suffixes, account-name suffixes and ARM provider namespaces this handler claims.
     * {@link AzureRoutingFilter} assembles its dispatch tables from every handler at startup.
     * Handlers reachable only through path-shape stages (Entra, Monitor, ...) keep the default.
     */
    default ServiceRoutes routes() {
        return ServiceRoutes.NONE;
    }

    /**
     * Whether this handler currently serves {@code serviceType}. Handlers own their enablement so that
     * adding a service does not mean editing a central switch.
     *
     * <p>The parameter exists for the one multi-type handler: {@code CosmosEngineHandler} serves six
     * engine types whose enabled-state differs at runtime. Single-type handlers ignore it and simply
     * return their config flag.</p>
     *
     * <p>Must stay cheap and side-effect free — {@link AzureServiceRegistry} calls this on every
     * dispatch and deliberately does <em>not</em> cache the result, because Cosmos engine enablement
     * changes while the emulator runs.</p>
     */
    default boolean enabled(String serviceType) {
        return true;
    }
}
