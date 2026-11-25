package io.floci.az.services.iothub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.PersistentStorage;
import io.floci.az.services.iothub.IotHubModels.AccessPolicy;
import io.floci.az.services.iothub.IotHubModels.ConsumerGroup;
import io.floci.az.services.iothub.IotHubModels.Device;
import io.floci.az.services.iothub.IotHubModels.IotHubResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * In persistent mode a hub is written through to {@code iothub.json} with everything it needs after a restart: the
 * policy keys, the ETag's write counter and the built-in endpoint's generated names. A fresh backend over the same
 * file reads what a restarted emulator would.
 */
@QuarkusTest
@TestProfile(IotHubPersistenceTest.Persistent.class)
@DisplayName("IoT Hub: a hub and its keys survive a restart in persistent mode")
class IotHubPersistenceTest {

    private static final Path DIR = Path.of("target", "iothub-persistence-test");
    private static final String HUB = "/subscriptions/10700000-0000-4000-8000-000000000002/resourceGroups/rg"
        + "/providers/Microsoft.Devices/IotHubs/persisted-hub";
    private static final String API = "?api-version=2023-06-30";

    public static class Persistent implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "floci-az.storage.services.iothub.mode", "persistent",
                "floci-az.storage.persistent-path", DIR.toString());
        }
    }

    @Test
    void theStoredHubCarriesItsKeysAndGeneratedNames() throws Exception {
        String body = "{\"location\": \"westus2\", \"tags\": {\"env\": \"dev\"}, \"sku\": {\"name\": \"S1\"}}";
        given().contentType("application/json").body(body).when().put(HUB + API).then().statusCode(201);
        given().contentType("application/json").body("{\"properties\": {\"name\": \"cg1\"}}")
            .when().put(HUB + "/eventHubEndpoints/events/ConsumerGroups/cg1" + API).then().statusCode(200);
        given().contentType("application/json").body(body).when().put(HUB + API).then().statusCode(201);
        List<String> primaries = given().when().post(HUB + "/listkeys" + API).then().statusCode(200)
            .extract().path("value.primaryKey");
        Map<String, Object> answered = given().when().get(HUB + API).then().statusCode(200).extract().path("$");

        PersistentStorage<String, StoredObject> reloaded =
            new PersistentStorage<>(DIR.resolve("iothub.json"), new TypeReference<Map<String, StoredObject>>() { });
        reloaded.load();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        List<IotHubResource> hubs = reloaded.scan(key -> key.endsWith("/iothubs/persisted-hub")).stream()
            .map(stored -> read(mapper, stored))
            .toList();

        assertEquals(1, hubs.size());
        IotHubResource hub = hubs.getFirst();
        assertEquals(primaries, hub.policies().stream().map(AccessPolicy::primaryKey).toList());
        assertEquals(answered.get("etag"), hub.etag());
        assertEquals(Map.of("env", "dev"), hub.tags());
        Map<String, Object> events = eventsEndpoint(answered);
        assertEquals(events.get("path"), hub.eventHubPath());
        assertEquals("sb://" + hub.eventHubNamespace() + ".servicebus.windows.net/", events.get("endpoint"));
        assertEquals(List.of("$Default", "cg1"), hub.consumerGroups().stream().map(ConsumerGroup::name).toList());
    }

    @Test
    void aStoredDeviceKeepsItsKeysAndEtag() throws Exception {
        // The file outlives the run, so a device an earlier run stored would make the create a conflict.
        given().post("/_admin/reset").then().statusCode(204);
        IotHubTestClient.createHub("persisted-devices");
        Response created = IotHubTestClient.owner("persisted-devices")
            .body("{\"deviceId\": \"esp32-1\"}").when().put("/devices/esp32-1" + IotHubTestClient.SERVICE_API);
        created.then().statusCode(200);

        PersistentStorage<String, StoredObject> reloaded =
            new PersistentStorage<>(DIR.resolve("iothub.json"), new TypeReference<Map<String, StoredObject>>() { });
        reloaded.load();
        List<Device> devices = reloaded.scan(key -> key.startsWith("dev\0") && key.endsWith("\0esp32-1")).stream()
            .map(stored -> readDevice(stored))
            .toList();

        assertEquals(1, devices.size());
        Device device = devices.getFirst();
        assertEquals(created.path("authentication.symmetricKey.primaryKey"), device.primaryKey());
        assertEquals(created.path("authentication.symmetricKey.secondaryKey"), device.secondaryKey());
        assertEquals(created.path("etag"), device.etag());
        assertEquals(created.path("generationId"), device.generationId());
    }

    @Test
    void aHubStoredBeforeConsumerGroupsLoadsWithTheDefaultGroup() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // A hub as the Phase 1 release wrote it: no consumerGroups field.
        IotHubResource stored = mapper.readValue("""
                {"id": "/subscriptions/s/resourceGroups/rg/providers/Microsoft.Devices/IotHubs/old-hub",
                 "name": "old-hub", "location": "westus2", "sku": "S1", "capacity": 1, "properties": {},
                 "policies": [], "incarnation": 7, "version": 3, "createdAt": "2026-11-01T10:00:00Z",
                 "eventHubPath": "old-hub-abc123", "eventHubNamespace": "iothub-ns-old-hub-1-ab"}""",
                IotHubResource.class);

        assertEquals(List.of(new ConsumerGroup("events", "$Default", "2026-11-01T10:00:00Z")),
                stored.consumerGroups());
    }

    private static Device readDevice(StoredObject stored) {
        try {
            return new ObjectMapper().readValue(stored.data(), Device.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static IotHubResource read(ObjectMapper mapper, StoredObject stored) {
        try {
            return mapper.readValue(stored.data(), IotHubResource.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> eventsEndpoint(Map<String, Object> hub) {
        Map<String, Object> properties = (Map<String, Object>) hub.get("properties");
        Map<String, Object> endpoints = (Map<String, Object>) properties.get("eventHubEndpoints");
        return (Map<String, Object>) endpoints.get("events");
    }
}
