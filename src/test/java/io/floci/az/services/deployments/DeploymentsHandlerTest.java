package io.floci.az.services.deployments;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
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
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
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
    void operationsAreListedInBothPathFormsAndReadOneByOne() throws Exception {
        assertEquals("Succeeded", awaitTerminal(put("ops", DEPLOYMENT)));

        String withoutProvider = RG + "/deployments/ops/operations" + API;
        Response list = given().when().get(withoutProvider);
        list.then().statusCode(200)
            .body("value", hasSize(5))
            .body("value[0].properties.provisioningOperation", equalTo("Create"))
            .body("value[0].properties.provisioningState", equalTo("Succeeded"))
            .body("value[0].properties.statusCode", equalTo("OK"))
            .body("value[0].properties.targetResource.id", equalTo(ACCOUNT))
            .body("value[0].properties.targetResource.resourceType", equalTo("Microsoft.Storage/storageAccounts"))
            .body("value[4].properties.provisioningOperation", equalTo("EvaluateDeploymentOutput"))
            .body("value[0].id", startsWith(DEPLOYMENTS + "/ops/operations/"));
        String operationId = list.path("value[0].operationId");

        given().when().get(DEPLOYMENTS + "/ops/operations" + API).then().statusCode(200)
            .body("value", hasSize(5));
        given().when().get(RG + "/deployments/ops/operations/" + operationId + API).then().statusCode(200)
            .body("operationId", equalTo(operationId))
            .body("properties.targetResource.resourceName", equalTo("deploysa0001"));
        given().when().get(DEPLOYMENTS + "/ops/operations/0000000000000000" + API).then().statusCode(404)
            .body("error.code", equalTo("DeploymentOperationNotFound"));
        given().when().get(RG + "/deployments/missing/operations" + API).then().statusCode(404)
            .body("error.code", equalTo("DeploymentNotFound"));
    }

    @Test
    void aFailedOperationCarriesTheProvidersStatusAndError() throws Exception {
        assertEquals("Failed", awaitTerminal(put("failop", """
            {"properties": {"mode": "Incremental", "template": {"resources": [
              {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01", "name": "BAD_NAME!",
               "location": "westeurope", "kind": "StorageV2", "sku": {"name": "Standard_LRS"}}]}}}
            """)));

        given().when().get(RG + "/deployments/failop/operations" + API).then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].properties.provisioningState", equalTo("Failed"))
            .body("value[0].properties.statusCode", equalTo("BadRequest"))
            .body("value[0].properties.statusMessage.status", equalTo("Failed"))
            .body("value[0].properties.statusMessage.error.code", notNullValue());
    }

    @Test
    void listsPageWithTopAndANextLink() throws Exception {
        for (String name : new String[] {"page1", "page2", "page3"}) {
            assertEquals("Succeeded", awaitTerminal(put(name, DEPLOYMENT)));
        }

        Response first = given().queryParam("$top", 2).when().get(DEPLOYMENTS + API);
        first.then().statusCode(200).body("value.name", contains("page1", "page2"));
        String next = first.path("nextLink");
        assertNotNull(next);
        assertTrue(next.contains("$skiptoken=2"), next);
        given().when().get(URI.create(next).getRawPath() + "?" + URI.create(next).getRawQuery()).then().statusCode(200)
            .body("value.name", contains("page3"))
            .body("nextLink", nullValue());
        given().when().get(DEPLOYMENTS + API).then().statusCode(200)
            .body("value", hasSize(3))
            .body("nextLink", nullValue());
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

    private static final String SUB_DEPLOYMENTS = "/subscriptions/" + SUB + "/providers/Microsoft.Resources/deployments";

    /** A subscription-scope template that creates a resource group, depending on it by its provider-form id. */
    private static final String SUBSCRIPTION_DEPLOYMENT = """
        {"location": "westeurope", "properties": {"mode": "Incremental", "template": {
          "parameters": {"groupName": {"type": "string", "defaultValue": "rg-from-sub"}},
          "resources": [
            {"type": "Microsoft.Resources/resourceGroups", "apiVersion": "2021-04-01",
             "name": "[parameters('groupName')]", "location": "northeurope", "tags": {"made": "sub"}}],
          "outputs": {
            "groupId": {"type": "string",
                        "value": "[subscriptionResourceId('Microsoft.Resources/resourceGroups', parameters('groupName'))]"},
            "groupLocation": {"type": "string",
                              "value": "[reference(subscriptionResourceId('Microsoft.Resources/resourceGroups', parameters('groupName')), '2021-04-01', 'Full').location]"}}}}}
        """;

    @Test
    void aSubscriptionDeploymentCreatesAResourceGroup() throws Exception {
        Response accepted = json(SUBSCRIPTION_DEPLOYMENT).when().put(SUB_DEPLOYMENTS + "/sub1" + API);
        accepted.then().statusCode(201)
            .body("id", equalTo(SUB_DEPLOYMENTS + "/sub1"))
            .body("location", equalTo("westeurope"));
        assertEquals("Succeeded", awaitTerminal(accepted),
            () -> given().when().get(SUB_DEPLOYMENTS + "/sub1" + API).asString());

        given().when().get("/subscriptions/" + SUB + "/resourceGroups/rg-from-sub?api-version=2021-04-01")
            .then().statusCode(200)
            .body("location", equalTo("northeurope"));
        given().when().get(SUB_DEPLOYMENTS + "/sub1" + API).then().statusCode(200)
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.outputResources[0].id", equalTo("/subscriptions/" + SUB + "/resourceGroups/rg-from-sub"))
            .body("properties.outputs.groupLocation.value", equalTo("northeurope"));
        given().when().get(SUB_DEPLOYMENTS + "/sub1/operations" + API).then().statusCode(200)
            .body("value[0].properties.targetResource.resourceType", equalTo("Microsoft.Resources/resourceGroups"));
        given().when().get(SUB_DEPLOYMENTS + API).then().statusCode(200).body("value.name", contains("sub1"));
        given().when().get(DEPLOYMENTS + API).then().statusCode(200).body("value", hasSize(0));
    }

    @Test
    void aSubscriptionDeploymentNeedsAFixedLocation() throws Exception {
        json(SUBSCRIPTION_DEPLOYMENT.replace("\"location\": \"westeurope\", ", ""))
            .when().put(SUB_DEPLOYMENTS + "/noloc" + API).then().statusCode(400)
            .body("error.code", equalTo("LocationRequired"));
        assertEquals("Succeeded", awaitTerminal(json(SUBSCRIPTION_DEPLOYMENT).when().put(SUB_DEPLOYMENTS + "/fixed" + API)));
        json(SUBSCRIPTION_DEPLOYMENT.replace("westeurope", "eastus"))
            .when().put(SUB_DEPLOYMENTS + "/fixed" + API).then().statusCode(400)
            .body("error.code", equalTo("InvalidDeploymentLocation"));
    }

    @Test
    void aSubscriptionDeploymentRefusesResourceGroupTypesAndResourceGroupFunctions() throws Exception {
        json("""
            {"location": "westeurope", "properties": {"mode": "Incremental", "template": {"resources": [
              {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01", "name": "subsa0001",
               "location": "westeurope"}]}}}
            """).when().put(SUB_DEPLOYMENTS + "/rgtype" + API).then().statusCode(400)
            .body("error.code", equalTo("InvalidTemplate"))
            .body("error.message", containsString("cannot be deployed at subscription scope"));
        Response failed = json("""
            {"location": "westeurope", "properties": {"mode": "Incremental", "template": {
              "outputs": {"rg": {"type": "string", "value": "[resourceGroup().name]"}}}}}
            """).when().put(SUB_DEPLOYMENTS + "/rgfn2" + API);
        assertEquals("Failed", awaitTerminal(failed));
        given().when().get(SUB_DEPLOYMENTS + "/rgfn2" + API).then().statusCode(200)
            .body("properties.error.details[0].code", equalTo("InvalidTemplate"))
            .body("properties.error.details[0].message", containsString("subscription-scope deployment has no resource group"));
    }

    /** azd's shape: a subscription template that creates a group, then deploys into it through a module. */
    private static String moduleDeployment(String scope) {
        return """
            {"location": "westeurope", "properties": {"mode": "Incremental", "template": {
              "parameters": {"accountName": {"type": "string", "defaultValue": "moduleacct01"}},
              "variables": {"groupName": "rg-module"},
              "resources": [
                {"type": "Microsoft.Resources/resourceGroups", "apiVersion": "2021-04-01",
                 "name": "[variables('groupName')]", "location": "westeurope"},
                {"type": "Microsoft.Resources/deployments", "apiVersion": "2022-09-01", "name": "storage",
                 "resourceGroup": "[variables('groupName')]",
                 "dependsOn": ["[subscriptionResourceId('Microsoft.Resources/resourceGroups', variables('groupName'))]"],
                 "properties": {"mode": "Incremental", "expressionEvaluationOptions": {"scope": "SCOPE"},
                   PARAMETERS
                   "template": {
                     PARAMETER_DECLARATIONS
                     "resources": [{"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01",
                                    "name": "[parameters('accountName')]", "location": "westeurope",
                                    "kind": "StorageV2", "sku": {"name": "Standard_LRS"}}],
                     "outputs": {"accountId": {"type": "string",
                       "value": "[resourceId('Microsoft.Storage/storageAccounts', parameters('accountName'))]"}}}}}],
              "outputs": {"fromModule": {"type": "string",
                "value": "[reference('storage').outputs.accountId.value]"}}}}}
            """.replace("SCOPE", scope)
            .replace("PARAMETERS", scope.equals("inner")
                ? "\"parameters\": {\"accountName\": {\"value\": \"[parameters('accountName')]\"}}," : "")
            .replace("PARAMETER_DECLARATIONS", scope.equals("inner")
                ? "\"parameters\": {\"accountName\": {\"type\": \"string\"}}," : "");
    }

    @Test
    void aSubscriptionTemplateDeploysIntoItsNewGroupThroughAnInnerModule() throws Exception {
        Response accepted = json(moduleDeployment("inner")).when().put(SUB_DEPLOYMENTS + "/main" + API);
        assertEquals("Succeeded", awaitTerminal(accepted),
            () -> given().when().get(SUB_DEPLOYMENTS + "/main" + API).asString());

        String account = "/subscriptions/" + SUB + "/resourceGroups/rg-module/providers/Microsoft.Storage/"
            + "storageAccounts/moduleacct01";
        given().when().get(account + "?api-version=2023-01-01").then().statusCode(200);
        given().when().get(SUB_DEPLOYMENTS + "/main" + API).then().statusCode(200)
            .body("properties.outputs.fromModule.value", equalTo(account));
        given().when().get("/subscriptions/" + SUB + "/resourceGroups/rg-module/providers/Microsoft.Resources/"
            + "deployments/storage" + API).then().statusCode(200)
            .body("properties.provisioningState", equalTo("Succeeded"))
            .body("properties.outputs.accountId.value", equalTo(account));
    }

    @Test
    void anOuterModuleUsesTheParentsParameters() throws Exception {
        Response accepted = json(moduleDeployment("outer")).when().put(SUB_DEPLOYMENTS + "/main-outer" + API);
        assertEquals("Succeeded", awaitTerminal(accepted),
            () -> given().when().get(SUB_DEPLOYMENTS + "/main-outer" + API).asString());

        given().when().get("/subscriptions/" + SUB + "/resourceGroups/rg-module/providers/Microsoft.Storage/"
            + "storageAccounts/moduleacct01?api-version=2023-01-01").then().statusCode(200);
    }

    @Test
    void calculateTemplateHashAgreesWithTheDeploymentsOwnHash() throws Exception {
        String template = DEPLOYMENT.substring(DEPLOYMENT.indexOf("\"template\": ") + "\"template\": ".length(),
            DEPLOYMENT.lastIndexOf("}}"));
        Response hashed = json(template).when().post("/providers/Microsoft.Resources/calculateTemplateHash" + API);
        hashed.then().statusCode(200)
            .body("minifiedTemplate", startsWith("{\"$SCHEMA\":"));

        assertEquals("Succeeded", awaitTerminal(put("hashed", DEPLOYMENT)));
        given().when().get(DEPLOYMENTS + "/hashed" + API).then().statusCode(200)
            .body("properties.templateHash", equalTo(hashed.path("templateHash")));
    }

    @Test
    void whatIfPredictsCreatesWithoutCreatingAnything() {
        given().contentType("application/json").body(DEPLOYMENT).when().post(DEPLOYMENTS + "/preview/whatIf" + API)
            .then().statusCode(200)
            .body("status", equalTo("Succeeded"))
            .body("properties.changes", hasSize(4))
            // The container is left out: the ARM container GET answers 200 for any container.
            .body("properties.changes.findAll { !it.resourceId.contains('/containers/') }.changeType",
                everyItem(equalTo("Create")))
            .body("properties.changes[0].resourceId", equalTo(ACCOUNT))
            .body("properties.changes[0].after.type", equalTo("Microsoft.Storage/storageAccounts"))
            .body("properties.changes[0].after.location", equalTo("westeurope"));
        given().when().get(ACCOUNT + "?api-version=2023-01-01").then().statusCode(404);
        given().when().get(DEPLOYMENTS + "/preview" + API).then().statusCode(404);
    }

    @Test
    void whatIfAfterADeploymentShowsWhatTheTemplateWouldChange() throws Exception {
        assertEquals("Succeeded", awaitTerminal(put("base", DEPLOYMENT)));

        given().contentType("application/json").body(DEPLOYMENT).when().post(DEPLOYMENTS + "/base/whatIf" + API)
            .then().statusCode(200)
            .body("properties.changes.find { it.resourceId.endsWith('/virtualNetworks/vnet1') }.changeType",
                equalTo("NoChange"));
        given().contentType("application/json").body(DEPLOYMENT.replace("10.0.0.0/16", "10.9.0.0/16"))
            .when().post(DEPLOYMENTS + "/base/whatIf" + API).then().statusCode(200)
            .body("properties.changes.find { it.resourceId.endsWith('/virtualNetworks/vnet1') }.changeType",
                equalTo("Modify"))
            .body("properties.changes.find { it.resourceId.endsWith('/virtualNetworks/vnet1') }.delta[0].path",
                equalTo("properties.addressSpace.addressPrefixes"));
        given().contentType("application/json")
            .body(DEPLOYMENT.replace("\"mode\": \"Incremental\",",
                "\"mode\": \"Incremental\", \"whatIfSettings\": {\"resultFormat\": \"ResourceIdOnly\"},"))
            .when().post(DEPLOYMENTS + "/base/whatIf" + API).then().statusCode(200)
            .body("properties.changes[0].before", nullValue())
            .body("properties.changes[0].after", nullValue())
            .body("properties.changes[0].resourceId", equalTo(ACCOUNT));
    }

    @Test
    void whatIfMarksANestedDeploymentUnsupported() {
        given().contentType("application/json").body(moduleDeployment("inner"))
            .when().post(SUB_DEPLOYMENTS + "/main/whatIf" + API).then().statusCode(200)
            .body("properties.changes[0].changeType", equalTo("Create"))
            .body("properties.changes[1].changeType", equalTo("Unsupported"))
            .body("properties.changes[1].unsupportedReason", containsString("nested deployment"));
    }

    /** Puts {@code content} in floci's own blob storage and answers its URL, the way a linked template is staged. */
    private static String staged(String name, String content) {
        given().put("/devstoreaccount1/templates?restype=container");
        given().contentType("application/json").header("x-ms-blob-type", "BlockBlob").body(content)
            .when().put("/devstoreaccount1/templates/" + name).then().statusCode(201);
        return "http://localhost:" + RestAssured.port + "/devstoreaccount1/templates/" + name;
    }

    private static final String LINKED_TEMPLATE = """
        {"contentVersion": "1.0.0.0",
         "parameters": {"accountName": {"type": "string"}},
         "resources": [
           {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01",
            "name": "[parameters('accountName')]", "location": "westeurope", "kind": "StorageV2",
            "sku": {"name": "Standard_LRS"}},
           {"type": "Microsoft.Resources/deployments", "apiVersion": "2022-09-01", "name": "child",
            "properties": {"mode": "Incremental", "templateLink": {"relativePath": "children/child.json"}}}],
         "outputs": {"name": {"type": "string", "value": "[parameters('accountName')]"}}}
        """;

    @Test
    void aLinkedTemplateAndParameterFileAreDownloadedAndEchoed() throws Exception {
        String template = staged("main.json", LINKED_TEMPLATE);
        staged("children/child.json", """
            {"contentVersion": "1.0.0.0", "resources": [
              {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01", "name": "linkedchild01",
               "location": "westeurope", "kind": "StorageV2", "sku": {"name": "Standard_LRS"}}]}
            """);
        String parameters = staged("main.parameters.json", """
            {"$schema": "https://schema.management.azure.com/schemas/2019-04-01/deploymentParameters.json#",
             "contentVersion": "1.0.0.0", "parameters": {"accountName": {"value": "linkedmain01"}}}
            """);

        Response accepted = json("{\"properties\": {\"mode\": \"Incremental\", \"templateLink\": {\"uri\": \""
            + template + "\", \"contentVersion\": \"1.0.0.0\"}, \"parametersLink\": {\"uri\": \"" + parameters + "\"}}}")
            .when().put(DEPLOYMENTS + "/linked" + API);
        accepted.then().statusCode(201).body("properties.templateLink.uri", equalTo(template));
        assertEquals("Succeeded", awaitTerminal(accepted),
            () -> given().when().get(DEPLOYMENTS + "/linked" + API).asString());

        given().when().get(DEPLOYMENTS + "/linked" + API).then().statusCode(200)
            .body("properties.outputs.name.value", equalTo("linkedmain01"))
            .body("properties.parametersLink.uri", equalTo(parameters));
        given().when().get(RG + "/providers/Microsoft.Storage/storageAccounts/linkedmain01?api-version=2023-01-01")
            .then().statusCode(200);
        given().when().get(RG + "/providers/Microsoft.Storage/storageAccounts/linkedchild01?api-version=2023-01-01")
            .then().statusCode(200);
        given().when().get(DEPLOYMENTS + "/child" + API).then().statusCode(200)
            .body("properties.templateLink.uri", equalTo(template.replace("main.json", "children/child.json")));
        given().when().post(DEPLOYMENTS + "/linked/exportTemplate" + API).then().statusCode(200)
            .body("template.outputs.name.type", equalTo("string"));
    }

    @Test
    void aLinkThatCannotBeUsedIsInvalidContentLink() {
        String template = staged("versioned.json", "{\"contentVersion\": \"1.0.0.0\", \"resources\": []}");
        String missing = "http://localhost:" + RestAssured.port + "/devstoreaccount1/templates/missing.json";
        json("{\"properties\": {\"mode\": \"Incremental\", \"templateLink\": {\"uri\": \"" + missing + "\"}}}")
            .when().put(DEPLOYMENTS + "/broken" + API).then().statusCode(400)
            .body("error.code", equalTo("InvalidContentLink"))
            .body("error.message", startsWith("Unable to download deployment content from '" + missing + "'"));
        json("{\"properties\": {\"mode\": \"Incremental\", \"templateLink\": {\"uri\": \"" + template
            + "\", \"contentVersion\": \"2.0.0.0\"}}}")
            .when().post(DEPLOYMENTS + "/versioned/validate" + API).then().statusCode(400)
            .body("error.code", equalTo("InvalidContentLink"));
        json("{\"properties\": {\"mode\": \"Incremental\", \"templateLink\": {\"uri\": \"" + template
            + "\"}, \"parameters\": {}, \"parametersLink\": {\"uri\": \"" + template + "\"}}}")
            .when().put(DEPLOYMENTS + "/both" + API).then().statusCode(400)
            .body("error.code", equalTo("InvalidTemplate"));
        json("{\"properties\": {\"mode\": \"Incremental\", \"templateLink\": {\"id\": \"/subscriptions/x/"
            + "resourceGroups/y/providers/Microsoft.Resources/templateSpecs/z/versions/1\"}}}")
            .when().put(DEPLOYMENTS + "/spec" + API).then().statusCode(400)
            .body("error.message", containsString("template spec"));
    }

    /** The documented contoso.uniqueName template, with an outer-scope nested deployment calling it too. */
    private static final String USER_FUNCTION_DEPLOYMENT = """
        {"properties": {"mode": "Incremental", "template": {
          "contentVersion": "1.0.0.0",
          "parameters": {"storageNamePrefix": {"type": "string", "maxLength": 11}},
          "functions": [{"namespace": "contoso", "members": {"uniqueName": {
            "parameters": [{"name": "namePrefix", "type": "string"}],
            "output": {"type": "string",
                       "value": "[format('{0}{1}', toLower(parameters('namePrefix')), uniqueString(resourceGroup().id))]"}
          }}}],
          "resources": [
            {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01",
             "name": "[contoso.uniqueName(parameters('storageNamePrefix'))]", "location": "westeurope",
             "kind": "StorageV2", "sku": {"name": "Standard_LRS"}},
            {"type": "Microsoft.Resources/deployments", "apiVersion": "2022-09-01", "name": "outer-module",
             "properties": {"mode": "Incremental", "template": {
               "resources": [{"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01",
                 "name": "[contoso.uniqueName('Outer')]", "location": "westeurope", "kind": "StorageV2",
                 "sku": {"name": "Standard_LRS"}}]}}}],
          "outputs": {"accountName": {"type": "string",
                                      "value": "[contoso.uniqueName(parameters('storageNamePrefix'))]"},
                      "outerName": {"type": "string", "value": "[contoso.uniqueName('Outer')]"}}},
          "parameters": {"storageNamePrefix": {"value": "Udf"}}}}
        """;

    @Test
    void userDefinedFunctionsNameResourcesAndOutputsAndReachAnOuterNestedDeployment() throws Exception {
        assertEquals("Succeeded", awaitTerminal(put("udf", USER_FUNCTION_DEPLOYMENT)));

        String accountName = given().when().get(DEPLOYMENTS + "/udf" + API).then().statusCode(200)
            .extract().path("properties.outputs.accountName.value");
        String outerName = given().when().get(DEPLOYMENTS + "/udf" + API)
            .then().extract().path("properties.outputs.outerName.value");
        assertTrue(accountName.startsWith("udf") && accountName.length() == 16, accountName);
        assertEquals(accountName.substring(3), outerName.substring(5), "the same uniqueString(resourceGroup().id)");
        given().when().get(RG + "/providers/Microsoft.Storage/storageAccounts/" + accountName + "?api-version=2023-01-01")
            .then().statusCode(200);
        given().when().get(RG + "/providers/Microsoft.Storage/storageAccounts/" + outerName + "?api-version=2023-01-01")
            .then().statusCode(200);
    }

    @Test
    void aUserDefinedFunctionReadingVariablesIsRefusedAtValidation() {
        json(USER_FUNCTION_DEPLOYMENT.replace("toLower(parameters('namePrefix'))", "variables('x')"))
            .when().post(DEPLOYMENTS + "/udf-bad/validate" + API).then().statusCode(400)
            .body("error.code", equalTo("InvalidTemplate"))
            .body("error.message", containsString("can't access variables"));
    }

    @Test
    void aMissingResourceGroupIsResourceGroupNotFound() {
        json(DEPLOYMENT).when()
            .put("/subscriptions/" + SUB + "/resourceGroups/no-such-rg/providers/Microsoft.Resources/deployments/d"
                + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceGroupNotFound"));
    }

    @Test
    void aRunThatFailsUnexpectedlyLeavesItsRecordFailedAndReplaceable() throws Exception {
        String overflowing = """
            {"properties": {"mode": "Incremental", "template": {
              "outputs": {"big": {"type": "int", "value": "[add(9223372036854775807, 1)]"}}}}}
            """;
        assertEquals("Failed", awaitTerminal(put("overflow", overflowing)));

        given().when().get(DEPLOYMENTS + "/overflow" + API).then().statusCode(200)
            .body("properties.provisioningState", equalTo("Failed"))
            .body("properties.error.code", equalTo("InternalServerError"));
        assertEquals("Failed", awaitTerminal(put("overflow", overflowing)), "a failed name can be deployed again");
        given().when().delete(DEPLOYMENTS + "/overflow" + API).then().statusCode(204);
    }

    @Test
    void laterPagesKeepTheFilterOfTheFirst() throws Exception {
        String failing = """
            {"properties": {"mode": "Incremental", "template": {"resources": [
              {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01", "name": "BAD_NAME!",
               "location": "westeurope", "kind": "StorageV2", "sku": {"name": "Standard_LRS"}}]}}}
            """;
        String empty = "{\"properties\": {\"mode\": \"Incremental\", \"template\": {\"resources\": []}}}";
        assertEquals("Succeeded", awaitTerminal(put("a", empty)));
        assertEquals("Failed", awaitTerminal(put("b", failing)));
        assertEquals("Succeeded", awaitTerminal(put("c", empty)));

        Response first = given().queryParam("$filter", "provisioningState eq 'Succeeded'").queryParam("$top", 1)
            .when().get(DEPLOYMENTS + API);
        first.then().statusCode(200).body("value.name", contains("a"));
        URI next = URI.create(first.path("nextLink"));
        given().urlEncodingEnabled(false).when().get(next.getRawPath() + "?" + next.getRawQuery())
            .then().statusCode(200)
            .body("value.name", contains("c"))
            .body("nextLink", nullValue());
    }

    @Test
    void deletingTheGroupDeletesItsDeploymentHistory() throws Exception {
        assertEquals("Succeeded", awaitTerminal(put("history",
            "{\"properties\": {\"mode\": \"Incremental\", \"template\": {\"resources\": []}}}")));

        int deleted = given().when().delete(RG + "?api-version=2021-04-01").then().extract().statusCode();
        assertTrue(deleted == 200 || deleted == 202, "the group delete answers " + deleted);
        json("{\"location\":\"westeurope\"}").when().put(RG + "?api-version=2021-04-01").then().statusCode(201);

        given().when().get(DEPLOYMENTS + "/history" + API).then().statusCode(404)
            .body("error.code", equalTo("DeploymentNotFound"));
        given().when().get(DEPLOYMENTS + API).then().statusCode(200).body("value", hasSize(0));
    }

    @Test
    void anExtensionResourceIsCreatedAtTheResourceItsScopeNames() throws Exception {
        String assignment = ACCOUNT + "/providers/Microsoft.Authorization/policyAssignments/onAccount";
        assertEquals("Succeeded", awaitTerminal(put("extension", """
            {"properties": {"mode": "Incremental", "template": {"resources": [
              {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01", "name": "deploysa0001",
               "location": "westeurope", "kind": "StorageV2", "sku": {"name": "Standard_LRS"}},
              {"type": "Microsoft.Authorization/policyAssignments", "apiVersion": "2025-03-01", "name": "onAccount",
               "scope": "Microsoft.Storage/storageAccounts/deploysa0001", "dependsOn": ["deploysa0001"],
               "properties": {"policyDefinitionId":
                 "/providers/Microsoft.Authorization/policyDefinitions/0a914e76-4921-4c19-b460-a2d36003525a"}}]}}}
            """)), () -> given().when().get(DEPLOYMENTS + "/extension" + API).asString());

        given().when().get(assignment + "?api-version=2025-03-01").then().statusCode(200)
            .body("properties.scope", equalTo(ACCOUNT));
        given().when().get(RG + "/providers/Microsoft.Authorization/policyAssignments/onAccount?api-version=2025-03-01")
            .then().statusCode(404);
    }
}
