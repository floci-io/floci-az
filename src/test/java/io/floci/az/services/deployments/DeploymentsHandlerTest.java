package io.floci.az.services.deployments;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

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

    @BeforeEach
    void group() {
        given().post("/_admin/reset").then().statusCode(204);
        json("{\"location\":\"westeurope\"}").when().put(RG + "?api-version=2021-04-01").then().statusCode(201);
    }

    @Test
    void aDeploymentCreatesEveryResourceAndAnswersItsOutputs() {
        json(DEPLOYMENT).when().put(DEPLOYMENTS + "/dep1" + API).then().statusCode(201)
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
    void aFailingResourceFailsTheDeploymentWithAzuresError() {
        json("""
            {"properties": {"mode": "Incremental", "template": {"resources": [
              {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01", "name": "BAD_NAME!",
               "location": "westeurope", "kind": "StorageV2", "sku": {"name": "Standard_LRS"}}]}}}
            """).when().put(DEPLOYMENTS + "/failing" + API).then().statusCode(201)
            .body("properties.provisioningState", equalTo("Failed"))
            .body("properties.error.code", equalTo("DeploymentFailed"))
            .body("properties.error.details[0].code", notNullValue())
            .body("properties.error.details[0].target",
                equalTo(RG + "/providers/Microsoft.Storage/storageAccounts/BAD_NAME!"));
    }

    @Test
    void exportListHeadAndDelete() {
        json(DEPLOYMENT).when().put(DEPLOYMENTS + "/dep3" + API).then().statusCode(201);
        json(DEPLOYMENT).when().put(DEPLOYMENTS + "/dep3" + API).then().statusCode(200);

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
    void aMissingResourceGroupIsResourceGroupNotFound() {
        json(DEPLOYMENT).when()
            .put("/subscriptions/" + SUB + "/resourceGroups/no-such-rg/providers/Microsoft.Resources/deployments/d"
                + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceGroupNotFound"));
    }
}
