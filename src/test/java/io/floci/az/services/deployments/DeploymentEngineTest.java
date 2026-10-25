package io.floci.az.services.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.core.ArmRouter;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.arm.ArmTypeRegistry;
import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.directory.AzureDirectory;
import io.floci.az.core.directory.ResourceGroups;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DeploymentEngine: ordering, dispatch, outputs and failures through the internal dispatch seam")
class DeploymentEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUB = "00000000-0000-0000-0000-0000000000e1";
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String RG_ID = "/subscriptions/" + SUB + "/resourceGroups/rg1";
    private static final String SA_ID = RG_ID + "/providers/Microsoft.Storage/storageAccounts/sa1";
    private static final String CONTAINER_ID = SA_ID + "/blobServices/default/containers/c1";

    /** One request the engine sent: method, path, api-version and body. */
    private record Sent(String method, String path, String apiVersion, JsonNode body) {
    }

    private final List<Sent> sent = new ArrayList<>();
    private Function<Sent, Response> answer;
    private DeploymentEngine engine;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void engine() {
        ArmRouter router = mock(ArmRouter.class);
        when(router.dispatchInternal(anyString(), anyString(), anyMap(), anyMap(), any(), any()))
            .thenAnswer(invocation -> record(invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2), invocation.getArgument(4)));
        when(router.dispatchInternal(anyString(), anyString(), anyMap(), (AzureRequest) any()))
            .thenAnswer(invocation -> record(invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2), null));
        Instance<ArmRouter> instance = mock(Instance.class);
        when(instance.get()).thenReturn(router);
        AzureDirectory directory = mock(AzureDirectory.class);
        when(directory.tenantOfSubscription(SUB)).thenReturn(TENANT);
        when(directory.cloudOfTenant(TENANT)).thenReturn(CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD));
        when(directory.subscription(SUB)).thenReturn(Optional.empty());
        when(directory.tenant(TENANT)).thenReturn(Optional.empty());
        engine = new DeploymentEngine(instance, new ResourceGroups(null), directory, ArmTypeRegistry.empty(), MAPPER);
        answer = request -> Response.ok(echo(request)).build();
    }

    private Response record(String method, String path, Map<String, String> query, byte[] body) throws Exception {
        Sent request = new Sent(method, path.startsWith("/") ? path : "/" + path, query.get("api-version"),
            body == null ? null : MAPPER.readTree(new String(body, StandardCharsets.UTF_8)));
        sent.add(request);
        return answer.apply(request);
    }

    /** A handler that answers a PUT with its body plus an id and Succeeded, and a GET likewise. */
    private static JsonNode echo(Sent request) {
        ObjectNode body = request.body() == null
            ? MAPPER.createObjectNode() : request.body().deepCopy();
        body.put("id", request.path());
        body.withObjectProperty("properties").put("provisioningState", "Succeeded");
        if (request.path().endsWith("/sa1")) {
            body.withObjectProperty("properties").withObjectProperty("primaryEndpoints").put("blob", "https://sa1.blob.example/");
        }
        return body;
    }

    private DeploymentEngine.Request request(String properties) throws Exception {
        return new DeploymentEngine.Request(SUB, "rg1", "dep1", MAPPER.readTree(properties), null);
    }

    private static final String STORAGE_TEMPLATE = """
        {"mode": "Incremental", "parameters": {"name": {"value": "sa1"}}, "template": {
          "parameters": {"name": {"type": "string"}, "secret": {"type": "securestring", "defaultValue": "s3"}},
          "variables": {"container": "c1"},
          "resources": [
            {"type": "Microsoft.Storage/storageAccounts/blobServices/containers", "apiVersion": "2023-01-01",
             "name": "[concat(parameters('name'), '/default/', variables('container'))]",
             "dependsOn": ["[resourceId('Microsoft.Storage/storageAccounts', parameters('name'))]"],
             "properties": {"publicAccess": "None"}},
            {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01",
             "name": "[parameters('name')]", "location": "eastus", "kind": "StorageV2",
             "sku": {"name": "Standard_LRS"}, "comments": "the account"}],
          "outputs": {
            "blob": {"type": "string", "value": "[reference(parameters('name')).primaryEndpoints.blob]"},
            "id": {"type": "string", "value": "[resourceId('Microsoft.Storage/storageAccounts', 'sa1')]"}}}}
        """;

    @Test
    void resourcesAreSentInDependencyOrderWithTheirApiVersionAndWithoutTemplateKeys() throws Exception {
        DeploymentEngine.Result result = engine.deploy(request(STORAGE_TEMPLATE));

        assertEquals("Succeeded", result.provisioningState());
        assertEquals("PUT", sent.get(0).method());
        assertEquals(SA_ID, sent.get(0).path());
        assertEquals("2023-01-01", sent.get(0).apiVersion());
        assertEquals("{\"location\":\"eastus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}",
            sent.get(0).body().toString());
        assertEquals(CONTAINER_ID, sent.get(1).path());
        assertEquals("{\"properties\":{\"publicAccess\":\"None\"}}", sent.get(1).body().toString());
        assertEquals(List.of(SA_ID, CONTAINER_ID), result.outputResources());
    }

    @Test
    void outputsAreEvaluatedAfterTheResourcesExistAndReadThemLive() throws Exception {
        DeploymentEngine.Result result = engine.deploy(request(STORAGE_TEMPLATE));

        assertEquals("https://sa1.blob.example/", result.outputs().path("blob").path("value").asText());
        assertEquals("string", result.outputs().path("blob").path("type").asText());
        assertEquals(SA_ID, result.outputs().path("id").path("value").asText());
        Sent read = sent.get(sent.size() - 1);
        assertEquals("GET", read.method());
        assertEquals(SA_ID, read.path());
        assertEquals("2023-01-01", read.apiVersion(), "a reference by name uses the template's api-version");
    }

    @Test
    void parametersAreEchoedWithAzuresTypeNamesAndSecureValuesLeftOut() throws Exception {
        DeploymentEngine.Result result = engine.deploy(request(STORAGE_TEMPLATE));

        assertEquals("String", result.parameters().path("name").path("type").asText());
        assertEquals("sa1", result.parameters().path("name").path("value").asText());
        assertEquals("SecureString", result.parameters().path("secret").path("type").asText());
        assertFalse(result.parameters().path("secret").has("value"));
        assertNull(result.error());
        assertEquals("Microsoft.Storage", result.providers().get(0).path("namespace").asText());
        assertEquals(CONTAINER_ID, result.dependencies().get(0).path("id").asText());
        assertEquals(SA_ID, result.dependencies().get(0).path("dependsOn").get(0).path("id").asText());
    }

    @Test
    void aHandlerErrorFailsTheDeploymentWithAzuresErrorAndStopsIt() throws Exception {
        answer = request -> Response.status(400).entity(Map.of("error",
            Map.of("code", "AccountNameInvalid", "message", "bad name"))).build();

        DeploymentEngine.Result result = engine.deploy(request(STORAGE_TEMPLATE));

        assertEquals("Failed", result.provisioningState());
        assertEquals(1, sent.size(), "nothing after the failed resource is sent");
        assertEquals("DeploymentFailed", result.error().path("code").asText());
        assertEquals(RG_ID + "/providers/Microsoft.Resources/deployments/dep1", result.error().path("target").asText());
        assertEquals(DeploymentEngine.DEPLOYMENT_FAILED_MESSAGE, result.error().path("message").asText());
        JsonNode detail = result.error().path("details").get(0);
        assertEquals("AccountNameInvalid", detail.path("code").asText());
        assertEquals(SA_ID, detail.path("target").asText());
        assertTrue(result.outputs().isEmpty());
    }

    @Test
    void anAcceptedResourceIsPolledToItsTerminalState() throws Exception {
        answer = request -> {
            if (request.method().equals("PUT") && request.path().equals(SA_ID)) {
                return Response.status(202).build();
            }
            return Response.ok(echo(request)).build();
        };

        DeploymentEngine.Result result = engine.deploy(request(STORAGE_TEMPLATE));

        assertEquals("Succeeded", result.provisioningState());
        assertEquals("GET", sent.get(1).method());
        assertEquals(SA_ID, sent.get(1).path());
    }

    @Test
    void aFailedTerminalStateIsAResourceDeploymentFailure() throws Exception {
        answer = request -> {
            ObjectNode body = (ObjectNode)
                echo(request);
            body.withObjectProperty("properties").put("provisioningState", "Failed");
            return Response.ok(body).build();
        };

        DeploymentEngine.Result result = engine.deploy(request(STORAGE_TEMPLATE));

        JsonNode detail = result.error().path("details").get(0);
        assertEquals("ResourceDeploymentFailure", detail.path("code").asText());
        assertEquals("The resource write operation failed to complete successfully, because it reached terminal "
            + "provisioning state 'Failed'.", detail.path("message").asText());
    }

    @Test
    void aFalseConditionSkipsTheResource() throws Exception {
        DeploymentEngine.Result result = engine.deploy(request("""
            {"mode": "Incremental", "template": {"resources": [
              {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01", "name": "skipped",
               "condition": "[equals('a', 'b')]", "location": "eastus"},
              {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01", "name": "kept",
               "condition": true, "location": "eastus"}]}}
            """));

        assertEquals(1, sent.size());
        assertTrue(sent.getFirst().path().endsWith("/kept"));
        assertEquals(1, result.outputResources().size());
    }

    @Test
    void aReferenceByNameIsAnImplicitDependency() throws Exception {
        engine.deploy(request("""
            {"mode": "Incremental", "template": {"resources": [
              {"type": "Microsoft.Web/sites", "apiVersion": "2022-03-01", "name": "app", "location": "eastus",
               "properties": {"blob": "[reference('sa1').primaryEndpoints.blob]"}},
              {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01", "name": "sa1",
               "location": "eastus"}]}}
            """));

        assertEquals(SA_ID, sent.get(0).path());
        assertEquals("GET", sent.get(1).method());
        assertTrue(sent.get(2).path().endsWith("/Microsoft.Web/sites/app"));
        assertEquals("https://sa1.blob.example/", sent.get(2).body().path("properties").path("blob").asText());
    }

    @Test
    void aCanceledRunCreatesNothingMoreAndEndsCanceled() throws Exception {
        DeploymentEngine.Prepared prepared = engine.prepare(request(STORAGE_TEMPLATE));

        DeploymentEngine.Result result = engine.run(prepared, () -> true);

        assertEquals("Canceled", result.provisioningState());
        assertTrue(sent.isEmpty());
        assertTrue(result.outputResources().isEmpty());
        assertNull(result.error());
    }

    @Test
    void aCancelSeenAfterTheFirstResourceKeepsIt() throws Exception {
        DeploymentEngine.Prepared prepared = engine.prepare(request(STORAGE_TEMPLATE));

        DeploymentEngine.Result result = engine.run(prepared, () -> !sent.isEmpty());

        assertEquals("Canceled", result.provisioningState());
        assertEquals(List.of(SA_ID), result.outputResources());
    }

    @Test
    void everyResourceAndTheOutputsAreRecordedAsOperations() throws Exception {
        DeploymentEngine.Result result = engine.deploy(request(STORAGE_TEMPLATE));

        List<DeploymentEngine.Operation> operations = result.operations();
        assertEquals(3, operations.size());
        assertEquals("Create", operations.get(0).provisioningOperation());
        assertEquals(SA_ID, operations.get(0).targetId());
        assertEquals("Microsoft.Storage/storageAccounts", operations.get(0).targetType());
        assertEquals("sa1", operations.get(0).targetName());
        assertEquals("OK", operations.get(0).statusCode());
        assertEquals("2023-01-01", operations.get(0).apiVersion());
        assertEquals(CONTAINER_ID, operations.get(1).targetId());
        assertEquals("EvaluateDeploymentOutput", operations.get(2).provisioningOperation());
        assertNull(operations.get(2).targetId());
        assertTrue(operations.get(0).operationId().matches("[0-9A-F]{16}"), operations.get(0).operationId());
    }

    @Test
    void aFailedResourceIsAFailedOperationWithTheHandlersStatusAndError() throws Exception {
        answer = request -> Response.status(409).entity(Map.of("error",
            Map.of("code", "StorageAccountAlreadyTaken", "message", "taken"))).build();

        DeploymentEngine.Result result = engine.deploy(request(STORAGE_TEMPLATE));

        DeploymentEngine.Operation failed = result.operations().getFirst();
        assertEquals(1, result.operations().size());
        assertEquals("Failed", failed.provisioningState());
        assertEquals("Conflict", failed.statusCode());
        assertEquals("StorageAccountAlreadyTaken", failed.error().path("code").asText());
    }

    private static final String LOOP_TEMPLATE = """
        {"mode": "Incremental", "parameters": {"count": {"value": 3}}, "template": {
          "parameters": {"count": {"type": "int"}},
          "variables": {
            "copy": [{"name": "names", "count": "[parameters('count')]",
                      "input": "[concat('sa', copyIndex('names', 1))]"}],
            "disks": {"copy": [{"name": "list", "count": 2, "input": {"lun": "[copyIndex('list')]"}}]}},
          "resources": [
            {"type": "Microsoft.Web/sites", "apiVersion": "2022-03-01", "name": "app", "location": "eastus",
             "dependsOn": ["accounts"],
             "properties": {"copy": [{"name": "slots", "count": 2,
                                      "input": {"slot": "[copyIndex('slots')]", "of": "[variables('names')[copyIndex('slots')]]"}}]}},
            {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01",
             "name": "[variables('names')[copyIndex()]]", "location": "eastus",
             "copy": {"name": "accounts", "count": "[parameters('count')]", "mode": "serial", "batchSize": 1},
             "tags": {"index": "[string(copyIndex())]", "disks": "[string(length(variables('disks').list))]"}},
            {"type": "Microsoft.KeyVault/vaults", "apiVersion": "2023-07-01", "name": "[concat('kv', copyIndex())]",
             "location": "eastus", "copy": {"name": "none", "count": 0}}],
          "outputs": {"ids": {"type": "array", "copy": {"count": "[parameters('count')]",
                                                     "input": "[concat('out', copyIndex())]"}}}}}
        """;

    @Test
    void aResourceCopyLoopDeploysOneResourcePerIterationWithItsCopyIndex() throws Exception {
        DeploymentEngine.Result result = engine.deploy(request(LOOP_TEMPLATE));

        assertEquals("Succeeded", result.provisioningState(), String.valueOf(result.error()));
        List<String> puts = sent.stream().filter(request -> request.method().equals("PUT")).map(Sent::path).toList();
        assertEquals(List.of(RG_ID + "/providers/Microsoft.Storage/storageAccounts/sa1",
            RG_ID + "/providers/Microsoft.Storage/storageAccounts/sa2",
            RG_ID + "/providers/Microsoft.Storage/storageAccounts/sa3",
            RG_ID + "/providers/Microsoft.Web/sites/app"), puts, "dependsOn a loop waits for every instance");
        assertEquals("{\"index\":\"1\",\"disks\":\"2\"}", sent.get(1).body().path("tags").toString());
        assertFalse(sent.get(0).body().has("copy"), "the resource loop is not sent");
    }

    @Test
    void propertyVariableAndOutputLoopsBuildArrays() throws Exception {
        DeploymentEngine.Result result = engine.deploy(request(LOOP_TEMPLATE));

        JsonNode app = sent.stream().filter(request -> request.path().endsWith("/sites/app")).findFirst()
            .orElseThrow().body();
        assertEquals("[{\"slot\":0,\"of\":\"sa1\"},{\"slot\":1,\"of\":\"sa2\"}]",
            app.path("properties").path("slots").toString());
        assertFalse(app.path("properties").has("copy"));
        assertEquals("[\"out0\",\"out1\",\"out2\"]", result.outputs().path("ids").path("value").toString());
    }

    @Test
    void aCopyCountOutsideZeroToEightHundredIsAnInvalidTemplate() {
        for (String count : new String[] {"-1", "801", "\"x\""}) {
            TemplateException refused = assertThrows(TemplateException.class, () -> engine.validate(request(
                "{\"mode\": \"Incremental\", \"template\": {\"resources\": [{\"type\": "
                    + "\"Microsoft.Storage/storageAccounts\", \"apiVersion\": \"1\", \"name\": \"[concat('a', copyIndex())]\","
                    + " \"copy\": {\"name\": \"c\", \"count\": " + count + "}}]}}")));
            assertTrue(refused.getMessage().contains("from 0 to 800"), refused.getMessage());
        }
    }

    @Test
    void aNestedChildIsDeployedAfterItsParentUnderTheJoinedName() throws Exception {
        DeploymentEngine.Result result = engine.deploy(request("""
            {"mode": "Incremental", "parameters": {"vnet": {"value": "vnet1"}}, "template": {
              "parameters": {"vnet": {"type": "string"}},
              "resources": [{"type": "Microsoft.Network/virtualNetworks", "apiVersion": "2023-05-01",
                "name": "[parameters('vnet')]", "location": "eastus",
                "resources": [{"type": "subnets", "apiVersion": "2023-05-01", "name": "default",
                  "dependsOn": ["[parameters('vnet')]"], "properties": {"addressPrefix": "10.0.1.0/24"}}]}]}}
            """));

        assertEquals("Succeeded", result.provisioningState(), String.valueOf(result.error()));
        assertEquals(List.of(RG_ID + "/providers/Microsoft.Network/virtualNetworks/vnet1",
            RG_ID + "/providers/Microsoft.Network/virtualNetworks/vnet1/subnets/default"),
            sent.stream().map(Sent::path).toList());
        assertFalse(sent.get(0).body().has("resources"), "nested children are not sent with the parent");
    }

    private static final String NESTED_TEMPLATE = """
        {"mode": "Incremental", "parameters": {"prefix": {"value": "[literal"}}, "template": {
          "parameters": {"prefix": {"type": "string"}},
          "variables": {"suffix": "[concat(parameters('prefix'), '-v')]"},
          "resources": [
            {"type": "Microsoft.Resources/deployments", "apiVersion": "2022-09-01", "name": "inner",
             "resourceGroup": "rg2",
             "properties": {"mode": "Incremental", "expressionEvaluationOptions": {"scope": "inner"},
               "parameters": {"name": {"value": "[variables('suffix')]"}},
               "template": {"parameters": {"name": {"type": "string"}},
                            "resources": [{"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01",
                                           "name": "[parameters('name')]"}]}}},
            {"type": "Microsoft.Resources/deployments", "apiVersion": "2022-09-01", "name": "outer",
             "properties": {"mode": "Incremental",
               "template": {"resources": [{"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01",
                                           "name": "[variables('suffix')]"}]}}}]}}
        """;

    @Test
    void anInnerNestedDeploymentIsSentAsWrittenToItsTargetResourceGroup() throws Exception {
        DeploymentEngine.Result result = engine.deploy(request(NESTED_TEMPLATE));

        assertEquals("Succeeded", result.provisioningState(), String.valueOf(result.error()));
        Sent inner = sent.get(0);
        assertEquals("/subscriptions/" + SUB + "/resourceGroups/rg2/providers/Microsoft.Resources/deployments/inner",
            inner.path());
        assertEquals("2022-09-01", inner.apiVersion());
        assertFalse(inner.body().has("resourceGroup"), "the target is in the id, not the body");
        assertEquals("[parameters('name')]",
            inner.body().path("properties").path("template").path("resources").get(0).path("name").asText(),
            "an inner template is not evaluated by the parent");
        assertEquals("[literal-v", inner.body().path("properties").path("parameters").path("name").path("value").asText());
    }

    @Test
    void anOuterNestedDeploymentCarriesTheParentsValuesEscaped() throws Exception {
        engine.deploy(request(NESTED_TEMPLATE));

        Sent outer = sent.stream().filter(request -> request.path().endsWith("/deployments/outer")).findFirst()
            .orElseThrow();
        assertEquals(RG_ID + "/providers/Microsoft.Resources/deployments/outer", outer.path());
        JsonNode template = outer.body().path("properties").path("template");
        assertEquals("[[literal", template.path("parameters").path("prefix").path("defaultValue").asText());
        assertEquals("[[literal-v", template.path("variables").path("suffix").asText());
        assertEquals("[variables('suffix')]", template.path("resources").get(0).path("name").asText());
    }

    @Test
    void validateChecksEverythingAndSendsNothing() throws Exception {
        List<String> ids = engine.validate(request(STORAGE_TEMPLATE));

        assertEquals(List.of(SA_ID, CONTAINER_ID), ids);
        assertTrue(sent.isEmpty());
    }

    @Test
    void completeModeAndLinkedTemplatesAreRefused() {
        assertEquals("DeploymentModeNotSupported", assertThrows(TemplateException.class,
            () -> engine.validate(request("{\"mode\": \"Complete\", \"template\": {}}"))).code());
        assertEquals("InvalidTemplate", assertThrows(TemplateException.class,
            () -> engine.validate(request("{\"mode\": \"Incremental\", \"templateLink\": {\"uri\": \"x\"}}"))).code());
        assertEquals("InvalidTemplate", assertThrows(TemplateException.class,
            () -> engine.validate(request("{\"template\": {}}"))).code());
    }

    @Test
    void parameterValuesAreChecked() {
        String template = "\"template\": {\"parameters\": {\"sku\": {\"type\": \"string\", "
            + "\"allowedValues\": [\"Standard_LRS\", \"Premium_LRS\"]}}}";
        TemplateException missing = assertThrows(TemplateException.class,
            () -> engine.validate(request("{\"mode\": \"Incremental\", " + template + "}")));
        assertTrue(missing.getMessage().contains("The value for the template parameter 'sku' is not provided"),
            missing.getMessage());
        TemplateException notAllowed = assertThrows(TemplateException.class, () -> engine.validate(request(
            "{\"mode\": \"Incremental\", \"parameters\": {\"sku\": {\"value\": \"Basic\"}}, " + template + "}")));
        assertTrue(notAllowed.getMessage().contains("The parameter value is not part of the allowed values"),
            notAllowed.getMessage());
        assertThrows(TemplateException.class, () -> engine.validate(request(
            "{\"mode\": \"Incremental\", \"parameters\": {\"sku\": {\"value\": 3}}, " + template + "}")));
        assertThrows(TemplateException.class, () -> engine.validate(request(
            "{\"mode\": \"Incremental\", \"parameters\": {\"other\": {\"value\": \"x\"}}, " + template + "}")));
    }

    @Test
    void nestedCopyLoopsThatExpandPastTheTemplateBudgetAreRefusedBeforeTheyAreBuilt() {
        TemplateException refused = assertThrows(TemplateException.class, () -> engine.validate(request("""
            {"mode": "Incremental", "template": {
              "variables": {"huge": {"copy": [{"name": "a", "count": 800, "input": {
                "copy": [{"name": "b", "count": 800, "input": {
                  "copy": [{"name": "c", "count": 800, "input": "[copyIndex('c')]"}]}}]}}]}},
              "resources": [{"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01",
                "name": "[concat('sa', length(variables('huge').a))]", "location": "eastus"}]}}
            """)));

        assertEquals("InvalidTemplate", refused.code());
        assertTrue(refused.getMessage().contains("expand to more than 640000 values"), refused.getMessage());
    }

    @Test
    void aReferenceInsideAPropertyLoopIsADependencyOfEveryIteration() throws Exception {
        DeploymentEngine.Result result = engine.deploy(request("""
            {"mode": "Incremental", "template": {
              "variables": {"names": ["sa1", "sa2"]},
              "resources": [
                {"type": "Microsoft.Web/sites", "apiVersion": "2022-03-01", "name": "app", "location": "eastus",
                 "properties": {"copy": [{"name": "slots", "count": 2, "input": {
                   "state": "[reference(variables('names')[copyIndex('slots')]).provisioningState]"}}]}},
                {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01", "name": "sa1",
                 "location": "eastus"},
                {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01", "name": "sa2",
                 "location": "eastus"}]}}
            """));

        assertEquals("Succeeded", result.provisioningState(), String.valueOf(result.error()));
        List<String> puts = sent.stream().filter(request -> request.method().equals("PUT")).map(Sent::path).toList();
        assertEquals(List.of(SA_ID, RG_ID + "/providers/Microsoft.Storage/storageAccounts/sa2",
            RG_ID + "/providers/Microsoft.Web/sites/app"), puts);
        JsonNode app = sent.stream().filter(request -> request.path().endsWith("/sites/app")).findFirst()
            .orElseThrow().body();
        assertEquals("[{\"state\":\"Succeeded\"},{\"state\":\"Succeeded\"}]",
            app.path("properties").path("slots").toString());
    }

    @Test
    void anExtensionResourceIsCreatedUnderTheResourceItsScopeNames() throws Exception {
        DeploymentEngine.Result result = engine.deploy(request("""
            {"mode": "Incremental", "template": {"resources": [
              {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01", "name": "sa1",
               "location": "eastus"},
              {"type": "Microsoft.Authorization/locks", "apiVersion": "2020-05-01", "name": "byName",
               "scope": "Microsoft.Storage/storageAccounts/sa1", "dependsOn": ["sa1"],
               "properties": {"level": "CanNotDelete"}},
              {"type": "Microsoft.Authorization/locks", "apiVersion": "2020-05-01", "name": "byId",
               "scope": "[resourceId('Microsoft.Storage/storageAccounts', 'sa1')]", "dependsOn": ["sa1"],
               "properties": {"level": "ReadOnly"}}]}}
            """));

        assertEquals("Succeeded", result.provisioningState(), String.valueOf(result.error()));
        assertEquals(List.of(SA_ID, SA_ID + "/providers/Microsoft.Authorization/locks/byName",
            SA_ID + "/providers/Microsoft.Authorization/locks/byId"), sent.stream().map(Sent::path).toList());
        assertFalse(sent.get(1).body().has("scope"), "the scope is the path, not a property");
        assertEquals("InvalidTemplate", assertThrows(TemplateException.class, () -> engine.validate(request("""
            {"mode": "Incremental", "template": {"resources": [
              {"type": "Microsoft.Authorization/locks", "apiVersion": "2020-05-01", "name": "lock",
               "scope": "Microsoft.Storage/storageAccounts"}]}}
            """))).code());
    }

    @Test
    void aListFunctionNamingATemplateResourcePostsToItsId() throws Exception {
        DeploymentEngine.Result result = engine.deploy(request("""
            {"mode": "Incremental", "template": {"resources": [
              {"type": "Microsoft.Storage/storageAccounts", "apiVersion": "2023-01-01", "name": "sa1",
               "location": "eastus"}],
              "outputs": {"keys": {"type": "string", "value": "[listKeys('sa1', '2023-01-01').id]"}}}}
            """));

        assertEquals("Succeeded", result.provisioningState(), String.valueOf(result.error()));
        Sent post = sent.get(sent.size() - 1);
        assertEquals("POST", post.method());
        assertEquals(SA_ID + "/listKeys", post.path());
        assertEquals("2023-01-01", post.apiVersion());
    }

    private static String linkedParent(String relativePath) {
        return """
            {"mode": "Incremental", "templateLink": {"uri": "https://store.example/t/main.json?sv=1&sig=SECRET"},
             "template": {"resources": [{"type": "Microsoft.Resources/deployments", "apiVersion": "2022-09-01",
               "name": "child", "properties": {"mode": "Incremental", "templateLink": {"relativePath": "%s"}}}]}}
            """.formatted(relativePath);
    }

    @Test
    void aRelativePathKeepsTheParentsQueryOnTheParentsHost() throws Exception {
        DeploymentEngine.Result result = engine.deploy(request(linkedParent("children/child.json")));

        assertEquals("Succeeded", result.provisioningState(), String.valueOf(result.error()));
        assertEquals("https://store.example/t/children/child.json?sv=1&sig=SECRET",
            sent.getFirst().body().path("properties").path("templateLink").path("uri").asText());
    }

    @Test
    void aRelativePathThatLeavesTheParentsHostIsRefusedAndSendsNoToken() throws Exception {
        for (String escape : new String[] {"//attacker.example/child.json", "https://attacker.example/child.json",
                "http://store.example/t/child.json"}) {
            sent.clear();
            DeploymentEngine.Result result = engine.deploy(request(linkedParent(escape)));

            assertEquals("Failed", result.provisioningState(), escape);
            assertEquals("InvalidTemplate", result.error().path("details").get(0).path("code").asText(), escape);
            assertTrue(sent.isEmpty(), escape + " sent " + sent);
        }
    }
}
