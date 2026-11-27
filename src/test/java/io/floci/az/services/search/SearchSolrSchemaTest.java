package io.floci.az.services.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

/** How an index is laid out in its Solr collection: plain functions, no Solr needed. */
@DisplayName("Azure AI Search: the Solr schema of an index")
class SearchSolrSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String text) {
        try {
            return MAPPER.readValue(text, Map.class);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final Map<String, Object> HOTELS = json("""
            {"name": "hotels", "fields": [
              {"name": "id", "type": "Edm.String", "key": true, "searchable": false},
              {"name": "title", "type": "Edm.String", "searchable": true},
              {"name": "description", "type": "Edm.String", "searchable": true, "analyzer": "en.lucene"},
              {"name": "tags", "type": "Collection(Edm.String)", "searchable": true,
               "indexAnalyzer": "keyword", "searchAnalyzer": "whitespace"},
              {"name": "rating", "type": "Edm.Double", "searchable": false},
              {"name": "code", "type": "Edm.String", "searchable": false},
              {"name": "address", "type": "Edm.ComplexType", "fields": [
                {"name": "city", "type": "Edm.String", "searchable": true}]},
              {"name": "rooms", "type": "Collection(Edm.ComplexType)", "fields": [
                {"name": "kind", "type": "Edm.String", "searchable": true}]}],
             "similarity": {"@odata.type": "#Microsoft.Azure.Search.BM25Similarity", "k1": 1.5, "b": null}}""");

    @Test
    void eachAnalyzerPairIsAFieldTypeWithTheIndexSimilarity() {
        Map<String, Object> schema = SearchSolrSchema.schema(HOTELS);

        List<Map<String, Object>> types = list(schema.get("add-field-type"));
        assertThat(types.stream().map(type -> type.get("name")).toList(), contains("t_standard_lucene__standard_lucene",
                "t_en_lucene__en_lucene", "t_keyword__whitespace"));
        assertThat(types.getFirst().get("analyzer"),
                equalTo(Map.of("class", "org.apache.lucene.analysis.standard.StandardAnalyzer")));
        assertThat(types.get(1).get("analyzer"),
                equalTo(Map.of("class", "org.apache.lucene.analysis.en.EnglishAnalyzer")));
        assertThat(types.get(2).get("indexAnalyzer"),
                equalTo(Map.of("class", "org.apache.lucene.analysis.core.KeywordAnalyzer")));
        assertThat(types.get(2).get("queryAnalyzer"),
                equalTo(Map.of("class", "org.apache.lucene.analysis.core.WhitespaceAnalyzer")));
        assertThat(types.getFirst().get("similarity"),
                equalTo(Map.of("class", "solr.BM25SimilarityFactory", "k1", "1.5", "b", "0.75")));

        List<Map<String, Object>> solrFields = list(schema.get("add-field"));
        assertThat(solrFields.get(2), equalTo(Map.of("name", "tags", "type", "t_keyword__whitespace",
                "indexed", true, "stored", true, "multiValued", true)));
    }

    @Test
    void azuresAnalyzersMapToLucenesAndAnUnknownOneToTheStandardAnalyzer() {
        assertThat(SearchSolrSchema.analyzer("fi.lucene"),
                equalTo(Map.of("class", "org.apache.lucene.analysis.fi.FinnishAnalyzer")));
        assertThat(SearchSolrSchema.analyzer("pt-BR.microsoft"),
                equalTo(Map.of("class", "org.apache.lucene.analysis.br.BrazilianAnalyzer")));
        assertThat(SearchSolrSchema.analyzer("zh-Hans.lucene"),
                equalTo(Map.of("class", "org.apache.lucene.analysis.cjk.CJKAnalyzer")));
        assertThat(SearchSolrSchema.analyzer("pl.lucene"),
                equalTo(Map.of("class", "org.apache.lucene.analysis.standard.StandardAnalyzer")));
        assertThat(SearchSolrSchema.analyzer("myCustomAnalyzer"),
                equalTo(Map.of("class", "org.apache.lucene.analysis.standard.StandardAnalyzer")));
        assertThat(String.valueOf(SearchSolrSchema.analyzer("standardasciifolding.lucene")),
                containsString("solr.ASCIIFoldingFilterFactory"));
        assertThat(String.valueOf(SearchSolrSchema.analyzer("pattern")), containsString("\\W+"));
    }

    @Test
    void theClassicSimilarityIsSolrsAndBm25DefaultsAreLucenes() {
        assertThat(SearchSolrSchema.similarity(Map.of("@odata.type", "#Microsoft.Azure.Search.ClassicSimilarity")),
                equalTo(Map.of("class", "solr.ClassicSimilarityFactory")));
        assertThat(SearchSolrSchema.similarity(null),
                equalTo(Map.of("class", "solr.BM25SimilarityFactory", "k1", "1.2", "b", "0.75")));
    }

    @Test
    void theCollectionNameIsTheSameInAnyCaseAndDiffersPerCloud() {
        String name = SearchSolrSchema.collection("AzureCloud", "Svc", "Hotels");

        assertThat(name, matchesPattern("az_[0-9a-f]{32}"));
        assertThat(SearchSolrSchema.collection("azurecloud", "svc", "hotels"), equalTo(name));
        assertThat(SearchSolrSchema.collection("AzureChinaCloud", "svc", "hotels"), not(equalTo(name)));
    }

    private static final Map<String, Object> INVENTORY = json("""
            {"name": "inventory", "fields": [
              {"name": "id", "type": "Edm.String", "key": true, "filterable": true},
              {"name": "name", "type": "Edm.String", "searchable": true, "filterable": true,
               "normalizer": "lowercase"},
              {"name": "rating", "type": "Edm.Double", "filterable": true, "sortable": true},
              {"name": "location", "type": "Edm.GeographyPoint", "filterable": true},
              {"name": "notes", "type": "Edm.String", "searchable": true},
              {"name": "rooms", "type": "Collection(Edm.ComplexType)", "fields": [
                {"name": "type", "type": "Edm.String", "facetable": true},
                {"name": "beds", "type": "Collection(Edm.ComplexType)", "fields": [
                  {"name": "size", "type": "Edm.Int32", "filterable": true}]}]}]}""");

    @Test
    void eachFilterableSortableOrFacetableFieldHasAValueFieldOfItsType() {
        SearchSolrSchema.Layout layout = SearchSolrSchema.layout(INVENTORY);

        assertThat(layout.valueFields().stream().map(SearchSolrSchema.ValueField::solrName).toList(),
                contains("_v_id", "_v_name", "_v_rating", "_v_location", "_v_rooms.type", "_v_rooms.beds.size"));
        assertThat(layout.scopes(), contains(new SearchSolrSchema.Scope("rooms", "", 1),
                new SearchSolrSchema.Scope("rooms/beds", "rooms", 2)));
        List<Map<String, Object>> fields = list(SearchSolrSchema.schema(INVENTORY).get("add-field"));
        assertThat(fields.stream().filter(field -> "_v_location".equals(field.get("name"))).findFirst().orElseThrow(),
                equalTo(Map.of("name", "_v_location", "type", "location", "indexed", true, "stored", false,
                        "docValues", true, "multiValued", false)));
        assertThat(fields.stream().filter(field -> "_v_rooms.beds.size".equals(field.get("name"))).findFirst()
                .orElseThrow().get("multiValued"), equalTo(true));
    }

    @Test
    void aDocumentHoldsItsValuesAndEachElementOfAComplexCollectionIsAChildDocument() {
        Map<String, Object> stored = json("""
                {"id": "k", "name": "Alpha INN", "rating": "INF", "notes": "quiet",
                 "location": {"type": "Point", "coordinates": [24.9, 60.1]},
                 "rooms": [{"type": "Suite", "beds": [{"size": 2}, {"size": 1}]}, {"type": "Single"}]}""");

        Map<String, Object> document = SearchSolrSchema.document(SearchSolrSchema.layout(INVENTORY), "k", stored);

        assertThat(document.get("_key_"), equalTo("k"));
        assertThat(document.get("_depth_"), equalTo(0));
        assertThat(document.get("_v_name"), equalTo("alpha inn"));
        assertThat(document.get("name"), equalTo("Alpha INN"));
        assertThat(document.get("_v_rating"), equalTo("Infinity"));
        assertThat(document.get("_v_location"), equalTo("60.1,24.9"));
        // Every element's values, for facets.
        assertThat(document.get("_v_rooms.type"), equalTo(List.of("Suite", "Single")));
        List<Map<String, Object>> rooms = list(document.get("_n_rooms"));
        assertThat(rooms, hasSize(2));
        assertThat(rooms.getFirst().get("_key_"), equalTo("_1:k#rooms.0"));
        assertThat(rooms.getFirst().get("_scope_"), equalTo("rooms"));
        assertThat(rooms.getFirst().get("_v_rooms.type"), equalTo("Suite"));
        List<Map<String, Object>> beds = list(rooms.getFirst().get("_n_beds"));
        assertThat(beds.stream().map(bed -> bed.get("_v_rooms.beds.size")).toList(), contains(2, 1));
        assertThat(beds.getFirst().get("_depth_"), equalTo(2));
        assertThat(rooms.get(1), not(hasKey("_n_beds")));
    }

    @Test
    void aNaNIsMarkedRatherThanGivenAValue() {
        Map<String, Object> document = SearchSolrSchema.document(SearchSolrSchema.layout(INVENTORY), "k",
                json("{\"id\": \"k\", \"rating\": \"NaN\"}"));

        assertThat(document, not(hasKey("_v_rating")));
        assertThat(document.get("_nan_"), equalTo(List.of("_v_rating")));
    }

    @Test
    void aComplexCollectionInsideAComplexFieldIsAChildDocumentOfItsOwn() {
        Map<String, Object> definition = json("""
                {"name": "nested", "fields": [
                  {"name": "id", "type": "Edm.String", "key": true},
                  {"name": "rooms", "type": "Collection(Edm.ComplexType)", "fields": [
                    {"name": "type", "type": "Edm.String", "filterable": true}]},
                  {"name": "address", "type": "Edm.ComplexType", "fields": [
                    {"name": "rooms", "type": "Collection(Edm.ComplexType)", "fields": [
                      {"name": "type", "type": "Edm.String", "filterable": true}]}]}]}""");
        Map<String, Object> stored = json("""
                {"id": "k", "rooms": [{"type": "Single"}], "address": {"rooms": [{"type": "Suite"}, {"type": "Twin"}]}}""");

        Map<String, Object> document = SearchSolrSchema.document(SearchSolrSchema.layout(definition), "k", stored);

        List<Map<String, Object>> rooms = list(document.get("_n_rooms"));
        assertThat(rooms.stream().map(room -> room.get("_v_rooms.type")).toList(), contains("Single"));
        List<Map<String, Object>> addressRooms = list(document.get("_n_address.rooms"));
        assertThat(addressRooms.stream().map(room -> room.get("_v_address.rooms.type")).toList(),
                contains("Suite", "Twin"));
        assertThat(addressRooms.getFirst().get("_scope_"), equalTo("address/rooms"));
        assertThat(addressRooms.getFirst().get("_key_"), equalTo("_1:k#address.rooms.0"));
    }

    @Test
    void azuresNormalizersFoldCaseAccentsAndArticles() {
        assertThat(SearchNormalizers.of("standard", Map.of()).apply("Ærøskøbing CAFÉ"), equalTo("aeroskobing cafe"));
        assertThat(SearchNormalizers.of("uppercase", Map.of()).apply("straße"), equalTo("STRASSE"));
        assertThat(SearchNormalizers.of("asciifolding", Map.of()).apply("Łódź 東京"), equalTo("Lodz 東京"));
        assertThat(SearchNormalizers.of("elision", Map.of()).apply("l'avion"), equalTo("avion"));
        Map<String, Object> custom = json("""
                {"normalizers": [{"name": "my", "@odata.type": "#Microsoft.Azure.Search.CustomNormalizer",
                  "tokenFilters": ["asciifolding", "uppercase"]}]}""");
        assertThat(SearchNormalizers.of("my", custom).apply("crème"), equalTo("CREME"));
        assertThat(SearchNormalizers.of("unknown", custom).apply("Same"), equalTo("Same"));
        assertThat(SearchNormalizers.of(null, custom).apply("Same"), equalTo("Same"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
