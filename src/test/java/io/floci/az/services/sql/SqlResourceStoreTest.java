package io.floci.az.services.sql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.GlobalNameRegistry;
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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SqlHandlerMockedTest.MockedProfile.class)
@DisplayName("SQL: servers in the ARM resource store, keyed by canonical id")
class SqlResourceStoreTest {

    private static final String API = "?api-version=2021-11-01";
    private static final String BODY = "{\"location\":\"eastus\",\"properties\":{"
        + "\"administratorLogin\":\"sa\",\"administratorLoginPassword\":\"FlociAz_Strong123!\"}}";

    @Inject
    StorageFactory storageFactory;

    @Inject
    GlobalNameRegistry names;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String server(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Sql/servers/" + name;
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneServer() {
        given().contentType("application/json").body(BODY).when().put(server("sub-case", "RG-Case", "casesrv") + API)
            .then().statusCode(201);
        given().contentType("application/json").body(BODY).when().put(server("sub-case", "rg-case", "casesrv") + API)
            .then().statusCode(200);

        given().when().get("/subscriptions/sub-case/resourceGroups/RG-CASE/providers/Microsoft.Sql/servers" + API)
            .then().statusCode(200).body("value", hasSize(1));
        given().when().get(server("SUB-CASE", "RG-CASE", "casesrv") + "/databases/master" + API)
            .then().statusCode(200);
    }

    @Test
    void aServerStoredUnderTheOldKeyIsServedListedOwnsItsNameAndMovesOnItsFirstWrite() throws Exception {
        SqlState.SqlServerEntry legacy = new SqlState.SqlServerEntry("legacysrv", "Sub-Legacy", "RG-Legacy",
            "eastus", "sa", "FlociAz_Strong123!", null, 0, "localhost", "Ready", null, null, Map.of(),
            new ConcurrentHashMap<>(Map.of("master", SqlState.SqlDatabaseEntry.master("legacysrv"))),
            new ConcurrentHashMap<>(), Instant.now());
        StorageBackend<String, StoredObject> backend = storageFactory.create(SqlState.STORE);
        String oldKey = "legacysrv";
        byte[] data = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsBytes(legacy);
        backend.put(oldKey, new StoredObject(oldKey, data, Map.of("serverName", "legacysrv"), Instant.now(), "etag"));
        names.clear();

        given().when().get(server("sub-legacy", "rg-legacy", "legacysrv") + API).then().statusCode(200)
            .body("name", equalTo("legacysrv"))
            .body("id", equalTo(server("Sub-Legacy", "RG-Legacy", "legacysrv")));
        given().when().get("/subscriptions/sub-legacy/providers/Microsoft.Sql/servers" + API).then().statusCode(200)
            .body("value.name", hasItem("legacysrv"));
        given().when().get(server("sub-legacy", "rg-legacy", "legacysrv") + "/databases/master" + API)
            .then().statusCode(200);
        given().contentType("application/json").body(BODY).when().put(server("sub-other", "rg", "legacysrv") + API)
            .then().statusCode(400).body("error.code", equalTo("NameAlreadyExists"));

        given().contentType("application/json")
            .body("{\"properties\":{\"startIpAddress\":\"10.0.0.1\",\"endIpAddress\":\"10.0.0.2\"}}")
            .when().put(server("Sub-Legacy", "RG-Legacy", "legacysrv") + "/firewallRules/office" + API)
            .then().statusCode(201);
        assertFalse(backend.get(oldKey).isPresent(), "the write moved the entry off its old key");
        assertTrue(backend.keys().stream()
            .anyMatch(key -> key.equals(server("sub-legacy", "rg-legacy", "legacysrv").toLowerCase())));
        given().when().get(server("sub-legacy", "rg-legacy", "legacysrv") + "/firewallRules/office" + API)
            .then().statusCode(200);
    }
}
