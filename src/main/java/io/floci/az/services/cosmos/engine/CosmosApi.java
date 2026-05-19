package io.floci.az.services.cosmos.engine;

public enum CosmosApi {
    NOSQL,
    MONGODB,
    POSTGRESQL,
    CASSANDRA,
    GREMLIN,
    TABLE;

    public String serviceTypeSuffix() {
        return switch (this) {
            case NOSQL       -> "cosmos-nosql";
            case MONGODB     -> "cosmos-mongo";
            case POSTGRESQL  -> "cosmos-postgresql";
            case CASSANDRA   -> "cosmos-cassandra";
            case GREMLIN     -> "cosmos-gremlin";
            case TABLE       -> "cosmos-table";
        };
    }
}
