package io.floci.az.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Runs Azure Container Instances in real mode: a container group starts Docker containers
 * ({@code floci-az.services.aci.mocked=false}; the default is mocked).
 *
 * <p>Used by {@code AciDockerTest}, against a real Docker daemon, and {@code AciActionFailureTest},
 * which replaces the container manager to make its actions fail.
 *
 * <p>Quarkus builds one application per distinct profile class during test discovery and keeps
 * each for the rest of the test JVM, so classes that need the same overrides share this profile
 * rather than each declaring their own: every extra profile costs every CI run about 4 seconds.
 * Add a class here only when it needs exactly these overrides; a class that needs one more
 * setting gets its own profile.
 */
public class AciContainersProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci-az.services.aci.mocked", "false");
    }
}
