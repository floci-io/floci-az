package io.floci.az.services.mysql;

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
@TestProfile(MySqlHandlerMockedTest.MockedProfile.class)
@DisplayName("MySQL: one store keyed by canonical id")
class MySqlResourceStoreTest {

    private static final String API = "?api-version=2023-12-30";
    private static final String BODY = "{\"location\":\"eastus\",\"sku\":{\"name\":\"Standard_B1ms\",\"tier\":\"Burstable\"},"
        + "\"properties\":{\"administratorLogin\":\"a\",\"administratorLoginPassword\":\"P@ssw0rd1234\",\"version\":\"8.0.21\"}}";

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String server(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.DBforMySQL/flexibleServers/"
            + name;
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneServer() {
        given().contentType("application/json").body(BODY).when().put(server("sub-case", "RG-Case", "Server1") + API)
            .then().statusCode(201);
        given().contentType("application/json").body(BODY.replace("{\"location\"", "{\"tags\":{\"env\":\"t\"},\"location\""))
            .when().put(server("sub-case", "rg-case", "server1") + API)
            .then().statusCode(200)
            .body("id", equalTo(server("sub-case", "RG-Case", "Server1")))
            .body("tags.env", equalTo("t"));

        given().when().get("/subscriptions/sub-case/resourceGroups/rg-case/providers/Microsoft.DBforMySQL"
                + "/flexibleServers" + API)
            .then().statusCode(200).body("value", hasSize(1));
    }

    @Test
    void aServerStoredUnderTheOldKeyIsServedAndMovesOnItsFirstWrite() throws Exception {
        MySqlState.ServerEntry legacy = new MySqlState.ServerEntry("LegacyMy", "Sub-Legacy", "RG-Legacy",
            "eastus", "8.0.21", "a", "P@ssw0rd1234", "Standard_B1ms", "Burstable", 32,
            "stale-container", 3306, "stale-host", Map.of(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
            new ConcurrentHashMap<>(), Instant.now());
        StorageBackend<String, StoredObject> backend = storageFactory.create("mysql");
        String oldKey = "legacymy";
        byte[] data = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsBytes(legacy);
        backend.put(oldKey, new StoredObject(oldKey, data, Map.of("serverName", "LegacyMy"), Instant.now(), oldKey));

        given().when().get(server("sub-legacy", "rg-legacy", "legacymy") + API).then().statusCode(200)
            .body("name", equalTo("LegacyMy"))
            .body("properties.localPort", nullValue())
            .body("properties.fullyQualifiedDomainName", equalTo("localhost"));
        given().when().get("/subscriptions/Sub-Legacy/providers/Microsoft.DBforMySQL/flexibleServers" + API)
            .then().statusCode(200).body("value", hasSize(1));
        // The account path finds a server by name through an index built when the state starts, as it does after a
        // restart over an older store; this entry was written after this run's state started.
        assertTrue(new MySqlState(storageFactory).getServer("legacymy").isPresent());

        given().contentType("application/json").body("{\"tags\":{\"env\":\"t\"}}")
            .when().patch(server("Sub-Legacy", "RG-Legacy", "LegacyMy") + API).then().statusCode(200);
        assertFalse(backend.get(oldKey).isPresent(), "the write moved the entry off its old key");
        assertTrue(backend.keys().stream()
            .anyMatch(key -> key.equals(server("sub-legacy", "rg-legacy", "legacymy").toLowerCase())));
        given().when().get(server("Sub-Legacy", "RG-Legacy", "LegacyMy") + API).then().statusCode(200)
            .body("tags.env", equalTo("t"));
        given().when().get("/devstoreaccount1-mysql/flexibleServers/legacymy/connect").then().statusCode(200);
    }
}
