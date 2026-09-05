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
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility test for the Azure Policy control plane (Microsoft.Authorization policy definitions,
 * set definitions, assignments and exemptions) exposed by floci-az.
 *
 * <p>Mirrors {@link ManagedIdentityCompatibilityTest}: the ARM management plane is driven with a raw
 * {@link HttpClient} against the real REST wire protocol (policy spec 2025-03-01, exemptions
 * 2022-07-01-preview), which is what every Azure SDK and the Azure CLI emit.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Azure Policy Compatibility")
class PolicyCompatibilityTest {

    private static final String BASE =
            System.getenv().getOrDefault("FLOCI_AZ_ENDPOINT", "http://localhost:4577");
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String RG = "policy-rg-" + SUFFIX;
    private static final String DEFINITION = "policy-def-" + SUFFIX;
    private static final String SET_DEFINITION = "policy-set-" + SUFFIX;
    private static final String ASSIGNMENT = "policy-assign-" + SUFFIX;
    private static final String EXEMPTION = "policy-exempt-" + SUFFIX;

    private static final String POLICY_API = "2025-03-01";
    private static final String EXEMPTION_API = "2022-07-01-preview";
    private static final String RG_API = "2021-04-01";

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void setup() {
        EmulatorConfig.assumeEmulatorRunning();
    }

    @Test
    @Order(1)
    void createDefinition_isCustomWithServerStamps() throws Exception {
        assertOk(put(resourceGroupUrl(), "{\"location\":\"eastus\"}"), "create resource group");

        HttpResponse<String> resp = put(definitionUrl(), """
                {"properties":{"displayName":"Compat allowed locations","mode":"All",
                 "policyRule":{"if":{"field":"location","notIn":["eastus"]},"then":{"effect":"deny"}}}}""");
        assertEquals(201, resp.statusCode(), "create definition: " + resp.body());

        JsonNode json = mapper.readTree(resp.body());
        assertEquals(DEFINITION, json.get("name").asText());
        assertEquals("Microsoft.Authorization/policyDefinitions", json.get("type").asText());
        assertEquals(definitionId(), json.get("id").asText());
        JsonNode props = json.get("properties");
        assertEquals("Custom", props.get("policyType").asText());
        assertEquals("All", props.get("mode").asText());
        assertEquals("deny", props.get("policyRule").get("then").get("effect").asText());
        assertNotNull(props.get("metadata").get("createdOn"), "metadata.createdOn is stamped server-side");
    }

    @Test
    @Order(2)
    void createSetDefinition_generatesReferenceIds() throws Exception {
        HttpResponse<String> resp = put(setDefinitionUrl(), """
                {"properties":{"displayName":"Compat initiative",
                 "policyDefinitions":[{"policyDefinitionId":"%s"}]}}""".formatted(definitionId()));
        assertEquals(201, resp.statusCode(), "create set definition: " + resp.body());

        JsonNode reference = mapper.readTree(resp.body()).get("properties").get("policyDefinitions").get(0);
        assertEquals(definitionId(), reference.get("policyDefinitionId").asText());
        assertFalse(reference.get("policyDefinitionReferenceId").asText().isBlank());
    }

    @Test
    @Order(3)
    void createAssignment_populatesScopeAndIdentity() throws Exception {
        HttpResponse<String> resp = put(assignmentUrl(), """
                {"location":"eastus","identity":{"type":"SystemAssigned"},
                 "properties":{"displayName":"Compat assignment","policyDefinitionId":"%s"}}""".formatted(definitionId()));
        assertEquals(201, resp.statusCode(), "create assignment: " + resp.body());

        JsonNode json = mapper.readTree(resp.body());
        assertEquals("Microsoft.Authorization/policyAssignments", json.get("type").asText());
        assertEquals("/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG,
                json.get("properties").get("scope").asText());
        assertEquals("Default", json.get("properties").get("enforcementMode").asText());
        assertEquals("SystemAssigned", json.get("identity").get("type").asText());
        assertGuid(json.get("identity").get("principalId").asText(), "identity.principalId");
        assertGuid(json.get("properties").get("instanceId").asText(), "properties.instanceId");
    }

    @Test
    @Order(4)
    void listAssignments_forResourceGroupAndSubscription() throws Exception {
        HttpResponse<String> rg = get(assignmentsUrl(resourceGroupScope()) + "&$filter=atExactScope()");
        assertOk(rg, "list resource group assignments");
        assertTrue(contains(rg.body(), ASSIGNMENT), "resource-group listing names the assignment");

        HttpResponse<String> sub = get(assignmentsUrl("/subscriptions/" + SUBSCRIPTION));
        assertOk(sub, "list subscription assignments");
        assertTrue(contains(sub.body(), ASSIGNMENT), "subscription listing includes descendants by default");
    }

    @Test
    @Order(5)
    void createExemption_forTheAssignment() throws Exception {
        HttpResponse<String> resp = put(exemptionUrl(), """
                {"properties":{"policyAssignmentId":"%s","exemptionCategory":"Waiver",
                 "displayName":"Compat exemption"}}""".formatted(assignmentId()));
        assertEquals(201, resp.statusCode(), "create exemption: " + resp.body());

        JsonNode props = mapper.readTree(resp.body()).get("properties");
        assertEquals(assignmentId(), props.get("policyAssignmentId").asText());
        assertEquals("Waiver", props.get("exemptionCategory").asText());
        assertEquals("Default", props.get("assignmentScopeValidation").asText());
    }

    @Test
    @Order(6)
    void deleteDefinitionInUse_isRejected() throws Exception {
        HttpResponse<String> resp = delete(definitionUrl());
        assertEquals(400, resp.statusCode(), "delete referenced definition: " + resp.body());
        assertEquals("InvalidDeletePolicyDefinitionRequest",
                mapper.readTree(resp.body()).get("error").get("code").asText());
    }

    @Test
    @Order(7)
    void deleteEverything_inDependencyOrder() throws Exception {
        HttpResponse<String> assignment = delete(assignmentUrl());
        assertOk(assignment, "delete assignment");
        assertEquals(ASSIGNMENT, mapper.readTree(assignment.body()).get("name").asText(),
                "assignment delete returns the deleted resource");

        // The exemption went with its assignment.
        assertEquals(404, get(exemptionUrl()).statusCode(), "exemption cascades with its assignment");

        assertOk(delete(setDefinitionUrl()), "delete set definition");
        assertOk(delete(definitionUrl()), "delete definition");

        HttpResponse<String> gone = get(definitionUrl());
        assertEquals(404, gone.statusCode());
        assertEquals("PolicyDefinitionNotFound", mapper.readTree(gone.body()).get("error").get("code").asText());

        assertOk(delete(resourceGroupUrl()), "delete resource group");
    }

    // ── URLs ────────────────────────────────────────────────────────────────────

    private static String resourceGroupScope() {
        return "/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG;
    }

    private static String resourceGroupUrl() {
        return BASE + resourceGroupScope() + "?api-version=" + RG_API;
    }

    private static String definitionId() {
        return "/subscriptions/" + SUBSCRIPTION + "/providers/Microsoft.Authorization/policyDefinitions/" + DEFINITION;
    }

    private static String definitionUrl() {
        return BASE + definitionId() + "?api-version=" + POLICY_API;
    }

    private static String setDefinitionUrl() {
        return BASE + "/subscriptions/" + SUBSCRIPTION
                + "/providers/Microsoft.Authorization/policySetDefinitions/" + SET_DEFINITION
                + "?api-version=" + POLICY_API;
    }

    private static String assignmentId() {
        return resourceGroupScope() + "/providers/Microsoft.Authorization/policyAssignments/" + ASSIGNMENT;
    }

    private static String assignmentUrl() {
        return BASE + assignmentId() + "?api-version=" + POLICY_API;
    }

    private static String assignmentsUrl(String scope) {
        return BASE + scope + "/providers/Microsoft.Authorization/policyAssignments?api-version=" + POLICY_API;
    }

    private static String exemptionUrl() {
        return BASE + resourceGroupScope() + "/providers/Microsoft.Authorization/policyExemptions/" + EXEMPTION
                + "?api-version=" + EXEMPTION_API;
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────────

    private static HttpResponse<String> put(String url, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    private static HttpResponse<String> delete(String url) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).DELETE());
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertOk(HttpResponse<String> resp, String action) {
        assertTrue(resp.statusCode() >= 200 && resp.statusCode() < 300,
                action + " failed with " + resp.statusCode() + ": " + resp.body());
    }

    private static void assertGuid(String value, String field) {
        assertTrue(value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                field + " must be a GUID, got " + value);
    }

    private static boolean contains(String listBody, String name) throws Exception {
        for (JsonNode item : mapper.readTree(listBody).get("value")) {
            if (name.equals(item.get("name").asText())) {
                return true;
            }
        }
        return false;
    }
}
