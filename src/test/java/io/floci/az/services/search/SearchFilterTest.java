package io.floci.az.services.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.search.SearchDocuments.Refused;
import io.floci.az.services.search.SearchFilter.Intervals;
import io.floci.az.services.search.SearchOData.Params;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An OData {@code $filter} and {@code $orderby} as Solr reads them, and the filters Azure does not allow: plain
 * functions, no Solr needed. {@code SearchSolrDockerTest} runs them.
 */
@DisplayName("Azure AI Search: a $filter and $orderby as Solr reads them")
class SearchFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> INVENTORY = json("""
            {"name": "inventory", "fields": [
              {"name": "id", "type": "Edm.String", "key": true, "filterable": true},
              {"name": "name", "type": "Edm.String", "searchable": true, "filterable": true, "sortable": true},
              {"name": "category", "type": "Edm.String", "filterable": true, "sortable": true,
               "normalizer": "standard"},
              {"name": "stars", "type": "Edm.Int32", "filterable": true, "sortable": true},
              {"name": "rating", "type": "Edm.Double", "filterable": true, "sortable": true},
              {"name": "opened", "type": "Edm.DateTimeOffset", "filterable": true},
              {"name": "parking", "type": "Edm.Boolean", "filterable": true, "sortable": true},
              {"name": "location", "type": "Edm.GeographyPoint", "filterable": true, "sortable": true},
              {"name": "places", "type": "Collection(Edm.GeographyPoint)", "filterable": true},
              {"name": "tags", "type": "Collection(Edm.String)", "filterable": true},
              {"name": "flags", "type": "Collection(Edm.Boolean)", "filterable": true},
              {"name": "scores", "type": "Collection(Edm.Int32)", "filterable": true},
              {"name": "prices", "type": "Collection(Edm.Double)", "filterable": true},
              {"name": "hidden", "type": "Edm.Int32", "filterable": false},
              {"name": "rooms", "type": "Collection(Edm.ComplexType)", "fields": [
                {"name": "type", "type": "Edm.String", "filterable": true},
                {"name": "price", "type": "Edm.Double", "filterable": true}]}]}""");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String text) {
        try {
            return MAPPER.readValue(text, Map.class);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** The filter as Solr's JSON query and the parameters it refers to, written as one string. */
    private static String solr(String filter) {
        Params params = new Params();
        SearchFilter.Translated translated = SearchFilter.translate(filter, INVENTORY,
                SearchSolrSchema.layout(INVENTORY),
                (search, fields, type, mode, sink) -> "TEXT(" + search + "," + fields + ")", params);
        try {
            return MAPPER.writeValueAsString(translated.query()) + " " + params.entries();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String refusal(String filter) {
        Refused refused = assertThrows(Refused.class, () -> solr(filter));
        String message = String.valueOf(refused.response().getEntity());
        assertThat(message, containsString("Parameter name: $filter"));
        return message;
    }

    @Test
    void comparisonsAreRangesAndTermsOnTheValueFields() {
        assertThat(solr("stars ge 4"), containsString("_v_stars:[4 TO *]"));
        // An integer field compared with a fraction keeps the whole numbers on the right side.
        assertThat(solr("stars gt 3.5"), containsString("_v_stars:[4 TO *]"));
        assertThat(solr("stars lt 3.5"), containsString("_v_stars:[* TO 3]"));
        assertThat(solr("5 le stars"), containsString("_v_stars:[5 TO *]"));
        assertThat(solr("rating gt 4.5"), containsString("_v_rating:{\\\"4.5\\\" TO *]"));
        assertThat(solr("rating lt INF"), containsString("_v_rating:[* TO \\\"Infinity\\\"}"));
        assertThat(solr("opened ge 2021-01-01T02:00:00+02:00"),
                containsString("_v_opened:[\\\"2021-01-01T00:00:00Z\\\" TO *]"));
        assertThat(solr("parking"), containsString("{!term f=_v_parking v=$azp0}"));
        assertThat(solr("parking"), containsString("azp0=true"));
    }

    @Test
    void stringsAreExactAfterTheNormalizerAndNullIsAMissingValue() {
        assertThat(solr("category eq 'Café'"), containsString("azp0=cafe"));
        assertThat(solr("name eq 'It''s'"), containsString("azp0=It's"));
        assertThat(solr("rating eq null"), containsString("\"must_not\":[{\"bool\":{\"should\":[\"_v_rating:[* TO *]\""));
        assertThat(solr("rating ne null"), containsString("_v_rating:[* TO *]"));
        assertThat(solr("search.in(name, 'a b,c')"), containsString("azp1=a,b,c"));
        assertThat(solr("search.in(name, 'a b|c', '|')"), containsString("azp1=a b,c"));
        assertThat(solr("rating eq NaN"), containsString("must_not"));
    }

    @Test
    void aNaNIsAValueThatPassesOnlyNe() {
        // A double's NaN has no value field entry, only a mark in _nan_: null tests and lambdas count it.
        assertThat(solr("rating ne null"), containsString("{!term f=_nan_ v=$azp0}"));
        assertThat(solr("rating ne null"), containsString("azp0=_v_rating"));
        assertThat(solr("rating eq null"), containsString("{!term f=_nan_"));
        assertThat(solr("prices/any()"), containsString("{!term f=_nan_"));
        assertThat(solr("prices/any(p: p gt 2)"), not(containsString("_nan_")));
        assertThat(solr("prices/any(p: p ne 2)"), containsString("_nan_"));
        // all: a NaN element fails ge, and passes ne.
        assertThat(solr("prices/all(p: p ge 2)"), containsString("_nan_"));
        assertThat(solr("prices/all(p: p ne 2)"), not(containsString("_nan_")));
        // Whole numbers have no NaN.
        assertThat(solr("scores/any()"), not(containsString("_nan_")));
        assertThat(solr("stars eq null"), not(containsString("_nan_")));
    }

    @Test
    void aNumberBeyondADoubleIsAnInfinityAndAnExponentNeverGrowsIntoDigits() {
        assertThat(solr("rating lt 1e1000000000"), equalTo(solr("rating lt INF")));
        assertThat(solr("rating gt -1e1000000000"), equalTo(solr("rating gt -INF")));
        assertThat(solr("rating gt 1e-1000000000"), containsString("_v_rating:{\\\"0.0\\\" TO *]"));
        assertThat(refusal("stars gt 1e1000000000"), containsString("NaN, -INF, or INF are not allowed"));
        // No whole number of the field is above 1e300.
        assertThat(solr("stars gt 1e300"), containsString("{\"bool\":{\"must_not\":[\"*:*\"]}}"));
    }

    @Test
    void aLongChainIsOneSolrClauseAndTooManyClausesOrLevelsAreRefused() {
        // The chain at the limit translates and writes as JSON: it does not nest a level per clause.
        String chain = String.join(" or ", Collections.nCopies(1000, "stars eq 1"));
        assertThat(solr(chain), containsString("_v_stars:[1 TO 1]"));
        String both = String.join(" and ", Collections.nCopies(1000, "stars eq 1"));
        assertThat(solr(both), containsString("_v_stars:[1 TO 1]"));
        String nested = "(stars eq 1 or ".repeat(99) + "stars eq 2" + ")".repeat(99);
        assertThat(solr(nested), containsString("_v_stars:[2 TO 2]"));

        assertThat(refusal(chain + " or stars eq 1"), containsString("more than 1000 clauses"));
        assertThat(refusal("(".repeat(101) + "stars eq 1" + ")".repeat(101)), containsString("more than 100 levels"));
        assertThat(refusal("not ".repeat(20000) + "parking"), containsString("more than 100 levels"));
    }

    @Test
    void aPointMustBeOnTheGlobe() {
        for (String point : new String[] {"POINT(NaN 60)", "POINT(24 200)", "POINT(-181 0)", "POINT(Infinity 0)"}) {
            assertThat(refusal("geo.distance(location, geography'" + point + "') lt 5"),
                    containsString("is not a valid point"));
        }
        assertThat(solr("geo.distance(location, geography'POINT(-180 90)') lt 5"), containsString("pt=90.0,-180.0"));
    }

    @Test
    void aComplexLambdaJoinsTheElementsChildDocumentsAndAPrimitiveOneTestsTheValues() {
        String rooms = solr("rooms/any(r: r/type eq 'Suite' and r/price lt 100)");
        assertThat(rooms, containsString("\"parent\":{\"which\":\"_depth_:[0 TO 0]\""));
        assertThat(rooms, containsString("{!term f=_scope_ v=$azp0}"));
        assertThat(rooms, containsString("_v_rooms.price:[* TO \\\"100\\\"}"));
        assertThat(solr("tags/any(t: t eq 'a' or search.in(t, 'b c'))"), containsString("azp1=a,b,c"));
        assertThat(solr("tags/all(t: t ne 'a' and not (t eq 'b'))"), containsString("must_not"));
        assertThat(solr("tags/any()"), equalTo("\"_v_tags:[* TO *]\" []"));
        // One value between 2 and 8, not one above 2 and another below 8.
        assertThat(solr("scores/any(s: s gt 2 and s lt 8)"), containsString("_v_scores:[3 TO 7]"));
        assertThat(solr("scores/all(s: s ge 2)"), containsString("_v_scores:[* TO 1]"));
        assertThat(solr("flags/any(f: f)"), containsString("azp0=true"));
        assertThat(solr("places/any(p: geo.distance(p, geography'POINT(24 60)') le 5)"),
                containsString("{!geofilt sfield=_v_places pt=60.0,24.0 d=5.0}"));
    }

    @Test
    void aDistanceComparedWithZeroOrLessIsSettledWithoutAGeofilt() {
        String point = "geo.distance(location, geography'POINT(24 60)') ";
        String none = "{\"bool\":{\"must_not\":[\"*:*\"]}}";
        // A distance is never below zero: lt 0 matches nothing, ge 0 every document with a point.
        assertThat(solr(point + "lt 0"), containsString(none));
        assertThat(solr(point + "le -1"), containsString(none));
        assertThat(solr(point + "ge 0"), equalTo("\"_v_location:[* TO *]\" []"));
        assertThat(solr(point + "gt -1"), equalTo("\"_v_location:[* TO *]\" []"));
        // At zero, le keeps a document exactly at the point and gt drops it.
        assertThat(solr(point + "le 0"), containsString("d=0.0}"));
        assertThat(solr(point + "gt 0"), containsString("d=0.0}"));
        String places = "places/%s(p: geo.distance(p, geography'POINT(24 60)') %s)";
        assertThat(solr(places.formatted("any", "lt 0")), containsString(none));
        assertThat(solr(places.formatted("any", "lt 0 or geo.distance(p, geography'POINT(24 60)') le 5")),
                not(containsString("d=0")));
        assertThat(solr(places.formatted("all", "ge 0")), not(containsString("geofilt")));
    }

    @Test
    void ismatchIsAFilterAndIsmatchscoringScores() {
        Params params = new Params();
        SearchFilter.Translated scoring = SearchFilter.translate("search.ismatchscoring('x') and stars gt 1",
                INVENTORY, SearchSolrSchema.layout(INVENTORY), (search, fields, type, mode, sink) -> "TEXT", params);
        assertThat(scoring.scoring(), is(true));
        assertThat(String.valueOf(scoring.query()), containsString("must=[TEXT]"));
        SearchFilter.Translated plain = SearchFilter.translate("search.ismatch('x', 'name')", INVENTORY,
                SearchSolrSchema.layout(INVENTORY), (search, fields, type, mode, sink) -> "TEXT(" + fields + ")",
                params);
        assertThat(plain.scoring(), is(false));
        assertThat(plain.query(), equalTo("TEXT(name)"));
    }

    @Test
    void lambdasAzureDoesNotAllowAreRefusedWithItsMessages() {
        assertThat(refusal("tags/any(t: t ne 'a')"), containsString("Found a test for equality or inequality"));
        assertThat(refusal("tags/all(t: t eq 'a')"), containsString("Found a test for equality or inequality"));
        assertThat(refusal("tags/any(t: t gt 'a')"), containsString("Found a comparison operator"));
        assertThat(refusal("scores/any(s: s ne 5 and s gt 2)"), containsString("Disjunctive Normal Form"));
        assertThat(refusal("scores/all(s: s eq 5 or s le 2)"), containsString("Conjunctive Normal Form"));
        assertThat(refusal("flags/any(f: f or not f)"), containsString("Invalid lambda expression"));
        assertThat(refusal("places/any(p: geo.distance(p, geography'POINT(24 60)') gt 5)"),
                containsString("compare geo.distance() using the 'lt' or 'le'"));
        assertThat(refusal("places/any(p: geo.distance(p, geography'POINT(24 60)') lt 5 and"
                + " geo.distance(p, geography'POINT(25 60)') lt 5)"), containsString("join subexpressions with 'or'"));
        assertThat(refusal("rooms/any(r: search.ismatch('x'))"),
                containsString("has no parameters bound to the range variable 'r'"));
        assertThat(refusal("rooms/any(r: stars gt 1)"), containsString("isn't bound to the range variable 'r'"));
    }

    @Test
    void whatCannotBeComparedIsRefused() {
        assertThat(refusal("not stars gt 5"), containsString("Found operand type 'Edm.Int32' for operator kind 'Not'"));
        assertThat(refusal("stars eq 'x'"), containsString("Found operand types 'Edm.Int32' and 'Edm.String'"));
        assertThat(refusal("stars gt INF"), containsString("NaN, -INF, or INF are not allowed"));
        assertThat(refusal("parking gt true"), containsString("supports only the eq and ne operators"));
        assertThat(refusal("rating gt null"), containsString("cannot compare with null"));
        assertThat(refusal("hidden eq 1"), containsString("'hidden' is not a filterable field"));
        assertThat(refusal("nope eq 1"), containsString("Could not find a property named 'nope'"));
        assertThat(refusal("rooms/type eq 'x'"), containsString("inside a lambda expression"));
        assertThat(refusal("tags eq 'x'"), containsString("'tags' is a collection"));
        assertThat(refusal("geo.distance(location, geography'POINT(1 2)') eq 3"), containsString("'eq' and 'ne'"));
        assertThat(refusal("stars"), containsString("must be of type 'Edm.Boolean'"));
        assertThat(refusal("stars gt 1 and"), containsString("Syntax error at position 14 in 'stars gt 1 and'."));
        assertThat(refusal("name eq 'open"), containsString("Syntax error at position 8"));
        // As recorded from Azure (java SearchTests.searchThrowsWhenRequestIsMalformedAsync): the position just past
        // the token the parser could not use.
        assertThat(refusal("stars gt 3 and opened gt 2026-13-45T00:00:00Z"),
                containsString("Syntax error at position 45 in 'stars gt 3 and opened gt 2026-13-45T00:00:00Z'."));
        assertThat(refusal("This is not a valid filter."),
                containsString("Syntax error at position 7 in 'This is not a valid filter.'."));
    }

    @Test
    void unionsIntersectionsAndComplementsOfIntervalsAreExact() {
        List<Intervals.Interval> belowTwo = Intervals.atom("lt", BigDecimal.valueOf(2));
        List<Intervals.Interval> aboveFive = Intervals.atom("gt", BigDecimal.valueOf(5));
        List<Intervals.Interval> outside = Intervals.union(belowTwo, aboveFive);
        assertThat(outside, hasSize(2));
        List<Intervals.Interval> inside = Intervals.complement(outside);
        assertThat(inside, contains(new Intervals.Interval(new Intervals.Bound(BigDecimal.valueOf(2), true),
                new Intervals.Bound(BigDecimal.valueOf(5), true))));
        assertThat(Intervals.intersection(belowTwo, aboveFive), hasSize(0));
        assertThat(Intervals.complement(List.of()), contains(Intervals.EVERYTHING));
        assertThat(Intervals.complement(List.of(Intervals.EVERYTHING)), hasSize(0));
        assertThat(Intervals.union(Intervals.atom("le", BigDecimal.ONE), Intervals.atom("gt", BigDecimal.ONE)),
                contains(Intervals.EVERYTHING));
    }

    @Test
    void orderByIsASolrSortWithTheScoreAsTieBreakAndNullsFirstAscendingLastDescending() {
        Map<String, Object> definition = INVENTORY;
        SearchSolrSchema.Layout layout = SearchSolrSchema.layout(definition);
        assertThat(SearchOrderBy.sort("stars desc, rating", definition, layout),
                equalTo(Optional.of("if(exists(_v_stars),1,0) desc,_v_stars desc,if(exists(_v_rating),1,0) asc,"
                        + "_v_rating asc,score desc")));
        assertThat(SearchOrderBy.sort("name", definition, layout),
                equalTo(Optional.of("if(exists(_v_name),1,0) asc,_v_name asc,score desc")));
        assertThat(SearchOrderBy.sort("search.score() asc, geo.distance(location, geography'POINT(24 60)') desc",
                definition, layout), equalTo(Optional.of("score asc,geodist(_v_location,60.0,24.0) desc")));
        assertThat(SearchOrderBy.sort(" ", definition, layout), equalTo(Optional.empty()));
        Refused collection = assertThrows(Refused.class, () -> SearchOrderBy.sort("tags", definition, layout));
        assertThat(String.valueOf(collection.response().getEntity()), containsString("'tags' is not a sortable field"));
        String many = String.join(",", Collections.nCopies(33, "stars"));
        Refused tooMany = assertThrows(Refused.class, () -> SearchOrderBy.sort(many, definition, layout));
        assertThat(String.valueOf(tooMany.response().getEntity()), not(containsString("sortable")));
        Refused point = assertThrows(Refused.class, () -> SearchOrderBy.sort("location desc", definition, layout));
        assertThat(String.valueOf(point.response().getEntity()), containsString("can only be sorted by geo.distance"));
        // As recorded from Azure (java SuggestTests.suggestThrowsWhenRequestIsMalformedAsync): the syntax is checked
        // before any field is looked up.
        Refused offTheGlobe = assertThrows(Refused.class, () -> SearchOrderBy.sort(
                "geo.distance(location, geography'POINT(24 200)')", definition, layout));
        assertThat(String.valueOf(offTheGlobe.response().getEntity()), containsString("is not a valid point"));
        Refused malformed = assertThrows(Refused.class,
                () -> SearchOrderBy.sort("This is not a valid orderby.", definition, layout));
        assertThat(String.valueOf(malformed.response().getEntity()),
                containsString("Syntax error at position 7 in 'This is not a valid orderby.'."));
    }
}
