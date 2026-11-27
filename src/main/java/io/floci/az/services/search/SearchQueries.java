package io.floci.az.services.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.floci.az.core.AzureRequest;
import io.floci.az.services.search.SearchDocuments.Refused;
import io.floci.az.services.search.SearchOData.Params;
import io.floci.az.services.search.SearchSolrSchema.Layout;
import io.floci.az.services.search.SearchSolrSchema.TextField;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Searching an index: {@code Documents_SearchPost} and {@code Documents_SearchGet} of the data-plane spec
 * ({@code searchindex.json}), run by the Solr sidecar.
 *
 * <pre>
 *   POST /indexes('{index}')/docs/search.post.search   (a SearchRequest body)
 *   GET  /indexes('{index}')/docs?search=...            (the same, as query parameters)
 * </pre>
 *
 * <p>The simple syntax runs on Solr's {@code simple} parser, which is Lucene's {@code SimpleQueryParser}, as Azure's
 * is, limited to the operators Azure documents. {@code queryType=full} runs on {@code edismax}, the classic Lucene
 * syntax over several fields: fielded terms, fuzzy, proximity, boosts, wildcards and regular expressions. Both search
 * the searchable fields, or those {@code searchFields} names, weighted by the scoring profile's text weights, and add
 * up a term's score over the fields. {@code searchMode} sets the default operator. Highlights come from Solr's unified
 * highlighter. The hits are read from storage, so they carry exactly what a lookup answers.
 *
 * <p>{@code filter} ({@link SearchFilter}), {@code orderby} ({@link SearchOrderBy}), {@code facets}
 * ({@link SearchFacets}) and a scoring profile's functions ({@link SearchScoring}) run on the value fields and child
 * documents of {@link SearchSolrSchema}; the query goes to Solr as a JSON query with the filter, so its parts never
 * need escaping. Vector queries and semantic ranking answer {@code 400} until they are served. A property this
 * api-version does not know is ignored, so a newer SDK's request still runs.
 *
 * <p>Paging follows the spec and Microsoft Learn: with no {@code top}, a page holds 50 results; a page never holds more
 * than 1000. When a query has more results than its page holds, the answer carries {@code @search.nextPageParameters}
 * (the request with {@code skip} moved on) and {@code @odata.nextLink}, which the SDKs follow.
 */
@ApplicationScoped
public class SearchQueries {

    private static final Logger LOG = Logger.getLogger(SearchQueries.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DOCS = "docs";
    private static final String POST_OPERATION = "search.post.search";

    static final int DEFAULT_PAGE = 50;
    static final int MAX_PAGE = 1000;
    static final int MAX_SKIP = 100000;

    private static final String INT32 = "Edm.Int32";
    private static final String BOOLEAN = "Edm.Boolean";
    private static final String DOUBLE = "Edm.Double";
    private static final String STRING = "Edm.String";
    private static final String COLLECTION = "Collection(Edm.String)";
    private static final String NOT_SUPPORTED = "FeatureNotSupportedInService";
    private static final String UNAVAILABLE = "ServiceUnavailable";

    /** Azure's simple syntax: Lucene's simple parser without the fuzzy and proximity operators. */
    private static final String SIMPLE_OPERATORS = "AND,OR,NOT,PREFIX,PHRASE,PRECEDENCE,ESCAPE,WHITESPACE";
    /** A regular expression standing as a term of its own, not after a field name. */
    /**
     * What the full syntax keeps as it is, a quoted phrase, an escaped character or a fielded regular expression, and
     * what it rewrites, a regular expression without a field. Field paths are added per index.
     */
    private static final String FULL_SYNTAX_TOKENS = "(?<keep>\"(?:[^\"\\\\]|\\\\.)*\"|\\\\.|(?<=:)/(?:[^/\\\\]|\\\\.)+/)"
            + "|(?<regex>(?<=^|[\\s(+\\-!])/(?:[^/\\\\]|\\\\.)+/)";
    private static final String DEFAULT_PRE_TAG = "<em>";
    private static final String DEFAULT_POST_TAG = "</em>";
    /** How many highlights a field answers at most, unless the request says otherwise (Learn: five). */
    private static final int HIGHLIGHT_SNIPPETS = 5;
    /**
     * A {@code highlight} entry with its own limit, {@code description-10} (Learn, "Hit highlighting"). A field name
     * has no dash, so a dash and a number can only be the limit.
     */
    private static final Pattern HIGHLIGHT_LIMIT = Pattern.compile("(.+)-([1-9][0-9]{0,8})");

    /** The {@code SearchRequest} properties and the EDM type each must have. */
    private static final Map<String, String> TYPES = types();

    /** The query-string parameters of the GET form, by the {@code SearchRequest} property they stand for. */
    private static final Map<String, String> GET_PARAMETERS = Map.ofEntries(
            Map.entry("search", "search"), Map.entry("$count", "count"), Map.entry("$top", "top"),
            Map.entry("$skip", "skip"), Map.entry("$select", "select"), Map.entry("$filter", "filter"),
            Map.entry("$orderby", "orderby"), Map.entry("facet", "facets"), Map.entry("highlight", "highlight"),
            Map.entry("highlightPreTag", "highlightPreTag"), Map.entry("highlightPostTag", "highlightPostTag"),
            Map.entry("minimumCoverage", "minimumCoverage"), Map.entry("scoringProfile", "scoringProfile"),
            Map.entry("scoringParameter", "scoringParameters"), Map.entry("searchFields", "searchFields"),
            Map.entry("searchMode", "searchMode"), Map.entry("queryType", "queryType"),
            Map.entry("sessionId", "sessionId"), Map.entry("scoringStatistics", "scoringStatistics"),
            Map.entry("semanticConfiguration", "semanticConfiguration"),
            Map.entry("semanticErrorHandling", "semanticErrorHandling"),
            Map.entry("semanticMaxWaitInMilliseconds", "semanticMaxWaitInMilliseconds"),
            Map.entry("answers", "answers"), Map.entry("captions", "captions"),
            Map.entry("semanticQuery", "semanticQuery"), Map.entry("debug", "debug"));

    /** The enumerations of {@code SearchRequest} this class reads, with their values. */
    private static final Map<String, List<String>> ENUMS = Map.of(
            "searchMode", List.of("any", "all"),
            "queryType", List.of("simple", "full", "semantic"));

    /**
     * What a search asks Solr, read and checked from the request.
     *
     * @param params    the Solr parameters, but for {@code json}
     * @param json      the JSON request: the query and its filters
     * @param facets    the facets, or null when the request asks for none
     * @param fields    the searched fields
     * @param highlight the highlighted fields, in the order the request names them
     * @param skip      the results to skip
     * @param top       the results wanted, or null for no limit
     */
    record Plan(List<Map.Entry<String, String>> params, Map<String, Object> json, SearchFacets.Plan facets,
                List<TextField> fields, List<TextField> highlight, int skip, Integer top) {
    }

    private final SearchIndexes indexes;
    private final SearchDocuments documents;
    private final SearchIndexSync sync;
    private final SearchSolrClient client;

    @Inject
    public SearchQueries(SearchIndexes indexes, SearchDocuments documents, SearchIndexSync sync,
                         SearchSolrClient client) {
        this.indexes = indexes;
        this.documents = documents;
        this.sync = sync;
        this.client = client;
    }

    /** Whether the path after the index is a search: {@code docs} alone, or {@code docs/search.post.search}. */
    static boolean addresses(List<String> rest) {
        return rest.size() == 1 && DOCS.equalsIgnoreCase(rest.getFirst())
                || rest.size() == 2 && DOCS.equalsIgnoreCase(rest.getFirst())
                && POST_OPERATION.equalsIgnoreCase(rest.get(1));
    }

    /** Serves a search of {@code index}; {@link #addresses} said the path is one. */
    public Response handle(AzureRequest req, String cloud, String service, String index, List<String> rest,
                           String serviceUrl) {
        boolean post = rest.size() == 2;
        String method = req.method().toUpperCase(Locale.ROOT);
        if (!method.equals(post ? "POST" : "GET")) {
            return SearchErrors.methodNotAllowed();
        }
        try {
            Map<String, Object> request = post ? fromBody(req) : fromQuery(req);
            // The definition is read under the index's lock, so the collection is built from the current one.
            return sync.locked(cloud, service, index, () -> {
                Map<String, Object> definition = indexes.find(cloud, service, index)
                        .orElseThrow(() -> new Refused(SearchErrors.indexNotFound(service, index)));
                return search(req, request, definition, cloud, service, index, post, serviceUrl);
            });
        } catch (Refused refused) {
            return refused.response();
        }
    }

    private Response search(AzureRequest req, Map<String, Object> request, Map<String, Object> definition,
                            String cloud, String service, String index, boolean post, String serviceUrl) {
        Plan plan = plan(request, definition);
        Optional<Map<String, Object>> selection = SearchDocuments.selection((String) request.get("select"),
                SearchDocuments.fields(definition));

        Hits hits;
        if (plan.fields().isEmpty() && !matchesAll(request)) {
            // Nothing to match text in, but a search still needs the engine, as every other one does.
            available();
            hits = new Hits(0, List.of(), Map.of(), List.of(), plan.facets() == null ? null
                    : plan.facets().read(MissingNode.getInstance(), MissingNode.getInstance()));
        } else {
            hits = run(plan, cloud, service, index, definition);
        }

        int wanted = plan.top() == null ? Integer.MAX_VALUE : plan.top();
        int available = (int) Math.max(0, hits.total() - plan.skip());
        int shown = hits.keys().size();
        // No next page past the skip limit: a request for it would be refused.
        boolean more = Math.min(available, wanted) > shown && plan.skip() + shown <= MAX_SKIP;

        String indexUrl = serviceUrl + "/indexes('" + definition.get("name") + "')";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("@odata.context", indexUrl + "/$metadata#docs(*)");
        if (Boolean.TRUE.equals(request.get("count"))) {
            body.put("@odata.count", hits.total());
        }
        if (request.get("minimumCoverage") != null) {
            body.put("@search.coverage", 100.0);
        }
        if (hits.facets() != null) {
            body.put("@search.facets", hits.facets());
        }
        Map<String, Object> next = null;
        if (more) {
            next = new LinkedHashMap<>(request);
            next.put("skip", plan.skip() + shown);
            if (plan.top() != null) {
                next.put("top", plan.top() - shown);
            }
            body.put("@search.nextPageParameters", next);
        }
        List<Map<String, Object>> fields = SearchDocuments.fields(definition);
        Map<String, Map<String, Object>> stored = documents.stored(cloud, service, index, hits.keys());
        List<Map<String, Object>> value = new ArrayList<>();
        for (int i = 0; i < hits.keys().size(); i++) {
            String key = hits.keys().get(i);
            Map<String, Object> document = stored.get(key);
            if (document == null) {
                // Deleted after Solr caught up; the next query no longer finds it.
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("@search.score", hits.scores().get(i));
            Map<String, List<String>> highlights = hits.highlights().getOrDefault(key, Map.of());
            if (!plan.highlight().isEmpty() && !highlights.isEmpty()) {
                result.put("@search.highlights", highlights);
            }
            result.putAll(SearchDocuments.project(document, fields, selection));
            value.add(result);
        }
        body.put("value", value);
        if (next != null) {
            body.put("@odata.nextLink", post
                    ? indexUrl + "/docs/" + POST_OPERATION + "?api-version=" + encode(apiVersion(req))
                    : indexUrl + "/docs?" + nextQuery(req, next));
        }
        return SearchErrors.json(200, body).build();
    }

    // ── Running the query ────────────────────────────────────────────────────

    /**
     * The hits of a page: the total, the keys and scores in rank order, each key's highlights, and the facets, or null
     * when none were asked for.
     */
    private record Hits(long total, List<String> keys, Map<String, Map<String, List<String>>> highlights,
                        List<Double> scores, Map<String, Object> facets) {
    }

    private void available() {
        try {
            sync.available();
        } catch (SearchIndexSync.Unavailable e) {
            throw new Refused(SearchErrors.error(503, UNAVAILABLE, e.getMessage()));
        }
    }

    private Hits run(Plan plan, String cloud, String service, String index, Map<String, Object> definition) {
        SearchIndexSync.Target target;
        try {
            target = sync.current(cloud, service, index, definition);
        } catch (SearchIndexSync.Unavailable e) {
            throw new Refused(SearchErrors.error(503, UNAVAILABLE, e.getMessage()));
        }
        JsonNode answer;
        JsonNode distinct = MissingNode.getInstance();
        try {
            if (plan.facets() != null && plan.facets().needsDistinctValues()) {
                List<Map.Entry<String, String>> first = new ArrayList<>(plan.params().stream()
                        .filter(param -> !"rows".equals(param.getKey()) && !param.getKey().startsWith("hl")).toList());
                first.add(param("rows", "0"));
                first.add(param("json", json(plan.json(), plan.facets().distinctValues())));
                distinct = client.select(target.engine().baseUrl(), target.collection(), first).path("facets");
            }
            List<Map.Entry<String, String>> params = new ArrayList<>(plan.params());
            params.add(param("json", json(plan.json(), plan.facets() == null ? null : plan.facets().solr(distinct))));
            answer = client.select(target.engine().baseUrl(), target.collection(), params);
        } catch (SearchSolrClient.SolrUnreachable e) {
            sync.lost(target.engine());
            throw new Refused(SearchErrors.error(503, UNAVAILABLE, "The Solr sidecar stopped answering."));
        } catch (SearchSolrClient.SolrError e) {
            if (e.status() == 400) {
                throw new Refused(SearchErrors.invalid("Failed to parse query string: " + e.getMessage()));
            }
            LOG.warnv("Search: Solr answered {0} to a query of index {1}: {2}", e.status(), index, e.getMessage());
            throw new Refused(SearchErrors.error(503, UNAVAILABLE, "The Solr sidecar could not run the query."));
        }
        List<String> keys = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (JsonNode doc : answer.path("response").path("docs")) {
            keys.add(doc.path(SearchSolrSchema.KEY).asText());
            scores.add(doc.path("score").asDouble());
        }
        Map<String, Map<String, List<String>>> highlights = new LinkedHashMap<>();
        JsonNode marked = answer.path("highlighting");
        for (String key : keys) {
            Map<String, List<String>> byField = new LinkedHashMap<>();
            for (TextField field : plan.highlight()) {
                List<String> fragments = new ArrayList<>();
                marked.path(key).path(field.solrName()).forEach(fragment -> fragments.add(fragment.asText()));
                if (!fragments.isEmpty()) {
                    byField.put(field.path(), fragments);
                }
            }
            highlights.put(key, byField);
        }
        Map<String, Object> facets = plan.facets() == null ? null
                : plan.facets().read(answer.path("facets"), distinct);
        return new Hits(answer.path("response").path("numFound").asLong(), keys, highlights, scores, facets);
    }

    /** The {@code json} parameter: the query, its filters and the facets, if any. */
    private static String json(Map<String, Object> request, Map<String, Object> facets) {
        Map<String, Object> json = new LinkedHashMap<>(request);
        if (facets != null && !facets.isEmpty()) {
            json.put("facet", facets);
        }
        try {
            return MAPPER.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A Solr query could not be written as JSON", e);
        }
    }

    /**
     * The Solr parameters of a search, and what the answer needs to read it back. The user's text is passed by
     * reference ({@code v=$azq}), so it is never read as Solr local parameters.
     */
    static Plan plan(Map<String, Object> request, Map<String, Object> definition) {
        refuseUnserved(request);
        Layout layout = SearchSolrSchema.layout(definition);
        List<TextField> searchable = layout.textFields();
        List<TextField> fields = chosen((String) request.get("searchFields"), searchable,
                SearchDocuments.fields(definition), "searchFields");
        Map<String, Integer> highlightLimits = request.get("highlight") instanceof String names && !names.isBlank()
                ? highlightLimits(names) : Map.of();
        List<TextField> highlight = highlightLimits.isEmpty() ? List.of()
                : chosen(String.join(",", highlightLimits.keySet()), searchable, SearchDocuments.fields(definition),
                "highlight");
        Map<String, Object> profile = profile(request, definition);
        Map<String, Double> weights = weights(profile);
        Integer top = (Integer) request.get("top");
        int skip = request.get("skip") instanceof Integer value ? value : 0;
        if (top != null && top < 0) {
            throw new Refused(SearchErrors.invalid("The value of 'top' must be zero or greater."));
        }
        if (skip < 0 || skip > MAX_SKIP) {
            throw new Refused(SearchErrors.invalid("The value of 'skip' must be between 0 and " + MAX_SKIP + "."));
        }
        // The contract's constraints; Azure's wording of both errors is not checked (verify).
        if (request.get("minimumCoverage") instanceof Number coverage
                && (coverage.doubleValue() < 0 || coverage.doubleValue() > 100)) {
            throw new Refused(SearchErrors.invalid("The value of 'minimumCoverage' must be between 0 and 100."));
        }
        if ((request.get("highlightPreTag") == null) != (request.get("highlightPostTag") == null)) {
            throw new Refused(SearchErrors.invalid(
                    "'highlightPreTag' and 'highlightPostTag' must be set together."));
        }

        List<Map.Entry<String, String>> params = new ArrayList<>();
        Params refs = new Params();
        boolean all = "all".equalsIgnoreCase((String) request.get("searchMode"));
        boolean full = "full".equalsIgnoreCase((String) request.get("queryType"));
        Object query;
        if (matchesAll(request)) {
            query = "*:*";
        } else {
            String text = (String) request.get("search");
            query = full ? "{!edismax v=$azq}" : "{!simple v=$azq}";
            params.add(param("azq", full ? fullText(text, searchable, fields) : text));
            params.add(param("qf", String.join(" ", fields.stream()
                    .map(field -> boosted(field, weights)).toList())));
            params.add(param("q.op", all ? "AND" : "OR"));
            if (full) {
                // Fielded terms may name the searchable fields only; a term's scores over the fields add up.
                params.add(param("uf", String.join(" ", searchable.stream().map(TextField::solrName).toList())));
                params.add(param("tie", "1.0"));
                params.add(param("sow", "true"));
            } else {
                params.add(param("q.operators", SIMPLE_OPERATORS));
            }
        }
        // Only the documents themselves, not the child documents of their complex collections' elements.
        List<Object> filters = new ArrayList<>();
        filters.add(SearchSolrSchema.DEPTH + ":0");
        if (request.get("filter") instanceof String filter && !filter.isBlank()) {
            SearchFilter.Translated translated = SearchFilter.translate(filter, definition, layout,
                    (search, searchFields, queryType, searchMode, sink) -> textQuery(search, searchFields, queryType,
                            searchMode, definition, searchable, sink), refs);
            if (translated.scoring()) {
                // search.ismatchscoring adds to the score, so the filter is part of the query.
                query = matchesAll(request) ? translated.query()
                        : Map.of("bool", Map.of("must", List.of(query, translated.query())));
            } else {
                filters.add(translated.query());
            }
        }
        Optional<String> boost = SearchScoring.boost(profile, (List<?>) request.get("scoringParameters"), layout,
                refs);
        if (boost.isPresent()) {
            Map<String, Object> boosted = new LinkedHashMap<>();
            boosted.put("b", boost.get());
            boosted.put("query", query);
            query = Map.of("boost", boosted);
        }
        SearchOrderBy.sort((String) request.get("orderby"), definition, layout)
                .ifPresent(sort -> params.add(param("sort", sort)));
        SearchFacets.Plan facets = request.get("facets") instanceof List<?> expressions && !expressions.isEmpty()
                ? SearchFacets.parse(expressions, definition, layout) : null;

        params.add(param("fl", SearchSolrSchema.KEY + ",score"));
        params.add(param("start", Integer.toString(skip)));
        params.add(param("rows", Integer.toString(top == null ? DEFAULT_PAGE : Math.min(top, MAX_PAGE))));
        if (!highlight.isEmpty()) {
            params.add(param("hl", "true"));
            params.add(param("hl.method", "unified"));
            params.add(param("hl.fl", String.join(",", highlight.stream().map(TextField::solrName).toList())));
            params.add(param("hl.requireFieldMatch", "true"));
            params.add(param("hl.snippets", Integer.toString(HIGHLIGHT_SNIPPETS)));
            for (TextField field : highlight) {
                Integer limit = highlightLimits.get(field.path());
                if (limit != null) {
                    params.add(param("f." + field.solrName() + ".hl.snippets", Integer.toString(limit)));
                }
            }
            params.add(param("hl.tag.pre", (String) request.getOrDefault("highlightPreTag", DEFAULT_PRE_TAG)));
            params.add(param("hl.tag.post", (String) request.getOrDefault("highlightPostTag", DEFAULT_POST_TAG)));
        }
        params.addAll(refs.entries());
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("query", query);
        json.put("filter", List.copyOf(filters));
        return new Plan(List.copyOf(params), json, facets, fields, highlight, skip, top);
    }

    /**
     * The Solr query of {@code search.ismatch} and {@code search.ismatchscoring}: the request's syntax over the
     * fields they name, every parameter passed by reference.
     */
    private static Object textQuery(String search, String searchFields, String queryType, String searchMode,
                                    Map<String, Object> definition, List<TextField> searchable, Params refs) {
        if (queryType != null && !"simple".equals(queryType) && !"full".equals(queryType)) {
            throw new Refused(SearchErrors.invalid("The queryType of search.ismatch must be 'simple' or 'full'."));
        }
        if (searchMode != null && !"any".equals(searchMode) && !"all".equals(searchMode)) {
            throw new Refused(SearchErrors.invalid("The searchMode of search.ismatch must be 'any' or 'all'."));
        }
        List<TextField> fields = chosen(searchFields, searchable, SearchDocuments.fields(definition), "searchFields");
        if (fields.isEmpty()) {
            return Map.of("bool", Map.of("must_not", List.of("*:*")));
        }
        if (search.isBlank() || "*".equals(search.trim())) {
            return "*:*";
        }
        String qf = refs.ref(String.join(" ", fields.stream().map(TextField::solrName).toList()));
        String operator = refs.ref("all".equals(searchMode) ? "AND" : "OR");
        if ("full".equals(queryType)) {
            return "{!edismax v=" + refs.ref(fullText(search, searchable, fields)) + " qf=" + qf + " q.op=" + operator
                    + " uf=" + refs.ref(String.join(" ", searchable.stream().map(TextField::solrName).toList()))
                    + " tie=1.0 sow=true}";
        }
        return "{!simple v=" + refs.ref(search) + " qf=" + qf + " q.op=" + operator + " q.operators="
                + refs.ref(SIMPLE_OPERATORS) + "}";
    }

    /** Whether the search matches every document: no text, or {@code *}. */
    private static boolean matchesAll(Map<String, Object> request) {
        String text = (String) request.get("search");
        return text == null || text.isBlank() || "*".equals(text.trim());
    }

    /**
     * The full syntax, with each field path written as its Solr name ({@code address/city:} as
     * {@code address.city:}) and a regular expression without a field searched over the searched fields, as the
     * classic parser alone would search it in one default field. A fielded term may name any searchable field, not
     * only the searched ones. Quoted phrases, escaped characters and the bodies of regular expressions are left
     * alone.
     */
    static String fullText(String text, List<TextField> searchable, List<TextField> fields) {
        Map<String, String> paths = new LinkedHashMap<>();
        searchable.stream().filter(field -> field.path().contains("/"))
                // Longest first, so a path is not read as the start of a longer one.
                .sorted(Comparator.comparingInt((TextField field) -> field.path().length()).reversed())
                .forEach(field -> paths.put(field.path(), field.solrName()));
        String tokens = FULL_SYNTAX_TOKENS;
        if (!paths.isEmpty()) {
            tokens += "|(?<![A-Za-z0-9_./])(?<path>" + String.join("|", paths.keySet().stream().map(Pattern::quote)
                    .toList()) + "):";
        }
        Matcher token = Pattern.compile(tokens).matcher(text);
        StringBuilder out = new StringBuilder();
        while (token.find()) {
            String replacement;
            if (token.group("keep") != null) {
                replacement = token.group();
            } else if (token.group("regex") != null) {
                String expression = token.group();
                replacement = fields.isEmpty() ? expression : "(" + String.join(" OR ", fields.stream()
                        .map(field -> field.solrName() + ":" + expression).toList()) + ")";
            } else {
                replacement = paths.get(token.group("path")) + ":";
            }
            token.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        token.appendTail(out);
        return out.toString();
    }

    private static String boosted(TextField field, Map<String, Double> weights) {
        Double weight = weights.get(field.path());
        return weight == null ? field.solrName() : field.solrName() + "^" + weight;
    }

    /** The scoring profile the request names, or the index's default one, or null for none. */
    private static Map<String, Object> profile(Map<String, Object> request, Map<String, Object> definition) {
        String name = request.get("scoringProfile") instanceof String sent ? sent
                : definition.get("defaultScoringProfile") instanceof String chosen ? chosen : null;
        if (name == null || name.isBlank()) {
            return null;
        }
        if (definition.get("scoringProfiles") instanceof List<?> profiles) {
            for (Object candidate : profiles) {
                if (candidate instanceof Map<?, ?> map && name.equals(map.get("name"))) {
                    return cast(map);
                }
            }
        }
        throw new Refused(SearchErrors.invalid("Unknown scoring profile '" + name + "'."));
    }

    /** The text weights of a scoring profile, by field path. */
    private static Map<String, Double> weights(Map<String, Object> profile) {
        Map<String, Double> weights = new LinkedHashMap<>();
        if (profile != null && profile.get("text") instanceof Map<?, ?> text
                && text.get("weights") instanceof Map<?, ?> sent) {
            for (Map.Entry<?, ?> weight : sent.entrySet()) {
                if (weight.getValue() instanceof Number number) {
                    weights.put(String.valueOf(weight.getKey()), number.doubleValue());
                }
            }
        }
        return weights;
    }

    // ── Reading the request ──────────────────────────────────────────────────

    /** A {@code SearchRequest} body, each known property checked for its type. */
    private static Map<String, Object> fromBody(AzureRequest req) {
        Object parsed = SearchDocuments.parse(SearchDocuments.body(req));
        if (!(parsed instanceof Map<?, ?> sent)) {
            throw new Refused(SearchErrors.invalid("An unexpected 'PrimitiveValue' node was found when reading from"
                    + " the JSON reader. A 'StartObject' node was expected."));
        }
        Map<String, Object> request = new LinkedHashMap<>();
        for (Map.Entry<?, ?> property : sent.entrySet()) {
            String name = String.valueOf(property.getKey());
            String type = TYPES.get(name);
            Object value = property.getValue();
            if (type == null || value == null) {
                continue;
            }
            request.put(name, checked(name, type, value));
        }
        return request;
    }

    private static Object checked(String name, String type, Object value) {
        boolean fits = switch (type) {
            case INT32 -> value instanceof Integer;
            case BOOLEAN -> value instanceof Boolean;
            case DOUBLE -> value instanceof Number;
            case STRING -> value instanceof String;
            default -> value instanceof List<?>;
        };
        if (!fits) {
            throw new Refused(SearchErrors.cannotConvert(literal(value), type));
        }
        if (ENUMS.containsKey(name) && ENUMS.get(name).stream().noneMatch(((String) value)::equalsIgnoreCase)) {
            throw new Refused(SearchErrors.invalid("Requested value '" + value + "' was not found."));
        }
        return value instanceof Number number && DOUBLE.equals(type) ? number.doubleValue() : value;
    }

    /** The query parameters of the GET form, read as the {@code SearchRequest} they stand for. */
    private static Map<String, Object> fromQuery(AzureRequest req) {
        Map<String, Object> request = new LinkedHashMap<>();
        for (Map.Entry<String, String> parameter : req.queryParams().entrySet()) {
            String name = GET_PARAMETERS.get(parameter.getKey());
            if (name == null || parameter.getValue() == null) {
                continue;
            }
            String text = parameter.getValue();
            String type = TYPES.get(name);
            Object value = switch (type) {
                case INT32 -> parseInt(text);
                case BOOLEAN -> parseBoolean(text);
                case DOUBLE -> parseDouble(text);
                case COLLECTION -> multi(req, parameter.getKey());
                default -> text;
            };
            request.put(name, checked(name, type, value));
        }
        return request;
    }

    private static Object parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new Refused(SearchErrors.cannotConvert(text, INT32));
        }
    }

    private static Object parseBoolean(String text) {
        if ("true".equalsIgnoreCase(text.trim()) || "false".equalsIgnoreCase(text.trim())) {
            return Boolean.parseBoolean(text.trim());
        }
        throw new Refused(SearchErrors.cannotConvert(text, BOOLEAN));
    }

    private static Object parseDouble(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new Refused(SearchErrors.cannotConvert(text, DOUBLE));
        }
    }

    private static List<String> multi(AzureRequest req, String parameter) {
        List<String> values = req.queryParamsMulti() == null ? null : req.queryParamsMulti().get(parameter);
        return values == null ? List.of(req.queryParams().get(parameter)) : values;
    }

    /** Refuses what the request asks for that floci-az does not serve yet. */
    private static void refuseUnserved(Map<String, Object> request) {
        if ("semantic".equalsIgnoreCase((String) request.get("queryType"))) {
            throw unserved("queryType 'semantic'");
        }
        for (String name : List.of("semanticConfiguration", "semanticQuery")) {
            if (request.get(name) instanceof String text && !text.isBlank()) {
                throw unserved("'" + name + "'");
            }
        }
        if (request.get("vectorQueries") instanceof List<?> list && !list.isEmpty()) {
            throw unserved("'vectorQueries'");
        }
        for (String name : List.of("answers", "captions", "debug")) {
            if (request.get(name) instanceof String text && !text.isBlank()
                    && !"none".equalsIgnoreCase(text) && !"disabled".equalsIgnoreCase(text)) {
                throw unserved("'" + name + "'");
            }
        }
    }

    private static Refused unserved(String what) {
        return new Refused(SearchErrors.error(400, NOT_SUPPORTED, "The search request uses " + what
                + ", which floci-az does not serve yet."));
    }

    /**
     * The fields a {@code highlight} list names, in order, each with its own limit or null for the default. A field
     * named twice keeps the last limit.
     */
    private static Map<String, Integer> highlightLimits(String names) {
        Map<String, Integer> limits = new LinkedHashMap<>();
        for (String entry : Arrays.stream(names.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList()) {
            Matcher limited = HIGHLIGHT_LIMIT.matcher(entry);
            if (limited.matches()) {
                limits.put(limited.group(1), Integer.valueOf(limited.group(2)));
            } else {
                limits.put(entry, null);
            }
        }
        return limits;
    }

    /**
     * The fields a comma-separated list names, each a searchable string field or sub-field, or every searchable one
     * when the list is empty.
     *
     * @param parameter the request property, for the message
     */
    private static List<TextField> chosen(String names, List<TextField> searchable,
                                          List<Map<String, Object>> fields, String parameter) {
        if (names == null || names.isBlank()) {
            return searchable;
        }
        List<TextField> chosen = new ArrayList<>();
        for (String path : Arrays.stream(names.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList()) {
            if (fieldAt(fields, path) == null) {
                throw new Refused(SearchErrors.error(400, SearchErrors.NO_CODE, "Invalid expression: Could not find"
                        + " a property named '" + path + "' on type 'search.document'.\r\nParameter name: "
                        + parameter));
            }
            Optional<TextField> text = searchable.stream().filter(field -> field.path().equals(path)).findFirst();
            if (text.isEmpty()) {
                throw new Refused(SearchErrors.error(400, SearchErrors.NO_CODE, "Invalid expression: '" + path
                        + "' is not a searchable field. Only searchable string fields can be used in " + parameter
                        + ".\r\nParameter name: " + parameter));
            }
            if ("highlight".equals(parameter) && Boolean.FALSE.equals(fieldAt(fields, path).get("retrievable"))) {
                // A fragment shows the field's text, which a non-retrievable field never answers (verify wording).
                throw new Refused(SearchErrors.error(400, SearchErrors.NO_CODE, "Invalid expression: '" + path
                        + "' is not a retrievable field. Only retrievable fields can be used in highlight."
                        + "\r\nParameter name: highlight"));
            }
            if (!chosen.contains(text.get())) {
                chosen.add(text.get());
            }
        }
        return chosen;
    }

    private static Map<String, Object> fieldAt(List<Map<String, Object>> fields, String path) {
        Map<String, Object> field = null;
        List<Map<String, Object>> candidates = fields;
        for (String part : path.split("/")) {
            field = candidates.stream().filter(f -> part.equals(f.get("name"))).findFirst().orElse(null);
            if (field == null) {
                return null;
            }
            candidates = SearchDocuments.fields(field);
        }
        return field;
    }

    // ── Small helpers ────────────────────────────────────────────────────────

    /** The query string of the next page of a GET search: the request's own, with the new skip and top. */
    private static String nextQuery(AzureRequest req, Map<String, Object> next) {
        List<String> parts = new ArrayList<>();
        parts.add("api-version=" + encode(apiVersion(req)));
        for (String key : req.queryParams().keySet()) {
            if (!"api-version".equals(key) && !"$skip".equals(key) && !"$top".equals(key)) {
                for (String value : multi(req, key)) {
                    parts.add(encode(key) + "=" + encode(value));
                }
            }
        }
        if (next.get("top") != null) {
            parts.add("$top=" + next.get("top"));
        }
        parts.add("$skip=" + next.get("skip"));
        return String.join("&", parts);
    }

    private static Map.Entry<String, String> param(String name, String value) {
        return new AbstractMap.SimpleImmutableEntry<>(name, value);
    }

    private static String apiVersion(AzureRequest req) {
        return req.queryParams().get("api-version");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20")
                .replace("%24", "$");
    }

    private static String literal(Object value) {
        return value instanceof String text ? text : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Map<String, String> types() {
        Map<String, String> types = new LinkedHashMap<>();
        for (String name : List.of("search", "searchFields", "searchMode", "queryType", "select", "filter",
                "orderby", "highlight", "highlightPreTag", "highlightPostTag", "scoringProfile", "sessionId",
                "scoringStatistics", "semanticConfiguration", "semanticErrorHandling", "semanticQuery", "answers",
                "captions", "debug", "vectorFilterMode")) {
            types.put(name, STRING);
        }
        types.put("top", INT32);
        types.put("skip", INT32);
        types.put("semanticMaxWaitInMilliseconds", INT32);
        types.put("count", BOOLEAN);
        types.put("minimumCoverage", DOUBLE);
        types.put("facets", COLLECTION);
        types.put("scoringParameters", COLLECTION);
        types.put("vectorQueries", "Collection(search.vectorQuery)");
        return Map.copyOf(types);
    }
}
