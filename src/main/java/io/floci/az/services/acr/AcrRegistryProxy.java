package io.floci.az.services.acr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.AzureRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Proxies the Docker Registry HTTP API V2 from {@code {name}.azurecr.io/v2/…} to the single shared
 * {@code registry:2} container, namespacing each repository with the registry name.
 *
 * <p>One container backs every emulated registry, so the registry name is an internal repository
 * prefix: {@code {name}.azurecr.io/v2/app/manifests/v1} reaches the container as
 * {@code /v2/{name}/app/manifests/v1}. The prefix is stripped again from anything the client reads
 * back (upload {@code Location} headers, the catalog), so it never leaks into the client's view.</p>
 */
@ApplicationScoped
public class AcrRegistryProxy {

    private static final Logger LOG = Logger.getLogger(AcrRegistryProxy.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Headers that belong to one hop and must not be forwarded; also the ones HttpClient rejects. */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "content-length", "expect", "host", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade");

    /** The shared container is anonymous: the client's registry token is meaningless to it. */
    private static final Set<String> DROPPED_REQUEST_HEADERS = Set.of("authorization");

    /** Response headers the emulator re-derives rather than copying from the backend. */
    private static final Set<String> REWRITTEN_RESPONSE_HEADERS = Set.of("location");

    /** Methods that can carry a request body, and so may stream one of undeclared length. */
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    /** {@link #pageSize} for a client that asked for no pagination at all. */
    static final int UNPAGINATED = -1;

    /** How long the shared container has to answer one request. */
    private static final Duration BACKEND_TIMEOUT = Duration.ofMinutes(5);

    private static final String V2 = "v2/";
    private static final String CATALOG = "_catalog";
    private static final String TAGS_LIST = "/tags/list";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * The path the shared container sees for a client path under {@code /v2/}. Repository-scoped
     * paths gain the registry prefix; the registry-scoped {@code _catalog} does not.
     */
    static String backendPath(String registryName, String clientPath) {
        String tail = clientPath.startsWith(V2) ? clientPath.substring(V2.length()) : clientPath;
        if (tail.isEmpty() || tail.startsWith("_")) {
            return V2 + tail;
        }
        return V2 + registryName + "/" + tail;
    }

    /**
     * Strips the registry prefix (and any backend authority) from an upload session {@code Location},
     * so the client's next request addresses the repository by the name it used.
     */
    static String clientLocation(String registryName, String location) {
        if (location == null) {
            return null;
        }
        String path = location;
        int schemeEnd = path.indexOf("://");
        if (schemeEnd >= 0) {
            int authorityEnd = path.indexOf('/', schemeEnd + 3);
            path = authorityEnd < 0 ? "/" : path.substring(authorityEnd);
        }
        String prefixed = "/" + V2 + registryName + "/";
        if (path.startsWith(prefixed)) {
            return "/" + V2 + path.substring(prefixed.length());
        }
        return path;
    }

    /** True when the response body names repositories and must be translated to the client's view. */
    static boolean rewritesBody(String clientPath) {
        return clientPath.endsWith(TAGS_LIST);
    }

    /**
     * This registry's repositories, in the order the container reported them, with the internal
     * prefix removed.
     *
     * <p>Reading stops at the first repository outside the prefix. The container walks the
     * repository directory tree, so one registry's repositories are a subtree and therefore
     * contiguous: the first outsider ends this registry's block, and nothing of ours follows it.</p>
     */
    static List<String> ownRepositories(String prefix, byte[] body) {
        List<String> repositories = new ArrayList<>();
        try {
            for (JsonNode repository : MAPPER.readTree(body).path("repositories")) {
                String name = repository.asText("");
                if (!name.startsWith(prefix)) {
                    break;
                }
                repositories.add(name.substring(prefix.length()));
            }
        } catch (Exception e) {
            LOG.debugv("Could not read the registry catalog: {0}", e.getMessage());
        }
        return repositories;
    }

    /**
     * The page size the client asked for, or {@link #UNPAGINATED} when it asked for none.
     *
     * <p>Zero is a request for an empty page, which is not the same as a request for the whole
     * catalog, so the two do not collapse. A size that will not parse, or that is negative, is
     * treated as absent.</p>
     */
    static int pageSize(String declared) {
        if (declared == null || declared.isBlank()) {
            return UNPAGINATED;
        }
        try {
            int declaredSize = Integer.parseInt(declared.trim());
            return declaredSize < 0 ? UNPAGINATED : declaredSize;
        } catch (NumberFormatException e) {
            return UNPAGINATED;
        }
    }

    /** The {@code Link} advertising the next page, in the shape the registry itself uses. */
    static String nextLink(String last, int pageSize) {
        return "</" + V2 + CATALOG + "?last=" + encode(last) + "&n=" + pageSize + ">; rel=\"next\"";
    }

    /**
     * Strips the internal prefix from the {@code name} a repository response reports, so
     * {@code /v2/{repo}/tags/list} names the repository the client asked about.
     */
    static byte[] unprefixRepositoryName(String registryName, byte[] body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (!root.isObject()) {
                return body;
            }
            String name = root.path("name").asText("");
            String prefix = registryName + "/";
            if (!name.startsWith(prefix)) {
                return body;
            }
            ObjectNode object = (ObjectNode) root;
            object.put("name", name.substring(prefix.length()));
            return MAPPER.writeValueAsBytes(object);
        } catch (Exception e) {
            LOG.debugv("Could not rewrite the repository name in a registry response: {0}", e.getMessage());
            return body;
        }
    }

    /**
     * Forwards one {@code /v2/} request to the shared container. Bodies stream in both directions, so
     * a layer is never held in memory: the request keeps the length the client declared, and the
     * response streams back. The exceptions are bodies that name repositories, which are rewritten,
     * and {@code HEAD}, which carries the backend's {@code Content-Length} and no body: Docker reads
     * that length to size a blob it is about to push.
     */
    public Response proxy(AzureRequest request, String registryName, String backendEndpoint) {
        String clientPath = trimLeadingSlash(request.rawPath());
        if (clientPath.equals(V2 + CATALOG)) {
            return catalog(request, registryName, backendEndpoint);
        }
        URI target = URI.create("http://" + backendEndpoint + "/"
                + backendPath(registryName, clientPath) + queryString(request));
        // Subscribed to at most once: only one of the branches below runs.
        HttpRequest.BodyPublisher body = bodyPublisher(request);
        try {
            if ("HEAD".equals(request.method())) {
                return response(send(request, target, body, HttpResponse.BodyHandlers.discarding()),
                        registryName, null, true);
            }
            if (rewritesBody(clientPath)) {
                HttpResponse<byte[]> backend =
                        send(request, target, body, HttpResponse.BodyHandlers.ofByteArray());
                return response(backend, registryName,
                        unprefixRepositoryName(registryName, backend.body()), false);
            }
            HttpResponse<InputStream> backend =
                    send(request, target, body, HttpResponse.BodyHandlers.ofInputStream());
            return response(backend, registryName, backend.body(), false);
        } catch (Exception e) {
            return unavailable(target, e);
        }
    }

    /** Forwards one request to the shared container, carrying the client's headers over. */
    private <T> HttpResponse<T> send(AzureRequest request, URI target, HttpRequest.BodyPublisher body,
                                     HttpResponse.BodyHandler<T> handler) throws Exception {
        HttpRequest.Builder outgoing = HttpRequest.newBuilder(target)
                .timeout(BACKEND_TIMEOUT)
                .method(request.method(), body);
        forwardRequestHeaders(request, outgoing);
        return httpClient.send(outgoing.build(), handler);
    }

    /** The registry error for a container that could not be reached or did not answer. */
    private static Response unavailable(URI target, Exception e) {
        LOG.warnv("ACR data-plane request to {0} failed: {1}", target, e.getMessage());
        return AcrErrors.error(502, AcrErrors.UNAVAILABLE,
                "the registry data plane is unavailable: " + e.getMessage());
    }

    /**
     * Serves {@code /v2/_catalog} as this registry's own catalog, one backend request per page.
     *
     * <p>The container has no filter parameter: the catalog's whole query vocabulary is {@code n}
     * and {@code last}. The filter is therefore expressed as a range, which works because a
     * registry's repositories are a contiguous subtree of the container's walk. Seeding
     * {@code last} with {@code {registry}/} lands exactly on the first of ours, so {@code n} then
     * counts ours rather than everyone's and a page comes back full.</p>
     *
     * <p>One extra repository is requested beyond the page. The container advertises a next page
     * whenever the page it returned was full, not when more results actually exist, so its
     * {@code Link} cannot say whether this registry has more. That extra entry can: outside the
     * prefix it means the block ended here, and the page is the last one.</p>
     *
     * <p>Verified against {@code registry:2} (Distribution 2.8.3). {@code last} is a position in
     * that walk rather than a repository that has to exist, which is what lets the seed name
     * nothing. None of this is in the distribution spec, so
     * {@code AcrCatalogPaginationDockerTest} pins it.</p>
     */
    private Response catalog(AzureRequest request, String registryName, String backendEndpoint) {
        String prefix = registryName + "/";
        int pageSize = pageSize(firstQueryValue(request, "n"));
        URI target = catalogTarget(backendEndpoint, prefix, firstQueryValue(request, "last"), pageSize);
        try {
            if (pageSize == 0) {
                // A page of nothing is what was asked for, so there is nothing to ask the container.
                return catalogPage(List.of(), false, pageSize);
            }
            HttpResponse<byte[]> backend = send(request, target,
                    HttpRequest.BodyPublishers.noBody(), HttpResponse.BodyHandlers.ofByteArray());
            if (backend.statusCode() != 200) {
                return response(backend, registryName, backend.body(), false);
            }

            List<String> repositories = ownRepositories(prefix, backend.body());
            boolean more = pageSize > 0 && repositories.size() > pageSize;
            return catalogPage(more ? repositories.subList(0, pageSize) : repositories, more, pageSize);
        } catch (Exception e) {
            return unavailable(target, e);
        }
    }

    /** The container request behind one catalog page: this registry's range, one repository over. */
    private static URI catalogTarget(String backendEndpoint, String prefix, String clientLast,
                                     int pageSize) {
        StringBuilder query = new StringBuilder("?last=")
                .append(encode(prefix + (clientLast == null ? "" : clientLast)));
        if (pageSize > 0) {
            query.append("&n=").append((long) pageSize + 1);
        }
        return URI.create("http://" + backendEndpoint + "/" + V2 + CATALOG + query);
    }

    /** One catalog page, carrying the cursor to the next only when this registry has more. */
    private static Response catalogPage(List<String> repositories, boolean more, int pageSize)
            throws JsonProcessingException {
        Response.ResponseBuilder page = Response
                .ok(MAPPER.writeValueAsBytes(Map.of("repositories", repositories)))
                .type(MediaType.APPLICATION_JSON);
        if (more) {
            page.header("Link", nextLink(repositories.get(repositories.size() - 1), pageSize));
        }
        return page.build();
    }

    /** The first value of a query parameter, or {@code null} when the client sent none. */
    private static String firstQueryValue(AzureRequest request, String name) {
        Map<String, List<String>> parameters = request.queryParamsMulti();
        if (parameters == null) {
            return null;
        }
        List<String> values = parameters.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /**
     * Streams the request body through, never buffering it: a layer is pushed whole and would
     * otherwise be held in memory in its entirety.
     *
     * <p>A declared {@code Content-Length} is carried over, because that length is what frames a
     * blob upload. A body with no declared length is chunked, which the clients here do not do
     * but an OCI client streaming a layer of unknown size may, and is forwarded chunked in turn.
     * Only methods that can carry a body take that path; a bodyless {@code GET} or {@code DELETE}
     * that simply declared no length must not acquire a chunked frame it never had.</p>
     *
     * <p>The supplier hands out the client's own stream, so it can only be subscribed to once. That
     * is safe because {@code HttpClient} retries only idempotent methods by default, and a blob
     * upload is {@code POST}/{@code PATCH}/{@code PUT}. Do not enable
     * {@code jdk.httpclient.enableAllMethodRetry}: a retry would resubscribe to a stream that has
     * already been drained and push a truncated layer.</p>
     */
    private static HttpRequest.BodyPublisher bodyPublisher(AzureRequest request) {
        long length = declaredContentLength(request);
        if (length == 0 || request.bodyStream() == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        if (length > 0) {
            return HttpRequest.BodyPublishers.fromPublisher(
                    HttpRequest.BodyPublishers.ofInputStream(request::bodyStream), length);
        }
        if (!BODY_METHODS.contains(request.method())) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofInputStream(request::bodyStream);
    }

    /** The client's {@code Content-Length}, or {@code -1} when it declared none. */
    private static long declaredContentLength(AzureRequest request) {
        if (request.headers() == null) {
            return -1;
        }
        String declared = request.headers().getHeaderString("Content-Length");
        if (declared == null || declared.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(declared.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void forwardRequestHeaders(AzureRequest request, HttpRequest.Builder outgoing) {
        if (request.headers() == null) {
            return;
        }
        request.headers().getRequestHeaders().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower) || DROPPED_REQUEST_HEADERS.contains(lower)) {
                return;
            }
            values.forEach(value -> outgoing.header(name, value));
        });
    }

    private static Response response(HttpResponse<?> backend, String registryName, Object entity,
                                     boolean keepContentLength) {
        Response.ResponseBuilder response = Response.status(backend.statusCode()).entity(entity);
        backend.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (REWRITTEN_RESPONSE_HEADERS.contains(lower)) {
                return;
            }
            // Content-Length describes the backend's framing, not ours: a streamed or rewritten body
            // is re-framed on the way out, and only HEAD reports the backend's length verbatim.
            if ("content-length".equals(lower)) {
                if (keepContentLength) {
                    values.forEach(value -> response.header(name, value));
                }
                return;
            }
            if (HOP_BY_HOP.contains(lower)) {
                return;
            }
            values.forEach(value -> response.header(name, value));
        });
        backend.headers().firstValue("Location")
                .map(location -> clientLocation(registryName, location))
                .ifPresent(location -> response.header("Location", location));
        return response.build();
    }

    /**
     * The query to forward, including the leading {@code ?}. The client's raw query goes through
     * byte for byte: registry:2 mints an opaque {@code _state} for an upload session and expects it
     * back exactly as issued, and rebuilding the query from the decoded parameters does not
     * round-trip it. Rebuilding is only the fallback for a request that carried no raw query.
     */
    private static String queryString(AzureRequest request) {
        String raw = request.rawQuery();
        if (raw != null && !raw.isEmpty()) {
            return "?" + raw;
        }
        return queryString(request.queryParamsMulti());
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
