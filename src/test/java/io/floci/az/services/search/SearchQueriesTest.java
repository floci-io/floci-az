package io.floci.az.services.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * How a search is written for Solr before Solr parses it: plain functions, no Solr needed.
 * {@code SearchSolrDockerTest} runs the searches themselves.
 */
@DisplayName("Azure AI Search: a search as Solr reads it")
class SearchQueriesTest {

    @Test
    void aHighlightFieldWithADashAndANumberAnswersThatManyHighlights() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> hotels = new ObjectMapper().readValue("""
                {"name": "hotels", "fields": [
                  {"name": "id", "type": "Edm.String", "key": true, "searchable": false},
                  {"name": "title", "type": "Edm.String", "searchable": true},
                  {"name": "tags", "type": "Collection(Edm.String)", "searchable": true}]}""", Map.class);

        List<Map.Entry<String, String>> params = SearchQueries.plan(
                Map.of("search", "x", "highlight", "title-3, tags"), hotels).params();

        assertThat(params, hasItem(Map.entry("hl.fl", "title,tags")));
        assertThat(params, hasItem(Map.entry("hl.snippets", "5")));
        assertThat(params, hasItem(Map.entry("f.title.hl.snippets", "3")));
        assertThat(params.stream().map(Map.Entry::getKey).toList(), not(hasItem("f.tags.hl.snippets")));
    }

    @Test
    void theFullSyntaxWritesFieldPathsAsSolrNamesAndSpreadsALoneRegexOverTheFieldsButLeavesPhrasesAlone() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> hotels = new ObjectMapper().readValue("""
                {"name": "hotels", "fields": [
                  {"name": "id", "type": "Edm.String", "key": true, "searchable": false},
                  {"name": "title", "type": "Edm.String", "searchable": true},
                  {"name": "tags", "type": "Collection(Edm.String)", "searchable": true},
                  {"name": "address", "type": "Edm.ComplexType", "fields": [
                    {"name": "city", "type": "Edm.String", "searchable": true}]}]}""", Map.class);
        List<SearchSolrSchema.TextField> fields = SearchSolrSchema.textFields(hotels);

        List<SearchSolrSchema.TextField> titleOnly = List.of(fields.get(0));

        assertThat(SearchQueries.fullText("address/city:helsinki AND title:x", fields, fields),
                equalTo("address.city:helsinki AND title:x"));
        assertThat(SearchQueries.fullText("address/city:helsinki", fields, titleOnly),
                equalTo("address.city:helsinki"));
        assertThat(SearchQueries.fullText("/hot.l/ -title:/x/", fields, fields),
                equalTo("(title:/hot.l/ OR tags:/hot.l/ OR address.city:/hot.l/) -title:/x/"));
        assertThat(SearchQueries.fullText("\"path /usr/ bin\" /b.n/", fields, titleOnly),
                equalTo("\"path /usr/ bin\" (title:/b.n/)"));
        assertThat(SearchQueries.fullText("\"address/city: x\" title:/address/city:/ a\\/b", fields, fields),
                equalTo("\"address/city: x\" title:/address/city:/ a\\/b"));
    }
}
