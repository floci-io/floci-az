package io.floci.az.services.acr;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.anyOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Catalog pagination against a real {@code registry:2}, which is the only thing that can confirm
 * the cursor behaviour the proxy is built on.
 *
 * <p>The proxy serves one client page from one backend request by seeding {@code last} with
 * {@code {registry}/} and asking for one repository beyond the page. That rests on three things
 * the distribution spec does not state: the container walks its repositories as a directory tree,
 * so one registry's repositories are contiguous; {@code last} is a position in that walk rather
 * than a repository that has to exist; and the container advertises a next page whenever the page
 * it returned was full rather than when more results remain. If any of those change under a
 * {@code registry:2} pull, this test fails rather than the behaviour silently degrading.
 *
 * <p>Skipped automatically when Docker is unavailable.
 */
@QuarkusTest
@TestProfile(AcrCatalogPaginationDockerTest.RealModeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ACR catalog pagination against a real registry (Docker required)")
class AcrCatalogPaginationDockerTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.acr.mocked", "false");
        }
    }

    /**
     * Ours sits between two neighbours in the shared container, which is what makes the cursor
     * seed load-bearing: without it the container would spend the first page on {@link #BEFORE}
     * and answer with repositories that are all filtered away.
     */
    private static final String BEFORE = "catalogpagea";
    private static final String REGISTRY = "catalogpageb";
    private static final String AFTER = "catalogpagec";
    private static final String SUB = "00000000-0000-0000-0000-000000000001";
    private static final String RG = "test-rg-catalog";

    /** Pushed in an order that does not match the catalog's, so ordering is the registry's own. */
    private static final List<String> OURS = List.of("web", "app", "team/api", "zebra");

    private boolean pushed;

    /** Pure filesystem check, which is all that is safe before Quarkus is listening. */
    @BeforeAll
    void checkDockerAvailable() {
        assumeTrue(Files.exists(Paths.get("/var/run/docker.sock")) || System.getenv("DOCKER_HOST") != null,
                "Docker socket not available, skipping the real registry catalog tests");
    }

    /**
     * Pushes into both registries once, on the first test to need it. This cannot be a
     * {@code @BeforeAll}: the HTTP port is not serving yet when that runs.
     */
    @BeforeEach
    void pushRepositoriesToBothRegistries() {
        if (pushed) {
            return;
        }
        createRegistry(BEFORE);
        createRegistry(REGISTRY);
        createRegistry(AFTER);
        // Neighbours on both sides, so a page can be spoiled from either end. Their repositories
        // must never appear in our catalog, whatever page they would have fallen on.
        push(BEFORE, "app");
        push(BEFORE, "other");
        push(BEFORE, "third");
        for (String repository : OURS) {
            push(REGISTRY, repository);
        }
        push(AFTER, "app");
        push(AFTER, "other");
        pushed = true;
    }

    @Test
    void theUnpaginatedCatalogIsThisRegistrysOwn() {
        assertEquals(List.of("app", "team/api", "web", "zebra"), catalog(""));
    }

    @Test
    void pagingWalksEveryRepositoryExactlyOnceAndStops() {
        List<String> seen = new ArrayList<>();
        String query = "?n=2";
        int pages = 0;

        while (query != null) {
            Response page = catalogResponse(query);
            seen.addAll(repositories(page));
            String link = page.getHeader("Link");
            if (link != null) {
                assertFalse(link.contains(REGISTRY + "/"),
                        "the next cursor leaked the internal repository prefix: " + link);
            }
            query = nextQuery(link);
            assertTrue(++pages <= 10, "paging did not terminate");
        }

        assertEquals(List.of("app", "team/api", "web", "zebra"), seen);
    }

    @Test
    void aPageIsFullEvenThoughTheNeighboursRepositoriesShareTheContainer() {
        // Three repositories precede ours in the container. Unseeded, the first page would be
        // spent on those and come back empty; seeded, the container counts ours and fills it.
        assertEquals(List.of("app", "team/api"), catalog("?n=2"));
    }

    @Test
    void aPageThatExactlyExhaustsTheCatalogCarriesNoNextLink() {
        Response page = catalogResponse("?n=4");

        assertEquals(List.of("app", "team/api", "web", "zebra"), repositories(page));
        assertEquals(null, page.getHeader("Link"),
                "the container advertises a next page after a full one, so we must not echo it");
    }

    @Test
    void aCursorNamingTheLastRepositoryEndsTheWalk() {
        Response page = catalogResponse("?n=2&last=zebra");

        assertEquals(List.of(), repositories(page));
        assertEquals(null, page.getHeader("Link"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static String loginServer(String registry) {
        return registry + ".azurecr.io";
    }

    private static void createRegistry(String name) {
        given().contentType("application/json")
                .body("{\"location\":\"eastus\",\"sku\":{\"name\":\"Basic\"}}")
                .when().put("/subscriptions/" + SUB + "/resourceGroups/" + RG
                        + "/providers/Microsoft.ContainerRegistry/registries/" + name
                        + "?api-version=2025-11-01")
                .then().statusCode(anyOf(is(200), is(201)));
    }

    /** Pushes an empty image, which is enough to make the repository exist in the catalog. */
    private static void push(String registry, String repository) {
        String host = loginServer(registry);
        String config = "{}";
        String digest = "sha256:" + sha256(config);

        String location = given().header("Host", host)
                .when().post("/v2/" + repository + "/blobs/uploads/")
                .then().statusCode(202)
                .extract().header("Location");
        assertNotNull(location, "no upload Location for " + registry + "/" + repository);
        assertFalse(location.contains(registry + "/"),
                "the upload Location leaked the internal prefix: " + location);

        // The upload session carries an opaque _state that the registry expects back byte for byte,
        // so the query must not be re-encoded on the way out.
        given().header("Host", host).urlEncodingEnabled(false)
                .contentType("application/octet-stream")
                .body(config.getBytes(StandardCharsets.UTF_8))
                .when().put(location + (location.contains("?") ? "&" : "?") + "digest=" + digest)
                .then().statusCode(201);

        String manifest = "{\"schemaVersion\":2,"
                + "\"mediaType\":\"application/vnd.docker.distribution.manifest.v2+json\","
                + "\"config\":{\"mediaType\":\"application/vnd.docker.container.image.v1+json\","
                + "\"size\":" + config.length() + ",\"digest\":\"" + digest + "\"},\"layers\":[]}";
        given().header("Host", host)
                .contentType("application/vnd.docker.distribution.manifest.v2+json").body(manifest)
                .when().put("/v2/" + repository + "/manifests/v1")
                .then().statusCode(201);
    }

    private static Response catalogResponse(String query) {
        return given().header("Host", loginServer(REGISTRY))
                .when().get("/v2/_catalog" + query)
                .then().statusCode(200)
                .extract().response();
    }

    private static List<String> catalog(String query) {
        return repositories(catalogResponse(query));
    }

    private static List<String> repositories(Response response) {
        List<String> repositories = response.jsonPath().getList("repositories");
        return repositories == null ? List.of() : repositories;
    }

    /** The query of a {@code Link} header's next page, or {@code null} when there is none. */
    private static String nextQuery(String link) {
        if (link == null) {
            return null;
        }
        int start = link.indexOf('?');
        int end = link.indexOf('>', start);
        return start < 0 || end < 0 ? null : link.substring(start, end);
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
