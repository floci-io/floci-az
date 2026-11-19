package io.floci.az.services.search;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Index management on the search data plane. The operations, status codes and property names are the ones of the
 * data-plane spec ({@code searchservice.json}, 2025-09-01) and its examples.
 */
@QuarkusTest
@DisplayName("Azure AI Search: index management")
class SearchIndexesTest {

    private static final String SERVICE = "indexsvc";
    private static final String BASE = "/" + SERVICE + "-search";
    private static final String API = "?api-version=2025-09-01";
    private static final String ARM_API = "?api-version=2025-05-01";
    private static final String ARM_SERVICE = "/subscriptions/5ea4c400-0000-4000-8000-000000000002/resourceGroups/rg"
            + "/providers/Microsoft.Search/searchServices/" + SERVICE;
    private static final String ETAG_PATTERN = "\"0x[0-9A-F]+\"";

    private static final String HOTELS = """
            {"name": "hotels",
             "description": "Hotels to stay in",
             "fields": [
               {"name": "id", "type": "Edm.String", "key": true},
               {"name": "name", "type": "Edm.String", "analyzer": "en.lucene", "sortable": false},
               {"name": "rating", "type": "Edm.Double", "facetable": false},
               {"name": "tags", "type": "Collection(Edm.String)"},
               {"name": "location", "type": "Edm.GeographyPoint"},
               {"name": "address", "type": "Edm.ComplexType", "fields": [
                 {"name": "street", "type": "Edm.String"},
                 {"name": "city", "type": "Edm.String", "searchable": false}]},
               {"name": "embedding", "type": "Collection(Edm.Single)", "dimensions": 4,
                "vectorSearchProfile": "profile1"}],
             "scoringProfiles": [{"name": "boostName", "text": {"weights": {"name": 3.0}}}],
             "defaultScoringProfile": "boostName",
             "corsOptions": {"allowedOrigins": ["https://example.com"], "maxAgeInSeconds": 60},
             "suggesters": [{"name": "sg", "searchMode": "analyzingInfixMatching", "sourceFields": ["name"]}],
             "semantic": {"configurations": [{"name": "sem", "prioritizedFields":
               {"titleField": {"fieldName": "name"}}}]},
             "vectorSearch": {"profiles": [{"name": "profile1", "algorithm": "hnsw1"}],
                              "algorithms": [{"name": "hnsw1", "kind": "hnsw"}]}}""";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String minimal(String name) {
        return "{\"name\": \"" + name + "\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true}]}";
    }

    private static String described(String name, String description) {
        return "{\"name\": \"" + name + "\", \"description\": \"" + description + "\","
                + " \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true}]}";
    }

    private static String withFields(String name, String fields) {
        return "{\"name\": \"" + name + "\", \"fields\": [" + fields + "]}";
    }

    /** An index with a key, the fields given, and a vector search profile {@code p} for them to use. */
    private static String withVectorProfile(String name, String fields) {
        return "{\"name\": \"" + name + "\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true}, "
                + fields + "], \"vectorSearch\": {\"profiles\": [{\"name\": \"p\", \"algorithm\": \"a\"}],"
                + " \"algorithms\": [{\"name\": \"a\", \"kind\": \"hnsw\"}]}}";
    }

    /** A vector field of 4 dimensions on profile {@code p}, with {@code attributes} added. */
    private static String vector(String attributes) {
        return "{\"name\": \"v\", \"type\": \"Collection(Edm.Single)\", \"dimensions\": 4,"
                + " \"vectorSearchProfile\": \"p\"" + attributes + "}";
    }

    private static Response post(String body) {
        return given().contentType("application/json").body(body).when().post(BASE + "/indexes" + API);
    }

    private static Response put(String name, String body) {
        return given().contentType("application/json").header("Prefer", "return=representation").body(body)
                .when().put(BASE + "/indexes('" + name + "')" + API);
    }

    private static Response get(String name) {
        return given().when().get(BASE + "/indexes('" + name + "')" + API);
    }

    @Test
    void creatingAnIndexAnswersItsDefinitionWithTheDefaultsFilledIn() {
        post(HOTELS).then().statusCode(201)
            .contentType(containsString("application/json"))
            .header("Location", endsWith(BASE + "/indexes('hotels')" + API))
            .header("ETag", matchesPattern(ETAG_PATTERN))
            .body("'@odata.context'", endsWith(BASE + "/$metadata#indexes/$entity"))
            .body("'@odata.etag'", matchesPattern(ETAG_PATTERN))
            .body("name", equalTo("hotels"))
            .body("description", equalTo("Hotels to stay in"))
            .body("fields.name", contains("id", "name", "rating", "tags", "location", "address", "embedding"))
            // A string key: every attribute defaults to true, as in the spec's example answer.
            .body("fields[0].key", equalTo(true))
            .body("fields[0].searchable", equalTo(true))
            .body("fields[0].filterable", equalTo(true))
            .body("fields[0].retrievable", equalTo(true))
            .body("fields[0].stored", equalTo(true))
            .body("fields[0].sortable", equalTo(true))
            .body("fields[0].facetable", equalTo(true))
            .body("fields[0].synonymMaps", empty())
            // Azure answers every attribute of a simple field, null when unset, in this order.
            .body("fields[0].keySet()", contains("name", "type", "searchable", "filterable", "retrievable", "stored",
                    "sortable", "facetable", "key", "indexAnalyzer", "searchAnalyzer", "analyzer", "normalizer",
                    "dimensions", "vectorSearchProfile", "vectorEncoding", "synonymMaps"))
            .body("fields[0].analyzer", nullValue())
            .body("fields[0].dimensions", nullValue())
            // What the client sent wins over a default.
            .body("fields[1].sortable", equalTo(false))
            .body("fields[1].analyzer", equalTo("en.lucene"))
            .body("fields[1].key", equalTo(false))
            // Only strings are searchable by default.
            .body("fields[2].searchable", equalTo(false))
            .body("fields[2].filterable", equalTo(true))
            .body("fields[2].facetable", equalTo(false))
            // A collection is not sortable, and a point is not facetable.
            .body("fields[3].searchable", equalTo(true))
            .body("fields[3].sortable", equalTo(false))
            .body("fields[4].facetable", equalTo(false))
            // A complex field has sub-fields and no attributes of its own.
            .body("fields[5].keySet()", contains("name", "type", "fields"))
            .body("fields[5].fields.name", contains("street", "city"))
            .body("fields[5].fields[0].searchable", equalTo(true))
            .body("fields[5].fields[1].searchable", equalTo(false))
            // A vector field, as the spec's example answers one.
            .body("fields[6].searchable", equalTo(true))
            .body("fields[6].filterable", equalTo(false))
            .body("fields[6].sortable", equalTo(false))
            .body("fields[6].facetable", equalTo(false))
            // The spec: retrievable "Default is true for simple fields, false for vector fields".
            .body("fields[6].retrievable", equalTo(false))
            .body("fields[6].stored", equalTo(true))
            .body("fields[6].dimensions", equalTo(4))
            .body("fields[6].vectorSearchProfile", equalTo("profile1"));
    }

    @Test
    void aMinimalIndexAnswersTheSectionsItDidNotSend() {
        post(minimal("plain")).then().statusCode(201)
            .body("keySet()", contains("@odata.context", "@odata.etag", "name", "description",
                    "defaultScoringProfile", "fields", "scoringProfiles", "corsOptions", "suggesters", "analyzers",
                    "normalizers", "tokenizers", "tokenFilters", "charFilters", "encryptionKey", "similarity",
                    "semantic", "vectorSearch"))
            .body("description", nullValue())
            .body("scoringProfiles", empty())
            .body("suggesters", empty())
            .body("analyzers", empty())
            .body("tokenizers", empty())
            .body("tokenFilters", empty())
            .body("charFilters", empty())
            .body("normalizers", empty())
            .body("defaultScoringProfile", nullValue())
            .body("corsOptions", nullValue())
            .body("encryptionKey", nullValue())
            .body("semantic", nullValue())
            .body("vectorSearch", nullValue())
            .body("similarity.'@odata.type'", equalTo("#Microsoft.Azure.Search.BM25Similarity"));
    }

    @Test
    void theSectionsFlociAzDoesNotActOnComeBackAsTheyWereSent() {
        post(HOTELS).then().statusCode(201);

        get("hotels").then().statusCode(200)
            .body("scoringProfiles[0].name", equalTo("boostName"))
            .body("scoringProfiles[0].text.weights.name", equalTo(3.0f))
            .body("defaultScoringProfile", equalTo("boostName"))
            .body("corsOptions.allowedOrigins", contains("https://example.com"))
            .body("corsOptions.maxAgeInSeconds", equalTo(60))
            .body("suggesters[0].sourceFields", contains("name"))
            .body("semantic.configurations[0].prioritizedFields.titleField.fieldName", equalTo("name"))
            .body("vectorSearch.profiles[0].algorithm", equalTo("hnsw1"))
            .body("vectorSearch.algorithms[0].kind", equalTo("hnsw"));
    }

    @Test
    void theVectorSearchSectionAndCustomAnalyzersGetTheDefaultsAzureFillsIn() {
        String body = "{\"name\": \"filled\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true}],"
                + " \"analyzers\": [{\"@odata.type\": \"#Microsoft.Azure.Search.CustomAnalyzer\", \"name\": \"a\","
                + " \"tokenizer\": \"standard_v2\"}],"
                + " \"vectorSearch\": {\"profiles\": [{\"name\": \"p\", \"algorithm\": \"h\"}],"
                + " \"algorithms\": [{\"name\": \"h\", \"kind\": \"hnsw\", \"hnswParameters\": {\"metric\": \"dotProduct\"}},"
                + " {\"name\": \"e\", \"kind\": \"exhaustiveKnn\"}]}}";
        post(body).then().statusCode(201)
            .body("analyzers[0].tokenFilters", empty())
            .body("analyzers[0].charFilters", empty())
            .body("vectorSearch.keySet()", contains("algorithms", "profiles", "vectorizers", "compressions"))
            .body("vectorSearch.vectorizers", empty())
            .body("vectorSearch.compressions", empty())
            .body("vectorSearch.profiles[0]", hasKey("vectorizer"))
            .body("vectorSearch.profiles[0].compression", nullValue())
            // The metric that was sent stays; the rest are Azure's defaults.
            .body("vectorSearch.algorithms[0].hnswParameters.metric", equalTo("dotProduct"))
            .body("vectorSearch.algorithms[0].hnswParameters.m", equalTo(4))
            .body("vectorSearch.algorithms[0].hnswParameters.efConstruction", equalTo(400))
            .body("vectorSearch.algorithms[0].hnswParameters.efSearch", equalTo(500))
            .body("vectorSearch.algorithms[0]", hasKey("exhaustiveKnnParameters"))
            .body("vectorSearch.algorithms[1].exhaustiveKnnParameters.metric", equalTo("cosine"));
    }

    @Test
    void aPropertyOfANewerApiVersionComesBackAsItWasSent() {
        String body = "{\"name\": \"newer\", \"somethingNew\": {\"mode\": \"on\"},"
                + " \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true, \"fieldNew\": 7}]}";
        post(body).then().statusCode(201)
            .body("somethingNew.mode", equalTo("on"))
            .body("fields[0].fieldNew", equalTo(7));
    }

    @Test
    void anIndexIsReadInBothUrlFormsAndOnTheServicesOwnHost() {
        post(minimal("forms")).then().statusCode(201);

        get("forms").then().statusCode(200).body("name", equalTo("forms"));
        given().when().get(BASE + "/indexes/forms" + API).then().statusCode(200).body("name", equalTo("forms"));
        given().header("Host", SERVICE + ".search.windows.net").when().get("/indexes('forms')" + API)
            .then().statusCode(200)
            .body("name", equalTo("forms"))
            .body("'@odata.context'", endsWith(SERVICE + ".search.windows.net/$metadata#indexes/$entity"));
        get("missing").then().statusCode(404)
            .body("error.code", equalTo(""))
            .body("error.message", containsString("'missing'"));
    }

    @Test
    void indexesAreKeptPerService() {
        post(minimal("mine")).then().statusCode(201);

        given().when().get("/othersvc-search/indexes('mine')" + API).then().statusCode(404);
        given().when().get("/othersvc-search/indexes" + API).then().statusCode(200).body("value", empty());
    }

    @Test
    void listingAnswersEveryIndexByNameAndSelectNarrowsIt() {
        post(minimal("zebra")).then().statusCode(201);
        post(minimal("alpha")).then().statusCode(201);

        given().when().get(BASE + "/indexes" + API).then().statusCode(200)
            .body("'@odata.context'", endsWith("/$metadata#indexes"))
            .body("value.name", contains("alpha", "zebra"))
            .body("value[0].'@odata.etag'", matchesPattern(ETAG_PATTERN))
            .body("value[0].fields", hasSize(1));
        given().queryParam("$select", "name").when().get(BASE + "/indexes" + API).then().statusCode(200)
            .body("'@odata.context'", endsWith("/$metadata#indexes(name)"))
            .body("value.name", contains("alpha", "zebra"))
            .body("value[0]", not(hasKey("fields")))
            .body("value[0]", not(hasKey("@odata.etag")));
        given().queryParam("$select", "*").when().get(BASE + "/indexes" + API).then().statusCode(200)
            .body("value[0].fields", hasSize(1));
    }

    @Test
    void aPutCreatesAnIndexAndThenUpdatesItUnderANewEtag() {
        String created = put("put-me", minimal("put-me")).then().statusCode(201)
            .header("Location", endsWith("/indexes('put-me')" + API))
            .extract().path("'@odata.etag'");

        String updated = put("put-me", "{\"name\": \"put-me\", \"description\": \"second\","
                + " \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true}]}")
            .then().statusCode(200)
            .body("description", equalTo("second"))
            .extract().path("'@odata.etag'");

        assertNotEquals(created, updated);
        get("put-me").then().statusCode(200)
            .body("description", equalTo("second"))
            .body("'@odata.etag'", equalTo(updated));
    }

    @Test
    void creatingANameThatIsTakenIsABadRequestAsOnAzure() {
        post(minimal("taken")).then().statusCode(201);

        post(minimal("taken")).then().statusCode(400)
            .body("error.code", equalTo("ResourceNameAlreadyInUse"))
            .body("error.message", equalTo("Cannot create index 'taken' because it already exists."))
            .body("error.details[0].code", equalTo("CannotCreateExistingIndex"));
    }

    @Test
    void anUpdateThatChangesNothingKeepsTheEtagAndAnswersWithoutOne() {
        String etag = post(minimal("same")).then().statusCode(201).extract().path("'@odata.etag'");

        put("same", minimal("same")).then().statusCode(200)
            .header("ETag", nullValue())
            .body("$", not(hasKey("@odata.etag")))
            .body("name", equalTo("same"));
        get("same").then().statusCode(200).body("'@odata.etag'", equalTo(etag));
    }

    @Test
    void theEtagConditionsOfAPutAreHonoured() {
        String etag = post(minimal("guarded")).then().statusCode(201).extract().path("'@odata.etag'");

        // If-None-Match: * creates only.
        given().contentType("application/json").header("If-None-Match", "*").body(minimal("guarded"))
            .when().put(BASE + "/indexes('guarded')" + API)
            .then().statusCode(412).body("error.code", equalTo(""));
        // If-Match: * updates only.
        given().contentType("application/json").header("If-Match", "*").body(minimal("absent"))
            .when().put(BASE + "/indexes('absent')" + API)
            .then().statusCode(412);
        given().contentType("application/json").header("If-Match", "\"0xDEAD\"").body(minimal("guarded"))
            .when().put(BASE + "/indexes('guarded')" + API)
            .then().statusCode(412);

        // The stored tag passes as the body carries it, quoted, and the write replaces it.
        String next = given().contentType("application/json").header("If-Match", etag)
            .body(described("guarded", "second")).when().put(BASE + "/indexes('guarded')" + API)
            .then().statusCode(200).extract().path("'@odata.etag'");
        assertNotEquals(etag, next);
        given().contentType("application/json").header("If-Match", etag).body(described("guarded", "third"))
            .when().put(BASE + "/indexes('guarded')" + API)
            .then().statusCode(412);
        // A weak or a bare tag names the same version.
        String third = given().contentType("application/json").header("If-Match", "W/" + next)
            .body(described("guarded", "third")).when().put(BASE + "/indexes('guarded')" + API)
            .then().statusCode(200).extract().path("'@odata.etag'");
        given().contentType("application/json").header("If-Match", third.substring(1, third.length() - 1))
            .body(described("guarded", "fourth")).when().put(BASE + "/indexes('guarded')" + API)
            .then().statusCode(200);
    }

    @Test
    void deletingAnswers204ThenNotFoundAndHonoursItsConditions() {
        String etag = post(minimal("doomed")).then().statusCode(201).extract().path("'@odata.etag'");

        given().header("If-Match", "\"0xDEAD\"").when().delete(BASE + "/indexes('doomed')" + API)
            .then().statusCode(412);
        get("doomed").then().statusCode(200);
        given().header("If-Match", etag).when().delete(BASE + "/indexes('doomed')" + API)
            .then().statusCode(204);
        given().when().delete(BASE + "/indexes('doomed')" + API).then().statusCode(404)
            .body("error.code", equalTo("OperationNotAllowed"))
            .body("error.message", equalTo("No index with the name 'doomed' was found in a service named '"
                    + SERVICE + "'."))
            .body("error.details[0].code", equalTo("IndexNotFoundInService"));
        get("doomed").then().statusCode(404);
        // No missing index meets an If-Match, not even *: Azure answers 412 rather than 404.
        given().header("If-Match", "*").when().delete(BASE + "/indexes('doomed')" + API).then().statusCode(412);
        given().header("If-Match", etag).when().delete(BASE + "/indexes('doomed')" + API).then().statusCode(412);
    }

    @Test
    void definitionsOutsideTheRulesAreRefused() {
        String key = "{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true}";
        String[] refused = {
            // index names
            minimal("Upper"), minimal("-leading"), minimal("_leading"), minimal("double--dash"),
            minimal("double__underscore"), minimal("x"), minimal("x".repeat(129)), minimal("dot.ted"),
            "{\"fields\": [" + key + "]}",
            // fields
            "{\"name\": \"nofields\"}",
            withFields("nokey", "{\"name\": \"id\", \"type\": \"Edm.String\"}"),
            withFields("twokeys", key + ", {\"name\": \"other\", \"type\": \"Edm.String\", \"key\": true}"),
            withFields("intkey", "{\"name\": \"id\", \"type\": \"Edm.Int32\", \"key\": true}"),
            withFields("badname", key + ", {\"name\": \"1starts\", \"type\": \"Edm.String\"}"),
            withFields("badname2", key + ", {\"name\": \"has-dash\", \"type\": \"Edm.String\"}"),
            withFields("reserved", key + ", {\"name\": \"azureSearchScore\", \"type\": \"Edm.String\"}"),
            withFields("dupe", key + ", {\"name\": \"id\", \"type\": \"Edm.Int32\"}"),
            withFields("notype", key + ", {\"name\": \"x\"}"),
            withFields("badtype", key + ", {\"name\": \"x\", \"type\": \"Edm.Guid\"}"),
            withFields("nested", key + ", {\"name\": \"x\", \"type\": \"Collection(Collection(Edm.String))\"}"),
            // the vector element types are valid inside a collection only
            withFields("single", key + ", {\"name\": \"x\", \"type\": \"Edm.Single\"}"),
            // vector fields
            withFields("nodims", key + ", {\"name\": \"v\", \"type\": \"Collection(Edm.Single)\","
                    + " \"vectorSearchProfile\": \"p\"}"),
            withFields("dims1", key + ", {\"name\": \"v\", \"type\": \"Collection(Edm.Single)\", \"dimensions\": 1,"
                    + " \"vectorSearchProfile\": \"p\"}"),
            withFields("noprofile", key + ", {\"name\": \"v\", \"type\": \"Collection(Edm.Single)\","
                    + " \"dimensions\": 4}"),
            withFields("unknownprofile", key + ", {\"name\": \"v\", \"type\": \"Collection(Edm.Single)\","
                    + " \"dimensions\": 4, \"vectorSearchProfile\": \"undeclared\"}"),
            // complex fields
            withFields("emptycomplex", key + ", {\"name\": \"c\", \"type\": \"Edm.ComplexType\"}"),
            withFields("subkey", "{\"name\": \"c\", \"type\": \"Edm.ComplexType\", \"fields\": [" + key + "]}"),
            withFields("simplechildren", key + ", {\"name\": \"s\", \"type\": \"Edm.String\", \"fields\": ["
                    + "{\"name\": \"t\", \"type\": \"Edm.String\"}]}"),
            // a complex field has no attributes of its own
            withFields("complexflag", key + ", {\"name\": \"c\", \"type\": \"Edm.ComplexType\", \"searchable\": true,"
                    + " \"fields\": [{\"name\": \"t\", \"type\": \"Edm.String\"}]}"),
            withFields("complexanalyzer", key + ", {\"name\": \"c\", \"type\": \"Edm.ComplexType\","
                    + " \"analyzer\": \"en.lucene\", \"fields\": [{\"name\": \"t\", \"type\": \"Edm.String\"}]}"),
            withFields("complexsynonyms", key + ", {\"name\": \"c\", \"type\": \"Edm.ComplexType\","
                    + " \"synonymMaps\": [\"m\"], \"fields\": [{\"name\": \"t\", \"type\": \"Edm.String\"}]}"),
            // attributes the field's type rules out
            withFields("searchableint", key + ", {\"name\": \"n\", \"type\": \"Edm.Int32\", \"searchable\": true}"),
            withFields("sortablelist", key + ", {\"name\": \"t\", \"type\": \"Collection(Edm.String)\","
                    + " \"sortable\": true}"),
            withFields("sortableinlist", key + ", {\"name\": \"rooms\", \"type\": \"Collection(Edm.ComplexType)\","
                    + " \"fields\": [{\"name\": \"t\", \"type\": \"Edm.String\", \"sortable\": true}]}"),
            withFields("facetablepoint", key + ", {\"name\": \"g\", \"type\": \"Edm.GeographyPoint\","
                    + " \"facetable\": true}"),
            withVectorProfile("vectornotsearchable", vector(", \"searchable\": false")),
            withVectorProfile("vectorfilterable", vector(", \"filterable\": true")),
            withVectorProfile("vectorsortable", vector(", \"sortable\": true")),
            withVectorProfile("vectorfacetable", vector(", \"facetable\": true")),
            // retrievable and stored
            withFields("hiddenkey", "{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true, \"retrievable\": false}"),
            withFields("unstoredkey", "{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true, \"stored\": false}"),
            withFields("unstoredtext", key + ", {\"name\": \"t\", \"type\": \"Edm.String\", \"stored\": false}"),
            withVectorProfile("unstoredretrievable", vector(", \"stored\": false, \"retrievable\": true")),
        };
        for (String body : refused) {
            post(body).then().statusCode(400).body("error.code", equalTo("OperationNotAllowed"));
        }
        given().when().get(BASE + "/indexes" + API).then().statusCode(200).body("value", empty());
    }

    @Test
    void valuesOfTheWrongJsonTypeAreRefusedRatherThanDefaulted() {
        String key = "{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true}";
        String[] refused = {
            "{\"name\": 7, \"fields\": [" + key + "]}",
            "{\"name\": \"xx\", \"fields\": \"id\"}",
            "{\"name\": \"xx\", \"fields\": [\"id\"]}",
            "{\"name\": \"xx\", \"fields\": [" + key + "], \"scoringProfiles\": {}}",
            "{\"name\": \"xx\", \"fields\": [" + key + "], \"vectorSearch\": []}",
            "{\"name\": \"xx\", \"fields\": [" + key + "], \"description\": 5}",
            withFields("xx", "{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": \"yes\"}"),
            withFields("xx", key + ", {\"name\": \"f\", \"type\": \"Edm.String\", \"searchable\": 1}"),
            withFields("xx", key + ", {\"name\": \"f\", \"type\": \"Edm.String\", \"analyzer\": 5}"),
            withFields("xx", key + ", {\"name\": \"f\", \"type\": \"Edm.String\", \"fields\": \"none\"}"),
            withFields("xx", key + ", {\"name\": \"f\", \"type\": \"Edm.String\", \"synonymMaps\": \"map\"}"),
            withFields("xx", key + ", {\"name\": \"f\", \"type\": \"Edm.String\", \"synonymMaps\": [3, {\"x\": 1}]}"),
            "[]",
            "null",
            "{not json",
        };
        for (String body : refused) {
            // Azure refuses what its reader cannot read with no code.
            post(body).then().statusCode(400).body("error.code", equalTo(""));
        }
        post(withFields("xx", key + ", {\"name\": \"f\", \"type\": \"Edm.String\", \"synonymMaps\": [3]}"))
            .then().statusCode(400)
            .body("error.message", equalTo("The request is invalid. Details: Cannot convert the literal '3' to the"
                    + " expected type 'Edm.String'."));
        post("null").then().statusCode(400)
            .body("error.message", equalTo("The request is invalid. Details: An unexpected 'PrimitiveValue' node"
                    + " was found when reading from the JSON reader. A 'StartObject' node was expected."));
    }

    @Test
    void aBooleanWrittenAsAStringIsReadAsAzureReadsIt() {
        post(withFields("strings", "{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": \"true\"},"
                + " {\"name\": \"f\", \"type\": \"Edm.String\", \"searchable\": \"false\"}"))
            .then().statusCode(201)
            .body("fields[0].key", equalTo(true))
            .body("fields[1].searchable", equalTo(false));
    }

    @Test
    void aRefusedUpdateLeavesTheDefinitionAndItsEtagAsTheyWere() {
        String etag = post(minimal("kept")).then().statusCode(201).extract().path("'@odata.etag'");

        put("kept", withFields("kept", "{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true,"
                + " \"synonymMaps\": [3]}"))
            .then().statusCode(400);
        put("kept", "null").then().statusCode(400).body("error.code", equalTo(""));
        put("kept", minimal("other")).then().statusCode(400)
            .body("error.code", equalTo("400"))
            .body("error.message", equalTo("The index name 'other' in the request payload does not match the name"
                    + " 'kept' in the request URL."));

        get("kept").then().statusCode(200)
            .body("'@odata.etag'", equalTo(etag))
            .body("fields[0].synonymMaps", empty());
    }

    @Test
    void aVectorFieldIsRetrievableOnlyWhenItSaysSo() {
        post(withVectorProfile("vectors", vector("") + ", " + vector(", \"retrievable\": true").replace("\"v\"", "\"w\"")
                + ", " + vector(", \"retrievable\": false").replace("\"v\"", "\"x\"")))
            .then().statusCode(201)
            .body("fields[1].retrievable", equalTo(false))
            .body("fields[2].retrievable", equalTo(true))
            .body("fields[3].retrievable", equalTo(false));
    }

    @Test
    void addingAnAnalysisComponentToAnIndexNeedsAllowIndexDowntime() {
        String index = "{\"name\": \"analysed\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\","
                + " \"key\": true}%s]%s}";
        String analyzer = ", \"analyzers\": [{\"@odata.type\": \"#Microsoft.Azure.Search.CustomAnalyzer\","
                + " \"name\": \"mine\", \"tokenizer\": \"standard_v2\"}]";
        post(index.formatted("", "")).then().statusCode(201);
        String path = BASE + "/indexes('analysed')" + API;

        // An ordinary new field needs no downtime.
        put("analysed", index.formatted(", {\"name\": \"title\", \"type\": \"Edm.String\"}", ""))
            .then().statusCode(200);
        String withAnalyzer = index.formatted(", {\"name\": \"title\", \"type\": \"Edm.String\"}", analyzer);
        put("analysed", withAnalyzer).then().statusCode(400)
            .body("error.code", equalTo(""))
            .body("error.message", startsWith("An index with the name 'analysed' in service '" + SERVICE
                    + "' could not be updated. Index update not allowed because it would cause downtime."))
            .body("error.message", containsString("set the 'allowIndexDowntime' query parameter to 'true'"));
        given().contentType("application/json").body(withAnalyzer)
            .when().put(path + "&allowIndexDowntime=false").then().statusCode(400);
        given().contentType("application/json").body(withAnalyzer)
            .when().put(path + "&allowIndexDowntime=true").then().statusCode(200)
            .body("analyzers.name", contains("mine"));
        // Sending the analyzers the index already has needs no downtime either.
        put("analysed", withAnalyzer).then().statusCode(200);
    }

    @Test
    void aComplexFieldKeepsAnAttributeOfANewerApiVersion() {
        post(withFields("complexnew", "{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true},"
                + " {\"name\": \"address\", \"type\": \"Edm.ComplexType\", \"fieldNew\": 7,"
                + " \"fields\": [{\"name\": \"city\", \"type\": \"Edm.String\"}]}"))
            .then().statusCode(201)
            .body("fields[1].fieldNew", equalTo(7))
            .body("fields[1]", not(hasKey("searchable")));
    }

    @Test
    void anIndexOfTheSameServiceNameInAnotherCloudIsSeparate() {
        given().contentType("application/json").body("{\"location\": \"westus\", \"sku\": {\"name\": \"basic\"}}")
            .when().put(ARM_SERVICE + ARM_API).then().statusCode(201);
        post(minimal("shared")).then().statusCode(201);
        String governmentHost = SERVICE + ".search.azure.us";
        given().header("Host", governmentHost).when().get("/indexes('shared')" + API).then().statusCode(404);
        given().header("Host", governmentHost).contentType("application/json").body(minimal("shared"))
            .when().post("/indexes" + API).then().statusCode(201);

        given().when().delete(ARM_SERVICE + ARM_API).then().statusCode(200);

        get("shared").then().statusCode(404);
        given().header("Host", governmentHost).when().get("/indexes('shared')" + API).then().statusCode(200);
    }

    @Test
    void multiValuedFieldsAreNotSortableAndAnUnstoredVectorIsNotRetrievable() {
        post(withVectorProfile("multi", "{\"name\": \"rooms\", \"type\": \"Collection(Edm.ComplexType)\","
                + " \"fields\": [{\"name\": \"kind\", \"type\": \"Edm.String\"},"
                + " {\"name\": \"bed\", \"type\": \"Edm.ComplexType\", \"fields\": ["
                + "{\"name\": \"size\", \"type\": \"Edm.Int32\"}]}]}, "
                + "{\"name\": \"home\", \"type\": \"Edm.ComplexType\", \"fields\": ["
                + "{\"name\": \"city\", \"type\": \"Edm.String\"}]}, "
                + vector(", \"stored\": false")))
            .then().statusCode(201)
            // Every simple field under a complex collection is multi-valued, however deep.
            .body("fields[1].fields[0].sortable", equalTo(false))
            .body("fields[1].fields[1].fields[0].sortable", equalTo(false))
            // Under a single complex field it is not.
            .body("fields[2].fields[0].sortable", equalTo(true))
            // stored false requires retrievable false, so that is its default.
            .body("fields[3].stored", equalTo(false))
            .body("fields[3].retrievable", equalTo(false));
    }

    @Test
    void anUpdateKeepsTheSimilarityAndEncryptionKeyItLeavesOutAndChangesTheSimilarityOnlyWithDowntime() {
        String encryptionKey = "\"encryptionKey\": {\"keyVaultKeyName\": \"k\", \"keyVaultKeyVersion\": \"%s\","
                + " \"keyVaultUri\": \"https://kv.vault.azure.net\"}";
        String similarity = "\"similarity\": {\"@odata.type\": \"#Microsoft.Azure.Search.BM25Similarity\","
                + " \"k1\": %s, \"b\": 0.5}";
        String index = "{\"name\": \"tuned\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true}]%s}";
        put("tuned", index.formatted(", " + similarity.formatted("1.5") + ", " + encryptionKey.formatted("1")))
            .then().statusCode(201);

        // Leaving both out keeps them, as does an explicit null for the key.
        put("tuned", index.formatted("")).then().statusCode(200)
            .body("similarity.k1", equalTo(1.5f))
            .body("encryptionKey.keyVaultKeyVersion", equalTo("1"));
        put("tuned", index.formatted(", \"encryptionKey\": null")).then().statusCode(200)
            .body("encryptionKey.keyVaultKeyVersion", equalTo("1"));
        // The same similarity passes, and the key may be rotated.
        put("tuned", index.formatted(", " + similarity.formatted("1.50") + ", " + encryptionKey.formatted("2")))
            .then().statusCode(200)
            .body("encryptionKey.keyVaultKeyVersion", equalTo("2"));

        // Its parameters change only with allowIndexDowntime=true, as on Azure; its algorithm never does.
        put("tuned", index.formatted(", " + similarity.formatted("1.8"))).then().statusCode(400)
            .body("error.code", equalTo(""))
            .body("error.message", containsString("or modify its similarity settings"));
        get("tuned").then().body("similarity.k1", equalTo(1.5f));
        given().contentType("application/json").body(index.formatted(", " + similarity.formatted("1.8")))
            .when().put(BASE + "/indexes('tuned')" + API + "&allowIndexDowntime=true").then().statusCode(200)
            .body("similarity.k1", equalTo(1.8f));
        given().contentType("application/json").body(index.formatted(", \"similarity\": {\"@odata.type\":"
                + " \"#Microsoft.Azure.Search.ClassicSimilarity\"}"))
            .when().put(BASE + "/indexes('tuned')" + API + "&allowIndexDowntime=true").then().statusCode(400)
            .body("error.code", equalTo(""))
            .body("error.message", equalTo("Similarity type cannot be changed on existing indexes."));
        get("tuned").then().body("similarity.k1", equalTo(1.8f));
    }

    @Test
    void theVectorSearchProfileOfAnExistingFieldCannotChange() {
        String profiles = ", \"vectorSearch\": {\"profiles\": [{\"name\": \"p\", \"algorithm\": \"a\"},"
                + " {\"name\": \"p2\", \"algorithm\": \"a\"}], \"algorithms\": [{\"name\": \"a\", \"kind\": \"hnsw\"}]}}";
        String index = "{\"name\": \"profiled\", \"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\","
                + " \"key\": true}, {\"name\": \"v\", \"type\": \"Collection(Edm.Single)\", \"dimensions\": 4,"
                + " \"vectorSearchProfile\": \"%s\"}]" + profiles;
        post(index.formatted("p")).then().statusCode(201);

        put("profiled", index.formatted("p2")).then().statusCode(400)
            .body("error.code", equalTo("OperationNotAllowed"))
            .body("error.message", equalTo("Existing field 'v' cannot be changed."))
            .body("error.details[0].code", equalTo("CannotChangeExistingField"));
        get("profiled").then().body("fields[1].vectorSearchProfile", equalTo("p"));
    }

    @Test
    void aFieldAddedByAnUpdateCannotBeUnstored() {
        post(withVectorProfile("later", vector(""))).then().statusCode(201);

        put("later", withVectorProfile("later", vector("") + ", {\"name\": \"w\","
                + " \"type\": \"Collection(Edm.Single)\", \"dimensions\": 4, \"vectorSearchProfile\": \"p\","
                + " \"stored\": false}"))
            .then().statusCode(400)
            .body("error.code", equalTo("OperationNotAllowed"))
            .body("error.message", containsString("'w'"));
        get("later").then().body("fields.name", contains("id", "v"));
    }

    @Test
    void indexNamesAtTheEdgesOfTheNamingRulesAreAccepted() {
        for (String name : new String[] {"ab", "under_score", "trailing-", "a-b_c", "x".repeat(128)}) {
            post(minimal(name)).then().statusCode(201).body("name", equalTo(name));
        }
    }

    @Test
    void aPutsBodyMustNameTheIndexOfItsUrl() {
        put("nameless", "{\"fields\": [{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true}]}")
            .then().statusCode(400)
            .body("error.message", equalTo("The request is invalid. Details: The index needs a 'name'."));
        put("one", minimal("two")).then().statusCode(400)
            .body("error.message", containsString("'two'"));
        // Index names are lowercase, and the body's must be the URL's exactly.
        put("Hotels", minimal("hotels")).then().statusCode(400)
            .body("error.message", containsString("'Hotels'"));
    }

    @Test
    void anUpdateMayAddFieldsButNotChangeOrRemoveThem() {
        String key = "{\"name\": \"id\", \"type\": \"Edm.String\", \"key\": true}";
        String address = "{\"name\": \"address\", \"type\": \"Edm.ComplexType\", \"fields\": ["
                + "{\"name\": \"city\", \"type\": \"Edm.String\"}%s]}";
        String title = "{\"name\": \"title\", \"type\": \"Edm.String\"%s}";
        put("grow", withFields("grow", key + ", " + title.formatted("") + ", " + address.formatted("")))
            .then().statusCode(201);

        // A new field, a new sub-field, and an attribute that does not decide how a field is indexed.
        put("grow", withFields("grow", key + ", " + title.formatted(", \"retrievable\": false") + ", "
                + address.formatted(", {\"name\": \"street\", \"type\": \"Edm.String\"}")
                + ", {\"name\": \"rating\", \"type\": \"Edm.Int32\"}"))
            .then().statusCode(200)
            .body("fields.name", contains("id", "title", "address", "rating"))
            .body("fields[1].retrievable", equalTo(false))
            .body("fields[2].fields.name", contains("city", "street"));

        String street = address.formatted(", {\"name\": \"street\", \"type\": \"Edm.String\"}");
        String rating = ", {\"name\": \"rating\", \"type\": \"Edm.Int32\"}";
        String[] refused = {
            // removed
            withFields("grow", key + ", " + title.formatted("") + ", " + street),
            // another type
            withFields("grow", key + ", {\"name\": \"title\", \"type\": \"Edm.Int32\"}, " + street + rating),
            // another attribute
            withFields("grow", key + ", " + title.formatted(", \"searchable\": false") + ", " + street + rating),
            withFields("grow", key + ", " + title.formatted(", \"analyzer\": \"en.lucene\"") + ", " + street + rating),
            // a sub-field removed
            withFields("grow", key + ", " + title.formatted("") + ", " + address.formatted("") + rating),
        };
        for (String body : refused) {
            put("grow", body).then().statusCode(400)
                .body("error.code", equalTo("OperationNotAllowed"))
                .body("error.message", matchesPattern("Existing field(\\(s\\))? '.+' cannot be (deleted|changed)\\."))
                .body("error.details[0].code", matchesPattern("Cannot(Delete|Change)ExistingField"));
        }
        // Azure's words, recorded.
        put("grow", refused[0]).then().body("error.message", equalTo("Existing field(s) 'rating' cannot be deleted."));
        put("grow", refused[1]).then().body("error.message", equalTo("Existing field 'title' cannot be changed."));
        get("grow").then().statusCode(200).body("fields[1].retrievable", equalTo(false));
    }

    @Test
    void theStatisticsOfAnIndexAndOfTheServiceHaveTheSpecsShape() {
        post(minimal("counted")).then().statusCode(201);

        given().when().get(BASE + "/indexes('counted')/search.stats" + API).then().statusCode(200)
            .body("'@odata.context'", endsWith(BASE
                    + "/$metadata#Microsoft.Azure.Search.V2025_09_01.IndexStatistics"))
            .body("documentCount", equalTo(0))
            .body("storageSize", equalTo(0))
            .body("vectorIndexSize", equalTo(0));
        given().when().get(BASE + "/indexes('missing')/search.stats" + API).then().statusCode(404);

        // A service ARM never declared reports as a standard one.
        given().when().get(BASE + "/servicestats" + API).then().statusCode(200)
            .body("'@odata.context'", endsWith(BASE
                    + "/$metadata#Microsoft.Azure.Search.V2025_09_01.ServiceStatistics"))
            .body("counters.keySet()", contains("documentCount", "indexesCount", "indexersCount",
                    "dataSourcesCount", "storageSize", "synonymMaps", "skillsetCount", "vectorIndexSize"))
            .body("counters.indexesCount.usage", equalTo(1))
            .body("counters.indexesCount.quota", equalTo(50))
            .body("counters.documentCount.usage", equalTo(0))
            .body("counters.documentCount.quota", nullValue())
            .body("counters", hasKey("indexersCount"))
            .body("counters", hasKey("dataSourcesCount"))
            .body("counters", hasKey("storageSize"))
            .body("counters", hasKey("synonymMaps"))
            .body("counters", hasKey("skillsetCount"))
            .body("counters", hasKey("vectorIndexSize"))
            .body("limits.maxFieldsPerIndex", equalTo(1000))
            .body("limits.maxFieldNestingDepthPerIndex", equalTo(10))
            .body("limits.maxComplexCollectionFieldsPerIndex", equalTo(40))
            .body("limits.maxComplexObjectsInCollectionsPerDocument", equalTo(3000));
    }

    @Test
    void theServiceCountersAreThoseOfTheApiVersion() {
        // aliasesCount is in the previews from 2021-04-30 and stable from 2026-04-01; the knowledge counters
        // from 2026-05-01-preview, and in a stable version newer than any in the specs.
        given().when().get(BASE + "/servicestats?api-version=2026-04-01").then().statusCode(200)
            .body("counters", hasKey("aliasesCount"))
            .body("counters", not(hasKey("knowledgeBasesCount")))
            .body("'@odata.context'", endsWith("Microsoft.Azure.Search.V2026_04_01.ServiceStatistics"));
        given().when().get(BASE + "/servicestats?api-version=2025-11-01-preview").then().statusCode(200)
            .body("counters", hasKey("aliasesCount"))
            .body("counters", not(hasKey("knowledgeSourcesCount")))
            .body("'@odata.context'", endsWith("Microsoft.Azure.Search.V2025_11_01_Preview.ServiceStatistics"));
        given().when().get(BASE + "/servicestats?api-version=2026-05-01-preview").then().statusCode(200)
            .body("counters", hasKey("knowledgeBasesCount"))
            .body("counters", hasKey("knowledgeSourcesCount"));
        given().when().get(BASE + "/servicestats?api-version=2026-10-01").then().statusCode(200)
            .body("counters", hasKey("aliasesCount"))
            .body("counters", hasKey("knowledgeBasesCount"));
    }

    @Test
    void aFreeServiceReportsTheQuotasOfARealOne() {
        given().contentType("application/json").body("{\"location\": \"westus\", \"sku\": {\"name\": \"free\"}}")
            .when().put(ARM_SERVICE + ARM_API).then().statusCode(201);
        given().when().get(BASE + "/servicestats" + API).then().statusCode(200)
            .body("counters.indexesCount.quota", equalTo(3))
            .body("counters.indexersCount.quota", equalTo(3))
            .body("counters.dataSourcesCount.quota", equalTo(3))
            .body("counters.synonymMaps.quota", equalTo(3))
            .body("counters.skillsetCount.quota", equalTo(3))
            .body("counters.storageSize.quota", equalTo(52428800))
            .body("counters.documentCount.quota", nullValue())
            .body("counters.vectorIndexSize.quota", nullValue())
            .body("limits.maxStoragePerIndex", equalTo(52428800));
    }

    @Test
    void theIndexQuotaFollowsTheServicesSkuAndHostingMode() {
        given().contentType("application/json").body("{\"location\": \"westus\", \"sku\": {\"name\": \"basic\"}}")
            .when().put(ARM_SERVICE + ARM_API).then().statusCode(201);
        given().when().get(BASE + "/servicestats" + API).then().statusCode(200)
            .body("counters.indexesCount.usage", equalTo(0))
            .body("counters.indexesCount.quota", equalTo(15));

        // The hosting mode is fixed at creation, so the service is created again. A high-density standard3
        // service holds 1000 indexes per partition.
        given().when().delete(ARM_SERVICE + ARM_API).then().statusCode(200);
        given().contentType("application/json").body("{\"location\": \"westus\", \"sku\": {\"name\": "
                + "\"standard3\"}, \"properties\": {\"hostingMode\": \"highDensity\", \"partitionCount\": 2}}")
            .when().put(ARM_SERVICE + ARM_API).then().statusCode(201);
        given().when().get(BASE + "/servicestats" + API).then().statusCode(200)
            .body("counters.indexesCount.quota", equalTo(2000));
    }

    @Test
    void thePartitionsOfAServiceMultiplyItsStorageQuotas() {
        // Three S1 partitions: 3 x 160 GB of storage and 3 x 35 GB of vectors, each index able to take it all.
        given().contentType("application/json").body("{\"location\": \"westus\", \"sku\": {\"name\": \"standard\"},"
                + " \"properties\": {\"partitionCount\": 3}}")
            .when().put(ARM_SERVICE + ARM_API).then().statusCode(201);
        given().when().get(BASE + "/servicestats" + API).then().statusCode(200)
            .body("counters.storageSize.quota", equalTo(3 * 160L * 1024 * 1024 * 1024))
            .body("counters.vectorIndexSize.quota", equalTo(3 * 35L * 1024 * 1024 * 1024))
            .body("counters.indexersCount.quota", equalTo(50))
            .body("counters.synonymMaps.quota", equalTo(5))
            .body("limits.maxStoragePerIndex", equalTo(3 * 160L * 1024 * 1024 * 1024));

        // S2 caps an index at 1.88 TB, under the 12 x 512 GB of the service.
        given().when().delete(ARM_SERVICE + ARM_API).then().statusCode(200);
        given().contentType("application/json").body("{\"location\": \"westus\", \"sku\": {\"name\": \"standard2\"},"
                + " \"properties\": {\"partitionCount\": 12}}")
            .when().put(ARM_SERVICE + ARM_API).then().statusCode(201);
        given().when().get(BASE + "/servicestats" + API).then().statusCode(200)
            .body("counters.storageSize.quota", equalTo(12 * 512L * 1024 * 1024 * 1024))
            .body("limits.maxStoragePerIndex", equalTo(Math.round(1.88 * 1024L * 1024 * 1024 * 1024)));

        // A high-density S3 service: 1000 of each object per partition, and indexes of at most 100 GB.
        given().when().delete(ARM_SERVICE + ARM_API).then().statusCode(200);
        given().contentType("application/json").body("{\"location\": \"westus\", \"sku\": {\"name\": "
                + "\"standard3\"}, \"properties\": {\"hostingMode\": \"highDensity\", \"partitionCount\": 2}}")
            .when().put(ARM_SERVICE + ARM_API).then().statusCode(201);
        given().when().get(BASE + "/servicestats" + API).then().statusCode(200)
            .body("counters.indexersCount.quota", equalTo(2000))
            .body("counters.synonymMaps.quota", equalTo(20))
            .body("limits.maxStoragePerIndex", equalTo(100L * 1024 * 1024 * 1024));
    }

    @Test
    void aRequestWithoutAnApiVersionIsRefused() {
        given().when().get(BASE + "/indexes").then().statusCode(400)
            .body("error.code", equalTo("MissingRequiredParameter"))
            .body("error.message", containsString("api-version"));
    }

    @Test
    void whatIsNotServedYetAnswersNotFound() {
        post(minimal("later")).then().statusCode(201);

        given().when().get(BASE + "/synonymmaps" + API).then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"));
        // Queries come with the query engine.
        given().when().get(BASE + "/indexes('later')/docs" + API).then().statusCode(404);
        given().contentType("application/json").body("{}")
            .when().post(BASE + "/indexes('later')/docs/search.post.search" + API).then().statusCode(404);
        given().when().patch(BASE + "/indexes('later')" + API).then().statusCode(405);
        given().when().get(BASE + "/servicestats/x" + API).then().statusCode(404);
        given().when().get(BASE + "/servicestats('x')" + API).then().statusCode(404);
        given().when().post(BASE + "/servicestats" + API).then().statusCode(405);
    }

    @Test
    void deletingTheServiceForgetsItsIndexes() {
        given().contentType("application/json").body("{\"location\": \"westus\", \"sku\": {\"name\": \"basic\"}}")
            .when().put(ARM_SERVICE + ARM_API).then().statusCode(201);
        post(minimal("owned")).then().statusCode(201);

        given().when().delete(ARM_SERVICE + ARM_API).then().statusCode(200);

        get("owned").then().statusCode(404);
    }

    @Test
    void aResetForgetsEveryIndex() {
        post(minimal("gone")).then().statusCode(201);

        given().post("/_admin/reset").then().statusCode(204);

        given().when().get(BASE + "/indexes" + API).then().statusCode(200).body("value", empty());
    }
}
