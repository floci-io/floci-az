package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * {@code parity-overrides.strict-resource-groups} alone: a request for a resource under a group that does not
 * exist is refused before its handler runs, with ARM's {@code 404 ResourceGroupNotFound}.
 */
@QuarkusTest
@TestProfile(ResourceGroupStrictTest.StrictGroupsOnly.class)
@DisplayName("ARM resource groups: strict-resource-groups override alone")
class ResourceGroupStrictTest {

    public static class StrictGroupsOnly implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-resource-groups", "true");
        }
    }

    private static final String SUB = "7a1d0000-0000-4000-8000-00000000c0a5";
    private static final String API = "?api-version=2024-11-01";
    private static final String STORAGE_API = "?api-version=2023-01-01";
    private static final String STORAGE_BODY =
        "{\"location\":\"eastus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}";

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
    void aResourcePutUnderAMissingGroupIsResourceGroupNotFound() {
        String account = group("rg-strict") + "/providers/Microsoft.Storage/storageAccounts/strictacct0001";
        json(STORAGE_BODY).when().put(account + STORAGE_API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceGroupNotFound"))
            .body("error.message", equalTo("Resource group 'rg-strict' could not be found."));
        given().when().get(account + STORAGE_API).then().statusCode(404)
            .body("error.code", equalTo("ResourceGroupNotFound"));
        given().when().delete(account + STORAGE_API).then().statusCode(404)
            .body("error.code", equalTo("ResourceGroupNotFound"));
        given().when().head(group("rg-strict") + API).then().statusCode(404);
    }

    @Test
    void aResourcePutUnderAnExistingGroupPassesWhateverItsCasing() {
        json("{\"location\":\"eastus\"}").when().put(group("rg-strict-ok") + API).then().statusCode(201);
        json(STORAGE_BODY)
            .when().put(group("RG-STRICT-OK") + "/providers/Microsoft.Storage/storageAccounts/strictacct0002"
                + STORAGE_API)
            .then().statusCode(200);
    }

    @Test
    void theGroupItselfAndTheProvidersAboveItAreNotChecked() {
        json("{\"location\":\"eastus\"}").when().put(group("rg-strict-new") + API).then().statusCode(201);
        json("{\"name\":\"strictfree0001\",\"type\":\"Microsoft.Storage/storageAccounts\"}")
            .when().post("/subscriptions/" + SUB + "/providers/Microsoft.Storage/checkNameAvailability" + STORAGE_API)
            .then().statusCode(200);
    }
}
