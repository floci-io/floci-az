package io.floci.az.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ArmRouter#dispatchInternal} with a body, the primitive the template deployment engine sends each resource
 * through: an internal PUT reaches the same handler a client's does and answers the same JSON, byte for byte once
 * the values each call generates afresh are blanked.
 */
@QuarkusTest
@DisplayName("ArmRouter: internal requests with a body answer as a client's")
class ArmInternalDispatchTest {

    private static final String ACCOUNT = "/subscriptions/sub-internal/resourceGroups/rg-internal/providers/"
        + "Microsoft.Storage/storageAccounts/internalput0001";
    private static final String API = "2023-01-01";
    private static final String BODY =
        "{\"location\":\"eastus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}";

    private static final String VAULT_BODY = "{\"location\":\"eastus\",\"properties\":{\"tenantId\":"
        + "\"00000000-0000-0000-0000-000000000002\",\"sku\":{\"family\":\"A\",\"name\":\"standard\"}}}";
    private static final String SITE_BODY = "{\"location\":\"eastus\",\"kind\":\"functionapp,linux\","
        + "\"properties\":{\"siteConfig\":{\"linuxFxVersion\":\"Python|3.12\"}}}";
    private static final String RG = "/subscriptions/sub-internal/resourceGroups/rg-internal";
    private static final String POLICY_API = "?api-version=2025-03-01";

    /** Values a handler generates on every call, so two equal requests never answer them equally. */
    private static final Set<String> GENERATED = Set.of("etag", "lastModifiedTime", "creationTime");

    @Inject
    ArmRouter router;

    @Inject
    ObjectMapper mapper;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    /** A request as the routing filter would build it for a client at RestAssured's host and port. */
    private static AzureRequest origin() {
        String authority = "localhost:" + RestAssured.port;
        return new AzureRequest("PUT", "arm", "arm", "subscriptions/sub-internal", null, null, Map.of(), Map.of(),
            null, false, "localhost", null, "subscriptions/sub-internal", null, authority);
    }

    private static JsonNode blankGenerated(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<String> names = object.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (GENERATED.contains(name)) {
                    object.put(name, "<generated>");
                } else {
                    blankGenerated(object.get(name));
                }
            }
        } else if (node != null && node.isArray()) {
            node.forEach(ArmInternalDispatchTest::blankGenerated);
        }
        return node;
    }

    @Test
    void anInternalPutOfAStorageAccountAnswersExactlyAsAClientPut() throws Exception {
        // Qualified: RestAssured's Response clashes with jakarta.ws.rs.core.Response, used for the internal answer.
        io.restassured.response.Response client = given().contentType("application/json").body(BODY)
            .when().put(ACCOUNT + "?api-version=" + API);
        String clientJson = mapper.writeValueAsString(blankGenerated(mapper.readTree(client.asString())));
        given().post("/_admin/reset").then().statusCode(204);

        Response internal = router.dispatchInternal("PUT", ACCOUNT, Map.of("api-version", API), Map.of(),
            BODY.getBytes(StandardCharsets.UTF_8), origin());
        String internalJson = mapper.writeValueAsString(
            blankGenerated(mapper.readTree(mapper.writeValueAsBytes(internal.getEntity()))));

        assertEquals(client.statusCode(), internal.getStatus());
        assertEquals(clientJson, internalJson);
        given().when().get(ACCOUNT + "?api-version=" + API).then().statusCode(200)
            .body("location", equalTo("eastus"))
            .body("kind", equalTo("StorageV2"));
    }

    private static Response internalPut(String path, String apiVersion, String body, ArmRouter router) {
        return router.dispatchInternal("PUT", path, Map.of("api-version", apiVersion), Map.of(),
            body.getBytes(StandardCharsets.UTF_8), origin());
    }

    @Test
    void anInternalPutOfAVaultOrASiteReachesItsProviderService() {
        String vault = RG + "/providers/Microsoft.KeyVault/vaults/internalkv0001";
        String site = RG + "/providers/Microsoft.Web/sites/internalsite0001";

        assertEquals(200, internalPut(vault, "2023-07-01", VAULT_BODY, router).getStatus());
        assertEquals(200, internalPut(site, "2023-01-01", SITE_BODY, router).getStatus());

        given().when().get(vault + "?api-version=2023-07-01").then().statusCode(200)
            .body("type", equalTo("Microsoft.KeyVault/vaults"));
        given().when().get(site + "?api-version=2023-01-01").then().statusCode(200)
            .body("type", equalTo("Microsoft.Web/sites"));
    }

    @Test
    void anInternalPutIsRefusedByTheGateWhereAClientPutWouldBe() {
        given().contentType("application/json").body(BODY)
            .when().put("/subscriptions/sub-other/resourceGroups/rg/providers/Microsoft.Storage/storageAccounts/"
                + "internalput0001?api-version=" + API)
            .then().statusCode(200);

        Response taken = internalPut(ACCOUNT, API, BODY, router);

        assertEquals(409, taken.getStatus());
        assertEquals("StorageAccountAlreadyTaken",
            mapper.valueToTree(taken.getEntity()).path("error").path("code").asText());
    }

    @Test
    void anInternalPutIsDeniedByAnAssignedPolicy() {
        String definitions = "/subscriptions/sub-internal/providers/Microsoft.Authorization/policyDefinitions";
        given().contentType("application/json").body("""
                {"properties": {"mode": "All", "policyRule": {
                  "if": {"field": "type", "equals": "Microsoft.Storage/storageAccounts"},
                  "then": {"effect": "deny"}}}}""")
            .when().put(definitions + "/no-storage" + POLICY_API).then().statusCode(201);
        given().contentType("application/json")
            .body("{\"properties\": {\"policyDefinitionId\": \"" + definitions + "/no-storage\"}}")
            .when().put("/subscriptions/sub-internal/providers/Microsoft.Authorization/policyAssignments/no-storage"
                + POLICY_API)
            .then().statusCode(201);

        Response denied = internalPut(ACCOUNT, API, BODY, router);

        assertEquals(403, denied.getStatus());
        assertEquals("RequestDisallowedByPolicy",
            mapper.valueToTree(denied.getEntity()).path("error").path("code").asText());
        given().when().get(ACCOUNT + "?api-version=" + API).then().statusCode(404);
    }

    @Test
    void aBodylessInternalRequestStillWorks() {
        given().contentType("application/json").body(BODY).when().put(ACCOUNT + "?api-version=" + API)
            .then().statusCode(200);

        Response internal = router.dispatchInternal("GET", ACCOUNT, Map.of("api-version", API), origin());

        assertEquals(200, internal.getStatus());
    }
}
