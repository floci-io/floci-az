package io.floci.az.services.cosmos.engine.impl;

import io.floci.az.services.cosmos.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class PostgreSqlCosmosEngine implements CosmosEngineProvider {

    // Pin to a specific tag to avoid pulling "latest" on every fresh install.
    // Override with FLOCI_AZ_SERVICES_COSMOS_ENGINES_POSTGRESQL_IMAGE if needed.
    private static final String DEFAULT_IMAGE = "citusdata/citus:12";
    private static final int DEFAULT_PORT = 5432;

    @Override
    public CosmosApi supportedApi() {
        return CosmosApi.POSTGRESQL;
    }

    @Override
    public CosmosEngine engine() {
        return new CosmosEngine() {
            @Override public CosmosApi api()          { return CosmosApi.POSTGRESQL; }
            @Override public String    defaultImage() { return DEFAULT_IMAGE; }
            @Override public int       defaultPort()  { return DEFAULT_PORT; }

            @Override
            public CosmosCompatibilityMetadata compatibility() {
                return new CosmosCompatibilityMetadata(
                    "medium",
                    List.of(
                        "Azure Cosmos DB for PostgreSQL is based on Citus but the Azure service is being retired",
                        "Azure-specific HA and scaling features",
                        "Multi-region replication"
                    ),
                    "PostgreSQL + Citus provides distributed PostgreSQL compatible with Cosmos DB for PostgreSQL API. "
                    + "Note: Azure Cosmos DB for PostgreSQL is being retired. This engine is kept for legacy compatibility."
                );
            }

            @Override
            public CosmosConnectionInfo buildConnectionInfo(String host, int port) {
                String cs = String.format(
                    "host=%s port=%d dbname=citus user=citus password=mypassword sslmode=disable", host, port);
                return new CosmosConnectionInfo(host, port, cs,
                    "Connect with any PostgreSQL client or JDBC driver. "
                    + "Note: Cosmos DB for PostgreSQL (Citus) is being retired by Azure.");
            }
        };
    }
}
