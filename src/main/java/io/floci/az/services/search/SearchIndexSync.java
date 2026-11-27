package io.floci.az.services.search;

import io.floci.az.services.search.SearchSolrManager.Engine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Brings an index's Solr collection up to date with storage just before it is queried. Storage is the source of
 * truth; a collection is a derived copy that can always be built again.
 *
 * <ul>
 *   <li>A collection that does not exist yet in the running container, or was built from another definition of the
 *       index (or an older {@link SearchSolrSchema#MAPPING_VERSION}), is built from scratch: created, given its
 *       schema, and filled with every stored document. So an index update, which may change analyzers or
 *       similarity, rebuilds its collection, and so does a new container.</li>
 *   <li>Otherwise only the documents {@link SearchChanges} recorded since the last query are sent again, as they are
 *       now in storage: added, or deleted when storage no longer has them.</li>
 *   <li>The collections of deleted indexes are deleted on a background thread, started by the delete itself and
 *       after each query, and only in a container already running: a delete never starts Solr. One whose delete
 *       fails is tried again after the next query. An index deleted and created again is rebuilt by its next
 *       query.</li>
 * </ul>
 *
 * <p>A query runs inside {@link #locked} for its index, which covers reading the definition, catching up and the
 * Solr query itself: a query never sees its collection rebuilt under it, and never builds it from a definition an
 * index update has replaced. Queries of different indexes do not wait for each other.
 */
@ApplicationScoped
public class SearchIndexSync {

    private static final Logger LOG = Logger.getLogger(SearchIndexSync.class);

    /** Solr could not be used: the engine is disabled, did not start, or stopped answering. */
    static final class Unavailable extends RuntimeException {
        Unavailable(String message) {
            super(message, null, false, false);
        }
    }

    /** A collection as it was built: in which container, from which definition, under which mapping. */
    private record Built(long generation, String collection, Map<String, Object> definition, int mapping) {
    }

    /** The collection of a deleted index, still to be deleted from the container it was built in. */
    private record Orphan(String indexId, long generation) {
    }

    private final SearchSolrManager manager;
    private final SearchSolrClient client;
    private final SearchChanges changes;
    private final SearchDocuments documents;
    /** By index id; an entry changes only under its index's lock. */
    private final Map<String, Built> built = new ConcurrentHashMap<>();
    /** The lock of each index id. */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final ExecutorService retirer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "floci-az-search-retire");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean retirementScheduled = new AtomicBoolean();
    /** Set by each request for a retirement, so one made while a retirement runs is not lost. */
    private final AtomicBoolean retirementRequested = new AtomicBoolean();
    /** By collection name. Guarded by itself; an entry is deleted from Solr under its index's lock. */
    private final Map<String, Orphan> orphans = new HashMap<>();

    @Inject
    public SearchIndexSync(SearchSolrManager manager, SearchSolrClient client, SearchChanges changes,
                           SearchDocuments documents) {
        this.manager = manager;
        this.client = client;
        this.changes = changes;
        this.documents = documents;
    }

    /**
     * Runs a query of an index under its lock. The collections of deleted indexes are deleted in the background, so
     * a slow delete never holds up a query.
     */
    <T> T locked(String cloud, String service, String index, Supplier<T> query) {
        scheduleRetirement();
        synchronized (lock(SearchChanges.indexId(cloud, service, index))) {
            return query.get();
        }
    }

    /**
     * Makes the collection of an index current and answers the engine and the collection to query. The caller holds
     * the index's lock ({@link #locked}) and read {@code definition} under it.
     *
     * @throws Unavailable when Solr cannot be used
     */
    Target current(String cloud, String service, String index, Map<String, Object> definition) {
        Engine engine = available();
        String id = SearchChanges.indexId(cloud, service, index);
        try {
            forgetEarlierContainers(engine);
            if (changes.takeDropped(id)) {
                // Deleted and created again since its collection was built: what that holds is not this index's.
                built.remove(id);
            }
            Built existing = built.get(id);
            Built target = existing;
            if (existing == null || existing.generation() != engine.generation()
                    || !existing.definition().equals(definition)
                    || existing.mapping() != SearchSolrSchema.MAPPING_VERSION) {
                target = rebuild(engine, id, existing, cloud, service, index, definition);
            } else {
                catchUp(engine, existing, cloud, service, index, definition);
            }
            // The local record: a query of another index that loses the engine may clear the map meanwhile.
            return new Target(engine, target.collection());
        } catch (SearchSolrClient.SolrUnreachable e) {
            lost(engine);
            throw new Unavailable("The Solr sidecar stopped answering: " + e.getMessage());
        } catch (SearchSolrClient.SolrError e) {
            // A collection left half built is built again by the next query.
            built.remove(id);
            throw new Unavailable("The Solr sidecar refused to index " + index + ": " + e.getMessage());
        }
    }

    /**
     * The running engine, starting it if need be.
     *
     * @throws Unavailable when Solr cannot be used
     */
    Engine available() {
        return manager.await().orElseThrow(() -> new Unavailable(unavailableMessage()));
    }

    /**
     * Solr stopped answering while a query ran: forget what was built in that container (not in a newer one), so the
     * next query starts and fills a new one.
     */
    void lost(Engine engine) {
        built.values().removeIf(entry -> entry.generation() <= engine.generation());
        synchronized (orphans) {
            orphans.values().removeIf(orphan -> orphan.generation() <= engine.generation());
        }
        manager.lost(engine);
    }

    /** The engine and collection a query runs against. */
    record Target(Engine engine, String collection) {
    }

    /** A deleted index's collection is deleted without waiting for a query, which may never come. */
    @PostConstruct
    void listenForDrops() {
        changes.onIndexesDropped(this::scheduleRetirement);
    }

    @PreDestroy
    void shutdown() {
        retirer.shutdownNow();
    }

    private Object lock(String indexId) {
        return locks.computeIfAbsent(indexId, id -> new Object());
    }

    private void scheduleRetirement() {
        boolean pending;
        synchronized (orphans) {
            pending = !orphans.isEmpty();
        }
        if (!pending && changes.droppedIndexes().isEmpty()) {
            return;
        }
        // Requested before it is scheduled: a retirement already running sees the request when it finishes.
        retirementRequested.set(true);
        startRetirement();
    }

    private void startRetirement() {
        if (!retirementScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            retirer.execute(() -> {
                try {
                    while (retirementRequested.getAndSet(false)) {
                        manager.running().ifPresent(this::retireDropped);
                    }
                } finally {
                    retirementScheduled.set(false);
                }
                if (retirementRequested.get()) {
                    // Requested after the loop's last check.
                    startRetirement();
                }
            });
        } catch (RejectedExecutionException e) {
            // Shutting down.
            retirementScheduled.set(false);
        }
    }

    /**
     * On the background thread: deletes the collections of deleted indexes, each under its index's lock; a failure
     * is tried again after the next query.
     */
    private void retireDropped(Engine engine) {
        for (String id : changes.droppedIndexes()) {
            synchronized (lock(id)) {
                if (!changes.takeDropped(id)) {
                    // A query of the index created again took it, and rebuilt the collection.
                    continue;
                }
                Built gone = built.remove(id);
                if (gone != null) {
                    synchronized (orphans) {
                        orphans.put(gone.collection(), new Orphan(id, gone.generation()));
                    }
                }
            }
        }
        Map<String, Orphan> pending;
        synchronized (orphans) {
            pending = new HashMap<>(orphans);
        }
        for (Map.Entry<String, Orphan> entry : pending.entrySet()) {
            synchronized (lock(entry.getValue().indexId())) {
                synchronized (orphans) {
                    if (orphans.get(entry.getKey()) != entry.getValue()) {
                        // Built again meanwhile, which deleted it first.
                        continue;
                    }
                }
                try {
                    if (entry.getValue().generation() == engine.generation()) {
                        client.deleteCollection(engine.baseUrl(), entry.getKey());
                    }
                    synchronized (orphans) {
                        orphans.remove(entry.getKey());
                    }
                } catch (RuntimeException e) {
                    LOG.warnv("Search: the Solr collection {0} of a deleted index could not be deleted yet: {1}",
                            entry.getKey(), e.getMessage());
                    return;
                }
            }
        }
    }

    // ── Under the index's lock ───────────────────────────────────────────────

    private void forgetEarlierContainers(Engine engine) {
        built.values().removeIf(entry -> entry.generation() < engine.generation());
    }

    private Built rebuild(Engine engine, String id, Built existing, String cloud, String service, String index,
                          Map<String, Object> definition) {
        built.remove(id);
        String collection = SearchSolrSchema.collection(cloud, service, index);
        if (existing != null && !existing.collection().equals(collection)) {
            client.deleteCollection(engine.baseUrl(), existing.collection());
        }
        // Also when nothing is recorded: a build that failed half way, or a deleted index of the same name, leaves
        // its collection behind.
        client.deleteCollection(engine.baseUrl(), collection);
        synchronized (orphans) {
            orphans.remove(collection);
        }
        // Taken before storage is read: a document written after this is recorded again and sent by the next query.
        changes.takeDocuments(id);
        client.createCollection(engine.baseUrl(), collection, SearchSolrSchema.BASE_CONFIG_SET);
        Map<String, Object> schema = SearchSolrSchema.schema(definition);
        if (!schema.isEmpty()) {
            client.schema(engine.baseUrl(), collection, schema);
        }
        SearchSolrSchema.Layout layout = SearchSolrSchema.layout(definition);
        List<Map<String, Object>> adds = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> document : documents.stored(cloud, service, index).entrySet()) {
            adds.add(SearchSolrSchema.document(layout, document.getKey(), document.getValue()));
        }
        int count = adds.size();
        client.update(engine.baseUrl(), collection, adds, List.of());
        Built done = new Built(engine.generation(), collection, definition, SearchSolrSchema.MAPPING_VERSION);
        built.put(id, done);
        LOG.infov("Search: built the Solr collection of index {0} in service {1} ({2} documents)", index, service,
                count);
        return done;
    }

    private void catchUp(Engine engine, Built existing, String cloud, String service, String index,
                         Map<String, Object> definition) {
        Set<String> keys = changes.takeDocuments(SearchChanges.indexId(cloud, service, index));
        if (keys.isEmpty()) {
            return;
        }
        SearchSolrSchema.Layout layout = SearchSolrSchema.layout(definition);
        Map<String, Map<String, Object>> present = documents.stored(cloud, service, index, keys);
        List<Map<String, Object>> adds = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> document : present.entrySet()) {
            adds.add(SearchSolrSchema.document(layout, document.getKey(), document.getValue()));
        }
        // Deleting a document by its key deletes its child documents too, and adding it replaces them.
        Set<String> deletes = new HashSet<>(keys);
        deletes.removeAll(present.keySet());
        try {
            client.update(engine.baseUrl(), existing.collection(), adds, deletes);
        } catch (RuntimeException e) {
            // Sent again by the next query.
            changes.documentsChanged(SearchChanges.indexId(cloud, service, index), keys);
            throw e;
        }
    }

    private String unavailableMessage() {
        return manager.enabled()
                ? "The Solr sidecar that serves Azure AI Search queries is not running. It needs Docker; see the"
                        + " floci-az log for why it did not start."
                : "Azure AI Search queries are served by a Solr sidecar, which is turned off"
                        + " (floci-az.services.search.engine.enabled=false).";
    }
}
