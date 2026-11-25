package io.floci.az.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Runs Azure Database for PostgreSQL mocked: flexible servers are ARM state with no PostgreSQL
 * container behind them ({@code floci-az.services.postgres.mocked=true}; the default starts one).
 *
 * <p>Used by {@code PostgresHandlerMockedTest}, {@code PostgresEntraAuthTest},
 * {@code PostgresGlobalNameTest} and {@code PostgresResourceStoreTest}.
 *
 * <p>Quarkus builds one application per distinct profile class during test discovery and keeps
 * each for the rest of the test JVM, so classes that need the same overrides share this profile
 * rather than each declaring their own: every extra profile costs every CI run about 4 seconds.
 * Add a class here only when it needs exactly these overrides; a class that needs one more
 * setting gets its own profile.
 */
public class PostgresMockedProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci-az.services.postgres.mocked", "true");
    }
}
