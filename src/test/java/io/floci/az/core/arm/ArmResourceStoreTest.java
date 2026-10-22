package io.floci.az.core.arm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.InMemoryStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ArmResourceStore: canonical keys, legacy reads and move-on-write")
class ArmResourceStoreTest {

    /** A stored resource that carries its own id, as service models do. */
    record Thing(String id, String label) {
    }

    private static final String ID = "/subscriptions/Sub-1/resourceGroups/RG-One/providers/Microsoft.Test/things/a";

    private final InMemoryStorage<String, StoredObject> backend = new InMemoryStorage<>();
    private final ArmResourceStore<Thing> store =
        ArmResourceStore.over(backend, "things", Thing.class, Thing::id, key -> !key.startsWith("other:"));

    private static ArmResourceId id(String path) {
        return ArmResourceId.parse(path).orElseThrow();
    }

    private void putLegacy(String key, Thing thing) throws Exception {
        putLegacy(key, thing, Instant.now());
    }

    private void putLegacy(String key, Thing thing, Instant lastModified) throws Exception {
        backend.put(key, new StoredObject(key, new ObjectMapper().writeValueAsBytes(thing), Map.of(), lastModified,
            "etag"));
    }

    @Test
    void aResourceIsKeyedByItsCanonicalIdAndKeepsItsCasing() {
        store.put(new Thing(ID, "first"));

        assertEquals(List.of(ID.toLowerCase()), List.copyOf(backend.keys()));
        ArmResourceStore.Stored<Thing> found = store.get(id(ID.toLowerCase())).orElseThrow();
        assertEquals(ID, found.id().id());
        assertEquals("first", found.value().label());
    }

    @Test
    void aWriteThroughOtherCasingUpdatesTheOneResource() {
        store.put(new Thing(ID, "first"));
        store.put(new Thing(ID.replace("RG-One", "rg-one"), "second"));

        assertEquals(1, store.all().size());
        assertEquals("second", store.get(id(ID)).orElseThrow().value().label());
    }

    @Test
    void aLegacyEntryIsReadAndListedAsItIs() throws Exception {
        putLegacy("Sub-1/RG-One/a", new Thing(ID, "legacy"));

        assertEquals("legacy", store.get(id(ID)).orElseThrow().value().label());
        assertEquals(1, store.list(new ArmScope("sub-1", "rg-one")).size());
        assertEquals(1, store.list(new ArmScope("SUB-1", null)).size());
        assertTrue(backend.get("Sub-1/RG-One/a").isPresent(), "a read never rewrites");
    }

    @Test
    void theFirstWriteMovesALegacyEntryToItsCanonicalKey() throws Exception {
        putLegacy("Sub-1/RG-One/a", new Thing(ID, "legacy"));

        store.update(id(ID), thing -> new Thing(thing.id(), "moved"));

        assertEquals(List.of(ID.toLowerCase()), List.copyOf(backend.keys()));
        assertEquals("moved", store.get(id(ID)).orElseThrow().value().label());
    }

    @Test
    void aDeleteRemovesTheCanonicalAndTheLegacyEntry() throws Exception {
        putLegacy("Sub-1/RG-One/a", new Thing(ID, "legacy"));

        assertTrue(store.delete(id(ID)).isPresent());
        assertTrue(backend.keys().isEmpty());
        assertFalse(store.delete(id(ID)).isPresent());
    }

    @Test
    void aDeleteRemovesEveryLegacyEntryThatDiffersOnlyInCase() throws Exception {
        putLegacy("Sub-1/RG-One/a", new Thing(ID, "upper"));
        putLegacy("sub-1/rg-one/a", new Thing(ID.replace("RG-One", "rg-one"), "lower"));

        assertEquals(1, store.all().size());
        assertTrue(store.delete(id(ID)).isPresent());
        assertTrue(backend.keys().isEmpty());
        assertTrue(store.get(id(ID)).isEmpty());
    }

    @Test
    void aWriteMovesEveryLegacyCasingToTheOneCanonicalKey() throws Exception {
        putLegacy("Sub-1/RG-One/a", new Thing(ID, "upper"));
        putLegacy("sub-1/rg-one/a", new Thing(ID.replace("RG-One", "rg-one"), "lower"));

        store.put(new Thing(ID, "written"));

        assertEquals(List.of(ID.toLowerCase()), List.copyOf(backend.keys()));
    }

    @Test
    void aReadOfSeveralLegacyCasingsTakesTheMostRecentlyWritten() throws Exception {
        putLegacy("Sub-1/RG-One/a", new Thing(ID, "older"), Instant.parse("2026-01-01T00:00:00Z"));
        putLegacy("sub-1/rg-one/a", new Thing(ID, "newer"), Instant.parse("2026-02-01T00:00:00Z"));

        assertEquals("newer", store.get(id(ID)).orElseThrow().value().label());
        assertEquals("newer", store.all().getFirst().value().label());
    }

    @Test
    void createOrUpdateCreatesOnceAndHandsTheSecondWriteTheFirstsValue() {
        ArmResourceStore.Written<Thing> created =
            store.createOrUpdate(id(ID), existing -> existing.orElse(new Thing(ID, "first")));
        ArmResourceStore.Written<Thing> updated =
            store.createOrUpdate(id(ID.toLowerCase()), existing -> existing.orElse(new Thing(ID, "second")));

        assertTrue(created.created());
        assertFalse(updated.created());
        assertEquals("first", updated.stored().value().label());
        assertEquals(1, store.all().size());
    }

    @Test
    void aCanonicalEntryWinsOverALegacyOneForTheSameResource() throws Exception {
        store.put(new Thing(ID, "canonical"));
        putLegacy("Sub-1/RG-One/a", new Thing(ID, "stale"));

        assertEquals(1, store.all().size());
        assertEquals("canonical", store.all().getFirst().value().label());
    }

    @Test
    void theStoreIgnoresAndKeepsKeysItDoesNotOwn() throws Exception {
        putLegacy("other:x", new Thing(ID.replace("/a", "/x"), "not mine"));
        store.put(new Thing(ID, "mine"));

        assertEquals(1, store.all().size());
        store.clear();
        assertEquals(List.of("other:x"), List.copyOf(backend.keys()));
    }
}
