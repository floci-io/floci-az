package io.floci.az.services.apim;

import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class ApiManagementGateway {

    private static final Logger LOG = Logger.getLogger(ApiManagementGateway.class);

    private final ApiManagementStore store;
    private final ApiManagementService services;
    private final ApiManagementProxy proxy;

    @Inject
    public ApiManagementGateway(ApiManagementStore store, ApiManagementService services, ApiManagementProxy proxy) {
        this.store = store;
        this.services = services;
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
        Optional<String> service = services.liveServiceKey(serviceName);
        if (service.isEmpty()) {
            return notFound("APIM service not found: " + serviceName);
        }
        String scope = service.get();

        ApiMatch match = findApi(scope, gatewayPath);
        if (match == null) {
            return notFound("No API route matched: " + gatewayPath);
        }
        String apiId = String.valueOf(match.api().get("name"));

        Response subscriptionFailure = validateSubscriptionKey(request, scope, apiId);
        if (subscriptionFailure != null) {
            return subscriptionFailure;
        }

        Optional<Map<String, Object>> operation = matchedOperationResource(scope,
                apiId, request.method(), match.suffix());
        if (operation.isEmpty() && hasOperations(scope, apiId)) {
            return notFound("No API operation matched: " + gatewayPath);
        }
        PolicyContext policy = applyPolicies(scope, apiId,
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

    private ApiMatch findApi(String scope, String gatewayPath) {
        return inService(store.apis(), scope)
                .map(a -> new ApiMatch(a, apiPath(cast(a.get("properties"))), ""))
                .filter(m -> routeMatches(m.apiPath(), gatewayPath))
                .map(m -> new ApiMatch(m.api(), m.apiPath(), suffix(m.apiPath(), gatewayPath)))
                .max(Comparator.comparingInt(m -> m.apiPath().length()))
                .orElse(null);
    }

    private Optional<Map<String, Object>> matchedOperationResource(String scope, String apiId, String method,
                                                                  String suffix) {
        String normalized = suffix.isBlank() ? "/" : "/" + trimSlashes(suffix);
        return inService(store.operations(), scope)
                .filter(o -> ApiManagementService.same(apiId, o.get("_api")))
                .filter(o -> method.equalsIgnoreCase(String.valueOf(cast(o.get("properties")).get("method"))))
                .filter(o -> operationTemplateMatches(String.valueOf(cast(o.get("properties")).get("urlTemplate")), normalized))
                .findFirst();
    }

    private boolean hasOperations(String scope, String apiId) {
        return inService(store.operations(), scope)
                .anyMatch(o -> ApiManagementService.same(apiId, o.get("_api")));
    }

    private Response validateSubscriptionKey(AzureRequest request, String scope, String apiId) {
        List<String> requiredProducts = store.productApis().entrySet().stream()
                .filter(e -> e.getValue().endsWith(("/apis/" + apiId).toLowerCase(Locale.ROOT)))
                .filter(e -> e.getKey().startsWith(scope + "products/"))
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
        boolean valid = inService(store.subscriptions(), scope)
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
        return productIds.stream().anyMatch(productId -> scope.toLowerCase(Locale.ROOT).endsWith("/products/" + productId));
    }

    private PolicyContext applyPolicies(String scope, String apiId, String operationId, String suffix,
                                        Map<String, String> requestQueryParams) {
        Map<String, String> queryParams = new LinkedHashMap<>();
        if (requestQueryParams != null) {
            queryParams.putAll(requestQueryParams);
        }
        PolicyContext context = new PolicyContext(scope, null, suffix, new LinkedHashMap<>(), queryParams);
        applyPolicy(context, findPolicyByScopeService(scope));
        applyPolicy(context, findPolicyByScopeApi(scope, apiId));
        if (operationId != null) {
            applyPolicy(context, findPolicyByScopeOperation(scope, apiId, operationId));
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
            resolveBackendUrl(context.scope(), resolveNamedValues(context.scope(), backendId))
                    .ifPresent(context::backendUrl);
            return;
        }
        String baseUrl = element.getAttribute("base-url");
        if (!baseUrl.isBlank()) {
            context.backendUrl(resolveNamedValues(context.scope(), baseUrl));
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
            context.suffix(trimSlashes(resolveNamedValues(context.scope(), template)));
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
                String value = resolveNamedValues(context.scope(),
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
                context.queryParams().put(name, resolveNamedValues(context.scope(),
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
            context.returnBody(resolveNamedValues(context.scope(),
                    bodies.item(bodies.getLength() - 1).getTextContent()));
        }
    }

    private String resolveNamedValues(String scope, String value) {
        if (value == null || !value.contains("{{")) {
            return value;
        }
        Matcher matcher = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}").matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = findNamedValue(scope, name).orElse(matcher.group(0));
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private Optional<String> findNamedValue(String scope, String namedValueId) {
        return inService(store.namedValues(), scope)
                .filter(n -> ApiManagementService.same(namedValueId, n.get("name")))
                .map(n -> stringValue(cast(n.get("properties")).get("value")))
                .filter(v -> v != null)
                .findFirst();
    }

    private Optional<String> resolveBackendUrl(String scope, String backendId) {
        return inService(store.backends(), scope)
                .filter(b -> ApiManagementService.same(backendId, b.get("name")))
                .map(b -> stringValue(cast(b.get("properties")).get("url")))
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    private Optional<Map<String, Object>> findPolicyByScopeService(String scope) {
        String marker = scope + "policies/";
        return store.policies().entrySet().stream()
                .filter(e -> e.getKey().startsWith(marker))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private Optional<Map<String, Object>> findPolicyByScopeApi(String scope, String apiId) {
        String marker = scope + ("apis/" + apiId + "/policies/").toLowerCase(Locale.ROOT);
        return store.policies().entrySet().stream()
                .filter(e -> e.getKey().startsWith(marker))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private Optional<Map<String, Object>> findPolicyByScopeOperation(String scope, String apiId, String operationId) {
        String marker = scope + ("apis/" + apiId + "/operations/" + operationId + "/policies/").toLowerCase(Locale.ROOT);
        return store.policies().entrySet().stream()
                .filter(e -> e.getKey().startsWith(marker))
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

    private static Map<String, Object> cast(Object value) {
        return ArmJson.cast(value);
    }

    private static String stringValue(Object value) {
        return value instanceof String s ? s : null;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second != null && !second.isBlank() ? second : null);
    }

    private Response notFound(String message) {
        return ArmErrors.notFound(message);
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

    /**
     * The resources of one kind that belong to the service whose key prefix is {@code scope}: every child is keyed
     * under its service's {@code sub/rg/apim/name/} prefix, so a deleted service's children of the same name are not.
     */
    private static Stream<Map<String, Object>> inService(Map<String, Map<String, Object>> kind, String scope) {
        return kind.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(scope))
                .map(Map.Entry::getValue);
    }

    private record ApiMatch(Map<String, Object> api, String apiPath, String suffix) {
    }

    private static final class PolicyContext {
        /** The service's key prefix, which selects its named values and backends. */
        private final String scope;
        private String backendUrl;
        private String suffix;
        private final Map<String, String> headers;
        private final Map<String, String> queryParams;
        private Integer returnStatusCode;
        private String returnBody;

        private PolicyContext(String scope, String backendUrl, String suffix, Map<String, String> headers,
                              Map<String, String> queryParams) {
            this.scope = scope;
            this.backendUrl = backendUrl;
            this.suffix = suffix;
            this.headers = headers;
            this.queryParams = queryParams;
        }

        private String scope() {
            return scope;
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
