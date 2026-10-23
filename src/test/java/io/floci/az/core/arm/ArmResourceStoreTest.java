package io.floci.az.core.arm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.InMemoryStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void storesOfTwoTypesShareABackendWithoutSeeingEachOther() throws Exception {
        String other = "/subscriptions/Sub-1/resourceGroups/RG-One/providers/Microsoft.Test/others/a";
        ArmResourceStore<Thing> things = ArmResourceStore.over(backend, "shared", "Microsoft.Test/things",
            Thing.class, Thing::id, key -> key.startsWith("thing/"));
        ArmResourceStore<Thing> others = ArmResourceStore.over(backend, "shared", "Microsoft.Test/others",
            Thing.class, Thing::id, key -> key.startsWith("other/"));
        things.put(new Thing(ID, "thing"));
        others.put(new Thing(other, "other"));
        putLegacy("other/sub-1/rg-one/b", new Thing(other.replace("/a", "/b"), "legacy other"));

        assertEquals(List.of("thing"), things.all().stream().map(stored -> stored.value().label()).toList());
        assertEquals(2, others.all().size());
        things.clear();
        assertEquals(2, others.all().size());
        assertTrue(things.all().isEmpty());
    }

    @Test
    void writesFindLegacyEntriesWithoutWalkingEveryKey() throws Exception {
        CountingStorage counting = new CountingStorage();
        ArmResourceStore<Thing> indexed = ArmResourceStore.over(counting, "things", Thing.class, Thing::id, key -> true);
        counting.put("Sub-1/RG-One/a", new StoredObject("Sub-1/RG-One/a",
            new ObjectMapper().writeValueAsBytes(new Thing(ID, "legacy")), Map.of(), Instant.now(), "etag"));

        for (int i = 0; i < 20; i++) {
            indexed.put(new Thing(ID.replace("/a", "/n" + i), "new"));
        }
        indexed.put(new Thing(ID, "moved"));
        indexed.delete(id(ID.replace("/a", "/n0")));

        assertEquals(1, counting.keyScans, "the legacy keys are indexed once, not walked on every write");
        assertFalse(counting.get("Sub-1/RG-One/a").isPresent(), "the write still moved the legacy entry");
        assertEquals("moved", indexed.get(id(ID)).orElseThrow().value().label());
    }

    @Test
    void shadowedLegacyEntriesAreRemovedAndReturned() throws Exception {
        String other = ID.replace("/a", "/b");
        backend.put("Sub-1/RG-One/a", new StoredObject("Sub-1/RG-One/a",
            new ObjectMapper().writeValueAsBytes(new Thing(ID, "old casing")), Map.of(), Instant.now().minusSeconds(60),
            "etag"));
        backend.put("sub-1/rg-one/a", new StoredObject("sub-1/rg-one/a",
            new ObjectMapper().writeValueAsBytes(new Thing(ID.toLowerCase(), "newest casing")), Map.of(), Instant.now(),
            "etag"));
        putLegacy("Sub-1/RG-One/b", new Thing(other, "legacy b"));
        putLegacy(other.toLowerCase(), new Thing(other, "canonical b"));

        List<String> removed = store.removeShadowedLegacy().stream().map(stored -> stored.value().label()).sorted()
            .toList();

        assertEquals(List.of("legacy b", "old casing"), removed);
        assertEquals("newest casing", store.get(id(ID)).orElseThrow().value().label());
        assertEquals("canonical b", store.get(id(other)).orElseThrow().value().label());
        assertTrue(store.removeShadowedLegacy().isEmpty(), "a second pass finds nothing");
    }

    @Test
    void aTypedStoreNeitherReadsWritesNorDeletesAnotherTypesId() {
        String other = "/subscriptions/Sub-1/resourceGroups/RG-One/providers/Microsoft.Test/others/a";
        ArmResourceStore<Thing> things = ArmResourceStore.over(backend, "shared", "Microsoft.Test/things",
            Thing.class, Thing::id, key -> false);
        ArmResourceStore<Thing> others = ArmResourceStore.over(backend, "shared", "Microsoft.Test/others",
            Thing.class, Thing::id, key -> false);
        others.put(new Thing(other, "other"));

        assertTrue(things.get(id(other)).isEmpty(), "another type's id does not resolve");
        assertTrue(things.delete(id(other)).isEmpty());
        assertEquals("other", others.get(id(other)).orElseThrow().value().label(), "and is not deleted");
        assertThrows(IllegalArgumentException.class, () -> things.put(new Thing(other, "wrong store")));
    }

    private static List<String> labels(List<ArmResourceStore.Stored<Thing>> stored) {
        return stored.stream().map(entry -> entry.value().label()).sorted().toList();
    }

    @Test
    void underReturnsTheChildrenAndExtensionsOfAScopeButNotASiblingWithTheSamePrefix() {
        String sibling = ID + "b";
        store.put(new Thing(ID, "scope"));
        store.put(new Thing(ID + "/children/c", "child"));
        store.put(new Thing(ID + "/providers/Microsoft.Ext/subs/s1", "extension"));
        store.put(new Thing(sibling, "sibling"));
        store.put(new Thing(sibling + "/providers/Microsoft.Ext/subs/s1", "sibling extension"));

        assertEquals(List.of("child", "extension"), labels(store.under(id(ID))));
        assertEquals(List.of("child", "extension"), labels(store.under(id(ID.toUpperCase()))),
            "the scope compares case-insensitively");
        assertEquals(List.of("sibling extension"), labels(store.under(id(sibling))));
    }

    @Test
    void underFindsLegacyEntriesAndPrefersTheCanonicalOne() throws Exception {
        String first = ID + "/providers/Microsoft.Ext/subs/s1";
        String second = ID + "/providers/Microsoft.Ext/subs/s2";
        putLegacy("Sub-1/RG-One/a/s1", new Thing(first, "legacy s1"));
        putLegacy("Sub-1/RG-One/a/s2", new Thing(second, "legacy s2"));
        putLegacy("Sub-1/RG-One/ab/s1", new Thing(ID + "b/providers/Microsoft.Ext/subs/s1", "legacy sibling"));
        putLegacy(second.toLowerCase(), new Thing(second, "canonical s2"));

        assertEquals(List.of("canonical s2", "legacy s1"), labels(store.under(id(ID))));
    }

    @Test
    void underInATypedStoreSkipsAnotherTypesEntries() {
        ArmResourceStore<Thing> things = ArmResourceStore.over(backend, "shared", "Microsoft.Ext/subs",
            Thing.class, Thing::id, key -> false);
        ArmResourceStore<Thing> others = ArmResourceStore.over(backend, "shared", "Microsoft.Ext/others",
            Thing.class, Thing::id, key -> false);
        things.put(new Thing(ID + "/providers/Microsoft.Ext/subs/s1", "mine"));
        others.put(new Thing(ID + "/providers/Microsoft.Ext/others/o1", "theirs"));

        assertEquals(List.of("mine"), labels(things.under(id(ID))));
    }

    @Test
    void underDecodesOnlyTheEntriesUnderTheScope() {
        CountingStorage counting = new CountingStorage();
        ArmResourceStore<Thing> counted = ArmResourceStore.over(counting, "things", Thing.class, Thing::id,
            key -> true);
        for (int i = 0; i < 20; i++) {
            counted.put(new Thing(ID.replace("/a", "/n" + i) + "/providers/Microsoft.Ext/subs/s", "elsewhere"));
        }
        counted.put(new Thing(ID + "/providers/Microsoft.Ext/subs/s", "here"));
        counting.reads = 0;

        assertEquals(List.of("here"), labels(counted.under(id(ID))));
        assertEquals(1, counting.reads, "only the matching entry is read");
    }

    /** Counts how often the store walks the backend's keys. */
    static final class CountingStorage extends InMemoryStorage<String, StoredObject> {
        int keyScans;
        int reads;

        @Override
        public Set<String> keys() {
            keyScans++;
            return super.keys();
        }

        @Override
        public Optional<StoredObject> get(String key) {
            reads++;
            return super.get(key);
        }
    }
}
