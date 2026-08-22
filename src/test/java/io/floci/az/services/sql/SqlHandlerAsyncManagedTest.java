package io.floci.az.services.sql;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(SqlHandlerAsyncManagedTest.AsyncManagedProfile.class)
class SqlHandlerAsyncManagedTest {

    private static final String SERVER_URL = "/subscriptions/test-sub/resourceGroups/test-rg"
        + "/providers/Microsoft.Sql/servers/asyncserver?api-version=2021-11-01";
    private static final String BODY = "{\"location\":\"eastus\",\"properties\":{"
        + "\"administratorLogin\":\"sa\","
        + "\"administratorLoginPassword\":\"FlociAz_Strong123!\"}}";

    @InjectMock
    SqlServerManager serverManager;

    @Inject
    SqlState state;

    private CountDownLatch startEntered;
    private CountDownLatch releaseStart;

    @BeforeEach
    void setUp() {
        given().post("/_admin/reset").then().statusCode(204);
        startEntered = new CountDownLatch(1);
        releaseStart = new CountDownLatch(1);
        when(serverManager.startServer(any())).thenAnswer(invocation -> {
            SqlState.SqlServerEntry desired = invocation.getArgument(0);
            startEntered.countDown();
            if (!releaseStart.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timed out waiting to release provisioning");
            }
            return desired.withContainer("container-id", 14330, "localhost");
        });
    }

    @AfterEach
    void releaseWorker() {
        releaseStart.countDown();
    }

    @Test
    void managedCreateUsesLocationPollingAndDeterministicRetries() throws Exception {
        Instant requestStarted = Instant.now();
        Response create = given()
            .contentType("application/json")
            .body(BODY)
            .when().put(SERVER_URL);

        assertEquals(202, create.statusCode());
        assertTrue(Duration.between(requestStarted, Instant.now()).compareTo(Duration.ofSeconds(2)) < 0);
        assertTrue(startEntered.await(2, TimeUnit.SECONDS));
        String operationLocation = create.header("Location");
        assertNotNull(operationLocation);
        assertTrue(operationLocation.contains("/providers/Microsoft.Sql/locations/eastus/"
            + "serverOperationResults/"));
        assertEquals("1", create.header("Retry-After"));
        assertEquals("Creating", create.jsonPath().getString("properties.state"));

        given()
            .contentType("application/json")
            .body(BODY)
            .when().put(SERVER_URL)
            .then().statusCode(202)
            .header("Location", equalTo(operationLocation));

        given()
            .contentType("application/json")
            .body(BODY.replace("eastus", "westus"))
            .when().put(SERVER_URL)
            .then().statusCode(409)
            .body("error.code", equalTo("ConflictingServerOperation"));

        given()
            .when().get(operationLocation)
            .then().statusCode(202)
            .body("status", equalTo("InProgress"));

        given()
            .when().get(SERVER_URL)
            .then().statusCode(200)
            .body("properties.state", equalTo("Creating"));

        releaseStart.countDown();
        awaitSucceeded(operationLocation, Duration.ofSeconds(5));

        given()
            .when().get(SERVER_URL)
            .then().statusCode(200)
            .body("properties.state", equalTo("Ready"));

        given()
            .when().get("/devstoreaccount1-sql/servers/asyncserver/connect")
            .then().statusCode(200)
            .body("host", equalTo("localhost"))
            .body("port", equalTo(14330));
    }

    @Test
    void managedPutReprovisionsRestoredReadyServerWithoutRuntime() throws Exception {
        state.putServer(new SqlState.SqlServerEntry(
            "asyncserver", "test-sub", "test-rg", "eastus", "sa",
            "FlociAz_Strong123!", null, 0, "localhost", "Ready", null, null,
            Map.of(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), Instant.now()));

        Response retry = given()
            .contentType("application/json")
            .body(BODY)
            .when().put(SERVER_URL);

        assertEquals(202, retry.statusCode());
        assertEquals("Creating", retry.jsonPath().getString("properties.state"));
        assertTrue(startEntered.await(2, TimeUnit.SECONDS));

        releaseStart.countDown();
        awaitSucceeded(retry.header("Location"), Duration.ofSeconds(5));

        given()
            .when().get("/devstoreaccount1-sql/servers/asyncserver/connect")
            .then().statusCode(200)
            .body("port", equalTo(14330));
    }

    private static void awaitSucceeded(String operationLocation, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Response response = given().when().get(operationLocation);
            if (response.statusCode() == 200) {
                assertEquals("Succeeded", response.jsonPath().getString("status"));
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Provisioning operation did not succeed within " + timeout);
    }

    public static class AsyncManagedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "floci-az.services.sql.data-plane.provider", "managed",
                "floci-az.services.sql.accept-eula", "Y");
        }
    }
}
