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
    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024 * MIB;
    private static final long TIB = 1024 * GIB;

    /**
     * The quotas of a tier, from Microsoft's service limits page, for a service created after February 2025 in a
     * region with the higher capacity. The page writes GB; the recorded Free service reports its "50 MB" as
     * 52428800 bytes, so the sizes here are binary too.
     *
     * @param objects           the indexes, and also the indexers, data sources and skillsets, a service holds
     * @param synonymMaps       the synonym maps a service holds
     * @param storagePerUnit    the storage of one partition in bytes, or of the whole service on Free
     * @param vectorPerUnit     the vector index size of one partition in bytes, or null where the page gives none
     * @param maxIndexBytes     the largest index, or null where it is the whole service's storage
     */
    private record Tier(int objects, int synonymMaps, long storagePerUnit, Long vectorPerUnit, Long maxIndexBytes) {
    }

    private static final Map<String, Tier> TIERS = Map.of(
            "free", new Tier(3, 3, 50 * MIB, null, null),
            "basic", new Tier(15, 3, 15 * GIB, 5 * GIB, null),
            "standard", new Tier(50, 5, 160 * GIB, 35 * GIB, null),
            "standard2", new Tier(200, 10, 512 * GIB, 150 * GIB, Math.round(1.88 * TIB)),
            "standard3", new Tier(200, 20, 1024 * GIB, 300 * GIB, Math.round(2.34 * TIB)),
            "storage_optimized_l1", new Tier(10, 10, 2048 * GIB, 150 * GIB, null),
            "storage_optimized_l2", new Tier(10, 10, 4096 * GIB, 300 * GIB, null));
    /**
     * A {@code standard3} service in {@code HighDensity} mode: indexes, indexers, data sources and skillsets are
     * "1,000 per partition or 3,000 per service", and an index holds at most 100 GB.
     */
    private static final int HIGH_DENSITY_OBJECTS_PER_PARTITION = 1000;
    private static final int HIGH_DENSITY_OBJECTS_PER_SERVICE = 3000;
    private static final long HIGH_DENSITY_MAX_INDEX_BYTES = 100 * GIB;
    private static final String DEFAULT_SKU = "standard";
    private static final String FREE_SKU = "free";

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
        Quotas quotas = quotas(declared);
        Map<String, Object> counters = new LinkedHashMap<>();
        if (hasAliasesCounter(apiVersion)) {
            counters.put("aliasesCount", counter(0, null));
        }
        counters.put("documentCount", counter(0, null));
        counters.put("indexesCount", counter(indexes.count(cloud, service), quotas.objects()));
        counters.put("indexersCount", counter(0, quotas.objects()));
        counters.put("dataSourcesCount", counter(0, quotas.objects()));
        counters.put("storageSize", counter(0, quotas.storage()));
        counters.put("synonymMaps", counter(0, quotas.synonymMaps()));
        counters.put("skillsetCount", counter(0, quotas.objects()));
        counters.put("vectorIndexSize", counter(0, quotas.vectorStorage()));
        if (hasKnowledgeCounters(apiVersion)) {
            counters.put("knowledgeBasesCount", counter(0, null));
            counters.put("knowledgeSourcesCount", counter(0, null));
        }

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxStoragePerIndex", quotas.maxStoragePerIndex());
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

    /** What servicestats reports a service may hold, its partitions counted. */
    private record Quotas(Integer objects, Integer synonymMaps, Long storage, Long vectorStorage,
                          Long maxStoragePerIndex) {
    }

    /**
     * The quotas of the service's SKU, {@code standard} with one partition for a service ARM does not hold. Storage
     * and vector storage are per partition, so they grow with {@code partitionCount}; an index may take the whole
     * service's storage, up to the cap of {@code standard2} and {@code standard3}. A high-density {@code standard3}
     * service holds 1000 objects per partition, up to 3000, and indexes of at most 100 GB.
     */
    private static Quotas quotas(Optional<SearchServiceResource> declared) {
        String sku = declared.map(SearchServiceResource::sku).orElse(DEFAULT_SKU);
        Tier tier = TIERS.get(sku);
        if (tier == null) {
            return new Quotas(null, null, null, null, null);
        }
        Map<String, Object> properties = declared.map(SearchServiceResource::properties).orElse(Map.of());
        // Free has no partitions: its storage is the service's.
        int partitions = !FREE_SKU.equals(sku) && properties.get("partitionCount") instanceof Number count
                ? count.intValue() : 1;
        long storage = tier.storagePerUnit() * partitions;
        Long vectors = tier.vectorPerUnit() == null ? null : tier.vectorPerUnit() * partitions;
        int objects = tier.objects();
        Long maxIndex = tier.maxIndexBytes();
        boolean highDensity = SearchArmService.HIGH_DENSITY_SKU.equals(sku)
                && "highdensity".equalsIgnoreCase(String.valueOf(properties.get("hostingMode")));
        if (highDensity) {
            objects = Math.min(partitions * HIGH_DENSITY_OBJECTS_PER_PARTITION, HIGH_DENSITY_OBJECTS_PER_SERVICE);
            maxIndex = HIGH_DENSITY_MAX_INDEX_BYTES;
        }
        long perIndex = maxIndex == null ? storage : Math.min(storage, maxIndex);
        return new Quotas(objects, tier.synonymMaps(), storage, vectors, perIndex);
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
