package io.floci.az.services.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How queries of indexes share the Solr collections, with Solr mocked: a query of an index waits for another of the
 * same index, never for one of another; the failed delete of a deleted index's collection is tried again without
 * failing a query; an index deleted and created again is rebuilt; and an engine lost midway fails no other query.
 */
@DisplayName("Azure AI Search: catching up the Solr collections")
class SearchIndexSyncTest {

    private static final URI BASE = URI.create("http://localhost:41000");
    private static final Map<String, Object> DEFINITION = Map.of("name", "idx",
            "fields", List.of(Map.of("name", "id", "type", "Edm.String", "key", true, "searchable", false)));

    private SearchSolrClient client;
    private SearchChanges changes;
    private SearchIndexSync sync;
    private SearchSolrManager.Engine engine;

    @BeforeEach
    void mockSolr() {
        engine = new SearchSolrManager.Engine("c1", BASE, 1);
        SearchSolrManager manager = mock(SearchSolrManager.class);
        when(manager.await()).thenReturn(Optional.of(engine));
        when(manager.running()).thenReturn(Optional.of(engine));
        client = mock(SearchSolrClient.class);
        changes = new SearchChanges();
        sync = new SearchIndexSync(manager, client, changes, mock(SearchDocuments.class));
    }

    private String query(String index) {
        return sync.locked("c", "svc", index, () -> sync.current("c", "svc", index, DEFINITION).collection());
    }

    @Test
    void aQueryWaitsForAnotherOfTheSameIndexButNotOfAnother() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> sync.locked("c", "svc", "a", () -> {
            inside.countDown();
            await(release);
            return null;
        }));
        await(inside);

        assertThat(query("b"), equalTo(SearchSolrSchema.collection("c", "svc", "b")));
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> query("a"));
        Thread.sleep(200);
        assertThat(second.isDone(), is(false));

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        assertThat(second.get(5, TimeUnit.SECONDS), equalTo(SearchSolrSchema.collection("c", "svc", "a")));
    }

    @Test
    void aFailedDeleteOfADroppedCollectionIsRetriedWithoutFailingTheQuery() {
        String dropped = SearchSolrSchema.collection("c", "svc", "a");
        query("a");
        changes.indexesDropped(List.of(SearchChanges.indexId("c", "svc", "a")));
        doThrow(new SearchSolrClient.SolrError(500, "busy")).doNothing()
                .when(client).deleteCollection(BASE, dropped);

        // Once by its own build, then in the background: once failed, once retried after the next query.
        query("b");
        verify(client, timeout(5000).times(2)).deleteCollection(BASE, dropped);
        query("b");
        verify(client, timeout(5000).times(3)).deleteCollection(BASE, dropped);
        query("b");
        verify(client, after(300).times(3)).deleteCollection(BASE, dropped);
        verify(client, times(1)).createCollection(eq(BASE), eq(SearchSolrSchema.collection("c", "svc", "b")), any());
    }

    @Test
    void anIndexDeletedAndCreatedAgainWithTheSameDefinitionIsRebuilt() {
        String collection = SearchSolrSchema.collection("c", "svc", "a");
        query("a");

        changes.indexesDropped(List.of(SearchChanges.indexId("c", "svc", "a")));

        assertThat(query("a"), equalTo(collection));
        verify(client, times(2)).createCollection(eq(BASE), eq(collection), any());
    }

    @Test
    void anEngineLostByAnotherQueryMidwayStillAnswersThisOne() {
        String collection = SearchSolrSchema.collection("c", "svc", "a");
        query("a");
        changes.documentsChanged(SearchChanges.indexId("c", "svc", "a"), List.of("gone"));
        doAnswer(invocation -> {
            sync.lost(engine);
            return null;
        }).when(client).update(eq(BASE), eq(collection), any(), any());

        assertThat(query("a"), equalTo(collection));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS), is(true));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
