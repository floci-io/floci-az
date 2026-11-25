package io.floci.az.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Checks client secrets against the Entra directory
 * ({@code floci-az.services.entra.validate-client-credentials=true}; by default a secret is accepted
 * as sent).
 *
 * <p>Used by {@code ClientCredentialValidationTest} and {@code GraphApplicationsCredentialTest}.
 *
 * <p>Quarkus builds one application per distinct profile class during test discovery and keeps
 * each for the rest of the test JVM, so classes that need the same overrides share this profile
 * rather than each declaring their own: every extra profile costs every CI run about 4 seconds.
 * Add a class here only when it needs exactly these overrides; a class that needs one more
 * setting gets its own profile.
 */
public class ClientCredentialValidationProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci-az.services.entra.validate-client-credentials", "true");
    }
}
