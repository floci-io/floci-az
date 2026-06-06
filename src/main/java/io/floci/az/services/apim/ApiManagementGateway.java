package io.floci.az.services.apim;

import io.floci.az.core.AzureRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@ApplicationScoped
public class ApiManagementGateway {

    private static final Logger LOG = Logger.getLogger(ApiManagementGateway.class);

    private final ApiManagementStore store;
    private final ApiManagementProxy proxy;

    @Inject
    public ApiManagementGateway(ApiManagementStore store, ApiManagementProxy proxy) {
        this.store = store;
        this.proxy = proxy;
    }

    public Response handle(AzureRequest request) {
        String cleanPath = trimSlashes(request.resourcePath());
        if (cleanPath.isBlank()) {
            return notFound("APIM service name is required");
        }
        int slash = cleanPath.indexOf('/');
        String serviceName = slash < 0 ? cleanPath : cleanPath.substring(0, slash);
        String gatewayPath = slash < 0 ? "" : cleanPath.substring(slash + 1);
        Map<String, Object> service = store.services.values().stream()
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
        if (operation.isEmpty() && hasOperations(serviceName, apiId)) {
            return notFound("No API operation matched: " + gatewayPath);
        }
        PolicyContext policy = applyPolicies(serviceName, apiId,
                operation.map(o -> String.valueOf(o.get("name"))).orElse(null),
                match.suffix(), request.queryParams());
        if (policy.returnStatusCode() != null) {
            return policyResponse(policy);
        }
        String serviceUrl = firstNonBlank(policy.backendUrl(), stringValue(cast(match.api().get("properties")).get("serviceUrl")));
        ApiManagementProxy.ProxyRequest proxyRequest = new ApiManagementProxy.ProxyRequest(
                serviceName,
                apiId,
                gatewayPath,
                policy.suffix(),
                operation.map(o -> String.valueOf(o.get("name"))).orElse(null),
                serviceUrl,
                policy.headers(),
                policy.queryParams());
        return proxy.invoke(request, proxyRequest);
    }

    private ApiMatch findApi(String serviceName, String gatewayPath) {
        return store.apis.values().stream()
                .filter(a -> serviceName.equals(a.get("_service")))
                .map(a -> new ApiMatch(a, apiPath(cast(a.get("properties"))), ""))
                .filter(m -> routeMatches(m.apiPath(), gatewayPath))
                .map(m -> new ApiMatch(m.api(), m.apiPath(), suffix(m.apiPath(), gatewayPath)))
                .max(Comparator.comparingInt(m -> m.apiPath().length()))
                .orElse(null);
    }

    private Optional<Map<String, Object>> matchedOperationResource(String serviceName, String apiId, String method,
                                                                  String suffix) {
        String normalized = suffix.isBlank() ? "/" : "/" + trimSlashes(suffix);
        return store.operations.values().stream()
                .filter(o -> serviceName.equals(o.get("_service")) && apiId.equals(o.get("_api")))
                .filter(o -> method.equalsIgnoreCase(String.valueOf(cast(o.get("properties")).get("method"))))
                .filter(o -> operationTemplateMatches(String.valueOf(cast(o.get("properties")).get("urlTemplate")), normalized))
                .findFirst();
    }

    private boolean hasOperations(String serviceName, String apiId) {
        return store.operations.values().stream()
                .anyMatch(o -> serviceName.equals(o.get("_service")) && apiId.equals(o.get("_api")));
    }

    private Response validateSubscriptionKey(AzureRequest request, String serviceName, String apiId) {
        List<String> requiredProducts = store.productApis.entrySet().stream()
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
        boolean valid = store.subscriptions.values().stream()
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

    private PolicyContext applyPolicies(String serviceName, String apiId, String operationId, String suffix,
                                        Map<String, String> requestQueryParams) {
        Map<String, String> queryParams = new LinkedHashMap<>();
        if (requestQueryParams != null) {
            queryParams.putAll(requestQueryParams);
        }
        PolicyContext context = new PolicyContext(serviceName, null, suffix, new LinkedHashMap<>(), queryParams);
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
            applySetQueryParameters(context, doc);
            applyReturnResponse(context, doc);
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
        String backendId = element.getAttribute("backend-id");
        if (!backendId.isBlank()) {
            resolveBackendUrl(context.serviceName(), resolveNamedValues(context.serviceName(), backendId))
                    .ifPresent(context::backendUrl);
            return;
        }
        String baseUrl = element.getAttribute("base-url");
        if (!baseUrl.isBlank()) {
            context.backendUrl(resolveNamedValues(context.serviceName(), baseUrl));
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
            context.suffix(trimSlashes(resolveNamedValues(context.serviceName(), template)));
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
            String action = element.getAttribute("exists-action");
            if ("delete".equalsIgnoreCase(action)) {
                context.headers().remove(name);
                continue;
            }
            if ("skip".equalsIgnoreCase(action) && context.headers().containsKey(name)) {
                continue;
            }
            NodeList values = element.getElementsByTagName("value");
            if (values.getLength() > 0) {
                String value = resolveNamedValues(context.serviceName(),
                        values.item(values.getLength() - 1).getTextContent());
                if ("append".equalsIgnoreCase(action) && context.headers().containsKey(name)) {
                    context.headers().put(name, context.headers().get(name) + "," + value);
                } else {
                    context.headers().put(name, value);
                }
            }
        }
    }

    private void applySetQueryParameters(PolicyContext context, Document doc) {
        NodeList nodes = doc.getElementsByTagName("set-query-parameter");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String name = element.getAttribute("name");
            if (name.isBlank()) {
                continue;
            }
            String action = element.getAttribute("exists-action");
            if ("delete".equalsIgnoreCase(action)) {
                context.queryParams().remove(name);
                continue;
            }
            if ("skip".equalsIgnoreCase(action) && context.queryParams().containsKey(name)) {
                continue;
            }
            NodeList values = element.getElementsByTagName("value");
            if (values.getLength() > 0) {
                context.queryParams().put(name, resolveNamedValues(context.serviceName(),
                        values.item(values.getLength() - 1).getTextContent()));
            }
        }
    }

    private void applyReturnResponse(PolicyContext context, Document doc) {
        NodeList nodes = doc.getElementsByTagName("return-response");
        if (nodes.getLength() == 0) {
            return;
        }
        Element element = (Element) nodes.item(nodes.getLength() - 1);
        NodeList statuses = element.getElementsByTagName("set-status");
        int statusCode = 200;
        if (statuses.getLength() > 0) {
            Element status = (Element) statuses.item(statuses.getLength() - 1);
            String code = status.getAttribute("code");
            if (!code.isBlank()) {
                statusCode = Integer.parseInt(code);
            }
        }
        NodeList bodies = element.getElementsByTagName("set-body");
        context.returnStatusCode(statusCode);
        if (bodies.getLength() > 0) {
            context.returnBody(resolveNamedValues(context.serviceName(),
                    bodies.item(bodies.getLength() - 1).getTextContent()));
        }
    }

    private String resolveNamedValues(String serviceName, String value) {
        if (value == null || !value.contains("{{")) {
            return value;
        }
        java.util.regex.Matcher matcher = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}").matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = findNamedValue(serviceName, name).orElse(matcher.group(0));
            matcher.appendReplacement(resolved, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private Optional<String> findNamedValue(String serviceName, String namedValueId) {
        return store.namedValues.values().stream()
                .filter(n -> serviceName.equals(n.get("_service")) && namedValueId.equals(n.get("name")))
                .map(n -> stringValue(cast(n.get("properties")).get("value")))
                .filter(v -> v != null)
                .findFirst();
    }

    private Optional<String> resolveBackendUrl(String serviceName, String backendId) {
        return store.backends.values().stream()
                .filter(b -> serviceName.equals(b.get("_service")) && backendId.equals(b.get("name")))
                .map(b -> stringValue(cast(b.get("properties")).get("url")))
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    private Optional<Map<String, Object>> findPolicyByScopeService(String serviceName) {
        String marker = "/apim/" + serviceName + "/policies/";
        return store.policies.entrySet().stream()
                .filter(e -> e.getKey().contains(marker) && !e.getKey().contains("/apis/"))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private Optional<Map<String, Object>> findPolicyByScopeApi(String serviceName, String apiId) {
        String marker = "/apim/" + serviceName + "/apis/" + apiId + "/policies/";
        return store.policies.entrySet().stream()
                .filter(e -> e.getKey().contains(marker))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private Optional<Map<String, Object>> findPolicyByScopeOperation(String serviceName, String apiId, String operationId) {
        String marker = "/apim/" + serviceName + "/apis/" + apiId + "/operations/" + operationId + "/policies/";
        return store.policies.entrySet().stream()
                .filter(e -> e.getKey().contains(marker))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private Response policyResponse(PolicyContext policy) {
        Response.ResponseBuilder response = Response.status(policy.returnStatusCode());
        if (policy.returnBody() != null) {
            response.entity(policy.returnBody());
            String trimmed = policy.returnBody().trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                response.type("application/json");
            }
        }
        policy.headers().forEach(response::header);
        return response.build();
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

    private static String firstHeader(AzureRequest request, String name) {
        if (request.headers() == null) {
            return null;
        }
        return request.headers().getHeaderString(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String stringValue(Object value) {
        return value instanceof String s ? s : null;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second != null && !second.isBlank() ? second : null);
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

    private record ApiMatch(Map<String, Object> api, String apiPath, String suffix) {
    }

    private static final class PolicyContext {
        private final String serviceName;
        private String backendUrl;
        private String suffix;
        private final Map<String, String> headers;
        private final Map<String, String> queryParams;
        private Integer returnStatusCode;
        private String returnBody;

        private PolicyContext(String serviceName, String backendUrl, String suffix, Map<String, String> headers,
                              Map<String, String> queryParams) {
            this.serviceName = serviceName;
            this.backendUrl = backendUrl;
            this.suffix = suffix;
            this.headers = headers;
            this.queryParams = queryParams;
        }

        private String serviceName() {
            return serviceName;
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

        private Map<String, String> queryParams() {
            return queryParams;
        }

        private Integer returnStatusCode() {
            return returnStatusCode;
        }

        private void returnStatusCode(Integer returnStatusCode) {
            this.returnStatusCode = returnStatusCode;
        }

        private String returnBody() {
            return returnBody;
        }

        private void returnBody(String returnBody) {
            this.returnBody = returnBody;
        }
    }
}
