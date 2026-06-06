package io.floci.az.services.apim;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
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
    private final Map<String, Map<String, Object>> policies = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> products = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, String> productApis = new ConcurrentHashMap<>();
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
        String apiId = String.valueOf(match.api().get("name"));

        Response subscriptionFailure = validateSubscriptionKey(request, serviceName, apiId);
        if (subscriptionFailure != null) {
            return subscriptionFailure;
        }

        Optional<Map<String, Object>> operation = matchedOperationResource(serviceName,
                apiId, request.method(), match.suffix());
        PolicyContext policy = applyPolicies(serviceName, apiId,
                operation.map(o -> String.valueOf(o.get("name"))).orElse(null),
                match.suffix());
        String serviceUrl = firstNonBlank(policy.backendUrl(), stringValue(cast(match.api().get("properties")).get("serviceUrl")));
        if (serviceUrl != null) {
            return proxy(request, serviceUrl, policy.suffix(), policy.headers());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", serviceName);
        body.put("apiId", apiId);
        body.put("method", request.method());
        body.put("path", "/" + gatewayPath);
        body.put("backendPath", "/" + trimSlashes(policy.suffix()));
        body.put("operationId", operation.map(o -> String.valueOf(o.get("name"))).orElse(null));
        body.put("headers", policy.headers());
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

        if (parts.length >= 3 && "policies".equals(parts[2])) {
            return handlePolicy(request, method, serviceKey(sub, rg, serviceName),
                    "/subscriptions/" + sub + "/resourceGroups/" + rg
                            + "/providers/Microsoft.ApiManagement/service/" + serviceName,
                    "Microsoft.ApiManagement/service/policies", parts);
        }

        if (parts.length >= 3 && "products".equals(parts[2])) {
            return handleProducts(request, method, sub, rg, serviceName, parts);
        }

        if (parts.length >= 3 && "subscriptions".equals(parts[2])) {
            return handleSubscriptions(request, method, sub, rg, serviceName, parts);
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
            if (parts.length >= 5 && "policies".equals(parts[4])) {
                return handlePolicy(request, method, apiKey(sub, rg, serviceName, apiId),
                        "/subscriptions/" + sub + "/resourceGroups/" + rg
                                + "/providers/Microsoft.ApiManagement/service/" + serviceName + "/apis/" + apiId,
                        "Microsoft.ApiManagement/service/apis/policies", parts);
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
                if (parts.length >= 7 && "policies".equals(parts[6])) {
                    return handlePolicy(request, method, operationKey(sub, rg, serviceName, apiId, operationId),
                            "/subscriptions/" + sub + "/resourceGroups/" + rg
                                    + "/providers/Microsoft.ApiManagement/service/" + serviceName
                                    + "/apis/" + apiId + "/operations/" + operationId,
                            "Microsoft.ApiManagement/service/apis/operations/policies", parts);
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

    private List<Map<String, Object>> listProducts(String sub, String rg, String serviceName) {
        return products.values().stream()
                .filter(p -> sub.equals(p.get("_sub")) && rg.equals(p.get("_rg")) && serviceName.equals(p.get("_service")))
                .map(ApiManagementHandler::stripInternal)
                .toList();
    }

    private List<Map<String, Object>> listSubscriptions(String sub, String rg, String serviceName) {
        return subscriptions.values().stream()
                .filter(s -> sub.equals(s.get("_sub")) && rg.equals(s.get("_rg")) && serviceName.equals(s.get("_service")))
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
        policies.keySet().removeIf(k -> k.startsWith(serviceKey(sub, rg, serviceName)));
        products.keySet().removeIf(k -> k.startsWith(serviceKey(sub, rg, serviceName) + "/products/"));
        subscriptions.keySet().removeIf(k -> k.startsWith(serviceKey(sub, rg, serviceName) + "/subscriptions/"));
        productApis.keySet().removeIf(k -> k.startsWith(serviceKey(sub, rg, serviceName) + "/products/"));
    }

    private void deleteApi(String sub, String rg, String serviceName, String apiId) {
        apis.remove(apiKey(sub, rg, serviceName, apiId));
        operations.keySet().removeIf(k -> k.startsWith(apiKey(sub, rg, serviceName, apiId) + "/operations/"));
        policies.keySet().removeIf(k -> k.startsWith(apiKey(sub, rg, serviceName, apiId)));
        productApis.entrySet().removeIf(e -> e.getValue().equals(apiKey(sub, rg, serviceName, apiId)));
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

    private Optional<Map<String, Object>> matchedOperationResource(String serviceName, String apiId, String method, String suffix) {
        String normalized = suffix.isBlank() ? "/" : "/" + trimSlashes(suffix);
        return operations.values().stream()
                .filter(o -> serviceName.equals(o.get("_service")) && apiId.equals(o.get("_api")))
                .filter(o -> method.equalsIgnoreCase(String.valueOf(cast(o.get("properties")).get("method"))))
                .filter(o -> operationTemplateMatches(String.valueOf(cast(o.get("properties")).get("urlTemplate")), normalized))
                .findFirst();
    }

    private Response handlePolicy(AzureRequest request, String method, String parentKey, String parentId,
                                  String type, String[] parts) {
        if (parts.length < 1 || !"policies".equals(parts[parts.length - 1]) && parts.length < 2) {
            return notFound(parentId + "/policies");
        }
        if ("policies".equals(parts[parts.length - 1])) {
            List<Map<String, Object>> items = policies.values().stream()
                    .filter(p -> parentKey.equals(p.get("_parent")))
                    .map(ApiManagementHandler::stripInternal)
                    .toList();
            return Response.ok(Map.of("value", items)).build();
        }

        String policyId = parts[parts.length - 1];
        String key = policyKey(parentKey, policyId);
        return switch (method) {
            case "PUT" -> createOrUpdatePolicy(request, parentKey, parentId, type, policyId, key);
            case "GET" -> getResource(key, policies, "policies/" + policyId);
            case "DELETE" -> {
                policies.remove(key);
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    private Response createOrUpdatePolicy(AzureRequest request, String parentKey, String parentId,
                                          String type, String policyId, String key) {
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.putIfAbsent("format", "rawxml");
        properties.putIfAbsent("value", "");

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("_parent", parentKey);
        resource.put("id", parentId + "/policies/" + policyId);
        resource.put("name", policyId);
        resource.put("type", type);
        resource.put("properties", properties);
        policies.put(key, resource);
        return Response.ok(stripInternal(resource)).build();
    }

    private Response handleProducts(AzureRequest request, String method, String sub, String rg,
                                    String serviceName, String[] parts) {
        if (parts.length == 3) {
            return Response.ok(Map.of("value", listProducts(sub, rg, serviceName))).build();
        }
        String productId = parts[3];
        if (parts.length == 4) {
            return switch (method) {
                case "PUT" -> createOrUpdateProduct(request, sub, rg, serviceName, productId);
                case "GET" -> getResource(productKey(sub, rg, serviceName, productId), products, "products/" + productId);
                case "DELETE" -> {
                    products.remove(productKey(sub, rg, serviceName, productId));
                    productApis.keySet().removeIf(k -> k.startsWith(productKey(sub, rg, serviceName, productId) + "/apis/"));
                    yield Response.ok().build();
                }
                default -> Response.status(405).build();
            };
        }
        if (parts.length >= 5 && "apis".equals(parts[4])) {
            if (parts.length == 5) {
                List<Map<String, Object>> items = productApis.entrySet().stream()
                        .filter(e -> e.getKey().startsWith(productKey(sub, rg, serviceName, productId) + "/apis/"))
                        .map(Map.Entry::getValue)
                        .map(apis::get)
                        .filter(java.util.Objects::nonNull)
                        .map(ApiManagementHandler::stripInternal)
                        .toList();
                return Response.ok(Map.of("value", items)).build();
            }
            String apiId = parts[5];
            return switch (method) {
                case "PUT" -> linkProductApi(sub, rg, serviceName, productId, apiId);
                case "GET" -> getProductApi(sub, rg, serviceName, productId, apiId);
                case "DELETE" -> {
                    productApis.remove(productApiKey(sub, rg, serviceName, productId, apiId));
                    yield Response.ok().build();
                }
                default -> Response.status(405).build();
            };
        }
        return notFound("products/" + productId);
    }

    private Response handleSubscriptions(AzureRequest request, String method, String sub, String rg,
                                         String serviceName, String[] parts) {
        if (parts.length == 3) {
            return Response.ok(Map.of("value", listSubscriptions(sub, rg, serviceName))).build();
        }
        String subscriptionId = parts[3];
        return switch (method) {
            case "PUT" -> createOrUpdateSubscription(request, sub, rg, serviceName, subscriptionId);
            case "GET" -> getResource(subscriptionKey(sub, rg, serviceName, subscriptionId),
                    subscriptions, "subscriptions/" + subscriptionId);
            case "DELETE" -> {
                subscriptions.remove(subscriptionKey(sub, rg, serviceName, subscriptionId));
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    private Response createOrUpdateProduct(AzureRequest request, String sub, String rg,
                                           String serviceName, String productId) {
        if (!services.containsKey(serviceKey(sub, rg, serviceName))) {
            return notFound("service/" + serviceName);
        }
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.putIfAbsent("displayName", productId);
        properties.putIfAbsent("subscriptionRequired", true);
        properties.putIfAbsent("approvalRequired", false);
        properties.putIfAbsent("state", "published");

        Map<String, Object> resource = resource(sub, rg, "Microsoft.ApiManagement/service/products", productId,
                "/subscriptions/" + sub + "/resourceGroups/" + rg
                        + "/providers/Microsoft.ApiManagement/service/" + serviceName + "/products/" + productId,
                null, properties);
        resource.put("_service", serviceName);
        products.put(productKey(sub, rg, serviceName, productId), resource);
        return Response.ok(stripInternal(resource)).build();
    }

    private Response linkProductApi(String sub, String rg, String serviceName, String productId, String apiId) {
        String productKey = productKey(sub, rg, serviceName, productId);
        String apiKey = apiKey(sub, rg, serviceName, apiId);
        if (!products.containsKey(productKey)) {
            return notFound("products/" + productId);
        }
        Map<String, Object> api = apis.get(apiKey);
        if (api == null) {
            return notFound("apis/" + apiId);
        }
        productApis.put(productApiKey(sub, rg, serviceName, productId, apiId), apiKey);
        return Response.ok(stripInternal(api)).build();
    }

    private Response getProductApi(String sub, String rg, String serviceName, String productId, String apiId) {
        String apiKey = productApis.get(productApiKey(sub, rg, serviceName, productId, apiId));
        Map<String, Object> api = apiKey == null ? null : apis.get(apiKey);
        return api == null ? notFound("products/" + productId + "/apis/" + apiId)
                : Response.ok(stripInternal(api)).build();
    }

    private Response createOrUpdateSubscription(AzureRequest request, String sub, String rg,
                                                String serviceName, String subscriptionId) {
        if (!services.containsKey(serviceKey(sub, rg, serviceName))) {
            return notFound("service/" + serviceName);
        }
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.putIfAbsent("displayName", subscriptionId);
        properties.putIfAbsent("state", "active");
        properties.putIfAbsent("scope", "/subscriptions/" + sub + "/resourceGroups/" + rg
                + "/providers/Microsoft.ApiManagement/service/" + serviceName);
        properties.putIfAbsent("primaryKey", generatedSubscriptionKey(subscriptionId, "primary"));
        properties.putIfAbsent("secondaryKey", generatedSubscriptionKey(subscriptionId, "secondary"));

        Map<String, Object> resource = resource(sub, rg, "Microsoft.ApiManagement/service/subscriptions", subscriptionId,
                "/subscriptions/" + sub + "/resourceGroups/" + rg
                        + "/providers/Microsoft.ApiManagement/service/" + serviceName + "/subscriptions/" + subscriptionId,
                null, properties);
        resource.put("_service", serviceName);
        subscriptions.put(subscriptionKey(sub, rg, serviceName, subscriptionId), resource);
        return Response.ok(stripInternal(resource)).build();
    }

    private Response validateSubscriptionKey(AzureRequest request, String serviceName, String apiId) {
        List<String> requiredProducts = productApis.entrySet().stream()
                .filter(e -> e.getValue().endsWith("/apis/" + apiId))
                .filter(e -> e.getKey().contains("/apim/" + serviceName + "/products/"))
                .map(e -> productIdFromProductApiKey(e.getKey()))
                .toList();
        if (requiredProducts.isEmpty()) {
            return null;
        }

        String key = firstNonBlank(firstHeader(request, "Ocp-Apim-Subscription-Key"),
                request.queryParams() == null ? null : request.queryParams().get("subscription-key"));
        if (key == null) {
            return unauthorized("Subscription key is required.");
        }
        boolean valid = subscriptions.values().stream()
                .filter(s -> serviceName.equals(s.get("_service")))
                .map(s -> cast(s.get("properties")))
                .filter(p -> "active".equalsIgnoreCase(String.valueOf(p.getOrDefault("state", "active"))))
                .filter(p -> subscriptionScopeMatches(requiredProducts, stringValue(p.get("scope"))))
                .anyMatch(p -> key.equals(p.get("primaryKey")) || key.equals(p.get("secondaryKey")));
        return valid ? null : unauthorized("Subscription key is invalid.");
    }

    private static boolean subscriptionScopeMatches(List<String> productIds, String scope) {
        if (scope == null || scope.isBlank()) {
            return false;
        }
        return productIds.stream().anyMatch(productId -> scope.equals("/products/" + productId)
                || scope.endsWith("/products/" + productId));
    }

    private PolicyContext applyPolicies(String serviceName, String apiId, String operationId, String suffix) {
        PolicyContext context = new PolicyContext(null, suffix, new LinkedHashMap<>());
        applyPolicy(context, findPolicyByScopeService(serviceName));
        applyPolicy(context, findPolicyByScopeApi(serviceName, apiId));
        if (operationId != null) {
            applyPolicy(context, findPolicyByScopeOperation(serviceName, apiId, operationId));
        }
        return context;
    }

    private void applyPolicy(PolicyContext context, Optional<Map<String, Object>> policy) {
        policy.map(p -> stringValue(cast(p.get("properties")).get("value")))
                .filter(v -> !v.isBlank())
                .ifPresent(xml -> applyPolicyXml(context, xml));
    }

    private void applyPolicyXml(PolicyContext context, String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            applySetBackendService(context, doc);
            applyRewriteUri(context, doc);
            applySetHeaders(context, doc);
        } catch (Exception e) {
            LOG.warnf("Ignoring unsupported APIM policy XML: %s", e.getMessage());
        }
    }

    private void applySetBackendService(PolicyContext context, Document doc) {
        NodeList nodes = doc.getElementsByTagName("set-backend-service");
        if (nodes.getLength() == 0) {
            return;
        }
        Element element = (Element) nodes.item(nodes.getLength() - 1);
        String baseUrl = element.getAttribute("base-url");
        if (!baseUrl.isBlank()) {
            context.backendUrl(baseUrl);
        }
    }

    private void applyRewriteUri(PolicyContext context, Document doc) {
        NodeList nodes = doc.getElementsByTagName("rewrite-uri");
        if (nodes.getLength() == 0) {
            return;
        }
        Element element = (Element) nodes.item(nodes.getLength() - 1);
        String template = element.getAttribute("template");
        if (!template.isBlank()) {
            context.suffix(trimSlashes(template));
        }
    }

    private void applySetHeaders(PolicyContext context, Document doc) {
        NodeList nodes = doc.getElementsByTagName("set-header");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String name = element.getAttribute("name");
            if (name.isBlank()) {
                continue;
            }
            NodeList values = element.getElementsByTagName("value");
            if (values.getLength() > 0) {
                context.headers().put(name, values.item(values.getLength() - 1).getTextContent());
            }
        }
    }

    private Optional<Map<String, Object>> findPolicyByScopeService(String serviceName) {
        String marker = "/apim/" + serviceName + "/policies/";
        return policies.entrySet().stream()
                .filter(e -> e.getKey().contains(marker) && !e.getKey().contains("/apis/"))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private Optional<Map<String, Object>> findPolicyByScopeApi(String serviceName, String apiId) {
        String marker = "/apim/" + serviceName + "/apis/" + apiId + "/policies/";
        return policies.entrySet().stream()
                .filter(e -> e.getKey().contains(marker))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private Optional<Map<String, Object>> findPolicyByScopeOperation(String serviceName, String apiId, String operationId) {
        String marker = "/apim/" + serviceName + "/apis/" + apiId + "/operations/" + operationId + "/policies/";
        return policies.entrySet().stream()
                .filter(e -> e.getKey().contains(marker))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private Response proxy(AzureRequest request, String serviceUrl, String suffix, Map<String, String> extraHeaders) {
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

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second != null && !second.isBlank() ? second : null);
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
        copy.remove("_parent");
        return copy;
    }

    private Response notFound(String message) {
        return Response.status(404).entity(Map.of("error", Map.of(
                "code", "ResourceNotFound",
                "message", message
        ))).build();
    }

    private Response unauthorized(String message) {
        return Response.status(401).entity(Map.of("error", Map.of(
                "code", "AuthenticationFailed",
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

    private static String policyKey(String parentKey, String policyId) {
        return parentKey + "/policies/" + policyId;
    }

    private static String productKey(String sub, String rg, String serviceName, String productId) {
        return serviceKey(sub, rg, serviceName) + "/products/" + productId;
    }

    private static String subscriptionKey(String sub, String rg, String serviceName, String subscriptionId) {
        return serviceKey(sub, rg, serviceName) + "/subscriptions/" + subscriptionId;
    }

    private static String productApiKey(String sub, String rg, String serviceName, String productId, String apiId) {
        return productKey(sub, rg, serviceName, productId) + "/apis/" + apiId;
    }

    private static String productIdFromProductApiKey(String key) {
        String marker = "/products/";
        int start = key.indexOf(marker);
        if (start < 0) {
            return "";
        }
        String rest = key.substring(start + marker.length());
        int end = rest.indexOf('/');
        return end < 0 ? rest : rest.substring(0, end);
    }

    private static String generatedSubscriptionKey(String subscriptionId, String suffix) {
        return UUID.nameUUIDFromBytes((subscriptionId + ":" + suffix).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    private record ApiMatch(Map<String, Object> api, String apiPath, String suffix) {
    }

    private static final class PolicyContext {
        private String backendUrl;
        private String suffix;
        private final Map<String, String> headers;

        private PolicyContext(String backendUrl, String suffix, Map<String, String> headers) {
            this.backendUrl = backendUrl;
            this.suffix = suffix;
            this.headers = headers;
        }

        private String backendUrl() {
            return backendUrl;
        }

        private void backendUrl(String backendUrl) {
            this.backendUrl = backendUrl;
        }

        private String suffix() {
            return suffix;
        }

        private void suffix(String suffix) {
            this.suffix = suffix;
        }

        private Map<String, String> headers() {
            return headers;
        }
    }
}
