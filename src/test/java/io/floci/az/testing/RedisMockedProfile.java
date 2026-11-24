package io.floci.az.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Runs Azure Cache for Redis mocked: caches are ARM state with no Redis container behind them
 * ({@code floci-az.services.redis.mocked=true}; the default starts one).
 *
 * <p>Used by {@code RedisHandlerTest}, {@code RedisGlobalNameTest}, {@code PolicyInsightsTest} and
 * {@code PolicyPatchEvaluationTest}; the two Policy classes create caches as policy targets.
 *
 * <p>Quarkus builds one application per distinct profile class during test discovery and keeps
 * each for the rest of the test JVM, so classes that need the same overrides share this profile
 * rather than each declaring their own: every extra profile costs every CI run about 4 seconds.
 * Add a class here only when it needs exactly these overrides; a class that needs one more
 * setting gets its own profile.
 */
public class RedisMockedProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci-az.services.redis.mocked", "true");
    }
}
