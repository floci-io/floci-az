package io.floci.az.services.acr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repository namespacing the proxy applies: one shared container backs every registry, so the
 * registry name is prefixed on the way in and stripped from everything the client reads back.
 */
@DisplayName("AcrRegistryProxy: repository prefixing")
class AcrRegistryProxyTest {

    @Test
    void prefixesRepositoryScopedPathsWithTheRegistryName() {
        assertEquals("v2/myreg/app/manifests/v1",
                AcrRegistryProxy.backendPath("myreg", "v2/app/manifests/v1"));
        assertEquals("v2/myreg/team/app/blobs/sha256:abc",
                AcrRegistryProxy.backendPath("myreg", "v2/team/app/blobs/sha256:abc"));
        assertEquals("v2/myreg/app/blobs/uploads/",
                AcrRegistryProxy.backendPath("myreg", "v2/app/blobs/uploads/"));
        assertEquals("v2/myreg/app/tags/list",
                AcrRegistryProxy.backendPath("myreg", "v2/app/tags/list"));
    }

    @Test
    void leavesRegistryScopedPathsAlone() {
        assertEquals("v2/", AcrRegistryProxy.backendPath("myreg", "v2/"));
        assertEquals("v2/_catalog", AcrRegistryProxy.backendPath("myreg", "v2/_catalog"));
    }

    @Test
    void stripsThePrefixFromAnUploadSessionLocation() {
        assertEquals("/v2/app/blobs/uploads/abc-123?_state=xyz",
                AcrRegistryProxy.clientLocation("myreg", "/v2/myreg/app/blobs/uploads/abc-123?_state=xyz"));
    }

    @Test
    void rewritesAnAbsoluteLocationToAPathTheClientCanFollow() {
        assertEquals("/v2/app/blobs/uploads/abc-123",
                AcrRegistryProxy.clientLocation("myreg",
                        "http://floci-az-acr-registry:5000/v2/myreg/app/blobs/uploads/abc-123"));
    }

    @Test
    void leavesALocationThatCarriesNoPrefixUntouched() {
        assertEquals("/v2/other/manifests/v1",
                AcrRegistryProxy.clientLocation("myreg", "/v2/other/manifests/v1"));
        assertEquals(null, AcrRegistryProxy.clientLocation("myreg", null));
    }

    @Test
    void catalogShowsOnlyThisRegistrysRepositoriesWithoutThePrefix() {
        byte[] shared = ("{\"repositories\":[\"myreg/app\",\"myreg/team/api\"]}")
                .getBytes(StandardCharsets.UTF_8);

        assertEquals(List.of("app", "team/api"), AcrRegistryProxy.ownRepositories("myreg/", shared));
    }

    @Test
    void catalogStopsReadingAtTheFirstRepositoryOutsideThisRegistry() {
        // The container walks a directory tree, so a registry's repositories are a contiguous
        // subtree. The first outsider ends the block and nothing of ours follows it, which is what
        // lets one page be answered by one backend request.
        byte[] shared = ("{\"repositories\":[\"myreg/app\",\"myreg/web\",\"myreg-1/app\","
                + "\"myreg0/x\",\"otherreg/db\"]}").getBytes(StandardCharsets.UTF_8);

        assertEquals(List.of("app", "web"), AcrRegistryProxy.ownRepositories("myreg/", shared));
    }

    @Test
    void catalogStopsAtTheBlockEdgeRatherThanSkippingPastIt() {
        // A real container cannot produce this, because the block is a subtree. Stopping rather
        // than skipping is what makes that an invariant: collecting across a gap would count
        // repositories the page size did not account for and mint a cursor that skips the gap.
        byte[] shared = ("{\"repositories\":[\"myreg/app\",\"otherreg/db\",\"myreg/web\"]}")
                .getBytes(StandardCharsets.UTF_8);

        assertEquals(List.of("app"), AcrRegistryProxy.ownRepositories("myreg/", shared));
    }

    @Test
    void catalogReadsNothingWhenTheBlockHasNotStarted() {
        byte[] shared = "{\"repositories\":[\"otherreg/db\"]}".getBytes(StandardCharsets.UTF_8);

        assertEquals(List.of(), AcrRegistryProxy.ownRepositories("myreg/", shared));
    }

    @Test
    void aPageSizeIsReadOnlyWhenTheClientAskedForOne() {
        assertEquals(AcrRegistryProxy.UNPAGINATED, AcrRegistryProxy.pageSize(null));
        assertEquals(AcrRegistryProxy.UNPAGINATED, AcrRegistryProxy.pageSize(""));
        assertEquals(AcrRegistryProxy.UNPAGINATED, AcrRegistryProxy.pageSize("not-a-number"));
        assertEquals(AcrRegistryProxy.UNPAGINATED, AcrRegistryProxy.pageSize("-4"));
        assertEquals(10, AcrRegistryProxy.pageSize(" 10 "));
    }

    @Test
    void askingForNoRepositoriesIsNotAskingForAllOfThem() {
        // n=0 and no n at all used to collapse to the same answer, which handed a client that
        // asked for an empty page the entire catalog.
        assertEquals(0, AcrRegistryProxy.pageSize("0"));
        assertNotEquals(AcrRegistryProxy.pageSize("0"), AcrRegistryProxy.pageSize(null));
    }

    @Test
    void theNextLinkCarriesAnUnprefixedCursorInTheRegistrysOwnShape() {
        assertEquals("</v2/_catalog?last=team%2Fapi&n=2>; rel=\"next\"",
                AcrRegistryProxy.nextLink("team/api", 2));
    }

    @Test
    void tagsListNamesTheRepositoryTheClientAskedAbout() {
        byte[] backend = "{\"name\":\"myreg/team/api\",\"tags\":[\"v1\"]}".getBytes(StandardCharsets.UTF_8);

        assertEquals("{\"name\":\"team/api\",\"tags\":[\"v1\"]}",
                new String(AcrRegistryProxy.unprefixRepositoryName("myreg", backend),
                        StandardCharsets.UTF_8));
    }

    @Test
    void onlyBodiesThatNameRepositoriesAreRewritten() {
        // The catalog is not among them: it is built from the container's answer rather than
        // rewritten in place, because it is also paginated.
        assertFalse(AcrRegistryProxy.rewritesBody("v2/_catalog"));
        assertTrue(AcrRegistryProxy.rewritesBody("v2/app/tags/list"));
        assertFalse(AcrRegistryProxy.rewritesBody("v2/app/manifests/v1"));
        assertFalse(AcrRegistryProxy.rewritesBody("v2/app/blobs/sha256:abc"));
    }

    @Test
    void catalogReadsNothingWhenTheBodyIsNotTheExpectedShape() {
        assertEquals(List.of(),
                AcrRegistryProxy.ownRepositories("myreg/", "<html/>".getBytes(StandardCharsets.UTF_8)));
    }
}
