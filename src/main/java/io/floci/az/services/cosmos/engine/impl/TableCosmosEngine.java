package io.floci.az.services.cosmos.engine.impl;

import io.floci.az.services.cosmos.engine.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class TableCosmosEngine implements CosmosEngineProvider {

    private static final String DEFAULT_IMAGE = "mcr.microsoft.com/azure-storage/azurite";
    private static final int DEFAULT_PORT = 10002; // Azurite Table port

    @Override
    public CosmosApi supportedApi() {
        return CosmosApi.TABLE;
    }

    @Override
    public CosmosEngine engine() {
        return new CosmosEngine() {
            @Override public CosmosApi api()          { return CosmosApi.TABLE; }
            @Override public String    defaultImage() { return DEFAULT_IMAGE; }
            @Override public int       defaultPort()  { return DEFAULT_PORT; }

            @Override
            public CosmosCompatibilityMetadata compatibility() {
                return new CosmosCompatibilityMetadata(
                    "high",
                    List.of(
                        "Azure Cosmos DB RU/s throughput model",
                        "Cosmos DB-specific TTL semantics"
                    ),
                    "Azurite provides high compatibility with the Azure Table Storage API used by Cosmos DB for Table. "
                    + "Use standard Azure Table SDK connection strings."
                );
            }

            @Override
            public CosmosConnectionInfo buildConnectionInfo(String host, int port) {
                String cs = String.format(
                    "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                    + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==;"
                    + "TableEndpoint=http://%s:%d/devstoreaccount1;", host, port);
                return new CosmosConnectionInfo(host, port, cs,
                    "Use any Azure Table Storage SDK with this connection string.");
            }
        };
    }
}
