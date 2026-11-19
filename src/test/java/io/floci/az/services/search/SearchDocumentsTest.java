package io.floci.az.services.search;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Documents on the search data plane. The operations follow the data-plane spec ({@code searchindex.json},
 * 2025-09-01); the status codes, error codes and messages follow a recording of a real service, word for word.
 */
@QuarkusTest
@DisplayName("Azure AI Search: documents")
class SearchDocumentsTest {

    private static final String SERVICE = "docsvc";
    private static final String BASE = "/" + SERVICE + "-search";
    private static final String API = "?api-version=2025-09-01";
    private static final String DOCS = BASE + "/indexes('hotels')/docs";
    private static final String ARM_API = "?api-version=2025-05-01";
    private static final String ARM_SERVICE = "/subscriptions/5ea4c400-0000-4000-8000-000000000002/resourceGroups/rg"
            + "/providers/Microsoft.Search/searchServices/" + SERVICE;

    /** The index the recording used: every simple type, a collection, a complex field and a hidden one. */
    private static final String HOTELS = """
            {"name": "hotels", "fields": [
              {"name": "id", "type": "Edm.String", "key": true, "searchable": false},
              {"name": "title", "type": "Edm.String"},
              {"name": "rating", "type": "Edm.Double"},
              {"name": "tags", "type": "Collection(Edm.String)"},
              {"name": "updated", "type": "Edm.DateTimeOffset"},
              {"name": "integer32", "type": "Edm.Int32"},
              {"name": "integer64", "type": "Edm.Int64"},
              {"name": "point", "type": "Edm.GeographyPoint"},
              {"name": "hidden", "type": "Edm.String", "retrievable": false},
              {"name": "address", "type": "Edm.ComplexType", "fields": [
                {"name": "city", "type": "Edm.String"},
                {"name": "postcode", "type": "Edm.String"}]},
              {"name": "rooms", "type": "Collection(Edm.ComplexType)", "fields": [
                {"name": "kind", "type": "Edm.String"}]},
              {"name": "extra", "type": "Edm.Boolean"}]}""";

    private static final String VECTORS = """
            {"name": "vectors", "fields": [
              {"name": "id", "type": "Edm.String", "key": true},
              {"name": "embedding", "type": "Collection(Edm.Single)", "searchable": true, "dimensions": 4,
               "vectorSearchProfile": "p"}],
             "vectorSearch": {"profiles": [{"name": "p", "algorithm": "a"}],
                              "algorithms": [{"name": "a", "kind": "hnsw"}]}}""";

    @BeforeEach
    void createTheIndex() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body(HOTELS).when().post(BASE + "/indexes" + API)
            .then().statusCode(201);
    }

    private static Response batch(String actions) {
        return batchTo(DOCS, actions, "");
    }

    private static Response batchTo(String docs, String actions, String query) {
        return given().contentType("application/json").body("{\"value\": [" + actions + "]}")
            .when().post(docs + "/search.index" + API + query);
    }

    private static Response lookup(String key) {
        return given().when().get(DOCS + "('" + key + "')" + API);
    }

    @Test
    void anUploadCreatesDocumentsThatALookupAnswersInTheOrderOfTheIndex() {
        batch("""
                {"@search.action": "upload", "id": "one", "title": "First", "rating": 4.5, "tags": ["red", "blue"],
                 "updated": "2026-01-02T12:00:00+02:00", "hidden": "secret",
                 "address": {"city": "Helsinki", "postcode": "00100"}},
                {"@search.action": "upload", "id": "two", "title": "Second", "rating": 2.0, "tags": []}""")
            .then().statusCode(200)
            .body("'@odata.context'", endsWith(DOCS.replace("/docs", "")
                    + "/$metadata#Collection(Microsoft.Azure.Search.V2025_09_01.IndexResult)"))
            .body("value.key", contains("one", "two"))
            .body("value.status", contains(true, true))
            .body("value.errorMessage", contains(nullValue(), nullValue()))
            .body("value.statusCode", contains(201, 201));

        lookup("one").then().statusCode(200)
            .contentType("application/json; odata.metadata=minimal; odata.streaming=true; charset=utf-8")
            .body("'@odata.context'", endsWith(DOCS.replace("/docs", "") + "/$metadata#docs(*)/$entity"))
            .body("keySet()", contains("@odata.context", "id", "title", "rating", "tags", "updated", "integer32",
                    "integer64", "point", "address", "rooms", "extra"))
            .body("rating", equalTo(4.5f))
            .body("tags", contains("red", "blue"))
            // As Azure answers it: in UTC, with no fraction of a second when there is none.
            .body("updated", equalTo("2026-01-02T10:00:00Z"))
            .body("address.city", equalTo("Helsinki"))
            .body("address.postcode", equalTo("00100"));
        lookup("two").then().statusCode(200)
            .body("updated", nullValue())
            .body("integer32", nullValue())
            .body("point", nullValue())
            .body("address", nullValue())
            .body("rooms", hasSize(0))
            .body("tags", hasSize(0));
    }

    @Test
    void aLookupSelectsFieldsAndAMissingDocumentIsNotFoundWithNoBody() {
        batch("{\"id\": \"one\", \"title\": \"First\", \"rating\": 4.5, \"address\": {\"city\": \"Oulu\"}}")
            .then().statusCode(200);

        given().queryParam("$select", "id,title,rating").when().get(DOCS + "('one')" + API).then().statusCode(200)
            .body("'@odata.context'", endsWith("/$metadata#docs(*)/$entity"))
            .body("keySet()", contains("@odata.context", "id", "title", "rating"));
        given().queryParam("$select", "address/city").when().get(DOCS + "('one')" + API).then().statusCode(200)
            .body("keySet()", contains("@odata.context", "address"))
            .body("address.keySet()", contains("city"));

        // A whole field stays whole when one of its sub-fields is named too.
        given().queryParam("$select", "address,address/city").when().get(DOCS + "('one')" + API).then()
            .statusCode(200)
            .body("address.keySet()", contains("city", "postcode"));
        given().queryParam("$select", "address/city,address").when().get(DOCS + "('one')" + API).then()
            .statusCode(200)
            .body("address.keySet()", contains("city", "postcode"));

        lookup("missing").then().statusCode(404)
            .header("Content-Type", nullValue())
            .body(emptyString());
        given().when().get(BASE + "/indexes('nothere')/docs('one')" + API).then().statusCode(404)
            .body("error.code", equalTo(""));
    }

    @Test
    void aSelectOfAHiddenOrUnknownFieldIsRefusedInAzuresWords() {
        batch("{\"id\": \"one\"}").then().statusCode(200);

        given().queryParam("$select", "id,hidden").when().get(DOCS + "('one')" + API).then().statusCode(400)
            .body("error.code", equalTo(""))
            .body("error.message", equalTo("Invalid expression: 'hidden' is not a retrievable field. Only fields"
                    + " marked as retrievable in the index can be used in $select.\r\nParameter name: $select"));
        given().queryParam("$select", "id,doesNotExist").when().get(DOCS + "('one')" + API).then().statusCode(400)
            .body("error.message", equalTo("Invalid expression: Could not find a property named 'doesNotExist'"
                    + " on type 'search.document'.\r\nParameter name: $select"));
    }

    @Test
    void anActionThatIsMissingOrNullIsAnUpload() {
        batch("{\"id\": \"missing\"}, {\"@search.action\": null, \"id\": \"null\"}")
            .then().statusCode(200)
            .body("value.statusCode", contains(201, 201));

        given().when().get(BASE + "/indexes('hotels')/Docs/$COUNT" + API).then().statusCode(200).body(equalTo("2"));
    }

    @Test
    void aMergeReplacesTheFieldsItSendsAndMergesAComplexFieldRecursively() {
        batch("""
                {"id": "one", "title": "First", "rating": 4.5, "tags": ["red", "blue"],
                 "address": {"city": "Helsinki", "postcode": "00100"}}""").then().statusCode(200);

        batch("{\"@search.action\": \"merge\", \"id\": \"one\", \"title\": \"Merged\", \"address\": {\"city\": \"Turku\"},"
                + " \"tags\": [\"green\"]}")
            .then().statusCode(200).body("value[0].statusCode", equalTo(200));
        lookup("one").then().statusCode(200)
            .body("title", equalTo("Merged"))
            .body("rating", equalTo(4.5f))
            .body("tags", contains("green"))
            .body("address.city", equalTo("Turku"))
            .body("address.postcode", equalTo("00100"));

        // An explicit null clears a field.
        batch("{\"@search.action\": \"merge\", \"id\": \"one\", \"rating\": null, \"address\": null}")
            .then().statusCode(200);
        lookup("one").then().statusCode(200)
            .body("rating", nullValue())
            .body("address", nullValue())
            .body("title", equalTo("Merged"));
    }

    @Test
    void anUploadReplacesTheWholeDocumentAndMergeOrUploadDoesEither() {
        batch("{\"id\": \"one\", \"title\": \"First\", \"rating\": 4.5}").then().statusCode(200);

        batch("{\"@search.action\": \"upload\", \"id\": \"one\", \"title\": \"Replacement\"}")
            .then().statusCode(200).body("value[0].statusCode", equalTo(200));
        lookup("one").then().body("rating", nullValue()).body("title", equalTo("Replacement"));

        batch("""
                {"@search.action": "mergeOrUpload", "id": "one", "rating": 3.0},
                {"@search.action": "mergeOrUpload", "id": "three", "title": "Third"}""")
            .then().statusCode(200)
            .body("value.statusCode", contains(200, 201));
        lookup("one").then().body("title", equalTo("Replacement")).body("rating", equalTo(3.0f));
        lookup("three").then().statusCode(200).body("title", equalTo("Third"));
    }

    @Test
    void aMergeOfAMissingDocumentFailsAloneWithMultiStatus() {
        batch("{\"id\": \"two\", \"title\": \"Second\"}").then().statusCode(200);

        batch("""
                {"@search.action": "merge", "id": "two", "title": "Mixed success"},
                {"@search.action": "merge", "id": "missing", "title": "Cannot merge"}""")
            .then().statusCode(207)
            .body("value.key", contains("two", "missing"))
            .body("value.status", contains(true, false))
            .body("value.errorMessage", contains(nullValue(), equalTo("Document not found.")))
            .body("value.statusCode", contains(200, 404));
        lookup("two").then().body("title", equalTo("Mixed success"));
        lookup("missing").then().statusCode(404);
    }

    @Test
    void aDeleteSucceedsWhetherOrNotTheDocumentExists() {
        batch("{\"id\": \"one\"}, {\"id\": \"two\"}").then().statusCode(200);

        batch("{\"@search.action\": \"delete\", \"id\": \"missing\"}")
            .then().statusCode(200).body("value[0].status", equalTo(true)).body("value[0].statusCode", equalTo(200));
        batch("{\"@search.action\": \"delete\", \"id\": \"one\"}")
            .then().statusCode(200).body("value[0].statusCode", equalTo(200));

        lookup("one").then().statusCode(404);
        given().when().get(DOCS + "/$count" + API).then().statusCode(200)
            .contentType("text/plain; charset=utf-8")
            .body(equalTo("1"));
    }

    @Test
    void twoActionsOnOneKeyRunInOrderAndKeysAreCaseSensitive() {
        batch("""
                {"id": "probe", "title": "First action"},
                {"id": "probe", "title": "Second action"},
                {"id": "Case", "title": "Upper"},
                {"id": "case", "title": "Lower"}""")
            .then().statusCode(200)
            .body("value.statusCode", contains(201, 200, 201, 201));

        lookup("probe").then().body("title", equalTo("Second action"));
        lookup("Case").then().body("title", equalTo("Upper"));
        lookup("case").then().body("title", equalTo("Lower"));
    }

    @Test
    void aDocumentIsFoundByBothUrlFormsAndAnEncodedKey() {
        batch("{\"id\": \"equals=\", \"title\": \"Encoded equals\"}, {\"id\": \"it's\", \"title\": \"Quote\"}")
            .then().statusCode(400);
        batchTo(DOCS, "{\"id\": \"equals=\", \"title\": \"Encoded equals\"}, {\"id\": \"it's\", \"title\": \"Quote\"}",
                "&allowUnsafeKeys=true").then().statusCode(200);

        given().urlEncodingEnabled(false).when().get(DOCS + "('equals%3D')" + API).then().statusCode(200)
            .body("title", equalTo("Encoded equals"));
        given().when().get(DOCS + "/equals=" + API).then().statusCode(200).body("id", equalTo("equals="));
        // OData doubles a quote inside a quoted key.
        given().when().get(DOCS + "('it''s')" + API).then().statusCode(200).body("title", equalTo("Quote"));
    }

    @Test
    void aMissingOrMalformedKeyRefusesTheWholeBatch() {
        batch("{\"id\": \"fine\"}, {\"title\": \"No key\"}").then().statusCode(400)
            .body("error.code", equalTo("OperationNotAllowed"))
            .body("error.message", equalTo("The request is invalid. Details: actions : 1: Document key cannot be"
                    + " missing or empty."))
            .body("error.details[0].code", equalTo("MissingKeyField"))
            .body("error.details[0].message", equalTo("1: Document key cannot be missing or empty."
                    + " Parameters: actions"));
        lookup("fine").then().statusCode(404);

        batch("{\"id\": \"space key\"}").then().statusCode(400)
            .body("error.code", equalTo("InvalidName"))
            .body("error.message", startsWith("The request is invalid. Details: actions : 0: Invalid document key:"
                    + " 'space key'. Keys can only contain letters, digits, underscore (_), dash (-), or equal sign"
                    + " (=)."))
            .body("error.details[0].code", equalTo("InvalidDocumentKey"));
        batch("{\"id\": \"_leading\"}").then().statusCode(400)
            .body("error.message", equalTo("The request is invalid. Details: actions : 0: Invalid document key:"
                    + " '_leading'. Keys cannot start with a leading underscore."));
        batch("{\"id\": \"" + "x".repeat(1025) + "\"}").then().statusCode(400)
            .body("error.message", equalTo("The request is invalid. Details: actions : 0: Document key cannot be"
                    + " longer than 1024 characters."));
        batch("{\"id\": \"" + "x".repeat(1024) + "\"}").then().statusCode(200);
    }

    @Test
    void aValueThatDoesNotFitItsFieldRefusesTheWholeBatchWithNoCode() {
        refusedWithNoCode("{\"id\": \"a\", \"rating\": \"not-a-number\"}",
                "Cannot convert the literal 'not-a-number' to the expected type 'Edm.Double'.");
        refusedWithNoCode("{\"id\": \"a\", \"integer32\": 2147483648}",
                "Cannot convert the literal '2147483648' to the expected type 'Edm.Int32'.");
        refusedWithNoCode("{\"id\": \"a\", \"integer64\": \"9223372036854775806\"}",
                "Cannot convert a value to target type 'Edm.Int64' because of conflict between input format"
                        + " string/number and parameter 'IEEE754Compatible' false/true.");
        refusedWithNoCode("{\"id\": \"a\", \"tags\": [\"before\", null, \"after\"]}",
                "A null value was found with the expected type 'Edm.String[Nullable=False]'. The expected type"
                        + " 'Edm.String[Nullable=False]' does not allow null values.");
        refusedWithNoCode("{\"id\": \"a\", \"unknownProperty\": \"x\"}",
                "The property 'unknownProperty' does not exist on type 'search.documentFields' or is not present"
                        + " in the API version '2025-09-01'. Make sure to only use property names that are defined"
                        + " by the type.");
        refusedWithNoCode("{\"id\": \"a\", \"title\": 3}",
                "Cannot convert the literal '3' to the expected type 'Edm.String'.");
        refusedWithNoCode("{\"id\": \"a\", \"updated\": \"yesterday\"}",
                "Cannot convert the literal 'yesterday' to the expected type 'Edm.DateTimeOffset'.");
        given().when().get(DOCS + "/$count" + API).then().body(equalTo("0"));
    }

    private static void refusedWithNoCode(String action, String detail) {
        batch(action).then().statusCode(400)
            .body("error.code", equalTo(""))
            .body("error.message", equalTo("The request is invalid. Details: " + detail))
            .body("error", not(hasKey("details")));
    }

    @Test
    void anUnknownActionOrAnEmptyBatchIsRefused() {
        batch("{\"@search.action\": \"notAnAction\", \"id\": \"a\"}").then().statusCode(400)
            .body("error.code", equalTo("OperationNotAllowed"))
            .body("error.message", equalTo("Invalid action found in item 0 of index batch. Please specify one of"
                    + " the following for each item in the batch: upload, delete, merge, mergeOrUpload\r\n"
                    + "Parameter name: value"))
            .body("error.details[0].code", equalTo("InvalidIndexDocumentsAction"));
        batch("").then().statusCode(400)
            .body("error.code", equalTo("MissingRequiredParameter"))
            .body("error.message", equalTo("The request is invalid. Details: actions : No indexing actions found"
                    + " in the request. Please include between 1 and 32000 indexing actions in your request."))
            .body("error.details[0].code", equalTo("MissingIndexDocumentsActions"));
    }

    @Test
    void specialValuesKeepTheShapeAzureAnswersThemIn() {
        batch("""
                {"id": "nan", "rating": "NaN"}, {"id": "inf", "rating": "INF"}, {"id": "ninf", "rating": "-INF"},
                {"id": "millis", "updated": "2026-01-02T12:00:00.1234567+02:00"},
                {"id": "geo", "point": {"type": "Point", "coordinates": [24.94, 60.17],
                                       "crs": {"type": "name", "properties": {"name": "EPSG:3857"}}}},
                {"id": "whole", "rating": 2, "integer64": 9223372036854775806}""").then().statusCode(200);

        lookup("nan").then().body("rating", equalTo("NaN"));
        lookup("inf").then().body("rating", equalTo("INF"));
        lookup("ninf").then().body("rating", equalTo("-INF"));
        lookup("millis").then().body("updated", equalTo("2026-01-02T10:00:00.123Z"));
        // Azure answers any point with WGS 84's crs, the coordinates unchanged.
        lookup("geo").then()
            .body("point.type", equalTo("Point"))
            .body("point.coordinates", contains(24.94f, 60.17f))
            .body("point.crs.properties.name", equalTo("EPSG:4326"));
        given().when().get(DOCS + "('whole')" + API).then()
            .body(containsString("\"rating\":2.0"))
            .body(containsString("\"integer64\":9223372036854775806"));
    }

    @Test
    void aVectorMustHaveTheDimensionsOfItsField() {
        given().contentType("application/json").body(VECTORS).when().post(BASE + "/indexes" + API)
            .then().statusCode(201);
        String docs = BASE + "/indexes('vectors')/docs";

        batchTo(docs, "{\"id\": \"short\", \"embedding\": [0.1, 0.2, 0.3]}", "").then().statusCode(400)
            .body("error.code", equalTo("OperationNotAllowed"))
            .body("error.message", startsWith("The request is invalid. Details: actions : 0: There's a mismatch in"
                    + " vector dimensions. The vector field 'embedding', with dimension of '4', expects a length of"
                    + " '4'. However, the provided vector has a length of '3'."))
            .body("error.details[0].code", equalTo("IndexDocumentsFieldError"));
        batchTo(docs, "{\"id\": \"fits\", \"embedding\": [0.1, 0.2, 0.3, 0.4]}", "").then().statusCode(200);

        // A vector field is not retrievable by default, so a lookup leaves it out.
        given().when().get(docs + "('fits')" + API).then().statusCode(200)
            .body("keySet()", contains("@odata.context", "id"));
        given().when().get(BASE + "/indexes('vectors')/search.stats" + API).then().statusCode(200)
            .body("documentCount", equalTo(1))
            .body("vectorIndexSize", equalTo(16));
    }

    @Test
    void aLargeBatchIsAcceptedAndCounted() {
        String actions = IntStream.range(0, 1001)
            .mapToObj(i -> "{\"id\": \"bulk-" + i + "\", \"title\": \"tiny\"}")
            .collect(Collectors.joining(","));

        batch(actions).then().statusCode(200).body("value", hasSize(1001));

        given().when().get(DOCS + "/$count" + API).then().statusCode(200).body(equalTo("1001"));
        given().when().get(BASE + "/indexes('hotels')/search.stats" + API).then().statusCode(200)
            .body("documentCount", equalTo(1001))
            .body("storageSize", greaterThan(0));
        given().when().get(BASE + "/servicestats" + API).then().statusCode(200)
            .body("counters.documentCount.usage", equalTo(1001))
            .body("counters.storageSize.usage", greaterThan(0));
    }

    @Test
    void aBatchOverTheActionLimitIsTooLarge() {
        String actions = IntStream.range(0, 32001).mapToObj(i -> "{\"id\": \"k" + i + "\"}")
            .collect(Collectors.joining(","));

        batch(actions).then().statusCode(413);
        given().when().get(DOCS + "/$count" + API).then().body(equalTo("0"));
    }

    @Test
    void deletingAnIndexDeletesItsDocuments() {
        batch("{\"id\": \"one\"}").then().statusCode(200);

        given().when().delete(BASE + "/indexes('hotels')" + API).then().statusCode(204);
        given().contentType("application/json").body(HOTELS).when().post(BASE + "/indexes" + API)
            .then().statusCode(201);

        lookup("one").then().statusCode(404);
        given().when().get(DOCS + "/$count" + API).then().body(equalTo("0"));
    }

    @Test
    void deletingTheServiceOrResettingForgetsItsDocuments() {
        given().contentType("application/json").body("{\"location\": \"westus\", \"sku\": {\"name\": \"basic\"}}")
            .when().put(ARM_SERVICE + ARM_API).then().statusCode(201);
        batch("{\"id\": \"one\"}").then().statusCode(200);

        given().when().delete(ARM_SERVICE + ARM_API).then().statusCode(200);
        given().contentType("application/json").body(HOTELS).when().post(BASE + "/indexes" + API)
            .then().statusCode(201);
        lookup("one").then().statusCode(404);

        batch("{\"id\": \"two\"}").then().statusCode(200);
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body(HOTELS).when().post(BASE + "/indexes" + API)
            .then().statusCode(201);
        lookup("two").then().statusCode(404);
    }

    @Test
    void documentsOfAMissingIndexAreNotFound() {
        batchTo(BASE + "/indexes('nothere')/docs", "{\"id\": \"a\"}", "").then().statusCode(404)
            .body("error.message", equalTo("No index with the name 'nothere' was found in the service '" + SERVICE
                    + "'."));
        given().when().get(BASE + "/indexes('nothere')/docs/$count" + API).then().statusCode(404);
        given().when().get(DOCS + "/search.index" + API).then().statusCode(405);
    }

    @Test
    void aNestedComplexFieldStaysWholeWhenOneOfItsSubFieldsIsSelectedToo() {
        given().contentType("application/json").body("""
                {"name": "nested", "fields": [
                  {"name": "id", "type": "Edm.String", "key": true},
                  {"name": "address", "type": "Edm.ComplexType", "fields": [
                    {"name": "city", "type": "Edm.String"},
                    {"name": "region", "type": "Edm.ComplexType", "fields": [
                      {"name": "name", "type": "Edm.String"},
                      {"name": "code", "type": "Edm.String"}]}]}]}""")
            .when().post(BASE + "/indexes" + API).then().statusCode(201);
        String docs = BASE + "/indexes('nested')/docs";
        batchTo(docs, "{\"id\": \"one\", \"address\": {\"city\": \"Oulu\", \"region\": {\"name\": \"North\","
                + " \"code\": \"N\"}}}", "").then().statusCode(200);

        for (String select : new String[] {"address/region,address/region/name",
                "address/region/name,address/region"}) {
            given().queryParam("$select", select).when().get(docs + "('one')" + API).then().statusCode(200)
                .body("address.keySet()", contains("region"))
                .body("address.region.keySet()", contains("name", "code"));
        }
        given().queryParam("$select", "address/region/name").when().get(docs + "('one')" + API).then()
            .statusCode(200)
            .body("address.region.keySet()", contains("name"));
    }

    @Test
    void trailingContentAfterTheBatchRefusesItAndWritesNothing() {
        given().contentType("application/json").body("{\"value\": [{\"id\": \"one\"}]} {\"extra\": true}")
            .when().post(DOCS + "/search.index" + API).then().statusCode(400)
            .body("error.code", equalTo(""));

        lookup("one").then().statusCode(404);
        given().when().get(DOCS + "/$count" + API).then().body(equalTo("0"));
    }

    @Test
    void aDoubleWrittenAsAnIntegerBeyondALongIsAccepted() {
        batch("{\"id\": \"big\", \"rating\": 9223372036854775808, \"point\": {\"type\": \"Point\","
                + " \"coordinates\": [24, 60]}}").then().statusCode(200);

        // RestAssured reads JSON numbers as floats, so the exact double is checked in the raw body.
        lookup("big").then().statusCode(200)
            .body(containsString("\"rating\":9.223372036854776E18"))
            .body("point.coordinates", contains(24.0f, 60.0f));
        // Past the range of a double, a number would become an infinity, which JSON cannot carry.
        batch("{\"id\": \"huge\", \"rating\": 1e400}").then().statusCode(400);
        lookup("huge").then().statusCode(404);
    }

    @Test
    void aBatchOverSixteenMegabytesIsTooLargeAndWritesNothing() {
        String title = "x".repeat(16 * 1024 * 1024);

        batch("{\"id\": \"one\"}, {\"id\": \"two\", \"title\": \"" + title + "\"}").then().statusCode(413);
        given().when().get(DOCS + "/$count" + API).then().body(equalTo("0"));
    }

    @Test
    void aComplexCollectionAnswersEveryElementWithItsFieldsFilledIn() {
        batch("{\"id\": \"one\", \"rooms\": [{\"kind\": \"double\"}, {}]}").then().statusCode(200);

        lookup("one").then().statusCode(200)
            .body("rooms", hasSize(2))
            .body("rooms[0].kind", equalTo("double"))
            .body("rooms[1]", hasKey("kind"))
            .body("rooms[1].kind", nullValue());
    }
}
