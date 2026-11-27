package io.floci.az.compat;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.MatchConditions;
import com.azure.core.http.rest.RequestOptions;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.SearchIndexClientBuilder;
import com.azure.search.documents.indexes.models.CorsOptions;
import com.azure.search.documents.indexes.models.GetIndexStatisticsResult;
import com.azure.search.documents.indexes.models.HnswAlgorithmConfiguration;
import com.azure.search.documents.indexes.models.SearchField;
import com.azure.search.documents.indexes.models.SearchFieldDataType;
import com.azure.search.documents.indexes.models.SearchIndex;
import com.azure.search.documents.indexes.models.SearchServiceStatistics;
import com.azure.search.documents.indexes.models.SearchSuggester;
import com.azure.search.documents.indexes.models.VectorSearch;
import com.azure.search.documents.indexes.models.VectorSearchProfile;
import com.azure.search.documents.models.FacetResult;
import com.azure.search.documents.models.IndexAction;
import com.azure.search.documents.models.IndexActionType;
import com.azure.search.documents.models.IndexDocumentsBatch;
import com.azure.search.documents.models.IndexDocumentsOptions;
import com.azure.search.documents.models.IndexDocumentsResult;
import com.azure.search.documents.models.IndexingResult;
import com.azure.search.documents.models.QueryType;
import com.azure.search.documents.models.SearchMode;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchPagedIterable;
import com.azure.search.documents.models.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Index management, documents and queries on the Azure AI Search data plane, through {@code SearchIndexClient}
 * and {@code SearchClient}. The service is created through ARM first, and the client signs in with its admin key.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Azure AI Search Compatibility")
class SearchCompatibilityTest {

    private static final String SUB = "00000000-0000-0000-0000-000000000001";
    private static final String SERVICE = "sdk-test-search-java";
    private static final String ARM_SERVICE = "/subscriptions/" + SUB + "/resourceGroups/sdk-test-rg-search-java"
            + "/providers/Microsoft.Search/searchServices/" + SERVICE;
    private static final String ARM_API = "?api-version=2025-05-01";

    private final HttpClient http = HttpClient.newHttpClient();
    private SearchIndexClient client;

    @BeforeAll
    void setUp() throws Exception {
        EmulatorConfig.assumeEmulatorRunning();
        EmulatorConfig.installEmulatorTlsCert();
        Assumptions.assumeTrue(
                System.getProperty("javax.net.ssl.trustStore") != null,
                "requires FLOCI_AZ_TLS_ENABLED=true: the SDK sends an API key over https only");

        HttpResponse<String> created = arm("PUT", ARM_SERVICE,
                "{\"location\": \"eastus\", \"sku\": {\"name\": \"basic\"}}");
        assertTrue(created.statusCode() == 200 || created.statusCode() == 201, created.body());
        HttpResponse<String> keys = arm("POST", ARM_SERVICE + "/listAdminKeys", "");
        assertEquals(200, keys.statusCode(), keys.body());
        String adminKey = new ObjectMapper().readTree(keys.body()).get("primaryKey").asText();

        client = new SearchIndexClientBuilder()
                .endpoint(EmulatorConfig.httpBase().replace("http://", "https://") + "/" + SERVICE + "-search")
                .credential(new AzureKeyCredential(adminKey))
                .buildClient();
        for (String name : client.listIndexNames()) {
            client.deleteIndex(name);
        }
    }

    @AfterAll
    void tearDown() throws Exception {
        // Also when the setup failed after creating the service. A failed delete would leave the service behind
        // for the next run, so it fails the suite.
        HttpResponse<String> deleted = arm("DELETE", ARM_SERVICE, "");
        assertTrue(deleted.statusCode() == 200 || deleted.statusCode() == 204, deleted.body());
    }

    @Test
    @DisplayName("an index is created, read back with its fields and sections, listed and deleted")
    void anIndexIsCreatedReadBackListedAndDeleted() {
        SearchIndex created = client.createIndex(hotels("hotels-create"));
        assertEquals("hotels-create", created.getName());
        assertNotNull(created.getETag());

        SearchIndex fetched = client.getIndex("hotels-create");
        List<String> names = fetched.getFields().stream().map(SearchField::getName).toList();
        assertEquals(List.of("hotelId", "hotelName", "rating", "tags", "address", "embedding"), names);
        assertTrue(fetched.getFields().get(0).isKey());
        assertTrue(fetched.getFields().get(1).isSearchable());
        assertTrue(fetched.getFields().get(1).isSortable());
        assertFalse(fetched.getFields().get(2).isSearchable());
        assertEquals("Collection(Edm.String)", fetched.getFields().get(3).getType().toString());
        assertEquals(List.of("street", "city"),
                fetched.getFields().get(4).getFields().stream().map(SearchField::getName).toList());
        assertEquals(4, fetched.getFields().get(5).getVectorSearchDimensions());
        assertEquals("vector-profile", fetched.getFields().get(5).getVectorSearchProfileName());
        assertEquals(List.of("*"), fetched.getCorsOptions().getAllowedOrigins());
        assertEquals(List.of("hotelName"), fetched.getSuggesters().get(0).getSourceFields());
        assertEquals("hnsw", fetched.getVectorSearch().getProfiles().get(0).getAlgorithmConfigurationName());
        assertEquals(created.getETag(), fetched.getETag());

        client.createIndex(hotels("hotels-another"));
        List<String> listedNames = client.listIndexNames().stream().toList();
        assertTrue(listedNames.containsAll(List.of("hotels-another", "hotels-create")), listedNames.toString());
        assertTrue(listedNames.indexOf("hotels-another") < listedNames.indexOf("hotels-create"));
        SearchIndex listed = client.listIndexes().stream()
                .filter(index -> index.getName().equals("hotels-another")).findFirst().orElseThrow();
        assertEquals(6, listed.getFields().size());

        client.deleteIndex("hotels-another");
        assertFalse(client.listIndexNames().stream().toList().contains("hotels-another"));
        assertEquals(404, assertThrows(HttpResponseException.class,
                () -> client.getIndex("hotels-another")).getResponse().getStatusCode());
    }

    @Test
    @DisplayName("creating a name that is taken is a 400, as on Azure")
    void aTakenNameIsABadRequest() {
        client.createIndex(hotels("hotels-twice"));

        assertEquals(400, assertThrows(HttpResponseException.class,
                () -> client.createIndex(hotels("hotels-twice"))).getResponse().getStatusCode());
    }

    @Test
    @DisplayName("createOrUpdate adds a field under If-Match, refuses to change one, and the ETag guards a delete")
    void anUpdateAddsAFieldUnderItsEtagButCannotChangeOne() {
        SearchIndex first = client.createIndex(hotels("hotels-etag"));
        // An update that changes nothing keeps the ETag, as on Azure, so this one adds a field.
        List<SearchField> more = new ArrayList<>(first.getFields());
        more.add(new SearchField("stars", SearchFieldDataType.INT32).setFilterable(true));
        SearchIndex changed = new SearchIndex("hotels-etag", more).setVectorSearch(first.getVectorSearch());
        SearchIndex second = client.createOrUpdateIndexWithResponse(changed, ifMatch(first.getETag())).getValue();
        assertEquals(7, second.getFields().size());
        assertNotEquals(first.getETag(), second.getETag());

        // The first copy is stale now.
        assertEquals(412, assertThrows(HttpResponseException.class,
                () -> client.createOrUpdateIndexWithResponse(first, ifMatch(first.getETag())))
                .getResponse().getStatusCode());
        assertEquals(412, assertThrows(HttpResponseException.class,
                () -> client.deleteIndex("hotels-etag", new MatchConditions().setIfMatch(first.getETag())))
                .getResponse().getStatusCode());

        // An existing field cannot change its type.
        List<SearchField> retyped = new ArrayList<>(more);
        retyped.set(2, new SearchField("rating", SearchFieldDataType.INT32));
        assertEquals(400, assertThrows(HttpResponseException.class,
                () -> client.createOrUpdateIndex(new SearchIndex("hotels-etag", retyped)
                        .setVectorSearch(first.getVectorSearch()))).getResponse().getStatusCode());

        client.deleteIndex("hotels-etag", new MatchConditions().setIfMatch(second.getETag()));
        assertEquals(404, assertThrows(HttpResponseException.class,
                () -> client.getIndex("hotels-etag")).getResponse().getStatusCode());
    }

    @Test
    @DisplayName("index and service statistics deserialize")
    void statisticsDeserialize() {
        client.createIndex(hotels("hotels-stats"));

        GetIndexStatisticsResult stats = client.getIndexStatistics("hotels-stats");
        assertEquals(0, stats.getDocumentCount());
        assertEquals(0, stats.getStorageSize());

        SearchServiceStatistics service = client.getServiceStatistics();
        assertTrue(service.getCounters().getIndexCounter().getUsage() >= 1);
        // The index quota of the basic SKU the service was created with.
        assertEquals(15L, service.getCounters().getIndexCounter().getQuota());
        assertEquals(1000, service.getLimits().getMaxFieldsPerIndex());
    }

    @Test
    @DisplayName("documents are uploaded, read back, selected and counted")
    void documentsAreUploadedReadAndCounted() {
        client.createIndex(hotels("hotels-docs"));
        SearchClient docs = client.getSearchClient("hotels-docs");

        IndexDocumentsResult uploaded = docs.indexDocuments(new IndexDocumentsBatch(
                action(IndexActionType.UPLOAD, "hotelId", "1", "hotelName", "Fancy", "rating", 4.5,
                        "address", Map.of("street", "Main", "city", "Helsinki"),
                        "embedding", List.of(0.1, 0.2, 0.3, 0.4)),
                action(IndexActionType.UPLOAD, "hotelId", "2", "hotelName", "Plain")));
        assertEquals(List.of(201, 201), uploaded.getResults().stream().map(IndexingResult::getStatusCode).toList());

        Map<String, Object> fetched = docs.getDocument("1").getAdditionalProperties();
        assertEquals("Fancy", fetched.get("hotelName"));
        assertEquals(Map.of("street", "Main", "city", "Helsinki"), fetched.get("address"));
        // A vector field is not retrievable unless the index says so.
        assertFalse(fetched.containsKey("embedding"));
        assertEquals(List.of(), docs.getDocument("2").getAdditionalProperties().get("tags"));
        Map<String, Object> selected = docs.getDocument("1", List.of("hotelName")).getAdditionalProperties();
        assertEquals("Fancy", selected.get("hotelName"));
        assertFalse(selected.containsKey("rating"));
        assertEquals(2L, docs.getDocumentCount());
        assertEquals(404, assertThrows(HttpResponseException.class,
                () -> docs.getDocument("missing")).getResponse().getStatusCode());
    }

    @Test
    @DisplayName("a merge keeps what it does not send, and a missing key fails alone with 207")
    void aMergeKeepsWhatItDoesNotSend() {
        client.createIndex(hotels("hotels-merge"));
        SearchClient docs = client.getSearchClient("hotels-merge");
        docs.indexDocuments(new IndexDocumentsBatch(action(IndexActionType.UPLOAD, "hotelId", "1",
                "hotelName", "Fancy", "address", Map.of("street", "Main", "city", "Helsinki"))));

        docs.indexDocuments(new IndexDocumentsBatch(action(IndexActionType.MERGE, "hotelId", "1",
                "address", Map.of("city", "Turku"))));
        Map<String, Object> fetched = docs.getDocument("1").getAdditionalProperties();
        assertEquals("Fancy", fetched.get("hotelName"));
        assertEquals(Map.of("street", "Main", "city", "Turku"), fetched.get("address"));

        IndexDocumentsResult mixed = docs.indexDocumentsWithResponse(new IndexDocumentsBatch(
                        action(IndexActionType.MERGE_OR_UPLOAD, "hotelId", "3", "hotelName", "New"),
                        action(IndexActionType.MERGE, "hotelId", "missing", "hotelName", "Never")),
                new IndexDocumentsOptions().setThrowOnAnyError(false), null).getValue();
        assertEquals(List.of(201, 404), mixed.getResults().stream().map(IndexingResult::getStatusCode).toList());
        assertEquals("Document not found.", mixed.getResults().get(1).getErrorMessage());

        docs.indexDocuments(new IndexDocumentsBatch(action(IndexActionType.DELETE, "hotelId", "1"),
                action(IndexActionType.DELETE, "hotelId", "never")));
        assertEquals(1L, docs.getDocumentCount());
    }

    /** The index the query test searches, with three hotels and sixty more to page through. */
    private SearchClient loadQueryIndex() {
        SearchIndex index = hotels("hotels-query");
        List<SearchField> fields = new ArrayList<>(index.getFields());
        fields.add(new SearchField("opened", SearchFieldDataType.DATE_TIME_OFFSET).setFilterable(true)
                .setSortable(true).setFacetable(true));
        fields.add(new SearchField("location", SearchFieldDataType.GEOGRAPHY_POINT).setFilterable(true)
                .setSortable(true));
        client.createIndex(new SearchIndex("hotels-query", fields).setVectorSearch(index.getVectorSearch()));
        SearchClient docs = client.getSearchClient("hotels-query");
        List<IndexAction> actions = new ArrayList<>(List.of(
                action(IndexActionType.UPLOAD, "hotelId", "1", "hotelName", "Fancy seaside hotel", "rating", 4.5,
                        "tags", List.of("pool", "wifi"), "address", Map.of("street", "Harbour road"),
                        "opened", "2020-05-01T00:00:00Z",
                        "location", Map.of("type", "Point", "coordinates", List.of(24.94, 60.17))),
                action(IndexActionType.UPLOAD, "hotelId", "2", "hotelName", "Plain hotel", "rating", 2.0,
                        "tags", List.of("wifi"), "opened", "2021-06-01T00:00:00Z"),
                action(IndexActionType.UPLOAD, "hotelId", "3", "hotelName", "Fancy resort", "rating", 5.0,
                        "tags", List.of("spa"), "opened", "2022-07-01T00:00:00Z",
                        "location", Map.of("type", "Point", "coordinates", List.of(2.35, 48.85)))));
        for (int i = 0; i < 60; i++) {
            actions.add(action(IndexActionType.UPLOAD, "hotelId", "p" + i, "hotelName", "Paged inn"));
        }
        docs.indexDocuments(new IndexDocumentsBatch(actions.toArray(IndexAction[]::new)));
        return docs;
    }

    private static List<String> ids(SearchPagedIterable results) {
        return results.stream().map(hit -> (String) hit.getAdditionalProperties().get("hotelId")).toList();
    }

    @Test
    @DisplayName("a search ranks, counts, selects, highlights and pages")
    void aSearchRanksCountsSelectsHighlightsAndPages() {
        SearchClient docs = loadQueryIndex();
        SearchPagedIterable fancy = docs.search(new SearchOptions().setSearchText("fancy")
                .setIncludeTotalCount(true).setSelect("hotelId", "hotelName"));
        List<SearchResult> hits = fancy.stream().toList();
        assertEquals(2L, fancy.iterableByPage().iterator().next().getCount());
        assertEquals(List.of("1", "3"), hits.stream()
                .map(hit -> (String) hit.getAdditionalProperties().get("hotelId")).sorted().toList());
        assertTrue(hits.stream().allMatch(hit -> hit.getScore() > 0));
        assertFalse(hits.get(0).getAdditionalProperties().containsKey("rating"));
        assertEquals(List.of("1"), ids(docs.search(new SearchOptions().setSearchText("harbour")
                .setSearchFields("address/street"))));

        assertEquals(List.of("1"), ids(docs.search(new SearchOptions().setSearchText("fancy hotel")
                .setSearchMode(SearchMode.ALL))));
        assertEquals(List.of("1", "3"), ids(docs.search(new SearchOptions().setSearchText("hotelName:fanci~1")
                .setQueryType(QueryType.FULL))).stream().sorted().toList());
        SearchResult marked = docs.search(new SearchOptions().setSearchText("seaside")
                .setHighlightFields("hotelName")).iterator().next();
        assertEquals(List.of("Fancy <em>seaside</em> hotel"), marked.getHighlights().get("hotelName"));

        // Sixty results take two pages: the SDK follows the next page parameters.
        SearchPagedIterable paged = docs.search(new SearchOptions().setSearchText("paged")
                .setIncludeTotalCount(true));
        assertEquals(60L, paged.iterableByPage().iterator().next().getCount());
        assertEquals(60, ids(paged).size());

        assertEquals(List.of("1"), ids(docs.search(new SearchOptions().setSearchText("*")
                .setFilter("rating ge 4 and tags/any(t: t eq 'pool')"))));
        assertEquals(List.of("3", "2", "1"), ids(docs.search(new SearchOptions().setSearchText("*")
                .setFilter("opened ne null").setOrderBy("opened desc"))));
        assertEquals(List.of("1"), ids(docs.search(new SearchOptions().setSearchText("*")
                .setFilter("geo.distance(location, geography'POINT(24.9 60.2)') lt 10"))));
        Map<String, List<FacetResult>> facets = docs.search(new SearchOptions().setSearchText("*").setTop(0)
                .setFacets("rating,values:3", "opened,interval:year")).iterableByPage().iterator().next()
                .getFacets();
        assertEquals(List.of(1L, 2L), facets.get("rating").stream().map(FacetResult::getCount).toList());
        assertEquals(3.0, ((Number) facets.get("rating").get(1).getAdditionalProperties().get("from")).doubleValue());
        assertEquals(List.of(1L, 1L, 1L), facets.get("opened").stream().map(FacetResult::getCount).toList());
    }

    /** An action of {@code type} on a document of the given names and values. */
    private static IndexAction action(IndexActionType type, Object... namesAndValues) {
        Map<String, Object> document = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            document.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return new IndexAction().setActionType(type).setAdditionalProperties(document);
    }

    private static SearchIndex hotels(String name) {
        return new SearchIndex(name,
                new SearchField("hotelId", SearchFieldDataType.STRING).setKey(true),
                new SearchField("hotelName", SearchFieldDataType.STRING).setSearchable(true).setSortable(true),
                new SearchField("rating", SearchFieldDataType.DOUBLE).setSearchable(false).setFilterable(true)
                        .setFacetable(true),
                new SearchField("tags", SearchFieldDataType.collection(SearchFieldDataType.STRING))
                        .setSearchable(true).setFilterable(true),
                new SearchField("address", SearchFieldDataType.COMPLEX).setFields(
                        new SearchField("street", SearchFieldDataType.STRING).setSearchable(true),
                        new SearchField("city", SearchFieldDataType.STRING).setFilterable(true)),
                new SearchField("embedding", SearchFieldDataType.collection(SearchFieldDataType.SINGLE))
                        .setSearchable(true).setVectorSearchDimensions(4)
                        .setVectorSearchProfileName("vector-profile"))
                .setCorsOptions(new CorsOptions("*").setMaxAgeInSeconds(60L))
                .setSuggesters(new SearchSuggester("sg", "hotelName"))
                .setVectorSearch(new VectorSearch()
                        .setProfiles(new VectorSearchProfile("vector-profile", "hnsw"))
                        .setAlgorithms(new HnswAlgorithmConfiguration("hnsw")));
    }

    private static RequestOptions ifMatch(String etag) {
        return new RequestOptions().setHeader(HttpHeaderName.IF_MATCH, etag);
    }

    private HttpResponse<String> arm(String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(EmulatorConfig.httpBase() + path + ARM_API))
                .header("Authorization", "Bearer fake")
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
