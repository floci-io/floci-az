package io.floci.az.services.search;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.GlobalNameSource;
import io.floci.az.core.arm.StoreIndexSource;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudSuffix;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * HTTP handler for Azure AI Search.
 *
 * <h2>ARM management plane</h2>
 * <p>{@code Microsoft.Search/searchServices} and their admin and query keys, served by {@link SearchArmService}.
 *
 * <h2>Data plane</h2>
 * <p>Requests to {@code {service}.search.windows.net} and to the {@code /{service}-search/} path prefix, served by
 * {@link SearchDataPlane}: index management so far.
 */
@ApplicationScoped
public class SearchHandler implements AzureServiceHandler, Resettable, StoreIndexSource, GlobalNameSource {

    private static final Logger LOG = Logger.getLogger(SearchHandler.class);

    static final String SERVICE_TYPE = "search";

    /** The routing table is keyed by the public cloud's suffix, read from the catalog rather than spelled here. */
    private static final String PUBLIC_HOST_SUFFIX = CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD)
            .suffix(CloudSuffix.SEARCH).orElseThrow();

    private final EmulatorConfig config;
    private final SearchArmService arm;
    private final SearchDataPlane dataPlane;
    private final SearchIndexes indexes;
    private final SearchSolrManager engine;

    @Inject
    public SearchHandler(EmulatorConfig config, SearchArmService arm, SearchDataPlane dataPlane,
                         SearchIndexes indexes, SearchSolrManager engine) {
        this.config = config;
        this.arm = arm;
        this.dataPlane = dataPlane;
        this.indexes = indexes;
        this.engine = engine;
    }

    /** A restart that finds search services or indexes in storage starts the Solr sidecar for their queries. */
    void onStart(@Observes StartupEvent event) {
        if (config.services().search().enabled() && (arm.hasServices() || indexes.any())) {
            engine.prepare();
        }
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().search().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .host("." + PUBLIC_HOST_SUFFIX)
                .account("-search", SERVICE_TYPE)
                .provider(SearchArmSurface.NAMESPACE)
                .build();
    }

    @Override
    public Response handle(AzureRequest req) {
        String path = req.resourcePath();
        LOG.debugf("SearchHandler %s %s", req.method(), path);

        if (ArmPaths.hasProvider(path, SearchArmSurface.NAMESPACE)) {
            return arm.handle(req);
        }
        return dataPlane.handle(req);
    }

    // ── Resource index ───────────────────────────────────────────────────────

    @Override
    public boolean indexEnabled() {
        return config.services().search().enabled();
    }

    @Override
    public List<Map<String, Object>> indexEntries(ArmScope scope) {
        return arm.indexEntries(scope);
    }

    // ── GlobalNameSource ─────────────────────────────────────────────────────

    @Override
    public List<Claim> globalNames() {
        return arm.globalNames();
    }

    @Override
    public boolean holds(CloudSuffix suffix, String name, ArmResourceId owner) {
        return suffix == CloudSuffix.SEARCH && arm.holds(name, owner);
    }

    /** Wipes the search services and their indexes: {@code POST /_admin/reset}. */
    @Override
    public void clear() {
        arm.clear();
        indexes.clear();
    }
}
