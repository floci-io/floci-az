package io.floci.az.services.search;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What changed in storage since the Solr collections last caught up: the documents a batch wrote or deleted, and the
 * indexes that were deleted. The index and document operations record here, under their write lock;
 * {@link SearchIndexSync} takes the changes before a query and applies them to Solr. Recording costs no call to
 * Solr, so a write never waits for the sidecar and works without it.
 *
 * <p>An index is named by its id: the cloud, the service and the index, lowercased and separated by null bytes, as
 * in the storage keys.
 */
@ApplicationScoped
public class SearchChanges {

    private final Map<String, Set<String>> documents = new HashMap<>();
    private final Set<String> droppedIndexes = new LinkedHashSet<>();
    /** Told after indexes are deleted; set once by {@link SearchIndexSync}. */
    private volatile Runnable dropListener = () -> { };

    /**
     * Who to tell after indexes are deleted, outside this object's lock. It runs on the deleting thread, so it must
     * only hand the work on.
     */
    void onIndexesDropped(Runnable listener) {
        dropListener = listener;
    }

    /** The id of an index. */
    static String indexId(String cloud, String service, String index) {
        return String.join("\0", cloud, service, index).toLowerCase(Locale.ROOT);
    }

    /** Documents of an index were written or deleted. */
    synchronized void documentsChanged(String indexId, Collection<String> keys) {
        if (!keys.isEmpty()) {
            documents.computeIfAbsent(indexId, id -> new HashSet<>()).addAll(keys);
        }
    }

    /** Indexes were deleted, with their documents. */
    void indexesDropped(Collection<String> indexIds) {
        synchronized (this) {
            droppedIndexes.addAll(indexIds);
            indexIds.forEach(documents::remove);
        }
        dropListener.run();
    }

    /** The keys of the documents of an index that changed since the last call, which forgets them. */
    synchronized Set<String> takeDocuments(String indexId) {
        Set<String> keys = documents.remove(indexId);
        return keys == null ? Set.of() : keys;
    }

    /** The indexes deleted and not yet taken with {@link #takeDropped}. */
    synchronized Set<String> droppedIndexes() {
        return new HashSet<>(droppedIndexes);
    }

    /**
     * Whether the index was deleted since its collection was last checked, which forgets it. Called under the index's
     * sync lock, so the query that sees an index again after its delete and the clean-up never both miss it.
     */
    synchronized boolean takeDropped(String indexId) {
        return droppedIndexes.remove(indexId);
    }
}
