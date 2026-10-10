package io.floci.az.services.acr;

import io.floci.az.services.acr.AcrTokenService.Access;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scope parsing and the bearer challenge, the two pieces of the ACR token exchange that are pure
 * functions of what the client sent. Scope forms are taken from Azure CLI 2.89.1
 * ({@code azure/cli/command_modules/acr/_docker_utils.py}).
 */
@DisplayName("AcrTokenService: scope parsing and the bearer challenge")
class AcrTokenServiceTest {

    @Test
    void parsesTheRepositoryScopeTheAzureCliSends() {
        Access access = AcrTokenService.parseScope("repository:app:pull,push").orElseThrow();

        assertEquals("repository", access.type());
        assertEquals("app", access.name());
        assertEquals(List.of("pull", "push"), access.actions());
    }

    @Test
    void keepsSlashesInARepositoryName() {
        Access access = AcrTokenService.parseScope("repository:team/app:metadata_read").orElseThrow();

        assertEquals("team/app", access.name());
        assertEquals(List.of("metadata_read"), access.actions());
    }

    @Test
    void parsesTheArtifactRepositoryScope() {
        Access access = AcrTokenService.parseScope("artifact-repository:charts:pull").orElseThrow();

        assertEquals("artifact-repository", access.type());
        assertEquals("charts", access.name());
    }

    @Test
    void parsesTheRegistryScopeWhoseActionIsAlwaysAStar() {
        Access access = AcrTokenService.parseScope("registry:catalog:*").orElseThrow();

        assertEquals("registry", access.type());
        assertEquals("catalog", access.name());
        assertEquals(List.of("*"), access.actions());
    }

    @Test
    void rejectsScopesThatAreNotTypeNameActions() {
        assertEquals(Optional.empty(), AcrTokenService.parseScope(null));
        assertEquals(Optional.empty(), AcrTokenService.parseScope(""));
        assertEquals(Optional.empty(), AcrTokenService.parseScope("repository"));
        assertEquals(Optional.empty(), AcrTokenService.parseScope("repository:app"));
        assertEquals(Optional.empty(), AcrTokenService.parseScope("repository:app:"));
        assertEquals(Optional.empty(), AcrTokenService.parseScope(":app:pull"));
    }

    @Test
    void parsesEveryScopeWhenTheParameterRepeatsOrCarriesSeveral() {
        List<Access> access = AcrTokenService.parseScopes(
                List.of("repository:app:pull repository:other:push", "registry:catalog:*"));

        assertEquals(3, access.size());
        assertEquals("app", access.get(0).name());
        assertEquals("other", access.get(1).name());
        assertEquals("catalog", access.get(2).name());
    }

    @Test
    void skipsUnparseableScopesRatherThanFailingTheRequest() {
        assertEquals(List.of(), AcrTokenService.parseScopes(List.of("")));
        assertEquals(List.of(), AcrTokenService.parseScopes(null));
        assertEquals(1, AcrTokenService.parseScopes(List.of("nonsense repository:app:pull")).size());
    }

    @Test
    void challengeNamesTheRealmAndServiceTheClientParses() {
        String challenge = AcrTokenService.challenge("https", "myregistry.azurecr.io");

        assertEquals("Bearer realm=\"https://myregistry.azurecr.io/oauth2/token\","
                + "service=\"myregistry.azurecr.io\"", challenge);
        // The CLI splits on ' ' then ',' and requires both parameters to be quoted.
        assertTrue(challenge.startsWith("Bearer "));
    }

    @Test
    void challengeRealmCarriesTheSchemeTheCallerUsed() {
        // TLS is off by default, and a realm hardcoded to https:// would send the client to a port
        // nothing is listening on. The service is the login server either way.
        assertEquals("Bearer realm=\"http://myregistry.azurecr.io/oauth2/token\","
                + "service=\"myregistry.azurecr.io\"",
                AcrTokenService.challenge("http", "myregistry.azurecr.io"));
    }

    @Test
    void aScopedChallengeAddsTheScopeTheSdkClientsRequire() {
        // The Java, Python and .NET container-registry clients start the token exchange only when
        // the challenge carries both service and scope.
        assertEquals("Bearer realm=\"https://myregistry.azurecr.io/oauth2/token\","
                + "service=\"myregistry.azurecr.io\",scope=\"registry:catalog:*\"",
                AcrTokenService.challenge("https", "myregistry.azurecr.io", "registry:catalog:*"));
    }

    @Test
    void thePingNamesNoResourceSoItsChallengeCarriesNoScope() {
        assertNull(AcrTokenService.scopeFor("GET", "v2"));
        assertNull(AcrTokenService.scopeFor("GET", "/v2/"));
    }

    @Test
    void bothCatalogsNeedTheRegistryCatalogScope() {
        assertEquals("registry:catalog:*", AcrTokenService.scopeFor("GET", "v2/_catalog"));
        assertEquals("registry:catalog:*", AcrTokenService.scopeFor("GET", "acr/v1/_catalog"));
        assertEquals("registry:catalog:*", AcrTokenService.scopeFor("GET", "/acr/v1/_catalog/"));
    }

    @Test
    void registryReadsNeedPullWritesNeedPullAndPushDeletesNeedDelete() {
        assertEquals("repository:app:pull", AcrTokenService.scopeFor("GET", "v2/app/manifests/latest"));
        assertEquals("repository:app:pull", AcrTokenService.scopeFor("HEAD", "v2/app/blobs/sha256:abc"));
        assertEquals("repository:app:pull", AcrTokenService.scopeFor("GET", "v2/app/tags/list"));
        assertEquals("repository:app:pull,push", AcrTokenService.scopeFor("PUT", "v2/app/manifests/v1"));
        assertEquals("repository:app:pull,push", AcrTokenService.scopeFor("POST", "v2/app/blobs/uploads/"));
        assertEquals("repository:app:pull,push",
                AcrTokenService.scopeFor("PATCH", "v2/app/blobs/uploads/abc-123"));
        assertEquals("repository:app:delete", AcrTokenService.scopeFor("DELETE", "v2/app/manifests/sha256:abc"));
    }

    @Test
    void aRegistryPathKeepsTheSlashesInANestedRepositoryName() {
        assertEquals("repository:team/sub/app:pull",
                AcrTokenService.scopeFor("GET", "v2/team/sub/app/manifests/latest"));
        assertEquals("repository:team/app:pull,push",
                AcrTokenService.scopeFor("PUT", "v2/team/app/blobs/uploads/abc-123"));
    }

    @Test
    void metadataReadsNeedMetadataReadUpdatesNeedMetadataWriteDeletesNeedDelete() {
        assertEquals("repository:app:metadata_read", AcrTokenService.scopeFor("GET", "acr/v1/app"));
        assertEquals("repository:team/app:metadata_read", AcrTokenService.scopeFor("GET", "acr/v1/team/app/_tags"));
        assertEquals("repository:app:metadata_read",
                AcrTokenService.scopeFor("GET", "acr/v1/app/_manifests/sha256:abc"));
        assertEquals("repository:app:metadata_write", AcrTokenService.scopeFor("PATCH", "acr/v1/app/_tags/v1"));
        assertEquals("repository:app:delete", AcrTokenService.scopeFor("DELETE", "acr/v1/app"));
        assertEquals("repository:app:delete", AcrTokenService.scopeFor("DELETE", "acr/v1/app/_tags/v1"));
    }

    @Test
    void pathsThatNameNoRegistryResourceHaveNoScope() {
        assertNull(AcrTokenService.scopeFor("GET", "v2/app"));
        assertNull(AcrTokenService.scopeFor("GET", "acr/v1/_unknown"));
        assertNull(AcrTokenService.scopeFor("GET", "oauth2/token"));
        assertNull(AcrTokenService.scopeFor("GET", null));
    }
}
