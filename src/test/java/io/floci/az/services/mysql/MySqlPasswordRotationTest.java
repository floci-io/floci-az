package io.floci.az.services.mysql;

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
 * An admin password change on a container-backed MySQL flexible server, with the container
 * manager mocked so no Docker is needed.
 */
@QuarkusTest
@TestProfile(MySqlPasswordRotationTest.ContainerBacked.class)
@DisplayName("MySQL admin password changes reach the running container")
class MySqlPasswordRotationTest {

    private static final String BASE = "/subscriptions/mysql-rotation-sub/resourceGroups/mysql-rotation-rg"
            + "/providers/Microsoft.DBforMySQL/flexibleServers/";
    private static final String API = "?api-version=2023-06-30";
    private static final String OLD_PASSWORD = "Original_Strong123!";
    private static final String NEW_PASSWORD = "Rotated_Strong456!";
    private static final String SERVER_BODY = "{\"location\":\"eastus\","
            + "\"sku\":{\"name\":\"Standard_B1ms\",\"tier\":\"Burstable\"},"
            + "\"properties\":{\"administratorLogin\":\"myadmin\","
            + "\"administratorLoginPassword\":\"" + OLD_PASSWORD + "\",\"version\":\"8.0.21\"}}";
    private static final String PASSWORD_PATCH =
            "{\"properties\":{\"administratorLoginPassword\":\"" + NEW_PASSWORD + "\"}}";

    public static class ContainerBacked implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.mysql.mocked", "false");
        }
    }

    @InjectMock
    MySqlServerManager serverManager;

    @BeforeEach
    void setUp() {
        given().post("/_admin/reset").then().statusCode(204);
        doAnswer(invocation -> {
            MySqlState.ServerEntry entry = invocation.getArgument(0);
            return entry.withContainer("cid-" + entry.serverName(), 3306, "localhost");
        }).when(serverManager).startServer(any());
    }

    @Test
    void aPasswordPatchRotatesTheRunningContainerAndThenStoresTheNewPassword() {
        createServer("rotate-mysql");

        given().contentType("application/json").body(PASSWORD_PATCH)
                .when().patch(BASE + "rotate-mysql" + API)
                .then().statusCode(200);

        verify(serverManager).rotateAdminPassword(
                argThat(entry -> "cid-rotate-mysql".equals(entry.containerId())
                        && OLD_PASSWORD.equals(entry.administratorLoginPassword())),
                eq(NEW_PASSWORD));
    }

    @Test
    void aServerDeletedWhileItsPasswordRotatesStaysDeleted() throws Exception {
        createServer("vanishing-mysql");
        CountDownLatch rotating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            rotating.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS), "rotation was never released");
            return null;
        }).when(serverManager).rotateAdminPassword(any(), any());

        CompletableFuture<Integer> update = CompletableFuture.supplyAsync(() -> given()
                .contentType("application/json").body(PASSWORD_PATCH)
                .patch(BASE + "vanishing-mysql" + API).statusCode());
        assertTrue(rotating.await(10, TimeUnit.SECONDS), "rotation never began");

        given().delete(BASE + "vanishing-mysql" + API).then().statusCode(204);
        release.countDown();

        assertEquals(404, update.get(20, TimeUnit.SECONDS));
        given().get(BASE + "vanishing-mysql" + API).then().statusCode(404);
        // Only the create started a container: the refused update must not bring one back.
        verify(serverManager, times(1)).startServer(any());
    }

    private static void createServer(String name) {
        given().contentType("application/json").body(SERVER_BODY)
                .when().put(BASE + name + API)
                .then().statusCode(201);
    }
}
