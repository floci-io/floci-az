package io.floci.az.services.acr;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Management-plane coverage for Azure Container Registry in mocked mode (no Docker):
 * registry CRUD, listCredentials / regenerateCredential, and checkNameAvailability.
 *
 * <p>Always runs in mocked mode (no registry container) regardless of the default in application.yml.
 */
@QuarkusTest
@TestProfile(AcrHandlerTest.MockedProfile.class)
public class AcrHandlerTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.acr.mocked", "true");
        }
    }

    private static final String SUB = "00000000-0000-0000-0000-000000000001";
    private static final String RG = "test-rg";
    private static final String API = "?api-version=2025-11-01";

    private String registries() {
        return "/subscriptions/" + SUB + "/resourceGroups/" + RG
                + "/providers/Microsoft.ContainerRegistry/registries";
    }

    private String registry(String name) {
        return registries() + "/" + name;
    }

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    private void createRegistry(String name) {
        given().contentType("application/json")
                .body("{\"location\":\"eastus\",\"sku\":{\"name\":\"Basic\"},"
                        + "\"properties\":{\"adminUserEnabled\":true}}")
                .when().put(registry(name) + API)
                .then().statusCode(201);
    }

    @Test
    void createReturnsRegistryResource() {
        given().contentType("application/json")
                .body("{\"location\":\"eastus\",\"sku\":{\"name\":\"Basic\"},"
                        + "\"properties\":{\"adminUserEnabled\":true}}")
                .when().put(registry("acrcreate") + API)
                .then().statusCode(201)
                .body("type", is("Microsoft.ContainerRegistry/registries"))
                .body("sku.name", is("Basic"))
                .body("properties.provisioningState", is("Succeeded"))
                .body("properties.adminUserEnabled", is(true))
                .body("properties.loginServer", endsWith(".azurecr.io"))
                // Fields the azurerm provider dereferences without nil checks — must be present.
                .body("properties.zoneRedundancy", is("Disabled"))
                .body("properties.publicNetworkAccess", is("Enabled"));
    }

    @Test
    void getAndListReturnTheRegistry() {
        createRegistry("acrget");

        given().when().get(registry("acrget") + API)
                .then().statusCode(200).body("name", is("acrget"));

        given().when().get(registries() + API)
                .then().statusCode(200)
                .body("value.name", org.hamcrest.Matchers.hasItem("acrget"));
    }

    @Test
    void listCredentialsReturnsUsernameAndTwoPasswords() {
        createRegistry("acrcreds");

        given().when().post(registry("acrcreds") + "/listCredentials" + API)
                .then().statusCode(200)
                .body("username", is("acrcreds"))
                .body("passwords", hasSize(2))
                .body("passwords[0].name", is("password"))
                .body("passwords[0].value", notNullValue())
                .body("passwords[1].name", is("password2"));
    }

    @Test
    void regenerateCredentialRotatesThePrimaryPassword() {
        createRegistry("acrrotate");

        String before = given().when().post(registry("acrrotate") + "/listCredentials" + API)
                .then().statusCode(200).extract().jsonPath().getString("passwords[0].value");

        String after = given().contentType("application/json").body("{\"name\":\"password\"}")
                .when().post(registry("acrrotate") + "/regenerateCredential" + API)
                .then().statusCode(200).extract().jsonPath().getString("passwords[0].value");

        assertNotEquals(before, after);
    }

    @Test
    void checkNameAvailabilityReflectsExistingRegistries() {
        createRegistry("acrtaken");

        String checkPath = "/subscriptions/" + SUB
                + "/providers/Microsoft.ContainerRegistry/checkNameAvailability" + API;

        given().contentType("application/json")
                .body("{\"name\":\"acrtaken\",\"type\":\"Microsoft.ContainerRegistry/registries\"}")
                .when().post(checkPath)
                .then().statusCode(200)
                .body("nameAvailable", is(false))
                .body("reason", is("AlreadyExists"));

        given().contentType("application/json")
                .body("{\"name\":\"acrfreexyz\",\"type\":\"Microsoft.ContainerRegistry/registries\"}")
                .when().post(checkPath)
                .then().statusCode(200)
                .body("nameAvailable", is(true));
    }

    @Test
    void replicationsReturnsEmptyList() {
        createRegistry("acrrepl");
        // The azurerm provider lists geo-replications during Read; must be 200 + empty, not 404.
        given().when().get(registry("acrrepl") + "/replications" + API)
                .then().statusCode(200)
                .body("value", hasSize(0));
    }

    @Test
    void deleteRemovesTheRegistry() {
        createRegistry("acrdelete");

        given().when().delete(registry("acrdelete") + API).then().statusCode(202);
        given().when().get(registry("acrdelete") + API).then().statusCode(404);
    }
}
