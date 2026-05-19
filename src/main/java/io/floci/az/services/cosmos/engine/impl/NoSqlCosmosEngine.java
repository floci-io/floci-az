package io.floci.az.services.cosmos.engine.impl;

import io.floci.az.services.cosmos.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class NoSqlCosmosEngine implements CosmosEngineProvider {

    private static final int DEFAULT_PORT = 0; // embedded — uses the floci-az HTTP port

    @Override
    public CosmosApi supportedApi() {
        return CosmosApi.NOSQL;
    }

    @Override
    public CosmosEngine engine() {
        return new CosmosEngine() {
            @Override public CosmosApi api()          { return CosmosApi.NOSQL; }
            @Override public String    defaultImage() { return "(embedded — no Docker)"; }
            @Override public int       defaultPort()  { return DEFAULT_PORT; }
            @Override public boolean   isEmbedded()   { return true; }

            @Override
            public CosmosCompatibilityMetadata compatibility() {
                return new CosmosCompatibilityMetadata(
                    "high",
                    List.of(
                        "JOIN with nested arrays",
                        "Full-text search, vector search",
                        "Multi-region replication, RU/s autoscale"
                    ),
                    "Embedded in-process Cosmos DB NoSQL engine — no Docker required. "
                    + "Implements the full Cosmos DB SQL dialect (WHERE, ORDER BY, GROUP BY, aggregates, "
                    + "DISTINCT, LIKE, IIF, string/math/array functions) as documented at "
                    + "https://learn.microsoft.com/en-us/azure/cosmos-db/nosql/query/overview"
                );
            }

            @Override
            public CosmosConnectionInfo buildConnectionInfo(String host, int port) {
                // Embedded: data-plane goes through floci-az itself.
                // Java SDK (gateway mode): use https://localhost:4578 (same as the 'cosmos' endpoint).
                // Python/Node SDKs: use http://localhost:4577/{account}-cosmos-nosql/ path prefix.
                String cs = "AccountEndpoint=https://" + host + ":4578/;AccountKey="
                    + "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==;";
                return new CosmosConnectionInfo(host, 4578, cs,
                    "Embedded engine — no Docker needed. Java SDK: https://localhost:4578. "
                    + "Python/Node: http://localhost:4577/{account}-cosmos-nosql/");
            }
        };
    }
}
