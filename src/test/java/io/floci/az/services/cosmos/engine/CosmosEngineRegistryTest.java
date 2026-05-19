package io.floci.az.services.cosmos.engine;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CosmosEngineRegistryTest {

    @Inject CosmosEngineRegistry registry;

    @Test
    void allEnginesAreRegistered() {
        var all = registry.all();
        assertTrue(all.containsKey(CosmosApi.NOSQL),       "NOSQL must be registered");
        assertTrue(all.containsKey(CosmosApi.MONGODB),     "MONGODB must be registered");
        assertTrue(all.containsKey(CosmosApi.POSTGRESQL),  "POSTGRESQL must be registered");
        assertTrue(all.containsKey(CosmosApi.CASSANDRA),   "CASSANDRA must be registered");
        assertTrue(all.containsKey(CosmosApi.GREMLIN),     "GREMLIN must be registered");
        assertTrue(all.containsKey(CosmosApi.TABLE),       "TABLE must be registered");
    }

    @Test
    void resolveByApiReturnsCorrectProvider() {
        Optional<CosmosEngineProvider> mongoProvider = registry.resolve(CosmosApi.MONGODB);
        assertTrue(mongoProvider.isPresent());
        assertEquals(CosmosApi.MONGODB, mongoProvider.get().supportedApi());
        assertEquals("mongo:7", mongoProvider.get().engine().defaultImage());
    }

    @Test
    void resolveByServiceTypeWorks() {
        Optional<CosmosEngineProvider> tableProvider = registry.resolveByServiceType("cosmos-table");
        assertTrue(tableProvider.isPresent());
        assertEquals(CosmosApi.TABLE, tableProvider.get().supportedApi());
    }

    @Test
    void unknownServiceTypeReturnsEmpty() {
        assertTrue(registry.resolveByServiceType("cosmos-unknown").isEmpty());
        assertTrue(registry.resolve(null).isEmpty());
    }

    @Test
    void eachApiHasUniqueServiceTypeSuffix() {
        List<String> suffixes = Arrays.stream(CosmosApi.values())
            .map(CosmosApi::serviceTypeSuffix)
            .toList();
        long unique = suffixes.stream().distinct().count();
        assertEquals(suffixes.size(), unique, "Service type suffixes must be unique");
    }

    @Test
    void connectionInfoBuildsCorrectly() {
        var mongoEngine = registry.resolve(CosmosApi.MONGODB).get().engine();
        var info = mongoEngine.buildConnectionInfo("localhost", 27017);
        assertTrue(info.connectionString().startsWith("mongodb://localhost:27017"));
        assertEquals(27017, info.port());

        var tableEngine = registry.resolve(CosmosApi.TABLE).get().engine();
        var tableInfo = tableEngine.buildConnectionInfo("localhost", 10002);
        assertTrue(tableInfo.connectionString().contains("TableEndpoint=http://localhost:10002"));

        var cassandraEngine = registry.resolve(CosmosApi.CASSANDRA).get().engine();
        var cassInfo = cassandraEngine.buildConnectionInfo("localhost", 9042);
        assertEquals(9042, cassInfo.port());
    }

    @Test
    void compatibilityMetadataIsPresent() {
        for (CosmosApi api : CosmosApi.values()) {
            Optional<CosmosEngineProvider> provider = registry.resolve(api);
            assertTrue(provider.isPresent(), "Provider for " + api + " must be present");
            var meta = provider.get().engine().compatibility();
            assertNotNull(meta.parityLevel(), api + " parityLevel must not be null");
            assertFalse(meta.parityLevel().isBlank(), api + " parityLevel must not be blank");
            assertNotNull(meta.notes(), api + " notes must not be null");
            assertFalse(meta.notes().isBlank(), api + " notes must not be blank");
        }
    }
}
