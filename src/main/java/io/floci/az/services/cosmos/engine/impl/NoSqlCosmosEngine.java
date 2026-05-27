package io.floci.az.services.cosmos.engine.impl;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.services.cosmos.engine.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class NoSqlCosmosEngine implements CosmosEngineProvider {

    private static final int DEFAULT_PORT = 0; // embedded — uses the floci-az HTTP port

    private final EmulatorConfig config;

    @Inject
    public NoSqlCosmosEngine(EmulatorConfig config) {
        this.config = config;
    }

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
                // Embedded: data-plane goes through floci-az itself on the single public port.
                // Java SDK (gateway mode) requires HTTPS — enable via FLOCI_AZ_TLS_ENABLED=true.
                // Python/Node SDKs accept plain HTTP via the path-prefixed URL.
                int publicPort = config.port();
                String httpsBase = "https://" + host + ":" + publicPort;
                String httpBase  = "http://"  + host + ":" + publicPort;
                String cs = "AccountEndpoint=" + httpsBase + "/;AccountKey="
                    + "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==;";
                return new CosmosConnectionInfo(host, publicPort, cs,
                    "Embedded engine — no Docker needed. Java SDK: " + httpsBase
                    + " (requires FLOCI_AZ_TLS_ENABLED=true). "
                    + "Python/Node: " + httpBase + "/{account}-cosmos-nosql/");
            }
        };
    }
}
