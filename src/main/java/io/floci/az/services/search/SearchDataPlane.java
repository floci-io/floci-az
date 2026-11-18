package io.floci.az.services.search;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.RequestUrls;
import io.floci.az.services.search.SearchModels.SearchServiceResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The data plane of a search service, as {@code https://{service}.search.windows.net} serves it: index management
 * and the service statistics so far. Documents, queries, synonym maps and indexers are not served yet and answer
 * {@code 404}.
 */
@ApplicationScoped
public class SearchDataPlane {

    /** A keyed OData segment, {@code indexes('name')}. The plain form {@code indexes/name} is served too. */
    private static final Pattern KEYED = Pattern.compile("([^(]+)\\('(.*)'\\)");
    /** Set by the routing filter on a path-style request, {@code /{service}-search/...}. */
    private static final String ACCOUNT_SUFFIX_HEADER = "x-floci-account-suffix";
    private static final String SERVICE_STATS = "servicestats";

    /** The limits Azure documents for every tier; they are reported, not enforced. */
    private static final int MAX_FIELDS_PER_INDEX = 1000;
    private static final int MAX_FIELD_NESTING_DEPTH = 10;
    private static final int MAX_COMPLEX_COLLECTION_FIELDS = 40;
    private static final int MAX_COMPLEX_OBJECTS_IN_COLLECTIONS = 3000;
    /** How many indexes each SKU holds, from Microsoft's service limits page. */
    private static final Map<String, Integer> INDEX_QUOTA = Map.of(
            "free", 3, "basic", 15, "standard", 50, "standard2", 200, "standard3", 200,
            "storage_optimized_l1", 10, "storage_optimized_l2", 10);
    /** A {@code standard3} service in {@code HighDensity} mode: "1000 per partition or 3000 per service". */
    private static final int HIGH_DENSITY_INDEXES_PER_PARTITION = 1000;
    private static final int HIGH_DENSITY_INDEXES_PER_SERVICE = 3000;
    private static final String DEFAULT_SKU = "standard";
    private static final String FREE_SKU = "free";
    /**
     * A free service's quotas, as a real one reports them: 3 indexers, data sources, synonym maps and skillsets,
     * and 50 MB of storage, which is also its per-index limit. The other tiers' are not recorded and stay null.
     */
    private static final int FREE_RESOURCE_QUOTA = 3;
    private static final long FREE_STORAGE_BYTES = 50L * 1024 * 1024;

    private final EmulatorConfig config;
    private final SearchArmService arm;
    private final SearchAuth auth;
    private final SearchIndexes indexes;

    @Inject
    public SearchDataPlane(EmulatorConfig config, SearchArmService arm, SearchAuth auth, SearchIndexes indexes) {
        this.config = config;
        this.arm = arm;
        this.auth = auth;
        this.indexes = indexes;
    }

    /** Serves one request addressed to a search service. */
    public Response handle(AzureRequest req) {
        String service = req.accountName();
        List<String> segments = segments(req.resourcePath());
        if (service == null || service.isEmpty() || segments.isEmpty()) {
            return SearchErrors.notFound("The request addresses no search resource.");
        }
        String apiVersion = req.queryParams().get("api-version");
        if (apiVersion == null || apiVersion.isBlank()) {
            return SearchErrors.error(400, "MissingRequiredParameter",
                    "The 'api-version' query string parameter must be specified in the request.");
        }
        String collection = segments.getFirst();
        Optional<String> name = Optional.empty();
        List<String> rest = segments.subList(1, segments.size());
        Matcher keyed = KEYED.matcher(collection);
        if (keyed.matches()) {
            collection = keyed.group(1);
            name = Optional.of(keyed.group(2));
        } else if (!rest.isEmpty()) {
            name = Optional.of(rest.getFirst());
            rest = rest.subList(1, rest.size());
        }

        String operation = collection.toLowerCase(Locale.ROOT);
        boolean statistics = SERVICE_STATS.equals(operation);
        // The ARM resource is read only when something needs it: the strict key check, or the SKU's quota.
        Optional<SearchServiceResource> declared = auth.enforced() || statistics
                ? arm.findByName(req, service) : Optional.empty();
        Optional<Response> refusal = auth.refuseManagement(req, declared);
        if (refusal.isPresent()) {
            return refusal.get();
        }

        String cloud = SearchArmService.cloud(req).name();
        if (statistics) {
            if (name.isPresent()) {
                return SearchErrors.notFound("The resource 'servicestats/" + name.get() + "' does not exist.");
            }
            return "GET".equalsIgnoreCase(req.method())
                    ? serviceStatistics(req, cloud, service, declared) : SearchErrors.methodNotAllowed();
        }
        return switch (operation) {
            case "indexes" -> indexes.handle(req, cloud, service, name, rest, serviceUrl(req, service));
            default -> SearchErrors.notFound("This Azure AI Search operation is not served yet: " + collection);
        };
    }

    /**
     * {@code GetServiceStatistics}. The index count is real, and the index quota is the SKU's; the counters of
     * what floci-az does not serve yet are zero.
     */
    private Response serviceStatistics(AzureRequest req, String cloud, String service,
                                       Optional<SearchServiceResource> declared) {
        String apiVersion = req.queryParams().get("api-version");
        boolean free = FREE_SKU.equals(declared.map(SearchServiceResource::sku).orElse(DEFAULT_SKU));
        Integer freeQuota = free ? Integer.valueOf(FREE_RESOURCE_QUOTA) : null;
        Map<String, Object> counters = new LinkedHashMap<>();
        if (hasAliasesCounter(apiVersion)) {
            counters.put("aliasesCount", counter(0, null));
        }
        counters.put("documentCount", counter(0, null));
        counters.put("indexesCount", counter(indexes.count(cloud, service), indexQuota(declared)));
        counters.put("indexersCount", counter(0, freeQuota));
        counters.put("dataSourcesCount", counter(0, freeQuota));
        counters.put("storageSize", counter(0, free ? Long.valueOf(FREE_STORAGE_BYTES) : null));
        counters.put("synonymMaps", counter(0, freeQuota));
        counters.put("skillsetCount", counter(0, freeQuota));
        counters.put("vectorIndexSize", counter(0, null));
        if (hasKnowledgeCounters(apiVersion)) {
            counters.put("knowledgeBasesCount", counter(0, null));
            counters.put("knowledgeSourcesCount", counter(0, null));
        }

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxStoragePerIndex", free ? Long.valueOf(FREE_STORAGE_BYTES) : null);
        limits.put("maxFieldsPerIndex", MAX_FIELDS_PER_INDEX);
        limits.put("maxFieldNestingDepthPerIndex", MAX_FIELD_NESTING_DEPTH);
        limits.put("maxComplexCollectionFieldsPerIndex", MAX_COMPLEX_COLLECTION_FIELDS);
        limits.put("maxComplexObjectsInCollectionsPerDocument", MAX_COMPLEX_OBJECTS_IN_COLLECTIONS);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("@odata.context", serviceUrl(req, service) + "/$metadata#"
                + SearchIndexes.modelType(apiVersion, "ServiceStatistics"));
        body.put("counters", counters);
        body.put("limits", limits);
        return SearchErrors.json(200, body).build();
    }

    /**
     * Whether the api-version's {@code SearchServiceCounters} has {@code aliasesCount}: the previews from
     * {@code 2021-04-30-Preview} and the stable versions from {@code 2026-04-01}. A real {@code 2025-09-01} service
     * answers without it.
     */
    static boolean hasAliasesCounter(String apiVersion) {
        ApiVersion version = ApiVersion.parse(apiVersion);
        return version.preview() ? version.date().compareTo("2021-04-30") >= 0
                : version.date().compareTo("2026-04-01") >= 0;
    }

    /**
     * Whether the api-version has the knowledge counters: the previews from {@code 2026-05-01-preview}, and a stable
     * version newer than the newest one in the specs ({@code 2026-04-01}), whose model may require them.
     */
    static boolean hasKnowledgeCounters(String apiVersion) {
        ApiVersion version = ApiVersion.parse(apiVersion);
        return version.preview() ? version.date().compareTo("2026-05-01") >= 0
                : version.date().compareTo("2026-04-01") > 0;
    }

    /** An api-version as its date, {@code yyyy-MM-dd}, and whether it is a preview. */
    private record ApiVersion(String date, boolean preview) {
        static ApiVersion parse(String apiVersion) {
            String version = apiVersion == null ? "" : apiVersion.trim().toLowerCase(Locale.ROOT);
            return new ApiVersion(version.length() >= 10 ? version.substring(0, 10) : version,
                    version.endsWith("-preview"));
        }
    }

    /**
     * The index quota of the service's SKU, {@code standard} for a service ARM does not hold. A high-density
     * {@code standard3} service holds 1000 indexes per partition, up to 3000.
     */
    private static Integer indexQuota(Optional<SearchServiceResource> declared) {
        String sku = declared.map(SearchServiceResource::sku).orElse(DEFAULT_SKU);
        Map<String, Object> properties = declared.map(SearchServiceResource::properties).orElse(Map.of());
        if (SearchArmService.HIGH_DENSITY_SKU.equals(sku) && "highdensity".equalsIgnoreCase(String.valueOf(properties.get("hostingMode")))) {
            int partitions = properties.get("partitionCount") instanceof Number count ? count.intValue() : 1;
            return Math.min(partitions * HIGH_DENSITY_INDEXES_PER_PARTITION, HIGH_DENSITY_INDEXES_PER_SERVICE);
        }
        return INDEX_QUOTA.get(sku);
    }

    private static Map<String, Object> counter(int usage, Number quota) {
        Map<String, Object> counter = new LinkedHashMap<>();
        counter.put("usage", usage);
        counter.put("quota", quota);
        return counter;
    }

    /** The service's URL as the caller addressed it: its own host, or the emulator's with the path prefix. */
    private String serviceUrl(AzureRequest req, String service) {
        String base = RequestUrls.resolveBaseUrl(req, config);
        String suffix = req.headers().getHeaderString(ACCOUNT_SUFFIX_HEADER);
        return suffix == null ? base : base + "/" + service + suffix;
    }

    private static List<String> segments(String path) {
        int query = path.indexOf('?');
        List<String> segments = new ArrayList<>();
        for (String segment : (query < 0 ? path : path.substring(0, query)).split("/")) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }
}
