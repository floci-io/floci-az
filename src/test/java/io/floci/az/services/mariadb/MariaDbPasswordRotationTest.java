package io.floci.az.services.mariadb;

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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * An admin password change on a container-backed MariaDB server, with the container manager
 * mocked so no Docker is needed.
 */
@QuarkusTest
@TestProfile(MariaDbPasswordRotationTest.ContainerBacked.class)
@DisplayName("MariaDB admin password changes reach the running container")
class MariaDbPasswordRotationTest {

    private static final String BASE = "/subscriptions/mariadb-rotation-sub/resourceGroups/mariadb-rotation-rg"
            + "/providers/Microsoft.DBforMariaDB/servers/";
    private static final String API = "?api-version=2018-06-01";
    private static final String OLD_PASSWORD = "Original_Strong123!";
    private static final String NEW_PASSWORD = "Rotated_Strong456!";
    private static final String SERVER_BODY = "{\"location\":\"eastus\","
            + "\"properties\":{\"administratorLogin\":\"mariadbadmin\","
            + "\"administratorLoginPassword\":\"" + OLD_PASSWORD + "\",\"version\":\"10.3\","
            + "\"storageProfile\":{\"storageMB\":5120}}}";
    private static final String PASSWORD_PATCH =
            "{\"properties\":{\"administratorLoginPassword\":\"" + NEW_PASSWORD + "\"}}";

    public static class ContainerBacked implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.maria-db.mocked", "false");
        }
    }

    @InjectMock
    MariaDbServerManager serverManager;

    @BeforeEach
    void setUp() {
        given().post("/_admin/reset").then().statusCode(204);
        doAnswer(invocation -> {
            MariaDbState.ServerEntry entry = invocation.getArgument(0);
            return entry.withContainer("cid-" + entry.serverName(), 3306, "localhost");
        }).when(serverManager).startServer(any());
    }

    @Test
    void aPasswordPatchRotatesTheRunningContainerAndThenStoresTheNewPassword() {
        createServer("rotate-mariadb");

        given().contentType("application/json").body(PASSWORD_PATCH)
                .when().patch(BASE + "rotate-mariadb" + API)
                .then().statusCode(200);

        verify(serverManager).rotateAdminPassword(
                argThat(entry -> "cid-rotate-mariadb".equals(entry.containerId())
                        && OLD_PASSWORD.equals(entry.administratorLoginPassword())),
                eq(NEW_PASSWORD));
    }

    @Test
    void aServerDeletedWhileItsPasswordRotatesStaysDeleted() throws Exception {
        createServer("vanishing-mariadb");
        CountDownLatch rotating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            rotating.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS), "rotation was never released");
            return null;
        }).when(serverManager).rotateAdminPassword(any(), any());

        CompletableFuture<Integer> update = CompletableFuture.supplyAsync(() -> given()
                .contentType("application/json").body(PASSWORD_PATCH)
                .patch(BASE + "vanishing-mariadb" + API).statusCode());
        assertTrue(rotating.await(10, TimeUnit.SECONDS), "rotation never began");

        given().delete(BASE + "vanishing-mariadb" + API).then().statusCode(204);
        release.countDown();

        assertEquals(404, update.get(20, TimeUnit.SECONDS));
        given().get(BASE + "vanishing-mariadb" + API).then().statusCode(404);
        // Only the create started a container: the refused update must not bring one back.
        verify(serverManager, times(1)).startServer(any());
    }

    @Test
    void aServerDeletedWhileItsContainerStartsStaysDeletedAndTheContainerIsStopped() throws Exception {
        // The create stores the server with no container, so the GET below has to start one.
        doAnswer(invocation -> invocation.getArgument(0)).when(serverManager).startServer(any());
        createServer("starting-mariadb");
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            starting.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS), "startup was never released");
            MariaDbState.ServerEntry entry = invocation.getArgument(0);
            return entry.withContainer("cid-" + entry.serverName(), 3306, "localhost");
        }).when(serverManager).startServer(any());

        CompletableFuture<Integer> read = CompletableFuture.supplyAsync(() -> given()
                .get(BASE + "starting-mariadb" + API).statusCode());
        assertTrue(starting.await(10, TimeUnit.SECONDS), "startup never began");

        given().delete(BASE + "starting-mariadb" + API).then().statusCode(204);
        release.countDown();

        read.get(20, TimeUnit.SECONDS);
        given().get(BASE + "starting-mariadb" + API).then().statusCode(404);
        verify(serverManager).stopServer(argThat(entry -> "cid-starting-mariadb".equals(entry.containerId())));
    }

    private static void createServer(String name) {
        given().contentType("application/json").body(SERVER_BODY)
                .when().put(BASE + name + API)
                .then().statusCode(201);
    }
}
