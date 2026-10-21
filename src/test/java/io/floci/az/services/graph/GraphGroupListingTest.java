package io.floci.az.services.graph;

import io.floci.az.services.entra.DirectoryEntitySet;
import io.floci.az.services.entra.DirectoryObject;
import io.floci.az.services.entra.GraphDirectoryStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class GraphGroupListingTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String OTHER_TENANT = "different-tenant";
    private final String group = UUID.randomUUID().toString();
    private final String emptyGroup = UUID.randomUUID().toString();
    private final String user = UUID.randomUUID().toString();
    private final String otherGroup = UUID.randomUUID().toString();

    @Inject
    GraphDirectoryStore store;

    @BeforeEach
    void seedGroups() {
        store.put(GraphDirectoryStore.group(TENANT, group, "Reviewer's role", true));
        store.put(GraphDirectoryStore.group(TENANT, emptyGroup, "Empty role", true));
        DirectoryObject member = GraphDirectoryStore.user(
                TENANT, user, "reviewer@example.test", "Reviewer", "reviewer@example.test");
        member.props().put("internalToken", "must-not-be-returned");
        store.put(member);
        store.addLink(TENANT, group, GraphDirectoryStore.MEMBERS, user);
        store.put(GraphDirectoryStore.group(OTHER_TENANT, otherGroup, "Other tenant", true));
    }

    @AfterEach
    void cleanUp() {
        store.removeLink(TENANT, group, GraphDirectoryStore.MEMBERS, user);
        store.delete(TENANT, DirectoryEntitySet.GROUPS, group);
        store.delete(TENANT, DirectoryEntitySet.GROUPS, emptyGroup);
        store.delete(TENANT, DirectoryEntitySet.USERS, user);
        store.delete(OTHER_TENANT, DirectoryEntitySet.GROUPS, otherGroup);
    }

    @Test
    void groupListIsScopedToTheDefaultTenantAndDoesNotExpandByDefault() {
        given().when().get("/v1.0/groups").then().statusCode(200)
                .body("'@odata.context'", endsWith("$metadata#groups"))
                .body("value.id", hasItems(group, emptyGroup))
                .body("value.id", not(hasItem(otherGroup)))
                .body("value.find { it.id == '" + group + "' }", not(hasKey("members")));
    }

    @Test
    void inFilterExpandsMembersAndKeepsEmptyGroups() {
        given().queryParam("$filter", "id in ('" + group + "', '" + emptyGroup + "')")
                .queryParam("$expand", "members")
                .when().get("/v1.0/groups").then().statusCode(200)
                .body("value.id", containsInAnyOrder(group, emptyGroup))
                .body("value.find { it.id == '" + group + "' }.members.id", contains(user))
                .body("value.find { it.id == '" + group + "' }.members[0].userPrincipalName",
                        is("reviewer@example.test"))
                .body("value.find { it.id == '" + group + "' }.members[0].'@odata.type'", is("#microsoft.graph.user"))
                .body("value.find { it.id == '" + group + "' }.members[0]", not(hasKey("internalToken")))
                .body("value.find { it.id == '" + emptyGroup + "' }.members", empty());
    }

    @Test
    void displayNameEqualitySupportsEscapedQuotes() {
        given().queryParam("$filter", "displayName eq 'Reviewer''s role'")
                .when().get("/v1.0/groups").then().statusCode(200)
                .body("value.id", contains(group));
    }

    @Test
    void displayNameInMatchesSeveralNames() {
        given().queryParam("$filter", "displayName in ('Reviewer''s role', 'Empty role')")
                .when().get("/v1.0/groups").then().statusCode(200)
                .body("value.id", containsInAnyOrder(group, emptyGroup));
    }

    @Test
    void expansionIncludesDirectGroupsAndServicePrincipals() {
        String principal = UUID.randomUUID().toString();
        store.put(GraphDirectoryStore.servicePrincipal(TENANT, principal, "app-id", "Service"));
        store.addLink(TENANT, group, GraphDirectoryStore.MEMBERS, emptyGroup);
        store.addLink(TENANT, group, GraphDirectoryStore.MEMBERS, principal);
        try {
            given().queryParam("$filter", "id eq '" + group + "'").queryParam("$expand", "members")
                    .when().get("/v1.0/groups").then().statusCode(200)
                    .body("value[0].members.id", containsInAnyOrder(user, emptyGroup, principal))
                    .body("value[0].members.find { it.id == '" + emptyGroup + "' }.'@odata.type'",
                            is("#microsoft.graph.group"))
                    .body("value[0].members.find { it.id == '" + principal + "' }.appId", is("app-id"));
        } finally {
            store.removeLink(TENANT, group, GraphDirectoryStore.MEMBERS, emptyGroup);
            store.removeLink(TENANT, group, GraphDirectoryStore.MEMBERS, principal);
            store.delete(TENANT, DirectoryEntitySet.SERVICE_PRINCIPALS, principal);
        }
    }

    @Test
    void idEqualityAndMissingIdReturnFilteredCollections() {
        given().queryParam("$filter", "id eq '" + group + "'")
                .when().get("/v1.0/groups").then().statusCode(200).body("value.id", contains(group));
        given().queryParam("$filter", "id eq 'missing'")
                .when().get("/v1.0/groups").then().statusCode(200).body("value", empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id eq", "id in ()", "id in ('a',)", "id eq 'a' or id eq 'b'",
            "unsupported eq 'a'", "displayName eq 'unterminated"})
    void unsupportedOrMalformedFilterReturnsBadRequest(String filter) {
        given().queryParam("$filter", filter).when().get("/v1.0/groups").then().statusCode(400)
                .body("error.code", is("Request_BadRequest"));
    }

    @Test
    void unsupportedExpansionAndMethodAreRejected() {
        given().queryParam("$expand", "owners").when().get("/v1.0/groups").then().statusCode(400);
        given().contentType("application/json").body("{}")
                .when().post("/v1.0/groups").then().statusCode(405);
    }
}
