package io.floci.az.services.search;

import io.floci.az.core.XmlBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * How an index of Azure AI Search is laid out in its Solr collection. Pure functions: the collection's name, its
 * Schema API commands, and the Solr document for a stored one.
 *
 * <p>Solr holds what queries need and nothing else; the documents stay in floci-az's storage, which answers every hit.
 * The document key is {@code _key_}, a name no Azure field can take (they begin with a letter). Each searchable string
 * field, a sub-field of a complex one included, is a Solr text field of the same name, with {@code /} written as
 * {@code .}, so the full Lucene syntax's {@code field:term} needs no translation. A field under a complex collection
 * holds the values of every element.
 *
 * <p>Each filterable, sortable or facetable field also has a value field, {@code _v_} and its Solr name: the value
 * itself, with docValues, of the matching Solr type (a string passed through the field's normalizer). Filters,
 * sorting and facets use only these. Each element of a complex collection is also a child document, so a lambda
 * ({@code rooms/any(r: r/type eq 'Suite' and r/price lt 100)}) tests both conditions on the same element: a child
 * holds the value fields of its own element, {@code _scope_} (its collection's path) and {@code _depth_} (1 for an
 * element of a top-level collection, 2 below that). A document itself has depth 0, and every query keeps to those.
 * The document also holds the values of every element, for facets.
 *
 * <p>Azure's analyzers are Lucene's, so each maps to the same Lucene analyzer class or factory chain. A language
 * analyzer the Solr image lacks, a Microsoft one without a Lucene twin, and a custom analyzer (not served yet) fall
 * back to the standard analyzer.
 */
final class SearchSolrSchema {

    /** Raised when this mapping changes, so collections built by an older one are rebuilt. */
    static final int MAPPING_VERSION = 2;
    /** The Solr field that holds the document key. */
    static final String KEY = "_key_";
    /** How deep a Solr document sits: 0 for an Azure document, 1 for an element of its complex collections. */
    static final String DEPTH = "_depth_";
    /** The Azure path of the complex collection a child document is an element of. */
    static final String SCOPE = "_scope_";
    /**
     * The Solr names of the value fields that hold {@code NaN} in a document or child. A {@code NaN} is a value, so
     * {@code eq null} does not find it, but it compares with nothing, so its value field leaves it out.
     */
    static final String NAN = "_nan_";
    /** The prefix of a value field, which no Azure field name can take. */
    static final String VALUE_PREFIX = "_v_";
    /** The prefix under which child documents are nested in a Solr update. */
    private static final String CHILD_PREFIX = "_n_";
    /** The configset each collection's own configset is copied from. */
    static final String BASE_CONFIG_SET = "floci-az-search";

    private static final String STANDARD = "standard.lucene";
    private static final String STRING = "Edm.String";
    private static final String COMPLEX = "Edm.ComplexType";
    private static final String COMPLEX_COLLECTION = "Collection(Edm.ComplexType)";
    private static final String LUCENE = "org.apache.lucene.analysis.";
    private static final String STANDARD_CLASS = LUCENE + "standard.StandardAnalyzer";
    private static final String CLASSIC_SIMILARITY = "#Microsoft.Azure.Search.ClassicSimilarity";
    private static final double DEFAULT_K1 = 1.2;
    private static final double DEFAULT_B = 0.75;

    /**
     * The Lucene analyzer of each language, by the code in Azure's analyzer names. Polish and the Chinese smartcn
     * analyzer are not in the Solr image: Polish falls back to the standard analyzer, Chinese to Lucene's CJK one.
     */
    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("ar", "ar.ArabicAnalyzer"), Map.entry("hy", "hy.ArmenianAnalyzer"),
            Map.entry("eu", "eu.BasqueAnalyzer"), Map.entry("bg", "bg.BulgarianAnalyzer"),
            Map.entry("bn", "bn.BengaliAnalyzer"), Map.entry("ca", "ca.CatalanAnalyzer"),
            Map.entry("zh-hans", "cjk.CJKAnalyzer"), Map.entry("zh-hant", "cjk.CJKAnalyzer"),
            Map.entry("cs", "cz.CzechAnalyzer"), Map.entry("da", "da.DanishAnalyzer"),
            Map.entry("nl", "nl.DutchAnalyzer"), Map.entry("en", "en.EnglishAnalyzer"),
            Map.entry("et", "et.EstonianAnalyzer"), Map.entry("fi", "fi.FinnishAnalyzer"),
            Map.entry("fr", "fr.FrenchAnalyzer"), Map.entry("gl", "gl.GalicianAnalyzer"),
            Map.entry("de", "de.GermanAnalyzer"), Map.entry("el", "el.GreekAnalyzer"),
            Map.entry("hi", "hi.HindiAnalyzer"), Map.entry("hu", "hu.HungarianAnalyzer"),
            Map.entry("id", "id.IndonesianAnalyzer"), Map.entry("ga", "ga.IrishAnalyzer"),
            Map.entry("it", "it.ItalianAnalyzer"), Map.entry("ja", "ja.JapaneseAnalyzer"),
            Map.entry("ko", "ko.KoreanAnalyzer"), Map.entry("lv", "lv.LatvianAnalyzer"),
            Map.entry("lt", "lt.LithuanianAnalyzer"), Map.entry("no", "no.NorwegianAnalyzer"),
            Map.entry("nb", "no.NorwegianAnalyzer"), Map.entry("fa", "fa.PersianAnalyzer"),
            Map.entry("pt-br", "br.BrazilianAnalyzer"), Map.entry("pt-pt", "pt.PortugueseAnalyzer"),
            Map.entry("ro", "ro.RomanianAnalyzer"), Map.entry("ru", "ru.RussianAnalyzer"),
            Map.entry("sr-cyrillic", "sr.SerbianAnalyzer"), Map.entry("sr-latin", "sr.SerbianAnalyzer"),
            Map.entry("es", "es.SpanishAnalyzer"), Map.entry("sv", "sv.SwedishAnalyzer"),
            Map.entry("th", "th.ThaiAnalyzer"), Map.entry("tr", "tr.TurkishAnalyzer"));

    /**
     * A searchable string field as Solr holds it.
     *
     * @param path           the field's path in Azure's words, {@code address/city}
     * @param solrName       the Solr field, {@code address.city}
     * @param multiValued    whether it holds several values: a collection, or a field under a complex collection
     * @param indexAnalyzer  the analyzer for indexing
     * @param searchAnalyzer the analyzer for queries
     */
    record TextField(String path, String solrName, boolean multiValued, String indexAnalyzer, String searchAnalyzer) {
        String typeName() {
            return "t_" + typeToken(indexAnalyzer) + "__" + typeToken(searchAnalyzer);
        }
    }

    /**
     * A filterable, sortable or facetable field as Solr holds it.
     *
     * @param path       the field's path in Azure's words, {@code rooms/type}
     * @param solrName   the Solr value field, {@code _v_rooms.type}
     * @param type       the EDM type of one value, {@code Edm.String} for a {@code Collection(Edm.String)}
     * @param collection whether the field itself is a collection
     * @param scope      the path of the innermost complex collection the field is under, or {@code ""}
     * @param normalizer what a string value passes through
     */
    record ValueField(String path, String solrName, String type, boolean collection, String scope, boolean filterable,
                      boolean sortable, boolean facetable, UnaryOperator<String> normalizer) {

        /** Whether the Solr field holds several values: a collection, or a field of a complex collection's elements. */
        boolean multiValued() {
            return collection || !scope.isEmpty();
        }

        /** Whether a document has one value of it: neither a collection nor under one. */
        boolean singleValued() {
            return !multiValued();
        }
    }

    /**
     * A complex collection, whose elements are child documents.
     *
     * @param path   its Azure path, {@code rooms} or {@code rooms/beds}
     * @param parent the path of the complex collection it is under, or {@code ""}
     * @param depth  the depth of its elements' documents: 1 for a top-level collection
     */
    record Scope(String path, String parent, int depth) {
    }

    /** How an index is laid out in Solr: its text fields, value fields and complex collections. */
    record Layout(List<TextField> textFields, List<ValueField> valueFields, List<Scope> scopes) {

        Optional<ValueField> valueField(String path) {
            return valueFields.stream().filter(field -> field.path().equals(path)).findFirst();
        }

        Optional<Scope> scope(String path) {
            return scopes.stream().filter(scope -> scope.path().equals(path)).findFirst();
        }
    }

    private SearchSolrSchema() {
    }

    /** The layout of an index definition. */
    static Layout layout(Map<String, Object> definition) {
        List<ValueField> values = new ArrayList<>();
        List<Scope> scopes = new ArrayList<>();
        collectValues(fields(definition), "", "", 0, definition, values, scopes);
        return new Layout(textFields(definition), List.copyOf(values), List.copyOf(scopes));
    }

    private static void collectValues(List<Map<String, Object>> fields, String prefix, String scope, int depth,
                                      Map<String, Object> definition, List<ValueField> values, List<Scope> scopes) {
        for (Map<String, Object> field : fields) {
            String type = String.valueOf(field.get("type"));
            String path = prefix + field.get("name");
            if (COMPLEX_COLLECTION.equals(type)) {
                scopes.add(new Scope(path, scope, depth + 1));
                collectValues(fields(field), path + "/", path, depth + 1, definition, values, scopes);
                continue;
            }
            if (COMPLEX.equals(type)) {
                collectValues(fields(field), path + "/", scope, depth, definition, values, scopes);
                continue;
            }
            boolean filterable = Boolean.TRUE.equals(field.get("filterable"));
            boolean sortable = Boolean.TRUE.equals(field.get("sortable"));
            boolean facetable = Boolean.TRUE.equals(field.get("facetable"));
            boolean collection = type.startsWith("Collection(");
            String element = collection ? type.substring("Collection(".length(), type.length() - 1) : type;
            if (!(filterable || sortable || facetable) || solrType(element) == null) {
                continue;
            }
            UnaryOperator<String> normalizer = STRING.equals(element)
                    ? SearchNormalizers.of((String) field.get("normalizer"), definition) : UnaryOperator.identity();
            values.add(new ValueField(path, VALUE_PREFIX + solrName(path), element, collection, scope, filterable,
                    sortable, facetable, normalizer));
        }
    }

    /** The field of an index definition at a path, {@code address/city}, or null when there is none. */
    static Map<String, Object> definitionField(Map<String, Object> definition, String path) {
        List<Map<String, Object>> candidates = fields(definition);
        Map<String, Object> found = null;
        for (String part : path.split("/")) {
            found = candidates.stream().filter(field -> part.equals(field.get("name"))).findFirst().orElse(null);
            if (found == null) {
                return null;
            }
            candidates = fields(found);
        }
        return found;
    }

    /** The Solr type of a value of an EDM type, or null for one Solr does not hold (a vector's elements). */
    static String solrType(String edmType) {
        return switch (edmType) {
            case STRING -> "string";
            case "Edm.Int32" -> "pint";
            case "Edm.Int64" -> "plong";
            case "Edm.Double" -> "pdouble";
            case "Edm.Boolean" -> "boolean";
            case "Edm.DateTimeOffset" -> "pdate";
            case "Edm.GeographyPoint" -> "location";
            default -> null;
        };
    }

    /**
     * The collection of an index: a name Solr accepts and that is the same for the same index in any case, as
     * Azure's index and service names are case-insensitive.
     */
    static String collection(String cloud, String service, String index) {
        String id = String.join("\0", cloud, service, index).toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            return "az_" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The searchable string fields of an index definition, sub-fields of complex fields included. */
    static List<TextField> textFields(Map<String, Object> definition) {
        List<TextField> found = new ArrayList<>();
        collect(fields(definition), "", false, found);
        return found;
    }

    private static void collect(List<Map<String, Object>> fields, String prefix, boolean underCollection,
                                List<TextField> found) {
        for (Map<String, Object> field : fields) {
            String type = String.valueOf(field.get("type"));
            String path = prefix + field.get("name");
            if ("Edm.ComplexType".equals(type) || "Collection(Edm.ComplexType)".equals(type)) {
                collect(fields(field), path + "/", underCollection || type.startsWith("Collection("), found);
                continue;
            }
            boolean text = "Edm.String".equals(type) || "Collection(Edm.String)".equals(type);
            if (!text || !Boolean.TRUE.equals(field.get("searchable"))) {
                continue;
            }
            String analyzer = (String) field.get("analyzer");
            String indexAnalyzer = analyzer != null ? analyzer
                    : field.get("indexAnalyzer") instanceof String name ? name : STANDARD;
            String searchAnalyzer = analyzer != null ? analyzer
                    : field.get("searchAnalyzer") instanceof String name ? name : indexAnalyzer;
            found.add(new TextField(path, solrName(path), underCollection || type.startsWith("Collection("),
                    indexAnalyzer, searchAnalyzer));
        }
    }

    /** The Solr name of a field path: {@code address/city} is {@code address.city}. */
    static String solrName(String path) {
        return path.replace('/', '.');
    }

    /** The Azure path of a Solr field name. */
    static String azurePath(String solrName) {
        return solrName.replace('.', '/');
    }

    /** The Schema API commands that add an index's field types and fields to its new collection. */
    static Map<String, Object> schema(Map<String, Object> definition) {
        Layout layout = layout(definition);
        List<TextField> fields = layout.textFields();
        Map<String, Object> similarity = similarity(definition.get("similarity"));
        Map<String, Map<String, Object>> types = new LinkedHashMap<>();
        List<Map<String, Object>> solrFields = new ArrayList<>();
        for (TextField field : fields) {
            types.computeIfAbsent(field.typeName(), name -> fieldType(name, field, similarity));
            Map<String, Object> solrField = new LinkedHashMap<>();
            solrField.put("name", field.solrName());
            solrField.put("type", field.typeName());
            solrField.put("indexed", true);
            // Stored for the highlighter, which reads the text it marks up.
            solrField.put("stored", true);
            solrField.put("multiValued", field.multiValued());
            solrFields.add(solrField);
        }
        for (ValueField field : layout.valueFields()) {
            Map<String, Object> solrField = new LinkedHashMap<>();
            solrField.put("name", field.solrName());
            solrField.put("type", solrType(field.type()));
            solrField.put("indexed", true);
            solrField.put("stored", false);
            solrField.put("docValues", true);
            solrField.put("multiValued", field.multiValued());
            solrFields.add(solrField);
        }
        Map<String, Object> commands = new LinkedHashMap<>();
        if (!types.isEmpty()) {
            commands.put("add-field-type", List.copyOf(types.values()));
        }
        if (!solrFields.isEmpty()) {
            commands.put("add-field", solrFields);
        }
        return commands;
    }

    private static Map<String, Object> fieldType(String name, TextField field, Map<String, Object> similarity) {
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("name", name);
        type.put("class", "solr.TextField");
        type.put("similarity", similarity);
        if (field.indexAnalyzer().equals(field.searchAnalyzer())) {
            type.put("analyzer", analyzer(field.indexAnalyzer()));
        } else {
            type.put("indexAnalyzer", analyzer(field.indexAnalyzer()));
            type.put("queryAnalyzer", analyzer(field.searchAnalyzer()));
        }
        return type;
    }

    /** The Solr analyzer of an Azure analyzer name. */
    static Map<String, Object> analyzer(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "standardasciifolding.lucene" -> chain(Map.of("class", "solr.StandardTokenizerFactory"),
                    filter("solr.LowerCaseFilterFactory"), filter("solr.ASCIIFoldingFilterFactory"));
            case "keyword" -> analyzerClass("core.KeywordAnalyzer");
            case "simple" -> analyzerClass("core.SimpleAnalyzer");
            case "whitespace" -> analyzerClass("core.WhitespaceAnalyzer");
            // Lucene's StopAnalyzer: letters, lowercased, English stopwords removed.
            case "stop" -> chain(Map.of("class", "solr.LetterTokenizerFactory"),
                    filter("solr.LowerCaseFilterFactory"), filter("solr.StopFilterFactory"));
            // Azure's PatternAnalyzer defaults: split on non-word characters, lowercase.
            case "pattern" -> chain(Map.of("class", "solr.PatternTokenizerFactory", "pattern", "\\W+"),
                    filter("solr.LowerCaseFilterFactory"));
            default -> language(lower).map(SearchSolrSchema::analyzerClass)
                    .orElseGet(() -> Map.of("class", STANDARD_CLASS));
        };
    }

    /** The Lucene analyzer of {@code xx.lucene} or {@code xx.microsoft}, when the image has one for the language. */
    private static Optional<String> language(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || !(name.endsWith(".lucene") || name.endsWith(".microsoft"))) {
            return Optional.empty();
        }
        return Optional.ofNullable(LANGUAGES.get(name.substring(0, dot)));
    }

    /** Azure's similarity of the index: BM25 with its {@code k1} and {@code b}, or the classic TF-IDF one. */
    static Map<String, Object> similarity(Object similarity) {
        Map<String, Object> sent = similarity instanceof Map<?, ?> map ? cast(map) : Map.of();
        if (CLASSIC_SIMILARITY.equals(sent.get("@odata.type"))) {
            return Map.of("class", "solr.ClassicSimilarityFactory");
        }
        double k1 = sent.get("k1") instanceof Number number ? number.doubleValue() : DEFAULT_K1;
        double b = sent.get("b") instanceof Number number ? number.doubleValue() : DEFAULT_B;
        Map<String, Object> bm25 = new LinkedHashMap<>();
        bm25.put("class", "solr.BM25SimilarityFactory");
        bm25.put("k1", Double.toString(k1));
        bm25.put("b", Double.toString(b));
        return bm25;
    }

    /**
     * The Solr document of a stored one: its key, the values of its searchable string fields and of its value fields
     * (those under a complex collection gathered from every element), and a child document per element of each
     * complex collection.
     */
    static Map<String, Object> document(Layout layout, String key, Map<String, Object> stored) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put(KEY, key);
        document.put(DEPTH, 0);
        for (TextField field : layout.textFields()) {
            List<Object> values = new ArrayList<>();
            gather(stored, field.path().split("/"), 0, values);
            if (values.isEmpty()) {
                continue;
            }
            document.put(field.solrName(), field.multiValued() ? values : values.getFirst());
        }
        for (ValueField field : layout.valueFields()) {
            putValues(document, field, stored, field.path(), field.multiValued());
        }
        // A document key never starts with '_', and its length makes the rest unambiguous.
        addChildren(document, layout, "", "_" + key.length() + ":" + key, stored);
        return document;
    }

    /**
     * The child documents of the elements of the complex collections directly under {@code scope}. A collection may
     * sit inside plain complex fields of its scope ({@code address/rooms}), so it is found by its path relative to
     * the scope, and its children are labelled by that path, which keeps {@code rooms} and {@code address/rooms}
     * apart.
     */
    private static void addChildren(Map<String, Object> parent, Layout layout, String scope, String parentKey,
                                    Map<String, Object> holder) {
        for (Scope collection : layout.scopes()) {
            if (!collection.parent().equals(scope)) {
                continue;
            }
            String relative = collection.path().substring(scope.isEmpty() ? 0 : scope.length() + 1);
            Object elements = holder;
            for (String segment : relative.split("/")) {
                elements = elements instanceof Map<?, ?> map ? map.get(segment) : null;
            }
            if (!(elements instanceof List<?> list) || list.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> children = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof Map<?, ?> element)) {
                    continue;
                }
                Map<String, Object> elementValues = cast(element);
                String childKey = parentKey + "#" + solrName(relative) + "." + i;
                Map<String, Object> child = new LinkedHashMap<>();
                child.put(KEY, childKey);
                child.put(SCOPE, collection.path());
                child.put(DEPTH, collection.depth());
                for (ValueField field : layout.valueFields()) {
                    if (field.scope().equals(collection.path())) {
                        putValues(child, field, elementValues, field.path().substring(collection.path().length() + 1),
                                field.collection());
                    }
                }
                addChildren(child, layout, collection.path(), childKey, elementValues);
                children.add(child);
            }
            if (!children.isEmpty()) {
                parent.put(CHILD_PREFIX + solrName(relative), children);
            }
        }
    }

    private static void putValues(Map<String, Object> document, ValueField field, Map<String, Object> holder,
                                  String relativePath, boolean multiValued) {
        List<Object> raw = new ArrayList<>();
        gather(holder, relativePath.split("/"), 0, raw);
        List<Object> values = new ArrayList<>();
        for (Object value : raw) {
            Object converted = solrValue(field, value);
            if (converted != null) {
                values.add(converted);
            } else if ("NaN".equals(value) && "Edm.Double".equals(field.type())) {
                markNaN(document, field.solrName());
            }
        }
        if (!values.isEmpty()) {
            document.put(field.solrName(), multiValued ? values : values.getFirst());
        }
    }

    /**
     * A stored value as Solr takes it: a string normalized, a point as {@code lat,lon}, the infinities as Java
     * writes them. {@code NaN} is left out, so it equals nothing and is not less or greater than anything, as Azure
     * has it; {@link #NAN} marks that the field holds one.
     */
    static Object solrValue(ValueField field, Object value) {
        return switch (value) {
            case String text when STRING.equals(field.type()) -> field.normalizer().apply(text);
            case String text when "Edm.Double".equals(field.type()) -> switch (text) {
                case "INF" -> "Infinity";
                case "-INF" -> "-Infinity";
                default -> null;
            };
            case Map<?, ?> point when point.get("coordinates") instanceof List<?> coordinates
                    && coordinates.size() == 2 -> coordinates.get(1) + "," + coordinates.get(0);
            default -> value;
        };
    }

    @SuppressWarnings("unchecked")
    private static void markNaN(Map<String, Object> document, String solrName) {
        List<Object> marked = (List<Object>) document.computeIfAbsent(NAN, name -> new ArrayList<>());
        if (!marked.contains(solrName)) {
            marked.add(solrName);
        }
    }

    private static void gather(Object value, String[] path, int depth, List<Object> values) {
        if (value instanceof List<?> list) {
            for (Object item : list) {
                gather(item, path, depth, values);
            }
            return;
        }
        if (depth == path.length) {
            if (value != null) {
                values.add(value);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            gather(map.get(path[depth]), path, depth + 1, values);
        }
    }

    /**
     * The configset every collection starts from: a mutable managed schema holding the key, the fields SolrCloud and
     * child documents need and the types of value fields, no field guessing, and the {@code /select} handler. The types and fields of an index are
     * added with the Schema API.
     */
    static byte[] baseConfigSet() {
        String solrconfig = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("config")
                .elem("luceneMatchVersion", "10.0")
                .startAttr("schemaFactory", "class", "ManagedIndexSchemaFactory")
                .startAttr("bool", "name", "mutable").raw("true").end("bool")
                .startAttr("str", "name", "managedSchemaResourceName").raw("managed-schema.xml").end("str")
                .end("schemaFactory")
                .startAttr("updateHandler", "class", "solr.DirectUpdateHandler2")
                .selfClose("updateLog")
                .end("updateHandler")
                .selfClose("requestHandler", "name", "/select", "class", "solr.SearchHandler")
                .end("config")
                .build();
        String schema = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .startAttr("schema", "name", "floci-az-search", "version", "1.7")
                .selfClose("fieldType", "name", "string", "class", "solr.StrField")
                .selfClose("fieldType", "name", "plong", "class", "solr.LongPointField")
                .selfClose("fieldType", "name", "pint", "class", "solr.IntPointField")
                .selfClose("fieldType", "name", "pdouble", "class", "solr.DoublePointField")
                .selfClose("fieldType", "name", "pdate", "class", "solr.DatePointField")
                .selfClose("fieldType", "name", "boolean", "class", "solr.BoolField")
                .selfClose("fieldType", "name", "location", "class", "solr.LatLonPointSpatialField")
                .selfClose("fieldType", "name", "_nest_path_", "class", "solr.NestPathField")
                .selfClose("field", "name", KEY, "type", "string", "indexed", "true", "stored", "true",
                        "required", "true")
                .selfClose("field", "name", "_version_", "type", "plong", "indexed", "false", "stored", "false",
                        "docValues", "true")
                .selfClose("field", "name", "_root_", "type", "string", "indexed", "true", "stored", "false")
                .selfClose("field", "name", "_nest_path_", "type", "_nest_path_")
                .selfClose("field", "name", DEPTH, "type", "pint", "indexed", "true", "stored", "false",
                        "docValues", "true")
                .selfClose("field", "name", SCOPE, "type", "string", "indexed", "true", "stored", "false",
                        "docValues", "true")
                .selfClose("field", "name", NAN, "type", "string", "indexed", "true", "stored", "false",
                        "multiValued", "true")
                .elem("uniqueKey", KEY)
                .end("schema")
                .build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("solrconfig.xml"));
            zip.write(solrconfig.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("managed-schema.xml"));
            zip.write(schema.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException("The Solr configset could not be zipped", e);
        }
        return bytes.toByteArray();
    }

    // ── Small helpers ────────────────────────────────────────────────────────

    private static Map<String, Object> analyzerClass(String relative) {
        return Map.of("class", LUCENE + relative);
    }

    @SafeVarargs
    private static Map<String, Object> chain(Map<String, Object> tokenizer, Map<String, Object>... filters) {
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("tokenizer", tokenizer);
        chain.put("filters", List.of(filters));
        return chain;
    }

    private static Map<String, Object> filter(String factory) {
        return Map.of("class", factory);
    }

    private static String typeToken(String analyzer) {
        return analyzer.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(Map<String, Object> holder) {
        return holder.get("fields") instanceof List<?> fields ? (List<Map<String, Object>>) fields : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
