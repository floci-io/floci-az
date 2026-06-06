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
    private static final String OPERATION_ID = "get-item";
    private static final String PRODUCT_ID = "starter";
    private static final String SUBSCRIPTION_ID = "starter-sub";
    private static final String SUBSCRIPTION_KEY = "floci-apim-test-key";
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
    @Order(6)
    void apiPolicy_appliesSetHeaderAndRewriteUri() throws Exception {
        String policyXml = """
                <policies>
                  <inbound>
                    <base />
                    <rewrite-uri template="/backend/items/42" />
                    <set-header name="X-Floci-Apim" exists-action="override">
                      <value>policy-applied</value>
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
        HttpResponse<String> putResp = put(apiPolicyUrl(), policyBody(policyXml));
        assertOk(putResp, "create api policy");
        JsonNode policy = mapper.readTree(putResp.body());
        assertEquals("policy", policy.get("name").asText());
        assertTrue(policy.get("properties").get("value").asText().contains("rewrite-uri"));

        HttpResponse<String> gatewayResp = get(BASE + "/devstoreaccount1-apim/" + SERVICE + "/catalog/items/42");
        assertEquals(200, gatewayResp.statusCode(), gatewayResp.body());

        JsonNode json = mapper.readTree(gatewayResp.body());
        assertEquals("/catalog/items/42", json.get("path").asText());
        assertEquals("/backend/items/42", json.get("backendPath").asText());
        assertEquals("policy-applied", json.get("headers").get("X-Floci-Apim").asText());
    }

    @Test
    @Order(7)
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

        String subscriptionBody = """
                {
                  "properties": {
                    "displayName": "Starter subscription",
                    "scope": "/products/%s",
                    "state": "active",
                    "primaryKey": "%s",
                    "secondaryKey": "secondary-%s"
                  }
                }
                """.formatted(PRODUCT_ID, SUBSCRIPTION_KEY, SUBSCRIPTION_KEY);
        HttpResponse<String> subscriptionResp = put(subscriptionUrl(), subscriptionBody);
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
        assertEquals("/backend/items/42", json.get("backendPath").asText());
    }

    @Test
    @Order(8)
    void deleteResources_removesService() throws Exception {
        assertOk(delete(subscriptionUrl()), "delete subscription");
        assertOk(delete(productApiUrl()), "delete product api");
        assertOk(delete(productUrl()), "delete product");
        assertOk(delete(operationUrl()), "delete operation");
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

    private static String apiPolicyUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG
                + "/providers/Microsoft.ApiManagement/service/" + SERVICE
                + "/apis/" + API_ID + "/policies/policy?api-version=" + API_VERSION;
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

    private static String policyBody(String xml) throws Exception {
        return mapper.writeValueAsString(java.util.Map.of(
                "properties", java.util.Map.of(
                        "format", "rawxml",
                        "value", xml
                )
        ));
    }
}
