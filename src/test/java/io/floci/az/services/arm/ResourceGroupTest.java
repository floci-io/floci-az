package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

/**
 * Resource groups in the default configuration: the fixes that are always on (tags and {@code managedBy}
 * kept, {@code PATCH} and {@code HEAD} answered, names matched case-insensitively) and the answers that must
 * not change (every PUT 201, {@code DELETE} 200 without cascade, a missing group's delete 200, nothing
 * created for a resource PUT under a missing group).
 */
@QuarkusTest
@DisplayName("ARM resource groups: default configuration")
class ResourceGroupTest {

    private static final String SUB = "7a1d0000-0000-4000-8000-00000000c0a1";
    private static final String API = "?api-version=2024-11-01";
    private static final String STORAGE_API = "?api-version=2023-01-01";

    private static String group(String name) {
        return "/subscriptions/" + SUB + "/resourceGroups/" + name;
    }

    private static RequestSpecification json(String body) {
        return given().contentType("application/json").body(body);
    }

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void aPutKeepsTagsAndManagedByAndAGetReturnsThem() {
        json("{\"location\":\"westus\",\"managedBy\":\"/subscriptions/x/owner\",\"tags\":{\"owner\":\"probe\"}}")
            .when().put(group("rg-tags") + API)
            .then().statusCode(201)
            .body("tags.owner", equalTo("probe"))
            .body("managedBy", equalTo("/subscriptions/x/owner"));

        given().when().get(group("rg-tags") + API)
            .then().statusCode(200)
            .body("location", equalTo("westus"))
            .body("tags.owner", equalTo("probe"))
            .body("managedBy", equalTo("/subscriptions/x/owner"));
    }

    @Test
    void aGroupWithoutTagsHasNoTagsKey() {
        json("{\"location\":\"eastus\"}").when().put(group("rg-plain") + API).then().statusCode(201);

        given().when().get(group("rg-plain") + API)
            .then().statusCode(200)
            .body("tags", nullValue())
            .body("managedBy", nullValue());
    }

    @Test
    void aPatchReplacesTheTagsAndKeepsEverythingItLeavesOut() {
        json("{\"location\":\"westus\",\"managedBy\":\"owner-id\",\"tags\":{\"owner\":\"probe\",\"stale\":\"x\"}}")
            .when().put(group("rg-patch") + API).then().statusCode(201);

        json("{\"tags\":{\"owner\":\"probe2\"}}")
            .when().patch(group("rg-patch") + API)
            .then().statusCode(200)
            .body("name", equalTo("rg-patch"))
            .body("location", equalTo("westus"))
            .body("managedBy", equalTo("owner-id"))
            .body("tags.owner", equalTo("probe2"))
            .body("tags.stale", nullValue());

        json("{\"managedBy\":\"other-owner\"}")
            .when().patch(group("rg-patch") + API)
            .then().statusCode(200)
            .body("managedBy", equalTo("other-owner"))
            .body("tags.owner", equalTo("probe2"));

        given().when().get(group("rg-patch") + API)
            .then().statusCode(200)
            .body("managedBy", equalTo("other-owner"))
            .body("tags.owner", equalTo("probe2"));
    }

    @Test
    void aPatchOfAMissingGroupIsResourceGroupNotFound() {
        json("{\"tags\":{\"a\":\"b\"}}")
            .when().patch(group("rg-missing") + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceGroupNotFound"))
            .body("error.message", equalTo("Resource group 'rg-missing' could not be found."));
    }

    @Test
    void headAnswers204ForAGroupAnd404WithoutOne() {
        given().when().head(group("rg-head") + API).then().statusCode(404);

        json("{\"location\":\"eastus\"}").when().put(group("rg-head") + API).then().statusCode(201);

        given().when().head(group("rg-head") + API).then().statusCode(204);
        given().when().head(group("RG-HEAD") + API).then().statusCode(204);
    }

    @Test
    void namesMatchCaseInsensitivelyAndTheLatestSpellingIsEchoed() {
        json("{\"location\":\"eastus\"}").when().put(group("Rg-Case") + API).then().statusCode(201);

        given().when().get(group("rg-case") + API)
            .then().statusCode(200)
            .body("name", equalTo("Rg-Case"));

        json("{\"location\":\"eastus\"}").when().put(group("RG-CASE") + API).then().statusCode(201);

        given().when().get("/subscriptions/" + SUB + "/resourceGroups" + API)
            .then().statusCode(200)
            .body("value", hasSize(1))
            .body("value[0].name", equalTo("RG-CASE"))
            .body("value[0].id", equalTo(group("RG-CASE")));

        given().when().delete(group("rg-case") + API).then().statusCode(200);
        given().when().get(group("Rg-Case") + API).then().statusCode(404);
    }

    @Test
    void everyPutAnswers201AsItAlwaysHas() {
        json("{\"location\":\"eastus\"}").when().put(group("rg-reput") + API).then().statusCode(201);
        json("{\"location\":\"eastus\",\"tags\":{\"a\":\"b\"}}")
            .when().put(group("rg-reput") + API)
            .then().statusCode(201)
            .body("tags.a", equalTo("b"));
    }

    @Test
    void aMissingGroupKeepsTheGenericNotFound() {
        given().when().get(group("rg-never") + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceNotFound"))
            .body("error.message", equalTo("Resource not found: resourceGroups/rg-never"));
    }

    @Test
    void deleteAnswers200WithoutCascadeAndForAMissingGroup() {
        json("{\"location\":\"eastus\"}").when().put(group("rg-del") + API).then().statusCode(201);
        String account = group("rg-del") + "/providers/Microsoft.Storage/storageAccounts/rgdelacct0001";
        json("{\"location\":\"eastus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}")
            .when().put(account + STORAGE_API).then().statusCode(200);

        given().when().delete(group("rg-del") + API)
            .then().statusCode(200)
            .header("Location", nullValue())
            .body(emptyOrNullString());

        given().when().get(account + STORAGE_API).then().statusCode(200);
        given().when().delete(group("rg-del") + API).then().statusCode(200);
    }

    @Test
    void aResourcePutUnderAMissingGroupCreatesNoGroup() {
        String account = group("rg-implicit") + "/providers/Microsoft.Storage/storageAccounts/rgimplicit0001";
        json("{\"location\":\"westeurope\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}")
            .when().put(account + STORAGE_API).then().statusCode(200);

        given().when().get(group("rg-implicit") + API).then().statusCode(404);
        given().when().head(group("rg-implicit") + API).then().statusCode(404);
    }
}
