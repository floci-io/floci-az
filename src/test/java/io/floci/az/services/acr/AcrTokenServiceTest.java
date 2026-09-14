package io.floci.az.services.acr;

import io.floci.az.services.acr.AcrTokenService.Access;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
