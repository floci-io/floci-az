package io.floci.az.compat;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.MatchConditions;
import com.azure.core.http.rest.RequestOptions;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Index management on the Azure AI Search data plane, through {@code SearchIndexClient}. The service is created
 * through ARM first, and the client signs in with its admin key.
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
    @DisplayName("an index is created and read back with its fields and sections")
    void anIndexIsCreatedAndReadBack() {
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
    }

    @Test
    @DisplayName("creating a name that is taken is a 409, and a missing index a 404")
    void conflictsAndMissingIndexesAnswerTheirStatus() {
        client.createIndex(hotels("hotels-twice"));

        assertEquals(409, assertThrows(HttpResponseException.class,
                () -> client.createIndex(hotels("hotels-twice"))).getResponse().getStatusCode());
        assertEquals(404, assertThrows(HttpResponseException.class,
                () -> client.getIndex("hotels-missing")).getResponse().getStatusCode());
    }

    @Test
    @DisplayName("indexes are listed by name")
    void indexesAreListed() {
        client.createIndex(hotels("hotels-list-b"));
        client.createIndex(hotels("hotels-list-a"));

        List<String> names = client.listIndexNames().stream().toList();
        assertTrue(names.containsAll(List.of("hotels-list-a", "hotels-list-b")), names.toString());
        assertTrue(names.indexOf("hotels-list-a") < names.indexOf("hotels-list-b"));
        SearchIndex listed = client.listIndexes().stream()
                .filter(index -> index.getName().equals("hotels-list-a")).findFirst().orElseThrow();
        assertEquals(6, listed.getFields().size());
    }

    @Test
    @DisplayName("createOrUpdate adds a field, and refuses to change an existing one")
    void anUpdateAddsAFieldButCannotChangeOne() {
        SearchIndex index = client.createOrUpdateIndex(hotels("hotels-update"));

        List<SearchField> more = new ArrayList<>(index.getFields());
        more.add(new SearchField("stars", SearchFieldDataType.INT32).setFilterable(true));
        SearchIndex updated = client.createOrUpdateIndex(new SearchIndex("hotels-update", more)
                .setVectorSearch(index.getVectorSearch()));
        assertEquals(7, updated.getFields().size());
        assertNotEquals(index.getETag(), updated.getETag());

        List<SearchField> changed = new ArrayList<>(more);
        changed.set(2, new SearchField("rating", SearchFieldDataType.INT32));
        assertEquals(400, assertThrows(HttpResponseException.class,
                () -> client.createOrUpdateIndex(new SearchIndex("hotels-update", changed)
                        .setVectorSearch(index.getVectorSearch()))).getResponse().getStatusCode());
    }

    @Test
    @DisplayName("If-Match guards an update and a delete with the index's ETag")
    void theEtagGuardsAnUpdateAndADelete() {
        SearchIndex first = client.createIndex(hotels("hotels-etag"));
        SearchIndex second = client.createOrUpdateIndexWithResponse(first, ifMatch(first.getETag())).getValue();
        assertNotEquals(first.getETag(), second.getETag());

        assertEquals(412, assertThrows(HttpResponseException.class,
                () -> client.createOrUpdateIndexWithResponse(first, ifMatch(first.getETag())))
                .getResponse().getStatusCode());
        assertEquals(412, assertThrows(HttpResponseException.class,
                () -> client.deleteIndex("hotels-etag", new MatchConditions().setIfMatch(first.getETag())))
                .getResponse().getStatusCode());

        client.deleteIndex("hotels-etag", new MatchConditions().setIfMatch(second.getETag()));
        assertEquals(404, assertThrows(HttpResponseException.class,
                () -> client.getIndex("hotels-etag")).getResponse().getStatusCode());
    }

    @Test
    @DisplayName("an index without a key field is a 400")
    void anIndexWithoutAKeyIsRefused() {
        SearchIndex withoutKey = new SearchIndex("hotels-no-key",
                new SearchField("hotelId", SearchFieldDataType.STRING));

        assertEquals(400, assertThrows(HttpResponseException.class,
                () -> client.createIndex(withoutKey)).getResponse().getStatusCode());
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
    @DisplayName("a deleted index is gone")
    void aDeletedIndexIsGone() {
        client.createIndex(hotels("hotels-delete"));

        client.deleteIndex("hotels-delete");

        assertFalse(client.listIndexNames().stream().toList().contains("hotels-delete"));
        assertEquals(404, assertThrows(HttpResponseException.class,
                () -> client.getIndex("hotels-delete")).getResponse().getStatusCode());
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
