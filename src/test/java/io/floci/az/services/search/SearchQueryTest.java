package io.floci.az.services.search;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * A search request as the data plane reads and checks it, before Solr runs it: the unit tests keep the Solr sidecar
 * off, so a request that passes every check answers 503 here. {@code SearchSolrDockerTest} runs the searches
 * themselves.
 */
@QuarkusTest
@DisplayName("Azure AI Search: search requests")
class SearchQueryTest {

    private static final String SERVICE = "querysvc";
    private static final String BASE = "/" + SERVICE + "-search";
    private static final String API = "?api-version=2025-09-01";
    private static final String DOCS = BASE + "/indexes('hotels')/docs";
    private static final String SEARCH = DOCS + "/search.post.search" + API;

    private static final String HOTELS = """
            {"name": "hotels", "fields": [
              {"name": "id", "type": "Edm.String", "key": true, "searchable": false},
              {"name": "title", "type": "Edm.String"},
              {"name": "rating", "type": "Edm.Double"},
              {"name": "secret", "type": "Edm.String", "retrievable": false},
              {"name": "code", "type": "Edm.String", "searchable": false},
              {"name": "spot", "type": "Edm.GeographyPoint"},
              {"name": "note", "type": "Edm.String", "filterable": false, "sortable": false, "facetable": false}],
             "scoringProfiles": [
              {"name": "near", "functions": [{"type": "distance", "fieldName": "spot", "boost": 5,
                "distance": {"referencePointParameter": "here", "boostingDistance": 100}}]}]}""";

    @BeforeEach
    void createTheIndex() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body(HOTELS).when().post(BASE + "/indexes" + API)
            .then().statusCode(201);
    }

    private static Response search(String body) {
        return given().contentType("application/json").body(body).when().post(SEARCH);
    }

    @Test
    void aSearchAnswers503WhenTheSolrSidecarIsOff() {
        search("{\"search\": \"luxury\"}").then().statusCode(503)
            .body("error.code", equalTo("ServiceUnavailable"))
            .body("error.message", containsString("floci-az.services.search.engine.enabled=false"));
        given().when().get(DOCS + API + "&search=luxury").then().statusCode(503);

        // Also with no searchable field to match the text in.
        given().contentType("application/json")
            .body("{\"name\": \"keys\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true,"
                    + " \"searchable\": false}]}")
            .when().post(BASE + "/indexes" + API).then().statusCode(201);
        given().contentType("application/json").body("{\"search\": \"luxury\"}")
            .when().post(BASE + "/indexes('keys')/docs/search.post.search" + API).then().statusCode(503);
    }

    @Test
    void whatIsNotServedYetIsRefused() {
        for (String body : new String[] {"{\"queryType\": \"semantic\"}",
                "{\"vectorQueries\": [{\"kind\": \"vector\"}]}", "{\"answers\": \"extractive\"}",
                "{\"filter\": \"geo.intersects(spot, geography'POLYGON((1 1, 2 1, 2 2, 1 1))')\"}"}) {
            search(body).then().statusCode(400).body("error.code", equalTo("FeatureNotSupportedInService"));
        }
    }

    @Test
    void searchFieldsNameSearchableFieldsAndHighlightRetrievableOnesToo() {
        search("{\"search\": \"x\", \"searchFields\": \"nope\"}").then().statusCode(400)
            .body("error.message", containsString("Could not find a property named 'nope'"));
        search("{\"search\": \"x\", \"searchFields\": \"code\"}").then().statusCode(400)
            .body("error.message", containsString("'code' is not a searchable field"));
        search("{\"search\": \"x\", \"searchFields\": \"rating\"}").then().statusCode(400);
        search("{\"search\": \"x\", \"highlight\": \"code\"}").then().statusCode(400)
            .body("error.message", containsString("Parameter name: highlight"));
        search("{\"search\": \"x\", \"highlight\": \"secret\"}").then().statusCode(400)
            .body("error.message", containsString("'secret' is not a retrievable field"));
        // Learn, "Hit highlighting": a dash and a number set the field's limit.
        search("{\"search\": \"x\", \"highlight\": \"title-3\"}").then().statusCode(503);
        search("{\"search\": \"x\", \"highlight\": \"title-x\"}").then().statusCode(400)
            .body("error.message", containsString("Could not find a property named 'title-x'"));
    }

    @Test
    void aFilterSortOrFacetIsCheckedBeforeSolrRunsIt() {
        search("{\"filter\": \"rating gt 3\"}").then().statusCode(503);
        search("{\"filter\": \"note eq 'x'\"}").then().statusCode(400)
            .body("error.message", containsString("'note' is not a filterable field"))
            .body("error.message", containsString("Parameter name: $filter"));
        given().when().get(DOCS + API + "&$filter=rating%20gt").then().statusCode(400)
            .body("error.message", containsString("Syntax error"));
        search("{\"orderby\": \"rating desc, title\"}").then().statusCode(503);
        search("{\"orderby\": \"note\"}").then().statusCode(400)
            .body("error.message", containsString("Parameter name: $orderby"));
        search("{\"facets\": [\"rating,interval:1\"]}").then().statusCode(503);
        search("{\"facets\": [\"note\"]}").then().statusCode(400)
            .body("error.message", containsString("'note' is not a facetable field"));
        search("{\"facets\": [\"rating,interval:0.5\"]}").then().statusCode(400)
            .body("error.message", containsString("must be an integer greater than zero"));
        search("{\"facets\": [\"title,sort:sideways\"]}").then().statusCode(400);
        search("{\"facets\": [\"spot\"]}").then().statusCode(400);
        search("{\"facets\": [\"rating,count:2,values:1|2\"]}").then().statusCode(400);
        given().when().get(DOCS + API + "&facet=rating,values:5|1").then().statusCode(400)
            .body("error.message", containsString("ascending"));
    }

    @Test
    void anUnknownScoringProfileOrOneWithoutItsParametersIsRefused() {
        search("{\"search\": \"x\", \"scoringProfile\": \"nope\"}").then().statusCode(400)
            .body("error.message", containsString("'nope'"));
        search("{\"search\": \"x\", \"scoringProfile\": \"near\"}").then().statusCode(400)
            .body("error.message", containsString("'here'"));
        search("{\"search\": \"x\", \"scoringProfile\": \"near\", \"scoringParameters\": [\"here-24.9,60.2\"]}")
            .then().statusCode(503);
    }

    @Test
    void aValueOfTheWrongTypeOrOutOfRangeIsRefused() {
        search("{\"top\": \"x\"}").then().statusCode(400)
            .body("error.message", containsString("Cannot convert the literal 'x' to the expected type 'Edm.Int32'"));
        search("{\"count\": 1}").then().statusCode(400);
        search("{\"searchMode\": \"some\"}").then().statusCode(400);
        search("{\"queryType\": \"fancy\"}").then().statusCode(400);
        search("{\"top\": -1}").then().statusCode(400);
        search("{\"skip\": 100001}").then().statusCode(400);
        search("{\"minimumCoverage\": 100.5}").then().statusCode(400);
        search("{\"minimumCoverage\": -1}").then().statusCode(400);
        given().when().get(DOCS + API + "&minimumCoverage=101").then().statusCode(400);
        search("{\"search\": \"x\", \"highlight\": \"title\", \"highlightPreTag\": \"<b>\"}").then().statusCode(400);
        given().when().get(DOCS + API + "&highlightPostTag=x").then().statusCode(400);
        given().when().get(DOCS + API + "&$top=x").then().statusCode(400);
        search("[]").then().statusCode(400);
    }

    @Test
    void aSearchOfAMissingIndexIsNotFoundAndTheWrongMethodIsRefused() {
        given().contentType("application/json").body("{}")
            .when().post(BASE + "/indexes('nope')/docs/search.post.search" + API).then().statusCode(404)
            .body("error.message", containsString("No index with the name 'nope'"));
        given().when().get(SEARCH).then().statusCode(405);
        given().contentType("application/json").body("{}").when().post(DOCS + API).then().statusCode(405);
    }
}
