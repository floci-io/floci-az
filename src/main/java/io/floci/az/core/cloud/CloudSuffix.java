package io.floci.az.core.cloud;

/**
 * The host-addressed Azure services whose DNS suffix differs per cloud. Each constant is a key of
 * every cloud's {@code suffixes} map in {@code azure/clouds.json}; a cloud with no local source for a
 * suffix carries the key with an {@code UNSETTLED} source, so a constant is never silently missing.
 *
 * <p>{@link #hostRouted()} marks the suffixes {@code AzureRoutingFilter} dispatches on by {@code Host}
 * header today (the {@code ServiceRoutes.host(...)} declarations). The others are only used to build
 * hostnames in responses.
 */
public enum CloudSuffix {
    BLOB(true),
    DFS(true),
    QUEUE(true),
    TABLE(true),
    KEYVAULT(true),
    MHSM(true),
    ACR(true),
    SERVICEBUS(true),
    SIGNALR(true),
    APPCONFIG(false),
    SQL(false),
    POSTGRES(false),
    MYSQL(false),
    MARIADB(false),
    REDIS(false),
    EVENTGRID(false),
    CONTAINERAPPS(true),
    COMMUNICATION(true),
    AKS(false),
    ACI(false),
    APIM(false),
    WEB(false);

    private final boolean hostRouted;

    CloudSuffix(boolean hostRouted) {
        this.hostRouted = hostRouted;
    }

    /** Whether floci-az routes requests to this service by the suffix of their {@code Host} header. */
    public boolean hostRouted() {
        return hostRouted;
    }
}
