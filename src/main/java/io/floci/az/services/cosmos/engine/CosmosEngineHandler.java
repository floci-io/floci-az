package io.floci.az.services.cosmos.engine;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.services.cosmos.table.CosmosTableApiHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP control-plane handler for all Cosmos engine service types
 * (cosmos-mongo, cosmos-table, cosmos-cassandra, cosmos-gremlin,
 *  cosmos-postgresql, cosmos-nosql).
 *
 * <p>For <b>Docker-backed engines</b> (MongoDB, PostgreSQL, Cassandra, Gremlin, NoSQL VNext):
 * <ul>
 *   <li>Triggers on-demand container startup on first request</li>
 *   <li>Returns connection info (host, port, connection string)</li>
 *   <li>All data-plane traffic goes DIRECTLY to the container's native port</li>
 * </ul>
 *
 * <p>For <b>embedded engines</b> (Table):
 * <ul>
 *   <li>{@code /connect} — triggers "start" (no-op for embedded) and returns connection string</li>
 *   <li>All other paths — delegated to {@link CosmosTableApiHandler} for in-process handling</li>
 * </ul>
 */
@ApplicationScoped
public class CosmosEngineHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(CosmosEngineHandler.class);

    @Inject CosmosLifecycleManager  lifecycleManager;
    @Inject CosmosEngineRegistry    registry;
    @Inject CosmosTableApiHandler   tableApiHandler;

    @Override
    public String getServiceType() {
        return "cosmos-engine";
    }

    @Override
    public boolean handlesServiceType(String serviceType) {
        return serviceType != null && serviceType.startsWith("cosmos-") && !serviceType.equals("cosmos");
    }

    @Override
    public boolean canHandle(AzureRequest request) {
        String st = request.serviceType();
        return st != null && st.startsWith("cosmos-") && !st.equals("cosmos");
    }

    @Override
    public Response handle(AzureRequest request) {
        String serviceType = request.serviceType();
        Optional<CosmosEngineProvider> providerOpt = registry.resolveByServiceType(serviceType);

        if (providerOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "Unknown Cosmos API: " + serviceType))
                .build();
        }

        CosmosApi api   = providerOpt.get().supportedApi();
        String    path  = request.resourcePath();

        // Health / status endpoint (all engines)
        if ("GET".equals(request.method()) && (path.equals("health") || path.isEmpty())) {
            return handleStatus(api, providerOpt.get());
        }

        // ── Embedded (in-process) engines ──────────────────────────────────
        // /connect  → trigger "start" (registers virtual running state) and return conn info
        // all other paths → delegate to the embedded handler for real data operations
        if (providerOpt.get().engine().isEmbedded()) {
            Optional<CosmosConnectionInfo> connInfo = lifecycleManager.getOrStart(api);
            if (connInfo.isEmpty()) {
                return disabledResponse(api, serviceType);
            }
            if (isControlPath(path)) {
                return connectionInfoResponse(api, serviceType, connInfo.get());
            }
            // Data path — delegate to the in-process handler
            return tableApiHandler.handle(request);
        }

        // ── Docker-backed engines ───────────────────────────────────────────
        // Trigger on-demand startup; return connection info for all paths.
        Optional<CosmosConnectionInfo> connInfo = lifecycleManager.getOrStart(api);
        if (connInfo.isEmpty()) {
            return disabledResponse(api, serviceType);
        }
        return connectionInfoResponse(api, serviceType, connInfo.get());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Returns true for paths that are control-plane operations (not data-plane). */
    private boolean isControlPath(String path) {
        return path == null || path.isEmpty() || "connect".equals(path) || "health".equals(path);
    }

    private Response connectionInfoResponse(CosmosApi api, String serviceType,
                                             CosmosConnectionInfo connInfo) {
        return Response.ok(Map.of(
            "api",              api.name(),
            "serviceType",      serviceType,
            "status",           "running",
            "host",             connInfo.host(),
            "port",             connInfo.port(),
            "connectionString", connInfo.connectionString(),
            "notes",            connInfo.notes()
        )).build();
    }

    private Response disabledResponse(CosmosApi api, String serviceType) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
            .entity(Map.of(
                "error",       "Cosmos engine " + api + " could not be started",
                "api",         api.name(),
                "serviceType", serviceType
            ))
            .build();
    }

    private Response handleStatus(CosmosApi api, CosmosEngineProvider provider) {
        CosmosEngine engine = provider.engine();
        Optional<CosmosConnectionInfo> connInfo = lifecycleManager.getIfRunning(api);

        String status = connInfo.isPresent() ? "running" : "stopped";
        var body = new LinkedHashMap<String, Object>();
        body.put("api",           api.name());
        body.put("engine",        engine.displayName());
        body.put("status",        status);
        body.put("embedded",      engine.isEmbedded());
        body.put("defaultImage",  engine.defaultImage());
        body.put("defaultPort",   engine.defaultPort());
        body.put("parity",        engine.compatibility().parityLevel());
        if (connInfo.isPresent()) {
            body.put("host",             connInfo.get().host());
            body.put("port",             connInfo.get().port());
            body.put("connectionString", connInfo.get().connectionString());
        }

        return Response.ok(body).build();
    }
}
