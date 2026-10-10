package io.floci.az.services.apim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Quarkus-level tests for {@link ApiManagementHandler}: the ARM control plane for the service and its child
 * resources, and the gateway's routing, policy and proxy behaviour. A small in-process {@link HttpServer}
 * stands in for the backend and answers with what it received, so forwarded paths, headers and query
 * parameters can be asserted.
 */
@QuarkusTest
class ApiManagementHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SUB = "test-sub-apim";
    private static final String RG = "test-rg-apim";
    private static final String SERVICE = "unit-apim";
    private static final String API = "?api-version=2024-05-01";
    private static final String SERVICE_ID =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.ApiManagement/service/" + SERVICE;
    private static final String GATEWAY = "/devstoreaccount1-apim/" + SERVICE;

    private HttpServer backend;

    @BeforeEach
    void setUp() throws IOException {
        given().post("/_admin/reset").then().statusCode(204);
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", ApiManagementHandlerTest::echo);
        backend.start();
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.stop(0);
        }
    }

    private String backendUrl() {
        return "http://127.0.0.1:" + backend.getAddress().getPort();
    }

    private static void echo(HttpExchange exchange) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("method", exchange.getRequestMethod());
        body.put("path", exchange.getRequestURI().getPath());
        ObjectNode query = body.putObject("query");
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                String[] kv = pair.split("=", 2);
                query.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        ObjectNode headers = body.putObject("headers");
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            headers.put(header.getKey().toLowerCase(Locale.ROOT), String.join(",", header.getValue()));
        }
        body.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] response = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(201, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    // ── ARM helpers ──────────────────────────────────────────────────────────────

    private static void put(String relative, String body) {
        given().contentType("application/json").body(body)
                .when().put(SERVICE_ID + relative + API)
                .then().statusCode(200);
    }

    private static void createService() {
        put("", "{\"location\":\"westeurope\",\"properties\":{\"publisherEmail\":\"a@b.c\",\"publisherName\":\"unit\"}}");
    }

    private void createApi(String apiId, String path, boolean withBackend) {
        String serviceUrl = withBackend ? ",\"serviceUrl\":\"" + backendUrl() + "\"" : "";
        put("/apis/" + apiId, "{\"properties\":{\"displayName\":\"" + apiId + "\",\"path\":\"" + path + "\""
                + serviceUrl + "}}");
    }

    private static void createOperation(String apiId, String operationId, String method, String urlTemplate) {
        put("/apis/" + apiId + "/operations/" + operationId,
                "{\"properties\":{\"method\":\"" + method + "\",\"urlTemplate\":\"" + urlTemplate + "\"}}");
    }

    private static void putPolicy(String scope, String inbound) throws IOException {
        String xml = "<policies><inbound><base />" + inbound + "</inbound><backend><base /></backend>"
                + "<outbound><base /></outbound><on-error><base /></on-error></policies>";
        String body = MAPPER.writeValueAsString(Map.of("properties", Map.of("format", "rawxml", "value", xml)));
        put(scope + "/policies/policy", body);
    }

    // ── ARM: service ─────────────────────────────────────────────────────────────

    @Test
    void createGetListAndDeleteService() {
        given().contentType("application/json")
                .body("{\"location\":\"westeurope\",\"sku\":{\"name\":\"Consumption\",\"capacity\":0},"
                        + "\"properties\":{\"publisherEmail\":\"a@b.c\",\"publisherName\":\"unit\"}}")
                .when().put(SERVICE_ID + API)
                .then().statusCode(200)
                .body("id", equalTo(SERVICE_ID))
                .body("name", equalTo(SERVICE))
                .body("type", equalTo("Microsoft.ApiManagement/service"))
                .body("location", equalTo("westeurope"))
                .body("sku.name", equalTo("Consumption"))
                .body("etag", notNullValue())
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.publisherEmail", equalTo("a@b.c"))
                .body("properties.gatewayUrl", endsWith("/devstoreaccount1-apim/" + SERVICE))
                .body("_sub", nullValue())
                .body("_rg", nullValue());

        given().when().get(SERVICE_ID + API)
                .then().statusCode(200)
                .body("name", equalTo(SERVICE));

        given().when().get("/subscriptions/" + SUB + "/resourceGroups/" + RG
                        + "/providers/Microsoft.ApiManagement/service" + API)
                .then().statusCode(200)
                .body("value.name", hasItem(SERVICE));

        given().when().delete(SERVICE_ID + API).then().statusCode(200);
        given().when().get(SERVICE_ID + API)
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void serviceDefaultsSkuAndPublisherWhenOmitted() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .when().put(SERVICE_ID + API)
                .then().statusCode(200)
                .body("sku.name", equalTo("Developer"))
                .body("sku.capacity", equalTo(1))
                .body("properties.publisherName", equalTo("floci-az"));
    }

    @Test
    void serviceNameAlreadyUsedInAnotherResourceGroupConflicts() {
        createService();
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .when().put("/subscriptions/" + SUB + "/resourceGroups/other-rg"
                        + "/providers/Microsoft.ApiManagement/service/" + SERVICE + API)
                .then().statusCode(409)
                .body("error.code", equalTo("ServiceAlreadyExists"));
    }

    @Test
    void deletingTheServiceRemovesItsChildren() {
        createService();
        createApi("orders", "orders", true);
        put("/products/starter", "{\"properties\":{}}");

        given().when().delete(SERVICE_ID + API).then().statusCode(200);
        createService();

        given().when().get(SERVICE_ID + "/apis" + API)
                .then().statusCode(200)
                .body("value", hasSize(0));
        given().when().get(SERVICE_ID + "/products" + API)
                .then().statusCode(200)
                .body("value", hasSize(0));
    }

    // ── ARM: APIs and operations ─────────────────────────────────────────────────

    @Test
    void createGetListAndDeleteApi() {
        createService();
        given().contentType("application/json")
                .body("{\"properties\":{\"displayName\":\"Orders\",\"path\":\"orders\",\"serviceUrl\":\"http://example\"}}")
                .when().put(SERVICE_ID + "/apis/orders" + API)
                .then().statusCode(200)
                .body("id", equalTo(SERVICE_ID + "/apis/orders"))
                .body("type", equalTo("Microsoft.ApiManagement/service/apis"))
                .body("properties.path", equalTo("orders"))
                .body("properties.serviceUrl", equalTo("http://example"))
                .body("properties.protocols", hasItem("https"))
                .body("_service", nullValue());

        given().when().get(SERVICE_ID + "/apis/orders" + API)
                .then().statusCode(200)
                .body("properties.displayName", equalTo("Orders"));
        given().when().get(SERVICE_ID + "/apis" + API)
                .then().statusCode(200)
                .body("value.name", hasItem("orders"));

        given().when().delete(SERVICE_ID + "/apis/orders" + API).then().statusCode(200);
        given().when().get(SERVICE_ID + "/apis/orders" + API).then().statusCode(404);
    }

    @Test
    void apiUnderMissingServiceIsNotFound() {
        given().contentType("application/json").body("{\"properties\":{\"path\":\"orders\"}}")
                .when().put(SERVICE_ID + "/apis/orders" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void createGetListAndDeleteOperation() {
        createService();
        createApi("orders", "orders", true);
        given().contentType("application/json")
                .body("{\"properties\":{\"displayName\":\"Get order\",\"method\":\"GET\",\"urlTemplate\":\"/orders/{id}\"}}")
                .when().put(SERVICE_ID + "/apis/orders/operations/get-order" + API)
                .then().statusCode(200)
                .body("type", equalTo("Microsoft.ApiManagement/service/apis/operations"))
                .body("properties.method", equalTo("GET"))
                .body("properties.urlTemplate", equalTo("/orders/{id}"))
                .body("_api", nullValue());

        given().when().get(SERVICE_ID + "/apis/orders/operations/get-order" + API)
                .then().statusCode(200)
                .body("properties.displayName", equalTo("Get order"));
        given().when().get(SERVICE_ID + "/apis/orders/operations" + API)
                .then().statusCode(200)
                .body("value.name", hasItem("get-order"));

        given().when().delete(SERVICE_ID + "/apis/orders/operations/get-order" + API).then().statusCode(200);
        given().when().get(SERVICE_ID + "/apis/orders/operations/get-order" + API).then().statusCode(404);
    }

    @Test
    void operationUnderMissingApiIsNotFound() {
        createService();
        given().contentType("application/json").body("{\"properties\":{}}")
                .when().put(SERVICE_ID + "/apis/missing/operations/op" + API)
                .then().statusCode(404);
    }

    @Test
    void openApiImportGeneratesOperationsAndReimportReplacesThem() throws IOException {
        createService();
        String spec = MAPPER.writeValueAsString(Map.of(
                "openapi", "3.0.1",
                "info", Map.of("title", "Orders API", "version", "1"),
                "paths", Map.of(
                        "/orders/{orderId}", Map.of("get", Map.of("operationId", "getOrder")),
                        "/orders", Map.of("post", Map.of("summary", "Create")))));
        put("/apis/imported", MAPPER.writeValueAsString(Map.of("properties",
                Map.of("path", "imported", "format", "openapi+json", "value", spec))));

        given().when().get(SERVICE_ID + "/apis/imported/operations" + API)
                .then().statusCode(200)
                .body("value", hasSize(2))
                .body("value.name", hasItem("getOrder"))
                .body("value.name", hasItem("post-orders"));

        String updated = MAPPER.writeValueAsString(Map.of(
                "openapi", "3.0.1",
                "info", Map.of("title", "Customers API", "version", "2"),
                "paths", Map.of("/customers/{id}", Map.of("get", Map.of("operationId", "getCustomer")))));
        put("/apis/imported", MAPPER.writeValueAsString(Map.of("properties",
                Map.of("path", "imported", "format", "openapi+json", "value", updated))));

        given().when().get(SERVICE_ID + "/apis/imported/operations" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value.name", hasItem("getCustomer"));
    }

    // ── ARM: policies, products, subscriptions, named values, backends ──────────

    @Test
    void policiesAtServiceApiAndOperationScope() throws IOException {
        createService();
        createApi("orders", "orders", true);
        createOperation("orders", "get-order", "GET", "/orders/{id}");

        putPolicy("", "<set-header name=\"X-Scope\" exists-action=\"override\"><value>service</value></set-header>");
        putPolicy("/apis/orders", "<rewrite-uri template=\"/v2\" />");
        putPolicy("/apis/orders/operations/get-order", "<set-query-parameter name=\"q\" exists-action=\"override\">"
                + "<value>1</value></set-query-parameter>");

        given().when().get(SERVICE_ID + "/policies/policy" + API)
                .then().statusCode(200)
                .body("id", equalTo(SERVICE_ID + "/policies/policy"))
                .body("type", equalTo("Microsoft.ApiManagement/service/policies"))
                .body("properties.format", equalTo("rawxml"))
                .body("properties.value", containsString("X-Scope"));
        given().when().get(SERVICE_ID + "/apis/orders/policies" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].type", equalTo("Microsoft.ApiManagement/service/apis/policies"));
        given().when().get(SERVICE_ID + "/apis/orders/operations/get-order/policies/policy" + API)
                .then().statusCode(200)
                .body("type", equalTo("Microsoft.ApiManagement/service/apis/operations/policies"));

        given().when().delete(SERVICE_ID + "/apis/orders/policies/policy" + API).then().statusCode(200);
        given().when().get(SERVICE_ID + "/apis/orders/policies/policy" + API).then().statusCode(404);
    }

    @Test
    void productsAndProductApiLinks() {
        createService();
        createApi("orders", "orders", true);
        given().contentType("application/json").body("{\"properties\":{\"displayName\":\"Starter\"}}")
                .when().put(SERVICE_ID + "/products/starter" + API)
                .then().statusCode(200)
                .body("type", equalTo("Microsoft.ApiManagement/service/products"))
                .body("properties.subscriptionRequired", equalTo(true))
                .body("properties.approvalRequired", equalTo(false))
                .body("properties.state", equalTo("published"));
        given().when().get(SERVICE_ID + "/products" + API)
                .then().statusCode(200)
                .body("value.name", hasItem("starter"));

        given().contentType("application/json").body("{}")
                .when().put(SERVICE_ID + "/products/starter/apis/orders" + API)
                .then().statusCode(200)
                .body("name", equalTo("orders"));
        given().when().get(SERVICE_ID + "/products/starter/apis/orders" + API)
                .then().statusCode(200)
                .body("name", equalTo("orders"));
        given().when().get(SERVICE_ID + "/products/starter/apis" + API)
                .then().statusCode(200)
                .body("value.name", hasItem("orders"));

        given().contentType("application/json").body("{}")
                .when().put(SERVICE_ID + "/products/starter/apis/missing" + API)
                .then().statusCode(404);

        given().when().delete(SERVICE_ID + "/products/starter/apis/orders" + API).then().statusCode(200);
        given().when().get(SERVICE_ID + "/products/starter/apis/orders" + API).then().statusCode(404);

        given().when().delete(SERVICE_ID + "/products/starter" + API).then().statusCode(200);
        given().when().get(SERVICE_ID + "/products/starter" + API).then().statusCode(404);
    }

    @Test
    void subscriptionsGenerateKeysAndDefaultScope() {
        createService();
        given().contentType("application/json").body("{\"properties\":{\"displayName\":\"Mine\"}}")
                .when().put(SERVICE_ID + "/subscriptions/mine" + API)
                .then().statusCode(200)
                .body("type", equalTo("Microsoft.ApiManagement/service/subscriptions"))
                .body("properties.state", equalTo("active"))
                .body("properties.scope", equalTo(SERVICE_ID))
                .body("properties.primaryKey", notNullValue())
                .body("properties.secondaryKey", notNullValue());
        given().when().get(SERVICE_ID + "/subscriptions" + API)
                .then().statusCode(200)
                .body("value.name", hasItem("mine"));

        given().when().delete(SERVICE_ID + "/subscriptions/mine" + API).then().statusCode(200);
        given().when().get(SERVICE_ID + "/subscriptions/mine" + API).then().statusCode(404);
    }

    @Test
    void secretNamedValuesHideTheirValue() {
        createService();
        put("/namedValues/plain", "{\"properties\":{\"value\":\"visible\"}}");
        put("/namedValues/hidden", "{\"properties\":{\"value\":\"s3cret\",\"secret\":true}}");

        given().when().get(SERVICE_ID + "/namedValues/plain" + API)
                .then().statusCode(200)
                .body("type", equalTo("Microsoft.ApiManagement/service/namedValues"))
                .body("properties.value", equalTo("visible"));
        given().when().get(SERVICE_ID + "/namedValues/hidden" + API)
                .then().statusCode(200)
                .body("properties.secret", equalTo(true))
                .body("properties", not(hasKey("value")));
        given().when().get(SERVICE_ID + "/namedValues" + API)
                .then().statusCode(200)
                .body("value.find { it.name == 'hidden' }.properties", not(hasKey("value")));

        given().when().delete(SERVICE_ID + "/namedValues/plain" + API).then().statusCode(200);
        given().when().get(SERVICE_ID + "/namedValues/plain" + API).then().statusCode(404);
    }

    @Test
    void createGetListAndDeleteBackend() {
        createService();
        given().contentType("application/json").body("{\"properties\":{\"url\":\"http://backend\"}}")
                .when().put(SERVICE_ID + "/backends/b1" + API)
                .then().statusCode(200)
                .body("type", equalTo("Microsoft.ApiManagement/service/backends"))
                .body("properties.url", equalTo("http://backend"))
                .body("properties.protocol", equalTo("http"));
        given().when().get(SERVICE_ID + "/backends" + API)
                .then().statusCode(200)
                .body("value.name", hasItem("b1"));

        given().when().delete(SERVICE_ID + "/backends/b1" + API).then().statusCode(200);
        given().when().get(SERVICE_ID + "/backends/b1" + API).then().statusCode(404);
    }

    // ── Gateway ──────────────────────────────────────────────────────────────────

    @Test
    void gatewayForwardsToTheApiServiceUrl() {
        createService();
        createApi("orders", "orders", true);

        given().contentType("application/json").body("{\"id\":7}")
                .when().post(GATEWAY + "/orders/items/7")
                .then().statusCode(201)
                .contentType(containsString("application/json"))
                .body("method", equalTo("POST"))
                .body("path", equalTo("/items/7"))
                .body("headers.content-type", containsString("application/json"))
                .body("body", equalTo("{\"id\":7}"));
    }

    @Test
    void gatewayMatchesOperationsAndRejectsUnmatchedOnes() {
        createService();
        createApi("orders", "orders", true);
        createOperation("orders", "get-order", "GET", "/items/{id}");

        given().when().get(GATEWAY + "/orders/items/42")
                .then().statusCode(201)
                .body("path", equalTo("/items/42"));
        given().when().post(GATEWAY + "/orders/items/42")
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
        given().when().get(GATEWAY + "/orders/other")
                .then().statusCode(404);
    }

    @Test
    void gatewayPicksTheLongestMatchingApiPath() {
        createService();
        createApi("root", "shop", true);
        createApi("nested", "shop/admin", false);

        given().when().get(GATEWAY + "/shop/cart")
                .then().statusCode(201)
                .body("path", equalTo("/cart"));
        given().when().get(GATEWAY + "/shop/admin/users")
                .then().statusCode(500)
                .body("error.message", containsString("'nested'"));
    }

    @Test
    void gatewayRejectsUnknownServiceAndUnknownApi() {
        given().when().get(GATEWAY + "/orders")
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));

        createService();
        createApi("orders", "orders", true);
        given().when().get(GATEWAY + "/unknown")
                .then().statusCode(404)
                .body("error.message", containsString("No API route matched"));
    }

    @Test
    void apiWithoutBackendFailsInsteadOfAnsweringForTheBackend() {
        createService();
        createApi("bare", "bare", false);

        given().when().get(GATEWAY + "/bare/anything?x=1")
                .then().statusCode(500)
                .contentType(containsString("application/json"))
                .body("error.code", equalTo("BackendNotConfigured"))
                .body("error.message", containsString("'bare'"))
                .body("service", nullValue())
                .body("apiId", nullValue());
    }

    @Test
    void returnResponsePolicyStillAnswersForAnApiWithoutBackend() throws IOException {
        createService();
        createApi("bare", "bare", false);
        putPolicy("/apis/bare", "<return-response><set-status code=\"202\" reason=\"Accepted\" />"
                + "<set-header name=\"X-Mock\" exists-action=\"override\"><value>yes</value></set-header>"
                + "<set-body>{\"mocked\":true}</set-body></return-response>");

        given().when().get(GATEWAY + "/bare/anything")
                .then().statusCode(202)
                .header("X-Mock", equalTo("yes"))
                .contentType(containsString("application/json"))
                .body("mocked", equalTo(true));
    }

    @Test
    void setBackendServiceBaseUrlSuppliesTheBackend() throws IOException {
        createService();
        createApi("bare", "bare", false);
        putPolicy("/apis/bare", "<set-backend-service base-url=\"" + backendUrl() + "/base\" />");

        given().when().get(GATEWAY + "/bare/items")
                .then().statusCode(201)
                .body("path", equalTo("/base/items"));
    }

    @Test
    void setBackendServiceBackendIdResolvesTheBackendResource() throws IOException {
        createService();
        createApi("bare", "bare", false);
        put("/backends/echo", "{\"properties\":{\"url\":\"" + backendUrl() + "/from-backend\"}}");
        putPolicy("/apis/bare", "<set-backend-service backend-id=\"echo\" />");

        given().when().get(GATEWAY + "/bare/items")
                .then().statusCode(201)
                .body("path", equalTo("/from-backend/items"));
    }

    @Test
    void policiesRewriteTheUriAndSetHeadersAndQueryParameters() throws IOException {
        createService();
        createApi("orders", "orders", true);
        createOperation("orders", "get-order", "GET", "/items/{id}");
        put("/namedValues/greeting", "{\"properties\":{\"value\":\"hello\",\"secret\":true}}");

        putPolicy("", "<set-header name=\"X-Service\" exists-action=\"override\"><value>svc</value></set-header>");
        putPolicy("/apis/orders", "<rewrite-uri template=\"/backend/items\" />"
                + "<set-header name=\"X-Named\" exists-action=\"override\"><value>{{greeting}}</value></set-header>"
                + "<set-header name=\"X-Append\" exists-action=\"override\"><value>one</value></set-header>"
                + "<set-header name=\"X-Append\" exists-action=\"append\"><value>two</value></set-header>"
                + "<set-header name=\"X-Skip\" exists-action=\"override\"><value>first</value></set-header>"
                + "<set-header name=\"X-Skip\" exists-action=\"skip\"><value>second</value></set-header>"
                + "<set-header name=\"X-Gone\" exists-action=\"override\"><value>x</value></set-header>"
                + "<set-header name=\"X-Gone\" exists-action=\"delete\" />"
                + "<set-query-parameter name=\"mode\" exists-action=\"override\"><value>compat</value></set-query-parameter>"
                + "<set-query-parameter name=\"caller\" exists-action=\"skip\"><value>policy</value></set-query-parameter>"
                + "<set-query-parameter name=\"drop\" exists-action=\"delete\" />");
        putPolicy("/apis/orders/operations/get-order",
                "<set-header name=\"X-Operation\" exists-action=\"override\"><value>op</value></set-header>");

        given().when().get(GATEWAY + "/orders/items/9?caller=client&drop=yes")
                .then().statusCode(201)
                .body("path", equalTo("/backend/items"))
                .body("headers.x-service", equalTo("svc"))
                .body("headers.x-named", equalTo("hello"))
                .body("headers.x-append", equalTo("one,two"))
                .body("headers.x-skip", equalTo("first"))
                .body("headers.x-gone", nullValue())
                .body("headers.x-operation", equalTo("op"))
                .body("query.mode", equalTo("compat"))
                .body("query.caller", equalTo("client"))
                .body("query.drop", nullValue());
    }

    @Test
    void operationReturnResponseShortCircuitsTheBackend() throws IOException {
        createService();
        createApi("orders", "orders", true);
        createOperation("orders", "get-order", "GET", "/items/{id}");
        putPolicy("/apis/orders/operations/get-order", "<return-response><set-status code=\"429\" />"
                + "<set-body>{\"error\":\"rate-limited\"}</set-body></return-response>");

        given().when().get(GATEWAY + "/orders/items/1")
                .then().statusCode(429)
                .body("error", equalTo("rate-limited"));

        given().when().delete(SERVICE_ID + "/apis/orders/operations/get-order/policies/policy" + API)
                .then().statusCode(200);
        given().when().get(GATEWAY + "/orders/items/1")
                .then().statusCode(201)
                .body("path", equalTo("/items/1"));
    }

    @Test
    void unreachableBackendIsBadGateway() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        createService();
        put("/apis/down", "{\"properties\":{\"path\":\"down\",\"serviceUrl\":\"http://127.0.0.1:" + closedPort + "\"}}");

        given().when().get(GATEWAY + "/down/x")
                .then().statusCode(502)
                .body("error.code", equalTo("BackendUnavailable"));
    }

    @Test
    void productSubscriptionKeyIsEnforcedOnTheGateway() {
        createService();
        createApi("orders", "orders", true);
        put("/products/starter", "{\"properties\":{}}");
        put("/products/starter/apis/orders", "{}");
        put("/subscriptions/s1", "{\"properties\":{\"scope\":\"/products/starter\",\"primaryKey\":\"pk\","
                + "\"secondaryKey\":\"sk\"}}");

        given().when().get(GATEWAY + "/orders/x")
                .then().statusCode(401)
                .body("error.code", equalTo("AuthenticationFailed"));
        given().header("Ocp-Apim-Subscription-Key", "wrong").when().get(GATEWAY + "/orders/x")
                .then().statusCode(401);
        given().header("Ocp-Apim-Subscription-Key", "pk").when().get(GATEWAY + "/orders/x")
                .then().statusCode(201);
        given().when().get(GATEWAY + "/orders/x?subscription-key=sk")
                .then().statusCode(201);

        put("/subscriptions/s1", "{\"properties\":{\"scope\":\"/products/starter\",\"state\":\"suspended\","
                + "\"primaryKey\":\"pk\",\"secondaryKey\":\"sk\"}}");
        given().header("Ocp-Apim-Subscription-Key", "pk").when().get(GATEWAY + "/orders/x")
                .then().statusCode(401);

        given().when().delete(SERVICE_ID + "/products/starter/apis/orders" + API).then().statusCode(200);
        given().when().get(GATEWAY + "/orders/x")
                .then().statusCode(201);
    }
}
