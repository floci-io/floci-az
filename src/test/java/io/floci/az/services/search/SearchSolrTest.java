package io.floci.az.services.search;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Searches run by a real Solr sidecar: the one test class that pulls and starts it. Every test shares one container
 * and one index of seven documents; the tests that change an index use one of their own.
 */
@QuarkusTest
@TestProfile(SearchSolrTest.WithSolr.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Azure AI Search: queries run by the Solr sidecar (Docker required)")
class SearchSolrTest {

    /** Turns on the engine the test profile otherwise keeps off. */
    public static class WithSolr implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("%test.floci-az.services.search.engine.enabled", "true");
        }
    }

    private static final String SERVICE = "solrsvc";
    private static final String BASE = "/" + SERVICE + "-search";
    private static final String API = "?api-version=2025-09-01";
    private static final String DOCS = BASE + "/indexes('hotels')/docs";
    private static final String SEARCH = DOCS + "/search.post.search" + API;

    private static final String HOTELS = """
            {"name": "hotels", "fields": [
              {"name": "id", "type": "Edm.String", "key": true, "searchable": false},
              {"name": "title", "type": "Edm.String"},
              {"name": "description", "type": "Edm.String", "analyzer": "en.lucene"},
              {"name": "tags", "type": "Collection(Edm.String)"},
              {"name": "rating", "type": "Edm.Double"},
              {"name": "secret", "type": "Edm.String", "retrievable": false},
              {"name": "code", "type": "Edm.String", "searchable": false},
              {"name": "address", "type": "Edm.ComplexType", "fields": [
                {"name": "city", "type": "Edm.String"}]},
              {"name": "rooms", "type": "Collection(Edm.ComplexType)", "fields": [
                {"name": "kind", "type": "Edm.String"}]}],
             "scoringProfiles": [
              {"name": "titleFirst", "text": {"weights": {"title": 5}}},
              {"name": "tagsFirst", "text": {"weights": {"tags": 5}}}]}""";

    private boolean loaded;

    /** A filesystem check only: Quarkus is not listening yet when this runs. */
    @BeforeAll
    void needsDocker() {
        boolean docker = Files.exists(Paths.get("/var/run/docker.sock")) || System.getenv("DOCKER_HOST") != null;
        assumeTrue(docker, "Docker is not available: skipping the queries the Solr sidecar runs");
    }

    /** Loads the shared index once, before the first test. */
    @BeforeEach
    void loadTheHotelsOnce() {
        if (loaded) {
            return;
        }
        loaded = true;
        given().post("/_admin/reset").then().statusCode(204);
        createIndex(HOTELS);
        upload("hotels", """
                {"id": "1", "title": "Seaside hotel with wifi", "tags": ["pool", "wifi"], "rating": 4.0,
                 "secret": "hidden words", "code": "luxury", "address": {"city": "Helsinki"},
                 "description": "Guests are running on the beach", "rooms": [{"kind": "suite"}, {"kind": "double"}]},
                {"id": "2", "title": "Luxury hotel", "tags": ["luxury", "wifi", "spa"], "rating": 5.0},
                {"id": "3", "title": "Budget hostel", "tags": ["cheap"], "rating": 2.5,
                 "address": {"city": "Tampere"}},
                {"id": "4", "title": "Luxury resort and spa", "tags": ["luxury", "beach"], "rating": 4.5},
                {"id": "5", "title": "Quiet place", "tags": ["sunset"]},
                {"id": "6", "title": "Sunset inn", "tags": ["quiet"]},
                {"id": "7", "title": "Motel", "description": "A motel that runs a small cafe"}""");
    }

    private static void createIndex(String definition) {
        given().contentType("application/json").body(definition).when().post(BASE + "/indexes" + API)
            .then().statusCode(201);
    }

    private static void upload(String index, String documents) {
        given().contentType("application/json").body("{\"value\": [" + documents + "]}")
            .when().post(BASE + "/indexes('" + index + "')/docs/search.index" + API).then().statusCode(200);
    }

    private static Response search(String body) {
        return search("hotels", body);
    }

    private static Response search(String index, String body) {
        return given().contentType("application/json").body(body)
            .when().post(BASE + "/indexes('" + index + "')/docs/search.post.search" + API);
    }

    @Test
    void aSearchAnswersTheMatchingDocumentsBestFirstWithTheirScores() {
        JsonPath answer = search("{\"search\": \"luxury\"}").then().statusCode(200)
            .contentType("application/json; odata.metadata=minimal; odata.streaming=true; charset=utf-8")
            .body("'@odata.context'", endsWith(BASE + "/indexes('hotels')/$metadata#docs(*)"))
            .body("value.id", containsInAnyOrder("2", "4"))
            .body("value[0].keySet()", contains("@search.score", "id", "title", "description", "tags", "rating",
                    "code", "address", "rooms"))
            .body("$", not(hasKey("@odata.count")))
            .body("$", not(hasKey("@search.nextPageParameters")))
            .extract().jsonPath();
        List<Float> scores = answer.getList("value.'@search.score'", Float.class);
        assertThat(scores.get(0), greaterThanOrEqualTo(scores.get(1)));
        assertThat(scores.get(1), greaterThan(0f));
    }

    @Test
    void aStarMatchesEveryDocumentWithTheScoreOneAndCountsThem() {
        search("{\"search\": \"*\", \"count\": true}").then().statusCode(200)
            .body("'@odata.count'", equalTo(7))
            .body("value", hasSize(7))
            .body("value.'@search.score'", everyItem(equalTo(1.0f)));
        search("{}").then().statusCode(200).body("value", hasSize(7));
    }

    @Test
    void theGetFormSearchesAsThePostFormDoes() {
        given().when().get(DOCS + API + "&search=luxury&$count=true&$select=id,rating").then().statusCode(200)
            .body("'@odata.count'", equalTo(2))
            .body("value.id", containsInAnyOrder("2", "4"))
            .body("value[0].keySet()", contains("@search.score", "id", "rating"));
    }

    @Test
    void searchModeAllNeedsEveryTermAndAnyTakesANegationAsOr() {
        search("{\"search\": \"luxury pool\", \"searchMode\": \"all\"}").then().statusCode(200)
            .body("value", hasSize(0));
        search("{\"search\": \"hotel wifi\", \"searchMode\": \"all\"}").then().statusCode(200)
            .body("value.id", containsInAnyOrder("1", "2"));
        // Azure's any: wifi OR NOT luxury, so every document without luxury matches too, with the score 1.
        search("{\"search\": \"wifi -luxury\"}").then().statusCode(200)
            .body("value.id", containsInAnyOrder("1", "2", "3", "5", "6", "7"));
        search("{\"search\": \"wifi -luxury\", \"searchMode\": \"all\"}").then().statusCode(200)
            .body("value.id", contains("1"));
    }

    @Test
    void phrasesAndPrefixesMatchAndTheFullSyntaxOperatorsAreNotSimpleOnes() {
        search("{\"search\": \"\\\"luxury hotel\\\"\"}").then().statusCode(200).body("value.id", contains("2"));
        search("{\"search\": \"hos*\"}").then().statusCode(200).body("value.id", contains("3"));
        // Fuzzy matching belongs to the full syntax: in the simple one "luxary~1" is a plain term.
        search("{\"search\": \"luxary~1\"}").then().statusCode(200).body("value", hasSize(0));
    }

    @Test
    void onlySearchableFieldsAreSearchedAndSearchFieldsNarrowsThem() {
        // "luxury" is also 1's code, which is not searchable; 1's secret is searchable but not retrievable.
        search("{\"search\": \"hidden\"}").then().statusCode(200).body("value.id", contains("1"))
            .body("value[0]", not(hasKey("secret")));
        search("{\"search\": \"spa\", \"searchFields\": \"title\"}").then().statusCode(200)
            .body("value.id", contains("4"));
        search("{\"search\": \"helsinki\", \"searchFields\": \"address/city\"}").then().statusCode(200)
            .body("value.id", contains("1"));
        search("{\"search\": \"suite\", \"searchFields\": \"rooms/kind\"}").then().statusCode(200)
            .body("value.id", contains("1"))
            .body("value[0].rooms.kind", contains("suite", "double"));
    }

    @Test
    void aLanguageAnalyzerStemsAsLucenesDoes() {
        search("{\"search\": \"run\", \"searchFields\": \"description\"}").then().statusCode(200)
            .body("value.id", containsInAnyOrder("1", "7"));
        search("{\"search\": \"run\", \"searchFields\": \"title\"}").then().statusCode(200)
            .body("value", hasSize(0));
    }

    @Test
    void theFullSyntaxTakesFieldsFuzzinessRegularExpressionsAndBooleans() {
        search("{\"search\": \"title:luxury\", \"queryType\": \"full\"}").then().statusCode(200)
            .body("value.id", containsInAnyOrder("2", "4"));
        search("{\"search\": \"address/city:tampere\", \"queryType\": \"full\"}").then().statusCode(200)
            .body("value.id", contains("3"));
        search("{\"search\": \"luxary~1\", \"queryType\": \"full\"}").then().statusCode(200)
            .body("value.id", containsInAnyOrder("2", "4"));
        search("{\"search\": \"/hos.*/\", \"queryType\": \"full\"}").then().statusCode(200)
            .body("value.id", contains("3"));
        search("{\"search\": \"luxury AND NOT spa\", \"queryType\": \"full\", \"searchFields\": \"title\"}")
            .then().statusCode(200).body("value.id", contains("2"));
        search("{\"search\": \"\\\"seaside wifi\\\"~3\", \"queryType\": \"full\"}").then().statusCode(200)
            .body("value.id", contains("1"));
    }

    @Test
    void highlightsMarkTheMatchesOfTheNamedFields() {
        search("{\"search\": \"luxury\", \"highlight\": \"title,tags\", \"select\": \"id\"}").then()
            .statusCode(200)
            .body("value.find { it.id == '2' }.'@search.highlights'.title", contains("<em>Luxury</em> hotel"))
            .body("value.find { it.id == '2' }.'@search.highlights'.tags", contains("<em>luxury</em>"));
        search("{\"search\": \"helsinki\", \"highlight\": \"address/city\", \"highlightPreTag\": \"[\","
                + " \"highlightPostTag\": \"]\"}").then().statusCode(200)
            .body("value[0].'@search.highlights'.'address/city'", contains("[Helsinki]"));
        search("{\"search\": \"luxury\"}").then().statusCode(200)
            .body("value[0]", not(hasKey("@search.highlights")));
    }

    @Test
    void aScoringProfileWeighsItsFields() {
        search("{\"search\": \"sunset quiet\", \"scoringProfile\": \"titleFirst\"}").then().statusCode(200)
            .body("value.id", contains("6", "5"));
        search("{\"search\": \"sunset\", \"scoringProfile\": \"tagsFirst\"}").then().statusCode(200)
            .body("value.id", contains("5", "6"));
        search("{\"search\": \"sunset\", \"scoringProfile\": \"nope\"}").then().statusCode(400)
            .body("error.message", containsString("nope"));
    }

    @Test
    void selectAndTopAndSkipShapeTheResults() {
        search("{\"search\": \"luxury hotel\", \"select\": \"id\", \"top\": 1, \"skip\": 1, \"count\": true}")
            .then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].keySet()", contains("@search.score", "id"))
            .body("'@odata.count'", equalTo(3))
            // The page holds all the top asked for, so there is no next page, though more documents match.
            .body("$", not(hasKey("@search.nextPageParameters")));
        search("{\"search\": \"*\", \"top\": 0, \"count\": true}").then().statusCode(200)
            .body("value", hasSize(0)).body("'@odata.count'", equalTo(7));
        search("{\"search\": \"*\", \"skip\": 10}").then().statusCode(200).body("value", hasSize(0));
        search("{\"search\": \"luxury\", \"sessionId\": \"s\", \"scoringStatistics\": \"global\","
                + " \"minimumCoverage\": 80, \"facets\": [], \"answers\": \"none\", \"aNewerProperty\": true}")
            .then().statusCode(200)
            .body("value", hasSize(2))
            .body("'@search.coverage'", equalTo(100.0f));
    }

    @Test
    void withoutTopAPageHoldsFiftyAndTheAnswerSaysWhereTheNextStarts() {
        createIndex("""
                {"name": "paged", "fields": [
                  {"name": "id", "type": "Edm.String", "key": true},
                  {"name": "title", "type": "Edm.String"}]}""");
        upload("paged", IntStream.range(10, 70).mapToObj(i -> "{\"id\": \"p" + i + "\", \"title\": \"paged\"}")
                .collect(Collectors.joining(",")));
        JsonPath first = search("paged", "{\"search\": \"paged\", \"count\": true}").then().statusCode(200)
            .body("value", hasSize(50))
            .body("'@odata.count'", equalTo(60))
            .body("'@search.nextPageParameters'.search", equalTo("paged"))
            .body("'@search.nextPageParameters'.skip", equalTo(50))
            .body("'@search.nextPageParameters'", not(hasKey("top")))
            .body("'@odata.nextLink'", endsWith("/indexes('paged')/docs/search.post.search?api-version=2025-09-01"))
            .extract().jsonPath();
        Map<String, Object> next = first.getMap("'@search.nextPageParameters'");
        given().contentType("application/json").body(next)
            .when().post(BASE + "/indexes('paged')/docs/search.post.search" + API).then().statusCode(200)
            .body("value", hasSize(10))
            .body("$", not(hasKey("@odata.nextLink")));
        given().when().get(BASE + "/indexes('paged')/docs" + API + "&search=paged").then().statusCode(200)
            .body("value", hasSize(50))
            .body("'@odata.nextLink'", containsString("search=paged"))
            .body("'@odata.nextLink'", endsWith("$skip=50"));
    }

    @Test
    void aSearchSeesTheDocumentsAndTheIndexAsTheLastWriteLeftThem() {
        createIndex("""
                {"name": "changing", "fields": [
                  {"name": "id", "type": "Edm.String", "key": true},
                  {"name": "title", "type": "Edm.String"}]}""");
        upload("changing", "{\"id\": \"a\", \"title\": \"first words\"}, {\"id\": \"b\", \"title\": \"first too\"}");
        search("changing", "{\"search\": \"first\"}").then().statusCode(200).body("value.id",
                containsInAnyOrder("a", "b"));

        upload("changing", "{\"@search.action\": \"delete\", \"id\": \"a\"},"
                + " {\"@search.action\": \"merge\", \"id\": \"b\", \"title\": \"second words\"}");
        search("changing", "{\"search\": \"first\"}").then().statusCode(200).body("value", hasSize(0));
        search("changing", "{\"search\": \"second\"}").then().statusCode(200).body("value.id", contains("b"));

        // A field the update adds is searchable at once: the collection is built again from storage.
        given().contentType("application/json").body("""
                {"name": "changing", "fields": [
                  {"name": "id", "type": "Edm.String", "key": true},
                  {"name": "title", "type": "Edm.String"},
                  {"name": "note", "type": "Edm.String"}]}""")
            .when().put(BASE + "/indexes('changing')" + API).then().statusCode(200);
        upload("changing", "{\"@search.action\": \"merge\", \"id\": \"b\", \"note\": \"remark\"}");
        search("changing", "{\"search\": \"remark\"}").then().statusCode(200).body("value.id", contains("b"));

        // An index deleted and created again starts empty.
        given().when().delete(BASE + "/indexes('changing')" + API).then().statusCode(204);
        createIndex("""
                {"name": "changing", "fields": [
                  {"name": "id", "type": "Edm.String", "key": true},
                  {"name": "title", "type": "Edm.String"}]}""");
        search("changing", "{\"search\": \"*\", \"count\": true}").then().statusCode(200)
            .body("'@odata.count'", equalTo(0));
    }

    @Test
    void aSingleDocumentScoresAsLucenesBm25() {
        createIndex("""
                {"name": "single", "fields": [
                  {"name": "id", "type": "Edm.String", "key": true},
                  {"name": "title", "type": "Edm.String"}]}""");
        upload("single", "{\"id\": \"a\", \"title\": \"alone\"}");
        // idf = ln(1 + (1 - 1 + 0.5) / (1 + 0.5)); tf part = 1 / (1 + 1.2 * (1 - 0.75 + 0.75 * 1)).
        double expected = Math.log(1 + 0.5 / 1.5) / (1 + 1.2);
        float score = search("single", "{\"search\": \"alone\"}").then().statusCode(200)
            .extract().path("value[0].'@search.score'");
        assertThat((double) score, closeTo(expected, 1e-6));
    }
}
