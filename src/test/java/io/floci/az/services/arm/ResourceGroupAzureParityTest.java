package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Resource groups under {@code parity: azure}: 201 on create and 200 on update, a missing group is
 * {@code ResourceGroupNotFound}, a resource PUT under a missing group creates it once the resource exists, and
 * {@code DELETE} is a long-running operation that deletes every resource in the group first, whichever
 * handler serves it.
 */
@QuarkusTest
@TestProfile(ResourceGroupAzureParityTest.AzureParityProfile.class)
@DisplayName("ARM resource groups under parity: azure")
class ResourceGroupAzureParityTest {

    public static class AzureParityProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "floci-az.parity", "azure",
                "floci-az.services.redis.mocked", "true");
        }
    }

    private static final String SUB = "7a1d0000-0000-4000-8000-00000000c0a2";
    private static final String API = "?api-version=2024-11-01";
    private static final String STORAGE_API = "?api-version=2023-01-01";
    private static final String KV_API = "?api-version=2023-07-01";
    private static final String REDIS_API = "?api-version=2024-11-01";
    private static final String EVENT_GRID_API = "?api-version=2022-06-15";

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
    void aCreateIs201AndAnUpdateIs200() {
        json("{\"location\":\"eastus\"}").when().put(group("rg-status") + API).then().statusCode(201);
        json("{\"location\":\"eastus\",\"tags\":{\"a\":\"b\"}}")
            .when().put(group("rg-status") + API)
            .then().statusCode(200)
            .body("tags.a", equalTo("b"));
    }

    @Test
    void aMissingGroupIsResourceGroupNotFound() {
        given().when().get(group("rg-none") + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceGroupNotFound"))
            .body("error.message", equalTo("Resource group 'rg-none' could not be found."));
        given().when().delete(group("rg-none") + API)
            .then().statusCode(404)
            .body("error.code", equalTo("ResourceGroupNotFound"));
    }

    @Test
    void aResourcePutUnderAMissingGroupCreatesTheGroupWithTheResourcesLocation() {
        String account = group("rg-auto") + "/providers/Microsoft.Storage/storageAccounts/rgautoacct0001";
        json("{\"location\":\"westeurope\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}")
            .when().put(account + STORAGE_API).then().statusCode(200);

        given().when().get(group("rg-auto") + API)
            .then().statusCode(200)
            .body("name", equalTo("rg-auto"))
            .body("location", equalTo("westeurope"))
            .body("properties.provisioningState", equalTo("Succeeded"));
    }

    @Test
    void aRefusedResourcePutCreatesNoGroup() {
        String account = group("rg-refused") + "/providers/Microsoft.Storage/storageAccounts/NOT_VALID";
        json("{\"location\":\"westeurope\"}").when().put(account + STORAGE_API).then().statusCode(400);

        given().when().head(group("rg-refused") + API).then().statusCode(404);
    }

    @Test
    void deleteIsALongRunningOperationThatCompletesOnThePoll() {
        json("{\"location\":\"eastus\"}").when().put(group("rg-lro") + API).then().statusCode(201);

        Response accepted = given().when().delete(group("rg-lro") + API)
            .then().statusCode(202)
            .header("Retry-After", equalTo("1"))
            .header("Location", startsWith("http://localhost:" + RestAssured.port + "/subscriptions/" + SUB
                + "/operationresults/"))
            .body(emptyOrNullString())
            .extract().response();

        URI location = URI.create(accepted.getHeader("Location"));
        assertEquals("api-version=2024-11-01", location.getQuery());
        given().when().get(location.getPath() + "?" + location.getQuery())
            .then().statusCode(200)
            .body(emptyOrNullString());

        given().when().get(group("rg-lro") + API).then().statusCode(404)
            .body("error.code", equalTo("ResourceGroupNotFound"));
        given().when().get("/subscriptions/" + SUB + "/operationresults/not-an-operation" + API)
            .then().statusCode(404);
    }

    @Test
    void deleteCascadesAcrossEveryHandlerAndLeavesOtherGroupsAlone() {
        json("{\"location\":\"eastus\"}").when().put(group("rg-cascade") + API).then().statusCode(201);
        json("{\"location\":\"eastus\"}").when().put(group("rg-keep") + API).then().statusCode(201);

        String account = group("rg-cascade") + "/providers/Microsoft.Storage/storageAccounts/cascadeacct0001";
        String vault = group("RG-Cascade") + "/providers/Microsoft.KeyVault/vaults/cascade-kv-0001";
        String cache = group("rg-cascade") + "/providers/Microsoft.Cache/redis/cascade-cache";
        String topic = group("rg-cascade") + "/providers/Microsoft.EventGrid/topics/cascade-topic";
        String kept = group("rg-keep") + "/providers/Microsoft.Storage/storageAccounts/keptacct0001";
        json("{\"location\":\"eastus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}")
            .when().put(account + STORAGE_API).then().statusCode(200);
        json("{\"location\":\"eastus\",\"properties\":{\"sku\":{\"family\":\"A\",\"name\":\"standard\"},"
            + "\"accessPolicies\":[]}}").when().put(vault + KV_API).then().statusCode(200);
        json("{\"location\":\"eastus\",\"properties\":{\"sku\":{\"name\":\"Basic\",\"family\":\"C\",\"capacity\":0}}}")
            .when().put(cache + REDIS_API).then().statusCode(201);
        json("{\"location\":\"eastus\",\"properties\":{}}").when().put(topic + EVENT_GRID_API).then().statusCode(200);
        json("{\"location\":\"eastus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}")
            .when().put(kept + STORAGE_API).then().statusCode(200);

        // The vault was created under a differently cased group name: the cascade still finds it.
        given().when().get("/subscriptions/" + SUB + "/resources" + API)
            .then().statusCode(200)
            .body("value", hasSize(5))
            .body("value.name", hasItems("cascadeacct0001", "cascade-kv-0001", "cascade-cache", "cascade-topic"));

        given().when().delete(group("rg-cascade") + API).then().statusCode(202);

        given().when().get(account + STORAGE_API).then().statusCode(404);
        given().when().get(vault + KV_API).then().statusCode(404);
        given().when().get(cache + REDIS_API).then().statusCode(404);
        given().when().get(topic + EVENT_GRID_API).then().statusCode(404);
        given().when().get("/subscriptions/" + SUB + "/resources" + API)
            .then().statusCode(200)
            .body("value.id", hasItem(kept))
            .body("value.name", not(hasItem("cascade-cache")));
        given().when().get(kept + STORAGE_API).then().statusCode(200);
        given().when().head(group("rg-keep") + API).then().statusCode(204);

        // The global names the cascade released can be taken again.
        json("{\"location\":\"eastus\",\"kind\":\"StorageV2\",\"sku\":{\"name\":\"Standard_LRS\"}}")
            .when().put(group("rg-other") + "/providers/Microsoft.Storage/storageAccounts/cascadeacct0001"
                + STORAGE_API).then().statusCode(200);
    }
}
