package io.floci.az.services.cosmos.engine;

/**
 * Describes a Cosmos DB API-specific local engine.
 * Each implementation maps one CosmosApi to a Docker-backed engine.
 */
public interface CosmosEngine {

    /** Which Cosmos API this engine supports. */
    CosmosApi api();

    /** Docker image to use (may be overridden by config). */
    String defaultImage();

    /** Primary port exposed by the container (native protocol port). */
    int defaultPort();

    /** Human-readable label for logging and status. */
    default String displayName() {
        return api().name() + " (" + defaultImage() + ")";
    }

    /** Compatibility notes for documentation and runtime metadata. */
    CosmosCompatibilityMetadata compatibility();

    /**
     * Builds the connection info given the host and mapped port.
     * The host is always "localhost" in single-node dev mode.
     */
    CosmosConnectionInfo buildConnectionInfo(String host, int port);
}
