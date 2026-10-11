package io.floci.az.services.postgres;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * An admin password change on a container-backed flexible server is applied to the running
 * container before it is committed. The container manager is mocked, so no Docker is needed.
 */
@QuarkusTest
@TestProfile(PostgresPasswordRotationTest.ContainerBacked.class)
@DisplayName("PostgreSQL admin password changes reach the running container")
class PostgresPasswordRotationTest {

    private static final String BASE = "/subscriptions/pg-rotation-sub/resourceGroups/pg-rotation-rg"
            + "/providers/Microsoft.DBforPostgreSQL/flexibleServers/";
    private static final String API = "?api-version=2025-08-01";
    private static final String OLD_PASSWORD = "Original_Strong123!";
    private static final String NEW_PASSWORD = "Rotated_Strong456!";
    private static final String SERVER_BODY = "{\"location\":\"eastus\","
            + "\"sku\":{\"name\":\"Standard_B1ms\",\"tier\":\"Burstable\"},"
            + "\"properties\":{\"administratorLogin\":\"pgadmin\","
            + "\"administratorLoginPassword\":\"" + OLD_PASSWORD + "\",\"version\":\"16\"}}";
    private static final String PASSWORD_PATCH =
            "{\"properties\":{\"administratorLoginPassword\":\"" + NEW_PASSWORD + "\"}}";

    public static class ContainerBacked implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.postgres.mocked", "false");
        }
    }

    @InjectMock
    PostgresServerManager serverManager;

    @BeforeEach
    void setUp() {
        given().post("/_admin/reset").then().statusCode(204);
        doAnswer(invocation -> startedEntry(invocation.getArgument(0)))
                .when(serverManager).startServer(any());
    }

    @Test
    void aPasswordPatchRotatesTheRunningContainerAndThenStoresTheNewPassword() {
        createServer("rotate-pg");

        given().contentType("application/json").body(PASSWORD_PATCH)
                .when().patch(BASE + "rotate-pg" + API)
                .then().statusCode(202);

        verify(serverManager).rotateAdminPassword(
                argThat(entry -> "cid-rotate-pg".equals(entry.containerId())
                        && OLD_PASSWORD.equals(entry.administratorLoginPassword())),
                eq(NEW_PASSWORD));
        assertConnectPassword("rotate-pg", NEW_PASSWORD);
    }

    @Test
    void aPasswordChangeThroughPutIsAppliedToTheContainerToo() {
        createServer("put-pg");

        given().contentType("application/json")
                .body(SERVER_BODY.replace(OLD_PASSWORD, NEW_PASSWORD))
                .when().put(BASE + "put-pg" + API)
                .then().statusCode(202);

        verify(serverManager).rotateAdminPassword(
                argThat(entry -> "cid-put-pg".equals(entry.containerId())), eq(NEW_PASSWORD));
        assertConnectPassword("put-pg", NEW_PASSWORD);
    }

    @Test
    void anUpdateThatKeepsThePasswordDoesNotTouchTheContainer() {
        createServer("tags-pg");

        given().contentType("application/json")
                .body("{\"tags\":{\"env\":\"patched\"},"
                        + "\"properties\":{\"administratorLoginPassword\":\"" + OLD_PASSWORD + "\"}}")
                .when().patch(BASE + "tags-pg" + API)
                .then().statusCode(202);

        verify(serverManager, never()).rotateAdminPassword(any(), any());
    }

    @Test
    void aFailedRotationKeepsThePasswordTheContainerStillAccepts() {
        createServer("failing-pg");
        doThrow(new RuntimeException("Password rotation failed for PostgreSQL server 'failing-pg'"))
                .when(serverManager).rotateAdminPassword(any(), any());

        given().contentType("application/json").body(PASSWORD_PATCH)
                .when().patch(BASE + "failing-pg" + API)
                .then().statusCode(500)
                .body(not(containsString(NEW_PASSWORD)));

        assertConnectPassword("failing-pg", OLD_PASSWORD);
    }

    @Test
    void aPasswordChangeDuringContainerStartupIsAppliedOnceTheContainerRuns() throws Exception {
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            starting.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS), "startup was never released");
            return startedEntry(invocation.getArgument(0));
        }).when(serverManager).startServer(any());

        CompletableFuture<Integer> create = CompletableFuture.supplyAsync(() -> given()
                .contentType("application/json").body(SERVER_BODY)
                .put(BASE + "starting-pg" + API).statusCode());
        assertTrue(starting.await(10, TimeUnit.SECONDS), "container startup never began");

        CompletableFuture<Integer> update = CompletableFuture.supplyAsync(() -> given()
                .contentType("application/json").body(PASSWORD_PATCH)
                .patch(BASE + "starting-pg" + API).statusCode());
        assertThrows(TimeoutException.class, () -> update.get(500, TimeUnit.MILLISECONDS),
                "the password change must wait for the container it has to apply to");

        release.countDown();
        assertEquals(202, create.get(20, TimeUnit.SECONDS));
        assertEquals(202, update.get(20, TimeUnit.SECONDS));

        verify(serverManager).rotateAdminPassword(
                argThat(entry -> "cid-starting-pg".equals(entry.containerId())
                        && OLD_PASSWORD.equals(entry.administratorLoginPassword())),
                eq(NEW_PASSWORD));
        assertConnectPassword("starting-pg", NEW_PASSWORD);
    }

    private static PostgresState.ServerEntry startedEntry(PostgresState.ServerEntry entry) {
        return entry.withContainer("cid-" + entry.serverName(), 5432, "localhost");
    }

    private static void createServer(String name) {
        given().contentType("application/json").body(SERVER_BODY)
                .when().put(BASE + name + API)
                .then().statusCode(202);
    }

    private static void assertConnectPassword(String name, String password) {
        given().when().get("/devstoreaccount1-postgres/flexibleServers/" + name + "/connect")
                .then().statusCode(200)
                .body("uri", containsString(":" + password + "@"));
    }
}
