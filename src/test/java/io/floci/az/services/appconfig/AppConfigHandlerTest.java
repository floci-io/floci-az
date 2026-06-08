package io.floci.az.services.appconfig;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the 2024-09-01 parity features layered onto App Configuration:
 * pagination, Sync-Token, $select projection, tags filtering, Accept-Datetime
 * time-travel, and async snapshot provisioning.
 */
@QuarkusTest
public class AppConfigHandlerTest {

    private static final String ACCT = "acct";
    private static final String BASE = "/" + ACCT + "-appconfig";
    private static final String API = "?api-version=2024-09-01";

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    private void putKv(String key, String label, String body) {
        String url = BASE + "/kv/" + key + API + (label == null ? "" : "&label=" + label);
        given().contentType("application/json").body(body)
                .when().put(url)
                .then().statusCode(200);
    }

    @Test
    void syncTokenHeaderPresentOnEveryResponse() {
        putKv("k1", null, "{\"value\":\"v1\"}");
        given().when().get(BASE + "/kv/k1" + API)
                .then().statusCode(200)
                .header("Sync-Token", notNullValue());
        given().when().get(BASE + "/kv" + API)
                .then().statusCode(200)
                .header("Sync-Token", notNullValue());
    }

    @Test
    void selectProjectsOnlyRequestedFields() {
        putKv("sel", null, "{\"value\":\"hello\",\"content_type\":\"text/plain\"}");
        given().when().get(BASE + "/kv/sel" + API + "&$Select=key,value")
                .then().statusCode(200)
                .body("key", is("sel"))
                .body("value", is("hello"))
                .body("content_type", nullValue())
                .body("etag", nullValue());
    }

    @Test
    void tagsFilterMatchesAllProvidedTags() {
        putKv("prod1", null, "{\"value\":\"a\",\"tags\":{\"env\":\"prod\",\"tier\":\"web\"}}");
        putKv("prod2", null, "{\"value\":\"b\",\"tags\":{\"env\":\"prod\"}}");
        putKv("dev1", null, "{\"value\":\"c\",\"tags\":{\"env\":\"dev\"}}");

        // Single tag → two matches
        given().when().get(BASE + "/kv" + API + "&tags=env=prod")
                .then().statusCode(200)
                .body("items", hasSize(2));

        // Two tags (AND) → one match
        given().when().get(BASE + "/kv" + API + "&tags=env=prod&tags=tier=web")
                .then().statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].key", is("prod1"));
    }

    @Test
    void paginationReturnsNextLinkAndContinues() {
        for (int i = 0; i < 150; i++) {
            putKv(String.format("page-%03d", i), null, "{\"value\":\"v\"}");
        }

        Response first = given().when().get(BASE + "/kv" + API + "&key=page-*")
                .then().statusCode(200)
                .header("Sync-Token", notNullValue())
                .header("Link", notNullValue())
                .body("items", hasSize(100))
                .extract().response();

        String nextLink = first.jsonPath().getString("@nextLink");
        assertNotNull(nextLink, "first page must carry @nextLink");
        assertTrue(nextLink.startsWith("/kv?"), "nextLink should be relative to the resource: " + nextLink);

        // The SDK follows @nextLink verbatim against the endpoint base.
        Response second = given().when().get(BASE + nextLink)
                .then().statusCode(200)
                .body("items", hasSize(50))
                .extract().response();
        assertEquals("page-100", second.jsonPath().getString("items[0].key"));
        assertEquals(null, second.jsonPath().getString("@nextLink"));
    }

    @Test
    void acceptDatetimeReturnsHistoricalValue() throws InterruptedException {
        putKv("tt", null, "{\"value\":\"old\"}");
        Thread.sleep(10);
        Instant between = Instant.now();
        Thread.sleep(10);
        putKv("tt", null, "{\"value\":\"new\"}");

        // Current read → new value
        given().when().get(BASE + "/kv/tt" + API)
                .then().statusCode(200).body("value", is("new"));

        // As-of read between the two writes → old value
        String asOf = DateTimeFormatter.ISO_INSTANT.format(between);
        given().header("Accept-Datetime", asOf)
                .when().get(BASE + "/kv/tt" + API)
                .then().statusCode(200).body("value", is("old"));
    }

    @Test
    void snapshotProvisioningCompletesViaOperations() {
        putKv("s1", null, "{\"value\":\"v\"}");

        // Create → provisioning + Operation-Location
        given().contentType("application/json")
                .body("{\"filters\":[{\"key\":\"*\"}],\"composition_type\":\"key\"}")
                .when().put(BASE + "/snapshots/snap1" + API)
                .then().statusCode(201)
                .header("Operation-Location", notNullValue())
                .body("status", is("provisioning"));

        // Poll operation → Succeeded
        given().when().get(BASE + "/operations" + API + "&snapshot=snap1")
                .then().statusCode(200)
                .body("status", is("Succeeded"));

        // Snapshot is now ready
        given().when().get(BASE + "/snapshots/snap1" + API)
                .then().statusCode(200)
                .body("status", is("ready"));
    }
}
