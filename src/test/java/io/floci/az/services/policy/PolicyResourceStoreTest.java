package io.floci.az.services.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisplayName("Policy: resources in the policy backend, keyed by canonical id at every scope")
class PolicyResourceStoreTest {

    private static final String API = "?api-version=2025-03-01";
    private static final String AUTH = "/providers/Microsoft.Authorization";
    private static final String SUB_SCOPE = "/subscriptions/sub-pstore";
    private static final String RG_SCOPE = SUB_SCOPE + "/resourceGroups/RG-Store";
    private static final String MG_SCOPE = "/providers/Microsoft.Management/managementGroups/mg-store";
    private static final String DEFINITIONS = SUB_SCOPE + AUTH + "/policyDefinitions";
    private static final String DENY_ALL = """
            {"properties": {"mode": "All",
                            "policyRule": {"if": {"field": "type", "equals": "Microsoft.Network/networkSecurityGroups"},
                                           "then": {"effect": "deny"}}}}""";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static ValidatableResponse put(String path, String body) {
        return given().contentType("application/json").body(body).when().put(path + API).then();
    }

    @Test
    void aPutThroughOtherCasingUpdatesTheOneAssignment() {
        put(DEFINITIONS + "/cased-def", DENY_ALL).statusCode(201);
        String first = RG_SCOPE + AUTH + "/policyAssignments/Cased-Assign";
        String assignment = "{\"properties\": {\"policyDefinitionId\": \"" + DEFINITIONS + "/cased-def\"}}";
        String instanceId = put(first, assignment).statusCode(201).extract().path("properties.instanceId");

        put("/subscriptions/SUB-PSTORE/resourcegroups/rg-store" + AUTH + "/policyAssignments/cased-assign",
                "{\"properties\": {\"policyDefinitionId\": \"" + DEFINITIONS + "/CASED-DEF\", \"displayName\": \"v2\"}}")
                .statusCode(201)
                .body("id", equalTo(first))
                .body("name", equalTo("Cased-Assign"))
                .body("properties.scope", equalTo(RG_SCOPE))
                .body("properties.displayName", equalTo("v2"))
                .body("properties.instanceId", equalTo(instanceId));

        given().when().get(RG_SCOPE + AUTH + "/policyAssignments" + API)
                .then().statusCode(200).body("value", hasSize(1));
        given().when().delete(SUB_SCOPE + "/resourceGroups/rg-store" + AUTH + "/policyAssignments/CASED-ASSIGN" + API)
                .then().statusCode(200);
        given().when().get(first + API).then().statusCode(404);
    }

    @Test
    void resourcesAtEveryScopeAreStoredUnderCanonicalKeys() {
        put(DEFINITIONS + "/sub-def", DENY_ALL).statusCode(201);
        put(MG_SCOPE + AUTH + "/policyDefinitions/mg-def", DENY_ALL).statusCode(201);
        String definition = "{\"properties\": {\"policyDefinitionId\": \"" + DEFINITIONS + "/sub-def\"}}";
        String resourceScope = RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/sa1";
        put(MG_SCOPE + AUTH + "/policyAssignments/mg-assign", definition).statusCode(201);
        put(SUB_SCOPE + AUTH + "/policyAssignments/sub-assign", definition).statusCode(201);
        put(resourceScope + AUTH + "/policyAssignments/res-assign", definition).statusCode(201);

        StorageBackend<String, StoredObject> backend = storageFactory.create(PolicyStore.STORE);
        List<String> expected = List.of(
                DEFINITIONS + "/sub-def",
                MG_SCOPE + AUTH + "/policyDefinitions/mg-def",
                MG_SCOPE + AUTH + "/policyAssignments/mg-assign",
                SUB_SCOPE + AUTH + "/policyAssignments/sub-assign",
                resourceScope + AUTH + "/policyAssignments/res-assign");
        for (String id : expected) {
            assertTrue(backend.keys().contains(id.toLowerCase()), id);
        }
        assertEquals(expected.size(), backend.keys().size());

        given().when().get(MG_SCOPE + AUTH + "/policyDefinitions" + API)
                .then().statusCode(200).body("value", hasSize(1)).body("value[0].name", equalTo("mg-def"));
        given().when().get(resourceScope + AUTH + "/policyAssignments" + API)
                .then().statusCode(200).body("value.name", hasItems("sub-assign", "res-assign"))
                .body("value", hasSize(2));
    }

    @Test
    void aSetDefinitionIdNeverFindsADefinitionInTheSharedBackend() {
        put(DEFINITIONS + "/member", DENY_ALL).statusCode(201);
        String set = SUB_SCOPE + AUTH + "/policySetDefinitions/the-set";
        put(set, "{\"properties\": {\"policyDefinitions\": [{\"policyDefinitionId\": \"" + DEFINITIONS + "/member\"}]}}")
                .statusCode(201);

        put(SUB_SCOPE + AUTH + "/policySetDefinitions/nested",
                "{\"properties\": {\"policyDefinitions\": [{\"policyDefinitionId\": \"" + set + "\"}]}}")
                .statusCode(404).body("error.code", equalTo("PolicyDefinitionNotFound"));
        given().when().get(DEFINITIONS + "/the-set" + API).then().statusCode(404);
    }

    @Test
    void aRelativeDefinitionReferenceIsNotFoundAndCannotPinTheDefinition() {
        put(DEFINITIONS + "/relative-def", DENY_ALL).statusCode(201);

        put(RG_SCOPE + AUTH + "/policyAssignments/relative-assign",
                "{\"properties\": {\"policyDefinitionId\": \"" + DEFINITIONS.substring(1) + "/relative-def\"}}")
                .statusCode(404).body("error.code", equalTo("PolicyDefinitionNotFound"));
        given().when().delete(DEFINITIONS + "/relative-def" + API).then().statusCode(200);
    }

    @Test
    void anAssignmentAlreadyInTheBackendIsServedListedAndEnforced() throws Exception {
        String definitionId = DEFINITIONS + "/disk-def";
        String assignmentId = RG_SCOPE + AUTH + "/policyAssignments/disk-assign";
        Map<String, Object> rule = MAPPER.readValue(DENY_ALL, Map.class);
        Map<String, Object> definitionProperties = new LinkedHashMap<>(MAPPER.convertValue(rule.get("properties"), Map.class));
        definitionProperties.put("policyType", "Custom");
        write(Map.of("id", definitionId, "name", "disk-def", "type", "Microsoft.Authorization/policyDefinitions",
                "properties", definitionProperties, "_scope", SUB_SCOPE.substring(1).toLowerCase()));
        write(Map.of("id", assignmentId, "name", "disk-assign", "type", "Microsoft.Authorization/policyAssignments",
                "properties", Map.of("policyDefinitionId", definitionId, "scope", RG_SCOPE,
                        "enforcementMode", "Default"),
                "_scope", RG_SCOPE.substring(1).toLowerCase()));

        given().when().get("/subscriptions/SUB-PSTORE/resourceGroups/rg-store" + AUTH + "/policyAssignments/DISK-ASSIGN" + API).then().statusCode(200)
                .body("id", equalTo(assignmentId))
                .body("_scope", nullValue());
        given().when().get(RG_SCOPE + AUTH + "/policyAssignments" + API)
                .then().statusCode(200).body("value", hasSize(1));

        given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put(RG_SCOPE + "?api-version=2021-04-01").then().statusCode(lessThan(300));
        given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put(RG_SCOPE + "/providers/Microsoft.Network/networkSecurityGroups/nsg1?api-version=2023-09-01")
                .then().statusCode(403).body("error.code", equalTo("RequestDisallowedByPolicy"));

        given().when().delete(definitionId + API).then().statusCode(400)
                .body("error.code", equalTo("InvalidDeletePolicyDefinitionRequest"));
    }

    private void write(Map<String, Object> resource) throws Exception {
        String key = String.valueOf(resource.get("id")).toLowerCase();
        storageFactory.create(PolicyStore.STORE).put(key, new StoredObject(key, MAPPER.writeValueAsBytes(resource),
                Map.of(), Instant.now(), "etag"));
    }
}
