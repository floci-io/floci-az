package io.floci.az.compat;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.exception.ManagementException;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.resources.ResourceManager;
import com.azure.resourcemanager.resources.fluent.PolicyClient;
import com.azure.resourcemanager.resources.fluent.models.PolicyAssignmentInner;
import com.azure.resourcemanager.resources.fluent.models.PolicyDefinitionInner;
import com.azure.resourcemanager.resources.fluent.models.PolicyExemptionInner;
import com.azure.resourcemanager.resources.fluent.models.PolicySetDefinitionInner;
import com.azure.resourcemanager.resources.fluent.models.ResourceGroupInner;
import com.azure.resourcemanager.resources.models.EnforcementMode;
import com.azure.resourcemanager.resources.models.ExemptionCategory;
import com.azure.resourcemanager.resources.models.Identity;
import com.azure.resourcemanager.resources.models.PolicyAssignmentUpdate;
import com.azure.resourcemanager.resources.models.PolicyDefinitionReference;
import com.azure.resourcemanager.resources.models.PolicyType;
import com.azure.resourcemanager.resources.models.ResourceIdentityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility coverage for the Azure Policy control plane using Microsoft's generated
 * {@link PolicyClient} from {@code azure-resourcemanager-resources}.
 *
 * <p>Sibling of {@link PolicyCompatibilityTest}, which drives the same lifecycle over raw REST. This
 * one checks that the SDK's serialisation, scope handling, paging and error mapping work against
 * floci-az the way they do against Azure.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Azure Policy Java SDK Compatibility")
class PolicyClientCompatibilityTest {

    private static final String ENDPOINT = EmulatorConfig.httpBase();
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String RG = "policy-sdk-rg-" + SUFFIX;
    private static final String DEFINITION = "policy-sdk-def-" + SUFFIX;
    private static final String SET_DEFINITION = "policy-sdk-set-" + SUFFIX;
    private static final String ASSIGNMENT = "policy-sdk-assign-" + SUFFIX;
    private static final String EXEMPTION = "policy-sdk-exempt-" + SUFFIX;

    /** Scopes are passed to the SDK the way its samples do, without a leading slash. */
    private static final String RG_SCOPE = "subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RG;

    private static ResourceManager manager;
    private static PolicyClient policy;

    @BeforeAll
    static void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        Map<String, String> endpoints = new HashMap<>(AzureEnvironment.AZURE.getEndpoints());
        endpoints.put("resourceManagerEndpointUrl", ENDPOINT + "/");
        endpoints.put("managementEndpointUrl", ENDPOINT + "/");
        AzureProfile profile = new AzureProfile(TENANT, SUBSCRIPTION, new AzureEnvironment(endpoints));
        HttpPipeline pipeline = new HttpPipelineBuilder()
                .httpClient(HttpClient.createDefault())
                .build();
        manager = ResourceManager.authenticate(pipeline, profile).withSubscription(SUBSCRIPTION);
        policy = manager.policyClient();
    }

    @Test
    @Order(1)
    void createDefinition_isCustomWithServerStamps() {
        manager.serviceClient().getResourceGroups().createOrUpdate(RG, new ResourceGroupInner().withLocation("eastus"));

        PolicyDefinitionInner definition = policy.getPolicyDefinitions().createOrUpdate(DEFINITION,
                new PolicyDefinitionInner()
                        .withDisplayName("Compat allowed locations")
                        .withMode("All")
                        .withPolicyRule(Map.of(
                                "if", Map.of("field", "location", "notIn", List.of("eastus")),
                                "then", Map.of("effect", "deny"))));

        assertEquals(DEFINITION, definition.name());
        assertEquals("Microsoft.Authorization/policyDefinitions", definition.type());
        assertEquals(definitionId(), definition.id());
        assertEquals(PolicyType.CUSTOM, definition.policyType());
        assertEquals("All", definition.mode());
        Map<?, ?> metadata = assertInstanceOf(Map.class, definition.metadata());
        assertNotNull(metadata.get("createdOn"), "metadata.createdOn is stamped server-side");

        PolicyDefinitionInner fetched = policy.getPolicyDefinitions().get(DEFINITION);
        assertEquals("deny", ((Map<?, ?>) ((Map<?, ?>) fetched.policyRule()).get("then")).get("effect"));
        assertTrue(policy.getPolicyDefinitions().list().stream().anyMatch(d -> DEFINITION.equals(d.name())),
                "subscription listing names the definition");
    }

    @Test
    @Order(2)
    void createSetDefinition_generatesReferenceIds() {
        PolicySetDefinitionInner setDefinition = policy.getPolicySetDefinitions().createOrUpdate(SET_DEFINITION,
                new PolicySetDefinitionInner()
                        .withDisplayName("Compat initiative")
                        .withPolicyDefinitions(List.of(
                                new PolicyDefinitionReference().withPolicyDefinitionId(definitionId()))));

        assertEquals("Microsoft.Authorization/policySetDefinitions", setDefinition.type());
        assertEquals(PolicyType.CUSTOM, setDefinition.policyType());
        PolicyDefinitionReference reference = setDefinition.policyDefinitions().get(0);
        assertEquals(definitionId(), reference.policyDefinitionId());
        assertFalse(reference.policyDefinitionReferenceId().isBlank(), "reference id is generated server-side");
    }

    @Test
    @Order(3)
    void createAssignment_populatesScopeAndIdentity() {
        PolicyAssignmentInner assignment = policy.getPolicyAssignments().create(RG_SCOPE, ASSIGNMENT,
                new PolicyAssignmentInner()
                        .withLocation("eastus")
                        .withIdentity(new Identity().withType(ResourceIdentityType.SYSTEM_ASSIGNED))
                        .withDisplayName("Compat assignment")
                        .withPolicyDefinitionId(definitionId()));

        assertEquals("Microsoft.Authorization/policyAssignments", assignment.type());
        assertEquals(assignmentId(), assignment.id());
        assertEquals("/" + RG_SCOPE, assignment.scope());
        assertEquals(EnforcementMode.DEFAULT, assignment.enforcementMode());
        assertEquals(ResourceIdentityType.SYSTEM_ASSIGNED, assignment.identity().type());
        assertGuid(assignment.identity().principalId(), "identity.principalId");
        assertGuid(assignment.instanceId(), "properties.instanceId");
    }

    @Test
    @Order(4)
    void updateAssignment_patchesLocationAndKeepsTheRest() {
        PolicyAssignmentInner updated = policy.getPolicyAssignments().update(RG_SCOPE, ASSIGNMENT,
                new PolicyAssignmentUpdate().withLocation("westus"));

        assertEquals("westus", updated.location());
        assertEquals(definitionId(), updated.policyDefinitionId());
        assertEquals("Compat assignment", updated.displayName());
        assertEquals(ResourceIdentityType.SYSTEM_ASSIGNED, updated.identity().type());
    }

    @Test
    @Order(5)
    void listAssignments_forResourceGroupAndSubscription() {
        assertTrue(policy.getPolicyAssignments().listByResourceGroup(RG).stream()
                        .anyMatch(a -> ASSIGNMENT.equals(a.name())),
                "resource-group listing names the assignment");
        assertTrue(policy.getPolicyAssignments().list().stream().anyMatch(a -> ASSIGNMENT.equals(a.name())),
                "subscription listing includes descendants by default");
        assertEquals(ASSIGNMENT, policy.getPolicyAssignments().getById(assignmentId()).name());
    }

    @Test
    @Order(6)
    void createExemption_forTheAssignment() {
        PolicyExemptionInner exemption = policy.getPolicyExemptions().createOrUpdate(RG_SCOPE, EXEMPTION,
                new PolicyExemptionInner()
                        .withPolicyAssignmentId(assignmentId())
                        .withExemptionCategory(ExemptionCategory.WAIVER)
                        .withDisplayName("Compat exemption"));

        assertEquals("Microsoft.Authorization/policyExemptions", exemption.type());
        assertEquals(assignmentId(), exemption.policyAssignmentId());
        assertEquals(ExemptionCategory.WAIVER, exemption.exemptionCategory());
        assertTrue(policy.getPolicyExemptions().listByResourceGroup(RG).stream()
                        .anyMatch(e -> EXEMPTION.equals(e.name())),
                "resource-group listing names the exemption");
    }

    @Test
    @Order(7)
    void deleteDefinitionInUse_isRejected() {
        ManagementException rejected = assertThrows(ManagementException.class,
                () -> policy.getPolicyDefinitions().delete(DEFINITION));
        assertEquals(400, rejected.getResponse().getStatusCode());
        assertEquals("InvalidDeletePolicyDefinitionRequest", rejected.getValue().getCode());
    }

    @Test
    @Order(8)
    void deleteEverything_inDependencyOrder() {
        PolicyAssignmentInner deleted = policy.getPolicyAssignments().delete(RG_SCOPE, ASSIGNMENT);
        assertEquals(ASSIGNMENT, deleted.name(), "assignment delete returns the deleted resource");

        ManagementException exemptionGone = assertThrows(ManagementException.class,
                () -> policy.getPolicyExemptions().get(RG_SCOPE, EXEMPTION));
        assertEquals(404, exemptionGone.getResponse().getStatusCode(), "exemption cascades with its assignment");

        policy.getPolicySetDefinitions().delete(SET_DEFINITION);
        policy.getPolicyDefinitions().delete(DEFINITION);

        ManagementException definitionGone = assertThrows(ManagementException.class,
                () -> policy.getPolicyDefinitions().get(DEFINITION));
        assertEquals(404, definitionGone.getResponse().getStatusCode());
        assertEquals("PolicyDefinitionNotFound", definitionGone.getValue().getCode());

        manager.serviceClient().getResourceGroups().delete(RG);
    }

    private static String definitionId() {
        return "/subscriptions/" + SUBSCRIPTION + "/providers/Microsoft.Authorization/policyDefinitions/" + DEFINITION;
    }

    private static String assignmentId() {
        return "/" + RG_SCOPE + "/providers/Microsoft.Authorization/policyAssignments/" + ASSIGNMENT;
    }

    private static void assertGuid(String value, String field) {
        assertNotNull(value, field + " must be set");
        assertTrue(value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                field + " must be a GUID, got " + value);
    }
}
