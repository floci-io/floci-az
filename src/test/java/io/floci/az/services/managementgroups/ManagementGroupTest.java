package io.floci.az.services.managementgroups;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isOneOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@DisplayName("Management groups: hierarchy, expand and recurse, descendants, subscription membership, entities")
class ManagementGroupTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String BASE = "/providers/Microsoft.Management";
    private static final String API = "?api-version=2023-04-01";
    private static final String ROOT = BASE + "/managementGroups/" + TENANT;
    private static final String SUB = "aaaaaaaa-3333-3333-3333-000000000001";

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void tenantRootGroupAlwaysExistsAndHoldsUnplacedSubscriptions() {
        given().when().get(BASE + "/managementGroups" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo(TENANT))
                .body("value[0].type", equalTo("Microsoft.Management/managementGroups"))
                .body("value[0].properties.displayName", equalTo("Tenant Root Group"))
                .body("value[0].properties.tenantId", equalTo(TENANT));

        // A subscription the emulator has seen belongs to the root until it is placed.
        given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put("/subscriptions/" + SUB + "/resourceGroups/rg-mg?api-version=2021-04-01")
                .then().statusCode(isOneOf(200, 201));
        given().when().get(ROOT + API + "&$expand=children")
                .then().statusCode(200)
                .body("properties.details.parent", nullValue())
                .body("properties.children.find { it.name == '" + SUB + "' }.type", equalTo("/subscriptions"));
    }

    @Test
    void createsAHierarchyAndExpandsIt() {
        group("platform", "Platform", null).statusCode(200)
                .body("id", equalTo(BASE + "/managementGroups/platform"))
                .body("properties.displayName", equalTo("Platform"))
                .body("properties.details.parent.id", equalTo(ROOT))
                .body("properties.details.parent.displayName", equalTo("Tenant Root Group"))
                .body("properties.details.version", equalTo(1));
        group("landing-zones", "Landing zones", "platform").statusCode(200)
                .body("properties.details.parent.name", equalTo("platform"));
        group("corp", "Corp", "landing-zones").statusCode(200);

        given().when().get(BASE + "/managementGroups/corp" + API + "&$expand=path")
                .then().statusCode(200)
                .body("properties.details.path.name", equalTo(java.util.List.of(TENANT, "platform", "landing-zones", "corp")));
        given().when().get(BASE + "/managementGroups/corp" + API + "&$expand=ancestors")
                .then().statusCode(200)
                .body("properties.details.managementGroupAncestors.name", equalTo(java.util.List.of("landing-zones", "platform", TENANT)))
                .body("properties.details.managementGroupAncestorsChain[0].displayName", equalTo("Landing zones"));
        given().when().get(ROOT + API + "&$expand=children&$recurse=true")
                .then().statusCode(200)
                .body("properties.children.find { it.name == 'platform' }.children[0].name", equalTo("landing-zones"))
                .body("properties.children.find { it.name == 'platform' }.children[0].children[0].name", equalTo("corp"));
        given().when().get(BASE + "/managementGroups" + API)
                .then().statusCode(200)
                .body("value.name", hasItems(TENANT, "platform", "landing-zones", "corp"));
        given().when().get(BASE + "/managementGroups/corp" + API + "&$expand=everything")
                .then().statusCode(400);
    }

    @Test
    void patchMovesAndRenamesAndDeleteNeedsAnEmptyGroup() {
        group("a", "A", null).statusCode(200);
        group("b", "B", null).statusCode(200);
        group("child", "Child", "a").statusCode(200);

        given().contentType("application/json").body("{\"displayName\": \"Child renamed\", \"parentGroupId\": \"" + BASE + "/managementGroups/b\"}")
                .when().patch(BASE + "/managementGroups/child" + API)
                .then().statusCode(200)
                .body("properties.displayName", equalTo("Child renamed"))
                .body("properties.details.parent.name", equalTo("b"))
                .body("properties.details.version", equalTo(2));
        given().when().get(BASE + "/managementGroups/a/descendants" + API).then().statusCode(200).body("value", hasSize(0));
        given().when().get(BASE + "/managementGroups/b/descendants" + API).then().statusCode(200)
                .body("value[0].name", equalTo("child"))
                .body("value[0].properties.parent.id", equalTo(BASE + "/managementGroups/b"));

        // A group cannot be moved under its own descendant, and a non-empty group cannot be deleted.
        given().contentType("application/json").body("{\"parentGroupId\": \"" + BASE + "/managementGroups/child\"}")
                .when().patch(BASE + "/managementGroups/b" + API)
                .then().statusCode(400);
        given().when().delete(BASE + "/managementGroups/b" + API).then().statusCode(409);
        given().when().delete(BASE + "/managementGroups/child" + API).then().statusCode(204);
        given().when().delete(BASE + "/managementGroups/b" + API).then().statusCode(204);
        given().when().delete(BASE + "/managementGroups/b" + API).then().statusCode(204);
        given().when().get(BASE + "/managementGroups/b" + API).then().statusCode(404)
                .body("error.code", equalTo("NotFound"));
        given().when().delete(ROOT + API).then().statusCode(400);
        given().contentType("application/json").body("{\"properties\": {\"displayName\": \"x\"}}")
                .when().put(BASE + "/managementGroups/bad%20name!" + API).then().statusCode(400)
                .body("error.code", equalTo("InvalidManagementGroupName"));
    }

    @Test
    void subscriptionsAreMovedBetweenGroupsAndListedAsDescendants() {
        group("prod", "Production", null).statusCode(200);
        given().when().put(BASE + "/managementGroups/prod/subscriptions/" + SUB + API)
                .then().statusCode(200)
                .body("id", equalTo(BASE + "/managementGroups/prod/subscriptions/" + SUB))
                .body("type", equalTo("Microsoft.Management/managementGroups/subscriptions"))
                .body("properties.parent.id", equalTo(BASE + "/managementGroups/prod"))
                .body("properties.state", equalTo("Active"));
        given().when().get(BASE + "/managementGroups/prod/subscriptions" + API)
                .then().statusCode(200).body("value[0].name", equalTo(SUB));
        given().when().get(BASE + "/managementGroups/prod/subscriptions/" + SUB + API).then().statusCode(200);
        given().when().get(BASE + "/managementGroups/prod/descendants" + API)
                .then().statusCode(200)
                .body("value[0].type", equalTo("/subscriptions"))
                .body("value[0].name", equalTo(SUB));
        given().when().get(ROOT + API + "&$expand=children")
                .then().statusCode(200)
                .body("properties.children.name", not(hasItem(SUB)));

        given().when().delete(BASE + "/managementGroups/prod/subscriptions/" + SUB + API).then().statusCode(200);
        given().when().get(BASE + "/managementGroups/prod/subscriptions/" + SUB + API).then().statusCode(404);
        given().when().get(ROOT + API + "&$expand=children")
                .then().statusCode(200)
                .body("properties.children.name", hasItem(SUB));
    }

    @Test
    void entitiesNameAvailabilityAndOperations() {
        group("prod", "Production", null).statusCode(200);
        given().when().put(BASE + "/managementGroups/prod/subscriptions/" + SUB + API).then().statusCode(200);

        given().when().post(BASE + "/getEntities" + API)
                .then().statusCode(200)
                .body("value.find { it.name == 'prod' }.properties.parent.id", equalTo(ROOT))
                .body("value.find { it.name == 'prod' }.properties.numberOfDescendants", equalTo(1))
                .body("value.find { it.name == 'prod' }.properties.parentNameChain", equalTo(java.util.List.of(TENANT)))
                .body("value.find { it.name == '" + SUB + "' }.type", equalTo("/subscriptions"))
                .body("value.find { it.name == '" + SUB + "' }.properties.parentNameChain", equalTo(java.util.List.of("prod", TENANT)))
                .body("count.type", equalTo("Total"));
        given().when().post(BASE + "/getEntities" + API + "&groupName=prod")
                .then().statusCode(200)
                .body("value.name", equalTo(java.util.List.of("prod", SUB)));

        given().contentType("application/json").body("{\"name\": \"prod\", \"type\": \"Microsoft.Management/managementGroups\"}")
                .when().post(BASE + "/checkNameAvailability" + API)
                .then().statusCode(200).body("nameAvailable", equalTo(false)).body("reason", equalTo("AlreadyExists"));
        given().contentType("application/json").body("{\"name\": \"fresh\", \"type\": \"Microsoft.Management/managementGroups\"}")
                .when().post(BASE + "/checkNameAvailability" + API)
                .then().statusCode(200).body("nameAvailable", equalTo(true));
        given().when().get(BASE + "/operations" + API).then().statusCode(200).body("value[0].name", notNullValue());
        given().when().get(BASE + "/managementGroups/prod/settings/default" + API).then().statusCode(200)
                .body("properties.defaultManagementGroup", equalTo(ROOT));
    }

    private static ValidatableResponse group(String name, String displayName, String parent) {
        String details = parent == null ? "" : ", \"details\": {\"parent\": {\"id\": \"" + BASE + "/managementGroups/" + parent + "\"}}";
        return given().contentType("application/json")
                .body("{\"properties\": {\"displayName\": \"" + displayName + "\"" + details + "}}")
                .when().put(BASE + "/managementGroups/" + name + API)
                .then();
    }
}
