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
 * and one index of seven documents; the filter, sort, facet and scoring function tests share a second index of four,
 * and the tests that change an index use one of their own.
 */
@QuarkusTest
@TestProfile(SearchSolrDockerTest.WithSolr.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Azure AI Search: queries run by the Solr sidecar (Docker required)")
class SearchSolrDockerTest {

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

    private static final String INVENTORY = """
            {"name": "inventory", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "name", "type": "Edm.String"},
              {"name": "category", "type": "Edm.String", "searchable": false, "normalizer": "lowercase"},
              {"name": "stars", "type": "Edm.Int32"},
              {"name": "rating", "type": "Edm.Double"},
              {"name": "opened", "type": "Edm.DateTimeOffset"},
              {"name": "parking", "type": "Edm.Boolean"},
              {"name": "location", "type": "Edm.GeographyPoint"},
              {"name": "tags", "type": "Collection(Edm.String)"},
              {"name": "scores", "type": "Collection(Edm.Int32)"},
              {"name": "rooms", "type": "Collection(Edm.ComplexType)", "fields": [
                {"name": "type", "type": "Edm.String"},
                {"name": "price", "type": "Edm.Double"},
                {"name": "beds", "type": "Collection(Edm.ComplexType)", "fields": [
                  {"name": "size", "type": "Edm.String"}]}]}],
             "scoringProfiles": [
              {"name": "rated", "functions": [{"type": "magnitude", "fieldName": "rating", "boost": 3,
                "magnitude": {"boostingRangeStart": 0, "boostingRangeEnd": 5}}]},
              {"name": "near", "functions": [{"type": "distance", "fieldName": "location", "boost": 5,
                "distance": {"referencePointParameter": "here", "boostingDistance": 100}}]},
              {"name": "tagged", "functions": [{"type": "tag", "fieldName": "tags", "boost": 4,
                "tag": {"tagsParameter": "wanted"}}]}]}""";

    private boolean loaded;
    private boolean inventoryLoaded;

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

    /** Loads the second shared index once, before the first test that needs it. */
    private void inventory() {
        if (inventoryLoaded) {
            return;
        }
        inventoryLoaded = true;
        createIndex(INVENTORY);
        upload("inventory", """
                {"id": "a", "name": "Alpha inn", "category": "Budget", "stars": 3, "rating": 3.5,
                 "opened": "2020-03-15T10:00:00Z", "parking": true,
                 "location": {"type": "Point", "coordinates": [24.94, 60.17]}, "tags": ["wifi", "pool"],
                 "scores": [1, 9],
                 "rooms": [{"type": "Suite", "price": 300, "beds": [{"size": "king"}]},
                           {"type": "Single", "price": 80}]},
                {"id": "b", "name": "beta lodge", "category": "LUXURY", "stars": 5, "rating": 4.8,
                 "opened": "2021-07-01T00:00:00Z", "parking": false,
                 "location": {"type": "Point", "coordinates": [2.35, 48.85]}, "tags": ["spa"], "scores": [5],
                 "rooms": [{"type": "Suite", "price": 90, "beds": [{"size": "queen"}]}]},
                {"id": "c", "name": "Gamma hotel", "category": "luxury", "stars": 4,
                 "opened": "2021-07-20T00:00:00Z", "tags": ["wifi"], "rooms": []},
                {"id": "d", "name": "delta", "stars": 1, "rating": 1.0, "parking": true, "tags": []}""");
    }

    private List<String> filtered(String filter) {
        inventory();
        return search("inventory", "{\"filter\": \"" + filter.replace("\"", "\\\"") + "\", \"select\": \"id\"}")
            .then().statusCode(200).extract().jsonPath().getList("value.id", String.class);
    }

    private Response inventorySearch(String body) {
        inventory();
        return search("inventory", body);
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
        search("{\"search\": \"luxury\", \"highlight\": \"title-1\", \"select\": \"id\"}").then()
            .statusCode(200)
            .body("value.find { it.id == '2' }.'@search.highlights'.title", contains("<em>Luxury</em> hotel"));
    }

    @Test
    void aScoringProfileWeighsItsFields() {
        search("{\"search\": \"sunset quiet\", \"scoringProfile\": \"titleFirst\"}").then().statusCode(200)
            .body("value.id", contains("6", "5"));
        search("{\"search\": \"sunset\", \"scoringProfile\": \"tagsFirst\"}").then().statusCode(200)
            .body("value.id", contains("5", "6"));
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

    // ── Filters, sorting, facets and scoring functions ───────────────────────

    @Test
    void aFilterComparesNumbersDatesStringsAndBooleansAndKeepsNullApart() {
        assertThat(filtered("stars ge 4"), containsInAnyOrder("b", "c"));
        assertThat(filtered("stars gt 3.5"), containsInAnyOrder("b", "c"));
        assertThat(filtered("5 le stars"), contains("b"));
        assertThat(filtered("rating lt 4 and parking"), containsInAnyOrder("a", "d"));
        assertThat(filtered("not (stars gt 3) or rating eq null"), containsInAnyOrder("a", "c", "d"));
        // ne is true for a document without a value, as Learn's table has it.
        assertThat(filtered("rating ne 4.8"), containsInAnyOrder("a", "c", "d"));
        assertThat(filtered("opened ge 2021-01-01T00:00:00Z"), containsInAnyOrder("b", "c"));
        assertThat(filtered("opened lt 2021-07-10T00:00:00+02:00"), containsInAnyOrder("a", "b"));
        assertThat(filtered("name eq 'beta lodge'"), contains("b"));
        assertThat(filtered("name eq 'Beta lodge'"), hasSize(0));
        // The lowercase normalizer applies to the values and to the constant.
        assertThat(filtered("category eq 'LUXURY'"), containsInAnyOrder("b", "c"));
        assertThat(filtered("search.in(category, 'budget|Luxury', '|')"), containsInAnyOrder("a", "b", "c"));
        assertThat(filtered("parking eq false"), contains("b"));
    }

    @Test
    void aLambdaTestsOneElementOfACollectionAtATime() {
        assertThat(filtered("tags/any(t: t eq 'wifi')"), containsInAnyOrder("a", "c"));
        assertThat(filtered("tags/any()"), containsInAnyOrder("a", "b", "c"));
        assertThat(filtered("not tags/any()"), contains("d"));
        assertThat(filtered("tags/all(t: t ne 'wifi')"), containsInAnyOrder("b", "d"));
        // a's scores are 1 and 9: neither is between 2 and 8.
        assertThat(filtered("scores/any(s: s gt 2 and s lt 8)"), contains("b"));
        // a has a suite and a room under 100, but not one room that is both.
        assertThat(filtered("rooms/any(r: r/type eq 'Suite' and r/price lt 100)"), contains("b"));
        assertThat(filtered("rooms/all(r: r/price gt 85)"), containsInAnyOrder("b", "c", "d"));
        assertThat(filtered("rooms/any(r: r/beds/any(b: b/size eq 'king'))"), contains("a"));
        // A child document is never a result of its own.
        inventorySearch("{\"search\": \"*\", \"count\": true}").then().statusCode(200)
            .body("'@odata.count'", equalTo(4));
    }

    @Test
    void geoDistanceFiltersAndSortsInKilometres() {
        assertThat(filtered("geo.distance(location, geography'POINT(24.9 60.2)') lt 10"), contains("a"));
        assertThat(filtered("geo.distance(location, geography'POINT(24.9 60.2)') gt 10"), contains("b"));
        assertThat(filtered("geo.distance(location, geography'POINT(24.9 60.2)') lt 0"), hasSize(0));
        assertThat(filtered("geo.distance(location, geography'POINT(24.9 60.2)') ge 0"),
                containsInAnyOrder("a", "b"));
        List<String> nearest = inventorySearch("{\"orderby\": \"geo.distance(location,"
                + " geography'POINT(2.3 48.8)')\", \"select\": \"id\"}").then().statusCode(200)
            .extract().jsonPath().getList("value.id", String.class);
        assertThat(nearest.subList(0, 2), contains("b", "a"));
    }

    @Test
    void searchIsMatchFiltersByTextAndIsMatchScoringAlsoScores() {
        assertThat(filtered("search.ismatch('lodge', 'name')"), contains("b"));
        JsonPath answer = inventorySearch("{\"filter\": \"search.ismatchscoring('lodge') or stars eq 1\","
                + " \"select\": \"id\"}").then().statusCode(200).extract().jsonPath();
        assertThat(answer.getList("value.id", String.class), contains("b", "d"));
        assertThat(answer.getFloat("value[0].'@search.score'"), greaterThan(0f));
        assertThat(answer.getFloat("value[1].'@search.score'"), equalTo(0f));
    }

    @Test
    void orderByPutsNullsFirstAscendingAndLastDescending() {
        assertThat(ordered("rating desc"), contains("b", "a", "d", "c"));
        assertThat(ordered("rating"), contains("c", "d", "a", "b"));
        // Ordinal: upper case before lower case.
        assertThat(ordered("name asc"), contains("a", "c", "b", "d"));
        assertThat(ordered("category asc, stars desc"), contains("d", "a", "b", "c"));
        assertThat(ordered("parking desc, stars"), contains("d", "a", "b", "c"));
    }

    private List<String> ordered(String orderBy) {
        return inventorySearch("{\"orderby\": \"" + orderBy + "\", \"select\": \"id\"}").then().statusCode(200)
            .extract().jsonPath().getList("value.id", String.class);
    }

    @Test
    void facetsCountTheDocumentsByValueRangeAndInterval() {
        JsonPath facets = inventorySearch("{\"top\": 0, \"facets\": [\"category\", \"tags,sort:value\","
                + " \"stars,values:2|4\", \"rating,interval:2\", \"opened,interval:month\", \"rooms/type\","
                + " \"parking\"]}").then().statusCode(200)
            .body("value", hasSize(0))
            .extract().jsonPath();
        assertThat(facets.getList("'@search.facets'.category.value"), contains("luxury", "budget"));
        assertThat(facets.getList("'@search.facets'.category.count"), contains(2, 1));
        assertThat(facets.getList("'@search.facets'.tags.value"), contains("pool", "spa", "wifi"));
        assertThat(facets.getList("'@search.facets'.stars"), contains(Map.of("to", 2, "count", 1),
                Map.of("from", 2, "to", 4, "count", 1), Map.of("from", 4, "count", 2)));
        assertThat(facets.getList("'@search.facets'.rating.value"), contains(0, 2, 4));
        assertThat(facets.getList("'@search.facets'.opened.value"), contains("2020-03-01T00:00:00Z",
                "2021-07-01T00:00:00Z"));
        assertThat(facets.getList("'@search.facets'.opened.count"), contains(1, 2));
        // Range bounds as recorded from Azure (net RangeFacets): dates with milliseconds, a double field's as doubles.
        String ranges = inventorySearch("{\"top\": 0, \"facets\": [\"opened,values:2021-01-01T00:00:00Z\","
                + " \"rating,values:2|4\"]}").then().statusCode(200).extract().asString();
        assertThat(ranges, containsString("\"to\":\"2021-01-01T00:00:00.000Z\""));
        assertThat(ranges, containsString("\"from\":2.0,\"to\":4.0"));
        // Counted per document: a has two rooms but one suite.
        assertThat(facets.getList("'@search.facets'.'rooms/type'.value"), contains("Suite", "Single"));
        assertThat(facets.getList("'@search.facets'.'rooms/type'.count"), contains(2, 1));
        assertThat(facets.getList("'@search.facets'.parking.value"), contains(true, false));

        inventorySearch("{\"filter\": \"stars ge 4\", \"facets\": [\"category\"]}").then().statusCode(200)
            .body("'@search.facets'.category.value", contains("luxury"));
        inventorySearch("{\"search\": \"*\"}").then().statusCode(200).body("$", not(hasKey("@search.facets")));
    }

    @Test
    void aNaNIsAValueButComparesWithNothingAndALargeBucketKeepsItsSize() {
        createIndex("""
                {"name": "special", "fields": [
                  {"name": "id", "type": "Edm.String", "key": true},
                  {"name": "rating", "type": "Edm.Double"},
                  {"name": "prices", "type": "Collection(Edm.Double)"},
                  {"name": "serial", "type": "Edm.Int64"}]}""");
        upload("special", """
                {"id": "nan", "rating": "NaN", "prices": ["NaN"]},
                {"id": "one", "rating": 1.0, "prices": [1.0]},
                {"id": "none"},
                {"id": "big", "rating": 1e20, "serial": 9223372036854775806}""");
        assertThat(ids("special", "rating eq null"), contains("none"));
        assertThat(ids("special", "rating ne null"), containsInAnyOrder("nan", "one", "big"));
        assertThat(ids("special", "rating gt 0"), containsInAnyOrder("one", "big"));
        assertThat(ids("special", "prices/any()"), containsInAnyOrder("nan", "one"));
        assertThat(ids("special", "prices/any(p: p ne 1)"), contains("nan"));
        assertThat(ids("special", "prices/all(p: p ge 1)"), containsInAnyOrder("one", "none", "big"));

        // A whole-number start past a long stays a double rather than wrapping. (A width of 1 at 1e20 is below a
        // double's resolution, so the width is one a double can step by.)
        search("special", "{\"top\": 0, \"facets\": [\"rating,interval:10000000000000000000\"]}").then()
            .statusCode(200)
            .body(containsString("\"value\":1.0E20"));
        // 1 to 1e20 by 1 is far past the bucket limit, so the buckets come from the distinct values; the 1e20 one
        // is empty at a double's resolution and left out.
        search("special", "{\"top\": 0, \"facets\": [\"rating,interval:1\"]}").then().statusCode(200)
            .body("'@search.facets'.rating.value", contains(1))
            .body("'@search.facets'.rating.count", contains(1));
        // A boundary of a long field keeps every digit, though it has 19.
        search("special", "{\"top\": 0, \"facets\": [\"serial,values:9223372036854775806\"]}").then()
            .statusCode(200)
            .body(containsString("{\"to\":9223372036854775806,\"count\":0}"))
            .body(containsString("{\"from\":9223372036854775806,\"count\":1}"));
    }

    private static List<String> ids(String index, String filter) {
        return search(index, "{\"filter\": \"" + filter + "\", \"select\": \"id\"}").then().statusCode(200)
            .extract().jsonPath().getList("value.id", String.class);
    }

    @Test
    void scoringFunctionsBoostByMagnitudeDistanceAndTags() {
        assertThat(boosted("{\"scoringProfile\": \"rated\"}"), contains("b", "a", "d", "c"));
        assertThat(boosted("{\"scoringProfile\": \"near\", \"scoringParameters\": [\"here-24.9,60.2\"]}")
                .getFirst(), equalTo("a"));
        assertThat(boosted("{\"scoringProfile\": \"tagged\", \"scoringParameters\": [\"wanted-spa\"]}")
                .getFirst(), equalTo("b"));
    }

    private List<String> boosted(String body) {
        return inventorySearch(body).then().statusCode(200).extract().jsonPath().getList("value.id", String.class);
    }
}
