package io.floci.az.services.mariadb;

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
@TestProfile(MariaDbHandlerMockedTest.MockedProfile.class)
@DisplayName("MariaDB: one store keyed by canonical id")
class MariaDbResourceStoreTest {

    private static final String API = "?api-version=2018-06-01";
    private static final String BODY = "{\"location\":\"eastus\","
        + "\"properties\":{\"administratorLogin\":\"a\",\"administratorLoginPassword\":\"P@ssw0rd1234\",\"version\":\"10.3\"}}";

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String server(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.DBforMariaDB/servers/"
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

        given().when().get("/subscriptions/sub-case/resourceGroups/rg-case/providers/Microsoft.DBforMariaDB"
                + "/servers" + API)
            .then().statusCode(200).body("value", hasSize(1));
    }

    @Test
    void aServerStoredUnderTheOldKeyIsServedAndMovesOnItsFirstWrite() throws Exception {
        MariaDbState.ServerEntry legacy = new MariaDbState.ServerEntry("LegacyMaria", "Sub-Legacy", "RG-Legacy",
            "eastus", "10.3", "a", "P@ssw0rd1234", 5120,
            "stale-container", 3306, "stale-host", Map.of(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
            new ConcurrentHashMap<>(), Instant.now());
        StorageBackend<String, StoredObject> backend = storageFactory.create("mariadb");
        String oldKey = "legacymaria";
        byte[] data = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsBytes(legacy);
        backend.put(oldKey, new StoredObject(oldKey, data, Map.of("serverName", "LegacyMaria"), Instant.now(), oldKey));

        given().when().get(server("sub-legacy", "rg-legacy", "legacymaria") + API).then().statusCode(200)
            .body("name", equalTo("LegacyMaria"))
            .body("properties.localPort", nullValue())
            .body("properties.fullyQualifiedDomainName", equalTo("localhost"));
        given().when().get("/subscriptions/Sub-Legacy/providers/Microsoft.DBforMariaDB/servers" + API)
            .then().statusCode(200).body("value", hasSize(1));
        // The account path finds a server by name through an index built when the state starts, as it does after a
        // restart over an older store; this entry was written after this run's state started.
        assertTrue(new MariaDbState(storageFactory).getServer("legacymaria").isPresent());

        given().contentType("application/json").body("{\"tags\":{\"env\":\"t\"}}")
            .when().patch(server("Sub-Legacy", "RG-Legacy", "LegacyMaria") + API).then().statusCode(200);
        assertFalse(backend.get(oldKey).isPresent(), "the write moved the entry off its old key");
        assertTrue(backend.keys().stream()
            .anyMatch(key -> key.equals(server("sub-legacy", "rg-legacy", "legacymaria").toLowerCase())));
        given().when().get(server("Sub-Legacy", "RG-Legacy", "LegacyMaria") + API).then().statusCode(200)
            .body("tags.env", equalTo("t"));
        given().when().get("/devstoreaccount1-mariadb/servers/legacymaria/connect").then().statusCode(200);
    }
}
