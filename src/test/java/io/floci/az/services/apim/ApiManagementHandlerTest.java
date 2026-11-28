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
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
        exchange.getResponseHeaders().add("X-Backend", "echo");
        exchange.getResponseHeaders().add("X-Internal", "secret");
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

    /** A deleted service keeps its name until it is purged, so a test that creates it again purges it first. */
    private static void purgeService() {
        given().when().delete("/subscriptions/" + SUB + "/providers/Microsoft.ApiManagement/locations/westeurope"
                + "/deletedservices/" + SERVICE + API).then().statusCode(200);
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
        putPolicy(scope, inbound, "");
    }

    private static void putPolicy(String scope, String inbound, String outbound) throws IOException {
        String xml = "<policies><inbound><base />" + inbound + "</inbound><backend><base /></backend>"
                + "<outbound><base />" + outbound + "</outbound><on-error><base /></on-error></policies>";
        String body = MAPPER.writeValueAsString(Map.of("properties", Map.of("format", "rawxml", "value", xml)));
        given().contentType("application/json").body(body)
                .when().put(SERVICE_ID + scope + "/policies/policy" + API)
                .then().statusCode(anyOf(is(200), is(201)));
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
    void aSystemAssignedIdentityGetsAPrincipalThatARePutKeeps() {
        String body = "{\"location\":\"westeurope\",\"identity\":{\"type\":\"SystemAssigned\"},"
                + "\"tags\":{\"env\":\"dev\"},\"properties\":{\"publisherEmail\":\"a@b.c\",\"publisherName\":\"u\"}}";
        String principal = given().contentType("application/json").body(body)
                .when().put(SERVICE_ID + API)
                .then().statusCode(200)
                .body("identity.type", equalTo("SystemAssigned"))
                .body("identity.principalId", notNullValue())
                .body("identity.tenantId", notNullValue())
                .body("identity", not(hasKey("userAssignedIdentities")))
                .body("tags.env", equalTo("dev"))
                .extract().path("identity.principalId");

        given().contentType("application/json").body(body)
                .when().put(SERVICE_ID + API)
                .then().statusCode(200)
                .body("identity.principalId", equalTo(principal));

        given().when().get(SERVICE_ID + API)
                .then().statusCode(200)
                .body("identity.type", equalTo("SystemAssigned"))
                .body("identity.principalId", equalTo(principal))
                .body("tags.env", equalTo("dev"));
    }

    @Test
    void aUserAssignedIdentityAnswersTheIdsOfTheManagedIdentity() {
        String uami = "/subscriptions/" + SUB + "/resourceGroups/" + RG
                + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/apim-uami";
        Map<String, String> ids = given().contentType("application/json").body("{\"location\":\"westeurope\"}")
                .when().put(uami + "?api-version=2024-11-30")
                .then().statusCode(anyOf(is(200), is(201)))
                .extract().path("properties");

        given().contentType("application/json")
                .body("{\"location\":\"westeurope\",\"identity\":{\"type\":\"SystemAssigned, UserAssigned\","
                        + "\"userAssignedIdentities\":{\"" + uami + "\":{}}},"
                        + "\"properties\":{\"publisherEmail\":\"a@b.c\",\"publisherName\":\"u\"}}")
                .when().put(SERVICE_ID + API)
                .then().statusCode(200)
                .body("identity.type", equalTo("SystemAssigned, UserAssigned"))
                .body("identity.principalId", notNullValue())
                .body("identity.userAssignedIdentities.'" + uami + "'.principalId", equalTo(ids.get("principalId")))
                .body("identity.userAssignedIdentities.'" + uami + "'.clientId", equalTo(ids.get("clientId")));
    }

    @Test
    void anIdentityOfNoneIsNoIdentityAndAMisspeltTypeIsRefused() {
        given().contentType("application/json")
                .body("{\"location\":\"westeurope\",\"identity\":{\"type\":\"None\"}}")
                .when().put(SERVICE_ID + API)
                .then().statusCode(200)
                .body("$", not(hasKey("identity")));

        given().contentType("application/json")
                .body("{\"location\":\"westeurope\",\"identity\":{\"type\":\"SystemAssignd\"}}")
                .when().put(SERVICE_ID + API)
                .then().statusCode(400)
                .body("error.code", equalTo("ValidationError"));
    }

    @Test
    void aPatchMergesTagsAndIdentityOntoTheStoredService() {
        given().contentType("application/json")
                .body("{\"location\":\"westeurope\",\"identity\":{\"type\":\"SystemAssigned\"},"
                        + "\"tags\":{\"env\":\"dev\"},\"sku\":{\"name\":\"Developer\",\"capacity\":1},"
                        + "\"properties\":{\"publisherEmail\":\"a@b.c\",\"publisherName\":\"unit\"}}")
                .when().put(SERVICE_ID + API)
                .then().statusCode(200);
        String principal = given().when().get(SERVICE_ID + API).then().extract().path("identity.principalId");
        String etag = given().when().get(SERVICE_ID + API).then().extract().path("etag");

        // azurerm sends only what changed, always with a properties object.
        given().contentType("application/json")
                .body("{\"tags\":{\"env\":\"prod\",\"team\":\"api\"},\"properties\":{}}")
                .when().patch(SERVICE_ID + API)
                .then().statusCode(200)
                .body("tags.env", equalTo("prod"))
                .body("tags.team", equalTo("api"))
                .body("identity.principalId", equalTo(principal))
                .body("properties.publisherName", equalTo("unit"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("sku.name", equalTo("Developer"))
                .body("location", equalTo("westeurope"))
                .body("etag", not(equalTo(etag)));

        given().contentType("application/json")
                .body("{\"identity\":{\"type\":\"None\"},\"properties\":{\"publisherName\":\"renamed\"}}")
                .when().patch(SERVICE_ID + API)
                .then().statusCode(200);

        given().when().get(SERVICE_ID + API)
                .then().statusCode(200)
                .body("$", not(hasKey("identity")))
                .body("tags.env", equalTo("prod"))
                .body("properties.publisherName", equalTo("renamed"))
                .body("properties.publisherEmail", equalTo("a@b.c"));
    }

    @Test
    void aPatchOfAMissingServiceIsNotFound() {
        given().contentType("application/json").body("{\"tags\":{}}")
                .when().patch(SERVICE_ID + API)
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void deletingAndPurgingTheServiceRemovesItsChildren() {
        createService();
        createApi("orders", "orders", true);
        put("/products/starter", "{\"properties\":{}}");

        given().when().delete(SERVICE_ID + API).then().statusCode(200);
        purgeService();
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

    @Test
    void deletingAndPurgingTheServiceRemovesEveryChildSoARecreatedServiceStartsEmpty() throws IOException {
        createService();
        createApi("orders", "orders", false);
        createOperation("orders", "get-order", "GET", "/items/{id}");
        putPolicy("", "<set-header name=\"X-Service\" exists-action=\"override\"><value>svc</value></set-header>");
        putPolicy("/apis/orders", "<set-header name=\"X-Api\" exists-action=\"override\"><value>api</value></set-header>");
        put("/products/Starter", "{\"properties\":{}}");
        put("/products/Starter/apis/orders", "{}");
        put("/subscriptions/Sub1", "{\"properties\":{}}");
        put("/namedValues/Greeting", "{\"properties\":{\"value\":\"hello\"}}");
        put("/backends/Echo", "{\"properties\":{\"url\":\"http://backend\"}}");

        String sibling = SERVICE_ID + "x";
        given().contentType("application/json")
                .body("{\"location\":\"westeurope\",\"properties\":{\"publisherEmail\":\"a@b.c\",\"publisherName\":\"unit\"}}")
                .when().put(sibling + API).then().statusCode(200);
        String xml = "<policies><inbound><base /></inbound><backend><base /></backend>"
                + "<outbound><base /></outbound><on-error><base /></on-error></policies>";
        given().contentType("application/json")
                .body(MAPPER.writeValueAsString(Map.of("properties", Map.of("format", "rawxml", "value", xml))))
                .when().put(sibling + "/policies/policy" + API).then().statusCode(201);

        given().when().delete("/subscriptions/" + SUB + "/resourceGroups/" + RG.toUpperCase(Locale.ROOT)
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE.toUpperCase(Locale.ROOT) + API)
                .then().statusCode(200);
        purgeService();
        createService();

        for (String kind : List.of("/apis", "/products", "/subscriptions", "/namedValues", "/backends")) {
            given().when().get(SERVICE_ID + kind + API).then().statusCode(200).body("value", hasSize(0));
        }
        for (String child : List.of("/apis/orders", "/apis/orders/operations/get-order", "/policies/policy",
                "/apis/orders/policies/policy", "/products/Starter", "/products/Starter/apis/orders",
                "/subscriptions/Sub1", "/namedValues/Greeting", "/backends/Echo")) {
            given().when().get(SERVICE_ID + child + API).then().statusCode(404);
        }
        given().when().get(sibling + "/policies/policy" + API).then().statusCode(200);
    }

    @Test
    void deletingAnApiKeepsThePoliciesOfAnApiWhoseIdExtendsIt() throws IOException {
        createService();
        createApi("orders", "orders", false);
        createApi("orders2", "orders2", false);
        putPolicy("/apis/orders", "<set-header name=\"X-Api\" exists-action=\"override\"><value>a</value></set-header>");
        putPolicy("/apis/orders2", "<set-header name=\"X-Api\" exists-action=\"override\"><value>b</value></set-header>");

        given().when().delete(SERVICE_ID + "/apis/orders" + API).then().statusCode(lessThan(300));

        given().when().get(SERVICE_ID + "/apis/orders2/policies/policy" + API).then().statusCode(200);
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
        putPolicy("/apis/bare", "<set-header name=\"X-Request-Only\" exists-action=\"override\"><value>req</value>"
                + "</set-header><return-response><set-status code=\"202\" reason=\"Accepted\" />"
                + "<set-header name=\"X-Mock\" exists-action=\"override\"><value>yes</value></set-header>"
                + "<set-body>{\"mocked\":true}</set-body></return-response>");

        given().when().get(GATEWAY + "/bare/anything")
                .then().statusCode(202)
                .header("X-Mock", equalTo("yes"))
                .header("X-Request-Only", nullValue())
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
    void referencesFindABackendAndANamedValueRePutUnderAnotherCasing() throws IOException {
        createService();
        createApi("bare", "bare", false);
        put("/backends/Echo", "{\"properties\":{\"url\":\"" + backendUrl() + "/first\"}}");
        put("/namedValues/Greeting", "{\"properties\":{\"value\":\"hello\"}}");
        putPolicy("/apis/bare", "<set-backend-service backend-id=\"Echo\" />"
                + "<set-header name=\"X-Named\" exists-action=\"override\"><value>{{Greeting}}</value></set-header>");

        put("/backends/echo", "{\"properties\":{\"url\":\"" + backendUrl() + "/second\"}}");
        put("/namedValues/GREETING", "{\"properties\":{\"value\":\"hi\"}}");

        given().when().get(SERVICE_ID + "/backends" + API).then().statusCode(200).body("value", hasSize(1));
        given().when().get(GATEWAY + "/bare/items")
                .then().statusCode(201)
                .body("path", equalTo("/second/items"))
                .body("headers.x-named", equalTo("hi"));
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

    @Test
    void aPolicyAnswers201OnCreateAnd200OnUpdateWithAFreshETag() throws IOException {
        createService();
        String body = MAPPER.writeValueAsString(Map.of("properties", Map.of("format", "rawxml",
                "value", "<policies><inbound><base /></inbound></policies>")));
        String path = SERVICE_ID + "/apis/orders/policies/policy" + API;
        createApi("orders", "orders", false);

        String created = given().contentType("application/json").body(body).when().put(path)
                .then().statusCode(201).header("ETag", notNullValue()).extract().header("ETag");
        given().when().get(path).then().statusCode(200).header("ETag", equalTo(created));
        given().when().head(path).then().statusCode(200).header("ETag", equalTo(created));

        String updated = given().contentType("application/json").body(body).when().put(path)
                .then().statusCode(200).extract().header("ETag");
        assertNotEquals(created, updated);
        given().when().get(path).then().statusCode(200).header("ETag", equalTo(updated));

        given().when().delete(path).then().statusCode(200);
        given().when().head(path).then().statusCode(404);
    }

    @Test
    void productPoliciesAreServedAndGoWithTheirProduct() throws IOException {
        createService();
        put("/products/starter", "{\"properties\":{}}");
        putPolicy("/products/starter", "<set-header name=\"X-Product\" exists-action=\"override\">"
                + "<value>starter</value></set-header>");

        given().when().get(SERVICE_ID + "/products/starter/policies/policy" + API)
                .then().statusCode(200)
                .body("id", equalTo(SERVICE_ID + "/products/starter/policies/policy"))
                .body("type", equalTo("Microsoft.ApiManagement/service/products/policies"))
                .body("properties.value", containsString("X-Product"));
        given().when().get(SERVICE_ID + "/products/starter/policies" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("policy"));

        given().contentType("application/json").body("{\"properties\":{\"value\":\"<policies/>\"}}")
                .when().put(SERVICE_ID + "/products/missing/policies/policy" + API)
                .then().statusCode(404);

        given().when().delete(SERVICE_ID + "/products/starter/policies/policy" + API).then().statusCode(200);
        given().when().get(SERVICE_ID + "/products/starter/policies/policy" + API).then().statusCode(404);

        putPolicy("/products/starter", "<set-header name=\"X-Product\" exists-action=\"override\">"
                + "<value>again</value></set-header>");
        given().when().delete(SERVICE_ID + "/products/starter" + API).then().statusCode(200);
        put("/products/starter", "{\"properties\":{}}");
        given().when().get(SERVICE_ID + "/products/starter/policies" + API)
                .then().statusCode(200)
                .body("value", hasSize(0));
    }

    @Test
    void theGatewayAppliesThePolicyOfTheProductARequestComesInThrough() throws IOException {
        createService();
        createApi("orders", "orders", true);
        put("/products/starter", "{\"properties\":{}}");
        put("/products/starter/apis/orders", "{}");
        put("/products/premium", "{\"properties\":{}}");
        put("/products/premium/apis/orders", "{}");
        put("/subscriptions/s1", "{\"properties\":{\"scope\":\"/products/starter\",\"primaryKey\":\"starter-key\","
                + "\"secondaryKey\":\"starter-key2\"}}");
        put("/subscriptions/s2", "{\"properties\":{\"scope\":\"/products/premium\",\"primaryKey\":\"premium-key\","
                + "\"secondaryKey\":\"premium-key2\"}}");
        putPolicy("", "<set-header name=\"X-Layers\" exists-action=\"override\"><value>service</value></set-header>");
        putPolicy("/products/starter", "<set-header name=\"X-Product\" exists-action=\"override\">"
                + "<value>starter</value></set-header>"
                + "<set-header name=\"X-Layers\" exists-action=\"override\"><value>product</value></set-header>");
        putPolicy("/apis/orders", "<set-header name=\"X-Api\" exists-action=\"override\"><value>orders</value>"
                + "</set-header>");

        given().header("Ocp-Apim-Subscription-Key", "starter-key").when().get(GATEWAY + "/orders/x")
                .then().statusCode(201)
                .body("headers.x-product", equalTo("starter"))
                .body("headers.x-layers", equalTo("product"))
                .body("headers.x-api", equalTo("orders"));
        given().header("Ocp-Apim-Subscription-Key", "premium-key").when().get(GATEWAY + "/orders/x")
                .then().statusCode(201)
                .body("headers.x-product", nullValue())
                .body("headers.x-layers", equalTo("service"))
                .body("headers.x-api", equalTo("orders"));
    }

    @Test
    void anOutboundSetHeaderReachesTheCallerAndAnInboundOneTheBackend() throws IOException {
        createService();
        createApi("orders", "orders", true);
        putPolicy("/apis/orders",
                "<set-header name=\"X-In\" exists-action=\"override\"><value>request</value></set-header>",
                "<set-header name=\"X-Out\" exists-action=\"override\"><value>response</value></set-header>");

        given().when().get(GATEWAY + "/orders/x")
                .then().statusCode(201)
                .header("X-Out", equalTo("response"))
                .header("X-In", nullValue())
                .header("X-Backend", equalTo("echo"))
                .body("headers.x-in", equalTo("request"))
                .body("headers.x-out", nullValue());
    }

    @Test
    void anOutboundDeleteRemovesABackendResponseHeader() throws IOException {
        createService();
        createApi("orders", "orders", true);
        putPolicy("/apis/orders", "",
                "<set-header name=\"x-internal\" exists-action=\"delete\" />"
                        + "<set-header name=\"X-Backend\" exists-action=\"skip\"><value>policy</value></set-header>");

        given().when().get(GATEWAY + "/orders/x")
                .then().statusCode(201)
                .header("X-Internal", nullValue())
                .header("X-Backend", equalTo("echo"))
                .contentType(containsString("application/json"));
    }

    @Test
    void anOutboundSetHeaderSendsEveryValue() throws IOException {
        createService();
        createApi("orders", "orders", true);
        putPolicy("/apis/orders", "",
                "<set-header name=\"Set-Cookie\" exists-action=\"override\">"
                        + "<value>a=1</value><value>b=2</value></set-header>");

        List<String> cookies = given().when().get(GATEWAY + "/orders/x")
                .then().statusCode(201)
                .extract().headers().getValues("Set-Cookie");
        assertEquals(List.of("a=1", "b=2"), cookies);
    }

    @Test
    void outboundPoliciesComposeOutermostScopeFirst() throws IOException {
        createService();
        createApi("orders", "orders", true);
        createOperation("orders", "get-order", "GET", "/items/{id}");
        put("/products/starter", "{\"properties\":{}}");
        put("/products/starter/apis/orders", "{}");
        put("/subscriptions/s1", "{\"properties\":{\"scope\":\"/products/starter\",\"primaryKey\":\"pk\","
                + "\"secondaryKey\":\"sk\"}}");
        putPolicy("", "", outboundLayer("service"));
        putPolicy("/products/starter", "", outboundLayer("product"));
        putPolicy("/apis/orders", "", outboundLayer("api"));
        putPolicy("/apis/orders/operations/get-order", "",
                "<set-header name=\"X-Trail\" exists-action=\"append\"><value>operation</value></set-header>");

        List<String> trail = given().header("Ocp-Apim-Subscription-Key", "pk").when().get(GATEWAY + "/orders/items/1")
                .then().statusCode(201)
                .header("X-Scope", equalTo("api"))
                .body("headers.x-trail", nullValue())
                .extract().headers().getValues("X-Trail");
        // Each append adds a header value; joined, they read in scope order whether sent as lines or one list.
        assertEquals("service,product,api,operation", String.join(",", trail).replace(" ", ""));
    }

    private static String outboundLayer(String scope) {
        return "<set-header name=\"X-Scope\" exists-action=\"override\"><value>" + scope + "</value></set-header>"
                + "<set-header name=\"X-Trail\" exists-action=\"append\"><value>" + scope + "</value></set-header>";
    }

    @Test
    void serviceAndChildTypeSegmentsMatchInAnyCase() {
        String lowered = "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.ApiManagement/SERVICE/"
                + SERVICE;
        given().contentType("application/json")
                .body("{\"location\":\"westeurope\",\"properties\":{\"publisherEmail\":\"a@b.c\","
                        + "\"publisherName\":\"unit\"}}")
                .when().put(lowered + API)
                .then().statusCode(200)
                .body("name", equalTo(SERVICE));
        given().contentType("application/json")
                .body("{\"properties\":{\"displayName\":\"lower\",\"path\":\"lower\"}}")
                .when().put(lowered + "/APIS/lower-api" + API)
                .then().statusCode(200);

        given().when().get(SERVICE_ID + API).then().statusCode(200).body("name", equalTo(SERVICE));
        given().when().get(SERVICE_ID + "/apis/lower-api" + API).then().statusCode(200)
                .body("name", equalTo("lower-api"));
        given().when().get("/subscriptions/" + SUB + "/resourceGroups/" + RG
                        + "/providers/Microsoft.ApiManagement/service" + API)
                .then().statusCode(200)
                .body("value", hasSize(1));
    }
}
