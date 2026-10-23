package io.floci.az.services.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(PostgresHandlerMockedTest.MockedProfile.class)
@DisplayName("PostgreSQL: one store keyed by canonical id")
class PostgresResourceStoreTest {

    private static final String API = "?api-version=2025-08-01";
    private static final String BODY = "{\"location\":\"eastus\",\"sku\":{\"name\":\"Standard_B1ms\",\"tier\":\"Burstable\"},"
        + "\"properties\":{\"administratorLogin\":\"a\",\"administratorLoginPassword\":\"P@ssw0rd1234\",\"version\":\"16\"}}";

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String server(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.DBforPostgreSQL/flexibleServers/"
            + name;
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneServer() {
        given().contentType("application/json").body(BODY).when().put(server("sub-case", "RG-Case", "Server1") + API)
            .then().statusCode(202);
        given().contentType("application/json").body(BODY.replace("{\"location\"", "{\"tags\":{\"env\":\"t\"},\"location\""))
            .when().put(server("sub-case", "rg-case", "server1") + API)
            .then().statusCode(202)
            .body("id", equalTo(server("sub-case", "RG-Case", "Server1")))
            .body("tags.env", equalTo("t"));

        given().when().get("/subscriptions/sub-case/resourceGroups/rg-case/providers/Microsoft.DBforPostgreSQL"
                + "/flexibleServers" + API)
            .then().statusCode(200).body("value", hasSize(1));
    }

    @Test
    void aServerStoredUnderTheOldKeyIsServedAndMovesOnItsFirstWrite() throws Exception {
        PostgresState.ServerEntry legacy = new PostgresState.ServerEntry("LegacyPg", "Sub-Legacy", "RG-Legacy",
            "eastus", "16", "a", "P@ssw0rd1234", "Standard_B1ms", "Burstable", 32,
            "stale-container", 5432, "stale-host", Map.of(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
            new ConcurrentHashMap<>(), Instant.now());
        StorageBackend<String, StoredObject> backend = storageFactory.create("postgres");
        String oldKey = "legacypg";
        byte[] data = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsBytes(legacy);
        backend.put(oldKey, new StoredObject(oldKey, data, Map.of("serverName", "LegacyPg"), Instant.now(), oldKey));

        given().when().get(server("sub-legacy", "rg-legacy", "legacypg") + API).then().statusCode(200)
            .body("name", equalTo("LegacyPg"))
            .body("properties.localPort", nullValue())
            .body("properties.fullyQualifiedDomainName", equalTo("localhost"));
        given().when().get("/subscriptions/Sub-Legacy/providers/Microsoft.DBforPostgreSQL/flexibleServers" + API)
            .then().statusCode(200).body("value", hasSize(1));
        given().when().get("/devstoreaccount1-postgres/flexibleServers/legacypg/connect").then().statusCode(200);

        given().contentType("application/json").body("{\"tags\":{\"env\":\"t\"}}")
            .when().patch(server("Sub-Legacy", "RG-Legacy", "LegacyPg") + API).then().statusCode(202);
        assertFalse(backend.get(oldKey).isPresent(), "the write moved the entry off its old key");
        assertTrue(backend.keys().stream()
            .anyMatch(key -> key.equals(server("sub-legacy", "rg-legacy", "legacypg").toLowerCase())));
        given().when().get(server("Sub-Legacy", "RG-Legacy", "LegacyPg") + API).then().statusCode(200)
            .body("tags.env", equalTo("t"));
    }
}
