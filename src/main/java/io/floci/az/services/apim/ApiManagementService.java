package io.floci.az.services.apim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ApiManagementService {

    private static final Logger LOG = Logger.getLogger(ApiManagementService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Map<String, Object>> services;
    private final Map<String, Map<String, Object>> apis;
    private final Map<String, Map<String, Object>> operations;
    private final Map<String, Map<String, Object>> policies;
    private final Map<String, Map<String, Object>> products;
    private final Map<String, Map<String, Object>> subscriptions;
    private final Map<String, Map<String, Object>> namedValues;
    private final Map<String, Map<String, Object>> backends;
    private final Map<String, String> productApis;
    private final EmulatorConfig config;

    @Inject
    public ApiManagementService(EmulatorConfig config, ApiManagementStore store) {
        this.config = config;
        this.services = store.services;
        this.apis = store.apis;
        this.operations = store.operations;
        this.policies = store.policies;
        this.products = store.products;
        this.subscriptions = store.subscriptions;
        this.namedValues = store.namedValues;
        this.backends = store.backends;
        this.productApis = store.productApis;
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

        if (parts.length >= 3 && "namedValues".equals(parts[2])) {
            return handleNamedValues(request, method, sub, rg, serviceName, parts);
        }

        if (parts.length >= 3 && "backends".equals(parts[2])) {
            return handleBackends(request, method, sub, rg, serviceName, parts);
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
                .map(ApiManagementService::stripInternal)
                .toList();
    }

    public List<Map<String, Object>> listSubscriptionServices(String sub) {
        return services.values().stream()
                .filter(s -> sub.equals(s.get("_sub")))
                .map(ApiManagementService::stripInternal)
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
        importOpenApiOperations(sub, rg, serviceName, apiId, properties);
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

    private void importOpenApiOperations(String sub, String rg, String serviceName, String apiId,
                                         Map<String, Object> apiProperties) {
        String format = stringValue(apiProperties.get("format"));
        String value = stringValue(apiProperties.get("value"));
        if (format == null || value == null || !format.toLowerCase().contains("openapi")) {
            return;
        }
        try {
            JsonNode document = MAPPER.readTree(value);
            JsonNode info = document.path("info");
            if (!apiProperties.containsKey("displayName") && info.path("title").isTextual()) {
                apiProperties.put("displayName", info.path("title").asText());
            }
            JsonNode paths = document.path("paths");
            if (!paths.isObject()) {
                return;
            }
            operations.keySet().removeIf(k -> k.startsWith(apiKey(sub, rg, serviceName, apiId) + "/operations/"));
            paths.fields().forEachRemaining(pathEntry -> importOpenApiPath(sub, rg, serviceName, apiId,
                    pathEntry.getKey(), pathEntry.getValue()));
        } catch (Exception e) {
            LOG.warnf("Ignoring unsupported OpenAPI import for API %s: %s", apiId, e.getMessage());
        }
    }

    private void importOpenApiPath(String sub, String rg, String serviceName, String apiId,
                                   String path, JsonNode pathItem) {
        if (!pathItem.isObject()) {
            return;
        }
        pathItem.fields().forEachRemaining(methodEntry -> {
            String method = methodEntry.getKey().toUpperCase();
            if (!isOpenApiHttpMethod(method)) {
                return;
            }
            JsonNode operation = methodEntry.getValue();
            String operationId = operation.path("operationId").isTextual()
                    ? operation.path("operationId").asText()
                    : generatedOperationId(method, path);
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("displayName", firstNonBlank(textValue(operation.path("summary")), operationId));
            properties.put("method", method);
            properties.put("urlTemplate", path == null || path.isBlank() ? "/" : path);

            Map<String, Object> resource = resource(sub, rg,
                    "Microsoft.ApiManagement/service/apis/operations", operationId,
                    "/subscriptions/" + sub + "/resourceGroups/" + rg
                            + "/providers/Microsoft.ApiManagement/service/" + serviceName
                            + "/apis/" + apiId + "/operations/" + operationId,
                    null, properties);
            resource.put("_service", serviceName);
            resource.put("_api", apiId);
            operations.put(operationKey(sub, rg, serviceName, apiId, operationId), resource);
        });
    }

    private List<Map<String, Object>> listApis(String sub, String rg, String serviceName) {
        return apis.values().stream()
                .filter(a -> sub.equals(a.get("_sub")) && rg.equals(a.get("_rg")) && serviceName.equals(a.get("_service")))
                .map(ApiManagementService::stripInternal)
                .toList();
    }

    private List<Map<String, Object>> listOperations(String sub, String rg, String serviceName, String apiId) {
        return operations.values().stream()
                .filter(o -> sub.equals(o.get("_sub")) && rg.equals(o.get("_rg"))
                        && serviceName.equals(o.get("_service")) && apiId.equals(o.get("_api")))
                .map(ApiManagementService::stripInternal)
                .toList();
    }

    private List<Map<String, Object>> listProducts(String sub, String rg, String serviceName) {
        return products.values().stream()
                .filter(p -> sub.equals(p.get("_sub")) && rg.equals(p.get("_rg")) && serviceName.equals(p.get("_service")))
                .map(ApiManagementService::stripInternal)
                .toList();
    }

    private List<Map<String, Object>> listSubscriptions(String sub, String rg, String serviceName) {
        return subscriptions.values().stream()
                .filter(s -> sub.equals(s.get("_sub")) && rg.equals(s.get("_rg")) && serviceName.equals(s.get("_service")))
                .map(ApiManagementService::stripInternal)
                .toList();
    }

    private List<Map<String, Object>> listNamedValues(String sub, String rg, String serviceName) {
        return namedValues.values().stream()
                .filter(n -> sub.equals(n.get("_sub")) && rg.equals(n.get("_rg")) && serviceName.equals(n.get("_service")))
                .map(ApiManagementService::stripInternal)
                .toList();
    }

    private List<Map<String, Object>> listBackends(String sub, String rg, String serviceName) {
        return backends.values().stream()
                .filter(b -> sub.equals(b.get("_sub")) && rg.equals(b.get("_rg")) && serviceName.equals(b.get("_service")))
                .map(ApiManagementService::stripInternal)
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
        namedValues.keySet().removeIf(k -> k.startsWith(serviceKey(sub, rg, serviceName) + "/namedValues/"));
        backends.keySet().removeIf(k -> k.startsWith(serviceKey(sub, rg, serviceName) + "/backends/"));
        productApis.keySet().removeIf(k -> k.startsWith(serviceKey(sub, rg, serviceName) + "/products/"));
    }

    private void deleteApi(String sub, String rg, String serviceName, String apiId) {
        apis.remove(apiKey(sub, rg, serviceName, apiId));
        operations.keySet().removeIf(k -> k.startsWith(apiKey(sub, rg, serviceName, apiId) + "/operations/"));
        policies.keySet().removeIf(k -> k.startsWith(apiKey(sub, rg, serviceName, apiId)));
        productApis.entrySet().removeIf(e -> e.getValue().equals(apiKey(sub, rg, serviceName, apiId)));
    }

    private Response handlePolicy(AzureRequest request, String method, String parentKey, String parentId,
                                  String type, String[] parts) {
        if (parts.length < 1 || !"policies".equals(parts[parts.length - 1]) && parts.length < 2) {
            return notFound(parentId + "/policies");
        }
        if ("policies".equals(parts[parts.length - 1])) {
            List<Map<String, Object>> items = policies.values().stream()
                    .filter(p -> parentKey.equals(p.get("_parent")))
                    .map(ApiManagementService::stripInternal)
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
                        .map(ApiManagementService::stripInternal)
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

    private Response handleNamedValues(AzureRequest request, String method, String sub, String rg,
                                       String serviceName, String[] parts) {
        if (parts.length == 3) {
            return Response.ok(Map.of("value", listNamedValues(sub, rg, serviceName))).build();
        }
        String namedValueId = parts[3];
        return switch (method) {
            case "PUT" -> createOrUpdateNamedValue(request, sub, rg, serviceName, namedValueId);
            case "GET" -> getResource(namedValueKey(sub, rg, serviceName, namedValueId),
                    namedValues, "namedValues/" + namedValueId);
            case "DELETE" -> {
                namedValues.remove(namedValueKey(sub, rg, serviceName, namedValueId));
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    private Response createOrUpdateNamedValue(AzureRequest request, String sub, String rg,
                                              String serviceName, String namedValueId) {
        if (!services.containsKey(serviceKey(sub, rg, serviceName))) {
            return notFound("service/" + serviceName);
        }
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.putIfAbsent("displayName", namedValueId);
        properties.putIfAbsent("value", "");
        properties.putIfAbsent("secret", false);

        Map<String, Object> resource = resource(sub, rg, "Microsoft.ApiManagement/service/namedValues", namedValueId,
                "/subscriptions/" + sub + "/resourceGroups/" + rg
                        + "/providers/Microsoft.ApiManagement/service/" + serviceName + "/namedValues/" + namedValueId,
                null, properties);
        resource.put("_service", serviceName);
        namedValues.put(namedValueKey(sub, rg, serviceName, namedValueId), resource);
        return Response.ok(stripInternal(resource)).build();
    }

    private Response handleBackends(AzureRequest request, String method, String sub, String rg,
                                    String serviceName, String[] parts) {
        if (parts.length == 3) {
            return Response.ok(Map.of("value", listBackends(sub, rg, serviceName))).build();
        }
        String backendId = parts[3];
        return switch (method) {
            case "PUT" -> createOrUpdateBackend(request, sub, rg, serviceName, backendId);
            case "GET" -> getResource(backendKey(sub, rg, serviceName, backendId), backends, "backends/" + backendId);
            case "DELETE" -> {
                backends.remove(backendKey(sub, rg, serviceName, backendId));
                yield Response.ok().build();
            }
            default -> Response.status(405).build();
        };
    }

    private Response createOrUpdateBackend(AzureRequest request, String sub, String rg,
                                           String serviceName, String backendId) {
        if (!services.containsKey(serviceKey(sub, rg, serviceName))) {
            return notFound("service/" + serviceName);
        }
        Map<String, Object> body = parseBody(request);
        Map<String, Object> properties = new LinkedHashMap<>(cast(body.get("properties")));
        properties.putIfAbsent("title", backendId);
        properties.putIfAbsent("protocol", "http");
        properties.putIfAbsent("url", "");

        Map<String, Object> resource = resource(sub, rg, "Microsoft.ApiManagement/service/backends", backendId,
                "/subscriptions/" + sub + "/resourceGroups/" + rg
                        + "/providers/Microsoft.ApiManagement/service/" + serviceName + "/backends/" + backendId,
                null, properties);
        resource.put("_service", serviceName);
        backends.put(backendKey(sub, rg, serviceName, backendId), resource);
        return Response.ok(stripInternal(resource)).build();
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

    private static String textValue(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static boolean isOpenApiHttpMethod(String method) {
        return switch (method) {
            case "GET", "PUT", "POST", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE" -> true;
            default -> false;
        };
    }

    private static String generatedOperationId(String method, String path) {
        String cleanPath = trimSlashes(path).replaceAll("\\{([^/]+)}", "$1")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return method.toLowerCase() + (cleanPath.isBlank() ? "" : "-" + cleanPath);
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
        if ("Microsoft.ApiManagement/service/namedValues".equals(copy.get("type"))) {
            Map<String, Object> properties = new LinkedHashMap<>(cast(copy.get("properties")));
            if (Boolean.parseBoolean(String.valueOf(properties.getOrDefault("secret", false)))) {
                properties.remove("value");
                copy.put("properties", properties);
            }
        }
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

    private static String namedValueKey(String sub, String rg, String serviceName, String namedValueId) {
        return serviceKey(sub, rg, serviceName) + "/namedValues/" + namedValueId;
    }

    private static String backendKey(String sub, String rg, String serviceName, String backendId) {
        return serviceKey(sub, rg, serviceName) + "/backends/" + backendId;
    }

    private static String generatedSubscriptionKey(String subscriptionId, String suffix) {
        return UUID.nameUUIDFromBytes((subscriptionId + ":" + suffix).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    public void clearAll() {
        services.clear();
        apis.clear();
        operations.clear();
        policies.clear();
        products.clear();
        subscriptions.clear();
        namedValues.clear();
        backends.clear();
        productApis.clear();
    }
}
