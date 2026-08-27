package io.floci.az.services.containerapps;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.docker.ContainerLifecycleManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Proxies public Container App ingress requests to a running local revision replica. */
@ApplicationScoped
public class ContainerAppIngressProxy {

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "content-length", "expect", "host", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade");

    private final HttpClient httpClient;
    private final Duration timeout;

    @Inject
    public ContainerAppIngressProxy(EmulatorConfig config) {
        this.timeout = Duration.ofSeconds(config.services().containerApps().ingressTimeoutSeconds());
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public Response proxy(AzureRequest request, ContainerLifecycleManager.EndpointInfo endpoint) {
        try {
            URI target = URI.create("http://" + endpoint.host() + ":" + endpoint.port()
                    + "/" + trimLeadingSlash(request.resourcePath()) + queryString(request.queryParamsMulti()));
            byte[] body = request.bodyStream() == null ? new byte[0] : request.bodyStream().readAllBytes();
            HttpRequest.Builder outgoing = HttpRequest.newBuilder(target)
                    .timeout(timeout)
                    .method(request.method(), body.length == 0
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));

            if (request.headers() != null) {
                request.headers().getRequestHeaders().forEach((name, values) -> {
                    if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                        values.forEach(value -> outgoing.header(name, value));
                    }
                });
            }

            HttpResponse<byte[]> backend = httpClient.send(
                    outgoing.build(), HttpResponse.BodyHandlers.ofByteArray());
            Response.ResponseBuilder response = Response.status(backend.statusCode()).entity(backend.body());
            backend.headers().map().forEach((name, values) -> {
                if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                    values.forEach(value -> response.header(name, value));
                }
            });
            return response.build();
        } catch (Exception e) {
            return Response.status(502).entity(Map.of("error", Map.of(
                    "code", "ContainerAppUnavailable",
                    "message", e.getMessage() == null ? "Container App ingress is unavailable" : e.getMessage()
            ))).type("application/json").build();
        }
    }

    private static String queryString(Map<String, List<String>> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        StringBuilder query = new StringBuilder("?");
        parameters.forEach((name, values) -> values.forEach(value -> {
            if (query.length() > 1) {
                query.append('&');
            }
            query.append(encode(name)).append('=').append(encode(value));
        }));
        return query.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String trimLeadingSlash(String path) {
        return path == null ? "" : path.replaceFirst("^/+", "");
    }
}
