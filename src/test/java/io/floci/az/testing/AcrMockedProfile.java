package io.floci.az.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Runs Azure Container Registry mocked: registries are ARM state with no registry container behind
 * them ({@code floci-az.services.acr.mocked=true}; the default starts one).
 *
 * <p>Used by {@code AcrHandlerTest}, {@code AcrTokenEndpointsTest}, {@code AcrGlobalNameTest} and
 * {@code UserAssignedIdentityRuleTest}.
 *
 * <p>Quarkus builds one application per distinct profile class during test discovery and keeps
 * each for the rest of the test JVM, so classes that need the same overrides share this profile
 * rather than each declaring their own: every extra profile costs every CI run about 4 seconds.
 * Add a class here only when it needs exactly these overrides; a class that needs one more
 * setting gets its own profile.
 */
public class AcrMockedProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci-az.services.acr.mocked", "true");
    }
}
