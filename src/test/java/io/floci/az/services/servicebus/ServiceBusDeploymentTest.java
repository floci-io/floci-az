package io.floci.az.services.servicebus;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A {@code Microsoft.Resources/deployments} template that creates a Service Bus namespace, a queue under it and a
 * queue authorization rule, reading them back with {@code reference()} and {@code listKeys()}: the resources reach
 * the Service Bus handler through the deployment engine's internal dispatch, as a Bicep deployment's do.
 */
@QuarkusTest
@DisplayName("Microsoft.ServiceBus in ARM template deployments")
class ServiceBusDeploymentTest {

    private static final String SUB = "17800000-0000-4000-8000-0000000000d1";
    private static final String RG = "/subscriptions/" + SUB + "/resourceGroups/rg-sb-deploy";
    private static final String API = "?api-version=2025-04-01";
    private static final String NAMESPACE = RG + "/providers/Microsoft.ServiceBus/namespaces/sbns-deployed";

    private static final String DEPLOYMENT = """
        {"properties": {"mode": "Incremental",
          "parameters": {"namespaceName": {"value": "sbns-deployed"}},
          "template": {
            "$schema": "https://schema.management.azure.com/schemas/2019-04-01/deploymentTemplate.json#",
            "contentVersion": "1.0.0.0",
            "parameters": {"namespaceName": {"type": "string"},
                           "location": {"type": "string", "defaultValue": "[resourceGroup().location]"}},
            "resources": [
              {"type": "Microsoft.ServiceBus/namespaces/queues/authorizationRules", "apiVersion": "2024-01-01",
               "name": "[concat(parameters('namespaceName'), '/orders/sender')]",
               "dependsOn": ["[resourceId('Microsoft.ServiceBus/namespaces/queues', parameters('namespaceName'), 'orders')]"],
               "properties": {"rights": ["Send"]}},
              {"type": "Microsoft.ServiceBus/namespaces/queues", "apiVersion": "2024-01-01",
               "name": "[concat(parameters('namespaceName'), '/orders')]",
               "dependsOn": ["[resourceId('Microsoft.ServiceBus/namespaces', parameters('namespaceName'))]"],
               "properties": {"maxDeliveryCount": 5, "lockDuration": "PT30S"}},
              {"type": "Microsoft.ServiceBus/namespaces", "apiVersion": "2024-01-01",
               "name": "[parameters('namespaceName')]", "location": "[parameters('location')]",
               "sku": {"name": "Standard", "tier": "Standard"}}],
            "outputs": {
              "endpoint": {"type": "string",
                           "value": "[reference(parameters('namespaceName')).serviceBusEndpoint]"},
              "lockDuration": {"type": "string",
                               "value": "[reference(resourceId('Microsoft.ServiceBus/namespaces/queues', parameters('namespaceName'), 'orders')).lockDuration]"},
              "rootConnection": {"type": "string",
                                 "value": "[listKeys(resourceId('Microsoft.ServiceBus/namespaces/AuthorizationRules', parameters('namespaceName'), 'RootManageSharedAccessKey'), '2024-01-01').primaryConnectionString]"},
              "senderConnection": {"type": "string",
                                   "value": "[listKeys(resourceId('Microsoft.ServiceBus/namespaces/queues/authorizationRules', parameters('namespaceName'), 'orders', 'sender'), '2024-01-01').primaryConnectionString]"}}}}}
        """;

    @BeforeEach
    void group() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{\"location\":\"westeurope\"}")
                .when().put(RG + "?api-version=2021-04-01").then().statusCode(201);
    }

    private static String awaitTerminal(Response accepted) throws InterruptedException {
        String statusUrl = accepted.header("Azure-AsyncOperation");
        assertNotNull(statusUrl, "a PUT answers an Azure-AsyncOperation header");
        String path = URI.create(statusUrl).getRawPath() + "?" + URI.create(statusUrl).getRawQuery();
        for (int attempt = 0; attempt < 200; attempt++) {
            String status = given().when().get(path).path("status");
            if (!status.equals("Accepted") && !status.equals("Running")) {
                return status;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the deployment did not finish: " + statusUrl);
    }

    @Test
    void aTemplateCreatesTheNamespaceQueueAndRuleAndReadsTheirKeys() throws Exception {
        String deployments = RG + "/providers/Microsoft.Resources/deployments";
        Response accepted = given().contentType("application/json").body(DEPLOYMENT)
                .when().put(deployments + "/sb" + API);
        String status = awaitTerminal(accepted);
        assertEquals("Succeeded", status, given().get(deployments + "/sb" + API).asString());

        given().get(deployments + "/sb" + API).then().statusCode(200)
                .body("properties.outputs.endpoint.value",
                        equalTo("https://sbns-deployed.servicebus.windows.net:443/"))
                .body("properties.outputs.lockDuration.value", equalTo("PT30S"))
                .body("properties.outputs.rootConnection.value",
                        containsString("SharedAccessKeyName=RootManageSharedAccessKey"))
                .body("properties.outputs.senderConnection.value", endsWith(";EntityPath=orders"));

        given().get(NAMESPACE + "/queues/orders?api-version=2024-01-01").then().statusCode(200)
                .body("properties.maxDeliveryCount", equalTo(5));
        given().get("/devstoreaccount1-servicebus/sbns-deployed/queues/orders").then().statusCode(200);
    }
}
