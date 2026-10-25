package io.floci.az.services.deployments;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Microsoft.Resources/deployments} end to end: a template whose resources reach the real storage and network
 * handlers through internal dispatch, with outputs that read them back live.
 */
@QuarkusTest
@DisplayName("Deployments: resource-group template deployments")
class DeploymentsHandlerTest {

    private static final String SUB = "7a1d0000-0000-4000-8000-0000000000f1";
    private static final String RG = "/subscriptions/" + SUB + "/resourceGroups/rg-deploy";
    private static final String DEPLOYMENTS = RG + "/providers/Microsoft.Resources/deployments";
    private static final String API = "?api-version=2025-04-01";
    private static final String ACCOUNT = RG + "/providers/Microsoft.Storage/storageAccounts/deploysa0001";
    private static final String SUBNET = RG + "/providers/Microsoft.Network/virtualNetworks/vnet1/subnets/default";

    /** A storage account with a container, and a virtual network with a subnet; the az CLI may send comments. */
    private static final String DEPLOYMENT = """
        {"properties": {"mode": "Incremental",
          "parameters": {"accountName": {"value": "deploysa0001"}},
          "template": {
            // the az CLI splices the raw template file, comments included
            "$schema": "https://schema.management.azure.com/schemas/2019-04-01/deploymentTemplate.json#",
            "contentVersion": "1.0.0.0",
            "parameters": {"accountName": {"type": "string"},
                           "location": {"type": "string", "defaultValue": "[resourceGroup().location]"}},
            "variables": {"vnetName": "vnet1"},
            "resources": [
              {"type": "Microsoft.Storage/storageAccounts/blobServices/containers", "apiVersion": "2023-01-01",
               "name": "[concat(parameters('accountName'), '/default/logs')]",
               "dependsOn": ["[resourceId('Microsoft.Storage/storageAccounts', parameters('accountName'))]"],
               "properties": {}},
              {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01",
               "name": "[parameters('accountName')]", "location": "[parameters('location')]",
               "kind": "StorageV2", "sku": {"name": "Standard_LRS"}},
              {"type": "Microsoft.Network/virtualNetworks", "apiVersion": "2023-05-01",
               "name": "[variables('vnetName')]", "location": "[parameters('location')]",
               "properties": {"addressSpace": {"addressPrefixes": ["10.0.0.0/16"]}}},
              {"type": "Microsoft.Network/virtualNetworks/subnets", "apiVersion": "2023-05-01",
               "name": "[concat(variables('vnetName'), '/default')]",
               "dependsOn": ["[variables('vnetName')]"],
               "properties": {"addressPrefix": "10.0.1.0/24"}},
            ],
            "outputs": {
              "blobEndpoint": {"type": "string",
                               "value": "[reference(parameters('accountName')).primaryEndpoints.blob]"},
              "subnetId": {"type": "string",
                           "value": "[resourceId('Microsoft.Network/virtualNetworks/subnets', variables('vnetName'), 'default')]"},
              "label": {"type": "string", "value": "[concat('sa-', parameters('accountName'))]"}}}}}
        """;

    private static RequestSpecification json(String body) {
        return given().contentType("application/json").body(body);
    }

    /**
     * Polls the {@code Azure-AsyncOperation} URL a PUT answered until the run ends, as a client does, and returns
     * the terminal status.
     */
    private static String awaitTerminal(Response accepted) throws InterruptedException {
        String statusUrl = accepted.header("Azure-AsyncOperation");
        assertNotNull(statusUrl, "a PUT answers an Azure-AsyncOperation header");
        String path = URI.create(statusUrl).getRawPath() + "?" + URI.create(statusUrl).getRawQuery();
        for (int attempt = 0; attempt < 200; attempt++) {
            Response poll = given().when().get(path);
            poll.then().statusCode(200);
            String status = poll.path("status");
            if (!status.equals("Accepted") && !status.equals("Running")) {
                assertNull(poll.header("Retry-After"), "a final status asks for no further poll");
                return status;
            }
            assertEquals("1", poll.header("Retry-After"), "a status that is not final asks for a short poll");
            Thread.sleep(50);
        }
        throw new AssertionError("the deployment did not finish: " + statusUrl);
    }

    private static Response put(String name, String body) {
        return json(body).when().put(DEPLOYMENTS + "/" + name + API);
    }

    @BeforeEach
    void group() {
        given().post("/_admin/reset").then().statusCode(204);
        json("{\"location\":\"westeurope\"}").when().put(RG + "?api-version=2021-04-01").then().statusCode(201);
    }

    @Test
    void aPutIsAcceptedAndPolledThroughItsAsyncOperationAsAzureRecordsIt() throws Exception {
        Response accepted = put("dep0", DEPLOYMENT);

        accepted.then().statusCode(201)
            .header("Retry-After", equalTo("1"))
            .header("Azure-AsyncOperation", containsString("/deployments/dep0/operationStatuses/"))
            .body("properties.provisioningState", equalTo("Accepted"))
            .body("properties.providers", hasSize(0))
            .body("properties.dependencies", hasSize(0));
        assertTrue(accepted.header("Azure-AsyncOperation").matches(".*/operationStatuses/\\d{20}\\?api-version=2025-04-01"),
            accepted.header("Azure-AsyncOperation"));
        assertEquals("Succeeded", awaitTerminal(accepted));
    }

    @Test
    void aDeploymentCreatesEveryResourceAndAnswersItsOutputs() throws Exception {
        assertEquals("Succeeded", awaitTerminal(put("dep1", DEPLOYMENT)));

        given().when().get(DEPLOYMENTS + "/dep1" + API).then().statusCode(200)
            .body("id", equalTo(DEPLOYMENTS + "/dep1"))
            .body("type", equalTo("Microsoft.Resources/deployments"))
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.mode", equalTo("Incremental"))
            .body("properties.parameters.accountName.value", equalTo("deploysa0001"))
            .body("properties.parameters.location.value", equalTo("westeurope"))
            .body("properties.outputs.subnetId.value", equalTo(SUBNET))
            .body("properties.outputs.label.value", equalTo("sa-deploysa0001"))
            .body("properties.outputResources", hasSize(4));

        String blob = given().when().get(ACCOUNT + "?api-version=2023-01-01").then().statusCode(200)
            .body("location", equalTo("westeurope"))
            .extract().path("properties.primaryEndpoints.blob");
        given().when().get(DEPLOYMENTS + "/dep1" + API).then().statusCode(200)
            .body("properties.outputs.blobEndpoint.value", equalTo(blob));
        given().when().get(SUBNET + "?api-version=2023-05-01").then().statusCode(200)
            .body("properties.addressPrefix", equalTo("10.0.1.0/24"));
    }

    @Test
    void theAzCliBodyWithItsUnquotedTemplateKeyIsAccepted() {
        // What JsonCTemplatePolicy sends: the JSON of the other properties, then ", template:" and the raw file.
        String body = "{\"properties\": {\"mode\": \"Incremental\", \"parameters\": {\"accountName\": "
            + "{\"value\": \"deploysa0001\"}}, template:{\r\n  // raw file text\r\n  \"contentVersion\": \"1.0.0.0\",\r\n"
            + "  \"parameters\": {\"accountName\": {\"type\": \"string\"}},\r\n  \"resources\": []\r\n}}}";

        json(body).when().post(DEPLOYMENTS + "/azcli/validate" + API).then().statusCode(200)
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.parameters.accountName.value", equalTo("deploysa0001"));
    }

    @Test
    void validateChecksWithoutCreatingAnything() {
        json(DEPLOYMENT).when().post(DEPLOYMENTS + "/dep2/validate" + API).then().statusCode(200)
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.validatedResources", hasSize(4))
            .body("properties.validatedResources[0].id", equalTo(ACCOUNT));
        given().when().get(ACCOUNT + "?api-version=2023-01-01").then().statusCode(404);
        given().when().get(DEPLOYMENTS + "/dep2" + API).then().statusCode(404)
            .body("error.code", equalTo("DeploymentNotFound"));
    }

    @Test
    void aTemplateErrorIsA400ThatCreatesNothing() {
        json("{\"properties\": {\"mode\": \"Incremental\", \"template\": {\"resources\": [{\"type\": "
            + "\"Microsoft.Storage/storageAccounts\", \"apiVersion\": \"2023-01-01\", \"name\": \"[parameters('x')]\"}]}}}")
            .when().put(DEPLOYMENTS + "/bad" + API).then().statusCode(400)
            .body("error.code", equalTo("InvalidTemplate"))
            .body("error.message", startsWith("Deployment template validation failed: "));
        json("{\"properties\": {\"mode\": \"Complete\", \"template\": {}}}")
            .when().post(DEPLOYMENTS + "/bad/validate" + API).then().statusCode(400)
            .body("error.code", equalTo("DeploymentModeNotSupported"));
        given().when().get(DEPLOYMENTS + "/bad" + API).then().statusCode(404);
    }

    @Test
    void aFailingResourceFailsTheDeploymentWithAzuresError() throws Exception {
        Response accepted = put("failing", """
            {"properties": {"mode": "Incremental", "template": {"resources": [
              {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01", "name": "BAD_NAME!",
               "location": "westeurope", "kind": "StorageV2", "sku": {"name": "Standard_LRS"}}]}}}
            """);
        assertEquals("Failed", awaitTerminal(accepted));

        String statusUrl = accepted.header("Azure-AsyncOperation");
        given().when().get(URI.create(statusUrl).getRawPath() + API).then().statusCode(200)
            .body("status", equalTo("Failed"))
            .body("error.code", equalTo("DeploymentFailed"));
        given().when().get(DEPLOYMENTS + "/failing" + API).then().statusCode(200)
            .body("properties.provisioningState", equalTo("Failed"))
            .body("properties.error.code", equalTo("DeploymentFailed"))
            .body("properties.error.details[0].code", notNullValue())
            .body("properties.error.details[0].target",
                equalTo(RG + "/providers/Microsoft.Storage/storageAccounts/BAD_NAME!"));
    }

    @Test
    void exportListHeadAndDelete() throws Exception {
        Response first = put("dep3", DEPLOYMENT);
        first.then().statusCode(201);
        assertEquals("Succeeded", awaitTerminal(first));
        Response again = put("dep3", DEPLOYMENT);
        again.then().statusCode(200);
        assertEquals("Succeeded", awaitTerminal(again));

        given().when().post(DEPLOYMENTS + "/dep3/exportTemplate" + API).then().statusCode(200)
            .body("template.contentVersion", equalTo("1.0.0.0"))
            .body("template.variables.vnetName", equalTo("vnet1"));
        given().when().get(DEPLOYMENTS + API).then().statusCode(200)
            .body("value.name", contains("dep3"));
        given().queryParam("$filter", "provisioningState eq 'Failed'")
            .when().get(DEPLOYMENTS + API).then().statusCode(200)
            .body("value", hasSize(0));
        given().queryParam("$filter", "provisioningState eq 'Succeeded'")
            .when().get(DEPLOYMENTS + API).then().statusCode(200)
            .body("value.name", contains("dep3"));
        given().when().head(DEPLOYMENTS + "/dep3" + API).then().statusCode(204);

        given().when().delete(DEPLOYMENTS + "/dep3" + API).then().statusCode(204);

        given().when().head(DEPLOYMENTS + "/dep3" + API).then().statusCode(404);
        given().when().get(ACCOUNT + "?api-version=2023-01-01").then().statusCode(200);
    }

    @Test
    void onlyARunningDeploymentCanBeCanceled() throws Exception {
        assertEquals("Succeeded", awaitTerminal(put("dep4", DEPLOYMENT)));

        given().when().post(DEPLOYMENTS + "/dep4/cancel" + API).then().statusCode(409)
            .body("error.code", equalTo("DeploymentNotActive"));
        given().when().post(DEPLOYMENTS + "/none/cancel" + API).then().statusCode(404)
            .body("error.code", equalTo("DeploymentNotFound"));
        given().when().get(DEPLOYMENTS + "/dep4/operationStatuses/00000000000000000000" + API).then().statusCode(404);
    }

    @Test
    void aMissingResourceGroupIsResourceGroupNotFound() {
        json(DEPLOYMENT).when()
            .put("/subscriptions/" + SUB + "/resourceGroups/no-such-rg/providers/Microsoft.Resources/deployments/d"
                + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceGroupNotFound"));
    }
}
