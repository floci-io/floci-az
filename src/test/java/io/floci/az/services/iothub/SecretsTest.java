package io.floci.az.services.iothub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("IoT Hub endpoint secrets: masked on read, restored when sent back masked")
class SecretsTest {

    private static final String STORAGE =
            "DefaultEndpointsProtocol=https;AccountName=acct;AccountKey=c3RvcmFnZQ==;EndpointSuffix=core.windows.net";
    private static final String BUS =
            "Endpoint=sb://ns.servicebus.windows.net/;SharedAccessKeyName=send;SharedAccessKey=YnVz;EntityPath=q";

    private static Map<String, Object> properties(String storage, String queueOne, String queueTwo) {
        return Map.of(
                "storageEndpoints", Map.of("$default", Map.of("connectionString", storage, "containerName", "c")),
                "routing", Map.of("endpoints", Map.of("serviceBusQueues", List.of(
                        Map.of("name", "q1", "connectionString", queueOne),
                        Map.of("name", "q2", "connectionString", queueTwo)))));
    }

    @Test
    void maskHidesOnlyTheSecretSettingsAsAzureDoes() {
        Map<String, Object> masked = cast(Secrets.mask(properties(STORAGE, BUS, "")));

        assertEquals("DefaultEndpointsProtocol=https;AccountName=acct;AccountKey=****;EndpointSuffix=core.windows.net",
                storage(masked));
        assertEquals("Endpoint=sb://ns.servicebus.windows.net/;SharedAccessKeyName=send;SharedAccessKey=****;"
                + "EntityPath=q", queue(masked, 0));
        assertEquals("", queue(masked, 1));
    }

    @Test
    void aMaskedSecretSentBackKeepsTheStoredOneMatchedByPathAndEndpointName() {
        Map<String, Object> stored = properties(STORAGE, BUS, BUS.replace("YnVz", "dHdv"));
        // The client read the hub, reordered nothing it cares about, and wrote it back: endpoints swapped places.
        Map<String, Object> sent = cast(Secrets.mask(properties(STORAGE, BUS.replace("YnVz", "dHdv"), BUS)));
        Map<String, Object> swapped = Map.of(
                "storageEndpoints", sent.get("storageEndpoints"),
                "routing", Map.of("endpoints", Map.of("serviceBusQueues", List.of(
                        Map.of("name", "q2", "connectionString", queue(sent, 0)),
                        Map.of("name", "q1", "connectionString", queue(sent, 1))))));

        Map<String, Object> restored = Secrets.restore(swapped, stored);

        assertEquals(STORAGE, storage(restored));
        assertEquals(BUS.replace("YnVz", "dHdv"), queue(restored, 0));
        assertEquals(BUS, queue(restored, 1));
    }

    @Test
    void aNewSecretOrAMaskWithNothingStoredIsKeptAsSent() {
        String rotated = STORAGE.replace("c3RvcmFnZQ==", "bmV3");
        assertEquals(rotated, storage(Secrets.restore(properties(rotated, BUS, ""), properties(STORAGE, BUS, ""))));

        String masked = STORAGE.replace("c3RvcmFnZQ==", Secrets.MASK);
        assertEquals(masked, storage(Secrets.restore(properties(masked, BUS, ""), Map.of())));
    }

    @Test
    void cosmosKeysAndSharedAccessSignaturesAreSecretsToo() {
        Map<String, Object> cosmos = Map.of("name", "c1", "primaryKey", "a2V5", "secondaryKey", "", "databaseName", "db");
        Map<String, Object> masked = cast(Secrets.mask(cosmos));
        assertEquals("****", masked.get("primaryKey"));
        assertEquals("", masked.get("secondaryKey"));
        assertEquals("db", masked.get("databaseName"));
        assertEquals(cosmos, Secrets.restore(masked, cosmos));

        String sas = "BlobEndpoint=https://acct.blob.core.windows.net/;SharedAccessSignature=sv=2022-11-02&sig=c2ln";
        assertEquals("BlobEndpoint=https://acct.blob.core.windows.net/;SharedAccessSignature=****",
                cast(Secrets.mask(Map.of("connectionString", sas))).get("connectionString"));
    }

    @Test
    void aMaskLeftAfterRestoreIsFound() {
        String masked = cast(Secrets.mask(Map.of("connectionString", BUS))).get("connectionString").toString();
        assertTrue(Secrets.containsMask(Map.of("routing", Map.of("endpoints", Map.of("serviceBusQueues",
                List.of(Map.of("name", "renamed", "connectionString", masked)))))));
        assertTrue(Secrets.containsMask(Map.of("primaryKey", Secrets.MASK)));
        assertFalse(Secrets.containsMask(properties(STORAGE, BUS, "")));
        assertFalse(Secrets.containsMask(Secrets.restore(cast(Secrets.mask(properties(STORAGE, BUS, ""))),
                properties(STORAGE, BUS, ""))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static String storage(Map<String, Object> properties) {
        return (String) cast(cast(properties.get("storageEndpoints")).get("$default")).get("connectionString");
    }

    private static String queue(Map<String, Object> properties, int index) {
        List<Object> queues = (List<Object>) cast(cast(properties.get("routing")).get("endpoints"))
                .get("serviceBusQueues");
        return (String) cast(queues.get(index)).get("connectionString");
    }
}
