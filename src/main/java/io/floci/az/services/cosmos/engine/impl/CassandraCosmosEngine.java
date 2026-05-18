package io.floci.az.services.cosmos.engine.impl;

import io.floci.az.services.cosmos.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class CassandraCosmosEngine implements CosmosEngineProvider {

    private static final String DEFAULT_IMAGE = "scylladb/scylla";
    private static final int DEFAULT_PORT = 9042; // CQL native transport port

    @Override
    public CosmosApi supportedApi() {
        return CosmosApi.CASSANDRA;
    }

    @Override
    public CosmosEngine engine() {
        return new CosmosEngine() {
            @Override public CosmosApi api()          { return CosmosApi.CASSANDRA; }
            @Override public String    defaultImage() { return DEFAULT_IMAGE; }
            @Override public int       defaultPort()  { return DEFAULT_PORT; }

            @Override
            public CosmosCompatibilityMetadata compatibility() {
                return new CosmosCompatibilityMetadata(
                    "high",
                    List.of(
                        "Azure Cosmos DB RU/s throughput model",
                        "Multi-region replication",
                        "Cosmos DB-specific CQL extensions"
                    ),
                    "ScyllaDB provides high CQL protocol compatibility for the Cosmos DB for Apache Cassandra API. "
                    + "Apache Cassandra can be used as an alternative by changing the engine image to 'cassandra'."
                );
            }

            @Override
            public CosmosConnectionInfo buildConnectionInfo(String host, int port) {
                String cs = String.format(
                    "Contact Points=%s:%d;Username=cassandra;Password=cassandra", host, port);
                return new CosmosConnectionInfo(host, port, cs,
                    "Connect with any CQL driver (DataStax, Cassandra driver). "
                    + "Use 'cassandra'/'cassandra' as default credentials.");
            }
        };
    }
}
