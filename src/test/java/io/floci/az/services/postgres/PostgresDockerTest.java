package io.floci.az.services.postgres;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PostgreSQL handler tests with {@code mocked=false} — starts a real {@code postgres:17-alpine}
 * container backing the flexible server and proves the data plane is reachable.
 *
 * <p>Skipped automatically when Docker is unavailable. The backing image may need to be pulled
 * on first run, so the server PUT (which starts the container synchronously) is given a generous
 * client timeout via the handler's own readiness wait.
 *
 * <p>Tests are ordered and share state: the server is created in test 1, its live TCP port is
 * verified in test 2, connection strings are checked in test 3, and it is deleted in test 4.
 */
@QuarkusTest
@TestProfile(PostgresDockerTest.RealModeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("PostgresHandler — real Docker-backed mode (Docker required)")
class PostgresDockerTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.postgres.mocked", "false");
        }
    }

    private static final String SUB  = "test-sub-pg-docker";
    private static final String RG   = "test-rg-pg-docker";
    private static final String NAME = "docker-test-pg";
    private static final String API  = "?api-version=2025-08-01";
    private static final String BASE =
        "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.DBforPostgreSQL";
    private static final String PG_PATH = BASE + "/flexibleServers/" + NAME;

    private static final String CREATE_BODY =
        "{\"location\":\"eastus\","
        + "\"sku\":{\"name\":\"Standard_B1ms\",\"tier\":\"Burstable\"},"
        + "\"properties\":{"
        + "\"administratorLogin\":\"psqladmin\","
        + "\"administratorLoginPassword\":\"FlociAz_Strong123!\","
        + "\"version\":\"16\","
        + "\"storage\":{\"storageSizeGB\":32}}}";

    private static int localPort = 0;

    /** Pure filesystem check — safe to run before Quarkus is fully ready (mirrors VmDockerTest). */
    @BeforeAll
    void checkDockerAvailable() {
        boolean dockerAvailable = Files.exists(Paths.get("/var/run/docker.sock"))
                || System.getenv("DOCKER_HOST") != null;
        assumeTrue(dockerAvailable, "Docker socket not available — skipping real PostgreSQL tests");
    }

    @AfterAll
    void cleanup() {
        try { given().delete(PG_PATH + API); } catch (Exception ignored) {}
    }

    @Test
    @Order(1)
    @DisplayName("PUT server starts a container and returns 201 with provisioningState=Succeeded + localPort")
    void createServer() {
        given().post("/_admin/reset").then().statusCode(204);

        localPort = given().contentType("application/json").body(CREATE_BODY)
            .when().put(PG_PATH + API)
            .then().statusCode(201)
            .body("name", equalTo(NAME))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.state", equalTo("Ready"))
            .body("properties.localPort", greaterThan(0))
            .extract().path("properties.localPort");
    }

    @Test
    @Order(2)
    @DisplayName("the allocated host port accepts TCP connections (live PostgreSQL data plane)")
    void portAnswers() throws Exception {
        assumeTrue(localPort > 0, "server was not created — skipping");
        boolean open = false;
        long deadline = System.currentTimeMillis() + 30_000;
        while (!open && System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket("localhost", localPort)) {
                open = true;
            } catch (Exception e) {
                Thread.sleep(1_000);
            }
        }
        assertTrue(open, "PostgreSQL port " + localPort + " did not accept TCP connections");
    }

    @Test
    @Order(3)
    @DisplayName("/connect returns a jdbcUrl pointing at the live container port")
    void connectStrings() {
        assumeTrue(localPort > 0, "server was not created — skipping");
        given().when().get("/devstoreaccount1-postgres/flexibleServers/" + NAME + "/connect")
            .then().statusCode(200)
            .body("port", equalTo(localPort))
            .body("jdbcUrl", containsString("jdbc:postgresql://localhost:" + localPort))
            .body("jdbcUrl", containsString("sslmode=disable"));
    }

    @Test
    @Order(4)
    @DisplayName("DELETE removes the server and stops its container; subsequent GET returns 404")
    void deleteServer() {
        assumeTrue(localPort > 0, "server was not created — skipping");
        given().when().delete(PG_PATH + API).then().statusCode(204);
        given().when().get(PG_PATH + API).then().statusCode(404);
    }
}
