package io.floci.az.services.cosmos.engine;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the master {@code cosmos.mocked} switch forces engine containers off: even with a
 * container-backed API explicitly enabled, {@code getOrStart} returns empty and starts no Docker.
 */
@QuarkusTest
@TestProfile(CosmosLifecycleManagerMockedTest.MockedProfile.class)
@DisplayName("CosmosLifecycleManager — mocked mode skips engine containers")
@SuppressWarnings("unused")
class CosmosLifecycleManagerMockedTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.services.cosmos.mocked", "true",
                    // mongodb enabled — mocked must still win and start no container
                    "floci-az.services.cosmos.engines.mongodb.enabled", "true");
        }
    }

    @Inject CosmosLifecycleManager lifecycleManager;

    @Test
    @DisplayName("getOrStart returns empty for an enabled engine when mocked")
    void getOrStartEmptyWhenMocked() {
        Optional<CosmosConnectionInfo> info = lifecycleManager.getOrStart(CosmosApi.MONGODB);
        assertTrue(info.isEmpty(), "mocked cosmos must not start an engine container");
    }
}
