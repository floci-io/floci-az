package io.floci.az.services.cosmos.engine;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
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
 * <p>Data-plane traffic (MongoDB wire protocol, CQL, Gremlin WebSocket, etc.)
 * goes DIRECTLY to the engine's native port — it is not proxied through this handler.
 *
 * <p>This handler:
 * <ul>
 *   <li>Triggers on-demand engine startup on first request</li>
 *   <li>Serves connection info (host, port, connection string)</li>
 *   <li>Serves engine health and metadata</li>
 * </ul>
 */
@ApplicationScoped
public class CosmosEngineHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(CosmosEngineHandler.class);

    @Inject CosmosLifecycleManager lifecycleManager;
    @Inject CosmosEngineRegistry registry;

    @Override
    public String getServiceType() {
        // Logical type; routing uses handlesServiceType() for matching
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

        CosmosApi api = providerOpt.get().supportedApi();
        String path = request.resourcePath();

        // Health endpoint
        if ("GET".equals(request.method()) && (path.equals("health") || path.isEmpty())) {
            return handleStatus(api, providerOpt.get());
        }

        // Trigger on-demand startup and return connection info
        Optional<CosmosConnectionInfo> connInfo = lifecycleManager.getOrStart(api);
        if (connInfo.isEmpty()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of(
                    "error", "Cosmos engine " + api + " could not be started",
                    "api", api.name(),
                    "serviceType", serviceType
                ))
                .build();
        }

        // Return connection info as JSON
        return Response.ok(Map.of(
            "api",              api.name(),
            "serviceType",      serviceType,
            "status",           "running",
            "host",             connInfo.get().host(),
            "port",             connInfo.get().port(),
            "connectionString", connInfo.get().connectionString(),
            "notes",            connInfo.get().notes()
        )).build();
    }

    private Response handleStatus(CosmosApi api, CosmosEngineProvider provider) {
        CosmosEngine engine = provider.engine();
        Optional<CosmosConnectionInfo> connInfo = lifecycleManager.getIfRunning(api);

        String status = connInfo.isPresent() ? "running" : "stopped";
        var body = new LinkedHashMap<String, Object>();
        body.put("api",           api.name());
        body.put("engine",        engine.displayName());
        body.put("status",        status);
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
