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
 * <p>Azure's analyzers are Lucene's, so each maps to the same Lucene analyzer class or factory chain. A language
 * analyzer the Solr image lacks, a Microsoft one without a Lucene twin, and a custom analyzer (not served yet) fall
 * back to the standard analyzer.
 */
final class SearchSolrSchema {

    /** Raised when this mapping changes, so collections built by an older one are rebuilt. */
    static final int MAPPING_VERSION = 1;
    /** The Solr field that holds the document key. */
    static final String KEY = "_key_";
    /** The configset each collection's own configset is copied from. */
    static final String BASE_CONFIG_SET = "floci-az-search";

    private static final String STANDARD = "standard.lucene";
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

    private SearchSolrSchema() {
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
        List<TextField> fields = textFields(definition);
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
        Map<String, Object> commands = new LinkedHashMap<>();
        if (!types.isEmpty()) {
            commands.put("add-field-type", List.copyOf(types.values()));
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
     * The Solr document of a stored one: its key and the values of its searchable string fields. Values under a
     * complex collection are gathered from every element.
     */
    static Map<String, Object> document(List<TextField> fields, String key, Map<String, Object> stored) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put(KEY, key);
        for (TextField field : fields) {
            List<Object> values = new ArrayList<>();
            gather(stored, field.path().split("/"), 0, values);
            if (values.isEmpty()) {
                continue;
            }
            document.put(field.solrName(), field.multiValued() ? values : values.getFirst());
        }
        return document;
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
     * The configset every collection starts from: a mutable managed schema holding only the key and the fields
     * SolrCloud needs, no field guessing, and the {@code /select} handler. The types and fields of an index are
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
                .selfClose("fieldType", "name", "_nest_path_", "class", "solr.NestPathField")
                .selfClose("field", "name", KEY, "type", "string", "indexed", "true", "stored", "true",
                        "required", "true")
                .selfClose("field", "name", "_version_", "type", "plong", "indexed", "false", "stored", "false",
                        "docValues", "true")
                .selfClose("field", "name", "_root_", "type", "string", "indexed", "true", "stored", "false")
                .selfClose("field", "name", "_nest_path_", "type", "_nest_path_")
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
