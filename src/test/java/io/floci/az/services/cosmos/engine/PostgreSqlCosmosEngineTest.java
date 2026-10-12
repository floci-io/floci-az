package io.floci.az.services.cosmos.engine;

import io.floci.az.services.cosmos.engine.impl.PostgreSqlCosmosEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgreSqlCosmosEngineTest {

    private final CosmosEngine engine = new PostgreSqlCosmosEngine().engine();

    @Test
    void anIpv6HostGoesIntoTheConnectionStringWithoutUrlBrackets() {
        CosmosConnectionInfo info = engine.buildConnectionInfo("[::1]", 5432);

        assertEquals("host=::1 port=5432 dbname=citus user=citus password=mypassword sslmode=disable",
                info.connectionString());
    }

    @Test
    void aHostNameGoesIntoTheConnectionStringUnchanged() {
        CosmosConnectionInfo info = engine.buildConnectionInfo("docker", 15432);

        assertEquals("host=docker port=15432 dbname=citus user=citus password=mypassword sslmode=disable",
                info.connectionString());
    }
}
