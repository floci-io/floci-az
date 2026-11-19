package io.floci.az.services.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The documents of the search indexes: the {@code /docs} operations of the data-plane spec
 * ({@code searchindex.json}).
 *
 * <pre>
 *   POST /indexes('{index}')/docs/search.index      (allowUnsafeKeys)
 *   GET  /indexes('{index}')/docs('{key}')          ($select)
 *   GET  /indexes('{index}')/docs/{key}             ($select)
 *   GET  /indexes('{index}')/docs/$count
 * </pre>
 *
 * <p>Where the spec is silent, the behaviour follows a recording of a real service (Free tier, api-version
 * {@code 2025-09-01}). A batch is read whole before anything is written: a value that does not fit its field, an
 * unknown field, an unknown action or a bad key refuses the whole request with {@code 400}, and only what depends on
 * the stored documents (a {@code merge} of a missing key) fails one action, answered with {@code 207}.
 *
 * <p>Each document is one entry in the {@code search} backend the index definitions share. Its key is {@code doc},
 * the cloud, the service, the index and the document key, separated by null bytes; the document key keeps its
 * case, as Azure's keys are case-sensitive. A batch runs under the lock the index operations take, so no document
 * outlives the delete of its index.
 */
@ApplicationScoped
public class SearchDocuments {

    private static final Logger LOG = Logger.getLogger(SearchDocuments.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String STORE = "search";
    private static final char SEP = '\0';
    static final String KEY_PREFIX = "doc" + SEP;
    /** The metadata entry that holds how many bytes the document's vectors take, for the statistics. */
    private static final String VECTOR_BYTES = "vectorBytes";

    private static final String DOCS = "docs";
    private static final Pattern KEYED_DOCS = Pattern.compile("docs\\('(.*)'\\)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final String INDEX_OPERATION = "search.index";
    private static final String COUNT_OPERATION = "$count";
    private static final String OPERATION_PREFIX = "search.";

    private static final String ACTION = "@search.action";
    private static final String UPLOAD = "upload";
    private static final String MERGE = "merge";
    private static final String MERGE_OR_UPLOAD = "mergeOrUpload";
    private static final String DELETE = "delete";
    private static final List<String> ACTIONS = List.of(UPLOAD, DELETE, MERGE, MERGE_OR_UPLOAD);

    /** The most actions a batch may hold, as Azure's own message for an empty batch says. */
    private static final int MAX_ACTIONS = 32000;
    /** The largest batch body, from Microsoft's service limits page. */
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    private static final int MAX_KEY_LENGTH = 1024;
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9_=-]+");
    private static final String UNSAFE_KEYS_PARAMETER = "allowUnsafeKeys";

    private static final Pattern COLLECTION = Pattern.compile("Collection\\((.+)\\)");
    private static final String COMPLEX = "Edm.ComplexType";
    private static final Map<String, Integer> VECTOR_ELEMENT_BYTES = Map.of(
            "Edm.Single", 4, "Edm.Half", 2, "Edm.Int16", 2, "Edm.SByte", 1, "Edm.Byte", 1);
    private static final List<String> NOT_A_NUMBER = List.of("NaN", "INF", "-INF");
    private static final Map<String, Object> WGS84 = Map.of("type", "name",
            "properties", Map.of("name", "EPSG:4326"));

    private static final String DOCUMENT_TYPE = "search.documentFields";
    private static final String SELECT_SUFFIX = "\r\nParameter name: $select";
    private static final String DOCUMENT_NOT_FOUND = "Document not found.";
    private static final String UNSAFE_KEY_ADVICE = " Keys can only contain letters, digits, underscore (_), dash (-),"
            + " or equal sign (=). If the keys in your source data contain other characters, we recommend encoding"
            + " them with a URL-safe version of Base64 before uploading them to your index. If that is not an option,"
            + " you can add the 'allowUnsafeKeys' query string parameter to disable this check.";
    private static final String VECTOR_ADVICE = " Please ensure that the vector length matches the expected length of"
            + " the vector field. Read the following documentation for more details:"
            + " https://learn.microsoft.com/en-us/azure/search/vector-search-how-to-configure-compression-storage.";

    /** A refused request, thrown while a batch is read and carrying the answer. */
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

    /** One action of a batch, read and checked: its kind, its document key and the document's values. */
    private record Action(String kind, String key, Map<String, Object> document) {
    }

    /** The outcome of one action, as {@code IndexingResult} answers it. */
    private record Outcome(String key, boolean status, String errorMessage, int statusCode) {
        Map<String, Object> body() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("key", key);
            body.put("status", status);
            body.put("errorMessage", errorMessage);
            body.put("statusCode", statusCode);
            return body;
        }
    }

    /** What the documents of an index, or of a service, take up: for {@code search.stats} and servicestats. */
    record Usage(long documents, long storageBytes, long vectorBytes) {
    }

    private final StorageBackend<String, StoredObject> storage;
    private final SearchIndexes indexes;

    @Inject
    public SearchDocuments(StorageFactory storageFactory, SearchIndexes indexes) {
        this.storage = storageFactory.create(STORE);
        this.indexes = indexes;
    }

    /** Whether a path segment after the index addresses its documents: {@code docs} or {@code docs('key')}. */
    static boolean addressesDocuments(String segment) {
        return DOCS.equalsIgnoreCase(segment) || KEYED_DOCS.matcher(segment).matches();
    }

    /**
     * Whether a request under {@code docs} only reads: everything but {@code search.index}, so a query key may make
     * it.
     */
    static boolean reads(List<String> rest) {
        return !(rest.size() == 2 && INDEX_OPERATION.equalsIgnoreCase(rest.get(1)));
    }

    /**
     * Serves a request under {@code /indexes('{index}')/docs}.
     *
     * @param rest       the path segments after the index, the first of them {@code docs} or {@code docs('key')}
     * @param serviceUrl the service's URL as the caller addressed it, for the links in the answer
     */
    public Response handle(AzureRequest req, String cloud, String service, String index, List<String> rest,
                           String serviceUrl) {
        String method = req.method().toUpperCase(Locale.ROOT);
        Matcher keyed = KEYED_DOCS.matcher(rest.getFirst());
        try {
            if (keyed.matches() && rest.size() == 1) {
                return "GET".equals(method) ? lookup(req, cloud, service, index, keyed.group(1).replace("''", "'"),
                        serviceUrl) : SearchErrors.methodNotAllowed();
            }
            if (!keyed.matches() && rest.size() == 2) {
                String operation = rest.get(1);
                if (INDEX_OPERATION.equalsIgnoreCase(operation)) {
                    return "POST".equals(method) ? batch(req, cloud, service, index, serviceUrl)
                            : SearchErrors.methodNotAllowed();
                }
                if (COUNT_OPERATION.equalsIgnoreCase(operation)) {
                    return "GET".equals(method) ? count(cloud, service, index) : SearchErrors.methodNotAllowed();
                }
                if (!operation.toLowerCase(Locale.ROOT).startsWith(OPERATION_PREFIX)) {
                    return "GET".equals(method) ? lookup(req, cloud, service, index, operation, serviceUrl)
                            : SearchErrors.methodNotAllowed();
                }
            }
            return SearchErrors.notFound("This document operation is not served yet: " + String.join("/", rest));
        } catch (Refused refused) {
            return refused.response();
        }
    }

    // ── Operations ───────────────────────────────────────────────────────────

    /**
     * {@code Documents_Index}: {@code 200} when every action succeeds, {@code 207} when one fails. The batch is read
     * and checked whole before anything is written, and written in one step.
     */
    private Response batch(AzureRequest req, String cloud, String service, String index, String serviceUrl) {
        byte[] bytes = body(req);
        if (bytes.length > MAX_BODY_BYTES) {
            return tooLarge();
        }
        Object parsed = parse(bytes);
        // The batch is read and checked outside the lock, which every index and batch of the emulator shares.
        Map<String, Object> checkedAgainst = indexes.find(cloud, service, index)
                .orElseThrow(() -> new Refused(SearchErrors.indexNotFound(service, index)));
        List<Action> checked = read(req, checkedAgainst, parsed);
        synchronized (indexes.writeLock()) {
            Map<String, Object> definition = indexes.find(cloud, service, index)
                    .orElseThrow(() -> new Refused(SearchErrors.indexNotFound(service, index)));
            // The index may have changed meanwhile (a field added, or the index deleted and created again).
            List<Action> actions = definition.equals(checkedAgainst) ? checked : read(req, definition, parsed);
            String prefix = indexPrefix(cloud, service, index);
            Map<String, Optional<Map<String, Object>>> staged = new HashMap<>();
            List<Outcome> outcomes = new ArrayList<>();
            for (Action action : actions) {
                Optional<Map<String, Object>> current = staged.computeIfAbsent(action.key(),
                        key -> storage.get(prefix + key).map(SearchDocuments::decode));
                Outcome outcome = apply(action, current, fields(definition), staged);
                outcomes.add(outcome);
            }
            Map<String, StoredObject> puts = new LinkedHashMap<>();
            Set<String> deletes = new HashSet<>();
            for (Map.Entry<String, Optional<Map<String, Object>>> entry : staged.entrySet()) {
                String key = prefix + entry.getKey();
                if (entry.getValue().isPresent()) {
                    puts.put(key, encode(key, entry.getValue().get(), fields(definition)));
                } else if (storage.get(key).isPresent()) {
                    deletes.add(key);
                }
            }
            if (!puts.isEmpty() || !deletes.isEmpty()) {
                storage.applyBatch(puts, deletes);
            }
            LOG.debugv("Search: indexed {0} action(s) in index {1} of service {2}", actions.size(), index, service);
            boolean allSucceeded = outcomes.stream().allMatch(Outcome::status);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("@odata.context", indexUrl(serviceUrl, definition) + "/$metadata#Collection("
                    + SearchIndexes.modelType(req.queryParams().get("api-version"), "IndexResult") + ")");
            body.put("value", outcomes.stream().map(Outcome::body).toList());
            return SearchErrors.json(allSucceeded ? 200 : 207, body).build();
        }
    }

    /**
     * Runs one action on the document as the batch left it so far, so two actions on one key run in order and the
     * last one wins, as on Azure.
     */
    private static Outcome apply(Action action, Optional<Map<String, Object>> current, List<Map<String, Object>> fields,
                                 Map<String, Optional<Map<String, Object>>> staged) {
        String kind = MERGE_OR_UPLOAD.equals(action.kind()) ? (current.isPresent() ? MERGE : UPLOAD) : action.kind();
        return switch (kind) {
            case UPLOAD -> {
                staged.put(action.key(), Optional.of(action.document()));
                yield new Outcome(action.key(), true, null, current.isPresent() ? 200 : 201);
            }
            case MERGE -> {
                if (current.isEmpty()) {
                    yield new Outcome(action.key(), false, DOCUMENT_NOT_FOUND, 404);
                }
                staged.put(action.key(), Optional.of(merge(current.get(), action.document(), fields)));
                yield new Outcome(action.key(), true, null, 200);
            }
            default -> {
                // A delete of a key that does not exist succeeds too.
                staged.put(action.key(), Optional.empty());
                yield new Outcome(action.key(), true, null, 200);
            }
        };
    }

    /**
     * {@code Documents_Get}: every retrievable field in the order of the index, or those {@code $select} names. A
     * document that does not exist is {@code 404} with no body, as Azure answers it.
     */
    private Response lookup(AzureRequest req, String cloud, String service, String index, String key,
                            String serviceUrl) {
        Map<String, Object> definition = indexes.find(cloud, service, index)
                .orElseThrow(() -> new Refused(SearchErrors.indexNotFound(service, index)));
        Optional<Map<String, Object>> selection = selection(req.queryParams().get("$select"), fields(definition));
        Optional<Map<String, Object>> document = storage.get(indexPrefix(cloud, service, index) + key)
                .map(SearchDocuments::decode);
        if (document.isEmpty()) {
            return Response.status(404).build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("@odata.context", indexUrl(serviceUrl, definition) + "/$metadata#docs(*)/$entity");
        body.putAll(project(document.get(), fields(definition), selection));
        return SearchErrors.json(200, body).build();
    }

    /** {@code Documents_Count}: the bare number as {@code text/plain}, with no byte order mark. */
    private Response count(String cloud, String service, String index) {
        if (indexes.find(cloud, service, index).isEmpty()) {
            return SearchErrors.indexNotFound(service, index);
        }
        String prefix = indexPrefix(cloud, service, index);
        long count = storage.keys().stream().filter(key -> key.startsWith(prefix)).count();
        return Response.ok(Long.toString(count)).type("text/plain; charset=utf-8").build();
    }

    // ── For the index operations and the service statistics ──────────────────

    /** The key prefix of every document of {@code service}. */
    static String servicePrefix(String cloud, String service) {
        return KEY_PREFIX + cloud.toLowerCase(Locale.ROOT) + SEP + service.toLowerCase(Locale.ROOT) + SEP;
    }

    /** The key prefix of every document of {@code index}; index names are case-insensitive, document keys not. */
    static String indexPrefix(String cloud, String service, String index) {
        return servicePrefix(cloud, service) + index.toLowerCase(Locale.ROOT) + SEP;
    }

    /**
     * What the documents under {@code prefix} take up. The storage size is the size of their JSON, which is far
     * smaller than Azure's, whose index holds the inverted index too; the vector size is their vectors' elements.
     */
    static Usage usage(StorageBackend<String, StoredObject> storage, String prefix) {
        long documents = 0;
        long bytes = 0;
        long vectorBytes = 0;
        for (StoredObject stored : storage.scan(key -> key.startsWith(prefix))) {
            documents++;
            bytes += stored.data().length;
            vectorBytes += Long.parseLong(stored.metadata().getOrDefault(VECTOR_BYTES, "0"));
        }
        return new Usage(documents, bytes, vectorBytes);
    }

    /** What every document of {@code service} takes up. */
    public Usage usage(String cloud, String service) {
        return usage(storage, servicePrefix(cloud, service));
    }

    // ── Reading a batch ──────────────────────────────────────────────────────

    /**
     * The actions of a batch, read in the order Azure reads them: the JSON of every document against the index
     * first, which refuses a request with no code, then the action and the key of each, which refuse it with a code
     * and the position of the action.
     */
    private static List<Action> read(AzureRequest req, Map<String, Object> definition, Object parsed) {
        Object value = parsed instanceof Map<?, ?> body ? body.get("value") : null;
        if (!(parsed instanceof Map<?, ?>)) {
            throw new Refused(SearchErrors.invalid("An unexpected 'PrimitiveValue' node was found when reading from"
                    + " the JSON reader. A 'StartObject' node was expected."));
        }
        if (value != null && !(value instanceof List<?>)) {
            throw new Refused(SearchErrors.cannotConvert(literal(value), "Collection(search.indexAction)"));
        }
        List<?> items = value == null ? List.of() : (List<?>) value;
        if (items.isEmpty()) {
            String message = "No indexing actions found in the request. Please include between 1 and " + MAX_ACTIONS
                    + " indexing actions in your request.";
            throw new Refused(SearchErrors.error(400, "MissingRequiredParameter",
                    SearchErrors.INVALID_PREFIX + "actions : " + message, "MissingIndexDocumentsActions",
                    message + " Parameters: actions"));
        }
        if (items.size() > MAX_ACTIONS) {
            throw new Refused(tooLarge());
        }

        String apiVersion = req.queryParams().get("api-version");
        List<Map<String, Object>> fields = fields(definition);
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?>)) {
                throw new Refused(SearchErrors.invalid("An unexpected 'PrimitiveValue' node was found when reading"
                        + " from the JSON reader. A 'StartObject' node was expected."));
            }
            documents.add(convertObject(cast(item), fields, apiVersion, true));
        }

        for (int i = 0; i < items.size(); i++) {
            Object kind = action(items.get(i));
            if (!(kind instanceof String name) || !ACTIONS.contains(name)) {
                String message = "Invalid action found in item " + i + " of index batch. Please specify one of the"
                        + " following for each item in the batch: upload, delete, merge, mergeOrUpload";
                throw new Refused(SearchErrors.error(400, SearchErrors.OPERATION_NOT_ALLOWED,
                        message + "\r\nParameter name: value", "InvalidIndexDocumentsAction", message));
            }
        }

        String keyField = keyField(fields);
        boolean unsafeKeys = Boolean.parseBoolean(req.queryParams().get(UNSAFE_KEYS_PARAMETER));
        List<Action> actions = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String kind = (String) action(items.get(i));
            Map<String, Object> document = documents.get(i);
            String key = checkKey(i, document.get(keyField), unsafeKeys);
            if (!DELETE.equals(kind)) {
                checkVectors(i, document, fields);
            }
            actions.add(new Action(kind, key, document));
        }
        return actions;
    }

    /** The document key of action {@code i}, refused as Azure refuses it when it is missing or not a valid key. */
    private static String checkKey(int i, Object value, boolean unsafeKeys) {
        String key = value instanceof String text ? text : "";
        if (key.isEmpty()) {
            throw new Refused(actionError(i, SearchErrors.OPERATION_NOT_ALLOWED, "MissingKeyField",
                    "Document key cannot be missing or empty."));
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new Refused(actionError(i, "InvalidName", "InvalidDocumentKey",
                    "Document key cannot be longer than " + MAX_KEY_LENGTH + " characters."));
        }
        if (key.startsWith("_")) {
            throw new Refused(actionError(i, "InvalidName", "InvalidDocumentKey",
                    "Invalid document key: '" + key + "'. Keys cannot start with a leading underscore."));
        }
        if (!unsafeKeys && !SAFE_KEY.matcher(key).matches()) {
            throw new Refused(actionError(i, "InvalidName", "InvalidDocumentKey",
                    "Invalid document key: '" + key + "'." + UNSAFE_KEY_ADVICE));
        }
        return key;
    }

    /** Refuses a vector of action {@code i} whose length is not its field's {@code dimensions}. */
    private static void checkVectors(int i, Map<String, Object> document, List<Map<String, Object>> fields) {
        for (Map<String, Object> field : fields) {
            if (!(field.get("dimensions") instanceof Number dimensions)
                    || !(document.get((String) field.get("name")) instanceof List<?> vector)
                    || vector.size() == dimensions.intValue()) {
                continue;
            }
            throw new Refused(actionError(i, SearchErrors.OPERATION_NOT_ALLOWED, "IndexDocumentsFieldError",
                    "There's a mismatch in vector dimensions. The vector field '" + field.get("name")
                            + "', with dimension of '" + dimensions + "', expects a length of '" + dimensions
                            + "'. However, the provided vector has a length of '" + vector.size() + "'."
                            + VECTOR_ADVICE));
        }
    }

    /** The {@code @search.action} of an item: {@code upload} when it is missing or {@code null}. */
    private static Object action(Object item) {
        Object kind = cast(item).get(ACTION);
        return kind == null ? UPLOAD : kind;
    }

    /** A refusal of action {@code i}, in the shape Azure gives the ones it has a code for. */
    private static Response actionError(int i, String code, String detailCode, String text) {
        return SearchErrors.error(400, code, SearchErrors.INVALID_PREFIX + "actions : " + i + ": " + text,
                detailCode, i + ": " + text + " Parameters: actions");
    }

    // ── Reading a value against its field ───────────────────────────────────

    /**
     * The values of a document or a complex object, each read against its field. A property the index does not have
     * is refused; the {@code @} annotations of the action are not properties.
     */
    private static Map<String, Object> convertObject(Map<String, Object> sent, List<Map<String, Object>> fields,
                                                     String apiVersion, boolean topLevel) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            byName.put((String) field.get("name"), field);
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> property : sent.entrySet()) {
            if (topLevel && property.getKey().startsWith("@")) {
                continue;
            }
            Map<String, Object> field = byName.get(property.getKey());
            if (field == null) {
                throw new Refused(SearchErrors.invalid("The property '" + property.getKey() + "' does not exist on"
                        + " type '" + DOCUMENT_TYPE + "' or is not present in the API version '" + apiVersion
                        + "'. Make sure to only use property names that are defined by the type."));
            }
            converted.put(property.getKey(), convert(property.getValue(), field, apiVersion));
        }
        return converted;
    }

    private static Object convert(Object value, Map<String, Object> field, String apiVersion) {
        if (value == null) {
            return null;
        }
        String type = (String) field.get("type");
        Matcher collection = COLLECTION.matcher(type);
        if (!collection.matches()) {
            return COMPLEX.equals(type) ? convertComplex(value, field, apiVersion) : convertSimple(value, type);
        }
        String element = collection.group(1);
        if (!(value instanceof List<?> items)) {
            throw new Refused(SearchErrors.cannotConvert(literal(value), type));
        }
        List<Object> converted = new ArrayList<>();
        for (Object item : items) {
            if (item == null) {
                String nonNull = element + "[Nullable=False]";
                throw new Refused(SearchErrors.invalid("A null value was found with the expected type '" + nonNull
                        + "'. The expected type '" + nonNull + "' does not allow null values."));
            }
            converted.add(COMPLEX.equals(element) ? convertComplex(item, field, apiVersion)
                    : convertSimple(item, element));
        }
        return converted;
    }

    private static Object convertComplex(Object value, Map<String, Object> field, String apiVersion) {
        if (!(value instanceof Map<?, ?>)) {
            throw new Refused(SearchErrors.cannotConvert(literal(value), COMPLEX));
        }
        return convertObject(cast(value), fields(field), apiVersion, false);
    }

    /** One primitive value, in the words of Azure's OData reader when it does not fit. */
    private static Object convertSimple(Object value, String type) {
        return switch (type) {
            case "Edm.String" -> {
                if (value instanceof String) {
                    yield value;
                }
                throw new Refused(SearchErrors.cannotConvert(literal(value), type));
            }
            case "Edm.Boolean" -> {
                if (value instanceof Boolean) {
                    yield value;
                }
                throw new Refused(SearchErrors.cannotConvert(literal(value), type));
            }
            case "Edm.Int32" -> integer(value, type, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case "Edm.Int16" -> integer(value, type, Short.MIN_VALUE, Short.MAX_VALUE);
            case "Edm.SByte" -> integer(value, type, Byte.MIN_VALUE, Byte.MAX_VALUE);
            case "Edm.Byte" -> integer(value, type, 0, 255);
            case "Edm.Int64" -> {
                if (value instanceof String) {
                    throw new Refused(SearchErrors.invalid("Cannot convert a value to target type 'Edm.Int64'"
                            + " because of conflict between input format string/number and parameter"
                            + " 'IEEE754Compatible' false/true."));
                }
                yield integer(value, type, Long.MIN_VALUE, Long.MAX_VALUE);
            }
            case "Edm.Double", "Edm.Single", "Edm.Half" -> {
                if (value instanceof Number number && !(number instanceof BigInteger)) {
                    yield number.doubleValue();
                }
                if (value instanceof String text && NOT_A_NUMBER.contains(text)) {
                    // Azure keeps the three special values as the strings they were sent as.
                    yield text;
                }
                throw new Refused(SearchErrors.cannotConvert(literal(value), type));
            }
            case "Edm.DateTimeOffset" -> dateTime(value);
            case "Edm.GeographyPoint" -> point(value);
            default -> value;
        };
    }

    private static Object integer(Object value, String type, long min, long max) {
        // Jackson reads a JSON integer as an Integer or a Long, and one beyond a long as a BigInteger.
        if (!(value instanceof Integer || value instanceof Long)) {
            throw new Refused(SearchErrors.cannotConvert(literal(value), type));
        }
        long number = ((Number) value).longValue();
        if (number < min || number > max) {
            throw new Refused(SearchErrors.cannotConvert(literal(value), type));
        }
        return value;
    }

    /**
     * A {@code DateTimeOffset}, which Azure answers in UTC with a {@code Z} and its milliseconds, if any:
     * {@code 2026-01-02T12:00:00+02:00} becomes {@code 2026-01-02T10:00:00Z}.
     */
    private static String dateTime(Object value) {
        if (value instanceof String text) {
            try {
                Instant instant = OffsetDateTime.parse(text).toInstant().truncatedTo(ChronoUnit.MILLIS);
                return DateTimeFormatter.ISO_INSTANT.format(instant);
            } catch (DateTimeParseException ignored) {
                // Safe to drop: the value is refused below, as any other value that is not a date.
            }
        }
        throw new Refused(SearchErrors.cannotConvert(literal(value), "Edm.DateTimeOffset"));
    }

    /**
     * A GeoJSON point. Azure takes one with any {@code crs}, or none, and answers it with WGS 84's, the coordinates
     * unchanged.
     */
    private static Map<String, Object> point(Object value) {
        if (value instanceof Map<?, ?> sent && "Point".equals(sent.get("type"))
                && sent.get("coordinates") instanceof List<?> coordinates && coordinates.size() == 2
                && coordinates.stream().allMatch(c -> c instanceof Number && !(c instanceof BigInteger))) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("type", "Point");
            point.put("coordinates", coordinates.stream().map(c -> ((Number) c).doubleValue()).toList());
            point.put("crs", WGS84);
            return point;
        }
        throw new Refused(SearchErrors.cannotConvert(literal(value), "Edm.GeographyPoint"));
    }

    // ── Merging and answering ────────────────────────────────────────────────

    /**
     * {@code merge}: the sent fields replace the stored ones, an explicit {@code null} clears one and a collection is
     * replaced whole. A complex field that is not a collection merges field by field, as on Azure: merging
     * {@code address: {city}} keeps {@code address.postcode}.
     */
    private static Map<String, Object> merge(Map<String, Object> stored, Map<String, Object> sent,
                                             List<Map<String, Object>> fields) {
        Map<String, Object> merged = new LinkedHashMap<>(stored);
        for (Map.Entry<String, Object> property : sent.entrySet()) {
            Map<String, Object> field = field(fields, property.getKey());
            if (field != null && COMPLEX.equals(field.get("type")) && property.getValue() instanceof Map<?, ?> part
                    && merged.get(property.getKey()) instanceof Map<?, ?> current) {
                merged.put(property.getKey(), merge(cast(current), cast(part), fields(field)));
            } else {
                merged.put(property.getKey(), property.getValue());
            }
        }
        return merged;
    }

    /**
     * A document as a lookup answers it: the retrievable fields in the order of the index, a field it does not have
     * as {@code null}, or {@code []} for a collection.
     */
    private static Map<String, Object> project(Map<String, Object> document, List<Map<String, Object>> fields,
                                               Optional<Map<String, Object>> selection) {
        Map<String, Object> projected = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            String name = (String) field.get("name");
            if (!retrievable(field) || selection.isPresent() && !selection.get().containsKey(name)) {
                continue;
            }
            Optional<Map<String, Object>> below = selection.map(chosen -> cast(chosen.get(name)))
                    .filter(chosen -> !chosen.isEmpty());
            projected.put(name, projectValue(document.get(name), field, below));
        }
        return projected;
    }

    private static Object projectValue(Object value, Map<String, Object> field, Optional<Map<String, Object>> below) {
        String type = (String) field.get("type");
        boolean collection = COLLECTION.matcher(type).matches();
        boolean complex = COMPLEX.equals(type) || ("Collection(" + COMPLEX + ")").equals(type);
        if (value == null) {
            return collection ? List.of() : null;
        }
        if (!complex) {
            return value;
        }
        if (collection) {
            return ((List<?>) value).stream().map(item -> project(cast(item), fields(field), below)).toList();
        }
        return project(cast(value), fields(field), below);
    }

    /**
     * The fields {@code $select} names, as a tree: a top-level name maps to an empty map for the whole field, and
     * {@code address/city} maps {@code address} to {@code {city}}. Empty for every field.
     */
    private static Optional<Map<String, Object>> selection(String select, List<Map<String, Object>> fields) {
        if (select == null || select.isBlank()) {
            return Optional.empty();
        }
        List<String> paths = Arrays.stream(select.split(",")).map(String::trim).filter(path -> !path.isEmpty())
                .toList();
        if (paths.contains("*")) {
            return Optional.empty();
        }
        Map<String, Object> tree = new LinkedHashMap<>();
        Set<String> whole = new HashSet<>();
        for (String path : paths) {
            Map<String, Object> level = tree;
            List<Map<String, Object>> candidates = fields;
            for (String part : path.split("/")) {
                Map<String, Object> field = field(candidates, part);
                if (field == null) {
                    throw new Refused(SearchErrors.error(400, SearchErrors.NO_CODE, "Invalid expression: Could not"
                            + " find a property named '" + part + "' on type 'search.document'." + SELECT_SUFFIX));
                }
                if (!retrievable(field)) {
                    throw new Refused(SearchErrors.error(400, SearchErrors.NO_CODE, "Invalid expression: '" + path
                            + "' is not a retrievable field. Only fields marked as retrievable in the index can be"
                            + " used in $select." + SELECT_SUFFIX));
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> next = (Map<String, Object>) level.computeIfAbsent(part,
                        name -> new LinkedHashMap<String, Object>());
                level = next;
                candidates = fields(field);
            }
            if (!path.contains("/")) {
                whole.add(path);
            }
        }
        // address,address/city selects all of address: an empty map stands for the whole field.
        for (String name : whole) {
            tree.put(name, new LinkedHashMap<String, Object>());
        }
        return Optional.of(tree);
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    /** A document as it is stored, with how many bytes its vectors take for the statistics. */
    private static StoredObject encode(String key, Map<String, Object> document, List<Map<String, Object>> fields) {
        long vectorBytes = 0;
        for (Map<String, Object> field : fields) {
            Matcher collection = COLLECTION.matcher((String) field.get("type"));
            if (field.get("dimensions") != null && collection.matches()
                    && document.get((String) field.get("name")) instanceof List<?> vector) {
                vectorBytes += (long) vector.size() * VECTOR_ELEMENT_BYTES.getOrDefault(collection.group(1), 4);
            }
        }
        try {
            return new StoredObject(key, MAPPER.writeValueAsBytes(document),
                    Map.of(VECTOR_BYTES, Long.toString(vectorBytes)), Instant.now(), null);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A search document could not be written as JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decode(StoredObject stored) {
        try {
            return MAPPER.readValue(stored.data(), LinkedHashMap.class);
        } catch (IOException e) {
            throw new UncheckedIOException("The stored search document " + stored.key() + " is not readable", e);
        }
    }

    // ── Small helpers ────────────────────────────────────────────────────────

    private static byte[] body(AzureRequest req) {
        InputStream body = req.bodyStream();
        try {
            return body == null ? new byte[0] : body.readAllBytes();
        } catch (IOException e) {
            throw new Refused(SearchErrors.invalid("The request body could not be read."));
        }
    }

    private static Object parse(byte[] bytes) {
        if (bytes.length == 0) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(bytes, Object.class);
        } catch (IOException e) {
            throw new Refused(SearchErrors.invalid("The request body is not valid JSON."));
        }
    }

    /** A batch over the limits: {@code 413}, which the SDKs answer by splitting it. Not recorded. */
    private static Response tooLarge() {
        return SearchErrors.error(413, SearchErrors.NO_CODE, "The request is too large. A batch may hold at most "
                + MAX_ACTIONS + " actions and " + MAX_BODY_BYTES + " bytes.");
    }

    private static String indexUrl(String serviceUrl, Map<String, Object> definition) {
        return serviceUrl + "/indexes('" + definition.get("name") + "')";
    }

    private static String keyField(List<Map<String, Object>> fields) {
        return fields.stream().filter(field -> Boolean.TRUE.equals(field.get("key")))
                .map(field -> (String) field.get("name")).findFirst().orElse("");
    }

    private static Map<String, Object> field(List<Map<String, Object>> fields, String name) {
        return fields.stream().filter(field -> name.equals(field.get("name"))).findFirst().orElse(null);
    }

    /** Whether a lookup answers the field: a complex field always is, a simple one unless it says otherwise. */
    private static boolean retrievable(Map<String, Object> field) {
        return !Boolean.FALSE.equals(field.get("retrievable"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(Map<String, Object> holder) {
        return holder.get("fields") instanceof List<?> fields ? (List<Map<String, Object>>) fields : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** A JSON value as Azure's reader quotes it in a message: a string bare, anything else as its JSON. */
    private static String literal(Object value) {
        if (value instanceof String text) {
            return text;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
