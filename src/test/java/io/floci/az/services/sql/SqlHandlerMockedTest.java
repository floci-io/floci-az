package io.floci.az.services.sql;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link SqlHandler} in {@code mocked=true} mode: servers are created in state with no
 * SQL Server container and no EULA requirement, transitioning immediately to {@code state=Ready}.
 */
@QuarkusTest
@TestProfile(SqlHandlerMockedTest.MockedProfile.class)
@DisplayName("SqlHandler — mocked mode (no Docker, no EULA)")
@SuppressWarnings("unused")
class SqlHandlerMockedTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.sql.mocked", "true");
        }
    }

    private static final String SUB  = "test-sub-sql-mocked";
    private static final String RG   = "test-rg-sql-mocked";
    private static final String BASE = "/subscriptions/" + SUB + "/resourceGroups/" + RG
                                     + "/providers/Microsoft.Sql";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    @DisplayName("PUT server returns 201 with state=Ready and no EULA error")
    void putServerMockedReturnsReady() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{"
                + "\"administratorLogin\":\"sa\","
                + "\"administratorLoginPassword\":\"FlociAz_Strong123!\"}}")
            .when().put(BASE + "/servers/mockedserver?api-version=2021-11-01")
            .then().statusCode(201)
            .body("name", equalTo("mockedserver"))
            .body("properties.state", equalTo("Ready"))
            .body("properties", not(hasKey("localPort")));
    }

    @Test
    @DisplayName("created server is listable and gettable")
    void createdServerVisible() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{"
                + "\"administratorLogin\":\"sa\","
                + "\"administratorLoginPassword\":\"FlociAz_Strong123!\"}}")
            .when().put(BASE + "/servers/listme?api-version=2021-11-01")
            .then().statusCode(201);

        given()
            .when().get(BASE + "/servers/listme?api-version=2021-11-01")
            .then().statusCode(200)
            .body("properties.state", equalTo("Ready"));

        given()
            .when().get(BASE + "/servers?api-version=2021-11-01")
            .then().statusCode(200)
            .body("value", hasSize(1));
    }

    @Test
    @DisplayName("master database auto-created on mocked server")
    void masterDatabaseAutoCreated() {
        given()
            .contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{"
                + "\"administratorLogin\":\"sa\","
                + "\"administratorLoginPassword\":\"FlociAz_Strong123!\"}}")
            .when().put(BASE + "/servers/dbhost?api-version=2021-11-01")
            .then().statusCode(201);

        given()
            .when().get(BASE + "/servers/dbhost/databases/master?api-version=2021-11-01")
            .then().statusCode(200);
    }
}
