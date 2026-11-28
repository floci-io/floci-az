package io.floci.az.services.apim;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmErrors;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@ApplicationScoped
public class ApiManagementProxy {

    private final HttpClient httpClient;

    private static final Set<String> NOT_FORWARDED = Set.of("connection", "content-length", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    public ApiManagementProxy() {
        this.httpClient = HttpClient.newHttpClient();
    }

    public Response invoke(AzureRequest request, ProxyRequest proxyRequest) {
        if (proxyRequest.serviceUrl() != null) {
            return proxy(request, proxyRequest);
        }
        return noBackend(proxyRequest);
    }

    // Emulator choice: what the real gateway answers when an API has neither a serviceUrl nor a
    // set-backend-service policy is not documented in any reference we can check, so the request fails
    // as a server-side configuration error rather than inventing a success. Use a return-response
    // policy to mock an API without a backend.
    private Response noBackend(ProxyRequest proxyRequest) {
        return ArmErrors.error(500, "BackendNotConfigured",
                "API '" + proxyRequest.apiId() + "' in service '" + proxyRequest.serviceName()
                        + "' has no backend: set properties.serviceUrl, add a set-backend-service policy,"
                        + " or answer with a return-response policy.");
    }

    private Response proxy(AzureRequest request, ProxyRequest proxyRequest) {
        try {
            String target = proxyRequest.serviceUrl().replaceAll("/+$", "") + "/"
                    + trimSlashes(proxyRequest.backendPath()) + queryString(proxyRequest.queryParams());
            byte[] body = request.bodyStream() == null ? new byte[0] : request.bodyStream().readAllBytes();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                    .method(request.method(), body.length == 0
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            String contentType = firstHeader(request, "Content-Type");
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            proxyRequest.headers().forEach(builder::header);
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            Map<String, List<String>> headers = forwardedResponseHeaders(response.headers().map());
            for (ResponseHeaderAction action : proxyRequest.responseHeaders()) {
                action.applyTo(headers);
            }
            Response.ResponseBuilder out = Response.status(response.statusCode()).entity(response.body());
            headers.forEach((name, values) -> values.forEach(value -> out.header(name, value)));
            return out.build();
        } catch (Exception e) {
            return Response.status(502).entity(Map.of("error", Map.of(
                    "code", "BackendUnavailable",
                    "message", e.getMessage() == null ? "Backend unavailable" : e.getMessage()
            ))).build();
        }
    }

    /**
     * The backend's response headers the gateway passes on to the caller, keyed case-insensitively. The hop-by-hop
     * and framing headers are left for the gateway's own connection to set, and so is every header the backend's
     * {@code Connection} header names (RFC 9110 section 7.6.1), since those belong to that connection alone.
     */
    static Map<String, List<String>> forwardedResponseHeaders(Map<String, List<String>> backendHeaders) {
        Set<String> dropped = new HashSet<>(NOT_FORWARDED);
        backendHeaders.forEach((name, values) -> {
            if ("connection".equalsIgnoreCase(name)) {
                for (String value : values) {
                    for (String token : value.split(",")) {
                        if (!token.isBlank()) {
                            dropped.add(token.trim().toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        });
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        backendHeaders.forEach((name, values) -> {
            if (!name.startsWith(":") && !dropped.contains(name.toLowerCase(Locale.ROOT))) {
                headers.computeIfAbsent(name, k -> new ArrayList<>()).addAll(values);
            }
        });
        return headers;
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
                               Map<String, String> queryParams, List<ResponseHeaderAction> responseHeaders) {
    }

    /**
     * An {@code <outbound>} set-header policy, applied to the backend's response headers before they reach the
     * caller: {@code override} replaces, {@code skip} keeps an existing header, {@code append} adds its values and
     * {@code delete} removes the header. Each {@code <value>} element is one header value, so a policy can set
     * several (two {@code Set-Cookie} lines, for example).
     */
    public record ResponseHeaderAction(String name, String existsAction, List<String> values) {

        public ResponseHeaderAction {
            values = List.copyOf(values);
        }

        void applyTo(Map<String, List<String>> headers) {
            switch (existsAction) {
                case "delete" -> headers.remove(name);
                case "skip" -> {
                    if (!headers.containsKey(name) && !values.isEmpty()) {
                        headers.put(name, new ArrayList<>(values));
                    }
                }
                case "append" -> {
                    if (!values.isEmpty()) {
                        headers.computeIfAbsent(name, k -> new ArrayList<>()).addAll(values);
                    }
                }
                default -> {
                    if (!values.isEmpty()) {
                        headers.put(name, new ArrayList<>(values));
                    }
                }
            }
        }
    }
}
