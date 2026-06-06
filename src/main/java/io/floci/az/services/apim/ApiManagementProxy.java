package io.floci.az.services.apim;

import io.floci.az.core.AzureRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ApiManagementProxy {

    private final HttpClient httpClient;

    public ApiManagementProxy() {
        this.httpClient = HttpClient.newHttpClient();
    }

    public Response invoke(AzureRequest request, ProxyRequest proxyRequest) {
        if (proxyRequest.serviceUrl() != null) {
            return proxy(request, proxyRequest.serviceUrl(), proxyRequest.backendPath(),
                    proxyRequest.headers(), proxyRequest.queryParams());
        }
        return mock(request, proxyRequest);
    }

    private Response mock(AzureRequest request, ProxyRequest proxyRequest) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", proxyRequest.serviceName());
        body.put("apiId", proxyRequest.apiId());
        body.put("method", request.method());
        body.put("path", "/" + proxyRequest.gatewayPath());
        body.put("backendPath", "/" + trimSlashes(proxyRequest.backendPath()));
        body.put("operationId", proxyRequest.operationId());
        body.put("headers", proxyRequest.headers());
        body.put("queryParams", proxyRequest.queryParams());
        return Response.ok(body).build();
    }

    private Response proxy(AzureRequest request, String serviceUrl, String suffix, Map<String, String> extraHeaders,
                           Map<String, String> extraQueryParams) {
        try {
            String target = serviceUrl.replaceAll("/+$", "") + "/" + trimSlashes(suffix)
                    + queryString(extraQueryParams);
            byte[] body = request.bodyStream() == null ? new byte[0] : request.bodyStream().readAllBytes();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                    .method(request.method(), body.length == 0
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            String contentType = firstHeader(request, "Content-Type");
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            extraHeaders.forEach(builder::header);
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            Response.ResponseBuilder out = Response.status(response.statusCode()).entity(response.body());
            response.headers().firstValue("Content-Type").ifPresent(out::type);
            return out.build();
        } catch (Exception e) {
            return Response.status(502).entity(Map.of("error", Map.of(
                    "code", "BackendUnavailable",
                    "message", e.getMessage() == null ? "Backend unavailable" : e.getMessage()
            ))).build();
        }
    }

    private static String queryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        params.forEach((k, v) -> pairs.add(encode(k) + "=" + encode(v)));
        return "?" + String.join("&", pairs);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String firstHeader(AzureRequest request, String name) {
        if (request.headers() == null) {
            return null;
        }
        return request.headers().getHeaderString(name);
    }

    private static String trimSlashes(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    public record ProxyRequest(String serviceName, String apiId, String gatewayPath, String backendPath,
                               String operationId, String serviceUrl, Map<String, String> headers,
                               Map<String, String> queryParams) {
    }
}
