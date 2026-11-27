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
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
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

        SubscriptionCheck subscription = validateSubscriptionKey(request, scope, apiId);
        if (subscription.failure() != null) {
            return subscription.failure();
        }

        Optional<Map<String, Object>> operation = matchedOperationResource(scope,
                apiId, request.method(), match.suffix());
        if (operation.isEmpty() && hasOperations(scope, apiId)) {
            return notFound("No API operation matched: " + gatewayPath);
        }
        PolicyContext policy = applyPolicies(scope, subscription.productId(), apiId,
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
                policy.queryParams(),
                List.copyOf(policy.responseHeaders()));
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

    /**
     * The outcome of the subscription-key check: a refusal, or the product the request came in through (the product
     * of the subscription whose key it sent), whose policy then applies. An API in no product needs no key and comes
     * in through no product.
     */
    private record SubscriptionCheck(Response failure, String productId) {

        static final SubscriptionCheck OPEN = new SubscriptionCheck(null, null);
    }

    private SubscriptionCheck validateSubscriptionKey(AzureRequest request, String scope, String apiId) {
        List<String> requiredProducts = store.productApis().entrySet().stream()
                .filter(e -> e.getValue().endsWith(("/apis/" + apiId).toLowerCase(Locale.ROOT)))
                .filter(e -> e.getKey().startsWith(scope + "products/"))
                .map(e -> productIdFromProductApiKey(e.getKey()))
                .toList();
        if (requiredProducts.isEmpty()) {
            return SubscriptionCheck.OPEN;
        }

        String key = firstNonBlank(firstHeader(request, "Ocp-Apim-Subscription-Key"),
                request.queryParams() == null ? null : request.queryParams().get("subscription-key"));
        if (key == null) {
            return new SubscriptionCheck(unauthorized("Subscription key is required."), null);
        }
        return inService(store.subscriptions(), scope)
                .map(s -> cast(s.get("properties")))
                .filter(p -> "active".equalsIgnoreCase(String.valueOf(p.getOrDefault("state", "active"))))
                .filter(p -> key.equals(p.get("primaryKey")) || key.equals(p.get("secondaryKey")))
                .map(p -> subscribedProduct(requiredProducts, stringValue(p.get("scope"))))
                .flatMap(Optional::stream)
                .findFirst()
                .map(productId -> new SubscriptionCheck(null, productId))
                .orElseGet(() -> new SubscriptionCheck(unauthorized("Subscription key is invalid."), null));
    }

    /** The product among {@code productIds} that a subscription's {@code scope} names, if it names one. */
    private static Optional<String> subscribedProduct(List<String> productIds, String scope) {
        if (scope == null || scope.isBlank()) {
            return Optional.empty();
        }
        String lower = scope.toLowerCase(Locale.ROOT);
        return productIds.stream().filter(productId -> lower.endsWith("/products/" + productId)).findFirst();
    }

    /**
     * Applies the policies of each scope a request passes through, outermost first: the service, the product it came
     * in through (none for an API in no product), the API and the operation.
     */
    private PolicyContext applyPolicies(String scope, String productId, String apiId, String operationId,
                                        String suffix, Map<String, String> requestQueryParams) {
        Map<String, String> queryParams = new LinkedHashMap<>();
        if (requestQueryParams != null) {
            queryParams.putAll(requestQueryParams);
        }
        PolicyContext context = new PolicyContext(scope, null, suffix, new LinkedHashMap<>(), queryParams);
        applyPolicy(context, findPolicyByScopeService(scope));
        if (productId != null) {
            applyPolicy(context, findPolicyByScopeProduct(scope, productId));
        }
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

    /**
     * Applies one scope's policy document section by section, as the real gateway does: {@code <inbound>} and
     * {@code <backend>} act on the request forwarded to the backend, and {@code <outbound>} on the response returned
     * to the caller. {@code <on-error>} is not interpreted.
     */
    private void applyPolicyXml(PolicyContext context, String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            Element inbound = section(root, "inbound");
            Element backend = section(root, "backend");
            Element outbound = section(root, "outbound");
            applySetBackendService(context, inbound);
            applySetBackendService(context, backend);
            applyRewriteUri(context, inbound);
            applyRequestHeaders(context, inbound);
            applyRequestHeaders(context, backend);
            applySetQueryParameters(context, inbound);
            applySetQueryParameters(context, backend);
            applyReturnResponse(context, inbound);
            applyReturnResponse(context, backend);
            collectResponseHeaders(context, outbound);
        } catch (Exception e) {
            LOG.warnf("Ignoring unsupported APIM policy XML: %s", e.getMessage());
        }
    }

    /** The {@code <policies>} child section named {@code name}, or null when the document has none. */
    private static Element section(Element root, String name) {
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    /**
     * The {@code tag} policies of a section, in document order, leaving out those nested in a
     * {@code <return-response>}: those shape the response it returns, not the request.
     */
    private static List<Element> policies(Element section, String tag) {
        List<Element> result = new ArrayList<>();
        if (section == null) {
            return result;
        }
        NodeList nodes = section.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (!insideReturnResponse(element, section)) {
                result.add(element);
            }
        }
        return result;
    }

    private static boolean insideReturnResponse(Element element, Element section) {
        for (Node parent = element.getParentNode(); parent != null && parent != section;
             parent = parent.getParentNode()) {
            if (parent instanceof Element e && "return-response".equals(e.getTagName())) {
                return true;
            }
        }
        return false;
    }

    private void applySetBackendService(PolicyContext context, Element section) {
        List<Element> nodes = policies(section, "set-backend-service");
        if (nodes.isEmpty()) {
            return;
        }
        Element element = nodes.getLast();
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

    private void applyRewriteUri(PolicyContext context, Element section) {
        List<Element> nodes = policies(section, "rewrite-uri");
        if (nodes.isEmpty()) {
            return;
        }
        String template = nodes.getLast().getAttribute("template");
        if (!template.isBlank()) {
            context.suffix(trimSlashes(resolveNamedValues(context.scope(), template)));
        }
    }

    private void applyRequestHeaders(PolicyContext context, Element section) {
        for (Element element : policies(section, "set-header")) {
            applySetHeader(context, context.headers(), element);
        }
    }

    private void applySetHeader(PolicyContext context, Map<String, String> headers, Element element) {
        String name = element.getAttribute("name");
        if (name.isBlank()) {
            return;
        }
        String action = element.getAttribute("exists-action");
        if ("delete".equalsIgnoreCase(action)) {
            headers.remove(name);
            return;
        }
        if ("skip".equalsIgnoreCase(action) && headers.containsKey(name)) {
            return;
        }
        String value = lastValue(context, element);
        if (value == null) {
            return;
        }
        if ("append".equalsIgnoreCase(action) && headers.containsKey(name)) {
            headers.put(name, headers.get(name) + "," + value);
        } else {
            headers.put(name, value);
        }
    }

    /**
     * Records the {@code <outbound>} set-header policies; they apply to the backend's response in the proxy, after
     * every scope's request-side policies have run, in scope order.
     */
    private void collectResponseHeaders(PolicyContext context, Element section) {
        for (Element element : policies(section, "set-header")) {
            String name = element.getAttribute("name");
            if (name.isBlank()) {
                continue;
            }
            String action = element.getAttribute("exists-action");
            context.responseHeaders().add(new ApiManagementProxy.ResponseHeaderAction(name,
                    action.isBlank() ? "override" : action.toLowerCase(Locale.ROOT), lastValue(context, element)));
        }
    }

    private String lastValue(PolicyContext context, Element element) {
        NodeList values = element.getElementsByTagName("value");
        if (values.getLength() == 0) {
            return null;
        }
        return resolveNamedValues(context.scope(), values.item(values.getLength() - 1).getTextContent());
    }

    private void applySetQueryParameters(PolicyContext context, Element section) {
        for (Element element : policies(section, "set-query-parameter")) {
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
            String value = lastValue(context, element);
            if (value != null) {
                context.queryParams().put(name, value);
            }
        }
    }

    private void applyReturnResponse(PolicyContext context, Element section) {
        List<Element> nodes = policies(section, "return-response");
        if (nodes.isEmpty()) {
            return;
        }
        Element element = nodes.getLast();
        NodeList statuses = element.getElementsByTagName("set-status");
        int statusCode = 200;
        if (statuses.getLength() > 0) {
            Element status = (Element) statuses.item(statuses.getLength() - 1);
            String code = status.getAttribute("code");
            if (!code.isBlank()) {
                statusCode = Integer.parseInt(code);
            }
        }
        context.returnStatusCode(statusCode);
        context.returnHeaders().clear();
        NodeList headers = element.getElementsByTagName("set-header");
        for (int i = 0; i < headers.getLength(); i++) {
            applySetHeader(context, context.returnHeaders(), (Element) headers.item(i));
        }
        NodeList bodies = element.getElementsByTagName("set-body");
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

    private Optional<Map<String, Object>> findPolicyByScopeProduct(String scope, String productId) {
        String marker = scope + ("products/" + productId + "/policies/").toLowerCase(Locale.ROOT);
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
        policy.returnHeaders().forEach(response::header);
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
        private final List<ApiManagementProxy.ResponseHeaderAction> responseHeaders = new ArrayList<>();
        private final Map<String, String> returnHeaders = new LinkedHashMap<>();
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

        private List<ApiManagementProxy.ResponseHeaderAction> responseHeaders() {
            return responseHeaders;
        }

        private Map<String, String> returnHeaders() {
            return returnHeaders;
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
