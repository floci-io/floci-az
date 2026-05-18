package io.floci.az.services.cosmos.engine;

/**
 * CDI SPI for Cosmos engine providers.
 * Each engine registers itself as an {@code @ApplicationScoped} CDI bean
 * implementing this interface. The registry discovers all providers automatically.
 */
public interface CosmosEngineProvider {

    /** The Cosmos API this provider handles. */
    CosmosApi supportedApi();

    /** Returns the engine descriptor (metadata, default image, port). */
    CosmosEngine engine();
}
