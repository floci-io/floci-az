package io.floci.az.services.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.StoredObject;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The index definitions of the search services: the {@code /indexes} operations of the data-plane spec
 * ({@code searchservice.json}).
 *
 * <pre>
 *   POST   /indexes
 *   GET    /indexes                       ($select)
 *   PUT    /indexes('{name}')             (If-Match, If-None-Match)
 *   GET    /indexes('{name}')
 *   DELETE /indexes('{name}')             (If-Match, If-None-Match)
 *   GET    /indexes('{name}')/search.stats
 * </pre>
 *
 * <p>A definition is stored as the JSON the service answers, not as a typed record: the sections floci-az does not
 * act on yet (scoring profiles, analyzers, suggesters, semantic and vector configuration) must come back as they
 * were sent, whatever the api-version added to them. The fields are the exception: they are validated, and their
 * attributes are filled with the defaults Azure answers.
 *
 * <p>The definitions share the {@code search} backend with the ARM resources. Their keys are {@code idx}, the
 * cloud, the service and the index, separated by null bytes; no ARM id starts that way.
 */
@ApplicationScoped
public class SearchIndexes {

    private static final Logger LOG = Logger.getLogger(SearchIndexes.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String STORE = "search";
    /** A null byte cannot appear in a service or index name, so it separates the parts of a key safely. */
    private static final char SEP = '\0';
    private static final String KEY_PREFIX = "idx" + SEP;

    /**
     * Index names, from Microsoft's naming rules: 2 to 128 lowercase letters, digits, dashes and underscores, the
     * first a letter or digit, and no consecutive dashes or underscores.
     */
    private static final int MIN_NAME_LENGTH = 2;
    private static final int MAX_NAME_LENGTH = 128;
    private static final Pattern INDEX_NAME = Pattern.compile("[a-z0-9](?:[-_]?[a-z0-9])*[-_]?");
    /** Field names are reserved with this prefix. */
    private static final String RESERVED_FIELD_PREFIX = "azureSearch";
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern COLLECTION = Pattern.compile("Collection\\((.+)\\)");

    private static final String STRING = "Edm.String";
    private static final String GEOGRAPHY_POINT = "Edm.GeographyPoint";
    private static final String COMPLEX = "Edm.ComplexType";
    private static final List<String> SIMPLE_TYPES = List.of(STRING, "Edm.Int32", "Edm.Int64", "Edm.Double",
            "Edm.Boolean", "Edm.DateTimeOffset", GEOGRAPHY_POINT);
    /** The element types of a vector field, which the spec allows inside a collection only. */
    private static final List<String> VECTOR_TYPES = List.of("Edm.Single", "Edm.Half", "Edm.Int16", "Edm.SByte",
            "Edm.Byte");
    private static final int MIN_DIMENSIONS = 2;
    private static final int MAX_DIMENSIONS = 4096;

    /** The boolean attributes of a simple field, in the order Azure answers them. */
    private static final List<String> FLAGS = List.of("searchable", "filterable", "retrievable", "stored", "sortable",
            "facetable", "key");
    private static final List<String> STRING_ATTRIBUTES = List.of("analyzer", "searchAnalyzer", "indexAnalyzer",
            "normalizer", "vectorSearchProfile", "vectorEncoding");
    /**
     * What a complex field may not set: the spec says each of these "must be null for complex fields", and
     * {@code synonymMaps} null or empty.
     */
    private static final List<String> SIMPLE_ONLY = List.of("key", "searchable", "filterable", "retrievable", "stored",
            "sortable", "facetable", "analyzer", "searchAnalyzer", "indexAnalyzer", "normalizer");
    /** What an update may not change on a field that exists: the attributes that decide how it was indexed. */
    private static final List<String> IMMUTABLE = List.of("type", "key", "searchable", "filterable", "sortable",
            "facetable", "stored", "analyzer", "indexAnalyzer", "normalizer", "dimensions", "vectorEncoding");

    private static final List<String> LIST_SECTIONS = List.of("scoringProfiles", "suggesters", "analyzers",
            "tokenizers", "tokenFilters", "charFilters", "normalizers");
    /** The analysis sections an update can add to only with {@code allowIndexDowntime=true}. */
    private static final List<String> DOWNTIME_SECTIONS = List.of("analyzers", "tokenizers", "tokenFilters",
            "charFilters");
    private static final List<String> OBJECT_SECTIONS = List.of("corsOptions", "encryptionKey", "similarity",
            "semantic", "vectorSearch");
    private static final List<String> STRING_SECTIONS = List.of("description", "defaultScoringProfile");
    private static final String BM25 = "#Microsoft.Azure.Search.BM25Similarity";

    private static final String ETAG = "@odata.etag";
    private static final String CONTEXT = "@odata.context";

    /**
     * The search service that owns an index: its name, and the cloud the request addressed, since the same name
     * can be taken in two clouds.
     */
    private record Service(String cloud, String name) {
    }

    /** A stored definition with its ETag. */
    private record Entry(Map<String, Object> definition, String etag) {
    }

    /** A refused request, thrown from the validation of a definition and carrying the answer. */
    private static final class Refused extends RuntimeException {
        private final transient Response response;

        Refused(Response response) {
            super(null, null, false, false);
            this.response = response;
        }

        Response response() {
            return response;
        }
    }

    private final StorageBackend<String, StoredObject> storage;
    /** Guards a read of an index followed by its write, so the ETag conditions and the write are one step. */
    private final Object writeLock = new Object();
    private final AtomicLong etagSequence = new AtomicLong();

    @Inject
    public SearchIndexes(StorageFactory storageFactory) {
        this.storage = storageFactory.create(STORE);
    }

    /**
     * Serves a request under {@code /indexes}.
     *
     * @param cloud      the name of the cloud the request addresses
     * @param serviceName the search service the request addresses
     * @param index      the index it names, or empty for the collection
     * @param rest       the path segments after the index
     * @param serviceUrl the service's URL as the caller addressed it, for the links in the answer
     */
    public Response handle(AzureRequest req, String cloud, String serviceName, Optional<String> index,
                           List<String> rest, String serviceUrl) {
        Service service = new Service(cloud, serviceName);
        String method = req.method().toUpperCase(Locale.ROOT);
        try {
            if (index.isEmpty()) {
                return switch (method) {
                    case "POST" -> create(req, service, serviceUrl);
                    case "GET" -> list(req, service, serviceUrl);
                    default -> SearchErrors.methodNotAllowed();
                };
            }
            if (rest.isEmpty()) {
                return switch (method) {
                    case "PUT" -> createOrUpdate(req, service, index.get(), serviceUrl);
                    case "GET" -> read(service, index.get())
                            .map(entry -> answer(200, entry, serviceUrl))
                            .orElseGet(() -> indexNotFound(service, index.get()));
                    case "DELETE" -> delete(req, service, index.get());
                    default -> SearchErrors.methodNotAllowed();
                };
            }
            if (rest.size() == 1 && rest.getFirst().equalsIgnoreCase("search.stats")) {
                return "GET".equals(method) ? statistics(service, index.get()) : SearchErrors.methodNotAllowed();
            }
            return SearchErrors.notFound("This index operation is not served yet: " + String.join("/", rest));
        } catch (Refused refused) {
            return refused.response();
        } catch (ArmJson.InvalidBodyException e) {
            return SearchErrors.invalid("The request body is not a valid JSON object.");
        }
    }

    // ── Operations ───────────────────────────────────────────────────────────

    /** {@code Indexes_Create}: {@code 201}, or {@code 409} for a name that is taken. */
    private Response create(AzureRequest req, Service service, String serviceUrl) {
        Map<String, Object> definition = normalise(ArmJson.parseBodyStrict(req));
        String name = (String) definition.get("name");
        synchronized (writeLock) {
            if (read(service, name).isPresent()) {
                return SearchErrors.error(409, "ResourceNameAlreadyInUse",
                        "Cannot create index '" + name + "' because it already exists.");
            }
            Entry stored = write(service, definition);
            LOG.infov("Search: created index {0} in service {1}", name, service.name());
            return created(stored, serviceUrl);
        }
    }

    /**
     * {@code Indexes_CreateOrUpdate}: {@code 201} for a new index and {@code 200} for one that exists. The spec
     * requires {@code Prefer: return=representation}, so the definition is always answered.
     */
    private Response createOrUpdate(AzureRequest req, Service service, String name, String serviceUrl) {
        Map<String, Object> body = ArmJson.parseBodyStrict(req);
        if (body.get("name") instanceof String sent && !sent.equals(name)) {
            throw refuse("The index name in the request body, '" + sent + "', is not the one in the URL, '"
                    + name + "'.");
        }
        Map<String, Object> definition = normalise(body);
        synchronized (writeLock) {
            Optional<Entry> existing = read(service, name);
            if (conditionsFail(req, existing)) {
                return SearchErrors.preconditionFailed();
            }
            if (existing.isPresent()) {
                refuseIndexChanges(existing.get().definition(), body, definition, downtimeAllowed(req));
            }
            Entry stored = write(service, definition);
            if (existing.isPresent()) {
                return answer(200, stored, serviceUrl);
            }
            LOG.infov("Search: created index {0} in service {1}", name, service.name());
            return created(stored, serviceUrl);
        }
    }

    /** {@code Indexes_Delete}: {@code 204}, or {@code 404} for an index that does not exist. */
    private Response delete(AzureRequest req, Service service, String name) {
        synchronized (writeLock) {
            Optional<Entry> existing = read(service, name);
            if (existing.isEmpty()) {
                return indexNotFound(service, name);
            }
            if (conditionsFail(req, existing)) {
                return SearchErrors.preconditionFailed();
            }
            storage.delete(key(service, name));
        }
        LOG.infov("Search: deleted index {0} of service {1}", name, service.name());
        return Response.noContent().build();
    }

    /** {@code Indexes_List}: every index by name, or the properties {@code $select} names. */
    private Response list(AzureRequest req, Service service, String serviceUrl) {
        Optional<List<String>> select = select(req.queryParams().get("$select"));
        List<Map<String, Object>> value = new ArrayList<>();
        for (Entry entry : entries(service)) {
            Map<String, Object> full = body(entry);
            if (select.isEmpty()) {
                value.add(full);
                continue;
            }
            Map<String, Object> chosen = new LinkedHashMap<>();
            for (String property : select.get()) {
                if (full.containsKey(property)) {
                    chosen.put(property, full.get(property));
                }
            }
            value.add(chosen);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(CONTEXT, serviceUrl + "/$metadata#indexes"
                + select.map(properties -> "(" + String.join(",", properties) + ")").orElse(""));
        body.put("value", value);
        return SearchErrors.json(200, body).build();
    }

    /**
     * {@code Indexes_GetStatistics}. No document is stored yet, so every counter is zero.
     */
    private Response statistics(Service service, String name) {
        if (read(service, name).isEmpty()) {
            return indexNotFound(service, name);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentCount", 0);
        body.put("storageSize", 0);
        body.put("vectorIndexSize", 0);
        return SearchErrors.json(200, body).build();
    }

    // ── For the handler and the ARM service ──────────────────────────────────

    /** The definition of {@code index} in {@code service}, without its ETag. */
    public Optional<Map<String, Object>> find(String cloud, String service, String index) {
        return read(new Service(cloud, service), index).map(Entry::definition);
    }

    /** How many indexes {@code service} has. */
    public int count(String cloud, String service) {
        String prefix = prefix(new Service(cloud, service));
        return (int) storage.keys().stream().filter(key -> key.startsWith(prefix)).count();
    }

    /** Forgets the indexes of {@code service} in {@code cloud}: its ARM resource was deleted. */
    public void deleteAll(String cloud, String service) {
        deleteKeysStartingWith(prefix(new Service(cloud, service)));
    }

    /** Forgets every index: {@code POST /_admin/reset}. */
    public void clear() {
        deleteKeysStartingWith(KEY_PREFIX);
    }

    /** Deletes in one batch, so a persistent backend saves once rather than once per index. */
    private void deleteKeysStartingWith(String prefix) {
        synchronized (writeLock) {
            Set<String> doomed = storage.keys().stream().filter(key -> key.startsWith(prefix))
                    .collect(Collectors.toSet());
            if (!doomed.isEmpty()) {
                storage.applyBatch(Map.of(), doomed);
            }
        }
    }

    // ── Validation ───────────────────────────────────────────────────────────

    /**
     * The definition as it is stored and answered: checked, with the field attributes and the top-level sections
     * filled in. The JSON type of a value is checked before anything is defaulted, so a wrong type is refused
     * rather than read as absent.
     */
    private static Map<String, Object> normalise(Map<String, Object> body) {
        if (body.get("name") == null) {
            throw refuse("The index needs a 'name'.");
        }
        if (!(body.get("name") instanceof String name) || name.length() < MIN_NAME_LENGTH
                || name.length() > MAX_NAME_LENGTH || !INDEX_NAME.matcher(name).matches()) {
            throw refuse("Index name must be " + MIN_NAME_LENGTH + " to " + MAX_NAME_LENGTH + " lowercase letters,"
                    + " digits, dashes or underscores, start with a letter or digit, and have no consecutive dashes"
                    + " or underscores.");
        }
        if (!(body.get("fields") instanceof List<?> sentFields)) {
            throw refuse("The index needs 'fields', a list of field definitions.");
        }
        refuseMistypedSections(body);

        List<Map<String, Object>> fields = normaliseFields(sentFields, true, false, vectorProfiles(body), "");
        long keys = fields.stream().filter(field -> Boolean.TRUE.equals(field.get("key"))).count();
        if (keys != 1) {
            throw refuse("The index must have exactly one key field, and it has " + keys + ".");
        }

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", name);
        if (body.get("description") != null) {
            definition.put("description", body.get("description"));
        }
        definition.put("fields", fields);
        definition.put("scoringProfiles", listOrEmpty(body, "scoringProfiles"));
        definition.put("defaultScoringProfile", body.get("defaultScoringProfile"));
        definition.put("corsOptions", body.get("corsOptions"));
        definition.put("suggesters", listOrEmpty(body, "suggesters"));
        definition.put("analyzers", listOrEmpty(body, "analyzers"));
        definition.put("normalizers", listOrEmpty(body, "normalizers"));
        definition.put("tokenizers", listOrEmpty(body, "tokenizers"));
        definition.put("tokenFilters", listOrEmpty(body, "tokenFilters"));
        definition.put("charFilters", listOrEmpty(body, "charFilters"));
        definition.put("encryptionKey", body.get("encryptionKey"));
        definition.put("similarity", body.get("similarity") == null ? defaultSimilarity() : body.get("similarity"));
        definition.put("semantic", body.get("semantic"));
        definition.put("vectorSearch", body.get("vectorSearch"));
        // What a newer api-version adds comes back as it was sent, so its SDK model still deserialises.
        for (Map.Entry<String, Object> sent : body.entrySet()) {
            if (!sent.getKey().startsWith("@odata.")) {
                definition.putIfAbsent(sent.getKey(), sent.getValue());
            }
        }
        return definition;
    }

    private static void refuseMistypedSections(Map<String, Object> body) {
        for (String section : LIST_SECTIONS) {
            if (body.get(section) != null && !(body.get(section) instanceof List<?>)) {
                throw refuse("'" + section + "' must be a list.");
            }
        }
        for (String section : OBJECT_SECTIONS) {
            if (body.get(section) != null && !(body.get(section) instanceof Map<?, ?>)) {
                throw refuse("'" + section + "' must be an object.");
            }
        }
        for (String section : STRING_SECTIONS) {
            if (body.get(section) != null && !(body.get(section) instanceof String)) {
                throw refuse("'" + section + "' must be a string.");
            }
        }
    }

    /** The names of the vector search profiles the definition declares. */
    private static Set<String> vectorProfiles(Map<String, Object> body) {
        Set<String> names = new HashSet<>();
        if (ArmJson.cast(body.get("vectorSearch")).get("profiles") instanceof List<?> profiles) {
            for (Object profile : profiles) {
                if (ArmJson.cast(profile).get("name") instanceof String name) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * @param multiValued whether a complex collection holds these fields, as parent or further up, which makes
     *                    each of them multi-valued
     */
    private static List<Map<String, Object>> normaliseFields(List<?> sent, boolean topLevel, boolean multiValued,
                                                             Set<String> profiles, String parent) {
        List<Map<String, Object>> fields = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : sent) {
            if (!(item instanceof Map<?, ?>)) {
                throw refuse("Every entry of 'fields' must be a field definition.");
            }
            Map<String, Object> field = normaliseField(ArmJson.cast(item), topLevel, multiValued, profiles, parent);
            if (!seen.add((String) field.get("name"))) {
                throw refuse("The field name '" + parent + field.get("name") + "' is used more than once.");
            }
            fields.add(field);
        }
        return fields;
    }

    private static Map<String, Object> normaliseField(Map<String, Object> sent, boolean topLevel,
                                                      boolean multiValued, Set<String> profiles, String parent) {
        if (!(sent.get("name") instanceof String name) || name.length() > MAX_NAME_LENGTH
                || !FIELD_NAME.matcher(name).matches() || name.startsWith(RESERVED_FIELD_PREFIX)) {
            throw refuse("Invalid field name '" + sent.get("name") + "'. A field name must start with a letter,"
                    + " contain only letters, digits and underscores, be at most " + MAX_NAME_LENGTH
                    + " characters, and not begin with '" + RESERVED_FIELD_PREFIX + "'.");
        }
        String path = parent + name;
        if (!(sent.get("type") instanceof String type)) {
            throw refuse("The field '" + path + "' needs a 'type'.");
        }
        Matcher collectionOf = COLLECTION.matcher(type);
        boolean collection = collectionOf.matches();
        String element = collection ? collectionOf.group(1) : type;
        boolean complex = COMPLEX.equals(element);
        boolean vector = collection && VECTOR_TYPES.contains(element);
        if (!complex && !vector && !SIMPLE_TYPES.contains(element)) {
            throw refuse(VECTOR_TYPES.contains(element)
                    ? "The type '" + type + "' of field '" + path + "' is only valid as Collection(" + type + ")."
                    : "The type '" + type + "' of field '" + path + "' is not a supported data type.");
        }
        refuseMistypedAttributes(sent, path);

        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("type", type);
        boolean key = Boolean.TRUE.equals(sent.get("key"));
        if (key && (!topLevel || !STRING.equals(type))) {
            throw refuse("The key field '" + path + "' must be a top-level field of type " + STRING + ".");
        }
        if (complex) {
            refuseSimpleAttributes(sent, path);
            if (!(sent.get("fields") instanceof List<?> children) || children.isEmpty()) {
                throw refuse("The complex field '" + path + "' needs at least one sub-field in 'fields'.");
            }
            field.put("fields", normaliseFields(children, false, multiValued || collection, profiles, path + "/"));
            // What a newer api-version adds comes back as it was sent, as on a simple field.
            for (Map.Entry<String, Object> attribute : sent.entrySet()) {
                if (attribute.getValue() != null && !"fields".equals(attribute.getKey())
                        && !"synonymMaps".equals(attribute.getKey())) {
                    field.putIfAbsent(attribute.getKey(), attribute.getValue());
                }
            }
            return field;
        }
        if (sent.get("fields") instanceof List<?> children && !children.isEmpty()) {
            throw refuse("The field '" + path + "' is not a complex type, so it cannot have sub-fields.");
        }
        if (vector) {
            refuseIncompleteVector(sent, path, profiles);
        }
        boolean singleValued = !collection && !multiValued;
        refuseImpossibleAttributes(sent, path, element, vector, singleValued, key);

        boolean stored = flag(sent, "stored", true);
        field.put("searchable", flag(sent, "searchable", STRING.equals(element) || vector));
        field.put("filterable", flag(sent, "filterable", !vector));
        // The spec: "Default is true for simple fields, false for vector fields".
        field.put("retrievable", flag(sent, "retrievable", !vector));
        field.put("stored", stored);
        field.put("sortable", flag(sent, "sortable", !vector && singleValued));
        field.put("facetable", flag(sent, "facetable", !vector && !GEOGRAPHY_POINT.equals(element)));
        field.put("key", key);
        for (Map.Entry<String, Object> attribute : sent.entrySet()) {
            if (attribute.getValue() != null && !"fields".equals(attribute.getKey())) {
                field.putIfAbsent(attribute.getKey(), attribute.getValue());
            }
        }
        field.putIfAbsent("synonymMaps", List.of());
        return field;
    }

    private static void refuseMistypedAttributes(Map<String, Object> sent, String path) {
        for (String flag : FLAGS) {
            if (sent.get(flag) != null && !(sent.get(flag) instanceof Boolean)) {
                throw refuse("'" + flag + "' of field '" + path + "' must be true or false.");
            }
        }
        for (String attribute : STRING_ATTRIBUTES) {
            if (sent.get(attribute) != null && !(sent.get(attribute) instanceof String)) {
                throw refuse("'" + attribute + "' of field '" + path + "' must be a string.");
            }
        }
        if (sent.get("fields") != null && !(sent.get("fields") instanceof List<?>)) {
            throw refuse("'fields' of field '" + path + "' must be a list.");
        }
        if (sent.get("synonymMaps") != null && !(sent.get("synonymMaps") instanceof List<?> maps
                && maps.stream().allMatch(String.class::isInstance))) {
            throw refuse("'synonymMaps' of field '" + path + "' must be a list of synonym map names.");
        }
    }

    /** Refuses an attribute on a complex field, which may only have a name, a type and sub-fields. */
    private static void refuseSimpleAttributes(Map<String, Object> sent, String path) {
        for (String attribute : SIMPLE_ONLY) {
            if (sent.get(attribute) != null) {
                throw refuse("The complex field '" + path + "' cannot set '" + attribute + "'.");
            }
        }
        if (sent.get("synonymMaps") instanceof List<?> maps && !maps.isEmpty()) {
            throw refuse("The complex field '" + path + "' cannot set 'synonymMaps'.");
        }
    }

    /** Refuses the attribute values the spec rules out for a simple field of this kind. */
    private static void refuseImpossibleAttributes(Map<String, Object> sent, String path, String element,
                                                   boolean vector, boolean singleValued, boolean key) {
        if (Boolean.TRUE.equals(sent.get("searchable")) && !vector && !STRING.equals(element)) {
            throw refuse("The field '" + path + "' cannot be searchable: only string and vector fields can.");
        }
        if (vector && Boolean.FALSE.equals(sent.get("searchable"))) {
            throw refuse("The vector field '" + path + "' must be searchable.");
        }
        if (vector) {
            for (String attribute : List.of("filterable", "sortable", "facetable")) {
                if (Boolean.TRUE.equals(sent.get(attribute))) {
                    throw refuse("The vector field '" + path + "' cannot be " + attribute + ".");
                }
            }
        }
        if (Boolean.TRUE.equals(sent.get("sortable")) && !singleValued) {
            throw refuse("The field '" + path + "' cannot be sortable: it is multi-valued.");
        }
        if (Boolean.TRUE.equals(sent.get("facetable")) && GEOGRAPHY_POINT.equals(element)) {
            throw refuse("The field '" + path + "' cannot be facetable: it is of type " + GEOGRAPHY_POINT + ".");
        }
        if (key && (Boolean.FALSE.equals(sent.get("retrievable")) || Boolean.FALSE.equals(sent.get("stored")))) {
            throw refuse("The key field '" + path + "' must be retrievable and stored.");
        }
        if (Boolean.FALSE.equals(sent.get("stored"))) {
            if (!vector) {
                throw refuse("The field '" + path + "' cannot have 'stored' set to false: only vector fields can.");
            }
            if (Boolean.TRUE.equals(sent.get("retrievable"))) {
                throw refuse("The field '" + path + "' cannot be retrievable when 'stored' is false.");
            }
        }
    }

    private static void refuseIncompleteVector(Map<String, Object> sent, String path, Set<String> profiles) {
        if (!(sent.get("dimensions") instanceof Integer dimensions) || dimensions < MIN_DIMENSIONS
                || dimensions > MAX_DIMENSIONS) {
            throw refuse("The vector field '" + path + "' needs 'dimensions', a whole number from " + MIN_DIMENSIONS
                    + " to " + MAX_DIMENSIONS + ".");
        }
        if (!(sent.get("vectorSearchProfile") instanceof String profile)) {
            throw refuse("The vector field '" + path + "' needs a 'vectorSearchProfile'.");
        }
        if (!profiles.contains(profile)) {
            throw refuse("The vector search profile '" + profile + "' of field '" + path
                    + "' is not defined in 'vectorSearch.profiles'.");
        }
    }

    /**
     * Checks an update against the stored definition, and carries over what an update cannot clear. The spec
     * says {@code similarity} "can only be defined at index creation time and cannot be modified on existing
     * indexes", and that the service "will ignore attempts to set [{@code encryptionKey}] to null". An update that
     * leaves {@code similarity} out keeps the stored one too.
     */
    private static void refuseIndexChanges(Map<String, Object> stored, Map<String, Object> sent,
                                           Map<String, Object> definition, boolean downtimeAllowed) {
        refuseFieldChanges(fields(stored), fields(definition), "");
        refuseUnstoredNewFields(fields(stored), fields(definition), "");
        if (!downtimeAllowed) {
            refuseNewAnalysisComponents(stored, definition);
        }
        if (sent.get("similarity") == null) {
            definition.put("similarity", stored.get("similarity"));
        } else if (!sameSimilarity(ArmJson.cast(stored.get("similarity")), ArmJson.cast(sent.get("similarity")))) {
            throw new Refused(SearchErrors.error(400, "OperationNotAllowed",
                    "The similarity algorithm of an existing index cannot be changed."));
        }
        if (sent.get("encryptionKey") == null) {
            definition.put("encryptionKey", stored.get("encryptionKey"));
        }
    }

    /**
     * Refuses an update that adds an analyzer, tokenizer, token filter or char filter without
     * {@code allowIndexDowntime=true}: the spec says that parameter is what "allows new analyzers, tokenizers, token
     * filters, or char filters to be added to an index".
     */
    private static void refuseNewAnalysisComponents(Map<String, Object> stored, Map<String, Object> definition) {
        for (String section : DOWNTIME_SECTIONS) {
            Set<Object> before = componentNames(stored.get(section));
            for (Object name : componentNames(definition.get(section))) {
                if (!before.contains(name)) {
                    throw new Refused(SearchErrors.error(400, "OperationNotAllowed", "Adding '" + name + "' to '"
                            + section + "' takes the index offline: send allowIndexDowntime=true to allow it."));
                }
            }
        }
    }

    private static Set<Object> componentNames(Object section) {
        Set<Object> names = new HashSet<>();
        if (section instanceof List<?> components) {
            components.forEach(component -> names.add(ArmJson.cast(component).get("name")));
        }
        return names;
    }

    /** Whether the request sends {@code allowIndexDowntime=true}. */
    private static boolean downtimeAllowed(AzureRequest req) {
        return "true".equalsIgnoreCase(req.queryParams().get("allowIndexDowntime"));
    }

    /** Whether two {@code similarity} sections name the same algorithm with the same parameters. */
    private static boolean sameSimilarity(Map<String, Object> stored, Map<String, Object> sent) {
        if (!Objects.equals(stored.get("@odata.type"), sent.get("@odata.type"))) {
            return false;
        }
        Set<String> parameters = new HashSet<>(stored.keySet());
        parameters.addAll(sent.keySet());
        parameters.remove("@odata.type");
        for (String parameter : parameters) {
            Object before = stored.get(parameter);
            Object after = sent.get(parameter);
            boolean same = before instanceof Number a && after instanceof Number b
                    ? Double.compare(a.doubleValue(), b.doubleValue()) == 0
                    : Objects.equals(before, after);
            if (!same) {
                return false;
            }
        }
        return true;
    }

    /**
     * Refuses a field an update adds with {@code stored: false}: the spec allows it "only during index creation"
     * and says it cannot be "set as false for new fields".
     */
    private static void refuseUnstoredNewFields(List<Map<String, Object>> stored, List<Map<String, Object>> sent,
                                                String parent) {
        Map<String, Map<String, Object>> storedByName = new LinkedHashMap<>();
        stored.forEach(field -> storedByName.put((String) field.get("name"), field));
        for (Map<String, Object> field : sent) {
            String path = parent + field.get("name");
            Map<String, Object> existing = storedByName.get((String) field.get("name"));
            if (existing == null && Boolean.FALSE.equals(field.get("stored"))) {
                throw refuse("The new field '" + path + "' cannot have 'stored' set to false: it can only be set"
                        + " when the index is created.");
            }
            if (field.containsKey("fields")) {
                refuseUnstoredNewFields(existing == null ? List.of() : fields(existing), fields(field), path + "/");
            }
        }
    }

    /**
     * Refuses an update that removes a field or changes how an existing one was indexed. New fields, and new
     * sub-fields of a complex field, are fine.
     */
    private static void refuseFieldChanges(List<Map<String, Object>> stored, List<Map<String, Object>> sent,
                                           String parent) {
        Map<String, Map<String, Object>> sentByName = new LinkedHashMap<>();
        sent.forEach(field -> sentByName.put((String) field.get("name"), field));
        for (Map<String, Object> existing : stored) {
            String path = parent + existing.get("name");
            Map<String, Object> updated = sentByName.get((String) existing.get("name"));
            if (updated == null) {
                throw new Refused(SearchErrors.error(400, "OperationNotAllowed",
                        "Existing field(s) '" + path + "' cannot be deleted."));
            }
            for (String attribute : IMMUTABLE) {
                if (!Objects.equals(existing.get(attribute), updated.get(attribute))) {
                    throw new Refused(SearchErrors.error(400, "OperationNotAllowed",
                            "Existing field(s) '" + path + "' cannot be changed."));
                }
            }
            if (existing.containsKey("fields")) {
                refuseFieldChanges(fields(existing), fields(updated), path + "/");
            }
        }
    }

    // ── Conditions ───────────────────────────────────────────────────────────

    /** Whether {@code If-Match} or {@code If-None-Match} rules the request out for the index as it is stored. */
    private static boolean conditionsFail(AzureRequest req, Optional<Entry> existing) {
        String ifMatch = req.headers().getHeaderString("If-Match");
        if (ifMatch != null && existing.filter(entry -> matches(ifMatch, entry.etag())).isEmpty()) {
            return true;
        }
        String ifNoneMatch = req.headers().getHeaderString("If-None-Match");
        return ifNoneMatch != null && existing.filter(entry -> matches(ifNoneMatch, entry.etag())).isPresent();
    }

    /** Whether a condition header names {@code etag}: {@code *}, or the tag itself, quoted, weak or bare. */
    private static boolean matches(String header, String etag) {
        return Arrays.stream(header.split(",")).map(SearchIndexes::bare)
                .anyMatch(tag -> "*".equals(tag) || tag.equals(etag));
    }

    private static String bare(String tag) {
        String bare = tag.trim();
        if (bare.startsWith("W/")) {
            bare = bare.substring(2);
        }
        if (bare.length() >= 2 && bare.startsWith("\"") && bare.endsWith("\"")) {
            bare = bare.substring(1, bare.length() - 1);
        }
        return bare;
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    private static String prefix(Service service) {
        return KEY_PREFIX + service.cloud().toLowerCase(Locale.ROOT) + SEP + service.name().toLowerCase(Locale.ROOT)
                + SEP;
    }

    private static String key(Service service, String index) {
        return prefix(service) + index.toLowerCase(Locale.ROOT);
    }

    private Optional<Entry> read(Service service, String index) {
        return storage.get(key(service, index)).map(SearchIndexes::decode);
    }

    /** The indexes of {@code service}, by name. */
    private List<Entry> entries(Service service) {
        String prefix = prefix(service);
        return storage.scan(key -> key.startsWith(prefix)).stream()
                .map(SearchIndexes::decode)
                .sorted(Comparator.comparing(entry -> (String) entry.definition().get("name")))
                .toList();
    }

    private Entry write(Service service, Map<String, Object> definition) {
        String key = key(service, (String) definition.get("name"));
        String etag = newEtag();
        try {
            storage.put(key, new StoredObject(key, MAPPER.writeValueAsBytes(definition), Map.of(), Instant.now(),
                    etag));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("An index definition could not be written as JSON", e);
        }
        return new Entry(definition, etag);
    }

    @SuppressWarnings("unchecked")
    private static Entry decode(StoredObject stored) {
        try {
            return new Entry(MAPPER.readValue(stored.data(), LinkedHashMap.class), stored.etag());
        } catch (IOException e) {
            throw new UncheckedIOException("The stored index " + stored.key() + " is not readable", e);
        }
    }

    /** An ETag in the shape of Azure's, {@code 0x} and hex digits, that is new on every write. */
    private String newEtag() {
        long stamp = (Instant.now().toEpochMilli() << 16) | (etagSequence.incrementAndGet() & 0xFFFF);
        return "0x" + Long.toHexString(stamp).toUpperCase(Locale.ROOT);
    }

    // ── Answers ──────────────────────────────────────────────────────────────

    private static Response created(Entry entry, String serviceUrl) {
        return respond(201, entry, serviceUrl)
                .header("Location", serviceUrl + "/indexes('" + entry.definition().get("name") + "')")
                .build();
    }

    private static Response answer(int status, Entry entry, String serviceUrl) {
        return respond(status, entry, serviceUrl).build();
    }

    private static Response.ResponseBuilder respond(int status, Entry entry, String serviceUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(CONTEXT, serviceUrl + "/$metadata#indexes/$entity");
        body.putAll(body(entry));
        return SearchErrors.json(status, body).header("ETag", "W/\"" + entry.etag() + "\"");
    }

    /** The definition with its ETag, which Azure answers as a quoted string inside the JSON. */
    private static Map<String, Object> body(Entry entry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(ETAG, "\"" + entry.etag() + "\"");
        body.putAll(entry.definition());
        return body;
    }

    private static Response indexNotFound(Service service, String index) {
        return SearchErrors.notFound("No index with the name '" + index + "' was found in the service '"
                + service.name() + "'.");
    }

    private static Refused refuse(String detail) {
        return new Refused(SearchErrors.invalid(detail));
    }

    // ── Small helpers ────────────────────────────────────────────────────────

    /** The {@code $select} of a listing: empty for every property, else the property names it lists. */
    private static Optional<List<String>> select(String select) {
        if (select == null || select.isBlank() || "*".equals(select.trim())) {
            return Optional.empty();
        }
        List<String> properties = Arrays.stream(select.split(",")).map(String::trim)
                .filter(property -> !property.isEmpty()).toList();
        return properties.contains("*") ? Optional.empty() : Optional.of(properties);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(Map<String, Object> holder) {
        return holder.get("fields") instanceof List<?> fields ? (List<Map<String, Object>>) fields : List.of();
    }

    private static boolean flag(Map<String, Object> sent, String name, boolean fallback) {
        return sent.get(name) instanceof Boolean value ? value : fallback;
    }

    private static Object listOrEmpty(Map<String, Object> body, String section) {
        return body.get(section) == null ? List.of() : body.get(section);
    }

    private static Map<String, Object> defaultSimilarity() {
        Map<String, Object> similarity = new LinkedHashMap<>();
        similarity.put("@odata.type", BM25);
        similarity.put("k1", null);
        similarity.put("b", null);
        return similarity;
    }
}
