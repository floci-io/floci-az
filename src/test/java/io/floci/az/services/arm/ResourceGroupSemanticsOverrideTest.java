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
 * {@code parity-overrides.resource-group-semantics} alone: Azure's create/update statuses, not-found code and
 * implicit group creation, while {@code DELETE} keeps its default answer.
 */
@QuarkusTest
@TestProfile(ResourceGroupSemanticsOverrideTest.SemanticsOnly.class)
@DisplayName("ARM resource groups: resource-group-semantics override alone")
class ResourceGroupSemanticsOverrideTest {

    public static class SemanticsOnly implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.resource-group-semantics", "true");
        }
    }

    private static final String SUB = "7a1d0000-0000-4000-8000-00000000c0a3";
    private static final String API = "?api-version=2024-11-01";
    private static final String STORAGE_API = "?api-version=2023-01-01";
    private static final String CONTAINER_APPS_API = "?api-version=2025-07-01";

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
    void aReputIs200AndAMissingGroupIsResourceGroupNotFound() {
        json("{\"location\":\"eastus\"}").when().put(group("rg-sem") + API).then().statusCode(201);
        json("{\"location\":\"eastus\"}").when().put(group("RG-SEM") + API).then().statusCode(200);
        given().when().get(group("rg-other") + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceGroupNotFound"));
    }

    @Test
    void aResourcePutCreatesItsGroupButDeleteStaysSynchronousWithoutCascade() {
        String account = group("rg-sem-auto") + "/providers/Microsoft.Storage/storageAccounts/semautoacct0001";
        json("{\"location\":\"northeurope\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}")
            .when().put(account + STORAGE_API).then().statusCode(200);
        given().when().get(group("rg-sem-auto") + API)
            .then().statusCode(200)
            .body("location", equalTo("northeurope"));

        given().when().delete(group("rg-sem-auto") + API).then().statusCode(200);
        given().when().get(account + STORAGE_API).then().statusCode(200);
        given().when().delete(group("rg-sem-auto") + API).then().statusCode(200);
    }

    @Test
    void aGroupCreatedForAManagedEnvironmentTakesTheLocationOfItsJacksonBody() {
        String environment = group("rg-sem-env") + "/providers/Microsoft.App/managedEnvironments/sem-env";
        json("{\"location\":\"westeurope\",\"properties\":{\"zoneRedundant\":false}}")
            .when().put(environment + CONTAINER_APPS_API).then().statusCode(201);

        given().when().get(group("rg-sem-env") + API)
            .then().statusCode(200)
            .body("location", equalTo("westeurope"));
    }
}
