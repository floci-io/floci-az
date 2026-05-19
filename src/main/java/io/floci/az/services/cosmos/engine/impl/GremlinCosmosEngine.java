package io.floci.az.services.cosmos.engine.impl;

import io.floci.az.services.cosmos.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class GremlinCosmosEngine implements CosmosEngineProvider {

    private static final String DEFAULT_IMAGE = "tinkerpop/gremlin-server";
    private static final int DEFAULT_PORT = 8182; // WebSocket port

    @Override
    public CosmosApi supportedApi() {
        return CosmosApi.GREMLIN;
    }

    @Override
    public CosmosEngine engine() {
        return new CosmosEngine() {
            @Override public CosmosApi api()          { return CosmosApi.GREMLIN; }
            @Override public String    defaultImage() { return DEFAULT_IMAGE; }
            @Override public int       defaultPort()  { return DEFAULT_PORT; }

            @Override
            public CosmosCompatibilityMetadata compatibility() {
                return new CosmosCompatibilityMetadata(
                    "medium",
                    List.of(
                        "Azure Cosmos DB RU/s throughput model",
                        "Cosmos DB graph-specific partitioning behavior",
                        "Multi-region replication",
                        "Cosmos DB-specific Gremlin extensions"
                    ),
                    "Apache TinkerPop Gremlin Server supports Gremlin traversal compatibility for the Cosmos DB for Gremlin API. "
                    + "Connect via WebSocket on the configured port."
                );
            }

            @Override
            public CosmosConnectionInfo buildConnectionInfo(String host, int port) {
                String cs = String.format("ws://%s:%d/gremlin", host, port);
                return new CosmosConnectionInfo(host, port, cs,
                    "Connect with any Gremlin client using WebSocket protocol.");
            }
        };
    }
}
