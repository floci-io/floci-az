package io.floci.az.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Runs virtual machines in real mode: a VM is backed by a Docker container
 * ({@code floci-az.services.vm.mocked=false}; the default is mocked).
 *
 * <p>Used by {@code VmDockerTest}, against a real Docker daemon, and
 * {@code VmHandlerContainerStartFailureTest}, which replaces the container manager to make the
 * start fail.
 *
 * <p>Quarkus builds one application per distinct profile class during test discovery and keeps
 * each for the rest of the test JVM, so classes that need the same overrides share this profile
 * rather than each declaring their own: every extra profile costs every CI run about 4 seconds.
 * Add a class here only when it needs exactly these overrides; a class that needs one more
 * setting gets its own profile.
 */
public class VmContainersProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci-az.services.vm.mocked", "false");
    }
}
