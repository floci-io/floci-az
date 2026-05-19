package io.floci.az.services.cosmos.engine.impl;

import io.floci.az.services.cosmos.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * In-memory Cosmos DB for Table (Azure Table Storage API) engine.
 *
 * <p>Unlike the other Cosmos engines, this one is <em>embedded</em>: it does not launch a Docker
 * container. All Table API data (tables, entities, OData queries) is handled in-process by
 * {@link io.floci.az.services.cosmos.table.CosmosTableApiHandler} backed by a
 * {@link java.util.concurrent.ConcurrentHashMap}.
 *
 * <p>The connection string returned by {@code /connect} points back to floci-az itself so that
 * the Azure Data Tables SDK can be used without any additional setup.
 */
@ApplicationScoped
public class TableCosmosEngine implements CosmosEngineProvider {

    // floci-az default HTTP port — kept as a constant for connection-string generation.
    private static final int DEFAULT_EMULATOR_PORT = 4577;

    @Override
    public CosmosApi supportedApi() {
        return CosmosApi.TABLE;
    }

    @Override
    public CosmosEngine engine() {
        return new CosmosEngine() {
            @Override public CosmosApi api()          { return CosmosApi.TABLE; }
            @Override public boolean   isEmbedded()   { return true; }

            /** No Docker image — this engine runs in-process. */
            @Override public String    defaultImage() { return "(embedded — no Docker)"; }

            /** Not used for embedded engines; floci-az's own HTTP port is the entry point. */
            @Override public int       defaultPort()  { return DEFAULT_EMULATOR_PORT; }

            @Override
            public String displayName() { return "TABLE (in-memory, no Docker)"; }

            @Override
            public CosmosCompatibilityMetadata compatibility() {
                return new CosmosCompatibilityMetadata(
                    "high",
                    List.of(
                        "Azure Cosmos DB RU/s throughput model",
                        "Cosmos DB-specific TTL semantics",
                        "Large binary property values (Edm.Binary) are stored but not validated"
                    ),
                    "Lightweight in-memory Azure Table Storage emulator. No Docker required. "
                    + "Supports CRUD operations and OData $filter queries "
                    + "(eq, ne, gt, ge, lt, le, and, or, not)."
                );
            }

            @Override
            public CosmosConnectionInfo buildConnectionInfo(String host, int port) {
                // Point the SDK at floci-az itself using the cosmos-table routing prefix.
                String tableEndpoint = "http://" + host + ":" + port
                        + "/devstoreaccount1-cosmos-table";
                String cs = "DefaultEndpointsProtocol=http;"
                        + "AccountName=devstoreaccount1;"
                        + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==;"
                        + "TableEndpoint=" + tableEndpoint + "/;";
                return new CosmosConnectionInfo(host, port, cs,
                    "Use any Azure Table Storage SDK with this connection string. "
                    + "Data is stored in memory — restarting floci-az clears all tables.");
            }
        };
    }
}
