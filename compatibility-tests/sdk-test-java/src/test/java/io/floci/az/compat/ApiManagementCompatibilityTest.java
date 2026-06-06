package io.floci.az.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("API Management Compatibility")
class ApiManagementCompatibilityTest {

    private static final String BASE =
            System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String RG = "apim-rg-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String SERVICE = "apim" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private static final String API_ID = "catalog-api";
    private static final String OPENAPI_API_ID = "openapi-api";
    private static final String OPERATION_ID = "get-item";
    private static final String OPENAPI_GET_OPERATION_ID = "getOrder";
    private static final String OPENAPI_CREATE_OPERATION_ID = "createOrder";
    private static final String OPENAPI_UPDATED_OPERATION_ID = "getCustomer";
    private static final String PRODUCT_ID = "starter";
    private static final String SUBSCRIPTION_ID = "starter-sub";
    private static final String SUBSCRIPTION_KEY = "floci-apim-test-key";
    private static final String NAMED_VALUE_ID = "floci-header";
    private static final String SECRET_NAMED_VALUE_ID = "floci-secret-header";
    private static final String BACKEND_ID = "catalog-backend";
    private static final String API_VERSION = "2024-05-01";

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void setup() {
        EmulatorConfig.assumeEmulatorRunning();
    }

    @Test
    @Order(1)
    void createService_returnsGatewayUrl() throws Exception {
        put(resourceGroupUrl(), "{\"location\":\"eastus\"}");

        String body = """
                {
                  "location": "eastus",
                  "sku": {"name": "Developer", "capacity": 1},
                  "properties": {
                    "publisherEmail": "admin@example.com",
                    "publisherName": "floci"
                  }
                }
                """;
        HttpResponse<String> resp = put(serviceUrl(), body);
        assertOk(resp, "create service");

        JsonNode json = mapper.readTree(resp.body());
        assertEquals(SERVICE, json.get("name").asText());
        assertEquals("Microsoft.ApiManagement/service", json.get("type").asText());
        assertEquals("Succeeded", json.get("properties").get("provisioningState").asText());
        assertTrue(json.get("properties").get("gatewayUrl").asText()
                .contains("/devstoreaccount1-apim/" + SERVICE));
    }

    @Test
    @Order(2)
    void getAndListService_containsCreatedService() throws Exception {
        HttpResponse<String> getResp = get(serviceUrl());
        assertEquals(200, getResp.statusCode(), getResp.body());
        assertEquals(SERVICE, mapper.readTree(getResp.body()).get("name").asText());

        HttpResponse<String> listResp = get(collectionUrl("service"));
        assertEquals(200, listResp.statusCode(), listResp.body());
        assertContainsName(mapper.readTree(listResp.body()).get("value"), SERVICE);
    }

    @Test
    @Order(3)
    void createApiAndOperation_returnsArmResources() throws Exception {
        String apiBody = """
                {
                  "properties": {
                    "displayName": "Catalog API",
                    "path": "catalog",
                    "protocols": ["https"]
                  }
                }
                """;
        HttpResponse<String> apiResp = put(apiUrl(), apiBody);
        assertOk(apiResp, "create api");
        JsonNode api = mapper.readTree(apiResp.body());
        assertEquals(API_ID, api.get("name").asText());
        assertEquals("catalog", api.get("properties").get("path").asText());

        String operationBody = """
                {
                  "properties": {
                    "displayName": "Get item",
                    "method": "GET",
                    "urlTemplate": "/items/{id}"
                  }
                }
                """;
        HttpResponse<String> operationResp = put(operationUrl(), operationBody);
        assertOk(operationResp, "create operation");
        JsonNode operation = mapper.readTree(operationResp.body());
        assertEquals(OPERATION_ID, operation.get("name").asText());
        assertEquals("GET", operation.get("properties").get("method").asText());
    }

    @Test
    @Order(4)
    void listApisAndOperations_containsCreatedResources() throws Exception {
        HttpResponse<String> apisResp = get(apisCollectionUrl());
        assertEquals(200, apisResp.statusCode(), apisResp.body());
        assertContainsName(mapper.readTree(apisResp.body()).get("value"), API_ID);

        HttpResponse<String> operationsResp = get(operationsCollectionUrl());
        assertEquals(200, operationsResp.statusCode(), operationsResp.body());
        assertContainsName(mapper.readTree(operationsResp.body()).get("value"), OPERATION_ID);
    }

    @Test
    @Order(5)
    void openApiImport_createsOperationsAndGatewayRoutes() throws Exception {
        HttpResponse<String> apiResp = put(openApiApiUrl(), openApiImportBody());
        assertOk(apiResp, "import openapi api");
        JsonNode api = mapper.readTree(apiResp.body());
        assertEquals(OPENAPI_API_ID, api.get("name").asText());
        assertEquals("openapi", api.get("properties").get("path").asText());
        assertEquals("openapi+json", api.get("properties").get("format").asText());

        JsonNode operations = mapper.readTree(get(openApiOperationsCollectionUrl()).body()).get("value");
        assertContainsName(operations, OPENAPI_GET_OPERATION_ID);
        assertContainsName(operations, OPENAPI_CREATE_OPERATION_ID);

        HttpResponse<String> gatewayResp = get(BASE + "/devstoreaccount1-apim/" + SERVICE + "/openapi/orders/42");
        assertEquals(200, gatewayResp.statusCode(), gatewayResp.body());
        JsonNode json = mapper.readTree(gatewayResp.body());
        assertEquals(OPENAPI_API_ID, json.get("apiId").asText());
        assertEquals(OPENAPI_GET_OPERATION_ID, json.get("operationId").asText());
        assertEquals("/orders/42", json.get("backendPath").asText());

        HttpResponse<String> updatedApiResp = put(openApiApiUrl(), openApiUpdatedImportBody());
        assertOk(updatedApiResp, "reimport openapi api");
        JsonNode updatedOperations = mapper.readTree(get(openApiOperationsCollectionUrl()).body()).get("value");
        assertContainsName(updatedOperations, OPENAPI_UPDATED_OPERATION_ID);
        assertDoesNotContainName(updatedOperations, OPENAPI_GET_OPERATION_ID);
        assertDoesNotContainName(updatedOperations, OPENAPI_CREATE_OPERATION_ID);

        HttpResponse<String> removedRouteResp = get(BASE + "/devstoreaccount1-apim/" + SERVICE + "/openapi/orders/42");
        assertEquals(404, removedRouteResp.statusCode(), removedRouteResp.body());

        HttpResponse<String> updatedGatewayResp = get(BASE + "/devstoreaccount1-apim/" + SERVICE + "/openapi/customers/7");
        assertEquals(200, updatedGatewayResp.statusCode(), updatedGatewayResp.body());
        JsonNode updatedJson = mapper.readTree(updatedGatewayResp.body());
        assertEquals(OPENAPI_API_ID, updatedJson.get("apiId").asText());
        assertEquals(OPENAPI_UPDATED_OPERATION_ID, updatedJson.get("operationId").asText());
        assertEquals("/customers/7", updatedJson.get("backendPath").asText());
    }

    @Test
    @Order(6)
    void gatewayRoute_matchesRegisteredApiAndOperation() throws Exception {
        HttpResponse<String> resp = get(BASE + "/devstoreaccount1-apim/" + SERVICE + "/catalog/items/42");
        assertEquals(200, resp.statusCode(), resp.body());

        JsonNode json = mapper.readTree(resp.body());
        assertEquals(SERVICE, json.get("service").asText());
        assertEquals(API_ID, json.get("apiId").asText());
        assertEquals(OPERATION_ID, json.get("operationId").asText());
        assertEquals("/catalog/items/42", json.get("path").asText());
    }

    @Test
    @Order(7)
    void apiPolicy_appliesSetHeaderAndRewriteUri() throws Exception {
        String policyXml = """
                <policies>
                  <inbound>
                    <base />
                    <rewrite-uri template="/backend/items/42" />
                    <set-header name="X-Floci-Apim" exists-action="override">
                      <value>policy-applied</value>
                    </set-header>
                    <set-header name="X-Floci-Skip" exists-action="override">
                      <value>first</value>
                    </set-header>
                    <set-header name="X-Floci-Skip" exists-action="skip">
                      <value>second</value>
                    </set-header>
                    <set-header name="X-Floci-Append" exists-action="override">
                      <value>one</value>
                    </set-header>
                    <set-header name="X-Floci-Append" exists-action="append">
                      <value>two</value>
                    </set-header>
                    <set-header name="X-Floci-Delete" exists-action="override">
                      <value>remove-me</value>
                    </set-header>
                    <set-header name="X-Floci-Delete" exists-action="delete" />
                    <set-query-parameter name="floci-mode" exists-action="override">
                      <value>compat</value>
                    </set-query-parameter>
                    <set-query-parameter name="caller" exists-action="skip">
                      <value>policy-caller</value>
                    </set-query-parameter>
                    <set-query-parameter name="remove-me" exists-action="delete" />
                  </inbound>
                  <backend>
                    <base />
                  </backend>
                  <outbound>
                    <base />
                  </outbound>
                  <on-error>
                    <base />
                  </on-error>
                </policies>
                """;
        HttpResponse<String> putResp = put(apiPolicyUrl(), policyBody(policyXml));
        assertOk(putResp, "create api policy");
        JsonNode policy = mapper.readTree(putResp.body());
        assertEquals("policy", policy.get("name").asText());
        assertTrue(policy.get("properties").get("value").asText().contains("rewrite-uri"));

        HttpResponse<String> gatewayResp = get(BASE + "/devstoreaccount1-apim/" + SERVICE
                + "/catalog/items/42?caller=test&remove-me=yes");
        assertEquals(200, gatewayResp.statusCode(), gatewayResp.body());

        JsonNode json = mapper.readTree(gatewayResp.body());
        assertEquals("/catalog/items/42", json.get("path").asText());
        assertEquals("/backend/items/42", json.get("backendPath").asText());
        assertEquals("policy-applied", json.get("headers").get("X-Floci-Apim").asText());
        assertEquals("first", json.get("headers").get("X-Floci-Skip").asText());
        assertEquals("one,two", json.get("headers").get("X-Floci-Append").asText());
        assertTrue(json.get("headers").get("X-Floci-Delete") == null);
        assertEquals("test", json.get("queryParams").get("caller").asText());
        assertEquals("compat", json.get("queryParams").get("floci-mode").asText());
        assertTrue(json.get("queryParams").get("remove-me") == null);
    }

    @Test
    @Order(8)
    void operationPolicy_returnResponseShortCircuitsGateway() throws Exception {
        String policyXml = """
                <policies>
                  <inbound>
                    <base />
                    <return-response>
                      <set-status code="429" reason="Too Many Requests" />
                      <set-header name="Retry-After" exists-action="override">
                        <value>30</value>
                      </set-header>
                      <set-body>{"error":"rate-limited"}</set-body>
                    </return-response>
                  </inbound>
                  <backend>
                    <base />
                  </backend>
                  <outbound>
                    <base />
                  </outbound>
                  <on-error>
                    <base />
                  </on-error>
                </policies>
                """;
        assertOk(put(operationPolicyUrl(), policyBody(policyXml)), "create operation policy");

        HttpResponse<String> throttled = get(BASE + "/devstoreaccount1-apim/" + SERVICE + "/catalog/items/42");
        assertEquals(429, throttled.statusCode(), throttled.body());
        assertEquals("30", throttled.headers().firstValue("Retry-After").orElse(""));
        assertTrue(throttled.body().contains("rate-limited"), throttled.body());

        assertOk(delete(operationPolicyUrl()), "delete operation policy");
        HttpResponse<String> restored = get(BASE + "/devstoreaccount1-apim/" + SERVICE + "/catalog/items/42");
        assertEquals(200, restored.statusCode(), restored.body());
    }

    @Test
    @Order(9)
    void namedValuesAndBackends_areUsableFromPolicies() throws Exception {
        String namedValueBody = """
                {
                  "properties": {
                    "displayName": "floci-header",
                    "value": "named-value-applied",
                    "secret": false
                  }
                }
                """;
        HttpResponse<String> namedValueResp = put(namedValueUrl(), namedValueBody);
        assertOk(namedValueResp, "create named value");
        assertEquals(NAMED_VALUE_ID, mapper.readTree(namedValueResp.body()).get("name").asText());

        String secretNamedValueBody = """
                {
                  "properties": {
                    "displayName": "floci-secret-header",
                    "value": "secret-value-applied",
                    "secret": true
                  }
                }
                """;
        HttpResponse<String> secretNamedValueResp = put(secretNamedValueUrl(), secretNamedValueBody);
        assertOk(secretNamedValueResp, "create secret named value");
        JsonNode secretNamedValue = mapper.readTree(secretNamedValueResp.body());
        assertEquals(SECRET_NAMED_VALUE_ID, secretNamedValue.get("name").asText());
        assertTrue(secretNamedValue.get("properties").get("secret").asBoolean());
        assertTrue(secretNamedValue.get("properties").get("value") == null,
                "secret named value should not expose properties.value");

        String backendBody = """
                {
                  "properties": {
                    "title": "Catalog backend",
                    "protocol": "http",
                    "url": "http://127.0.0.1:4577"
                  }
                }
                """;
        HttpResponse<String> backendResp = put(backendUrl(), backendBody);
        assertOk(backendResp, "create backend");
        JsonNode backend = mapper.readTree(backendResp.body());
        assertEquals(BACKEND_ID, backend.get("name").asText());
        assertEquals("http://127.0.0.1:4577", backend.get("properties").get("url").asText());

        assertContainsName(mapper.readTree(get(namedValuesCollectionUrl()).body()).get("value"), NAMED_VALUE_ID);
        JsonNode namedValues = mapper.readTree(get(namedValuesCollectionUrl()).body()).get("value");
        assertContainsName(namedValues, SECRET_NAMED_VALUE_ID);
        assertNamedValueDoesNotExposeValue(namedValues, SECRET_NAMED_VALUE_ID);
        assertTrue(mapper.readTree(get(secretNamedValueUrl()).body()).get("properties").get("value") == null,
                "secret named value GET should not expose properties.value");
        assertContainsName(mapper.readTree(get(backendsCollectionUrl()).body()).get("value"), BACKEND_ID);

        String policyXml = """
                <policies>
                  <inbound>
                    <base />
                    <set-header name="X-Floci-NamedValue" exists-action="override">
                      <value>{{floci-header}}</value>
                    </set-header>
                    <set-header name="X-Floci-SecretNamedValue" exists-action="override">
                      <value>{{floci-secret-header}}</value>
                    </set-header>
                  </inbound>
                  <backend>
                    <base />
                  </backend>
                  <outbound>
                    <base />
                  </outbound>
                  <on-error>
                    <base />
                  </on-error>
                </policies>
                """;
        assertOk(put(apiPolicyUrl(), policyBody(policyXml)), "replace api policy with named value");

        HttpResponse<String> gatewayResp = get(BASE + "/devstoreaccount1-apim/" + SERVICE + "/catalog/items/42");
        assertEquals(200, gatewayResp.statusCode(), gatewayResp.body());
        JsonNode json = mapper.readTree(gatewayResp.body());
        assertEquals("named-value-applied", json.get("headers").get("X-Floci-NamedValue").asText());
        assertEquals("secret-value-applied", json.get("headers").get("X-Floci-SecretNamedValue").asText());

        String backendPolicyXml = """
                <policies>
                  <inbound>
                    <base />
                    <set-backend-service backend-id="catalog-backend" />
                    <rewrite-uri template="/health" />
                  </inbound>
                  <backend>
                    <base />
                  </backend>
                  <outbound>
                    <base />
                  </outbound>
                  <on-error>
                    <base />
                  </on-error>
                </policies>
                """;
        assertOk(put(apiPolicyUrl(), policyBody(backendPolicyXml)), "replace api policy with backend-id");

        HttpResponse<String> backendGatewayResp = get(BASE + "/devstoreaccount1-apim/" + SERVICE + "/catalog/items/42");
        assertEquals(200, backendGatewayResp.statusCode(), backendGatewayResp.body());
        assertTrue(backendGatewayResp.body().contains("\"status\""), backendGatewayResp.body());

        assertOk(put(apiPolicyUrl(), policyBody(policyXml)), "restore named value api policy");
    }

    @Test
    @Order(10)
    void productSubscription_enforcesSubscriptionKeyOnGateway() throws Exception {
        String productBody = """
                {
                  "properties": {
                    "displayName": "Starter",
                    "subscriptionRequired": true,
                    "approvalRequired": false,
                    "state": "published"
                  }
                }
                """;
        HttpResponse<String> productResp = put(productUrl(), productBody);
        assertOk(productResp, "create product");
        assertEquals(PRODUCT_ID, mapper.readTree(productResp.body()).get("name").asText());

        assertOk(put(productApiUrl(), "{}"), "link product api");

        HttpResponse<String> subscriptionResp = put(subscriptionUrl(), subscriptionBody("active"));
        assertOk(subscriptionResp, "create subscription");

        assertContainsName(mapper.readTree(get(productsCollectionUrl()).body()).get("value"), PRODUCT_ID);
        assertContainsName(mapper.readTree(get(subscriptionsCollectionUrl()).body()).get("value"), SUBSCRIPTION_ID);

        String gatewayUrl = BASE + "/devstoreaccount1-apim/" + SERVICE + "/catalog/items/42";
        HttpResponse<String> withoutKey = get(gatewayUrl);
        assertEquals(401, withoutKey.statusCode(), withoutKey.body());

        HttpResponse<String> withKey = getWithHeader(gatewayUrl, "Ocp-Apim-Subscription-Key", SUBSCRIPTION_KEY);
        assertEquals(200, withKey.statusCode(), withKey.body());
        JsonNode json = mapper.readTree(withKey.body());
        assertEquals(API_ID, json.get("apiId").asText());
        assertEquals("/items/42", json.get("backendPath").asText());

        HttpResponse<String> withQueryKey = get(gatewayUrl + "?subscription-key=" + SUBSCRIPTION_KEY);
        assertEquals(200, withQueryKey.statusCode(), withQueryKey.body());

        assertOk(put(subscriptionUrl(), subscriptionBody("inactive")), "deactivate subscription");
        HttpResponse<String> inactiveSubscription = getWithHeader(gatewayUrl, "Ocp-Apim-Subscription-Key", SUBSCRIPTION_KEY);
        assertEquals(401, inactiveSubscription.statusCode(), inactiveSubscription.body());

        assertOk(put(subscriptionUrl(), subscriptionBody("active")), "reactivate subscription");
        assertEquals(200, getWithHeader(gatewayUrl, "Ocp-Apim-Subscription-Key", SUBSCRIPTION_KEY).statusCode());

        assertOk(delete(productApiUrl()), "unlink product api");
        HttpResponse<String> afterUnlinkWithoutKey = get(gatewayUrl);
        assertEquals(200, afterUnlinkWithoutKey.statusCode(), afterUnlinkWithoutKey.body());
        assertOk(put(productApiUrl(), "{}"), "relink product api");
    }

    @Test
    @Order(11)
    void deleteResources_removesService() throws Exception {
        assertOk(delete(subscriptionUrl()), "delete subscription");
        assertOk(delete(productApiUrl()), "delete product api");
        assertOk(delete(productUrl()), "delete product");
        assertOk(delete(backendUrl()), "delete backend");
        assertOk(delete(secretNamedValueUrl()), "delete secret named value");
        assertOk(delete(namedValueUrl()), "delete named value");
        assertOk(delete(operationUrl()), "delete operation");
        assertOk(delete(openApiApiUrl()), "delete openapi api");
        assertOk(delete(apiUrl()), "delete api");
        assertOk(delete(serviceUrl()), "delete service");

        HttpResponse<String> resp = get(serviceUrl());
        assertEquals(404, resp.statusCode(), resp.body());
    }

    private static String resourceGroupUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "?api-version=2021-04-01";
    }

    private static String serviceUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "?api-version=" + API_VERSION;
    }

    private static String apiUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/apis/" + API_ID + "?api-version=" + API_VERSION;
    }

    private static String openApiApiUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/apis/" + OPENAPI_API_ID + "?api-version=" + API_VERSION;
    }

    private static String operationUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/apis/" + API_ID + "/operations/" + OPERATION_ID
                + "?api-version=" + API_VERSION;
    }

    private static String apisCollectionUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/apis?api-version=" + API_VERSION;
    }

    private static String operationsCollectionUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/apis/" + API_ID + "/operations?api-version=" + API_VERSION;
    }

    private static String openApiOperationsCollectionUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/apis/" + OPENAPI_API_ID + "/operations?api-version=" + API_VERSION;
    }

    private static String apiPolicyUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/apis/" + API_ID + "/policies/policy?api-version=" + API_VERSION;
    }

    private static String operationPolicyUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/apis/" + API_ID + "/operations/" + OPERATION_ID
                + "/policies/policy?api-version=" + API_VERSION;
    }

    private static String namedValuesCollectionUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/namedValues?api-version=" + API_VERSION;
    }

    private static String namedValueUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/namedValues/" + NAMED_VALUE_ID + "?api-version=" + API_VERSION;
    }

    private static String secretNamedValueUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/namedValues/" + SECRET_NAMED_VALUE_ID + "?api-version=" + API_VERSION;
    }

    private static String backendsCollectionUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/backends?api-version=" + API_VERSION;
    }

    private static String backendUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/backends/" + BACKEND_ID + "?api-version=" + API_VERSION;
    }

    private static String productsCollectionUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/products?api-version=" + API_VERSION;
    }

    private static String productUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/products/" + PRODUCT_ID + "?api-version=" + API_VERSION;
    }

    private static String productApiUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/products/" + PRODUCT_ID + "/apis/" + API_ID + "?api-version=" + API_VERSION;
    }

    private static String subscriptionsCollectionUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/subscriptions?api-version=" + API_VERSION;
    }

    private static String subscriptionUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/subscriptions/" + SUBSCRIPTION_ID + "?api-version=" + API_VERSION;
    }

    private static String collectionUrl(String resourceType) {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/" + resourceType
                + "?api-version=" + API_VERSION;
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getWithHeader(String url, String name, String value) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .GET()
                        .header(name, value)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(String url, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .PUT(HttpRequest.BodyPublishers.ofString(json))
                        .header("Content-Type", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void assertOk(HttpResponse<String> resp, String operation) {
        assertTrue(resp.statusCode() >= 200 && resp.statusCode() < 300,
                operation + " failed: " + resp.statusCode() + " " + resp.body());
    }

    private static void assertContainsName(JsonNode array, String name) {
        assertNotNull(array);
        assertTrue(array.isArray(), "Expected array but got " + array);
        for (JsonNode item : array) {
            if (name.equals(item.get("name").asText())) {
                return;
            }
        }
        throw new AssertionError("Expected list to contain name " + name + ": " + array);
    }

    private static void assertDoesNotContainName(JsonNode array, String name) {
        assertNotNull(array);
        assertTrue(array.isArray(), "Expected array but got " + array);
        for (JsonNode item : array) {
            if (name.equals(item.get("name").asText())) {
                throw new AssertionError("Expected list not to contain name " + name + ": " + array);
            }
        }
    }

    private static void assertNamedValueDoesNotExposeValue(JsonNode array, String name) {
        assertNotNull(array);
        for (JsonNode item : array) {
            if (name.equals(item.get("name").asText())) {
                assertTrue(item.get("properties").get("value") == null,
                        "secret named value should not expose properties.value in list");
                return;
            }
        }
        throw new AssertionError("Expected list to contain named value " + name + ": " + array);
    }

    private static String policyBody(String xml) throws Exception {
        return mapper.writeValueAsString(java.util.Map.of(
                "properties", java.util.Map.of(
                        "format", "rawxml",
                        "value", xml
                )
        ));
    }

    private static String openApiImportBody() throws Exception {
        String openApi = mapper.writeValueAsString(java.util.Map.of(
                "openapi", "3.0.1",
                "info", java.util.Map.of(
                        "title", "Orders API",
                        "version", "1.0"
                ),
                "paths", java.util.Map.of(
                        "/orders/{orderId}", java.util.Map.of(
                                "get", java.util.Map.of(
                                        "operationId", OPENAPI_GET_OPERATION_ID,
                                        "summary", "Get order"
                                )
                        ),
                        "/orders", java.util.Map.of(
                                "post", java.util.Map.of(
                                        "operationId", OPENAPI_CREATE_OPERATION_ID,
                                        "summary", "Create order"
                                )
                        )
                )
        ));
        return mapper.writeValueAsString(java.util.Map.of(
                "properties", java.util.Map.of(
                        "displayName", "Orders API",
                        "path", "openapi",
                        "protocols", java.util.List.of("https"),
                        "format", "openapi+json",
                        "value", openApi
                )
        ));
    }

    private static String openApiUpdatedImportBody() throws Exception {
        String openApi = mapper.writeValueAsString(java.util.Map.of(
                "openapi", "3.0.1",
                "info", java.util.Map.of(
                        "title", "Customers API",
                        "version", "2.0"
                ),
                "paths", java.util.Map.of(
                        "/customers/{customerId}", java.util.Map.of(
                                "get", java.util.Map.of(
                                        "operationId", OPENAPI_UPDATED_OPERATION_ID,
                                        "summary", "Get customer"
                                )
                        )
                )
        ));
        return mapper.writeValueAsString(java.util.Map.of(
                "properties", java.util.Map.of(
                        "displayName", "Customers API",
                        "path", "openapi",
                        "protocols", java.util.List.of("https"),
                        "format", "openapi+json",
                        "value", openApi
                )
        ));
    }

    private static String subscriptionBody(String state) {
        return """
                {
                  "properties": {
                    "displayName": "Starter subscription",
                    "scope": "/products/%s",
                    "state": "%s",
                    "primaryKey": "%s",
                    "secondaryKey": "secondary-%s"
                  }
                }
                """.formatted(PRODUCT_ID, state, SUBSCRIPTION_KEY, SUBSCRIPTION_KEY);
    }
}
