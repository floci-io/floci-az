package io.floci.az.services.authorization;

import io.floci.az.services.entra.EntraStore;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * The role definitions and role assignments the way azurerm, the az CLI and azure-mgmt-authorization use them,
 * against the contract they pin (assignments 2022-04-01, definitions 2022-05-01-preview). Each test names the
 * evidence for what it asserts.
 */
@QuarkusTest
class RbacHandlerTest {

    private static final String SUB = "rbac-sub";
    private static final String SUB_SCOPE = "/subscriptions/" + SUB;
    private static final String RG_SCOPE = SUB_SCOPE + "/resourceGroups/rbac-rg";
    private static final String VAULT_SCOPE = RG_SCOPE + "/providers/Microsoft.KeyVault/vaults/rbac-kv";
    private static final String REGISTRY_SCOPE = RG_SCOPE + "/providers/Microsoft.ContainerRegistry/registries/rbacacr";
    private static final String ASSIGNMENTS = "/providers/Microsoft.Authorization/roleAssignments";
    private static final String DEFINITIONS = "/providers/Microsoft.Authorization/roleDefinitions";
    private static final String ASSIGNMENT_API = "2022-04-01";
    private static final String DEFINITION_API = "2022-05-01-preview";
    private static final String ACR_PULL = "7f951dda-4ed3-4680-a7ca-43fe172d538d";
    private static final String READER = "acdd72a7-3385-48ef-bd42-f606fba81ae7";
    private static final String CONTRIBUTOR = "b24988ac-6180-42a0-ab88-20f7382dd24c";
    private static final String PRINCIPAL = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER_PRINCIPAL = "66666666-7777-8888-9999-000000000000";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    // ── Role definitions ─────────────────────────────────────────────────────────

    /** azurerm and the az CLI look a role up by name and need exactly one match; Azure matches the name ignoring case. */
    @Test
    void aRoleIsFoundByNameIgnoringCaseWithItsSubscriptionScopedId() {
        listDefinitions(SUB_SCOPE, "roleName eq 'acrpull'")
                .body("value", hasSize(1))
                .body("value[0].id", equalTo(SUB_SCOPE + DEFINITIONS + "/" + ACR_PULL))
                .body("value[0].name", equalTo(ACR_PULL))
                .body("value[0].type", equalTo("Microsoft.Authorization/roleDefinitions"))
                .body("value[0].properties.roleName", equalTo("AcrPull"))
                .body("value[0].properties.type", equalTo("BuiltInRole"))
                .body("value[0].properties.assignableScopes", equalTo(List.of("/")))
                .body("value[0].properties.permissions[0].actions",
                        hasItem("Microsoft.ContainerRegistry/registries/pull/read"));
    }

    /** The az CLI compares a role's id with an assignment's roleDefinitionId exactly, at any scope it lists from. */
    @Test
    void aRoleListedAtAResourceKeepsTheSubscriptionForm() {
        listDefinitions(REGISTRY_SCOPE, "roleName eq 'AcrPull'")
                .body("value", hasSize(1))
                .body("value[0].id", equalTo(SUB_SCOPE + DEFINITIONS + "/" + ACR_PULL));
    }

    @Test
    void theUnfilteredListHoldsEveryBuiltInAndTheTypeFilterApplies() {
        listDefinitions(SUB_SCOPE, null).body("value.size()", equalTo(RoleDefinitionCatalog.all().size()));
        listDefinitions(SUB_SCOPE, "type eq 'BuiltInRole'").body("value.size()", equalTo(RoleDefinitionCatalog.all().size()));
        listDefinitions(SUB_SCOPE, "type eq 'CustomRole'").body("value", empty());
    }

    /** A definition is read at any scope and by its tenant form, which azurerm sends as {@code //providers/...}. */
    @Test
    void aDefinitionIsReadAtAnyScopeAndAtTheTenantRoot() {
        getDefinition(RG_SCOPE + DEFINITIONS + "/" + READER, DEFINITION_API)
                .statusCode(200)
                .body("id", equalTo(SUB_SCOPE + DEFINITIONS + "/" + READER))
                .body("properties.roleName", equalTo("Reader"))
                .body("properties.permissions[0].actions", equalTo(List.of("*/read")));
        getDefinition(DEFINITIONS + "/" + READER, DEFINITION_API)
                .statusCode(200)
                .body("id", equalTo(DEFINITIONS + "/" + READER));
    }

    /** {@code condition} and {@code conditionVersion} are Permission properties only from 2022-05-01-preview. */
    @Test
    void permissionConditionsAppearOnlyFromThePreviewVersion() {
        getDefinition(SUB_SCOPE + DEFINITIONS + "/" + READER, "2022-04-01")
                .statusCode(200)
                .body("properties.permissions[0]", not(hasKey("condition")));
        getDefinition(SUB_SCOPE + DEFINITIONS + "/" + READER, DEFINITION_API)
                .statusCode(200)
                .body("properties.permissions[0]", hasKey("condition"))
                .body("properties.permissions[0].condition", nullValue());
    }

    @Test
    void anUnknownDefinitionIsNotFoundAndCustomRolesAreRefused() {
        getDefinition(SUB_SCOPE + DEFINITIONS + "/" + UUID.randomUUID(), DEFINITION_API).statusCode(404);
        given().contentType("application/json").queryParam("api-version", DEFINITION_API)
                .body("{\"properties\":{\"roleName\":\"custom\"}}")
                .when().put(SUB_SCOPE + DEFINITIONS + "/" + UUID.randomUUID())
                .then().statusCode(501);
    }

    // ── Role assignments ─────────────────────────────────────────────────────────

    /** azurerm's create: 404 before, PUT 201 with an id, GETs that answer 200, the role read back, delete 200 then 204. */
    @Test
    void theAzurermSequence() {
        String id = RG_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID();
        getAssignment(id).statusCode(404);

        String roleId = SUB_SCOPE + DEFINITIONS + "/" + READER;
        putAssignment(id, roleId, PRINCIPAL, "\"principalType\":\"ServicePrincipal\",\"description\":\"\"")
                .statusCode(201)
                .body("id", equalTo(id))
                .body("type", equalTo("Microsoft.Authorization/roleAssignments"))
                .body("properties.roleDefinitionId", equalTo(roleId))
                .body("properties.principalId", equalTo(PRINCIPAL))
                .body("properties.principalType", equalTo("ServicePrincipal"))
                .body("properties.scope", equalTo(RG_SCOPE))
                .body("properties.description", equalTo(""))
                .body("properties.createdOn", notNullValue());
        for (int i = 0; i < 5; i++) {
            getAssignment(id).statusCode(200).body("properties.roleDefinitionId", equalTo(roleId));
        }
        getDefinition(roleId, DEFINITION_API).statusCode(200).body("properties.roleName", equalTo("Reader"));

        putAssignment(id, roleId, PRINCIPAL, "\"principalType\":\"ServicePrincipal\",\"description\":\"\"")
                .statusCode(200);
        given().queryParam("api-version", ASSIGNMENT_API).when().delete(id)
                .then().statusCode(200).body("id", equalTo(id));
        given().queryParam("api-version", ASSIGNMENT_API).when().delete(id).then().statusCode(204);
        getAssignment(id).statusCode(404);
    }

    /** Azure writes every property of an assignment, the absent ones as explicit nulls (recorded responses). */
    @Test
    void absentOptionalPropertiesAreExplicitNulls() {
        String id = SUB_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID();
        putAssignment(id, SUB_SCOPE + DEFINITIONS + "/" + READER, PRINCIPAL, null).statusCode(201);
        getAssignment(id).statusCode(200)
                .body("properties", hasKey("condition"))
                .body("properties.condition", nullValue())
                .body("properties", hasKey("conditionVersion"))
                .body("properties", hasKey("delegatedManagedIdentityResourceId"))
                .body("properties", hasKey("description"))
                .body("properties.description", nullValue());
    }

    /** The same principal, role and scope under a second name: 409 RoleAssignmentExists, recorded by the az CLI tests. */
    @Test
    void theSameGrantUnderAnotherNameIsAConflict() {
        String roleId = SUB_SCOPE + DEFINITIONS + "/" + READER;
        putAssignment(RG_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID(), roleId, PRINCIPAL, null).statusCode(201);
        putAssignment(RG_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID(), DEFINITIONS + "/" + READER, PRINCIPAL, null)
                .statusCode(409)
                .body("error.code", equalTo("RoleAssignmentExists"));
    }

    /** A name is global, and an assignment's principal and scope cannot change (RBAC troubleshooting docs). */
    @Test
    void aNameCannotMoveToAnotherPrincipalOrScope() {
        String name = UUID.randomUUID().toString();
        String roleId = SUB_SCOPE + DEFINITIONS + "/" + READER;
        putAssignment(RG_SCOPE + ASSIGNMENTS + "/" + name, roleId, PRINCIPAL, null).statusCode(201);
        putAssignment(RG_SCOPE + ASSIGNMENTS + "/" + name, roleId, OTHER_PRINCIPAL, null)
                .statusCode(400)
                .body("error.code", equalTo("RoleAssignmentUpdateNotPermitted"));
        putAssignment(SUB_SCOPE + ASSIGNMENTS + "/" + name, roleId, PRINCIPAL, null)
                .statusCode(400)
                .body("error.code", equalTo("RoleAssignmentUpdateNotPermitted"));
    }

    @Test
    void theRoleOfAnAssignmentCanChange() {
        String id = RG_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID();
        putAssignment(id, SUB_SCOPE + DEFINITIONS + "/" + READER, PRINCIPAL, null).statusCode(201);
        putAssignment(id, SUB_SCOPE + DEFINITIONS + "/" + CONTRIBUTOR, PRINCIPAL, null)
                .statusCode(200)
                .body("properties.roleDefinitionId", equalTo(SUB_SCOPE + DEFINITIONS + "/" + CONTRIBUTOR));
    }

    @Test
    void anAssignmentNeedsAKnownRoleAndAPrincipal() {
        String id = RG_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID();
        putAssignment(id, SUB_SCOPE + DEFINITIONS + "/" + UUID.randomUUID(), PRINCIPAL, null)
                .statusCode(400)
                .body("error.code", equalTo("RoleDefinitionDoesNotExist"));
        given().contentType("application/json").queryParam("api-version", ASSIGNMENT_API)
                .body("{\"properties\":{\"roleDefinitionId\":\"" + SUB_SCOPE + DEFINITIONS + "/" + READER + "\"}}")
                .when().put(id)
                .then().statusCode(400);
    }

    /** floci-az never refuses a principal (azurerm retries PrincipalNotFound for 30 minutes); its type stays unknown. */
    @Test
    void anUnknownPrincipalIsAcceptedWithNoType() {
        String id = RG_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID();
        putAssignment(id, SUB_SCOPE + DEFINITIONS + "/" + READER, UUID.randomUUID().toString(), null)
                .statusCode(201)
                .body("properties.principalType", nullValue());
    }

    /** Azure fills an omitted principalType from the directory (recorded: User for a user, ServicePrincipal for an SP). */
    @Test
    void anOmittedPrincipalTypeIsResolvedFromTheDirectory() {
        putAssignment(SUB_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID(), SUB_SCOPE + DEFINITIONS + "/" + READER,
                EntraStore.DEV_USER_OBJECT_ID, null)
                .statusCode(201)
                .body("properties.principalType", equalTo("User"));

        String identity = RG_SCOPE + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/rbac-id";
        String identityPrincipal = given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .queryParam("api-version", "2023-01-31")
                .when().put(identity)
                .then().statusCode(anyOf(equalTo(200), equalTo(201)))
                .extract().path("properties.principalId");
        putAssignment(RG_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID(), SUB_SCOPE + DEFINITIONS + "/" + READER,
                identityPrincipal, null)
                .statusCode(201)
                .body("properties.principalType", equalTo("ServicePrincipal"));
    }

    /**
     * {@code atScope()} lists at or above the scope; no filter lists at, above and below; {@code principalId eq} and
     * {@code assignedTo()} keep one principal at, above and below (RoleAssignments_ListForScope, 2022-04-01).
     */
    @Test
    void theListFiltersFollowTheScopeRules() {
        String roleId = SUB_SCOPE + DEFINITIONS + "/" + READER;
        String atSub = SUB_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID();
        String atRg = RG_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID();
        String atVault = VAULT_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID();
        putAssignment(atSub, roleId, PRINCIPAL, null).statusCode(201);
        putAssignment(atRg, roleId, OTHER_PRINCIPAL, null).statusCode(201);
        putAssignment(atVault, roleId, PRINCIPAL, null).statusCode(201);

        listAssignments(RG_SCOPE, "atScope()").body("value.id", containsInAnyOrder(atSub, atRg));
        listAssignments(RG_SCOPE, null).body("value.id", containsInAnyOrder(atSub, atRg, atVault));
        listAssignments(RG_SCOPE, "principalId eq '" + PRINCIPAL + "'")
                .body("value.id", containsInAnyOrder(atSub, atVault));
        listAssignments(SUB_SCOPE, "assignedTo('" + PRINCIPAL + "')")
                .body("value.id", containsInAnyOrder(atSub, atVault));
        listAssignments(VAULT_SCOPE, "atScope() and assignedTo('" + OTHER_PRINCIPAL + "')")
                .body("value.id", contains(atRg));
    }

    /** A resource group's assignments, and those on its resources, go with it; the subscription's stay. */
    @Test
    void aResourceGroupDeleteTakesItsAssignments() {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .queryParam("api-version", "2021-04-01").when().put(RG_SCOPE)
                .then().statusCode(anyOf(equalTo(200), equalTo(201)));
        String roleId = SUB_SCOPE + DEFINITIONS + "/" + READER;
        String atSub = SUB_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID();
        String atRg = RG_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID();
        String atVault = VAULT_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID();
        putAssignment(atSub, roleId, PRINCIPAL, null).statusCode(201);
        putAssignment(atRg, roleId, PRINCIPAL, null).statusCode(201);
        putAssignment(atVault, roleId, PRINCIPAL, null).statusCode(201);

        given().queryParam("api-version", "2021-04-01").when().delete(RG_SCOPE)
                .then().statusCode(anyOf(equalTo(200), equalTo(202)));

        getAssignment(atSub).statusCode(200);
        getAssignment(atRg).statusCode(404);
        getAssignment(atVault).statusCode(404);
    }

    /** At a registry the route is RBAC's, not the registry's ("Unknown ACR path" in the report). */
    @Test
    void anAssignmentOnARegistryReachesRbac() {
        String roleId = listDefinitions(REGISTRY_SCOPE, "roleName eq 'acrpull'")
                .extract().path("value[0].id");
        String id = REGISTRY_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID();
        putAssignment(id, roleId, PRINCIPAL, "\"principalType\":\"ServicePrincipal\"")
                .statusCode(201)
                .body("properties.scope", equalTo(REGISTRY_SCOPE));
        listAssignments(REGISTRY_SCOPE, "atScope()").body("value.id", contains(id));
    }

    @Test
    void aResetForgetsAssignments() {
        String id = SUB_SCOPE + ASSIGNMENTS + "/" + UUID.randomUUID();
        putAssignment(id, SUB_SCOPE + DEFINITIONS + "/" + READER, PRINCIPAL, null).statusCode(201);
        given().post("/_admin/reset").then().statusCode(204);
        getAssignment(id).statusCode(404);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static ValidatableResponse listDefinitions(String scope, String filter) {
        RequestSpecification request = given().queryParam("api-version", DEFINITION_API);
        if (filter != null) {
            request = request.queryParam("$filter", filter);
        }
        return request.when().get(scope + DEFINITIONS).then().statusCode(200);
    }

    private static ValidatableResponse getDefinition(String id, String apiVersion) {
        return given().queryParam("api-version", apiVersion).when().get(id).then();
    }

    private static ValidatableResponse listAssignments(String scope, String filter) {
        RequestSpecification request = given().queryParam("api-version", ASSIGNMENT_API);
        if (filter != null) {
            request = request.queryParam("$filter", filter);
        }
        return request.when().get(scope + ASSIGNMENTS).then().statusCode(200);
    }

    private static ValidatableResponse getAssignment(String id) {
        return given().queryParam("api-version", ASSIGNMENT_API).when().get(id).then();
    }

    private static ValidatableResponse putAssignment(String id, String roleId, String principalId, String extra) {
        String body = "{\"properties\":{\"roleDefinitionId\":\"" + roleId + "\",\"principalId\":\"" + principalId + "\""
                + (extra == null ? "" : "," + extra) + "}}";
        return given().contentType("application/json").queryParam("api-version", ASSIGNMENT_API).body(body)
                .when().put(id).then();
    }
}
