package io.floci.az.services.iothub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.PersistentStorage;
import io.floci.az.services.iothub.IotHubModels.AccessPolicy;
import io.floci.az.services.iothub.IotHubModels.IotHubResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
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
