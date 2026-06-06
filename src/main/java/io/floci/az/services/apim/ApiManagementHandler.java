package io.floci.az.services.apim;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@ApplicationScoped
public class ApiManagementHandler implements AzureServiceHandler {

    private static final Logger LOG = Logger.getLogger(ApiManagementHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Map<String, Object>> services = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> apis = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> operations = new ConcurrentHashMap<>();
    private final EmulatorConfig config;
    private final HttpClient httpClient;

    @Inject
    public ApiManagementHandler(EmulatorConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String getServiceType() {
        return "apim";
    }

    @Override
    public boolean canHandle(AzureRequest request) {
        return "apim".equals(request.serviceType());
    }

    @Override
    public Response handle(AzureRequest request) {
        String cleanPath = trimSlashes(request.resourcePath());
        if (cleanPath.isBlank()) {
            return notFound("APIM service name is required");
        }
        int slash = cleanPath.indexOf('/');
        String serviceName = slash < 0 ? cleanPath : cleanPath.substring(0, slash);
        String gatewayPath = slash < 0 ? "" : cleanPath.substring(slash + 1);
        Map<String, Object> service = services.values().stream()
                .filter(s -> serviceName.equals(s.get("name")))
                .findFirst()
                .orElse(null);
        if (service == null) {
            return notFound("APIM service not found: " + serviceName);
        }

        ApiMatch match = findApi(serviceName, gatewayPath);
        if (match == null) {
            return notFound("No API route matched: " + gatewayPath);
        }

        Map<String, Object> props = cast(match.api().get("properties"));
        String serviceUrl = stringValue(props.get("serviceUrl"));
        if (serviceUrl != null && !serviceUrl.isBlank()) {
            return proxy(request, serviceUrl, match.suffix());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", serviceName);
        body.put("apiId", match.api().get("name"));
        body.put("method", request.method());
        body.put("path", "/" + gatewayPath);
        body.put("operationId", matchedOperation(serviceName, String.valueOf(match.api().get("name")),
                request.method(), match.suffix()).orElse(null));
        return Response.ok(body).build();
    }

    public Response handleArm(AzureRequest request, String path, String method, String sub) {
        String rg = extractRg(path);
        String tail = extractAfter(path, "/providers/Microsoft.ApiManagement/");
        String[] parts = split(tail);

        if (parts.length == 1 && "service".equals(parts[0])) {
            return Response.ok(Map.of("value", listServices(sub, rg))).build();
        }
        if (parts.length < 2 || !"service".equals(parts[0])) {
            return notFound(path);
        }

        String serviceName = parts[1];
        if (parts.length == 2) {
            return switch (method) {
                case "PUT" -> createOrUpdateService(request, sub, rg, serviceName);
                case "GET" -> getResource(serviceKey(sub, rg, serviceName), services, "service/" + serviceName);
                case "DELETE" -> {
                    deleteService(sub, rg, serviceName);
                    yield Response.ok().build();
                }
                default -> Response.status(405).build();
            };
        }

        if (parts.length >= 3 && "apis".equals(parts[2])) {
            if (parts.length == 3) {
                return Response.ok(Map.of("value", listApis(sub, rg, serviceName))).build();
            }
            String apiId = parts[3];
            if (parts.length == 4) {
                return switch (method) {
                    case "PUT" -> createOrUpdateApi(request, sub, rg, serviceName, apiId);
                    case "GET" -> getResource(apiKey(sub, rg, serviceName, apiId), apis, "apis/" + apiId);
                    case "DELETE" -> {
                        deleteApi(sub, rg, serviceName, apiId);
                        yield Response.ok().build();
                    }
                    default -> Response.status(405).build();
                };
            }
            if (parts.length >= 5 && "operations".equals(parts[4])) {
                if (parts.length == 5) {
                    return Response.ok(Map.of("value", listOperations(sub, rg, serviceName, apiId))).build();
                }
                String operationId = parts[5];
                if (parts.length == 6) {
                    return switch (method) {
                        case "PUT" -> createOrUpdateOperation(request, sub, rg, serviceName, apiId, operationId);
                        case "GET" -> getResource(operationKey(sub, rg, serviceName, apiId, operationId),
                                operations, "operations/" + operationId);
                        case "DELETE" -> {
                            operations.remove(operationKey(sub, rg, serviceName, apiId, operationId));
                            yield Response.ok().build();
                        }
                        default -> Response.status(405).build();
                    };
                }
            }
        }

        return notFound(path);
    }

    public List<Map<String, Object>> listServices(String sub, String rg) {
        return services.values().stream()
                .filter(s -> sub.equals(s.get("_sub")) && rg.equals(s.get("_rg")))
                .map(ApiManagementHandler::stripInternal)
                .toList();
    }

    public List<Map<String, Object>> listSubscriptionServices(String sub) {
        return services.values().stream()
                .filter(s -> sub.equals(s.get("_sub")))
                .map(ApiManagementHandler::stripInternal)
                .toList();
    }

    private Response createOrUpdateService(AzureRequest request, String sub, String rg, String serviceName) {
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.put("provisioningState", "Succeeded");
        properties.putIfAbsent("gatewayUrl", gatewayUrl(serviceName));
        properties.putIfAbsent("managementApiUrl", config.effectiveBaseUrl() + "/subscriptions/" + sub
                + "/resourceGroups/" + rg + "/providers/Microsoft.ApiManagement/service/" + serviceName);
        properties.putIfAbsent("publisherEmail", "admin@example.com");
        properties.putIfAbsent("publisherName", "floci-az");

        Map<String, Object> resource = resource(sub, rg, "Microsoft.ApiManagement/service", serviceName,
                "/subscriptions/" + sub + "/resourceGroups/" + rg
                        + "/providers/Microsoft.ApiManagement/service/" + serviceName,
                bodyString(body, "location", "eastus"), properties);
        Object sku = body.get("sku");
        resource.put("sku", sku instanceof Map<?, ?> ? sku : Map.of("name", "Developer", "capacity", 1));
        services.put(serviceKey(sub, rg, serviceName), resource);
        LOG.infof("ARM: created API Management service %s", serviceName);
        return Response.ok(stripInternal(resource)).build();
    }

    private Response createOrUpdateApi(AzureRequest request, String sub, String rg, String serviceName, String apiId) {
        if (!services.containsKey(serviceKey(sub, rg, serviceName))) {
            return notFound("service/" + serviceName);
        }
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.putIfAbsent("displayName", apiId);
        properties.putIfAbsent("path", apiId);
        properties.putIfAbsent("protocols", List.of("https"));
        properties.put("provisioningState", "Succeeded");

        Map<String, Object> resource = resource(sub, rg, "Microsoft.ApiManagement/service/apis", apiId,
                "/subscriptions/" + sub + "/resourceGroups/" + rg
                        + "/providers/Microsoft.ApiManagement/service/" + serviceName + "/apis/" + apiId,
                null, properties);
        resource.put("_service", serviceName);
        apis.put(apiKey(sub, rg, serviceName, apiId), resource);
        return Response.ok(stripInternal(resource)).build();
    }

    private Response createOrUpdateOperation(AzureRequest request, String sub, String rg, String serviceName,
                                             String apiId, String operationId) {
        if (!apis.containsKey(apiKey(sub, rg, serviceName, apiId))) {
            return notFound("apis/" + apiId);
        }
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.putIfAbsent("displayName", operationId);
        properties.putIfAbsent("method", "GET");
        properties.putIfAbsent("urlTemplate", "/");

        Map<String, Object> resource = resource(sub, rg, "Microsoft.ApiManagement/service/apis/operations", operationId,
                "/subscriptions/" + sub + "/resourceGroups/" + rg
                        + "/providers/Microsoft.ApiManagement/service/" + serviceName
                        + "/apis/" + apiId + "/operations/" + operationId,
                null, properties);
        resource.put("_service", serviceName);
        resource.put("_api", apiId);
        operations.put(operationKey(sub, rg, serviceName, apiId, operationId), resource);
        return Response.ok(stripInternal(resource)).build();
    }

    private List<Map<String, Object>> listApis(String sub, String rg, String serviceName) {
        return apis.values().stream()
                .filter(a -> sub.equals(a.get("_sub")) && rg.equals(a.get("_rg")) && serviceName.equals(a.get("_service")))
                .map(ApiManagementHandler::stripInternal)
                .toList();
    }

    private List<Map<String, Object>> listOperations(String sub, String rg, String serviceName, String apiId) {
        return operations.values().stream()
                .filter(o -> sub.equals(o.get("_sub")) && rg.equals(o.get("_rg"))
                        && serviceName.equals(o.get("_service")) && apiId.equals(o.get("_api")))
                .map(ApiManagementHandler::stripInternal)
                .toList();
    }

    private Response getResource(String key, Map<String, Map<String, Object>> store, String path) {
        Map<String, Object> resource = store.get(key);
        return resource == null ? notFound(path) : Response.ok(stripInternal(resource)).build();
    }

    private void deleteService(String sub, String rg, String serviceName) {
        services.remove(serviceKey(sub, rg, serviceName));
        apis.keySet().removeIf(k -> k.startsWith(serviceKey(sub, rg, serviceName) + "/apis/"));
        operations.keySet().removeIf(k -> k.startsWith(serviceKey(sub, rg, serviceName) + "/apis/"));
    }

    private void deleteApi(String sub, String rg, String serviceName, String apiId) {
        apis.remove(apiKey(sub, rg, serviceName, apiId));
        operations.keySet().removeIf(k -> k.startsWith(apiKey(sub, rg, serviceName, apiId) + "/operations/"));
    }

    private ApiMatch findApi(String serviceName, String gatewayPath) {
        return apis.values().stream()
                .filter(a -> serviceName.equals(a.get("_service")))
                .map(a -> new ApiMatch(a, apiPath(cast(a.get("properties"))), ""))
                .filter(m -> routeMatches(m.apiPath(), gatewayPath))
                .map(m -> new ApiMatch(m.api(), m.apiPath(), suffix(m.apiPath(), gatewayPath)))
                .max(Comparator.comparingInt(m -> m.apiPath().length()))
                .orElse(null);
    }

    private Optional<String> matchedOperation(String serviceName, String apiId, String method, String suffix) {
        String normalized = suffix.isBlank() ? "/" : "/" + trimSlashes(suffix);
        return operations.values().stream()
                .filter(o -> serviceName.equals(o.get("_service")) && apiId.equals(o.get("_api")))
                .filter(o -> method.equalsIgnoreCase(String.valueOf(cast(o.get("properties")).get("method"))))
                .filter(o -> operationTemplateMatches(String.valueOf(cast(o.get("properties")).get("urlTemplate")), normalized))
                .map(o -> String.valueOf(o.get("name")))
                .findFirst();
    }

    private Response proxy(AzureRequest request, String serviceUrl, String suffix) {
        try {
            String target = serviceUrl.replaceAll("/+$", "") + "/" + trimSlashes(suffix) + queryString(request.queryParams());
            byte[] body = request.bodyStream() == null ? new byte[0] : request.bodyStream().readAllBytes();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                    .method(request.method(), body.length == 0
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            String contentType = firstHeader(request, "Content-Type");
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
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

    private String gatewayUrl(String serviceName) {
        return config.effectiveBaseUrl().replaceAll("/+$", "") + "/devstoreaccount1-apim/" + serviceName;
    }

    private static Map<String, Object> resource(String sub, String rg, String type, String name, String id,
                                                String location, Map<String, Object> properties) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("_sub", sub);
        resource.put("_rg", rg);
        resource.put("id", id);
        resource.put("name", name);
        resource.put("type", type);
        if (location != null) {
            resource.put("location", location);
        }
        resource.put("etag", "\"" + UUID.randomUUID() + "\"");
        resource.put("properties", properties);
        return resource;
    }

    private static boolean routeMatches(String apiPath, String gatewayPath) {
        String api = trimSlashes(apiPath);
        String path = trimSlashes(gatewayPath);
        return api.isBlank() || path.equals(api) || path.startsWith(api + "/");
    }

    private static String suffix(String apiPath, String gatewayPath) {
        String api = trimSlashes(apiPath);
        String path = trimSlashes(gatewayPath);
        if (api.isBlank()) {
            return path;
        }
        if (path.equals(api)) {
            return "";
        }
        return path.substring(api.length() + 1);
    }

    private static String apiPath(Map<String, Object> properties) {
        return trimSlashes(stringValue(properties.get("path")));
    }

    private static boolean operationTemplateMatches(String template, String path) {
        String normalizedTemplate = template == null || template.isBlank() ? "/" : template;
        String regex = normalizedTemplate.replaceAll("\\{[^/]+}", "[^/]+");
        return Pattern.compile("^" + regex + "$").matcher(path).matches();
    }

    private static String trimSlashes(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private static String[] split(String path) {
        String clean = path == null ? "" : path;
        int q = clean.indexOf('?');
        if (q >= 0) {
            clean = clean.substring(0, q);
        }
        clean = clean.replaceAll("^/+", "").replaceAll("/+$", "");
        if (clean.isBlank()) {
            return new String[0];
        }
        return clean.split("/", -1);
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

    private static String extractRg(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("resourcegroups".equalsIgnoreCase(parts[i])) {
                return parts[i + 1];
            }
        }
        return "unknown";
    }

    private static String extractAfter(String path, String marker) {
        int idx = path.lastIndexOf(marker);
        if (idx < 0) {
            return "unknown";
        }
        String rest = path.substring(idx + marker.length());
        int q = rest.indexOf('?');
        return q >= 0 ? rest.substring(0, q) : rest;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String bodyString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value instanceof String s ? s : defaultValue;
    }

    private static String stringValue(Object value) {
        return value instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(AzureRequest request) {
        try {
            if (request.bodyStream() == null || request.bodyStream().available() == 0) {
                return Map.of();
            }
            return MAPPER.readValue(request.bodyStream(), Map.class);
        } catch (IOException e) {
            return Map.of();
        }
    }

    private static Map<String, Object> stripInternal(Map<String, Object> resource) {
        Map<String, Object> copy = new LinkedHashMap<>(resource);
        copy.remove("_sub");
        copy.remove("_rg");
        copy.remove("_service");
        copy.remove("_api");
        return copy;
    }

    private Response notFound(String message) {
        return Response.status(404).entity(Map.of("error", Map.of(
                "code", "ResourceNotFound",
                "message", message
        ))).build();
    }

    private static String serviceKey(String sub, String rg, String serviceName) {
        return sub + "/" + rg + "/apim/" + serviceName;
    }

    private static String apiKey(String sub, String rg, String serviceName, String apiId) {
        return serviceKey(sub, rg, serviceName) + "/apis/" + apiId;
    }

    private static String operationKey(String sub, String rg, String serviceName, String apiId, String operationId) {
        return apiKey(sub, rg, serviceName, apiId) + "/operations/" + operationId;
    }

    private record ApiMatch(Map<String, Object> api, String apiPath, String suffix) {
    }
}
