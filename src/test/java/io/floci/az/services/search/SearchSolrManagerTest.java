package io.floci.az.services.search;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * When the Solr sidecar is pulled, started and removed, with Docker and Solr mocked: nothing happens until a search
 * service asks for it, one container serves every request, a removal waits out its grace, and a failed start is not
 * retried at once.
 */
@DisplayName("Azure AI Search: the Solr sidecar's lifecycle")
class SearchSolrManagerTest {

    private static final String CONTAINER = "floci-az-search-default";

    private EmulatorConfig config;
    private ContainerBuilder builder;
    private ContainerLifecycleManager containers;
    private SearchSolrClient client;
    private SearchSolrManager manager;

    @BeforeEach
    void mockDockerAndSolr() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().search().enabled()).thenReturn(true);
        when(config.services().search().engine().enabled()).thenReturn(true);
        when(config.services().search().engine().image()).thenReturn("solr:10.1.0-slim");
        when(config.services().search().engine().heap()).thenReturn("256m");
        when(config.services().search().engine().startTimeoutSeconds()).thenReturn(5);
        when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());

        builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(spec.build()).thenReturn(mock(ContainerSpec.class));
        when(builder.newContainer(anyString())).thenReturn(spec);

        containers = mock(ContainerLifecycleManager.class);
        when(containers.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c1",
                Map.of(SearchSolrManager.SOLR_PORT, new ContainerLifecycleManager.EndpointInfo("localhost", 41000))));

        client = mock(SearchSolrClient.class);
        when(client.ready(any())).thenReturn(true);
        manager = new SearchSolrManager(config, builder, containers, client, Duration.ZERO);
    }

    @Test
    void theFirstRequestStartsOneContainerThatEveryLaterOneShares() {
        manager.prepare();
        manager.prepare();
        Optional<SearchSolrManager.Engine> engine = manager.await();

        assertThat(engine.isPresent(), is(true));
        assertThat(engine.get().baseUrl(), equalTo(URI.create("http://localhost:41000")));
        assertThat(manager.await().get().generation(), equalTo(engine.get().generation()));
        verify(containers, times(1)).createAndStart(any());
        verify(containers).removeIfExists(CONTAINER);
        verify(client).uploadConfigSet(eq(URI.create("http://localhost:41000")),
                eq(SearchSolrSchema.BASE_CONFIG_SET), any());
    }

    @Test
    void aDisabledEngineIsNeverPulledOrStarted() {
        when(config.services().search().engine().enabled()).thenReturn(false);

        manager.prepare();

        assertThat(manager.await().isPresent(), is(false));
        verifyNoInteractions(containers, builder, client);
    }

    @Test
    void stoppingRemovesTheContainerAndTheNextRequestStartsANewOne() {
        long first = manager.await().orElseThrow().generation();

        manager.stop();
        verify(containers, timeout(5000).times(2)).removeIfExists(CONTAINER);

        assertThat(manager.await().orElseThrow().generation(), equalTo(first + 1));
        verify(containers, times(2)).createAndStart(any());
    }

    @Test
    void aServiceCreatedWithinTheGraceKeepsTheContainer() {
        SearchSolrManager patient = new SearchSolrManager(config, builder, containers, client, Duration.ofHours(1));
        long first = patient.await().orElseThrow().generation();

        patient.stop();
        patient.prepare();

        assertThat(patient.await().orElseThrow().generation(), equalTo(first));
        verify(containers, times(1)).createAndStart(any());
        verify(containers, times(1)).removeIfExists(CONTAINER);
    }

    @Test
    void aFailedStartAnswersNoEngineAndIsNotRetriedAtOnce() {
        when(containers.createAndStart(any())).thenThrow(new IllegalStateException("no Docker"));

        assertThat(manager.await().isPresent(), is(false));
        manager.prepare();
        assertThat(manager.await().isPresent(), is(false));

        verify(containers, times(1)).createAndStart(any());
        verify(client, never()).uploadConfigSet(any(), anyString(), any());
    }

    @Test
    void aLostEngineIsStartedAgainByTheNextRequest() {
        SearchSolrManager.Engine engine = manager.await().orElseThrow();

        manager.lost(engine);

        assertThat(manager.await().orElseThrow().generation(), equalTo(engine.generation() + 1));
        verify(containers, times(2)).createAndStart(any());
    }

    @Test
    void shutdownRemovesAContainerThatWasLost() {
        manager.lost(manager.await().orElseThrow());

        manager.shutdown();

        verify(containers, times(2)).removeIfExists(CONTAINER);
    }
}
