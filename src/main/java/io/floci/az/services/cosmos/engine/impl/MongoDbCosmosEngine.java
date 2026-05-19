package io.floci.az.services.cosmos.engine.impl;

import io.floci.az.services.cosmos.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class MongoDbCosmosEngine implements CosmosEngineProvider {

    private static final String DEFAULT_IMAGE = "mongo:7";
    private static final int DEFAULT_PORT = 27017;

    @Override
    public CosmosApi supportedApi() {
        return CosmosApi.MONGODB;
    }

    @Override
    public CosmosEngine engine() {
        return new CosmosEngine() {
            @Override public CosmosApi api()          { return CosmosApi.MONGODB; }
            @Override public String    defaultImage() { return DEFAULT_IMAGE; }
            @Override public int       defaultPort()  { return DEFAULT_PORT; }

            @Override
            public CosmosCompatibilityMetadata compatibility() {
                return new CosmosCompatibilityMetadata(
                    "high",
                    List.of(
                        "Azure Cosmos DB RU/s throughput model",
                        "Cosmos DB-specific change feed semantics",
                        "Multi-region replication"
                    ),
                    "MongoDB Community Server provides high wire-protocol compatibility for the Cosmos DB for MongoDB API. "
                    + "Use the standard MongoDB connection string with Azure Cosmos DB for MongoDB SDK or driver."
                );
            }

            @Override
            public CosmosConnectionInfo buildConnectionInfo(String host, int port) {
                String cs = String.format("mongodb://%s:%d/", host, port);
                return new CosmosConnectionInfo(host, port, cs,
                    "Connect with any MongoDB driver or azure-cosmos MongoDB SDK using this connection string.");
            }
        };
    }
}
