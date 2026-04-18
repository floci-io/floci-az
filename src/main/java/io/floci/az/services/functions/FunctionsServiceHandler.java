package io.floci.az.services.functions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.AzureErrorResponse;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.functions.FunctionModels.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles all Azure Functions emulation requests:
 *
 *   Management  — /{account}-functions/admin/apps/…
 *   Invocation  — /{account}-functions/api/{appName}/{funcName}
 */
@ApplicationScoped
public class FunctionsServiceHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(FunctionsServiceHandler.class);

    private static final String APP_PREFIX  = "__app__:";

    private final StorageBackend<String, StoredObject> store;
    private final FunctionCodeStore codeStore;
    private final FunctionsExecutorService executor;
    private final WarmPool warmPool;
    private final ObjectMapper mapper;

    @Inject
    public FunctionsServiceHandler(StorageFactory storageFactory,
                                   FunctionCodeStore codeStore,
                                   FunctionsExecutorService executor,
                                   WarmPool warmPool) {
        this.store     = storageFactory.create("functions");
        this.codeStore = codeStore;
        this.executor  = executor;
        this.warmPool  = warmPool;
        this.mapper    = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override public String getServiceType() { return "functions"; }

    @Override public boolean canHandle(AzureRequest request) {
        return "functions".equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest request) {
        String path   = request.resourcePath();
        String method = request.method();
        LOG.infof("FunctionsService: %s %s", method, path);

        if (path.startsWith("admin/")) {
            return handleAdmin(request, path.substring(6), method);
        }
        if (path.startsWith("api/")) {
            return handleInvoke(request, path.substring(4));
        }
        return error("NotImplemented", "Not implemented", 501);
    }

    // ── Management API ────────────────────────────────────────────────────────

    private Response handleAdmin(AzureRequest request, String subPath, String method) {
        // subPath examples: "apps", "apps/{appName}", "apps/{appName}/functions",
        //                   "apps/{appName}/functions/{funcName}"
        String[] parts = subPath.split("/", -1);
        if (parts.length == 0 || !parts[0].equals("apps")) {
            return error("NotImplemented", "Only /admin/apps/... is supported", 501);
        }

        if (parts.length == 1) {
            // admin/apps
            return "GET".equalsIgnoreCase(method) ? listApps(request) : methodNotAllowed();
        }

        String appName = parts[1];

        if (parts.length == 2) {
            // admin/apps/{appName}
            return switch (method.toUpperCase()) {
                case "PUT"    -> createApp(request, appName);
                case "GET"    -> getApp(request, appName);
                case "DELETE" -> deleteApp(request, appName);
                default       -> methodNotAllowed();
            };
        }

        if (parts.length == 3 && parts[2].equals("functions")) {
            // admin/apps/{appName}/functions
            return "GET".equalsIgnoreCase(method) ? listFunctions(request, appName) : methodNotAllowed();
        }

        if (parts.length == 4 && parts[2].equals("functions")) {
            String funcName = parts[3];
            return switch (method.toUpperCase()) {
                case "PUT"    -> deployFunction(request, appName, funcName);
                case "GET"    -> getFunction(request, appName, funcName);
                case "DELETE" -> deleteFunction(request, appName, funcName);
                default       -> methodNotAllowed();
            };
        }

        return error("NotImplemented", "Unrecognised admin path: " + subPath, 501);
    }

    private Response createApp(AzureRequest request, String appName) {
        try {
            CreateAppRequest body = mapper.readValue(request.bodyStream(), CreateAppRequest.class);
            if (body.runtime() == null || body.runtime().isBlank()) {
                return error("InvalidInput", "runtime is required", 400);
            }
            String key = appKey(request.accountName(), appName);
            FunctionApp app = new FunctionApp(appName, request.accountName(),
                    body.runtime(), body.environment(), Instant.now());
            store.put(key, toStoredObject(app));
            return Response.status(201).type(MediaType.APPLICATION_JSON)
                    .entity(toAppResponse(app)).build();
        } catch (IOException e) {
            return error("InvalidInput", "Invalid request body: " + e.getMessage(), 400);
        }
    }

    private Response getApp(AzureRequest request, String appName) {
        return store.get(appKey(request.accountName(), appName))
                .map(so -> {
                    try {
                        FunctionApp app = mapper.readValue(so.data(), FunctionApp.class);
                        return Response.ok(toAppResponse(app)).type(MediaType.APPLICATION_JSON).build();
                    } catch (IOException e) {
                        return Response.serverError().build();
                    }
                })
                .orElseGet(() -> error("AppNotFound", "Function app '" + appName + "' not found", 404));
    }

    private Response listApps(AzureRequest request) {
        String prefix = appKey(request.accountName(), "");
        List<AppResponse> apps = store.scan(k -> k.startsWith(prefix)).stream()
                .filter(so -> so.key().startsWith(APP_PREFIX))
                .map(so -> {
                    try { return toAppResponse(mapper.readValue(so.data(), FunctionApp.class)); }
                    catch (IOException e) { return null; }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return Response.ok(new AppListResponse(apps)).type(MediaType.APPLICATION_JSON).build();
    }

    private Response deleteApp(AzureRequest request, String appName) {
        // Drain all warm containers for every function in the app
        String fnPrefix = fnScanPrefix(request.accountName(), appName);
        store.scan(k -> k.startsWith(fnPrefix)).forEach(so -> {
            try {
                FunctionDefinition def = mapper.readValue(so.data(), FunctionDefinition.class);
                warmPool.drain(def.functionKey());
            } catch (IOException ignored) {}
        });
        // Delete all function entries
        store.keys().stream()
                .filter(k -> k.startsWith(fnPrefix))
                .toList()
                .forEach(store::delete);
        // Delete app entry
        store.delete(appKey(request.accountName(), appName));
        // Delete code
        codeStore.deleteApp(request.accountName(), appName);
        return Response.noContent().build();
    }

    private Response deployFunction(AzureRequest request, String appName, String funcName) {
        // Verify app exists and get runtime
        Optional<StoredObject> appSo = store.get(appKey(request.accountName(), appName));
        if (appSo.isEmpty()) {
            return error("AppNotFound", "Function app '" + appName + "' not found", 404);
        }
        FunctionApp app;
        try {
            app = mapper.readValue(appSo.get().data(), FunctionApp.class);
        } catch (IOException e) {
            return Response.serverError().build();
        }

        try {
            DeployFunctionRequest body = mapper.readValue(request.bodyStream(), DeployFunctionRequest.class);

            // Decode and store code
            String codePath = null;
            if (body.zipBase64() != null && !body.zipBase64().isBlank()) {
                byte[] zipBytes = Base64.getDecoder().decode(body.zipBase64());
                codePath = codeStore.storeCode(
                        request.accountName(), appName, funcName, zipBytes).toString();
            }

            // Merge environment: app-level + function-level
            Map<String, String> env = new LinkedHashMap<>();
            if (app.environment() != null) env.putAll(app.environment());
            if (body.environment() != null) env.putAll(body.environment());

            FunctionDefinition def = new FunctionDefinition(
                    appName, funcName, request.accountName(),
                    app.runtime(),
                    body.handler() != null ? body.handler() : "index.handler",
                    body.timeoutSeconds() > 0 ? body.timeoutSeconds() : 230,
                    env.isEmpty() ? null : env,
                    codePath,
                    Instant.now());

            // Drain stale warm containers on redeploy
            warmPool.drain(def.functionKey());
            store.put(fnKey(request.accountName(), appName, funcName), toStoredObject(def));

            return Response.status(201).type(MediaType.APPLICATION_JSON)
                    .entity(toFunctionResponse(def, request.accountName())).build();
        } catch (IOException e) {
            return error("InvalidInput", "Invalid request body: " + e.getMessage(), 400);
        }
    }

    private Response getFunction(AzureRequest request, String appName, String funcName) {
        return store.get(fnKey(request.accountName(), appName, funcName))
                .map(so -> {
                    try {
                        FunctionDefinition def = mapper.readValue(so.data(), FunctionDefinition.class);
                        return Response.ok(toFunctionResponse(def, request.accountName()))
                                .type(MediaType.APPLICATION_JSON).build();
                    } catch (IOException e) {
                        return Response.serverError().build();
                    }
                })
                .orElseGet(() -> error("FunctionNotFound",
                        "Function '" + funcName + "' not found in app '" + appName + "'", 404));
    }

    private Response listFunctions(AzureRequest request, String appName) {
        String prefix = fnScanPrefix(request.accountName(), appName);
        List<FunctionResponse> fns = store.scan(k -> k.startsWith(prefix)).stream()
                .map(so -> {
                    try { return toFunctionResponse(
                            mapper.readValue(so.data(), FunctionDefinition.class), request.accountName()); }
                    catch (IOException e) { return null; }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return Response.ok(new FunctionListResponse(fns)).type(MediaType.APPLICATION_JSON).build();
    }

    private Response deleteFunction(AzureRequest request, String appName, String funcName) {
        String key = fnKey(request.accountName(), appName, funcName);
        store.get(key).ifPresent(so -> {
            try {
                FunctionDefinition def = mapper.readValue(so.data(), FunctionDefinition.class);
                warmPool.drain(def.functionKey());
            } catch (IOException ignored) {}
        });
        store.delete(key);
        codeStore.deleteCode(request.accountName(), appName, funcName);
        return Response.noContent().build();
    }

    // ── Invocation ────────────────────────────────────────────────────────────

    private Response handleInvoke(AzureRequest request, String subPath) {
        // subPath: "{appName}/{funcName}[/extra...]"
        String[] parts = subPath.split("/", 3);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return error("InvalidPath", "Expected api/{appName}/{funcName}", 400);
        }
        String appName  = parts[0];
        String funcName = parts[1];

        Optional<StoredObject> fnSo = store.get(fnKey(request.accountName(), appName, funcName));
        if (fnSo.isEmpty()) {
            LOG.warnf("Function NOT FOUND: %s/%s", appName, funcName);
            return error("FunctionNotFound",
                    "Function '" + funcName + "' not found in app '" + appName + "'", 404);
        }

        try {
            FunctionDefinition def = mapper.readValue(fnSo.get().data(), FunctionDefinition.class);
            return executor.invoke(def, request);
        } catch (IOException e) {
            return Response.serverError().build();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public void clearAll() {
        store.clear();
    }

    private StoredObject toStoredObject(Object obj) {
        try {
            byte[] data = mapper.writeValueAsBytes(obj);
            String key = obj instanceof FunctionApp a ? appKey(a.accountName(), a.appName())
                    : obj instanceof FunctionDefinition d ? fnKey(d.accountName(), d.appName(), d.funcName())
                    : UUID.randomUUID().toString();
            return new StoredObject(key, data, Map.of(), Instant.now(), UUID.randomUUID().toString());
        } catch (Exception e) {
            throw new RuntimeException("Serialization error", e);
        }
    }

    private AppResponse toAppResponse(FunctionApp app) {
        return new AppResponse(app.appName(), app.runtime(), "Running", app.createdAt());
    }

    private FunctionResponse toFunctionResponse(FunctionDefinition def, String accountName) {
        String invokeUrl = "http://localhost:" + "4577" + "/" + accountName
                + "-functions/api/" + def.appName() + "/" + def.funcName();
        return new FunctionResponse(
                def.funcName(), def.appName(), def.runtime(), def.handler(),
                def.timeoutSeconds(), invokeUrl,
                def.codeLocalPath() != null ? "Ready" : "AwaitingDeploy",
                def.createdAt());
    }

    private static String appKey(String account, String appName) {
        return APP_PREFIX + account + "/" + appName;
    }

    private static String fnKey(String account, String appName, String funcName) {
        return account + "/" + appName + "/" + funcName;
    }

    private static String fnScanPrefix(String account, String appName) {
        return account + "/" + appName + "/";
    }

    private static Response error(String code, String message, int status) {
        return new AzureErrorResponse(code, message).toJsonResponse(status);
    }

    private static Response methodNotAllowed() {
        return error("MethodNotAllowed", "HTTP method not allowed on this resource", 405);
    }
}
