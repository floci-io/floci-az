package io.floci.az.services.cosmos.engine;

/**
 * Connection details for a running Cosmos engine.
 *
 * @param host           Reachable host (usually "localhost")
 * @param port           Host port the container is mapped to
 * @param connectionString  Ready-to-use connection string for the API's native SDK
 * @param notes          Human-readable notes (protocol, usage tips)
 */
public record CosmosConnectionInfo(
        String host,
        int port,
        String connectionString,
        String notes) {
}
