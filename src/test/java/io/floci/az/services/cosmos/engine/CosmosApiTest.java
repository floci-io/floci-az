package io.floci.az.services.cosmos.engine;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CosmosApiTest {

    @Test
    void serviceTypeSuffixesAreCorrect() {
        assertEquals("cosmos-nosql",        CosmosApi.NOSQL.serviceTypeSuffix());
        assertEquals("cosmos-mongo",        CosmosApi.MONGODB.serviceTypeSuffix());
        assertEquals("cosmos-postgresql",   CosmosApi.POSTGRESQL.serviceTypeSuffix());
        assertEquals("cosmos-cassandra",    CosmosApi.CASSANDRA.serviceTypeSuffix());
        assertEquals("cosmos-gremlin",      CosmosApi.GREMLIN.serviceTypeSuffix());
        assertEquals("cosmos-table",        CosmosApi.TABLE.serviceTypeSuffix());
    }

    @Test
    void noDuplicateSuffixes() {
        List<String> suffixes = Arrays.stream(CosmosApi.values())
            .map(CosmosApi::serviceTypeSuffix)
            .toList();
        long unique = suffixes.stream().distinct().count();
        assertEquals(suffixes.size(), unique, "Suffixes must be unique");
    }

    @Test
    void allApisHaveSuffix() {
        for (CosmosApi api : CosmosApi.values()) {
            String suffix = api.serviceTypeSuffix();
            assertNotNull(suffix, api + " must have a suffix");
            assertFalse(suffix.isBlank(), api + " suffix must not be blank");
        }
    }
}
