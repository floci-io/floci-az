package io.floci.az.services.cosmos.engine.impl;

import io.floci.az.services.cosmos.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class NoSqlVNextCosmosEngine implements CosmosEngineProvider {

    private static final String DEFAULT_IMAGE =
        "mcr.microsoft.com/cosmosdb/linux/azure-cosmos-emulator:vnext-preview";
    private static final int DEFAULT_PORT = 8081; // vNext HTTPS port

    @Override
    public CosmosApi supportedApi() {
        return CosmosApi.NOSQL;
    }

    @Override
    public CosmosEngine engine() {
        return new CosmosEngine() {
            @Override public CosmosApi api()          { return CosmosApi.NOSQL; }
            @Override public String    defaultImage() { return DEFAULT_IMAGE; }
            @Override public int       defaultPort()  { return DEFAULT_PORT; }

            @Override
            public CosmosCompatibilityMetadata compatibility() {
                return new CosmosCompatibilityMetadata(
                    "high",
                    List.of(
                        "Multi-region replication",
                        "RU/s autoscale",
                        "Full Azure Cosmos DB behavioral parity (by design)"
                    ),
                    "The official Azure Cosmos DB Emulator Linux vNext provides the highest NoSQL API compatibility. "
                    + "Only supports the NoSQL/SQL API. Use the in-memory floci-az engine (cosmos service) for "
                    + "lightweight testing without container overhead."
                );
            }

            @Override
            public CosmosConnectionInfo buildConnectionInfo(String host, int port) {
                String endpoint = String.format("https://%s:%d/", host, port);
                String cs = String.format(
                    "AccountEndpoint=%s;AccountKey=C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==;",
                    endpoint);
                return new CosmosConnectionInfo(host, port, cs,
                    "Use the standard Azure Cosmos DB SDK with the official emulator key. "
                    + "TLS certificate validation may need to be disabled for local development.");
            }
        };
    }
}
