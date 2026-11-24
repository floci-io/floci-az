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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
