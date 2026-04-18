package io.floci.az.services.functions;

import io.floci.az.core.AzureErrorResponse;
import io.floci.az.core.AzureRequest;
import io.floci.az.services.functions.FunctionModels.FunctionDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes Azure Function invocations by acquiring a warm container,
 * proxying the HTTP request to it, and releasing the container back.
 */
@ApplicationScoped
public class FunctionsExecutorService {

    private static final Logger LOG = Logger.getLogger(FunctionsExecutorService.class);

    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of(
            "host", "connection", "transfer-encoding", "content-length");

    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of(
            "connection", "transfer-encoding");

    private final WarmPool warmPool;
    private final HttpClient httpClient;

    @Inject
    public FunctionsExecutorService(WarmPool warmPool) {
        this.warmPool   = warmPool;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public Response invoke(FunctionDefinition def, AzureRequest request) {
        if (def.codeLocalPath() == null) {
            return new AzureErrorResponse("FunctionCodeNotDeployed",
                    "No code has been deployed for function '" + def.funcName() + "'. "
                            + "Deploy code first via PUT admin/apps/{app}/functions/{fn}.")
                    .toJsonResponse(409);
        }

        ContainerHandle handle = null;
        try {
            handle = warmPool.acquire(def);
            return proxy(handle, request, def.funcName(), def.timeoutSeconds());
        } catch (Exception e) {
            LOG.errorv("Invocation failed for {0}: {1}", def.functionKey(), e.getMessage());
            if (handle != null) {
                warmPool.drain(def.functionKey());
                handle = null;
            }
            return new AzureErrorResponse("FunctionInvocationError", e.getMessage())
                    .toJsonResponse(502);
        } finally {
            if (handle != null) {
                warmPool.release(handle);
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Response proxy(ContainerHandle handle, AzureRequest request,
                           String funcName, int timeoutSeconds) throws IOException, InterruptedException {
        String targetUrl = "http://" + handle.host() + ":" + handle.port() + "/api/" + funcName;

        // Append query string
        if (!request.queryParams().isEmpty()) {
            String qs = request.queryParams().entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                            + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            targetUrl += "?" + qs;
        }

        // Read body
        byte[] body = new byte[0];
        if (request.bodyStream() != null) {
            body = request.bodyStream().readAllBytes();
        }

        // Build request
        HttpRequest.BodyPublisher publisher = body.length > 0
                ? HttpRequest.BodyPublishers.ofByteArray(body)
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(targetUrl))
                .method(request.method(), publisher)
                .timeout(Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 230));

        // Forward headers (skip hop-by-hop and content-length — Java HttpClient sets it)
        request.headers().getRequestHeaders().forEach((name, values) -> {
            if (!SKIP_REQUEST_HEADERS.contains(name.toLowerCase())) {
                values.forEach(v -> {
                    try { reqBuilder.header(name, v); } catch (Exception ignored) {}
                });
            }
        });

        LOG.debugv("Proxying {0} {1} → {2}", request.method(), funcName, targetUrl);

        HttpResponse<byte[]> resp = httpClient.send(reqBuilder.build(),
                HttpResponse.BodyHandlers.ofByteArray());

        // Build response
        Response.ResponseBuilder rb = Response.status(resp.statusCode());
        resp.headers().map().forEach((name, values) -> {
            if (!SKIP_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                values.forEach(v -> rb.header(name, v));
            }
        });
        if (resp.body() != null && resp.body().length > 0) {
            rb.entity(resp.body());
        }
        return rb.build();
    }
}
