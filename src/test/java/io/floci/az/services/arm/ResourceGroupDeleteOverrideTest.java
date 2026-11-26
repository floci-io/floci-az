package io.floci.az.services.arm;

import io.floci.az.services.iothub.IotHubTestClient;
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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * {@code parity-overrides.resource-group-delete} alone: {@code DELETE} is the long-running cascade and the
 * generic listings show what it deletes, while every PUT keeps its default answer.
 */
@QuarkusTest
@TestProfile(ResourceGroupDeleteOverrideTest.DeleteOnly.class)
@DisplayName("ARM resource groups: resource-group-delete override alone")
class ResourceGroupDeleteOverrideTest {

    public static class DeleteOnly implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.resource-group-delete", "true");
        }
    }

    private static final String SUB = "7a1d0000-0000-4000-8000-00000000c0a4";
    private static final String API = "?api-version=2024-11-01";
    private static final String EVENT_GRID_API = "?api-version=2022-06-15";
    private static final String MONITOR_API = "?api-version=2022-10-01";
    private static final String EMAIL_API = "?api-version=2023-04-01";

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

    /** The cascade goes through each vault's own delete, so a vault in the group is soft-deleted, not lost. */
    @Test
    void theCascadeSoftDeletesAKeyVault() {
        String keyVaultApi = "?api-version=2023-02-01";
        json("{\"location\":\"eastus\"}").when().put(group("rg-kv") + API).then().statusCode(201);
        json("{\"location\":\"eastus\",\"properties\":{\"accessPolicies\":[]}}")
            .when().put(group("rg-kv") + "/providers/Microsoft.KeyVault/vaults/kv-cascade" + keyVaultApi)
            .then().statusCode(200);

        given().when().delete(group("rg-kv") + API).then().statusCode(202);

        given().when().get("/subscriptions/" + SUB
                + "/providers/Microsoft.KeyVault/locations/eastus/deletedVaults/kv-cascade" + keyVaultApi)
            .then().statusCode(200)
            .body("properties.vaultId", equalTo(group("rg-kv") + "/providers/Microsoft.KeyVault/vaults/kv-cascade"));
    }

    /** Azure soft-deletes the workspaces and API Management services of a deleted group, so azd purges them after. */
    @Test
    void theCascadeSoftDeletesAWorkspaceAndAnApiManagementService() {
        String apimApi = "?api-version=2022-08-01";
        String workspace = group("rg-soft") + "/providers/Microsoft.OperationalInsights/workspaces/soft-ws";
        String apim = group("rg-soft") + "/providers/Microsoft.ApiManagement/service/soft-apim-cascade";
        json("{\"location\":\"eastus\"}").when().put(group("rg-soft") + API).then().statusCode(201);
        json("{\"location\":\"eastus\",\"properties\":{}}").when().put(workspace + MONITOR_API).then().statusCode(201);
        json("{\"location\":\"eastus\",\"sku\":{\"name\":\"Consumption\",\"capacity\":0},"
                + "\"properties\":{\"publisherEmail\":\"a@example.com\",\"publisherName\":\"a\"}}")
            .when().put(apim + apimApi).then().statusCode(200);

        given().when().delete(group("rg-soft") + API).then().statusCode(202);

        given().when().get("/subscriptions/" + SUB + "/providers/Microsoft.OperationalInsights/deletedWorkspaces"
                + MONITOR_API)
            .then().statusCode(200)
            .body("value.id", hasItem(workspace));
        given().when().get("/subscriptions/" + SUB
                + "/providers/Microsoft.ApiManagement/locations/eastus/deletedservices/soft-apim-cascade" + apimApi)
            .then().statusCode(200)
            .body("properties.serviceId", equalTo(apim));
    }

    @Test
    void everyPutStays201() {
        json("{\"location\":\"eastus\"}").when().put(group("rg-del-only") + API).then().statusCode(201);
        json("{\"location\":\"eastus\"}").when().put(group("rg-del-only") + API).then().statusCode(201);
    }

    @Test
    void theListingsShowAndTheCascadeDeletesEventGridMonitorAndEmailResources() {
        json("{\"location\":\"eastus\"}").when().put(group("rg-idx") + API).then().statusCode(201);
        String topic = group("rg-idx") + "/providers/Microsoft.EventGrid/topics/idx-topic";
        String workspace = group("rg-idx") + "/providers/Microsoft.OperationalInsights/workspaces/idx-ws";
        String email = group("rg-idx") + "/providers/Microsoft.Communication/emailServices/idx-email";
        json("{\"location\":\"eastus\",\"properties\":{}}").when().put(topic + EVENT_GRID_API).then().statusCode(201);
        json("{\"location\":\"eastus\",\"properties\":{}}").when().put(workspace + MONITOR_API).then().statusCode(201);
        json("{\"location\":\"global\",\"properties\":{\"dataLocation\":\"United States\"}}")
            .when().put(email + EMAIL_API).then().statusCode(201);

        given().when().get(group("rg-idx") + "/resources" + API)
            .then().statusCode(200)
            .body("value.id", hasItem(topic))
            .body("value.id", hasItem(workspace))
            .body("value.id", hasItem(email))
            .body("value.find { it.id == '" + workspace + "' }.type",
                equalTo("Microsoft.OperationalInsights/workspaces"));

        given().when().delete(group("rg-idx") + API)
            .then().statusCode(202)
            .header("Location", notNullValue());

        given().when().get(topic + EVENT_GRID_API).then().statusCode(404);
        given().when().get(workspace + MONITOR_API).then().statusCode(404);
        given().when().get(email + EMAIL_API).then().statusCode(404);
    }

    /** A search service is listed with its group, deleted with it, and its global name is free again. */
    @Test
    void theCascadeDeletesASearchServiceAndFreesItsName() {
        String searchApi = "?api-version=2025-05-01";
        String body = "{\"location\":\"eastus\",\"sku\":{\"name\":\"basic\"}}";
        json("{\"location\":\"eastus\"}").when().put(group("rg-search") + API).then().statusCode(201);
        String search = group("rg-search") + "/providers/Microsoft.Search/searchServices/idx-search";
        json(body).when().put(search + searchApi).then().statusCode(201);

        given().when().get(group("rg-search") + "/resources" + API)
            .then().statusCode(200)
            .body("value.id", hasItem(search));

        given().when().delete(group("rg-search") + API).then().statusCode(202);

        given().when().get(search + searchApi).then().statusCode(404);
        json(body).when().put(group("rg-other") + "/providers/Microsoft.Search/searchServices/idx-search" + searchApi)
            .then().statusCode(201);
    }

    /**
     * An IoT hub is listed with its group and deleted with it, its devices too, and its global name is free again: a
     * hub created again under the name has an empty registry.
     */
    @Test
    void theCascadeDeletesAnIotHubAndFreesItsName() {
        String iotApi = "?api-version=2023-06-30";
        String body = "{\"location\":\"eastus\",\"sku\":{\"name\":\"S1\"}}";
        json("{\"location\":\"eastus\"}").when().put(group("rg-iothub") + API).then().statusCode(201);
        String hub = group("rg-iothub") + "/providers/Microsoft.Devices/IotHubs/cascade-hub";
        json(body).when().put(hub + iotApi).then().statusCode(201);
        String ownerKey = given().when().post(hub + "/listkeys" + iotApi).then().statusCode(200)
            .extract().path("value.find { it.keyName == 'iothubowner' }.primaryKey");
        json("{\"deviceId\": \"d1\"}").header("Host", "cascade-hub.azure-devices.net")
            .header("Authorization", IotHubTestClient.token("cascade-hub.azure-devices.net", "iothubowner", ownerKey))
            .when().put("/devices/d1" + IotHubTestClient.SERVICE_API).then().statusCode(200);

        given().when().get(group("rg-iothub") + "/resources" + API)
            .then().statusCode(200)
            .body("value.id", hasItem(hub));

        given().when().delete(group("rg-iothub") + API).then().statusCode(202);

        given().when().get(hub + iotApi).then().statusCode(404);
        String recreated = group("rg-other") + "/providers/Microsoft.Devices/IotHubs/cascade-hub";
        json(body).when().put(recreated + iotApi).then().statusCode(201);
        given().when().get(recreated + "/IotHubStats" + iotApi).then().statusCode(200)
            .body("totalDeviceCount", equalTo(0));
    }

    /**
     * A Service Bus namespace is listed with its group and deleted with it, its queue on the broker too, and its
     * global name is free again.
     */
    @Test
    void theCascadeDeletesAServiceBusNamespaceWithItsQueueAndFreesItsName() {
        String sbApi = "?api-version=2024-01-01";
        String body = "{\"location\":\"eastus\",\"sku\":{\"name\":\"Standard\"}}";
        json("{\"location\":\"eastus\"}").when().put(group("rg-sb") + API).then().statusCode(201);
        String namespace = group("rg-sb") + "/providers/Microsoft.ServiceBus/namespaces/cascade-sbns";
        json(body).when().put(namespace + sbApi).then().statusCode(201);
        json("{\"properties\":{}}").when().put(namespace + "/queues/orders" + sbApi).then().statusCode(200);

        given().when().get(group("rg-sb") + "/resources" + API)
            .then().statusCode(200)
            .body("value.id", hasItem(namespace));

        given().when().delete(group("rg-sb") + API).then().statusCode(202);

        given().when().get("/devstoreaccount1-servicebus/cascade-sbns/queues/orders").then().statusCode(404);
        json(body).when().put(group("rg-other") + "/providers/Microsoft.ServiceBus/namespaces/cascade-sbns" + sbApi)
            .then().statusCode(201);
    }
}
