package io.floci.az.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Runs every service whose default starts a container mocked, so a test that only needs ARM state
 * or the management plane starts no Docker container: ACR, AKS, Functions, MariaDB, MySQL,
 * PostgreSQL and Redis are pure state, and SQL serves no data plane. ACI, Container Apps and VMs
 * are mocked by default already.
 *
 * <p>One profile instead of one per service: each distinct profile costs a Quarkus app build and
 * start on every test run. Classes that share it share one app, so each keeps its own
 * subscriptions or resets state in {@code @BeforeEach}. A test that needs one of these services
 * unmocked, or a different SQL data-plane setting, keeps its own profile.
 */
public class NoDockerProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "floci-az.services.acr.mocked", "true",
            "floci-az.services.aks.mocked", "true",
            "floci-az.services.functions.mocked", "true",
            "floci-az.services.maria-db.mocked", "true",
            "floci-az.services.mysql.mocked", "true",
            "floci-az.services.postgres.mocked", "true",
            "floci-az.services.redis.mocked", "true",
            "floci-az.services.sql.data-plane.provider", "none");
    }
}
